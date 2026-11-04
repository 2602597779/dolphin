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

    /**
     * 归属客户(租户隔离键):null = 内部渠道(告警自动开单/历史数据),
     * 客户侧 API 一律按本字段校验归属。
     */
    @Column(name = "customer_id", length = 64)
    private String customerId;

    /**
     * 优先级(P1~P4,阶段3 起由分诊产出落库;未分级按业务规则默认 P3,
     * 老数据 null 在指标聚合时同样按 P3 兜底)。
     */
    @Column(name = "priority", length = 8)
    private String priority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 首次结单时间(MTTR 统计口径):状态流转到 CLOSED 时由台账服务落戳;
     * 重开(如质检驳回转 ESCALATED)清空,再次结单重新计时。老数据 null 不参与 MTTR。
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * SLA 超时留痕时间(null=未超时):超响应/解决时限时由 {@code SlaMonitor} 落戳,
     * 同时升级人工池并加急通知;已留痕的工单后续巡检跳过(幂等)。
     */
    @Column(name = "sla_breach_at")
    private Instant slaBreachAt;

    protected TicketEntity() {
    }

    public TicketEntity(String id, String rawMessage, String status, Instant createdAt, Instant updatedAt) {
        this(id, rawMessage, status, null, createdAt, updatedAt);
    }

    public TicketEntity(String id, String rawMessage, String status, String customerId,
                        Instant createdAt, Instant updatedAt) {
        this(id, rawMessage, status, customerId, "P3", createdAt, updatedAt, null);
    }

    public TicketEntity(String id, String rawMessage, String status, String customerId, String priority,
                        Instant createdAt, Instant updatedAt, Instant closedAt) {
        this.id = id;
        this.rawMessage = rawMessage;
        this.status = status;
        this.customerId = customerId;
        this.priority = priority;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closedAt = closedAt;
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

    public String getCustomerId() {
        return customerId;
    }

    public String getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getSlaBreachAt() {
        return slaBreachAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public void setSlaBreachAt(Instant slaBreachAt) {
        this.slaBreachAt = slaBreachAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
