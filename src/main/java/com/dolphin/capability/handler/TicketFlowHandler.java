package com.dolphin.capability.handler;

import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityHandler;
import com.dolphin.router.DialogContext;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.TicketGraphRunner;
import com.dolphin.ticket.service.TicketService;
import com.dolphin.ticket.service.TicketStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工单流程能力:受理工单并投递既有多智能体图异步执行(分诊→诊断→回复→审批→执行→结单)。
 *
 * <p>会话内两段式交互(受理与执行分离,HTTP 线程不再被图执行占住):
 * <ol>
 *   <li>提交新工单 → 同步段只做受理落库+粘性预置,图投递 {@link TicketGraphRunner} 异步执行,
 *       立即返回受理确认;图停在 approval 前时粘性已就位,等待用户话术;</li>
 *   <li>粘性期内回复「同意/拒绝」→ 同步段完成受理闸门+决策落库(并发正确性关键,保持同步),
 *       resume 续跑投递异步;其余消息按工单实际状态回复进展,终态时清除粘性。</li>
 * </ol>
 * 与 REST 接口 /api/tickets/{id}/approve 等价,实现聊天驱动的 HITL 闭环。
 *
 * <p>与 REST 链路对齐的一致性保障:
 * <ul>
 *   <li><b>跨渠道审批闸门</b>:决策受理前先 tryTransition(PENDING_APPROVAL→PROCESSING),
 *       与 REST approve 同款条件状态翻转——工单已被 REST 审批/已结单/已转人工时,
 *       聊天侧输家不落决策、不重复续跑,清除粘性后按工单实际状态如实告知;</li>
 *   <li><b>失败补偿</b>:投递被拒时 TicketGraphRunner 就地补偿(系统说明+转人工+审计),
 *       聊天侧只负责清粘性与如实告知,工单不停留在 PROCESSING;</li>
 *   <li><b>粘性乐观预置</b>:受理即预置粘性(图大概率停在审批前),终态由进展查询或
 *       输家分支清除——DialogContext 字段为 volatile,异步轮次后的会话写入对下一请求可见,
 *       残留无害(任何后续消息都会经状态查询兜底)。</li>
 * </ul>
 */
@Component
public class TicketFlowHandler implements CapabilityHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketFlowHandler.class);

    private static final List<String> APPROVE_WORDS = List.of("同意", "批准", "确认", "通过", "执行");
    private static final List<String> REJECT_WORDS = List.of("拒绝", "不同意", "驳回", "取消");

    private final TicketService ticketService;
    private final ApprovalService approvalService;
    private final AuditLogger auditLogger;
    private final TicketGraphRunner graphRunner;
    private final TicketStatusService statusService;
    private final ConversationService conversationService;

    public TicketFlowHandler(TicketService ticketService,
                             ApprovalService approvalService,
                             AuditLogger auditLogger,
                             TicketGraphRunner graphRunner,
                             TicketStatusService statusService,
                             ConversationService conversationService) {
        this.ticketService = ticketService;
        this.approvalService = approvalService;
        this.auditLogger = auditLogger;
        this.graphRunner = graphRunner;
        this.statusService = statusService;
        this.conversationService = conversationService;
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
        // 分支一:粘性期内有在途工单 → 审批话术驱动续跑,其余消息按状态回复进展
        if (ctx.getPendingTicketId() != null) {
            return handleApprovalReply(message, ctx);
        }
        // 分支二:新工单提交 → 受理+异步投递
        return submitTicket(message, ctx);
    }

    /** 提交工单:同步受理落库+粘性预置,图投递 TicketGraphRunner 异步执行(与 REST submit 同款语义) */
    private String submitTicket(String message, DialogContext ctx) {
        String ticketId = ticketService.create(message);
        // 对话线程首条消息(对齐 REST 受理语义,顺带闭环缺陷#6:聊天建单写工单线程)
        conversationService.appendUser(ticketId, message);
        ticketService.updateStatus(ticketId, TicketService.STATUS_PROCESSING);
        // 粘性乐观预置:图大概率停在审批前,预置后用户可直接回复「同意」;
        // 终态(CLOSED/ESCALATED)由进展查询或输家分支清除,残留无害
        ctx.setActiveCapability(descriptor().code());
        ctx.setPendingTicketId(ticketId);
        if (!graphRunner.enqueueSubmitRound(ticketId, message)) {
            // 投递被拒:runner 已就地补偿(系统说明+转人工+审计),这里清粘性如实告知
            clearFlow(ctx);
            return "系统繁忙,工单 " + ticketId + " 受理队列已满,已转人工跟进。";
        }
        return "已受理工单 " + ticketId + ",正在诊断处理。回复「进展」查询结果;若需审批处置动作,会提示您确认。";
    }

    /** 审批话术处理:受理闸门+决策落库(同步,正确性关键)→ resume 投递异步;其余话术按状态回复进展 */
    private String handleApprovalReply(String message, DialogContext ctx) {
        String ticketId = ctx.getPendingTicketId();
        boolean approved = APPROVE_WORDS.stream().anyMatch(message::contains);
        boolean rejected = REJECT_WORDS.stream().anyMatch(message::contains);
        if (!approved && !rejected) {
            return describeTicketState(ticketId, ctx, "工单 " + ticketId + " 处理中,请稍候。");
        }

        // 跨渠道审批闸门(与 REST /approve 同款):仅当工单仍处于 PENDING_APPROVAL 才受理本次决策。
        // 输家(REST 已审批/已结单/已转人工/重复提交)不落决策、不重复续跑,清除粘性后按实际状态告知。
        if (!ticketService.tryTransition(ticketId, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)) {
            clearFlow(ctx);
            return describeTicketState(ticketId, ctx,
                    "工单 " + ticketId + " 已不在待审批状态(可能已通过其他渠道处理),本次回复未生效。");
        }

        approvalService.recordDecision(ticketId, approved,
                "会话:" + ctx.getSessionId(), "用户话术:" + message);
        auditLogger.log(ticketId, "chat-approval", approved ? "approved" : "rejected");
        String verdict = approved ? "已批准," : "已驳回,";
        if (!graphRunner.enqueueApprovalResume(ticketId, approved)) {
            // 投递被拒:runner 已就地补偿转人工,这里清粘性如实告知
            clearFlow(ctx);
            return verdict + "执行任务投递失败,工单已转人工跟进。";
        }
        // 粘性保持:resume 完成后单到终态,用户回复「进展」时按状态清除并告知结果
        return verdict + "正在执行处置动作,完成后可回复「进展」查询结果。";
    }

    /**
     * 按工单实际状态回复(进展查询/输家告知共用口径,与轮询接口同源 TicketStatusService.view):
     * 终态(CLOSED/ESCALATED)给出结论并清除粘性;PENDING_APPROVAL 提示待审批动作;
     * 执行中如实告知;查询失败回退 fallback(不掩盖异常)。
     */
    private String describeTicketState(String ticketId, DialogContext ctx, String fallback) {
        Map<String, Object> view;
        try {
            view = statusService.view(ticketId);
        } catch (Exception e) {
            log.warn("[ticket-flow] 工单状态查询失败 ticketId={}", ticketId, e);
            return fallback;
        }
        String status = String.valueOf(view.get("status"));
        if (TicketService.STATUS_CLOSED.equals(status)) {
            clearFlow(ctx);
            Object finalReply = view.get("finalReply");
            return "工单 " + ticketId + " 已完成。" + (finalReply == null ? "" : String.valueOf(finalReply));
        }
        if (TicketService.STATUS_ESCALATED.equals(status)) {
            clearFlow(ctx);
            return "工单 " + ticketId + " 已转人工跟进。";
        }
        if (TicketService.STATUS_PENDING_APPROVAL.equals(status)) {
            Object action = view.get("pendingAction");
            return "工单 " + ticketId + " 等待审批"
                    + (action == null ? "" : ",待执行动作[" + action + "]")
                    + "。回复「同意」执行或「拒绝」驳回。";
        }
        if (TicketService.STATUS_PROCESSING.equals(status)) {
            return "工单 " + ticketId + " 正在处理中,请稍候。";
        }
        return fallback;
    }

    /** 流程结束:清除粘性与待审批状态,会话回到自由路由 */
    private void clearFlow(DialogContext ctx) {
        ctx.setActiveCapability(null);
        ctx.setPendingTicketId(null);
    }
}
