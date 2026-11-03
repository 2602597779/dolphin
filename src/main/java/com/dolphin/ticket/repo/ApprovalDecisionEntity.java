package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 人工审批决策留痕实体(表 approval_decision)。每张工单至多一条有效决策(ticket_id 唯一),
 * 决策与令牌全程留痕:结单清理仅置空 token,审计行永久保留。
 */
@Entity
@Table(name = "approval_decision")
public class ApprovalDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, unique = true, length = 64)
    private String ticketId;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    @Column(name = "approver", nullable = false, length = 128)
    private String approver;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "reason", length = 2048)
    private String reason;

    /** 批准时签发的一次性审批令牌(拒绝或未签发为 null;结单清理后置空) */
    @Column(name = "token", length = 2048)
    private String token;

    protected ApprovalDecisionEntity() {
    }

    public ApprovalDecisionEntity(String ticketId, boolean approved, String approver,
                                  Instant decidedAt, String reason, String token) {
        this.ticketId = ticketId;
        this.approved = approved;
        this.approver = approver;
        this.decidedAt = decidedAt;
        this.reason = reason;
        this.token = token;
    }

    public Long getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public boolean isApproved() {
        return approved;
    }

    public String getApprover() {
        return approver;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getReason() {
        return reason;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
