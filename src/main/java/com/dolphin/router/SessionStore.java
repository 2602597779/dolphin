package com.dolphin.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储(骨架阶段内存实现,TODO:接入 Redis 并共享多实例)。
 *
 * <p>带空闲过期清理:默认 30 分钟未访问的会话由定时任务移除,避免无界增长。
 * 粘性/待审批状态随会话一起过期,等价于"流程超时自动放弃"。
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final ConcurrentHashMap<String, DialogContext> sessions = new ConcurrentHashMap<>();
    private final Duration idleTtl;

    public SessionStore() {
        this(Duration.ofMinutes(30));
    }

    /** 测试可注入短 TTL */
    public SessionStore(Duration idleTtl) {
        this.idleTtl = idleTtl;
    }

    public DialogContext getOrCreate(String sessionId) {
        DialogContext ctx = sessions.computeIfAbsent(sessionId, DialogContext::new);
        ctx.touch();
        return ctx;
    }

    /** 每 5 分钟清理空闲超时的会话 */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void evictExpired() {
        long deadline = System.currentTimeMillis() - idleTtl.toMillis();
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().getLastAccessedAt() < deadline);
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("[session] 清理空闲会话 {} 个,剩余 {} 个", removed, sessions.size());
        }
    }

    /** 当前会话数(测试与观测用) */
    public int size() {
        return sessions.size();
    }
}
