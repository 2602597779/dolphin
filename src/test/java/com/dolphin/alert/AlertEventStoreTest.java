package com.dolphin.alert;

import com.dolphin.alert.repo.AlertEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件存储(P0 起落库):新事件在前、载荷字段解析;
 * fingerprint 为主键,同指纹重复受理 upsert 覆盖为最新(取代原内存 deque 的容量淘汰语义)。
 */
@DataJpaTest
class AlertEventStoreTest {

    @Autowired
    private AlertEventRepository repository;

    private static AlertmanagerPayload.AlertItem alert(String fingerprint, String name) {
        return new AlertmanagerPayload.AlertItem("firing", fingerprint, "2026-10-23T10:00:00Z", null,
                Map.of("alertname", name, "severity", "critical", "uid", "u1"),
                Map.of("summary", name + " 摘要"));
    }

    @Test
    void 新事件排在最前() throws InterruptedException {
        AlertEventStore store = new AlertEventStore(repository);
        store.add(alert("fp1", "A"));
        Thread.sleep(10); // received_at 是唯一排序键,错开写入时刻避免同毫秒顺序不稳定
        store.add(alert("fp2", "B"));

        var recent = store.recent(10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).alertName()).isEqualTo("B");
        assertThat(store.totalAccepted()).isEqualTo(2);
    }

    @Test
    void 载荷字段正确解析() {
        AlertEventStore store = new AlertEventStore(repository);
        store.add(alert("fp1", "ModelHttp5xxRateHigh"));

        var e = store.recent(1).get(0);
        assertThat(e.fingerprint()).isEqualTo("fp1");
        assertThat(e.alertName()).isEqualTo("ModelHttp5xxRateHigh");
        assertThat(e.severity()).isEqualTo("critical");
        assertThat(e.summary()).isEqualTo("ModelHttp5xxRateHigh 摘要");
        assertThat(e.receivedAt()).isNotBlank();
    }

    @Test
    void 同指纹重复受理覆盖为最新() throws InterruptedException {
        AlertEventStore store = new AlertEventStore(repository);
        store.add(alert("fp-dup", "A-old"));
        Thread.sleep(10);
        store.add(alert("fp-dup", "A-new"));

        // fingerprint 主键 upsert:台账只留最新一条
        assertThat(store.totalAccepted()).isEqualTo(1);
        var recent = store.recent(10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).alertName()).isEqualTo("A-new");
    }
}
