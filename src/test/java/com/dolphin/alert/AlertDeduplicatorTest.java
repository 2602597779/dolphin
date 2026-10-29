package com.dolphin.alert;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警去重:同 fingerprint+startsAt 仅首次受理,时间窗变化视为新告警,TTL 过期后可再受理。
 */
class AlertDeduplicatorTest {

    @Test
    void 同一告警重复推送仅首次受理() {
        AlertDeduplicator dedup = new AlertDeduplicator(Duration.ofMinutes(10));
        assertThat(dedup.acceptIfNew("fp1", "2026-10-23T10:00:00Z")).isTrue();
        assertThat(dedup.acceptIfNew("fp1", "2026-10-23T10:00:00Z")).isFalse();
        assertThat(dedup.trackedCount()).isEqualTo(1);
    }

    @Test
    void startsAt变化视为新告警() {
        AlertDeduplicator dedup = new AlertDeduplicator(Duration.ofMinutes(10));
        assertThat(dedup.acceptIfNew("fp1", "2026-10-23T10:00:00Z")).isTrue();
        // 恢复后再次触发:同 fingerprint 但新 startsAt,应重新诊断
        assertThat(dedup.acceptIfNew("fp1", "2026-10-23T12:00:00Z")).isTrue();
    }

    @Test
    void 不同fingerprint互不影响() {
        AlertDeduplicator dedup = new AlertDeduplicator(Duration.ofMinutes(10));
        assertThat(dedup.acceptIfNew("fp1", "t")).isTrue();
        assertThat(dedup.acceptIfNew("fp2", "t")).isTrue();
    }

    @Test
    void TTL过期后同告警可再受理() throws InterruptedException {
        AlertDeduplicator dedup = new AlertDeduplicator(Duration.ofMillis(50));
        assertThat(dedup.acceptIfNew("fp1", "t")).isTrue();
        Thread.sleep(80);
        assertThat(dedup.acceptIfNew("fp1", "t")).isTrue();
    }
}
