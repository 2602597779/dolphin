package com.dolphin.router;

import com.dolphin.cluster.SessionStoreProperties;
import com.dolphin.router.repo.SessionContextEntity;
import com.dolphin.router.repo.SessionContextRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcSessionStore} 共享表语义(真实 H2 内存库):
 * 写回后可跨实例/重启读到、每次读出是快照对象(不再靠引用共享)、空闲过期清理、
 * 以及并发首次创建同一会话时"主键冲突 → 回读既有行"的兜底分支。
 */
@DataJpaTest
class JdbcSessionStoreTest {

    @Autowired
    private SessionContextRepository repository;

    private static SessionStoreProperties props(Duration idleTtl) {
        SessionStoreProperties properties = new SessionStoreProperties();
        properties.setKind("jdbc");
        properties.setIdleTtl(idleTtl);
        return properties;
    }

    @Test
    void 写回后新实例仍能读到粘性状态() {
        SessionStore store = new JdbcSessionStore(repository, props(Duration.ofMinutes(30)));
        DialogContext ctx = store.getOrCreate("s1");
        assertThat(ctx.getActiveCapability()).isNull();
        assertThat(ctx.getPendingTicketId()).isNull();

        ctx.setActiveCapability("ticket");
        ctx.setPendingTicketId("T123");
        store.save(ctx); // 契约:改完必须写回(调用方 ChatController 每轮末尾都会调)

        // 模拟重启 / 请求落到另一节点:全新实例(只共享数据库)也能读到同一份状态
        SessionStore restarted = new JdbcSessionStore(repository, props(Duration.ofMinutes(30)));
        DialogContext again = restarted.getOrCreate("s1");
        assertThat(again.getActiveCapability()).isEqualTo("ticket");
        assertThat(again.getPendingTicketId()).isEqualTo("T123");
    }

    @Test
    void 每次读出的是快照对象而非同一引用() {
        SessionStore store = new JdbcSessionStore(repository, props(Duration.ofMinutes(30)));
        DialogContext first = store.getOrCreate("s2");
        first.setPendingTicketId("T1");
        store.save(first);

        DialogContext second = store.getOrCreate("s2");
        // 与进程内实现的关键差异:引用不再共享(进程内实现此处是 isSameAs)
        assertThat(second).isNotSameAs(first);
        assertThat(second.getPendingTicketId()).isEqualTo("T1");
    }

    @Test
    void 空闲超过阈值的会话被巡检清理() {
        SessionStore store = new JdbcSessionStore(repository, props(Duration.ofMillis(50)));
        repository.save(new SessionContextEntity("old", null, null,
                System.currentTimeMillis() - 1000));
        repository.save(new SessionContextEntity("fresh", null, null, System.currentTimeMillis()));

        store.evictExpired();

        assertThat(store.size()).isEqualTo(1);
        assertThat(repository.findById("fresh")).isPresent();
        assertThat(repository.findById("old")).isEmpty();
    }

    @Test
    void size等于共享表行数() {
        SessionStore store = new JdbcSessionStore(repository, props(Duration.ofMinutes(30)));
        assertThat(store.size()).isZero();

        store.getOrCreate("s3");
        store.getOrCreate("s4");

        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void 并发首次创建同一会话时回读既有行() {
        // 两个实例同时首次访问同一 sessionId:后到的那个 saveAndFlush 撞主键唯一约束,
        // 此时必须回读对手写入的行,而不是把异常抛给调用方或返回空状态
        SessionContextRepository mockRepo = mock(SessionContextRepository.class);
        when(mockRepo.findById("dup"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new SessionContextEntity("dup", "ticket", "T9", 1L)));
        when(mockRepo.saveAndFlush(any(SessionContextEntity.class)))
                .thenThrow(new DataIntegrityViolationException("dup key"));

        SessionStore store = new JdbcSessionStore(mockRepo, props(Duration.ofMinutes(30)));
        DialogContext ctx = store.getOrCreate("dup");

        assertThat(ctx.getSessionId()).isEqualTo("dup");
        assertThat(ctx.getActiveCapability()).isEqualTo("ticket");
        assertThat(ctx.getPendingTicketId()).isEqualTo("T9");
    }
}
