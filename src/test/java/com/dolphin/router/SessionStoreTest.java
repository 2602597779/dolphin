package com.dolphin.router;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话存储(P0 抽接口后走默认进程内实现):同会话复用实例、空闲过期清理、写回不破坏粘性。
 */
class SessionStoreTest {

    @Test
    void 同一sessionId复用同一上下文() {
        SessionStore store = new LocalSessionStore(Duration.ofMinutes(30));
        DialogContext first = store.getOrCreate("a");
        DialogContext second = store.getOrCreate("a");
        assertThat(second).isSameAs(first);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void 空闲超过TTL的会话被清理() throws InterruptedException {
        SessionStore store = new LocalSessionStore(Duration.ofMillis(50));
        store.getOrCreate("old");
        Thread.sleep(80);
        store.getOrCreate("fresh");

        store.evictExpired();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.getOrCreate("fresh")).isNotNull();
    }

    @Test
    void 活跃会话touch后不被清理() throws InterruptedException {
        SessionStore store = new LocalSessionStore(Duration.ofMillis(80));
        store.getOrCreate("active");
        Thread.sleep(50);
        store.getOrCreate("active"); // touch 刷新访问时间
        Thread.sleep(50);

        store.evictExpired();

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void 写回后粘性状态不丢() {
        SessionStore store = new LocalSessionStore(Duration.ofMinutes(30));
        DialogContext ctx = store.getOrCreate("s1");
        ctx.setActiveCapability("ticket");
        ctx.setPendingTicketId("T123");

        store.save(ctx); // 进程内实现为空操作:就地修改已生效(外部化实现借此持久化)

        DialogContext again = store.getOrCreate("s1");
        assertThat(again).isSameAs(ctx);
        assertThat(again.getActiveCapability()).isEqualTo("ticket");
        assertThat(again.getPendingTicketId()).isEqualTo("T123");
    }
}
