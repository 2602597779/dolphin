package com.dolphin.capability.handler;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityHandler;
import com.dolphin.router.DialogContext;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.QualityReviewService;
import com.dolphin.ticket.service.TicketRoundService;
import com.dolphin.ticket.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工单流程能力:创建工单并驱动既有多智能体图(分诊→诊断→回复→审批→执行→结单)。
 *
 * <p>会话内两段式交互:
 * <ol>
 *   <li>提交新工单 → 图停在 approval 前时,记录 pendingTicketId 并保持粘性;</li>
 *   <li>粘性期内回复「同意/拒绝」→ 受理闸门通过后记录决策并 resume 续跑,结单后清除状态。</li>
 * </ol>
 * 与 REST 接口 /api/tickets/{id}/approve 等价,实现聊天驱动的 HITL 闭环。
 *
 * <p>与 REST 链路对齐的两道一致性保障:
 * <ul>
 *   <li><b>跨渠道审批闸门</b>:决策受理前先 tryTransition(PENDING_APPROVAL→PROCESSING),
 *       与 REST approve 同款条件状态翻转——工单已被 REST 审批/已结单/已转人工时,
 *       聊天侧输家不落决策、不重复续跑,清除粘性后如实告知;</li>
 *   <li><b>失败补偿</b>:图异常或无结论时与 TicketGraphRunner 补偿同款三步——
 *       系统说明入线程 + 转人工 + 审计留痕,聊天失败不再"石沉大海"。</li>
 * </ul>
 */
@Component
public class TicketFlowHandler implements CapabilityHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketFlowHandler.class);

    private static final List<String> APPROVE_WORDS = List.of("同意", "批准", "确认", "通过", "执行");
    private static final List<String> REJECT_WORDS = List.of("拒绝", "不同意", "驳回", "取消");

    /** 失败补偿的系统说明文案(与 TicketGraphRunner 补偿文案一致) */
    private static final String COMPENSATION_REPLY = "系统繁忙,您的诉求已记录,将转人工跟进。";

    private final TicketGraphCompiler compiler;
    private final TicketService ticketService;
    private final ApprovalService approvalService;
    private final AuditLogger auditLogger;
    private final QualityReviewService qualityReviewService;
    private final TicketRoundService roundService;
    private final HumanTicketPool humanTicketPool;

    public TicketFlowHandler(TicketGraphCompiler compiler,
                             TicketService ticketService,
                             ApprovalService approvalService,
                             AuditLogger auditLogger,
                             QualityReviewService qualityReviewService,
                             TicketRoundService roundService,
                             HumanTicketPool humanTicketPool) {
        this.compiler = compiler;
        this.ticketService = ticketService;
        this.approvalService = approvalService;
        this.auditLogger = auditLogger;
        this.qualityReviewService = qualityReviewService;
        this.roundService = roundService;
        this.humanTicketPool = humanTicketPool;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                "ticket-flow", "故障工单",
                "报告云服务故障、请求处理问题,或需要提交/跟进工单(可能触发诊断与处置动作)",
                List.of("帮我提个工单,数据库连不上了", "实例 i-abc123 一直报 500", "我的服务挂了尽快处理"),
                List.of("工单", "故障", "报错", "挂了", "宕机", "无法访问", "无法登录", "超时", "500", "5xx", "限流"),
                List.of("帮我?(提|发|创建)一?个?工单"),
                List.of());
    }

    @Override
    public String handle(String message, DialogContext ctx) {
        // 分支一:粘性期内有待审批工单 → 按同意/拒绝话术驱动审批续跑
        if (ctx.getPendingTicketId() != null) {
            return handleApprovalReply(message, ctx);
        }
        // 分支二:新工单提交 → 跑图
        return submitAndRun(message, ctx);
    }

    /** 提交工单并同步执行图流程 */
    private String submitAndRun(String message, DialogContext ctx) {
        String ticketId = ticketService.create(message);
        ticketService.updateStatus(ticketId, TicketService.STATUS_RUNNING);
        RunnableConfig config = RunnableConfig.builder().threadId(ticketId).build();
        try {
            // P0 并发根治:每请求独立编译,与 REST 链路同款
            CompiledGraph graph = compiler.compile();
            Optional<OverAllState> result = graph.invoke(Map.of(
                    TicketState.TICKET_ID, ticketId,
                    TicketState.RAW_MESSAGE, message), config);
            return interpretState(ticketId, result.orElse(null), ctx,
                    "submit-failed", "AI 受理处理异常");
        } catch (Exception e) {
            log.error("[ticket-flow] 工单图执行失败 ticketId={}", ticketId, e);
            return compensate(ticketId, ctx, "submit-failed", "AI 受理处理异常", e.getMessage());
        }
    }

    /** 审批话术处理:受理闸门 → 记录决策 → updateState + resume(决策必须先落 ApprovalService) */
    private String handleApprovalReply(String message, DialogContext ctx) {
        String ticketId = ctx.getPendingTicketId();
        boolean approved = APPROVE_WORDS.stream().anyMatch(message::contains);
        boolean rejected = REJECT_WORDS.stream().anyMatch(message::contains);
        if (!approved && !rejected) {
            return "工单 " + ticketId + " 正在等待审批,请回复「同意」或「拒绝」,或发送「换个话题」放弃。";
        }

        // 跨渠道审批闸门(与 REST /approve 同款):仅当工单仍处于 PENDING_APPROVAL 才受理本次决策。
        // 输家(REST 已审批/已结单/已转人工/重复提交)不落决策、不重复续跑,清除粘性后如实告知。
        if (!ticketService.tryTransition(ticketId, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)) {
            clearFlow(ctx);
            return "工单 " + ticketId + " 已不在待审批状态(可能已通过其他渠道处理),本次回复未生效。";
        }

        approvalService.recordDecision(ticketId, approved,
                "会话:" + ctx.getSessionId(), "用户话术:" + message);
        auditLogger.log(ticketId, "chat-approval", approved ? "approved" : "rejected");
        // 决策已落库;前缀只陈述决策不预断执行结果(续跑终稿/补偿文案由 interpretState 给出)
        String verdict = approved ? "已批准," : "已驳回,";
        RunnableConfig config = RunnableConfig.builder().threadId(ticketId).build();
        try {
            // P0 并发根治:每请求独立编译;新图模板为空,先从 DB checkpoint 水合本单状态再续跑
            // (与 TicketController.approve 同款语义:节点 action 读的是图模板,checkpoint 只定续跑起点)
            CompiledGraph graph = compiler.compile();
            graph.overAllState().input(graph.getState(config).state().data());
            graph.updateState(config, Map.of(TicketState.APPROVED, approved), null);
            graph.resume(new OverAllState.HumanFeedback(Map.of("approved", approved), null), config);
            // resume 节点输出写在本请求独立模板上,终稿从模板读取
            Optional<OverAllState> resumed = Optional.of(graph.overAllState());
            return verdict + interpretState(ticketId, resumed.orElse(null), ctx,
                    "approve-failed", "AI 审批续跑异常");
        } catch (Exception e) {
            log.error("[ticket-flow] 审批续跑失败 ticketId={}", ticketId, e);
            return verdict + compensate(ticketId, ctx, "approve-failed", "AI 审批续跑异常", e.getMessage());
        }
    }

    /**
     * 解读图执行结果:有终稿→消费终态意图(FINAL_STATUS,阶段 O8 起结单节点不再直接落库)后
     * 清除流程状态返回终稿;停在审批前→记录 pendingTicketId 并提示;
     * 其余(异常中断/无结论)→失败补偿(转人工),与 REST 链路同款语义。
     */
    private String interpretState(String ticketId, OverAllState state, DialogContext ctx,
                                  String failureAction, String poolReason) {
        String finalReply = state == null ? null : TicketState.str(state, TicketState.FINAL_REPLY, null);
        String pendingAction = state == null ? "" : TicketState.str(state, TicketState.PENDING_ACTION, "");
        if (finalReply != null && !finalReply.isBlank()) {
            // 终态意图落库(结单轮=CLOSED;转人工轮节点已自行落 ESCALATED,此处为空跳过)
            String finalStatus = state == null ? null : TicketState.str(state, TicketState.FINAL_STATUS, null);
            if (finalStatus != null && !finalStatus.isBlank()) {
                ticketService.updateStatus(ticketId, finalStatus);
                if (TicketService.STATUS_CLOSED.equals(finalStatus)) {
                    qualityReviewService.reviewAsync(ticketId, finalReply);
                }
            }
            clearFlow(ctx);
            return finalReply;
        }
        if (!pendingAction.isBlank()) {
            ticketService.updateStatus(ticketId, TicketService.STATUS_PENDING_APPROVAL);
            ctx.setActiveCapability(descriptor().code());
            ctx.setPendingTicketId(ticketId);
            return "工单 %s 诊断完成,存在待审批处置动作[%s]。回复「同意」执行,或调用 POST /api/tickets/%s/approve 审批。"
                    .formatted(ticketId, pendingAction, ticketId);
        }
        // 无终稿也无待审批动作:框架级异常,补偿兜底(系统说明 + 转人工 + 审计),不回退 NEW
        return compensate(ticketId, ctx, failureAction, poolReason, "图执行完成但未产出终稿与待审批动作");
    }

    /**
     * 失败补偿(与 TicketGraphRunner 补偿同款三步):系统说明入线程 + 转人工 + 审计留痕,
     * 任何一步失败都不上抛(不掩盖原始异常)。
     */
    private String compensate(String ticketId, DialogContext ctx,
                              String failureAction, String poolReason, String cause) {
        clearFlow(ctx);
        try {
            roundService.completeRound(ticketId, TicketService.STATUS_ESCALATED, COMPENSATION_REPLY);
        } catch (Exception e) {
            log.error("[ticket-flow] 失败补偿写系统答复异常 ticketId={}", ticketId, e);
        }
        try {
            humanTicketPool.add(ticketId, poolReason, "图执行异常: " + cause);
        } catch (Exception e) {
            log.error("[ticket-flow] 失败补偿转人工异常 ticketId={}", ticketId, e);
        }
        try {
            auditLogger.log(ticketId, failureAction, "图执行异常:" + cause);
        } catch (Exception e) {
            log.error("[ticket-flow] 失败补偿审计写入异常 ticketId={}", ticketId, e);
        }
        return "工单 " + ticketId + " 处理失败,已转人工跟进。";
    }

    /** 流程结束:清除粘性与待审批状态,会话回到自由路由 */
    private void clearFlow(DialogContext ctx) {
        ctx.setActiveCapability(null);
        ctx.setPendingTicketId(null);
    }
}
