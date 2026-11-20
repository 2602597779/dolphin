package com.dolphin.capability.handler;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.dolphin.router.DialogContext;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.QualityReviewService;
import com.dolphin.ticket.service.TicketRoundService;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天链路一致性回归:跨渠道审批闸门 + 失败补偿(与 REST 链路同款语义)。
 *
 * <p>覆盖:
 * <ol>
 *   <li>闸门输家(REST 已审批/已结单/重复提交)→ 不落决策、不续跑、清粘性;</li>
 *   <li>闸门赢家 → 决策落库 + resume 续跑 + 结单质检,粘性清除;</li>
 *   <li>审批续跑异常 → 补偿三步(系统说明/转人工/approve-failed 审计),工单不悬空;</li>
 *   <li>提交图异常 → 补偿三步(submit-failed 审计),不再"回退 NEW 石沉大海";</li>
 *   <li>图无结论 → 同样补偿兜底。</li>
 * </ol>
 */
class TicketFlowHandlerTest {

    private static final String TICKET_ID = "T-CHAT";
    private static final String SESSION_ID = "s-1";
    private static final String COMPENSATION_REPLY = "系统繁忙,您的诉求已记录,将转人工跟进。";

    private final TicketGraphCompiler compiler = mock(TicketGraphCompiler.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final QualityReviewService qualityReviewService = mock(QualityReviewService.class);
    private final TicketRoundService roundService = mock(TicketRoundService.class);
    private final HumanTicketPool humanTicketPool = mock(HumanTicketPool.class);

    private TicketFlowHandler handler() {
        return new TicketFlowHandler(compiler, ticketService, approvalService, auditLogger,
                qualityReviewService, roundService, humanTicketPool);
    }

    /** 粘性待审批会话上下文 */
    private DialogContext pendingContext() {
        DialogContext ctx = new DialogContext(SESSION_ID);
        ctx.setActiveCapability("ticket-flow");
        ctx.setPendingTicketId(TICKET_ID);
        return ctx;
    }

    @Test
    void approvalReplyLosingGateSkipsDecisionAndClearsSticky() {
        // REST 渠道已赢下闸门(PENDING_APPROVAL→PROCESSING),聊天侧再发「同意」应为输家
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)).thenReturn(false);

        DialogContext ctx = pendingContext();
        String reply = handler().handle("同意", ctx);

        assertThat(reply).contains("已不在待审批状态").contains("未生效");
        // 输家不落决策、不写审批审计、不碰图
        verify(approvalService, never()).recordDecision(anyString(), anyBoolean(), anyString(), anyString());
        verify(auditLogger, never()).log(anyString(), anyString(), anyString());
        verify(compiler, never()).compile();
        // 粘性清除,会话回到自由路由
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }

    @Test
    void approvalReplyWinningGateRecordsDecisionResumesAndCloses() throws Exception {
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)).thenReturn(true);
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        // mock 模板无 key strategies,input() 水合不生效——模板与 checkpoint 快照预置同一份
        // 终稿数据(对 merge/replace/过滤三种语义都稳健,同 TicketGraphRunnerTest 做法)
        Map<String, Object> finalState = Map.of(
                TicketState.FINAL_REPLY, "处置动作已执行完成,工单已结单。",
                TicketState.FINAL_STATUS, TicketService.STATUS_CLOSED);
        when(graph.overAllState()).thenReturn(new OverAllState(finalState));
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(snapshot.state()).thenReturn(new OverAllState(finalState));
        when(graph.getState(any(RunnableConfig.class))).thenReturn(snapshot);

        DialogContext ctx = pendingContext();
        String reply = handler().handle("同意", ctx);

        // 闸门先行,决策只在赢家处落库
        verify(ticketService).tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING);
        verify(approvalService).recordDecision(eq(TICKET_ID), eq(true), eq("会话:" + SESSION_ID), anyString());
        verify(auditLogger).log(TICKET_ID, "chat-approval", "approved");
        // 续跑终稿落 CLOSED + 异步质检,粘性清除
        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_CLOSED);
        verify(qualityReviewService).reviewAsync(TICKET_ID, "处置动作已执行完成,工单已结单。");
        assertThat(reply).startsWith("已批准,").contains("处置动作已执行完成");
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }

    @Test
    void approvalResumeFailureCompensatesInsteadOfDropping() throws Exception {
        when(ticketService.tryTransition(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)).thenReturn(true);
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        when(graph.overAllState()).thenReturn(new OverAllState());
        when(graph.getState(any(RunnableConfig.class))).thenThrow(new IllegalStateException("checkpoint missing"));

        DialogContext ctx = pendingContext();
        String reply = handler().handle("同意", ctx);

        // 决策已落库(闸门赢家),续跑异常走补偿三步,工单不悬空
        verify(approvalService).recordDecision(eq(TICKET_ID), eq(true), anyString(), anyString());
        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_ESCALATED, COMPENSATION_REPLY);
        verify(humanTicketPool).add(eq(TICKET_ID), eq("AI 审批续跑异常"), contains("checkpoint missing"));
        verify(auditLogger).log(eq(TICKET_ID), eq("approve-failed"), contains("checkpoint missing"));
        assertThat(reply).startsWith("已批准,").contains("已转人工跟进");
        // 补偿后粘性清除
        assertThat(ctx.getPendingTicketId()).isNull();
        assertThat(ctx.getActiveCapability()).isNull();
    }

    @Test
    void submitGraphFailureCompensatesInsteadOfRollbackNew() {
        when(ticketService.create(anyString())).thenReturn(TICKET_ID);
        when(compiler.compile()).thenThrow(new IllegalStateException("model down"));

        DialogContext ctx = new DialogContext(SESSION_ID);
        String reply = handler().handle("数据库连不上了,帮我提个工单", ctx);

        verify(ticketService).updateStatus(TICKET_ID, TicketService.STATUS_RUNNING);
        // 不再回退 NEW(旧缺陷:失败后状态回退、无人工跟进、无审计)
        verify(ticketService, never()).updateStatus(anyString(), eq(TicketService.STATUS_NEW));
        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_ESCALATED, COMPENSATION_REPLY);
        verify(humanTicketPool).add(eq(TICKET_ID), eq("AI 受理处理异常"), contains("model down"));
        verify(auditLogger).log(eq(TICKET_ID), eq("submit-failed"), contains("model down"));
        assertThat(reply).contains(TICKET_ID).contains("已转人工跟进");
    }

    @Test
    void submitWithoutOutcomeCompensatesInsteadOfRollbackNew() throws Exception {
        when(ticketService.create(anyString())).thenReturn(TICKET_ID);
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        // 图正常返回但既无终稿也无待审批动作:框架级异常,补偿兜底
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState()));

        String reply = handler().handle("实例连不上,帮我提个工单", new DialogContext(SESSION_ID));

        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_ESCALATED, COMPENSATION_REPLY);
        verify(humanTicketPool).add(eq(TICKET_ID), eq("AI 受理处理异常"), contains("未产出终稿与待审批动作"));
        verify(auditLogger).log(eq(TICKET_ID), eq("submit-failed"), contains("未产出终稿与待审批动作"));
        verify(ticketService, never()).updateStatus(anyString(), eq(TicketService.STATUS_NEW));
        assertThat(reply).contains("已转人工跟进");
    }
}
