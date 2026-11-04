package com.dolphin.ticket.api;

import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.TicketAsyncProperties;
import com.dolphin.ticket.service.TicketEventPublisher;
import com.dolphin.ticket.service.TicketGraphRunner;
import com.dolphin.ticket.service.TicketRoundService;
import com.dolphin.ticket.service.TicketService;
import com.dolphin.ticket.service.TicketStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段4优化 O4 + 阶段 O8:图执行异常的失败补偿回归。
 *
 * <p>客户消息已受理(202)后图 invoke 抛异常,不能留下"有追问、无答复、状态 PROCESSING"的
 * 脏中间态:必须补系统说明、转人工、写 reply-failed 审计;线程池拒绝时受理返回 503
 * 且工单同样转人工。本测试用同步 Executor(Runnable::run)让异步任务在受理调用内联执行,
 * 保证断言确定性。
 */
class ReplyFailureCompensationTest {

    private static final String TICKET_ID = "T-FAIL";
    private static final String CUSTOMER_ID = "cust-001";

    private final TicketGraphCompiler compiler = mock(TicketGraphCompiler.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final TicketCheckpointStore checkpointStore = mock(TicketCheckpointStore.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final HumanTicketPool humanTicketPool = mock(HumanTicketPool.class);

    /** 同步 Executor:enqueue 即执行,补偿在受理返回前完成,断言无竞态 */
    private final TicketController controller = controller(Runnable::run);

    /** 拒绝一切任务的 Executor:模拟线程池关闭期,受理应答 503 且工单转人工 */
    private final TicketController rejectingController = controller(task -> {
        throw new RejectedExecutionException("pool shutdown");
    });

    private TicketController controller(Executor executor) {
        TicketRoundService roundService = new TicketRoundService(conversationService, ticketService);
        TicketStatusService statusService =
                new TicketStatusService(ticketService, conversationService, checkpointStore);
        TicketGraphRunner runner = new TicketGraphRunner(compiler, checkpointStore, roundService,
                humanTicketPool, auditLogger, statusService, new TicketEventPublisher(),
                mock(com.dolphin.ticket.service.QualityReviewService.class), executor);
        return new TicketController(ticketService, approvalService, conversationService,
                checkpointStore, roundService, auditLogger, runner, statusService,
                new TicketEventPublisher(), new TicketAsyncProperties());
    }

    @Test
    void graphFailureCompensatesWithAgentReplyEscalationAndAudit() {
        when(ticketService.getForCustomer(TICKET_ID, CUSTOMER_ID)).thenReturn(Optional.of(
                new TicketService.Ticket(TICKET_ID, "实例连不上", TicketService.STATUS_RUNNING, CUSTOMER_ID)));
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_RUNNING,
                TicketService.STATUS_PROCESSING)).thenReturn(true);
        when(ticketService.get(TICKET_ID)).thenReturn(Optional.of(
                new TicketService.Ticket(TICKET_ID, "实例连不上", TicketService.STATUS_ESCALATED, CUSTOMER_ID)));
        when(conversationService.transcript(TICKET_ID)).thenReturn("客户: 实例连不上");
        when(compiler.compile()).thenThrow(new IllegalStateException("graph boom"));

        ResponseEntity<Map<String, Object>> response = controller.reply(TICKET_ID,
                new TicketController.ReplyRequest("补充:重启后还是连不上", "req-1"), asCustomer());

        // 受理即 202:失败不体现为 HTTP 错误,由补偿与状态视图暴露
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("accepted", true);

        // 受理闸门先翻转 PROCESSING,客户消息保留(证据),随后补系统说明 + 转人工
        verify(ticketService).tryTransition(TICKET_ID, TicketService.STATUS_RUNNING,
                TicketService.STATUS_PROCESSING);
        verify(conversationService).appendUser(TICKET_ID, "补充:重启后还是连不上", "req-1");
        verify(conversationService).appendAgent(TICKET_ID, "系统繁忙,您的诉求已记录,将转人工跟进。");
        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_ESCALATED);
        verify(humanTicketPool).add(eq(TICKET_ID), anyString(), contains("graph boom"));
        verify(auditLogger).log(eq(TICKET_ID), eq("reply-failed"), contains("graph boom"));
    }

    @Test
    void queueRejectionRespondsWith503AndCompensates() {
        when(ticketService.getForCustomer(TICKET_ID, CUSTOMER_ID)).thenReturn(Optional.of(
                new TicketService.Ticket(TICKET_ID, "实例连不上", TicketService.STATUS_RUNNING, CUSTOMER_ID)));
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_RUNNING,
                TicketService.STATUS_PROCESSING)).thenReturn(true);
        when(ticketService.get(TICKET_ID)).thenReturn(Optional.of(
                new TicketService.Ticket(TICKET_ID, "实例连不上", TicketService.STATUS_ESCALATED, CUSTOMER_ID)));
        when(conversationService.transcript(TICKET_ID)).thenReturn("");

        ResponseEntity<Map<String, Object>> response = rejectingController.reply(TICKET_ID,
                new TicketController.ReplyRequest("补充:重启后还是连不上", "req-2"), asCustomer());

        // 线程池拒绝:受理失败应答 503,工单已就地补偿转人工(不停留在 PROCESSING)
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_ESCALATED);
        verify(humanTicketPool).add(eq(TICKET_ID), anyString(), contains("线程池拒绝任务"));
        verify(auditLogger).log(eq(TICKET_ID), eq("reply-failed"), contains("线程池拒绝任务"));
    }

    private MockHttpServletRequest asCustomer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR, CUSTOMER_ID);
        return request;
    }
}
