package com.dolphin.alert.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 告警诊断记录实体(表 alert_diagnosis):seq 自增主键,重启后诊断台账与通知结果可见。
 * 查看 GET /api/alert/diagnoses。
 */
@Entity
@Table(name = "alert_diagnosis", indexes = @Index(name = "idx_alert_diagnosis_fp", columnList = "fingerprint"))
public class AlertDiagnosisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "alert_name", nullable = false, length = 128)
    private String alertName;

    @Column(name = "task_name", nullable = false, length = 128)
    private String taskName;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Lob
    @Column(name = "report")
    private String report;

    @Lob
    @Column(name = "proposal")
    private String proposal;

    @Column(name = "notified", nullable = false)
    private boolean notified;

    @Column(name = "notify_detail", nullable = false, length = 1024)
    private String notifyDetail;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected AlertDiagnosisEntity() {
    }

    public AlertDiagnosisEntity(String fingerprint, String alertName, String taskName, String outcome,
                                String report, String proposal, boolean notified, String notifyDetail, Instant at) {
        this.fingerprint = fingerprint;
        this.alertName = alertName;
        this.taskName = taskName;
        this.outcome = outcome;
        this.report = report;
        this.proposal = proposal;
        this.notified = notified;
        this.notifyDetail = notifyDetail;
        this.at = at;
    }

    public Long getSeq() {
        return seq;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getAlertName() {
        return alertName;
    }

    public String getTaskName() {
        return taskName;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReport() {
        return report;
    }

    public String getProposal() {
        return proposal;
    }

    public boolean isNotified() {
        return notified;
    }

    public String getNotifyDetail() {
        return notifyDetail;
    }

    public Instant getAt() {
        return at;
    }
}
