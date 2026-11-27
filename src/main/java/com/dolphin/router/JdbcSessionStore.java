package com.dolphin.router;

import com.dolphin.cluster.SessionStoreProperties;
import com.dolphin.router.repo.SessionContextEntity;
import com.dolphin.router.repo.SessionContextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 会话存储的共享表实现(表 session_context):把「路由粘性 + 待审批工单」放进数据库,
 * 多实例下任意节点读写同一份状态,取代单机 Map。
 *
 * <p>契约差异(与 {@link LocalSessionStore} 对照,调用方无感):
 * <ul>
 *   <li>进程内实现靠对象引用共享状态,{@code getOrCreate} 每次都返回<b>同一个</b>对象;
 *       本实现每次从库读出一份快照(新对象),因此<b>改完必须写回</b>——
 *       调用方(唯一入口 {@code ChatController})在每轮末尾调用 {@link #save} 已满足;</li>
 *   <li>进程内实现的过期清理只看本进程 Map;本实现是共享清理,任一节点巡检即全体生效。</li>
 * </ul>
 *
 * <p>并发语义:两个实例同时首次访问同一 sessionId 时,主键唯一约束保证只有一个插入成功,
 * 失败方回读对手写入的行——不会出现"同一会话两份互相覆盖的状态"。
 */
@Component
@ConditionalOnProperty(name = "dolphin.cluster.session-store.kind", havingValue = "jdbc")
public class JdbcSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionStore.class);

    /** 空闲过期清理周期:与进程内实现一致(5 分钟巡检一次) */
    private static final long EVICT_INTERVAL_MS = 5 * 60 * 1000L;

    private final SessionContextRepository repository;
    private final Duration idleTtl;

    public JdbcSessionStore(SessionContextRepository repository, SessionStoreProperties properties) {
        this.repository = repository;
        this.idleTtl = properties.getIdleTtl();
        log.info("[session-store] 使用共享表实现(表 session_context):多实例共享会话粘性,空闲 TTL={}",
                this.idleTtl);
    }

    /**
     * 取会话;不存在则建一行空会话。返回的是<b>快照对象</b>,改动需经 {@link #save} 落库。
     */
    @Override
    public DialogContext getOrCreate(String sessionId) {
        SessionContextEntity entity = repository.findById(sessionId)
                .orElseGet(() -> insertNewSession(sessionId));
        DialogContext ctx = new DialogContext(entity.getSessionId());
        ctx.setActiveCapability(entity.getActiveCapability());
        ctx.setPendingTicketId(entity.getPendingTicketId());
        ctx.touch();
        // 读出来就更新本对象内存里的访问时间;落库统一发生在 save(与调用方"轮末写回"契约一致)
        return ctx;
    }

    /** 写回:整行覆盖式写,last_accessed_at 用当前时间续期 */
    @Override
    @Transactional
    public void save(DialogContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.touch();
        repository.save(new SessionContextEntity(ctx.getSessionId(), ctx.getActiveCapability(),
                ctx.getPendingTicketId(), ctx.getLastAccessedAt()));
    }

    /** 每 5 分钟清理空闲超时的会话(共享清理:本节点删掉的行,其他节点也看不到了) */
    @Override
    @Scheduled(fixedDelay = EVICT_INTERVAL_MS)
    @Transactional
    public void evictExpired() {
        long deadline = System.currentTimeMillis() - idleTtl.toMillis();
        int removed = repository.deleteIdleBefore(deadline);
        if (removed > 0) {
            log.info("[session-store] 清理空闲会话 {} 个(阈值 {} 分钟),剩余 {} 个",
                    removed, idleTtl.toMinutes(), repository.count());
        }
    }

    /** 当前会话数(测试与观测用;含尚未过期的全部会话) */
    @Override
    public int size() {
        return (int) repository.count();
    }

    /**
     * 新建会话行:并发插入时主键唯一约束会挡住后来的那个,失败方回读已存在行即可
     * (回读再失败说明库不可用,把原异常抛出,不让它变成静默的空状态)。
     */
    private SessionContextEntity insertNewSession(String sessionId) {
        try {
            return repository.saveAndFlush(
                    new SessionContextEntity(sessionId, null, null, System.currentTimeMillis()));
        } catch (DataIntegrityViolationException e) {
            log.debug("[session-store] 会话 {} 已被其他实例创建,回读既有行", sessionId);
            return repository.findById(sessionId)
                    .orElseThrow(() -> new IllegalStateException("会话行主键冲突后回读失败:" + sessionId, e));
        }
    }
}
