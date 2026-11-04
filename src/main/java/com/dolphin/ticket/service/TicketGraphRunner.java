package com.dolphin.ticket.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.orchestration.state.TicketState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 工单图异步执行器(阶段 O8):受理即 202 后,图在独立线程池跑完并收尾。
 *
 * <p>三类轮次,受理链路(Controller)已完成校验与受理落库(状态已翻转为 PROCESSING):
 * <ul>
 *   <li><b>提交</b>:首轮 invoke,只传工单号与原始诉求;</li>
 *   <li><b>追问</b>:清旧断点后从头重跑(对话历史注入,阶段4 语义不变);</li>
 *   <li><b>审批续跑</b>:从 DB checkpoint 水合状态后 resume(决策已由受理链路落 ApprovalService)。</li>
 * </ul>
 *
 * <p>收尾语义(与同步时代的差异仅一处):结单轮的终态意图经 {@link TicketState#FINAL_STATUS}
 * 传递,与终稿在收尾短事务里原子落库——轮询端看到 CLOSED 时终稿必已可读(先落状态会有
 * done=true 却读不到终稿的竞态窗口)。转人工轮仍由图节点自行落 ESCALATED,收尾传 null
 * 不覆盖(避免盖掉异步质检结论);待审批时补写 PENDING_APPROVAL。
 *
 * <p>异步质检接线点(阶段 O8 移入本类):结单轮收尾事务提交后调度
 * {@link QualityReviewService#reviewAsync}——先调度会有 REJECT 重开的 ESCALATED
 * 被收尾 CLOSED 覆盖的竞态。
 *
 * <p>失败补偿(超时/异常兜底):图异常、收尾落库异常或线程池拒绝时,补系统说明 + 转人工 +
 * 审计留痕,工单绝不停留在 PROCESSING;卡死场景(进程崩溃等)由 SLA 响应计时器兜底
 * (PROCESSING 计入"未响应"口径,超时自动升级人工池)。
 *
 * <p>同单串行保证:受理闸门(条件状态翻转)保证同一工单最多只有一个在途轮次,
 * 线程消息按整轮交替,不会出现半轮穿插;不同工单的轮次在线程池并行。
 */
@Component
public class TicketGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(TicketGraphRunner.class);

    /** 失败补偿的系统说明文案(与同步时代 reply 失败补偿一致) */
    private static final String COMPENSATION_REPLY = "系统繁忙,您的诉求已记录,将转人工跟进。";

    private final TicketGraphCompiler compiler;
    private final TicketCheckpointStore checkpointStore;
    private final TicketRoundService roundService;
    private final HumanTicketPool humanTicketPool;
    private final AuditLogger auditLogger;
    private final TicketStatusService statusService;
    private final TicketEventPublisher eventPublisher;
    private final QualityReviewService qualityReviewService;
    private final Executor executor;

    public TicketGraphRunner(TicketGraphCompiler compiler,
                             TicketCheckpointStore checkpointStore,
                             TicketRoundService roundService,
                             HumanTicketPool humanTicketPool,
                             AuditLogger auditLogger,
                             TicketStatusService statusService,
                             TicketEventPublisher eventPublisher,
                             QualityReviewService qualityReviewService,
                             @Qualifier("ticketGraphExecutor") Executor executor) {
        this.compiler = compiler;
        this.checkpointStore = checkpointStore;
        this.roundService = roundService;
        this.humanTicketPool = humanTicketPool;
        this.auditLogger = auditLogger;
        this.statusService = statusService;
        this.eventPublisher = eventPublisher;
        this.qualityReviewService = qualityReviewService;
        this.executor = executor;
    }

    /** 提交轮次(首轮):受理后异步 invoke 图 */
    public boolean enqueueSubmitRound(String ticketId, String message) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(TicketState.TICKET_ID, ticketId);
        inputs.put(TicketState.RAW_MESSAGE, message);
        return tryEnqueue(ticketId, "submit-failed", "AI 受理处理异常",
                () -> runInvokeRound(ticketId, inputs, "submit-failed", "AI 受理处理异常"));
    }

    /** 追问轮次(阶段4 语义):清旧断点后带对话历史从头重跑 */
    public boolean enqueueReplyRound(String ticketId, String message, String history) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(TicketState.TICKET_ID, ticketId);
        inputs.put(TicketState.RAW_MESSAGE, message);
        if (history != null && !history.isBlank()) {
            inputs.put(TicketState.CONVERSATION_HISTORY, history);
        }
        return tryEnqueue(ticketId, "reply-failed", "AI 追问处理异常",
                () -> {
                    // 清旧断点:reply 是从头重跑而非续跑,否则会直接 resume 到上一轮中断点
                    checkpointStore.clear(ticketId);
                    runInvokeRound(ticketId, inputs, "reply-failed", "AI 追问处理异常");
                });
    }

    /** 审批续跑轮次:从 DB checkpoint 水合本单状态后 resume(决策已先行落库) */
    public boolean enqueueApprovalResume(String ticketId, boolean approved) {
        return tryEnqueue(ticketId, "approve-failed", "AI 审批续跑异常",
                () -> runApprovalResume(ticketId, approved));
    }

    /**
     * 投递轮次任务;线程池拒绝(仅进程关闭期可能)时就地补偿并返回 false,
     * 调用方以 503 应答(工单已转人工,不会停留在 PROCESSING)。
     */
    private boolean tryEnqueue(String ticketId, String failureAction, String poolReason, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.error("[graph-runner] 任务投递被拒绝,就地补偿转人工 ticketId={}", ticketId, e);
            compensate(ticketId, failureAction, poolReason, "线程池拒绝任务: " + e.getMessage());
            return false;
        }
    }

    /** 提交/追问共用的 invoke 轮次 */
    private void runInvokeRound(String ticketId, Map<String, Object> inputs,
                                String failureAction, String poolReason) {
        RunnableConfig config = RunnableConfig.builder().threadId(ticketId).build();
        OverAllState state;
        try {
            // P0 并发根治:每请求独立编译,图模板不跨请求共享
            CompiledGraph graph = compiler.compile();
            Optional<OverAllState> result = graph.invoke(inputs, config);
            state = result.orElse(null);
        } catch (Exception e) {
            log.error("工单图执行失败 ticketId={}", ticketId, e);
            compensate(ticketId, failureAction, poolReason, e.getMessage());
            return;
        }
        finishRound(ticketId, state, failureAction, poolReason);
    }

    /** 审批续跑:水合 → updateState → resume(与同步时代 approve 完全同款语义) */
    private void runApprovalResume(String ticketId, boolean approved) {
        RunnableConfig config = RunnableConfig.builder().threadId(ticketId).build();
        OverAllState state;
        try {
            // 新图模板为空,先从 DB checkpoint 水合本单状态(checkpoint 只用于确定续跑起点)
            CompiledGraph graph = compiler.compile();
            graph.overAllState().input(graph.getState(config).state().data());
            graph.updateState(config, Map.of(TicketState.APPROVED, approved), null);
            graph.resume(new OverAllState.HumanFeedback(Map.of("approved", approved), null), config);
            // resume 同步跑完后模板即本单终态,终稿/执行结果从模板读取
            state = graph.overAllState();
        } catch (Exception e) {
            log.error("工单审批续跑失败 ticketId={}", ticketId, e);
            compensate(ticketId, "approve-failed", "AI 审批续跑异常", e.getMessage());
            return;
        }
        finishRound(ticketId, state, "approve-failed", "AI 审批续跑异常");
    }

    /**
     * 轮次收尾:解读图状态并落库。
     * <ul>
     *   <li>有终稿 → 终稿入线程;结单轮的终态意图(FINAL_STATUS=CLOSED)与终稿同一事务
     *       原子落库,转人工轮(FINAL_STATUS 空,节点已落 ESCALATED)传 null 不覆盖;</li>
     *   <li>无终稿但有待审批动作 → 补 PENDING_APPROVAL,草稿入线程作为首次答复;</li>
     *   <li>两者皆无 → 框架级异常(图未产出任何结论),按失败补偿兜底;</li>
     *   <li>收尾落库自身异常 → 同样补偿,工单不留在 PROCESSING。</li>
     * </ul>
     */
    private void finishRound(String ticketId, OverAllState state, String failureAction, String poolReason) {
        String pendingAction = state == null ? "" : TicketState.str(state, TicketState.PENDING_ACTION, "");
        String finalReply = state == null ? null : TicketState.str(state, TicketState.FINAL_REPLY, null);
        String replyDraft = state == null ? null : TicketState.str(state, TicketState.REPLY_DRAFT, null);
        String finalStatus = state == null ? null : TicketState.str(state, TicketState.FINAL_STATUS, null);
        try {
            if (finalReply != null && !finalReply.isBlank()) {
                roundService.completeRound(ticketId, finalStatus, finalReply);
            } else if (!pendingAction.isBlank()) {
                roundService.completeRound(ticketId, TicketService.STATUS_PENDING_APPROVAL, replyDraft);
            } else {
                compensate(ticketId, failureAction, poolReason, "图执行完成但未产出终稿与待审批动作");
                return;
            }
        } catch (Exception e) {
            log.error("工单轮次收尾落库失败 ticketId={}", ticketId, e);
            compensate(ticketId, failureAction, poolReason, "收尾落库异常: " + e.getMessage());
            return;
        }
        // 异步质检:结单轮收尾事务提交后调度(CLOSED 与终稿已原子可见;
        // 先调度会有 REJECT 重开的 ESCALATED 被收尾 CLOSED 覆盖的竞态)
        if (TicketService.STATUS_CLOSED.equals(finalStatus)) {
            qualityReviewService.reviewAsync(ticketId, finalReply);
        }
        publishResult(ticketId);
    }

    /**
     * 失败补偿:系统说明 + 转人工 + 审计,任何一步失败都不上抛(不掩盖原始异常),
     * 最后仍发布完成事件(SSE 订阅者与轮询客户端能看到 ESCALATED 终态)。
     */
    private void compensate(String ticketId, String failureAction, String poolReason, String cause) {
        try {
            roundService.completeRound(ticketId, TicketService.STATUS_ESCALATED, COMPENSATION_REPLY);
        } catch (Exception e) {
            log.error("失败补偿写系统答复异常 ticketId={}", ticketId, e);
        }
        try {
            humanTicketPool.add(ticketId, poolReason, "图执行异常: " + cause);
        } catch (Exception e) {
            log.error("失败补偿转人工异常 ticketId={}", ticketId, e);
        }
        try {
            auditLogger.log(ticketId, failureAction, "图执行异常:" + cause);
        } catch (Exception e) {
            log.error("失败补偿审计写入异常 ticketId={}", ticketId, e);
        }
        publishResult(ticketId);
    }

    /** 发布本轮完成事件(SSE);无订阅者为空操作,异常不影响主链路 */
    private void publishResult(String ticketId) {
        try {
            eventPublisher.publishResult(ticketId, statusService.view(ticketId));
        } catch (Exception e) {
            log.warn("[graph-runner] 完成事件发布异常 ticketId={}: {}", ticketId, e.getMessage());
        }
    }
}
