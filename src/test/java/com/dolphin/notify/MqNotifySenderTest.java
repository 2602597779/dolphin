package com.dolphin.notify;

import com.dolphin.alert.DingTalkNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知通道回归:直发透传、MQ 语义(入队异步送达/限速/重试/死信/队列满拒绝)、
 * 下游未配置不重试、消费者可关停、配置按 channel 选实现。
 *
 * <p>测试用 {@link FakeSender} 替代真实钉钉(可控失败/阻塞),真实 HTTP 发送已由
 * {@code DingTalkNotifierTest} 覆盖;时序断言留足余量,避免机器负载导致抖动。
 */
class MqNotifySenderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void directSenderDelegatesToDingTalkNotifier() {
        DingTalkNotifier notifier = mock(DingTalkNotifier.class);
        when(notifier.isConfigured()).thenReturn(true);
        when(notifier.sendMarkdown("标题", "正文")).thenReturn(true);

        DirectNotifySender sender = new DirectNotifySender(notifier);

        assertThat(sender.isConfigured()).isTrue();
        assertThat(sender.send("标题", "正文")).isTrue();
        verify(notifier).sendMarkdown("标题", "正文");
    }

    @Test
    void mqModeQueuesAndDeliversInOrder() {
        FakeSender fake = new FakeSender();
        MqNotifySender sender = new MqNotifySender(fake, props(10, 0, 2, 10), registry);
        try {
            assertThat(sender.send("t1", "b1")).isTrue();
            assertThat(sender.send("t2", "b2")).isTrue();

            awaitUntil("两条通知送达", () -> fake.delivered.size() == 2);
            assertThat(fake.delivered).containsExactly("t1", "t2");
        } finally {
            sender.shutdown();
        }
    }

    @Test
    void mqModeRateLimitsConsumption() {
        FakeSender fake = new FakeSender();
        MqNotifySender sender = new MqNotifySender(fake, props(10, 150, 1, 10), registry);
        try {
            sender.send("t1", "b1");
            sender.send("t2", "b2");

            awaitUntil("两条通知送达", () -> fake.deliveredAt.size() == 2);
            long gapMs = Duration.between(fake.deliveredAt.get(0), fake.deliveredAt.get(1)).toMillis();
            // 限速间隔 150ms:留 50ms 容差(调度抖动),验证"两条消息之间确实被拉开"
            assertThat(gapMs).isGreaterThanOrEqualTo(100L);
        } finally {
            sender.shutdown();
        }
    }

    @Test
    void failedSendIsRetriedBeforeSuccess() {
        FakeSender fake = new FakeSender();
        fake.failuresRemaining.set(1);
        MqNotifySender sender = new MqNotifySender(fake, props(10, 0, 2, 10), registry);
        try {
            sender.send("t1", "b1");

            awaitUntil("重试后送达", () -> fake.delivered.size() == 1);
            assertThat(fake.attempts.get()).isEqualTo(2); // 首次失败 + 1 次重试成功
            assertThat(sender.deadLetterCount()).isZero();
        } finally {
            sender.shutdown();
        }
    }

    @Test
    void exhaustedRetriesGoToDeadLetter() {
        FakeSender fake = new FakeSender();
        fake.failuresRemaining.set(100);
        MqNotifySender sender = new MqNotifySender(fake, props(10, 0, 2, 10), registry);
        try {
            sender.send("t1", "b1");

            awaitUntil("进入死信", () -> sender.deadLetterCount() == 1);
            // max-retries=2 → 共尝试 3 次(与 LlmGuard 的 max-retries 语义一致)
            assertThat(fake.attempts.get()).isEqualTo(3);
            assertThat(registry.counter("dolphin.notify.dead.letter").count()).isEqualTo(1.0);
            assertThat(fake.delivered).isEmpty();
        } finally {
            sender.shutdown();
        }
    }

    @Test
    void unconfiguredDownstreamDegradesWithoutRetry() throws InterruptedException {
        FakeSender fake = new FakeSender();
        fake.configured.set(false);
        MqNotifySender sender = new MqNotifySender(fake, props(10, 0, 3, 10), registry);
        try {
            sender.send("t1", "b1");

            awaitUntil("单次降级调用", () -> fake.attempts.get() >= 1);
            Thread.sleep(150); // 留出"若错误重试"的观察窗口
            assertThat(fake.attempts.get()).isEqualTo(1);
            assertThat(sender.deadLetterCount()).isZero();
        } finally {
            sender.shutdown();
        }
    }

    @Test
    void queueFullRejectsSendAndCountsDropped() {
        FakeSender fake = new FakeSender();
        fake.block = true;
        MqNotifySender sender = new MqNotifySender(fake, props(1, 0, 1, 10), registry);
        try {
            assertThat(sender.send("m1", "b1")).isTrue();
            // 消费者取走 m1(队列已空)并在下游阻塞,才能确定性地填满容量 1 的队列
            awaitUntil("消费者取走首条并在下游阻塞", () -> fake.attempts.get() == 1);

            assertThat(sender.send("m2", "b2")).isTrue();
            assertThat(sender.send("m3", "b3")).isFalse();
            assertThat(registry.counter("dolphin.notify.queue.dropped").count()).isEqualTo(1.0);
        } finally {
            fake.releaseLatch.countDown();
            sender.shutdown();
        }
    }

    @Test
    void shutdownStopsConsumerThread() {
        MqNotifySender sender = new MqNotifySender(new FakeSender(), props(10, 0, 1, 10), registry);

        assertThat(sender.isConsumerAlive()).isTrue();
        sender.shutdown();
        awaitUntil("消费者线程退出", () -> !sender.isConsumerAlive());
    }

    @Test
    void configChoosesImplementationByChannel() {
        DingTalkNotifier notifier = mock(DingTalkNotifier.class);
        NotifyConfig config = new NotifyConfig();

        assertThat(config.directNotifySender(notifier)).isInstanceOf(DirectNotifySender.class);

        NotifyProperties mqProps = props(10, 0, 1, 10);
        mqProps.setChannel("mq");
        NotifySender mqSender = config.mqNotifySender(notifier, mqProps, registry);
        try {
            assertThat(mqSender).isInstanceOf(MqNotifySender.class);
        } finally {
            ((MqNotifySender) mqSender).shutdown();
        }
    }

    // ---- 测试辅助 ----

    private static NotifyProperties props(int capacity, long intervalMs, int maxRetries, long backoffMs) {
        NotifyProperties properties = new NotifyProperties();
        properties.setQueueCapacity(capacity);
        properties.setIntervalMs(intervalMs);
        properties.setMaxRetries(maxRetries);
        properties.setRetryBackoffMs(backoffMs);
        return properties;
    }

    private static void awaitUntil(String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(3);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待超时:" + description);
    }

    /** 可控下游:统计尝试次数/送达记录,可模拟失败、未配置、阻塞消费 */
    private static final class FakeSender implements NotifySender {

        private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        private final List<Instant> deliveredAt = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private final AtomicBoolean configured = new AtomicBoolean(true);
        private final CountDownLatch releaseLatch = new CountDownLatch(1);
        private volatile boolean block;

        @Override
        public boolean isConfigured() {
            return configured.get();
        }

        @Override
        public boolean send(String title, String text) {
            attempts.incrementAndGet();
            if (block) {
                try {
                    releaseLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!configured.get()) {
                return false;
            }
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return false;
            }
            delivered.add(title);
            deliveredAt.add(Instant.now());
            return true;
        }
    }
}
