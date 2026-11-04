package com.dolphin.ticket.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.orchestration.state.TicketState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 O8 异步执行器的收尾分支单测:同步 Executor 内联执行,免 Spring 上下文。
 *
 * <p>覆盖:
 * <ol>
 *   <li>结单轮(终稿 + FINAL_STATUS)→ 终态与终稿同一事务落库,收尾后调度异步质检;</li>
 *   <li>转人工轮(终稿但无 FINAL_STATUS,节点已落 ESCALATED)→ 状态不覆盖、不质检;</li>
 *   <li>待审批 → 补 PENDING_APPROVAL,草稿入线程;</li>
 *   <li>图无结论(终稿与待审批动作皆空)→ 按失败补偿兜底,工单不留在 PROCESSING;</li>
 *   <li>线程池拒绝 → 就地补偿并返回 false(受理方应答 503)。</li>
 * </ol>
 */
class TicketGraphRunnerTest {

    private static final String TICKET_ID = "T-ASYNC";

    private final TicketGraphCompiler compiler = mock(TicketGraphCompiler.class);
    private final TicketCheckpointStore checkpointStore = mock(TicketCheckpointStore.class);
    private final TicketRoundService roundService = mock(TicketRoundService.class);
    private final HumanTicketPool humanTicketPool = mock(HumanTicketPool.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final TicketStatusService statusService = mock(TicketStatusService.class);
    private final TicketEventPublisher eventPublisher = mock(TicketEventPublisher.class);
    private final QualityReviewService qualityReviewService = mock(QualityReviewService.class);

    private TicketGraphRunner runner(Executor executor) {
        return new TicketGraphRunner(compiler, checkpointStore, roundService, humanTicketPool,
                auditLogger, statusService, eventPublisher, qualityReviewService, executor);
    }

    @Test
    void closedRoundCommitsFinalStatusWithReplyThenSchedulesQualityReview() throws Exception {
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                TicketState.FINAL_REPLY, "已为您结单,重启完成。",
                TicketState.FINAL_STATUS, TicketService.STATUS_CLOSED))));

        assertThat(runner(Runnable::run).enqueueSubmitRound(TICKET_ID, "实例连不上")).isTrue();

        // 结单轮:终态意图与终稿同一事务原子落库(轮询端看到 CLOSED 时终稿必已可读)
        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_CLOSED, "已为您结单,重启完成。");
        // 收尾事务提交后调度异步质检(先调度会有 REJECT 重开被 CLOSED 覆盖的竞态)
        verify(qualityReviewService).reviewAsync(TICKET_ID, "已为您结单,重启完成。");
        // 完成事件照常发布(SSE/快照可见)
        verify(eventPublisher).publishResult(eq(TICKET_ID), anyMap());
        // 正常收尾不转人工、不写失败审计
        verify(humanTicketPool, never()).add(anyString(), anyString(), anyString());
        verify(auditLogger, never()).log(anyString(), anyString(), anyString());
    }

    @Test
    void escalatedRoundKeepsNodeStatusAndSkipsQualityReview() throws Exception {
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        // 转人工轮:escalate 节点已自行落 ESCALATED,状态无 FINAL_STATUS 意图
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(
                new OverAllState(Map.of(TicketState.FINAL_REPLY, "该工单已转人工专员处理。"))));

        assertThat(runner(Runnable::run).enqueueSubmitRound(TICKET_ID, "帮我删库")).isTrue();

        // 终稿入线程;状态传 null 不覆盖(节点已落 ESCALATED,也避免盖掉异步质检结论)
        verify(roundService).completeRound(TICKET_ID, null, "该工单已转人工专员处理。");
        // 转人工轮不质检(质检只审查结单终稿)
        verify(qualityReviewService, never()).reviewAsync(anyString(), anyString());
        verify(eventPublisher).publishResult(eq(TICKET_ID), anyMap());
    }

    @Test
    void pendingActionRoundMarksPendingApprovalWithDraftReply() throws Exception {
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState(Map.of(
                TicketState.REPLY_DRAFT, "需重启实例,是否批准?",
                TicketState.PENDING_ACTION, "restartInstance:i-abc123"))));

        assertThat(runner(Runnable::run).enqueueReplyRound(TICKET_ID, "还是连不上", "客户: 实例连不上"))
                .isTrue();

        // 追问轮次先清旧断点再重跑
        verify(checkpointStore).clear(TICKET_ID);
        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_PENDING_APPROVAL, "需重启实例,是否批准?");
        verify(eventPublisher).publishResult(eq(TICKET_ID), anyMap());
    }

    @Test
    void graphWithoutOutcomeIsCompensatedInsteadOfStuckProcessing() throws Exception {
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        // 图正常返回但既无终稿也无待审批动作:框架级异常,必须补偿兜底
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(new OverAllState()));

        assertThat(runner(Runnable::run).enqueueSubmitRound(TICKET_ID, "实例连不上")).isTrue();

        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_ESCALATED,
                "系统繁忙,您的诉求已记录,将转人工跟进。");
        verify(humanTicketPool).add(eq(TICKET_ID), eq("AI 受理处理异常"),
                contains("未产出终稿与待审批动作"));
        verify(auditLogger).log(eq(TICKET_ID), eq("submit-failed"),
                contains("未产出终稿与待审批动作"));
        // 补偿后仍发布完成事件:轮询/SSE 客户端能看到 ESCALATED 终态
        verify(eventPublisher).publishResult(eq(TICKET_ID), anyMap());
    }

    @Test
    void emptyInvokeResultIsCompensatedAsFailure() throws Exception {
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.empty());

        assertThat(runner(Runnable::run).enqueueReplyRound(TICKET_ID, "还是连不上", null)).isTrue();

        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_ESCALATED,
                "系统繁忙,您的诉求已记录,将转人工跟进。");
        verify(auditLogger).log(eq(TICKET_ID), eq("reply-failed"), contains("图执行异常"));
    }

    @Test
    void rejectedExecutionCompensatesAndReportsFailure() {
        assertThat(runner(task -> {
            throw new RejectedExecutionException("shutdown");
        }).enqueueApprovalResume(TICKET_ID, true)).isFalse();

        verify(roundService).completeRound(TICKET_ID, TicketService.STATUS_ESCALATED,
                "系统繁忙,您的诉求已记录,将转人工跟进。");
        verify(humanTicketPool).add(eq(TICKET_ID), eq("AI 审批续跑异常"), contains("线程池拒绝任务"));
        verify(auditLogger).log(eq(TICKET_ID), eq("approve-failed"), contains("线程池拒绝任务"));
        // 未跑图,不发布正常完成语义之外的状态翻转(completeRound 已落 ESCALATED)
        verify(eventPublisher).publishResult(eq(TICKET_ID), anyMap());
    }

    @Test
    void historyIsInjectedIntoReplyRoundInputs() throws Exception {
        CompiledGraph graph = mock(CompiledGraph.class);
        when(compiler.compile()).thenReturn(graph);
        when(graph.invoke(anyMap(), any(RunnableConfig.class))).thenReturn(Optional.of(
                new OverAllState(Map.of(TicketState.FINAL_REPLY, "已结单"))));

        runner(Runnable::run).enqueueReplyRound(TICKET_ID, "还是连不上", "客户: 实例连不上\n客服: 诊断中");

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(graph).invoke(captor.capture(), any(RunnableConfig.class));
        assertThat(captor.getValue())
                .containsEntry(TicketState.TICKET_ID, TICKET_ID)
                .containsEntry(TicketState.RAW_MESSAGE, "还是连不上")
                .containsEntry(TicketState.CONVERSATION_HISTORY, "客户: 实例连不上\n客服: 诊断中");
        // 提交轮次(无历史)不注入 CONVERSATION_HISTORY 由下式间接保证:历史为空不传
        verify(checkpointStore).clear(TICKET_ID);
    }
}
