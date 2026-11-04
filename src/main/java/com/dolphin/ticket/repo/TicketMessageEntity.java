package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 工单对话消息实体(表 ticket_message,阶段4 多轮对话)。
 *
 * <p>一条工单一个对话线程:提交时的原始诉求是首条用户消息,此后每轮
 * 客户补充({@code POST /api/tickets/{id}/reply})与系统回复依次追加,
 * 支撑"PENDING 单基于上下文续跑"与客户侧线程回看。
 */
@Entity
@Table(name = "ticket_message", indexes = @Index(name = "idx_message_ticket", columnList = "ticket_id"))
public class TicketMessageEntity {

    /** 角色:客户发言 */
    public static final String ROLE_USER = "USER";
    /** 角色:系统/客服回复 */
    public static final String ROLE_AGENT = "AGENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属工单号 */
    @Column(name = "ticket_id", nullable = false, length = 64)
    private String ticketId;

    /** 发言角色:USER=客户 / AGENT=系统回复 */
    @Column(name = "role", nullable = false, length = 8)
    private String role;

    /** 消息内容 */
    @Column(name = "content", nullable = false, length = 8192)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketMessageEntity() {
    }

    public TicketMessageEntity(String ticketId, String role, String content, Instant createdAt) {
        this.ticketId = ticketId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
