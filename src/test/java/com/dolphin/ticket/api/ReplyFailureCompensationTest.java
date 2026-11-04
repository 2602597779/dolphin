package com.dolphin.ticket.api;

import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.TicketLockRegistry;
import com.dolphin.ticket.service.TicketRoundService;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段4优化 O4:图执行异常的失败补偿回归。
 *
 * <p>客户消息已落库后图 invoke 抛异常,不能留下"有追问、无答复、状态 RUNNING"的脏中间态:
 * 必须补系统说明、转人工、写 reply-failed 审计,并返回 500 说明已转人工。
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
    private final TicketRoundService roundService =
            new TicketRoundService(conversationService, ticketService);
    private final TicketController controller = new TicketController(
            compiler, ticketService, approvalService, conversationService, checkpointStore,
            new TicketLockRegistry(), roundService, auditLogger, humanTicketPool);

    @Test
    void graphFailureCompensatesWithAgentReplyEscalationAndAudit() throws Exception {
        when(ticketService.getForCustomer(TICKET_ID, CUSTOMER_ID)).thenReturn(Optional.of(
                new TicketService.Ticket(TICKET_ID, "实例连不上", TicketService.STATUS_RUNNING, CUSTOMER_ID)));
        when(conversationService.transcript(TICKET_ID)).thenReturn("客户: 实例连不上");
        when(compiler.compile()).thenThrow(new IllegalStateException("graph boom"));

        ResponseEntity<Map<String, Object>> response = controller.reply(TICKET_ID,
                new TicketController.ReplyRequest("补充:重启后还是连不上", "req-1"), asCustomer());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat((String) response.getBody().get("error")).contains("已转人工跟进");

        // 客户消息保留(证据),随后补系统说明 + 转人工
        verify(conversationService).appendUser(TICKET_ID, "补充:重启后还是连不上", "req-1");
        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_RUNNING);
        verify(conversationService).appendAgent(TICKET_ID, "系统繁忙,您的诉求已记录,将转人工跟进。");
        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_ESCALATED);
        verify(humanTicketPool).add(eq(TICKET_ID), anyString(), contains("graph boom"));
        verify(auditLogger).log(eq(TICKET_ID), eq("reply-failed"), contains("graph boom"));
    }

    private MockHttpServletRequest asCustomer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR, CUSTOMER_ID);
        return request;
    }
}
