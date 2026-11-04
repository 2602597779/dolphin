package com.dolphin.sla;

import com.dolphin.alert.DingTalkNotifier;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * SLA 超时巡检(阶段3):周期扫描未闭环工单,超时自动升级人工兜底。
 *
 * <p>规则(与 {@link SlaProperties} 语义一致):
 * <ul>
 *   <li><b>响应超时</b>:卡在 NEW/RUNNING(图尚未产出客户可见回复)超过响应时限;</li>
 *   <li><b>解决超时</b>:未 CLOSED(含 PENDING_APPROVAL/ESCALATED)超过解决时限。</li>
 * </ul>
 *
 * <p>超时动作(一次性,幂等):落 {@code ticket.sla_breach_at} 留痕 → 未在人工池则入队 →
 * 审计 {@code sla-breach} → 钉钉加急(webhook 未配置自动降级审计日志,不中断流程)。
 * 已留痕的工单后续扫描直接跳过,避免重复入队/重复加急刷屏。
 *
 * <p>实现细节:不改写工单状态——审批中断的单仍可正常 approve 续跑结单,
 * SLA 升级是"拉人兜底"而非"改判流程",人工池完结(resolve)会把工单转 CLOSED。
 */
@Component
public class SlaMonitor {

    private static final Logger log = LoggerFactory.getLogger(SlaMonitor.class);

    private final TicketRepository ticketRepository;
    private final HumanTicketPool humanTicketPool;
    private final AuditLogger auditLogger;
    private final DingTalkNotifier dingTalkNotifier;
    private final SlaProperties properties;
    private final Clock clock;

    public SlaMonitor(TicketRepository ticketRepository,
                      HumanTicketPool humanTicketPool,
                      AuditLogger auditLogger,
                      DingTalkNotifier dingTalkNotifier,
                      SlaProperties properties,
                      Clock clock) {
        this.ticketRepository = ticketRepository;
        this.humanTicketPool = humanTicketPool;
        this.auditLogger = auditLogger;
        this.dingTalkNotifier = dingTalkNotifier;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 周期巡检。扫描周期由 {@code dolphin.sla.scan-interval} 控制(默认 60s,
     * 演示/测试可配 10s);方法本身幂等,直接调用即可在单测中驱动。
     */
    @Scheduled(fixedDelayString = "${dolphin.sla.scan-interval:60s}",
            initialDelayString = "${dolphin.sla.scan-initial-delay:30s}")
    public void scan() {
        Instant now = clock.instant();
        // 未闭环且从未 breach 的工单(breach 留痕后跳过,天然幂等)
        List<TicketEntity> openTickets =
                ticketRepository.findByStatusNotAndSlaBreachAtIsNull(TicketService.STATUS_CLOSED);
        for (TicketEntity ticket : openTickets) {
            String priority = priorityOrDefault(ticket.getPriority());
            Duration age = Duration.between(ticket.getCreatedAt(), now);
            String level = breachLevel(ticket.getStatus(), priority, age);
            if (level != null) {
                breach(ticket, priority, age, level, now);
            }
        }
    }

    /** 判定超时级别:null=未超时;RESPONSE=响应超时;RESOLVE=解决超时(响应未超先判响应) */
    private String breachLevel(String status, String priority, Duration age) {
        if ((TicketService.STATUS_NEW.equals(status) || TicketService.STATUS_RUNNING.equals(status))
                && age.compareTo(properties.responseOf(priority)) > 0) {
            return "RESPONSE";
        }
        if (age.compareTo(properties.resolveOf(priority)) > 0) {
            return "RESOLVE";
        }
        return null;
    }

    /** 超时动作:留痕 + 入队(若未在池) + 审计 + 加急通知 */
    private void breach(TicketEntity ticket, String priority, Duration age, String level, Instant now) {
        String ticketId = ticket.getId();
        ticket.setSlaBreachAt(now);
        ticket.setUpdatedAt(now);
        ticketRepository.save(ticket);

        String reason = "SLA超时:" + level + "(优先级" + priority + ",已耗时" + minutes(age)
                + "分钟,时限" + minutes(limitOf(level, priority)) + "分钟)";
        // 未入人工池才入队:保留已认领(CLAIMED)状态,不重置别人的处理进度
        boolean newlyQueued = humanTicketPool.get(ticketId).isEmpty();
        if (newlyQueued) {
            humanTicketPool.add(ticketId, reason, "原始诉求:" + ticket.getRawMessage());
        }
        auditLogger.log(ticketId, "sla-breach", reason + (newlyQueued ? " 已升级人工池" : " 已在人工池,仅加急"));

        // 加急通知:钉钉 markdown;未配置 webhook 时 DingTalkNotifier 自身降级为日志
        String text = "### [SLA加急] " + reason + "\n\n- 工单号:" + ticketId + "\n- 优先级:"
                + priority + "\n- 状态:" + ticket.getStatus() + "\n- 原始诉求:"
                + abbreviate(ticket.getRawMessage(), 60) + "\n\n请尽快认领处理(POST /api/human-tickets/"
                + ticketId + "/claim)。";
        boolean notified = dingTalkNotifier.sendMarkdown("[SLA加急] 工单 " + ticketId, text);
        log.warn("[sla] {} ticketId={} priority={} age={}m notified={}",
                reason, ticketId, priority, minutes(age), notified);
    }

    private Duration limitOf(String level, String priority) {
        return "RESPONSE".equals(level) ? properties.responseOf(priority) : properties.resolveOf(priority);
    }

    private static String priorityOrDefault(String priority) {
        return priority == null || priority.isBlank() ? SlaPriorityRules.P3 : priority;
    }

    private static long minutes(Duration d) {
        return Math.max(0, d.truncatedTo(ChronoUnit.SECONDS).toSeconds() / 60);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
