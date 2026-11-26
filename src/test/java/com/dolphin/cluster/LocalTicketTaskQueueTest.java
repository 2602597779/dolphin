package com.dolphin.cluster;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务队列(P0 第 3 处接口留口):把"投递被拒绝"从异常翻译成返回值,语义与改造前一致。
 *
 * <p>关键契约:返回 false 即"任务不会被执行",调用方必须走失败补偿(转人工);
 * 背压拒绝绝不能静默吞掉任务。
 */
class LocalTicketTaskQueueTest {

    @Test
    void 正常投递返回true并被执行() {
        AtomicInteger ran = new AtomicInteger();
        TicketTaskQueue queue = new LocalTicketTaskQueue(Runnable::run);

        assertThat(queue.submit(ran::incrementAndGet)).isTrue();
        assertThat(ran).hasValue(1);
    }

    @Test
    void 执行器拒绝时返回false且不抛异常() {
        // 执行器关闭期(发布/停机)的既有语义:runner 拿到 false 就地补偿转人工
        TicketTaskQueue queue = new LocalTicketTaskQueue(task -> {
            throw new RejectedExecutionException("shutdown");
        });

        assertThat(queue.submit(() -> { })).isFalse();
    }

    @Test
    void 有界队列满时背压拒绝() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        TicketTaskQueue queue = new LocalTicketTaskQueue(pool);
        try {
            // 唯一线程被占住(等 started 确认已开工,避免"任务还在队列里"的时序歧义)
            assertThat(queue.submit(() -> {
                started.countDown();
                await(release);
            })).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(queue.submit(() -> { })).isTrue();   // 进队列(容量 1)
            assertThat(queue.submit(() -> { })).isFalse();  // 队列满 → 背压拒绝
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
