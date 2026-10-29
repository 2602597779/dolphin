package com.dolphin.ticket.service;

import com.dolphin.ticket.infra.audit.AuditLogger;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工工单池(最小实现):图编排的 escalateHuman 节点把转人工工单落队列,
 * 人工专员通过 REST 认领/完结,替代原先的"骨架占位"文案。
 *
 * <p>生命周期:WAITING(待认领)→ CLAIMED(处理中)→ RESOLVED(完结,工单转 CLOSED)。
 * 骨架阶段内存实现,TODO:接数据库持久化与真实人工工单系统(如飞书任务/客服台)。
 */
@Component
public class HumanTicketPool {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 一条人工任务 */
    public record HumanTask(String ticketId, String reason, String detail, String status,
                            String owner, String createdAt, String updatedAt) {
    }

    private final Map<String, HumanTask> tasks = new ConcurrentHashMap<>();
    private final AuditLogger auditLogger;
    private final TicketService ticketService;

    public HumanTicketPool(AuditLogger auditLogger, TicketService ticketService) {
        this.auditLogger = auditLogger;
        this.ticketService = ticketService;
    }

    /** 转人工落队列(同工单重复落队以最新一次为准) */
    public HumanTask add(String ticketId, String reason, String detail) {
        HumanTask task = new HumanTask(ticketId, reason, detail, STATUS_WAITING, "",
                OffsetDateTime.now().toString(), OffsetDateTime.now().toString());
        tasks.put(ticketId, task);
        auditLogger.log(ticketId, "human-pool", "入队 reason=" + reason);
        return task;
    }

    /** 按 status 过滤,WAITING 优先、按入队时间倒序 */
    public List<HumanTask> list(String status) {
        return tasks.values().stream()
                .filter(t -> status == null || status.isBlank() || t.status().equalsIgnoreCase(status))
                .sorted(Comparator.comparing(HumanTask::updatedAt).reversed())
                .toList();
    }

    public Optional<HumanTask> get(String ticketId) {
        return Optional.ofNullable(tasks.get(ticketId));
    }

    /** 认领:WAITING → CLAIMED */
    public Optional<HumanTask> claim(String ticketId, String owner) {
        HumanTask current = tasks.get(ticketId);
        if (current == null || !STATUS_WAITING.equals(current.status())) {
            return Optional.empty();
        }
        HumanTask claimed = new HumanTask(ticketId, current.reason(), current.detail(),
                STATUS_CLAIMED, owner == null || owner.isBlank() ? "anonymous" : owner,
                current.createdAt(), OffsetDateTime.now().toString());
        tasks.put(ticketId, claimed);
        auditLogger.log(ticketId, "human-pool", "认领 owner=" + claimed.owner());
        return Optional.of(claimed);
    }

    /** 完结:CLAIMED → RESOLVED,工单状态转 CLOSED */
    public Optional<HumanTask> resolve(String ticketId, String note) {
        HumanTask current = tasks.get(ticketId);
        if (current == null || !STATUS_CLAIMED.equals(current.status())) {
            return Optional.empty();
        }
        HumanTask resolved = new HumanTask(ticketId, current.reason(), current.detail(),
                STATUS_RESOLVED, current.owner(), current.createdAt(), OffsetDateTime.now().toString());
        tasks.put(ticketId, resolved);
        ticketService.updateStatus(ticketId, TicketService.STATUS_CLOSED);
        auditLogger.log(ticketId, "human-pool", "完结 note=" + (note == null ? "" : note));
        return Optional.of(resolved);
    }
}
