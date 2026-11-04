package com.dolphin.ticket.api;

import com.dolphin.customer.CustomerSeeder;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.TicketEventPublisher;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 O8 异步受理集成回归:受理即 202 + 轮询/SSE。
 *
 * <p>用可开关的闸门 Executor 替换图执行线程池,把"HTTP 受理"与"图执行"两个阶段
 * 在时间上拉开,确定性验证异步契约:
 * <ol>
 *   <li>submit 只受理不跑图:立即 202 + 状态 PROCESSING + 客户消息已落库,响应毫秒级;</li>
 *   <li>在途互斥:上一轮 PROCESSING 期间,同单新 reply / 并发第二个 approve 均被 409 拒绝,
 *       线程消息不穿插;本轮终态后下一轮受理正常,线程按 USER,AGENT 整轮交替;</li>
 *   <li>轮询:done + finalReply/pendingAction 口径;跨租户 404;</li>
 *   <li>SSE:连接即推 status 快照(PROCESSING),图完成后推 result 事件并结束连接。</li>
 * </ol>
 *
 * <p>环境:offline profile + H2 内存库;MockMvc 走完整 HTTP 链路(含 API-Key 过滤器)验证 SSE。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-async;DB_CLOSE_DELAY=-1",
        "dolphin.rag.vector-store-path=target/dolphin-async-vector-store.json"
}, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("offline")
class AsyncTicketAcceptanceTest {

    private static final String CUSTOMER_ID = "cust-001";
    private static final String API_KEY = CustomerSeeder.DEMO_KEY_ALPHA;

    /** 在途状态(轮询等待的"未完成"口径,与状态视图一致) */
    private static final Set<String> IN_FLIGHT = Set.of(
            TicketService.STATUS_NEW, TicketService.STATUS_RUNNING, TicketService.STATUS_PROCESSING);

    @MockitoBean(name = "ticketGraphExecutor")
    private ExecutorService graphExecutor;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TicketController ticketController;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private TicketEventPublisher eventPublisher;
    @Autowired
    private CustomerSeeder customerSeeder;

    /** 图执行闸门:关=受理后任务挂起(线程不跑),开=任务在独立线程执行 */
    private volatile CountDownLatch gate;

    @BeforeEach
    void setUp() {
        customerSeeder.run(); // 幂等:表空才灌
        newGate();
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            CompletableFuture.runAsync(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            });
            return null;
        }).when(graphExecutor).execute(any(Runnable.class));
    }

    private void newGate() {
        gate = new CountDownLatch(1);
    }

    private void releaseGate() {
        gate.countDown();
    }

    @Test
    void submitReturns202ImmediatelyThenPollRevealsResult() {
        long start = System.currentTimeMillis();
        ResponseEntity<Map<String, Object>> response = ticketController.submit(
                new TicketController.SubmitRequest("SLB 健康检查不通过是什么原因"), asCustomer());
        long elapsed = System.currentTimeMillis() - start;

        // 受理即 202:闸门未开(图完全没跑),受理返回仍是毫秒级——HTTP 线程不被图执行占用
        assertThat(elapsed).isLessThan(2000);
        Map<String, Object> body = body(response);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(body.get("status")).isEqualTo(TicketService.STATUS_PROCESSING);
        assertThat(body.get("accepted")).isEqualTo(true);
        assertThat((String) body.get("poll")).isEqualTo("/api/tickets/" + body.get("ticketId") + "/status");
        assertThat((String) body.get("events")).isEqualTo("/api/tickets/" + body.get("ticketId") + "/events");

        String id = (String) body.get("ticketId");
        // 受理已落库:状态 PROCESSING + 客户诉求入线程,不等图执行
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_PROCESSING);
        assertThat(conversationService.count(id)).isEqualTo(1);

        // 释放闸门,图执行完成(咨询剧本:无动作直执结单)
        releaseGate();
        awaitStatus(id, TicketService.STATUS_CLOSED);

        // 轮询:done + finalReply,等待期间 done=false 由在途断言覆盖(见下一个用例)
        Map<String, Object> view = statusView(id);
        assertThat(view.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat((Boolean) view.get("done")).isTrue();
        assertThat((Boolean) view.get("processing")).isFalse();
        // 咨询剧本终稿:线程最新一条客服消息 = SLB 健康检查答复(结单语义由状态位表达)
        assertThat(String.valueOf(view.get("finalReply"))).contains("健康检查");
        assertThat(view.get("pendingAction")).isNull();
    }

    @Test
    void inFlightRoundRejectsConcurrentReplyThenNextRoundAcceptedAfterDone() {
        String id = submitGated("我的ECS实例连不上了,ssh 都登不上");
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_PROCESSING);

        // 在途:同单第二条追问被拒(409),不落消息、不排队
        ResponseEntity<Map<String, Object>> rejected = ticketController.reply(id,
                new TicketController.ReplyRequest("其实是安全组没放行443端口,帮我开一下"), asCustomer());
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(body(rejected).get("error"))).contains("正在处理上一轮请求");
        assertThat(conversationService.count(id)).isEqualTo(1);

        // 轮询视角:在途时 done=false、processing=true
        Map<String, Object> inFlightView = statusView(id);
        assertThat((Boolean) inFlightView.get("done")).isFalse();
        assertThat((Boolean) inFlightView.get("processing")).isTrue();
        assertThat(inFlightView.get("finalReply")).isNull();

        // 释放闸门:本轮跑完进入审批中断
        releaseGate();
        awaitStatus(id, TicketService.STATUS_PENDING_APPROVAL);
        Map<String, Object> pendingView = statusView(id);
        assertThat((Boolean) pendingView.get("done")).isTrue(); // 本轮已有结论(等待人工审批)
        assertThat(String.valueOf(pendingView.get("pendingAction"))).contains("restartInstance");
        assertThat(String.valueOf(pendingView.get("finalReply"))).contains("审批");

        // 下一轮受理正常(上一轮已终态),改道安全组剧本结单
        ResponseEntity<Map<String, Object>> next = ticketController.reply(id,
                new TicketController.ReplyRequest("其实是安全组没放行443端口,帮我开一下"), asCustomer());
        assertThat(next.getStatusCode().value()).isEqualTo(202);
        assertThat(body(next).get("status")).isEqualTo(TicketService.STATUS_PROCESSING);
        awaitStatus(id, TicketService.STATUS_CLOSED);

        // 同单消息顺序保证:整轮交替,无半轮穿插
        assertThat(conversationService.messages(id))
                .extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER", "AGENT");
    }

    @Test
    void concurrentSecondApproveIsRejectedWhileFirstIsInFlight() {
        String id = submitGated("我的ECS实例连不上了,ssh 都登不上");
        releaseGate();
        awaitStatus(id, TicketService.STATUS_PENDING_APPROVAL);

        // 第一笔审批受理:重新关闸,审批续跑被挂起
        newGate();
        ResponseEntity<Map<String, Object>> first = ticketController.approve(id,
                new TicketController.ApproveRequest(true, "approver-1", "同意重启"), asCustomer());
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(body(first).get("status")).isEqualTo(TicketService.STATUS_PROCESSING);

        // 并发第二笔审批:状态已是 PROCESSING,直接拒绝
        ResponseEntity<Map<String, Object>> second = ticketController.approve(id,
                new TicketController.ApproveRequest(false, "approver-2", "我反对"), asCustomer());
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(body(second).get("error"))).contains("不允许审批");

        releaseGate();
        awaitStatus(id, TicketService.STATUS_CLOSED);
        Map<String, Object> view = statusView(id);
        assertThat(String.valueOf(view.get("finalReply"))).contains("已向云平台提交实例 i-abc123");
    }

    @Test
    void sseStreamPushesSnapshotAndResultEvents() throws Exception {
        String id = submitGated("SLB 健康检查不通过是什么原因");

        // 连接即推快照:图被闸门挂起,快照应为 PROCESSING
        MvcResult mvcResult = mockMvc.perform(get("/api/tickets/{id}/events", id)
                        .header("X-API-Key", API_KEY))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 图完成后:发布 result 事件并结束连接
        releaseGate();
        awaitStatus(id, TicketService.STATUS_CLOSED);
        mvcResult.getAsyncResult(5000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"processing\":true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:result")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"CLOSED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"done\":true")));
        // 终态完成:订阅清理,不泄漏
        assertThat(eventPublisher.subscriberCount(id)).isZero();
    }

    @Test
    void sseSnapshotOnLateConnectionCompletesImmediatelyWhenAlreadyDone() throws Exception {
        String id = submitGated("SLB 健康检查不通过是什么原因");
        releaseGate();
        awaitStatus(id, TicketService.STATUS_CLOSED);

        // 完成后才连接:快照即终态,连接立即结束(不挂到超时)
        MvcResult mvcResult = mockMvc.perform(get("/api/tickets/{id}/events", id)
                        .header("X-API-Key", API_KEY))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult(5000);
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"CLOSED\"")));
        assertThat(eventPublisher.subscriberCount(id)).isZero();
    }

    @Test
    void statusEndpointIsTenantIsolated() throws Exception {
        String id = submitGated("SLB 健康检查不通过是什么原因");
        releaseGate();
        awaitStatus(id, TicketService.STATUS_CLOSED);

        // 跨租户:404,不泄露存在性(事件接口同款校验)
        mockMvc.perform(get("/api/tickets/{id}/status", id)
                        .header("X-API-Key", CustomerSeeder.DEMO_KEY_BETA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tickets/{id}/events", id)
                        .header("X-API-Key", CustomerSeeder.DEMO_KEY_BETA))
                .andExpect(status().isNotFound());
    }

    // ---- 辅助 ----

    /** 闸门关闭状态下提交(图任务挂起),断言受理即 202 并返回工单号 */
    private String submitGated(String message) {
        ResponseEntity<Map<String, Object>> response = ticketController.submit(
                new TicketController.SubmitRequest(message), asCustomer());
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(body(response).get("status")).isEqualTo(TicketService.STATUS_PROCESSING);
        return (String) body(response).get("ticketId");
    }

    /** 轮询状态视图(O8 客户端同款口径) */
    @SuppressWarnings("unchecked")
    private Map<String, Object> statusView(String ticketId) {
        ResponseEntity<?> response = ticketController.status(ticketId, asCustomer());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (Map<String, Object>) response.getBody();
    }

    /** 轮询等待工单到达期望状态;进入其他终态立即失败 */
    private void awaitStatus(String ticketId, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String status = null;
        while (System.nanoTime() < deadline) {
            status = ticketService.get(ticketId).map(TicketService.Ticket::status).orElse(null);
            if (expected.equals(status)) {
                return;
            }
            if (status != null && !IN_FLIGHT.contains(status)) {
                break;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("等待工单状态超时/不符 ticketId=" + ticketId
                + " expected=" + expected + " actual=" + status);
    }

    private MockHttpServletRequest asCustomer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.dolphin.ticket.auth.ApiKeyAuthFilter.CUSTOMER_ID_ATTR, CUSTOMER_ID);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody();
    }
}
