package com.dolphin.ticket.infra.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 审计留痕实体(表 audit_log):所有工单动作/告警受理/质检/人工池操作统一落库,
 * log.info 之外的持久化副本,复盘与合规查询可用。
 *
 * <p>session_id 承载业务主标识:工单链路为工单号,告警链路为指纹。
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Lob
    @Column(name = "detail")
    private String detail;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(String sessionId, String action, String detail, Instant at) {
        this.sessionId = sessionId;
        this.action = action;
        this.detail = detail;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
