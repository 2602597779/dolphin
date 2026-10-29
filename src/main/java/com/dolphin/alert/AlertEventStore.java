package com.dolphin.alert;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警事件环形存储(内存,上限 200 条,最旧淘汰)。
 *
 * <p>M2 引入 MySQL 后,此存储将被 alert_diagnosis_record 表替代,
 * 当前用于演示与调试(GET /api/alert/events 查看最近受理的告警)。
 */
@Component
public class AlertEventStore {

    private static final int MAX_EVENTS = 200;

    /** 已受理告警事件 */
    public record AlertEvent(long seq, String fingerprint, String status, String alertName,
                             String severity, String summary, String startsAt) {
    }

    private final ConcurrentLinkedDeque<AlertEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public void add(AlertmanagerPayload.AlertItem alert) {
        events.addFirst(new AlertEvent(
                seq.incrementAndGet(),
                alert.fingerprint(),
                alert.status(),
                alert.alertName(),
                alert.severity(),
                alert.summary(),
                alert.startsAt()));
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    /** 最近事件(新→旧) */
    public List<AlertEvent> recent(int limit) {
        return events.stream().limit(Math.max(limit, 0)).toList();
    }

    public long totalAccepted() {
        return seq.get();
    }
}
