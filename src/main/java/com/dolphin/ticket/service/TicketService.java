package com.dolphin.ticket.service;

import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工单台账服务(P0 起持久化:H2 文件默认 / mysql profile 可切)。
 *
 * <p>对外 API 与骨架期一致:创建返回工单号、按号查询、状态流转;
 * 内部由 ConcurrentHashMap 换为 {@link TicketRepository},重启后台账完好。
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
     * @param id        工单号
     * @param rawMessage 用户原始工单文本
     * @param status    当前状态
     */
    public record Ticket(String id, String rawMessage, String status) {
    }

    private final TicketRepository repository;

    public TicketService(TicketRepository repository) {
        this.repository = repository;
    }

    /** 工单号自增序号:并发提交落在同一毫秒时由序号位区分,避免主键冲突 */
    private final AtomicLong idSeq = new AtomicLong();

    /** 创建工单,返回生成的工单号 */
    public String create(String rawMessage) {
        // P0 并发修复:工单号 = 毫秒(base36) + 进程内序号(base36),并发提交同毫秒不再碰撞
        String id = "T" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + Long.toString(idSeq.incrementAndGet(), 36).toUpperCase();
        Instant now = Instant.now();
        repository.save(new TicketEntity(id, rawMessage, STATUS_NEW, now, now));
        return id;
    }

    /** 按工单号查询 */
    public Optional<Ticket> get(String id) {
        return repository.findById(id).map(e -> new Ticket(e.getId(), e.getRawMessage(), e.getStatus()));
    }

    /** 更新工单状态(工单不存在时静默忽略) */
    public void updateStatus(String id, String status) {
        repository.findById(id).ifPresent(e -> {
            e.setStatus(status);
            e.setUpdatedAt(Instant.now());
            repository.save(e);
        });
    }
}
