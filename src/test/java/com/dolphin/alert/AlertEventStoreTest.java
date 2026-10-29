package com.dolphin.alert;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件存储:新事件在前列、超出容量淘汰最旧、载荷字段解析。
 */
class AlertEventStoreTest {

    private static AlertmanagerPayload.AlertItem alert(String fingerprint, String name) {
        return new AlertmanagerPayload.AlertItem("firing", fingerprint, "2026-10-23T10:00:00Z", null,
                Map.of("alertname", name, "severity", "critical", "uid", "u1"),
                Map.of("summary", name + " 摘要"));
    }

    @Test
    void 新事件排在最前且序号递增() {
        AlertEventStore store = new AlertEventStore();
        store.add(alert("fp1", "A"));
        store.add(alert("fp2", "B"));

        var recent = store.recent(10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).alertName()).isEqualTo("B");
        assertThat(recent.get(0).seq()).isGreaterThan(recent.get(1).seq());
        assertThat(store.totalAccepted()).isEqualTo(2);
    }

    @Test
    void 载荷字段正确解析() {
        AlertEventStore store = new AlertEventStore();
        store.add(alert("fp1", "ModelHttp5xxRateHigh"));

        var e = store.recent(1).get(0);
        assertThat(e.alertName()).isEqualTo("ModelHttp5xxRateHigh");
        assertThat(e.severity()).isEqualTo("critical");
        assertThat(e.summary()).isEqualTo("ModelHttp5xxRateHigh 摘要");
    }

    @Test
    void 超出容量淘汰最旧事件() {
        AlertEventStore store = new AlertEventStore();
        for (int i = 0; i < 250; i++) {
            store.add(alert("fp" + i, "A" + i));
        }
        assertThat(store.recent(300)).hasSize(200);
        // 最旧的 fp0 已被淘汰,最新 fp249 在首位
        assertThat(store.recent(1).get(0).alertName()).isEqualTo("A249");
    }
}
