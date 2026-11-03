package com.dolphin.ticket.service;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.repo.HumanTaskEntity;
import com.dolphin.ticket.repo.HumanTaskRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 人工工单池(最小实现):图编排的 escalateHuman 节点把转人工工单落队列,
 * 人工专员通过 REST 认领/完结,替代原先的"骨架占位"文案。
 *
 * <p>生命周期:WAITING(待认领)→ CLAIMED(处理中)→ RESOLVED(完结,工单转 CLOSED)。
 * P0 起落库(H2 文件/mysql profile),重启后人工队列完好;{@link HumanTask} record 保留为对外 DTO。
 * TODO:接真实人工工单系统(如飞书任务/客服台)。
 */
@Component
public class HumanTicketPool {

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 一条人工任务(对外只读 DTO,时间由实体 Instant 序列化为 ISO-8601 字符串) */
    public record HumanTask(String ticketId, String reason, String detail, String status,
                            String owner, String createdAt, String updatedAt) {
    }

    private final HumanTaskRepository repository;
    private final AuditLogger auditLogger;
    private final TicketService ticketService;

    public HumanTicketPool(HumanTaskRepository repository, AuditLogger auditLogger, TicketService ticketService) {
        this.repository = repository;
        this.auditLogger = auditLogger;
        this.ticketService = ticketService;
    }

    /** 转人工落队列(同工单重复落队以最新一次为准:整行覆盖,状态回到 WAITING) */
    public HumanTask add(String ticketId, String reason, String detail) {
        Instant now = Instant.now();
        repository.save(new HumanTaskEntity(ticketId, reason, detail, STATUS_WAITING, "", now, now));
        auditLogger.log(ticketId, "human-pool", "入队 reason=" + reason);
        return get(ticketId).orElseThrow();
    }

    /** 按 status 过滤,按更新时间倒序 */
    public List<HumanTask> list(String status) {
        List<HumanTaskEntity> entities = (status == null || status.isBlank())
                ? repository.findAllByOrderByUpdatedAtDesc()
                : repository.findByStatusIgnoreCaseOrderByUpdatedAtDesc(status);
        return entities.stream().map(HumanTicketPool::toDto).toList();
    }

    public Optional<HumanTask> get(String ticketId) {
        return repository.findById(ticketId).map(HumanTicketPool::toDto);
    }

    /** 认领:WAITING → CLAIMED */
    public Optional<HumanTask> claim(String ticketId, String owner) {
        return repository.findById(ticketId)
                .filter(e -> STATUS_WAITING.equals(e.getStatus()))
                .map(e -> {
                    e.setStatus(STATUS_CLAIMED);
                    e.setOwner(owner == null || owner.isBlank() ? "anonymous" : owner);
                    e.setUpdatedAt(Instant.now());
                    repository.save(e);
                    auditLogger.log(ticketId, "human-pool", "认领 owner=" + e.getOwner());
                    return toDto(e);
                });
    }

    /** 完结:CLAIMED → RESOLVED,工单状态转 CLOSED */
    public Optional<HumanTask> resolve(String ticketId, String note) {
        return repository.findById(ticketId)
                .filter(e -> STATUS_CLAIMED.equals(e.getStatus()))
                .map(e -> {
                    e.setStatus(STATUS_RESOLVED);
                    e.setUpdatedAt(Instant.now());
                    repository.save(e);
                    ticketService.updateStatus(ticketId, TicketService.STATUS_CLOSED);
                    auditLogger.log(ticketId, "human-pool", "完结 note=" + (note == null ? "" : note));
                    return toDto(e);
                });
    }

    private static HumanTask toDto(HumanTaskEntity e) {
        return new HumanTask(e.getTicketId(), e.getReason(), e.getDetail(), e.getStatus(),
                e.getOwner(), e.getCreatedAt().toString(), e.getUpdatedAt().toString());
    }
}
