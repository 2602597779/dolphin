package com.dolphin.alert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook v4 载荷模型(仅取诊断链路需要的字段,未知字段忽略)。
 *
 * <p>参考 Alertmanager notify/webhook 数据格式:
 * status=firing/resolved,每条 alert 携带 fingerprint、labels(告警身份)、
 * annotations(摘要描述)、startsAt/endsAt(时间窗)。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerPayload(
        String version,
        String status,
        List<AlertItem> alerts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlertItem(
            String status,
            String fingerprint,
            String startsAt,
            String endsAt,
            Map<String, String> labels,
            Map<String, String> annotations) {

        /** 告警规则名(alertname label,与 Prometheus 规则中的 alert 对应) */
        public String alertName() {
            return labels == null ? "" : labels.getOrDefault("alertname", "");
        }

        public String severity() {
            return labels == null ? "" : labels.getOrDefault("severity", "");
        }

        /** 摘要:优先 annotations.summary,缺失则用规则名 */
        public String summary() {
            String s = annotations == null ? null : annotations.get("summary");
            return (s == null || s.isBlank()) ? alertName() : s;
        }
    }
}
