package com.dolphin.notify;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知链路「MQ 语义」实现:<b>进程内</b>有界队列 + 单消费者线程,不是真实消息中间件。
 *
 * <p>为什么需要(而非直发):钉钉自定义机器人有 20 条/分钟的硬限流,告警风暴下同步直发只会
 * 连环失败。MQ 的缓冲/限速/重试三件套价值在此可真实兑现:
 * <ul>
 *   <li><b>缓冲</b>:生产端只投递不等待(图执行/SLA 巡检不被通知拖慢);队列<b>有界</b>,
 *       满即拒绝并计数(旁路通知可丢,不阻塞主流程);</li>
 *   <li><b>限速</b>:单消费者按 {@code dolphin.notify.interval-ms}(默认 3s≈20 条/分钟)匀速消费;</li>
 *   <li><b>重试</b>:单条失败线性退避重试({@code max-retries} 次重试,共 max-retries+1 次尝试),
 *       耗尽进死信(计数 + WARN 留痕,不阻塞后续消息)。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>:队列在进程内存中,重启即丢未消费消息、无法多实例分发——这两点正是
 * 生产换 RocketMQ 的理由。接口 {@link NotifySender} 已留口,换真实 MQ 只替换本实现类,
 * 调用方零改动;默认 {@code channel=direct} 走既有同步直发语义(offline 演示/测试不受影响)。
 *
 * <p>指标(Micrometer,Prometheus 端自动转下划线):
 * {@code dolphin_notify_queue_depth}(排队深度)/
 * {@code dolphin_notify_queue_dropped_total}(队列满被拒数)/
 * {@code dolphin_notify_dead_letter_total}(死信数)。
 */
public class MqNotifySender implements NotifySender {

    private static final Logger log = LoggerFactory.getLogger(MqNotifySender.class);

    /** 空闲轮询间隔:无消息时阻塞等待,避免忙等 */
    private static final long IDLE_POLL_MS = 200L;

    /** 最终下游(真实发送 = 钉钉直发) */
    private final NotifySender downstream;
    private final NotifyProperties properties;
    private final BlockingQueue<NotifyMessage> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Counter droppedCounter;
    private final Counter deadLetterCounter;
    private final AtomicLong deadLetters = new AtomicLong();

    public MqNotifySender(NotifySender downstream, NotifyProperties properties, MeterRegistry meterRegistry) {
        this.downstream = downstream;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
        Gauge.builder("dolphin.notify.queue.depth", queue, BlockingQueue::size)
                .description("通知队列排队深度(持续增长=消费速率不足)")
                .register(meterRegistry);
        this.droppedCounter = Counter.builder("dolphin.notify.queue.dropped")
                .description("队列满被拒绝的通知数(旁路可丢,不阻塞主流程)")
                .register(meterRegistry);
        this.deadLetterCounter = Counter.builder("dolphin.notify.dead.letter")
                .description("重试耗尽进入死信的通知数")
                .register(meterRegistry);
        this.worker = new Thread(this::consumeLoop, "notify-consumer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean isConfigured() {
        return downstream.isConfigured();
    }

    /** 生产端:只投递不等待(队列满返回 false 并计数,通知旁路可丢) */
    @Override
    public boolean send(String title, String text) {
        NotifyMessage message = new NotifyMessage(title, text, Instant.now());
        if (queue.offer(message)) {
            return true;
        }
        droppedCounter.increment();
        log.warn("[notify-queue-full] 队列已满(容量 {}),通知被拒绝:{}", properties.getQueueCapacity(), title);
        return false;
    }

    /** 单消费者循环:取一条 → 发送(含重试)→ 限速等待 → 取下一条 */
    private void consumeLoop() {
        while (running.get()) {
            NotifyMessage message;
            try {
                message = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (message == null) {
                continue;
            }
            try {
                deliver(message);
            } catch (Exception e) {
                // 单条消息异常隔离:记日志后继续消费,绝不让一条消息卡死整条通知链路
                log.warn("[notify-consumer] 单条消息处理异常(已隔离,继续下一条): {}", e.getMessage());
            }
            // 限速:两条通知之间的最小间隔(默认 3s≈钉钉 20 条/分钟)
            sleep(Math.max(0, properties.getIntervalMs()));
        }
    }

    /**
     * 投递一条消息。
     *
     * <p>下游未配置(无 webhook):单次调用即降级日志,重试没有意义(与直发语义一致);
     * 下游已配置:失败按 {@code retry-backoff-ms × n} 线性退避重试,耗尽进死信。
     */
    private void deliver(NotifyMessage message) {
        if (!downstream.isConfigured()) {
            downstream.send(message.title(), message.text());
            return;
        }
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (downstream.send(message.title(), message.text())) {
                return;
            }
            if (attempt < maxAttempts) {
                sleep(Math.max(0, properties.getRetryBackoffMs() * attempt));
            }
        }
        deadLetterCounter.increment();
        deadLetters.incrementAndGet();
        log.warn("[notify-dead-letter] 消息重试 {} 次仍失败,进死信(计数+留痕,不阻塞后续): {}",
                maxAttempts, message.title());
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 关停消费者(Spring 销毁回调);未消费消息随进程丢弃,诚实边界见类注释 */
    public void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    /** 消费者线程是否仍存活(测试可见) */
    boolean isConsumerAlive() {
        return worker.isAlive();
    }

    /** 死信计数(测试可见) */
    long deadLetterCount() {
        return deadLetters.get();
    }
}
