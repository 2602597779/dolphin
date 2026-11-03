package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 工单台账实体(表 ticket)。领域对象见 {@code TicketService.Ticket}(record),由 store 负责映射。
 */
@Entity
@Table(name = "ticket", indexes = @Index(name = "idx_ticket_status", columnList = "status"))
public class TicketEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "raw_message", nullable = false, length = 8192)
    private String rawMessage;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketEntity() {
    }

    public TicketEntity(String id, String rawMessage, String status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.rawMessage = rawMessage;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public String getStatus() {
        return status;
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

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
