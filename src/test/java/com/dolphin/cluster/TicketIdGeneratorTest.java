package com.dolphin.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发号器(P0 第 1 处接口留口):本地实现行为与抽取前一致,实例实现跨进程不撞号。
 *
 * <p>工单号是给人看的字符串(URL/审计/演示都会出现),因此断言落在"唯一 + 可辨识"上:
 * 本地号 = {@code T + 毫秒36进制 + 序列36进制};实例号额外带实例号位 {@code T...-<wid>-<seq>}。
 */
class TicketIdGeneratorTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 500;

    @Test
    void 本地发号器格式与单线程唯一() {
        TicketIdGenerator generator = new LocalTicketIdGenerator();

        Set<String> ids = newConcurrentSet();
        for (int i = 0; i < 1000; i++) {
            ids.add(generator.next());
        }

        assertThat(ids).hasSize(1000);
        // 与改造前格式一致:T + 36 进制字符,无分隔符
        assertThat(ids).allMatch(id -> id.startsWith("T") && id.matches("T[0-9A-Z]+"));
    }

    @Test
    void 本地发号器同毫秒并发不撞号() throws Exception {
        TicketIdGenerator generator = new LocalTicketIdGenerator();

        assertThat(concurrentlyGenerate(generator)).hasSize(THREADS * PER_THREAD);
    }

    @Test
    void 实例发号器号段带回实例号且跨实例不重叠() throws Exception {
        TicketIdGenerator node3 = new WorkerTicketIdGenerator(3);
        TicketIdGenerator node10 = new WorkerTicketIdGenerator(10);

        Set<String> fromNode3 = concurrentlyGenerate(node3);
        Set<String> fromNode10 = concurrentlyGenerate(node10);

        assertThat(fromNode3).hasSize(THREADS * PER_THREAD);
        assertThat(fromNode10).hasSize(THREADS * PER_THREAD);
        // 实例号位不同 → 两个进程的号段天然不重叠(不依赖"是否同毫秒")
        assertThat(fromNode3).doesNotContainAnyElementsOf(fromNode10);
        // 实例号 10 以 36 进制呈现为 A
        assertThat(fromNode3).allMatch(id -> id.matches("T[0-9A-Z]+-3-[0-9A-Z]+"));
        assertThat(fromNode10).allMatch(id -> id.matches("T[0-9A-Z]+-A-[0-9A-Z]+"));
    }

    @Test
    void 实例号越界启动即失败() {
        // 宁早失败不静默撞号:越界配置在装配期就被拦下
        assertThatThrownBy(() -> new WorkerTicketIdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker-id");
        assertThatThrownBy(() -> new WorkerTicketIdGenerator(1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker-id");
        assertThat(new WorkerTicketIdGenerator(1023).next()).startsWith("T");
    }

    // ---- 辅助 ----

    /** 并发发号:统一放行后多线程各发 PER_THREAD 个,返回去重后的号集合 */
    private Set<String> concurrentlyGenerate(TicketIdGenerator generator) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = newConcurrentSet();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        ids.add(generator.next());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return ids;
    }

    private Set<String> newConcurrentSet() {
        return ConcurrentHashMap.newKeySet();
    }
}
