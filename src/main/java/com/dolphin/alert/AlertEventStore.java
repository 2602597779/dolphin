package com.dolphin.alert;

import com.dolphin.alert.repo.AlertEventEntity;
import com.dolphin.alert.repo.AlertEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 已受理告警事件台账(P0 起落库):fingerprint 主键,重复指纹 upsert 覆盖为最新,
 * 重启后台账可见。演示/调试入口 GET /api/alert/events。
 */
@Component
public class AlertEventStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 已受理告警事件(对外只读 DTO;P0 起 record 去 seq、增 receivedAt) */
    public record AlertEvent(String fingerprint, String status, String alertName,
                             String severity, String summary, String startsAt, String receivedAt) {
    }

    private final AlertEventRepository repository;

    public AlertEventStore(AlertEventRepository repository) {
        this.repository = repository;
    }

    /** 受理一条告警(同指纹重复受理覆盖为最新:fingerprint 主键整行 upsert) */
    public void add(AlertmanagerPayload.AlertItem alert) {
        repository.save(new AlertEventEntity(
                alert.fingerprint(),
                alert.status(),
                alert.alertName(),
                alert.severity(),
                alert.summary(),
                labelsJson(alert),
                alert.startsAt(),
                Instant.now()));
    }

    /** 最近事件(新→旧,按受理时间倒序) */
    public List<AlertEvent> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, limit)).stream()
                .map(AlertEventStore::toDto)
                .toList();
    }

    /** 累计受理的告警指纹数 */
    public long totalAccepted() {
        return repository.count();
    }

    private static String labelsJson(AlertmanagerPayload.AlertItem alert) {
        try {
            return alert.labels() == null ? "{}" : MAPPER.writeValueAsString(alert.labels());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static AlertEvent toDto(AlertEventEntity e) {
        return new AlertEvent(e.getFingerprint(), e.getStatus(), e.getAlertName(),
                e.getSeverity(), e.getSummary(), e.getStartsAt(), e.getReceivedAt().toString());
    }
}
