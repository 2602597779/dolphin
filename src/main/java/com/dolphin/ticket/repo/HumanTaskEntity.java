package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 人工任务实体(表 human_task):转人工工单池,专员经 /api/human-tickets 认领/完结。
 * 每张工单至多一条任务(ticket_id 主键,重复落队以最新为准)。
 */
@Entity
@Table(name = "human_task")
public class HumanTaskEntity {

    @Id
    @Column(name = "ticket_id", nullable = false, length = 64)
    private String ticketId;

    @Column(name = "reason", nullable = false, length = 512)
    private String reason;

    @Lob
    @Column(name = "detail")
    private String detail;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "owner", nullable = false, length = 128)
    private String owner;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HumanTaskEntity() {
    }

    public HumanTaskEntity(String ticketId, String reason, String detail, String status,
                           String owner, Instant createdAt, Instant updatedAt) {
        this.ticketId = ticketId;
        this.reason = reason;
        this.detail = detail;
        this.status = status;
        this.owner = owner;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public String getStatus() {
        return status;
    }

    public String getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
