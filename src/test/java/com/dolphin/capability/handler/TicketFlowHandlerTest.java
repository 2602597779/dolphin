package com.dolphin.capability.handler;

import com.dolphin.router.DialogContext;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.TicketGraphRunner;
import com.dolphin.ticket.service.TicketService;
import com.dolphin.ticket.service.TicketStatusService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天链路异步化回归:受理/执行分离 + 跨渠道审批闸门 + 投递拒绝兜底。
 *
 * <p>覆盖(图执行的收尾/补偿语义由 TicketGraphRunnerTest 覆盖,此处聚焦聊天侧):
 * <ol>
 *   <li>提交受理 → 落对话线程 + 翻 PROCESSING + 异步投递 + 粘性乐观预置;</li>
 *   <li>提交投递被拒 → runner 已补偿转人工,聊天侧清粘性如实告知;</li>
 *   <li>审批赢家 → 闸门通过 + 决策落库 + resume 投递,粘性保持等「进展」收尾;</li>
 *   <li>审批输家 → 不落决策不投递,按工单实际状态(CLOSED)如实告知并清粘性;</li>
 *   <li>审批投递被拒 → 决策已落库,清粘性告知转人工;</li>
 *   <li>进展查询(执行中/待审批)→ 状态如实回复,粘性保持;</li>
 *   <li>进展查询(已结单)→ 终稿回复,粘性清除。</li>
 * </ol>
 */
class TicketFlowHandlerTest {

    private static final String TICKET_ID = "T-CHAT";
    private static final String SESSION_ID = "s-1";

    private final TicketService ticketService = mock(TicketService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final TicketGraphRunner graphRunner = mock(TicketGraphRunner.class);
    private final TicketStatusService statusService = mock(TicketStatusService.class);
    private final ConversationService conversationService = mock(ConversationService.class);

    private TicketFlowHandler handler() {
        return new TicketFlowHandler(ticketService, approvalService, auditLogger,
                graphRunner, statusService, conversationService);
    }

    /** 粘性在途会话上下文 */
    private DialogContext pendingContext() {
        DialogContext ctx = new DialogContext(SESSION_ID);
        ctx.setActiveCapability("ticket-flow");
        ctx.setPendingTicketId(TICKET_ID);
        return ctx;
    }

    /** 构造 TicketStatusService.view 的状态视图 */
    private Map<String, Object> view(String status, String finalReply, String pendingAction) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("finalReply", finalReply);
        m.put("pendingAction", pendingAction);
        return m;
    }

    @Test
    void submitAcceptsEnqueuesAndPresetsSticky() {
        when(ticketService.create(anyString())).thenReturn(TICKET_ID);
        when(graphRunner.enqueueSubmitRound(TICKET_ID, "数据库连不上了")).thenReturn(true);

        DialogContext ctx = new DialogContext(SESSION_ID);
        String reply = handler().handle("数据库连不上了", ctx);

        // 受理语义与 REST 对齐:落对话线程 + 翻 PROCESSING + 异步投递,粘性乐观预置
        verify(conversationService).appendUser(TICKET_ID, "数据库连不上了");
        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_PROCESSING);
        verify(graphRunner).enqueueSubmitRound(TICKET_ID, "数据库连不上了");
        assertThat(reply).contains(TICKET_ID).contains("已受理");
        assertThat(ctx.getPendingTicketId()).isEqualTo(TICKET_ID);
        assertThat(ctx.getActiveCapability()).isEqualTo("ticket-flow");
    }

    @Test
    void submitEnqueueRejectedClearsStickyAndReportsEscalation() {
        when(ticketService.create(anyString())).thenReturn(TICKET_ID);
        when(graphRunner.enqueueSubmitRound(anyString(), anyString())).thenReturn(false);

        DialogContext ctx = new DialogContext(SESSION_ID);
        String reply = handler().handle("数据库连不上了", ctx);

        // 投递被拒:runner 已就地补偿转人工,聊天侧清粘性如实告知
        assertThat(reply).contains(TICKET_ID).contains("已转人工跟进");
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }

    @Test
    void approvalReplyWinningGateRecordsDecisionAndEnqueuesResume() {
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)).thenReturn(true);
        when(graphRunner.enqueueApprovalResume(TICKET_ID, true)).thenReturn(true);

        DialogContext ctx = pendingContext();
        String reply = handler().handle("同意", ctx);

        // 闸门先行,决策只在赢家处落库;resume 投递异步,粘性保持等「进展」查询收尾
        verify(ticketService).tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING);
        verify(approvalService).recordDecision(eq(TICKET_ID), eq(true), eq("会话:" + SESSION_ID), anyString());
        verify(auditLogger).log(TICKET_ID, "chat-approval", "approved");
        verify(graphRunner).enqueueApprovalResume(TICKET_ID, true);
        assertThat(reply).startsWith("已批准,").contains("正在执行");
        assertThat(ctx.getPendingTicketId()).isEqualTo(TICKET_ID);
        assertThat(ctx.getActiveCapability()).isEqualTo("ticket-flow");
    }

    @Test
    void approvalReplyLosingGateSkipsDecisionAndReportsActualState() {
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)).thenReturn(false);
        when(statusService.view(TICKET_ID))
                .thenReturn(view(TicketService.STATUS_CLOSED, "处置动作已执行完成,工单已结单。", null));

        DialogContext ctx = pendingContext();
        String reply = handler().handle("同意", ctx);

        // 输家不落决策、不写审批审计、不投递;按实际状态(CLOSED)如实告知并清粘性
        verify(approvalService, never()).recordDecision(anyString(), anyBoolean(), anyString(), anyString());
        verify(auditLogger, never()).log(anyString(), anyString(), anyString());
        verify(graphRunner, never()).enqueueApprovalResume(anyString(), anyBoolean());
        assertThat(reply).contains("已完成").contains("处置动作已执行完成,工单已结单。");
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }

    @Test
    void approvalResumeEnqueueRejectedClearsSticky() {
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)).thenReturn(true);
        when(graphRunner.enqueueApprovalResume(TICKET_ID, true)).thenReturn(false);

        DialogContext ctx = pendingContext();
        String reply = handler().handle("同意", ctx);

        // 决策已落库(闸门赢家),投递被拒:runner 已补偿转人工,聊天侧清粘性告知
        verify(approvalService).recordDecision(eq(TICKET_ID), eq(true), anyString(), anyString());
        assertThat(reply).startsWith("已批准,").contains("已转人工跟进");
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }

    @Test
    void progressQueryOnProcessingTicketKeepsSticky() {
        when(statusService.view(TICKET_ID)).thenReturn(view(TicketService.STATUS_PROCESSING, null, null));

        DialogContext ctx = pendingContext();
        String reply = handler().handle("进展如何", ctx);

        assertThat(reply).contains("正在处理中");
        assertThat(ctx.getPendingTicketId()).isEqualTo(TICKET_ID);
        assertThat(ctx.getActiveCapability()).isEqualTo("ticket-flow");
    }

    @Test
    void progressQueryOnPendingApprovalShowsAction() {
        when(statusService.view(TICKET_ID))
                .thenReturn(view(TicketService.STATUS_PENDING_APPROVAL, null, "restartInstance:i-abc123"));

        DialogContext ctx = pendingContext();
        String reply = handler().handle("现在怎么样了", ctx);

        assertThat(reply).contains("等待审批").contains("restartInstance");
        assertThat(ctx.getPendingTicketId()).isEqualTo(TICKET_ID);
    }

    @Test
    void progressQueryOnClosedTicketReturnsFinalReplyAndClearsSticky() {
        when(statusService.view(TICKET_ID))
                .thenReturn(view(TicketService.STATUS_CLOSED, "处置动作已执行完成,工单已结单。", null));

        DialogContext ctx = pendingContext();
        String reply = handler().handle("进展", ctx);

        assertThat(reply).contains("已完成").contains("处置动作已执行完成,工单已结单。");
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }
}
