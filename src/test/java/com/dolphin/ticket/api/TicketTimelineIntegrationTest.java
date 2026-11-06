package com.dolphin.ticket.api;

import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 全链路可见化验收:工单时间线 API 的端到端断言。
 *
 * <p>三条真实链路(offline 桩剧本,零外部依赖):
 * <ol>
 *   <li>故障单全闭环:提交 → 审批中断 → 批准 → 执行重启(基线/验证)→ 结单,
 *       时间线 nodePath 完整、events 含 BASELINE/VERIFY、决策为批准;</li>
 *   <li>拒绝单:驳回后执行器令牌门禁拒绝,nodePath 终点为 escalateHuman,决策为拒绝;</li>
 *   <li>咨询单:kbAnswer 直达结单,nodePath 不含诊断/审批/执行节点。</li>
 * </ol>
 *
 * <p>环境同并发回归:H2 内存库 + 加速 sim 延迟/验证轮询,异步图执行经轮询等待。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dolphin-timeline;DB_CLOSE_DELAY=-1",
        "dolphin.sim.restart-latency=PT0.5S",
        "dolphin.verify.poll-interval=PT0.2S",
        "dolphin.rag.vector-store-path=target/dolphin-timeline-vector-store.json"
})
@ActiveProfiles("offline")
class TicketTimelineIntegrationTest {

    private static final long JOIN_TIMEOUT_SECONDS = 60;

    /** 在途状态(与状态视图一致的"未完成"口径) */
    private static final Set<String> IN_FLIGHT = Set.of(
            TicketService.STATUS_NEW, TicketService.STATUS_RUNNING, TicketService.STATUS_PROCESSING);

    /** 重启剧本:命中 offline 桩的 restartInstance(需审批)剧本 */
    private static final String RESTART_MESSAGE = "ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复";

    /** 咨询剧本:命中 SLB 知识问答(无写操作,直接结单) */
    private static final String CONSULT_MESSAGE = "SLB 健康检查一直不通过是什么原因,该如何排查?";

    @Autowired
    private TicketController ticketController;

    @Autowired
    private TicketTimelineController timelineController;

    @Autowired
    private TicketService ticketService;

    /** 场景一:故障单批准闭环——完整节点路径 + 基线验证事件 + 批准决策 */
    @Test
    void approvedFaultTicket_timelineShowsFullChain() {
        String id = submit(RESTART_MESSAGE);
        awaitStatus(id, TicketService.STATUS_PENDING_APPROVAL);
        approve(id, true);
        awaitStatus(id, TicketService.STATUS_CLOSED);

        Map<String, Object> body = timeline(id);

        assertThat(body.get("ticketId")).isEqualTo(id);
        Map<String, Object> ticket = bodyMap(body, "ticket");
        assertThat(ticket.get("status")).isEqualTo(TicketService.STATUS_CLOSED);
        assertThat(String.valueOf(ticket.get("rawMessage"))).contains("i-abc123");
        assertThat(ticket.get("createdAt")).isNotNull();
        assertThat(ticket.get("updatedAt")).isNotNull();

        // 节点路径按发生顺序完整还原(approval 在 interruptBefore 恢复后进入一次)
        assertThat(bodyList(body, "nodePath")).containsExactly(
                "triage", "diagnosis", "reply", "approval", "execute", "followup");

        List<Map<String, Object>> events = bodyEventList(body, "events");
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e -> assertThat(e.get("id")).isNotNull());
        // 执行器的基线采样与验证结果都落时间线,且相位归类为 verify
        assertThat(actionNames(events)).anyMatch(a -> a.endsWith(":BASELINE"))
                .anyMatch(a -> a.endsWith(":VERIFY"));
        assertThat(events).anyMatch(e -> "verify".equals(e.get("phase")));
        // 节点轨迹相位归类为 node
        assertThat(events).anyMatch(e -> "node".equals(e.get("phase"))
                && String.valueOf(e.get("action")).startsWith("graph:"));

        List<Map<String, Object>> decisions = bodyEventList(body, "decisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("approved")).isEqualTo(true);
        assertThat(decisions.get(0).get("approver")).isEqualTo("timeline-test");
        assertThat(decisions.get(0).get("decidedAt")).isNotNull();
    }

    /** 场景二:拒绝单——令牌门禁拒绝,路径终点为转人工 */
    @Test
    void rejectedFaultTicket_timelineEndsAtEscalate() {
        String id = submit(RESTART_MESSAGE);
        awaitStatus(id, TicketService.STATUS_PENDING_APPROVAL);
        approve(id, false);
        awaitStatus(id, TicketService.STATUS_ESCALATED);

        Map<String, Object> body = timeline(id);

        List<String> nodePath = bodyList(body, "nodePath");
        assertThat(nodePath).containsExactly(
                "triage", "diagnosis", "reply", "approval", "execute", "escalateHuman");

        List<Map<String, Object>> decisions = bodyEventList(body, "decisions");
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).get("approved")).isEqualTo(false);
    }

    /** 场景三:咨询单——kbAnswer 直达结单,不经过诊断/审批/执行 */
    @Test
    void consultTicket_timelineSkipsFaultNodes() {
        String id = submit(CONSULT_MESSAGE);
        awaitStatus(id, TicketService.STATUS_CLOSED);

        Map<String, Object> body = timeline(id);

        assertThat(bodyList(body, "nodePath")).containsExactly(
                "triage", "kbAnswer", "reply", "followup");
        assertThat(bodyEventList(body, "decisions")).isEmpty();
    }

    /** 提交工单(直调传 Mock 请求=内部渠道,不走 HTTP 鉴权),返回工单号 */
    private String submit(String message) {
        ResponseEntity<Map<String, Object>> response = ticketController
                .submit(new TicketController.SubmitRequest(message), new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return String.valueOf(response.getBody().get("ticketId"));
    }

    /** 审批工单(批准/拒绝),返回 202 受理 */
    private void approve(String ticketId, boolean approved) {
        ResponseEntity<Map<String, Object>> response = ticketController.approve(ticketId,
                new TicketController.ApproveRequest(approved, "timeline-test", "时间线回归自动审批"),
                new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    /** 查询时间线,断言 200 并返回响应体 */
    private Map<String, Object> timeline(String ticketId) {
        ResponseEntity<Map<String, Object>> response = timelineController.timeline(ticketId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** 轮询等待工单到达期望状态(异步图执行在后台线程池完成) */
    private void awaitStatus(String ticketId, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(JOIN_TIMEOUT_SECONDS);
        String status = null;
        while (System.nanoTime() < deadline) {
            status = ticketService.get(ticketId).map(TicketService.Ticket::status).orElse(null);
            if (expected.equals(status)) {
                return;
            }
            // 防御:进入错误终态立即失败,不做无谓等待
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyMap(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> bodyList(Map<String, Object> body, String key) {
        return (List<String>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bodyEventList(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    private static List<String> actionNames(List<Map<String, Object>> events) {
        return events.stream().map(e -> String.valueOf(e.get("action"))).toList();
    }
}
