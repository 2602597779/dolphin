package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 写操作幂等表实体(表 action_idempotency):同一工单+动作+目标只允许一次副作用,
 * 重启进程后去重依然生效(idem_key = ticketId|action|target)。
 */
@Entity
@Table(name = "action_idempotency", indexes = @Index(name = "idx_idempotency_ticket", columnList = "ticket_id"))
public class ActionIdempotencyEntity {

    @Id
    @Column(name = "idem_key", nullable = false, length = 512)
    private String idemKey;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "ticket_id", nullable = false, length = 64)
    private String ticketId;

    /** 首次执行结果说明(供重复请求返回与留痕) */
    @Lob
    @Column(name = "result")
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ActionIdempotencyEntity() {
    }

    public ActionIdempotencyEntity(String idemKey, String action, String ticketId, String result, Instant createdAt) {
        this.idemKey = idemKey;
        this.action = action;
        this.ticketId = ticketId;
        this.result = result;
        this.createdAt = createdAt;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getAction() {
        return action;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
