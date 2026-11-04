package com.dolphin.ticket.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段4优化 O1:工单级串行锁回归。
 *
 * <p>验证点:同一 ticketId 的动作互斥(并发计数不会 >1)、异常路径也释放锁、
 * 动作完成后锁对象从 map 移除不随工单号堆积。
 */
class TicketLockRegistryTest {

    private final TicketLockRegistry registry = new TicketLockRegistry();

    @Test
    void sameTicketActionsNeverOverlap() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlap = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return registry.withLock("T1", () -> {
                    if (inside.incrementAndGet() > 1) {
                        overlap.set(true); // 两个动作同时在临界区 = 串行化失效
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inside.decrementAndGet();
                    }
                    return null;
                });
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertThat(overlap).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    void exceptionStillReleasesLockAndNextCallerProceeds() {
        assertThatThrownBy(() -> registry.withLock("T1", () -> {
            throw new IllegalStateException("动作失败");
        })).isInstanceOf(IllegalStateException.class);

        // 锁已释放:后续请求可进入,锁对象也已清理
        assertThat(registry.withLock("T1", () -> "ok")).isEqualTo("ok");
        assertThat(registry.size()).isZero();
    }

    @Test
    void differentTicketsDoNotBlockEachOther() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();
        for (String ticketId : List.of("T1", "T2")) {
            futures.add(executor.submit(() -> registry.withLock(ticketId, () -> {
                bothInside.countDown();
                try {
                    return bothInside.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            })));
        }
        for (Future<?> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(true);
        }
        executor.shutdown();
        assertThat(registry.size()).isZero();
    }
}
