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
import com.dolphin.ticket.service.QualityReviewService;
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
 *   <li>粘性期内回复「同意/拒绝」→ 记录决策并 resume 续跑,结单后清除状态。</li>
 * </ol>
 * 与 REST 接口 /api/tickets/{id}/approve 等价,实现聊天驱动的 HITL 闭环。
 */
@Component
public class TicketFlowHandler implements CapabilityHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketFlowHandler.class);

    private static final List<String> APPROVE_WORDS = List.of("同意", "批准", "确认", "通过", "执行");
    private static final List<String> REJECT_WORDS = List.of("拒绝", "不同意", "驳回", "取消");

    private final TicketGraphCompiler compiler;
    private final TicketService ticketService;
    private final ApprovalService approvalService;
    private final AuditLogger auditLogger;
    private final QualityReviewService qualityReviewService;

    public TicketFlowHandler(TicketGraphCompiler compiler,
                             TicketService ticketService,
                             ApprovalService approvalService,
                             AuditLogger auditLogger,
                             QualityReviewService qualityReviewService) {
        this.compiler = compiler;
        this.ticketService = ticketService;
        this.approvalService = approvalService;
        this.auditLogger = auditLogger;
        this.qualityReviewService = qualityReviewService;
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
                    "工单 " + ticketId + " 处理异常,已回退状态,请重试或转人工。");
        } catch (Exception e) {
            log.error("[ticket-flow] 工单图执行失败 ticketId={}", ticketId, e);
            ticketService.updateStatus(ticketId, TicketService.STATUS_NEW);
            return "工单 " + ticketId + " 处理失败:" + e.getMessage();
        }
    }

    /** 审批话术处理:记录决策 → updateState + resume(探针结论:决策必须先落 ApprovalService) */
    private String handleApprovalReply(String message, DialogContext ctx) {
        String ticketId = ctx.getPendingTicketId();
        boolean approved = APPROVE_WORDS.stream().anyMatch(message::contains);
        boolean rejected = REJECT_WORDS.stream().anyMatch(message::contains);
        if (!approved && !rejected) {
            return "工单 " + ticketId + " 正在等待审批,请回复「同意」或「拒绝」,或发送「换个话题」放弃。";
        }

        approvalService.recordDecision(ticketId, approved,
                "会话:" + ctx.getSessionId(), "用户话术:" + message);
        auditLogger.log(ticketId, "chat-approval", approved ? "approved" : "rejected");
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
            String done = approved ? "已批准并执行。" : "已驳回,不执行处置动作。";
            return done + interpretState(ticketId, resumed.orElse(null), ctx,
                    "审批决策已记录,但流程续跑异常,请通过 REST 接口跟进。");
        } catch (Exception e) {
            log.error("[ticket-flow] 审批续跑失败 ticketId={}", ticketId, e);
            clearFlow(ctx);
            return "审批续跑失败:" + e.getMessage();
        }
    }

    /**
     * 解读图执行结果:有终稿→消费终态意图(FINAL_STATUS,阶段 O8 起结单节点不再直接落库)后
     * 清除流程状态返回终稿;停在审批前→记录 pendingTicketId 并提示;
     * 其余(异常中断)→回退状态。
     */
    private String interpretState(String ticketId, OverAllState state, DialogContext ctx, String errorHint) {
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
        ticketService.updateStatus(ticketId, TicketService.STATUS_NEW);
        return errorHint;
    }

    /** 流程结束:清除粘性与待审批状态,会话回到自由路由 */
    private void clearFlow(DialogContext ctx) {
        ctx.setActiveCapability(null);
        ctx.setPendingTicketId(null);
    }
}
