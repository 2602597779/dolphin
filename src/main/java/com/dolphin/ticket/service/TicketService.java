package com.dolphin.ticket.service;

import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工单台账服务(P0 起持久化:H2 文件默认 / mysql profile 可切)。
 *
 * <p>对外 API 与骨架期一致:创建返回工单号、按号查询、状态流转;
 * 内部由 ConcurrentHashMap 换为 {@link TicketRepository},重启后台账完好。
 *
 * <p>租户隔离(阶段1):创建带归属 customerId;客户侧查询走
 * {@link #getForCustomer}/{@link #listForCustomer},非本客户的工单一律"不存在"。
 */
@Component
public class TicketService {

    /** 工单状态常量 */
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_ESCALATED = "ESCALATED";

    /**
     * 工单领域对象(record,对外只读)。
     *
     * @param id          工单号
     * @param rawMessage  用户原始工单文本
     * @param status      当前状态
     * @param customerId  归属客户(null = 内部渠道:告警自动开单/历史数据)
     */
    public record Ticket(String id, String rawMessage, String status, String customerId) {
    }

    private final TicketRepository repository;

    public TicketService(TicketRepository repository) {
        this.repository = repository;
    }

    /** 工单号自增序号:并发提交落在同一毫秒时由序号位区分,避免主键冲突 */
    private final AtomicLong idSeq = new AtomicLong();

    /** 创建工单(内部渠道:告警自动开单等系统链路),返回生成的工单号 */
    public String create(String rawMessage) {
        return create(rawMessage, null);
    }

    /** 创建工单并绑定归属客户,返回生成的工单号 */
    public String create(String rawMessage, String customerId) {
        // P0 并发修复:工单号 = 毫秒(base36) + 进程内序号(base36),并发提交同毫秒不再碰撞
        String id = "T" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + Long.toString(idSeq.incrementAndGet(), 36).toUpperCase();
        Instant now = Instant.now();
        repository.save(new TicketEntity(id, rawMessage, STATUS_NEW, customerId, now, now));
        return id;
    }

    /** 按工单号查询(内部/运营视角,不看归属) */
    public Optional<Ticket> get(String id) {
        return repository.findById(id).map(TicketService::toDomain);
    }

    /**
     * 客户侧查询:只可见本客户的工单,他人工单一律 empty(404 语义,不泄露存在性)。
     * customerId 为 null 表示内部渠道直调(如单测),此时退化为全量可见。
     */
    public Optional<Ticket> getForCustomer(String id, String customerId) {
        if (customerId == null) {
            return get(id);
        }
        return repository.findByIdAndCustomerId(id, customerId).map(TicketService::toDomain);
    }

    /** 客户侧工单列表(新→旧);status 为 null/空表示全部 */
    public List<Ticket> listForCustomer(String customerId, String status, int limit) {
        Pageable pageable = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        boolean allStatus = status == null || status.isBlank();
        List<TicketEntity> entities;
        if (customerId == null) {
            // 内部渠道直调(单测/运营):全量视角
            entities = allStatus
                    ? repository.findAllByOrderByCreatedAtDesc(pageable)
                    : repository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            entities = repository.findByCustomer(customerId, allStatus ? null : status, pageable);
        }
        return entities.stream().map(TicketService::toDomain).toList();
    }

    /** 更新工单优先级(分诊结论落库;工单不存在时静默忽略) */
    public void updatePriority(String id, String priority) {
        repository.findById(id).ifPresent(e -> {
            e.setPriority(priority);
            e.setUpdatedAt(Instant.now());
            repository.save(e);
        });
    }

    /** 更新工单状态(工单不存在时静默忽略);结单落 closed_at 戳,重开清空(再次结单重新计时) */
    public void updateStatus(String id, String status) {
        repository.findById(id).ifPresent(e -> {
            e.setStatus(status);
            Instant now = Instant.now();
            e.setUpdatedAt(now);
            if (STATUS_CLOSED.equals(status)) {
                // 首次结单时间戳为 MTTR 统计口径;重复置 CLOSED 保持首戳不变
                if (e.getClosedAt() == null) {
                    e.setClosedAt(now);
                }
            } else if (e.getClosedAt() != null) {
                // 重开(如质检驳回转 ESCALATED/人工重处理):清空结单时间
                e.setClosedAt(null);
            }
            repository.save(e);
        });
    }

    private static Ticket toDomain(TicketEntity e) {
        return new Ticket(e.getId(), e.getRawMessage(), e.getStatus(), e.getCustomerId());
    }
}
