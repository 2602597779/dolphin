package com.dolphin.alert;

import com.dolphin.alert.repo.AlertDiagnosisEntity;
import com.dolphin.alert.repo.AlertDiagnosisRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 告警诊断记录台账(P0 起落库):seq 自增主键,重启后诊断台账与通知结果可见。
 * 查看 GET /api/alert/diagnoses。
 */
@Component
public class AlertDiagnosisStore {

    /** 诊断终态:DIAGNOSED=产出报告(可能含动作建议) | NO_MATCHING_TASK=未命中 alert_task | DIAGNOSIS_FAILED=Agent 调用异常 */
    public static final String OUTCOME_DIAGNOSED = "DIAGNOSED";
    public static final String OUTCOME_NO_TASK = "NO_MATCHING_TASK";
    public static final String OUTCOME_FAILED = "DIAGNOSIS_FAILED";

    /** 一条诊断记录(对外只读 DTO,seq=数据库自增 id) */
    public record DiagnosisRecord(long seq, String fingerprint, String alertName, String taskName,
                                  String outcome, String report, String proposal,
                                  boolean notified, String notifyDetail, String at) {
    }

    private final AlertDiagnosisRepository repository;

    public AlertDiagnosisStore(AlertDiagnosisRepository repository) {
        this.repository = repository;
    }

    /** 落一条诊断记录(追加,seq 自增) */
    public DiagnosisRecord add(String fingerprint, String alertName, String taskName,
                               String outcome, String report, String proposal,
                               boolean notified, String notifyDetail) {
        AlertDiagnosisEntity saved = repository.save(new AlertDiagnosisEntity(
                fingerprint, alertName,
                taskName == null || taskName.isBlank() ? "-" : taskName,
                outcome, report == null ? "" : report, proposal == null ? "" : proposal,
                notified, notifyDetail == null ? "" : notifyDetail, Instant.now()));
        return toDto(saved);
    }

    /** 最近记录(新→旧,按 seq 倒序) */
    public List<DiagnosisRecord> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findAllByOrderBySeqDesc(PageRequest.of(0, limit)).stream()
                .map(AlertDiagnosisStore::toDto)
                .toList();
    }

    /** 累计诊断记录数 */
    public long total() {
        return repository.count();
    }

    private static DiagnosisRecord toDto(AlertDiagnosisEntity e) {
        return new DiagnosisRecord(e.getSeq(), e.getFingerprint(), e.getAlertName(), e.getTaskName(),
                e.getOutcome(), e.getReport(), e.getProposal(), e.isNotified(), e.getNotifyDetail(),
                e.getAt().toString());
    }
}
