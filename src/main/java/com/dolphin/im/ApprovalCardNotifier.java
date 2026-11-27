package com.dolphin.im;

import com.dolphin.notify.NotifySender;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.ApprovalPendingEvent;
import com.dolphin.ticket.service.TicketStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 审批卡片通知:工单挂起待审批时把"请人工审批"推到 IM。
 *
 * <p>订阅时机是 {@link TransactionPhase#AFTER_COMMIT} 而不是 {@code @EventListener}:
 * 通知只在状态真的提交后才发,回滚的轮次不会留下"卡片已发、工单却没挂起"的假消息。
 * {@code fallbackExecution = true} 让无事务调用(如手工重放)也能触发,避免静默丢失。
 *
 * <p>刻意<b>不用 {@code @Async}</b>:本项目未开启 {@code @EnableAsync},加注解得静默 no-op
 * (最坏情况是没人发现通知根本没发)。真正想要的异步解耦在通道层:
 * {@code dolphin.notify.channel=mq} 时 {@link NotifySender} 走进程内 MQ 语义实现
 * (有界缓冲 + 单消费者限速 + 重试 + 死信),默认 {@code direct} 为同步直发。
 *
 * <p>旁路语义:通知失败只记审计,不抛异常——图线程的续跑/结单不受通知侧影响。
 * 未配置通知通道时降级为审计留痕(离线演示与既有测试零外部依赖)。
 *
 * <p>诚实边界:这里的"卡片"是含工单号与待执行动作的 markdown 通知;平台交互按钮
 * (钉钉 ActionCard / 飞书交互卡片)需要平台应用凭证与 SDK 能力,本参考实现不引入
 * 平台 SDK(零新增依赖约定),审批动作用户在 IM 侧点击后由平台回调
 * {@code POST /api/im/callback/{platform}} 驱动。⚠️ 未用真机沙箱对通。
 */
@Component
public class ApprovalCardNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalCardNotifier.class);

    /** 审计动作名:审批卡片(含降级与发送失败,便于复盘"通知到底发出去没有") */
    public static final String AUDIT_ACTION = "im-approval-card";

    /** 待办动作取不到时的占位(不掩盖异常,只降级展示) */
    private static final String UNKNOWN_ACTION = "未取到待执行动作";

    private final NotifySender notifySender;
    private final TicketStatusService statusService;
    private final AuditLogger auditLogger;

    public ApprovalCardNotifier(NotifySender notifySender,
                                TicketStatusService statusService,
                                AuditLogger auditLogger) {
        this.notifySender = notifySender;
        this.statusService = statusService;
        this.auditLogger = auditLogger;
    }

    /** 工单挂起待审批(事务提交后):发审批卡片,未配置通道则降级审计留痕 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalPending(ApprovalPendingEvent event) {
        String ticketId = event.ticketId();
        String pendingAction = pendingAction(ticketId);

        if (!notifySender.isConfigured()) {
            log.info("[im-card] 通知通道未配置,审批卡片降级为审计留痕 ticketId={} pendingAction={}",
                    ticketId, pendingAction);
            auditLogger.log(ticketId, AUDIT_ACTION, "degraded:未配置通知通道 pendingAction=" + pendingAction);
            return;
        }

        boolean sent = notifySender.send(title(ticketId), body(ticketId, pendingAction));
        auditLogger.log(ticketId, AUDIT_ACTION,
                (sent ? "sent:" : "failed:") + "pendingAction=" + pendingAction);
    }

    /** 卡片标题:带工单号,IM 列表里能直接检索 */
    static String title(String ticketId) {
        return "待人工审批:" + ticketId;
    }

    /** 卡片正文(markdown):待执行动作 + 审批入口 */
    static String body(String ticketId, String pendingAction) {
        return "### 工单待人工审批\n\n"
                + "- 工单号:`" + ticketId + "`\n"
                + "- 待执行动作:`" + pendingAction + "`\n"
                + "- 审批入口:同意 / 拒绝后,平台回调 `POST /api/im/callback/{platform}`\n\n"
                + "> 审批结果驱动图执行续跑;同一事件号重复投递只处理一次。";
    }

    /** 现取待执行动作:查询失败只降级展示,不把异常抛回图线程 */
    private String pendingAction(String ticketId) {
        try {
            Object action = statusService.view(ticketId).get("pendingAction");
            return action == null ? UNKNOWN_ACTION : String.valueOf(action);
        } catch (RuntimeException e) {
            log.warn("[im-card] 读取待执行动作失败 ticketId={} err={}", ticketId, e.toString());
            return UNKNOWN_ACTION;
        }
    }
}
