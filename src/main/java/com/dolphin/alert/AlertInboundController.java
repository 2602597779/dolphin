package com.dolphin.alert;

import com.dolphin.ticket.infra.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook 接收端:告警链路的入口。
 *
 * <p>处理流程:解析载荷 → 逐条去重 → 首次告警落事件存储并审计
 * → 命中 alert_task 的告警提交异步 Agent 诊断(M2:直调 DiagnosisAgent,不走工单图)
 * → 诊断结论推送钉钉群(未配置 webhook 时降级日志,见 {@link DingTalkNotifier})。
 */
@RestController
@RequestMapping("/api/alert")
public class AlertInboundController {

    private static final Logger log = LoggerFactory.getLogger(AlertInboundController.class);

    private final AlertDeduplicator deduplicator;
    private final AlertEventStore eventStore;
    private final AuditLogger auditLogger;
    private final AlertDiagnosisService diagnosisService;
    private final AlertDiagnosisStore diagnosisStore;

    public AlertInboundController(AlertDeduplicator deduplicator,
                                  AlertEventStore eventStore,
                                  AuditLogger auditLogger,
                                  AlertDiagnosisService diagnosisService,
                                  AlertDiagnosisStore diagnosisStore) {
        this.deduplicator = deduplicator;
        this.eventStore = eventStore;
        this.auditLogger = auditLogger;
        this.diagnosisService = diagnosisService;
        this.diagnosisStore = diagnosisStore;
    }

    /** Alertmanager webhook 回调端点(配置在 alertmanager.yml 的 receiver.url) */
    @PostMapping("/webhook")
    public Map<String, Object> webhook(@RequestBody AlertmanagerPayload payload) {
        if (payload == null || payload.alerts() == null || payload.alerts().isEmpty()) {
            return Map.of("accepted", 0, "duplicated", 0, "diagnosisScheduled", 0);
        }
        int accepted = 0;
        int duplicated = 0;
        int scheduled = 0;
        for (AlertmanagerPayload.AlertItem alert : payload.alerts()) {
            if (!deduplicator.acceptIfNew(alert.fingerprint(), alert.startsAt())) {
                duplicated++;
                log.debug("[alert] 重复告警丢弃 fingerprint={}", alert.fingerprint());
                continue;
            }
            eventStore.add(alert);
            auditLogger.log("-", "alert-received",
                    "fingerprint=%s name=%s severity=%s status=%s".formatted(
                            alert.fingerprint(), alert.alertName(), alert.severity(), alert.status()));
            accepted++;
            // M2:命中 alert_task 则异步触发 Agent 诊断(未命中仅受理,返回值不阻塞本响应)
            if (diagnosisService.schedule(alert)) {
                scheduled++;
            }
        }
        return Map.of("accepted", accepted, "duplicated", duplicated,
                "totalAccepted", eventStore.totalAccepted(),
                "diagnosisScheduled", scheduled);
    }

    /** 查看最近受理的告警事件(调试/演示) */
    @GetMapping("/events")
    public List<AlertEventStore.AlertEvent> events(@RequestParam(defaultValue = "20") int limit) {
        return eventStore.recent(Math.min(limit, 200));
    }

    /** 查看最近的告警诊断记录(M2:含诊断报告、动作建议与通知结果) */
    @GetMapping("/diagnoses")
    public Map<String, Object> diagnoses(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("total", diagnosisStore.total(), "records", diagnosisStore.recent(Math.min(limit, 200)));
    }
}
