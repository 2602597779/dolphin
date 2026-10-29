package com.dolphin.alert;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警去重:同一条告警(fingerprint+startsAt)在 TTL 内只受理一次。
 *
 * <p>Alertmanager 在 repeat_interval 下会重复推送同一 firing 告警,
 * 不去重会导致同一故障被反复诊断。key 设计含 startsAt:
 * 同一 fingerprint 恢复后再次触发(startsAt 变化)视为新告警,应重新诊断。
 */
@Component
public class AlertDeduplicator {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /** fingerprint|startsAt → 受理时间戳 */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final Duration ttl;

    public AlertDeduplicator() {
        this(DEFAULT_TTL);
    }

    /** 测试可注入短 TTL */
    public AlertDeduplicator(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * 受理并判断是否首次出现。
     *
     * @return true=首次(应继续处理),false=TTL 内重复(丢弃)
     */
    public boolean acceptIfNew(String fingerprint, String startsAt) {
        evictExpired();
        String key = (fingerprint == null ? "?" : fingerprint) + "|" + (startsAt == null ? "?" : startsAt);
        return seen.putIfAbsent(key, System.currentTimeMillis()) == null;
    }

    private void evictExpired() {
        long deadline = System.currentTimeMillis() - ttl.toMillis();
        seen.entrySet().removeIf(e -> e.getValue() < deadline);
    }

    /** 当前去重窗口内已受理数量(观测用) */
    public int trackedCount() {
        return seen.size();
    }
}
