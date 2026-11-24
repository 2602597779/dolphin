package com.dolphin.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmGuard 单测:并发封顶、失败重试、退避中断、指标记录四个核心语义。
 * 退避基数在测试里配到毫秒级,避免拖慢回归。
 */
class LlmGuardTest {

    private LlmGuard guard(int maxConcurrency, int maxRetries, long backoffMs) {
        return guard(maxConcurrency, maxRetries, backoffMs, new SimpleMeterRegistry());
    }

    private LlmGuard guard(int maxConcurrency, int maxRetries, long backoffMs, SimpleMeterRegistry registry) {
        LlmGuardProperties p = new LlmGuardProperties();
        p.setMaxConcurrency(maxConcurrency);
        p.setMaxRetries(maxRetries);
        p.setRetryBackoffMs(backoffMs);
        return new LlmGuard(p, registry);
    }

    @Test
    @DisplayName("正常路径:配额内调用直接返回结果")
    void callReturnsSupplierResult() {
        LlmGuard guard = guard(2, 0, 0);
        assertThat(guard.call(() -> "ok", "test")).isEqualTo("ok");
    }

    @Test
    @DisplayName("并发封顶:maxConcurrency=1 时后到者等先到者释放后才进入")
    void secondCallWaitsWhenConcurrencyExhausted() throws Exception {
        LlmGuard guard = guard(1, 0, 0);
        CountDownLatch insideCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> guard.call(() -> {
                order.add("first-enter");
                insideCall.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return "first";
            }, "first"));

            assertThat(insideCall.await(2, TimeUnit.SECONDS)).isTrue();

            Future<String> second = pool.submit(() -> guard.call(() -> {
                order.add("second-enter");
                return "second";
            }, "second"));

            // 给 second 足够时间尝试进入:它必须仍被挡在闸外
            Thread.sleep(150);
            assertThat(order).containsExactly("first-enter");

            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(order).containsExactly("first-enter", "second-enter");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("失败重试:前两次失败第三次成功,共尝试 3 次")
    void retriesUntilSuccess() {
        LlmGuard guard = guard(2, 2, 1);
        AtomicInteger attempts = new AtomicInteger();
        String result = guard.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("boom");
            }
            return "recovered";
        }, "test");
        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("重试耗尽:原样抛出最后一次异常,总尝试 = maxRetries + 1")
    void throwsLastFailureWhenRetriesExhausted() {
        LlmGuard guard = guard(2, 2, 1);
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> guard.call(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("always-fail-" + attempts.get());
        }, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("always-fail-3");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("退避等待被中断:恢复中断位并抛 IllegalStateException")
    void interruptionDuringBackoffRestoresFlag() throws Exception {
        LlmGuard guard = guard(1, 5, 60_000); // 退避极长,确保 sleep 阶段被打断
        CountDownLatch firstAttemptDone = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean(false);

        Thread caller = new Thread(() -> {
            try {
                guard.call(() -> {
                    firstAttemptDone.countDown();
                    throw new IllegalStateException("fail-once");
                }, "test");
            } catch (Throwable t) {
                caught.set(t);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        assertThat(firstAttemptDone.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // 等 caller 进入退避 sleep
        caller.interrupt();
        caller.join(2000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
        assertThat(interruptFlagRestored.get()).isTrue();
    }

    @Test
    @DisplayName("指标:按 action 分桶记录耗时(成败都记),配额水位 gauge 反映剩余许可")
    void recordsTimerPerActionAndPermitsGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGuard guard = guard(2, 1, 1, registry);

        guard.call(() -> "ok", "triage");
        assertThatThrownBy(() -> guard.call(() -> { throw new IllegalStateException("boom"); }, "triage"))
                .isInstanceOf(IllegalStateException.class);
        guard.call(() -> "ok", "diagnosis");

        // triage:成功 1 次 + 失败重试 2 次 = 3 次尝试全记录;diagnosis:1 次
        assertThat(registry.get("dolphin.llm.call").tag("action", "triage").timer().count()).isEqualTo(3);
        assertThat(registry.get("dolphin.llm.call").tag("action", "diagnosis").timer().count()).isEqualTo(1);
        // 配额水位 gauge:调用结束后许可应全部归还
        assertThat(registry.get("dolphin.llm.guard.permits").gauge().value()).isEqualTo(2.0);
    }
}
