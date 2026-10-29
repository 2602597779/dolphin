package com.dolphin.alert;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警诊断记录环形存储(内存,上限 200 条,最旧淘汰)。
 *
 * <p>M2 引入 MySQL 后将被 alert_diagnosis_record 表替代(与 {@link AlertEventStore} 同策略),
 * 当前用于演示与调试(GET /api/alert/diagnoses 查看)。
 */
@Component
public class AlertDiagnosisStore {

    private static final int MAX_RECORDS = 200;

    /** 诊断终态:DIAGNOSED=产出报告(可能含动作建议) | NO_MATCHING_TASK=未命中 alert_task | DIAGNOSIS_FAILED=Agent 调用异常 */
    public static final String OUTCOME_DIAGNOSED = "DIAGNOSED";
    public static final String OUTCOME_NO_TASK = "NO_MATCHING_TASK";
    public static final String OUTCOME_FAILED = "DIAGNOSIS_FAILED";

    /** 一条诊断记录 */
    public record DiagnosisRecord(long seq, String fingerprint, String alertName, String taskName,
                                  String outcome, String report, String proposal,
                                  boolean notified, String notifyDetail, String at) {
    }

    private final ConcurrentLinkedDeque<DiagnosisRecord> records = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public DiagnosisRecord add(String fingerprint, String alertName, String taskName,
                               String outcome, String report, String proposal,
                               boolean notified, String notifyDetail) {
        DiagnosisRecord record = new DiagnosisRecord(
                seq.incrementAndGet(), fingerprint, alertName,
                taskName == null || taskName.isBlank() ? "-" : taskName,
                outcome, report == null ? "" : report, proposal == null ? "" : proposal,
                notified, notifyDetail == null ? "" : notifyDetail, OffsetDateTime.now().toString());
        records.addFirst(record);
        while (records.size() > MAX_RECORDS) {
            records.pollLast();
        }
        return record;
    }

    /** 最近记录(新→旧) */
    public List<DiagnosisRecord> recent(int limit) {
        return records.stream().limit(Math.max(limit, 0)).toList();
    }

    public long total() {
        return seq.get();
    }
}
