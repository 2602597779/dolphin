package com.dolphin.router.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会话粘性状态实体(表 session_context):session_id 一行。
 *
 * <p>承载两件跨请求状态:{@code active_capability}(路由粘性,上一轮走的是哪个能力)
 * 与 {@code pending_ticket_id}(会话里正在等审批结果的那张单)。进程内实现把它们放在
 * 单机 Map 里,多实例部署时下一次请求可能落到别的节点即丢失;本表让任意实例读到同一份状态。
 *
 * <p>{@code last_accessed_at} 用 epoch 毫秒(与 {@link com.dolphin.router.DialogContext}
 * 字段一一对应):不做时区换算,同时作为空闲过期的唯一判据。
 */
@Entity
@Table(name = "session_context")
public class SessionContextEntity {

    @Id
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** 路由粘性:当前会话活跃能力(如 ticket);null=尚未确定 */
    @Column(name = "active_capability", length = 64)
    private String activeCapability;

    /** 待审批工单号:会话内正在等审批结果的那张单;null=无 */
    @Column(name = "pending_ticket_id", length = 64)
    private String pendingTicketId;

    /** 最近一次访问时间(epoch 毫秒) */
    @Column(name = "last_accessed_at", nullable = false)
    private long lastAccessedAt;

    protected SessionContextEntity() {
    }

    public SessionContextEntity(String sessionId, String activeCapability, String pendingTicketId,
                                long lastAccessedAt) {
        this.sessionId = sessionId;
        this.activeCapability = activeCapability;
        this.pendingTicketId = pendingTicketId;
        this.lastAccessedAt = lastAccessedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActiveCapability() {
        return activeCapability;
    }

    public void setActiveCapability(String activeCapability) {
        this.activeCapability = activeCapability;
    }

    public String getPendingTicketId() {
        return pendingTicketId;
    }

    public void setPendingTicketId(String pendingTicketId) {
        this.pendingTicketId = pendingTicketId;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(long lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
}
