package com.dolphin.ticket.api;

import com.dolphin.customer.CustomerSeeder;
import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.infra.audit.AuditLogEntity;
import com.dolphin.ticket.infra.audit.AuditLogRepository;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段4 工单多轮对话回归:reply 续跑 + 对话线程全链路。
 *
 * <p>验证点:
 * <ol>
 *   <li>多轮续跑:PENDING_APPROVAL 单追问后按最新上下文从头重跑(改口安全组 → 免审批直执结单);</li>
 *   <li>线程完整:客户/系统消息按序追加,每轮都入线程可回看;</li>
 *   <li>状态约束:CLOSED 拒绝追问(409)、ESCALATED 只记录不重跑、空消息 400;</li>
 *   <li>租户隔离:跨客户 reply/messages 均 404。</li>
 * </ol>
 *
 * <p>环境:offline profile + H2 内存库,直调 Controller(绕过 HTTP 层,鉴权属性手工注入)。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-multiturn;DB_CLOSE_DELAY=-1",
        "dolphin.rag.vector-store-path=target/dolphin-multiturn-vector-store.json"
})
@ActiveProfiles("offline")
class MultiTurnConversationTest {

    @Autowired
    private TicketController ticketController;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private CustomerSeeder customerSeeder;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void seedCustomers() {
        customerSeeder.run(); // 幂等:表空才灌
    }

    @Test
    void pendingTicket_replyRerunsFromScratchAndThreadKeepsAllRounds() {
        // 第 1 轮:连不上 → 诊断出 restartInstance(需审批)→ 中断在审批前
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        assertThat(ticketService.get(id).orElseThrow().status())
                .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
        assertThat(conversationService.messages(id)).hasSize(2); // 客户诉求 + 首次答复草稿

        // 第 2 轮:客户改口"其实是安全组没放行 443" → 按本轮诉求从头重跑
        // → authorizeSecurityGroup 免审批直执 → 结单(而非沿用上轮的重启审批断点)
        Map<String, Object> body = body(ticketController.reply(id,
                new TicketController.ReplyRequest("其实是安全组没放行443端口,帮我开一下"),
                asCustomer("cust-001")));
        assertThat(body.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat((Integer) body.get("rounds")).isEqualTo(4); // 2 客户 + 2 系统

        // 线程按序完整:客户诉求/系统答复交替,第 2 轮答复对应本轮安全组诉求
        var messages = conversationService.messages(id);
        assertThat(messages).extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER", "AGENT");
        assertThat(messages.get(2).content()).contains("安全组");
        assertThat(messages.get(3).content()).isNotBlank();
    }

    @Test
    void closedTicket_replyRejectedWith409() {
        String id = submit("cust-001", "SLB 健康检查不通过是什么原因"); // 咨询类,无动作直执结单
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_CLOSED);

        ResponseEntity<Map<String, Object>> response = ticketController.reply(id,
                new TicketController.ReplyRequest("还有个问题"), asCustomer("cust-001"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("error", "工单已结单,如需继续处理请重新提交: " + id);
        // 线程不被污染:追问未入线程
        assertThat(conversationService.messages(id)).hasSize(2);
    }

    @Test
    void escalatedTicket_replyRecordedOnlyWithoutAiRerun() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        ticketService.updateStatus(id, TicketService.STATUS_ESCALATED); // 模拟已转人工

        Map<String, Object> body = body(ticketController.reply(id,
                new TicketController.ReplyRequest("补充:重启之后还是连不上"), asCustomer("cust-001")));
        assertThat(body.get("status")).isEqualTo(TicketService.STATUS_ESCALATED);
        assertThat((String) body.get("note")).contains("转人工");

        // 追问只入线程供专员查看:不触发 AI 重跑(无系统侧新消息)、状态不翻转
        var messages = conversationService.messages(id);
        assertThat(messages).extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER");
        assertThat(messages.get(2).content()).contains("重启之后还是连不上");
        assertThat(ticketService.get(id).orElseThrow().status()).isEqualTo(TicketService.STATUS_ESCALATED);
    }

    @Test
    void reply_blankMessageRejectedWith400() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        assertThat(ticketController.reply(id, new TicketController.ReplyRequest("  "),
                asCustomer("cust-001")).getStatusCode().value()).isEqualTo(400);
        assertThat(ticketController.reply(id, new TicketController.ReplyRequest(null),
                asCustomer("cust-001")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void replyAndMessages_enforceTenantIsolation() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");

        // 跨客户追问/看线程:404,不泄露工单存在性
        assertThat(ticketController.reply(id, new TicketController.ReplyRequest("我也问问"),
                asCustomer("cust-002")).getStatusCode().value()).isEqualTo(404);
        assertThat(ticketController.messages(id, asCustomer("cust-002"))
                .getStatusCode().value()).isEqualTo(404);

        // 本人可看线程:角色/内容/时间戳齐全
        ResponseEntity<?> response = ticketController.messages(id, asCustomer("cust-001"));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("ticketStatus")).isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
        assertThat((Integer) body.get("count")).isEqualTo(2);
        var msgs = (java.util.List<?>) body.get("messages");
        assertThat(msgs).allSatisfy(m -> {
            Map<?, ?> msg = (Map<?, ?>) m;
            assertThat(msg.containsKey("role")).isTrue();
            assertThat(msg.containsKey("content")).isTrue();
            assertThat(msg.containsKey("createdAt")).isTrue();
        });
        assertThat(msgs.get(0)).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) msgs.get(0)).get("role")).isEqualTo("USER");
    }

    @Test
    void pendingTicket_replyInvalidatesPreviousApprovalWithAudit() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        assertThat(ticketService.get(id).orElseThrow().status())
                .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);

        // O3:追问会清旧断点,旧待审批动作作废必须留痕(含原动作,审批人旧链接失效可追溯)
        ticketController.reply(id, new TicketController.ReplyRequest("再补充:重启之后仍然连不上"),
                asCustomer("cust-001"));

        List<AuditLogEntity> invalidated = auditLogRepository.findByAction("approval-invalidated")
                .stream().filter(e -> id.equals(e.getSessionId())).toList();
        assertThat(invalidated).hasSize(1);
        assertThat(invalidated.get(0).getDetail())
                .contains("原待审批动作作废", "restartInstance:i-abc123");
    }

    @Test
    void replyWithSameRequestIdIsIdempotent() {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        String requestId = "req-idem-1";

        // O7:首次追问按最新诉求改道安全组并结单
        Map<String, Object> first = body(ticketController.reply(id,
                new TicketController.ReplyRequest("其实是安全组没放行443端口,帮我开一下", requestId),
                asCustomer("cust-001")));
        assertThat(first.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
        int roundsAfterFirst = conversationService.messages(id).size();
        assertThat(roundsAfterFirst).isEqualTo(4);

        // 网络重试同一 requestId:返回 duplicated 标记,不重复落消息、不重复跑图
        Map<String, Object> retry = body(ticketController.reply(id,
                new TicketController.ReplyRequest("其实是安全组没放行443端口,帮我开一下", requestId),
                asCustomer("cust-001")));
        assertThat(retry).containsEntry("duplicated", true);
        assertThat(retry.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat((Integer) retry.get("rounds")).isEqualTo(roundsAfterFirst);
        assertThat(conversationService.messages(id)).hasSize(roundsAfterFirst);
    }

    @Test
    void concurrentRepliesOnSameTicketAreSerialized() throws Exception {
        String id = submit("cust-001", "我的ECS实例连不上了,ssh 都登不上");
        int baseMessages = conversationService.messages(id).size();

        // O1:两线程同时追问同一单,锁内串行执行;线程消息必须按整轮交替,不能出现半轮穿插
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map<String, Object>>>> futures = List.of(
                executor.submit(() -> replyAfter(start, id, "补充:实例还是连不上", "concurrent-1")),
                executor.submit(() -> replyAfter(start, id, "再补充:重启之后仍然连不上", "concurrent-2")));
        start.countDown();

        for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
            ResponseEntity<Map<String, Object>> response = future.get(15, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat((String) response.getBody().get("status"))
                    .isEqualTo(TicketService.STATUS_PENDING_APPROVAL);
        }
        executor.shutdown();

        var messages = conversationService.messages(id);
        assertThat(messages).hasSize(baseMessages + 4);
        assertThat(messages).extracting(ConversationService.Message::role)
                .containsExactly("USER", "AGENT", "USER", "AGENT", "USER", "AGENT");
        String userContents = messages.stream()
                .filter(m -> "USER".equals(m.role()))
                .map(ConversationService.Message::content)
                .reduce("", (a, b) -> a + "|" + b);
        assertThat(userContents)
                .contains("补充:实例还是连不上", "再补充:重启之后仍然连不上");
    }

    // ---- 辅助 ----

    private ResponseEntity<Map<String, Object>> replyAfter(CountDownLatch start, String id,
                                                           String message, String requestId) throws InterruptedException {
        start.await();
        return ticketController.reply(id, new TicketController.ReplyRequest(message, requestId),
                asCustomer("cust-001"));
    }

    private String submit(String customerId, String message) {
        Map<String, Object> body = body(ticketController.submit(
                new TicketController.SubmitRequest(message), asCustomer(customerId)));
        return (String) body.get("ticketId");
    }

    private MockHttpServletRequest asCustomer(String customerId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR, customerId);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody();
    }
}
