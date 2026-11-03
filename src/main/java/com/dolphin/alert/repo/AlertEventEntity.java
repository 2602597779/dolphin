package com.dolphin.alert.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 已受理告警事件实体(表 alert_event):fingerprint 主键,重复指纹 upsert 覆盖为最新,
 * 重启后台账可见。演示/调试入口 GET /api/alert/events。
 */
@Entity
@Table(name = "alert_event")
public class AlertEventEntity {

    @Id
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "alert_name", nullable = false, length = 128)
    private String alertName;

    @Column(name = "severity", nullable = false, length = 32)
    private String severity;

    @Column(name = "summary", nullable = false, length = 2048)
    private String summary;

    @Lob
    @Column(name = "labels_json")
    private String labelsJson;

    @Column(name = "starts_at", nullable = false, length = 64)
    private String startsAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AlertEventEntity() {
    }

    public AlertEventEntity(String fingerprint, String status, String alertName, String severity,
                            String summary, String labelsJson, String startsAt, Instant receivedAt) {
        this.fingerprint = fingerprint;
        this.status = status;
        this.alertName = alertName;
        this.severity = severity;
        this.summary = summary;
        this.labelsJson = labelsJson;
        this.startsAt = startsAt;
        this.receivedAt = receivedAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getStatus() {
        return status;
    }

    public String getAlertName() {
        return alertName;
    }

    public String getSeverity() {
        return severity;
    }

    public String getSummary() {
        return summary;
    }

    public String getLabelsJson() {
        return labelsJson;
    }

    public String getStartsAt() {
        return startsAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
