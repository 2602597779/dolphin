package com.dolphin.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储的进程内默认实现(单实例语义,与抽取接口前行为逐字节一致)。
 *
 * <p>带空闲过期清理:默认 30 分钟未访问的会话由定时任务移除,避免无界增长。
 * 粘性/待审批状态随会话一起过期,等价于"流程超时自动放弃"。
 *
 * <p>多实例不适用:两个节点各有自己的 Map,会话粘性会在负载均衡下丢失。
 * 多实例部署需提供外部化实现并标 {@code @Primary} 覆盖(见 {@link SessionStore} 契约)。
 */
@Component
public class LocalSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(LocalSessionStore.class);

    private final ConcurrentHashMap<String, DialogContext> sessions = new ConcurrentHashMap<>();
    private final Duration idleTtl;

    public LocalSessionStore() {
        this(Duration.ofMinutes(30));
    }

    /** 测试可注入短 TTL */
    public LocalSessionStore(Duration idleTtl) {
        this.idleTtl = idleTtl;
    }

    @Override
    public DialogContext getOrCreate(String sessionId) {
        DialogContext ctx = sessions.computeIfAbsent(sessionId, DialogContext::new);
        ctx.touch();
        return ctx;
    }

    /** 进程内实现:会话对象由容器持有,handler 就地修改即生效,无需写回动作。 */
    @Override
    public void save(DialogContext ctx) {
        if (ctx != null) {
            ctx.touch();
        }
    }

    /** 每 5 分钟清理空闲超时的会话 */
    @Override
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
    @Override
    public int size() {
        return sessions.size();
    }
}
