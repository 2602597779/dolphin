package com.dolphin.ticket.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工单异步执行装配(阶段 O8):图执行线程池。
 *
 * <p>受理即 202 后,图(含 LLM 调用、云 API 轮询验证)全部在该池执行,
 * 不再占用 Servlet 工作线程。池大小由 {@code dolphin.async.pool-size} 控制
 * (LLM 调用为 IO 密集,按下游模型服务并发配额定,而非 CPU 核数)。
 *
 * <p>任务队列<b>有界</b>({@code dolphin.async.queue-capacity},默认 500):真削峰而非
 * 无限积压——队列满时新任务被拒绝(AbortPolicy 抛 RejectedExecutionException),
 * 由 {@link TicketGraphRunner#tryEnqueue} 就地补偿转人工 + 审计,受理方以 503/转人工
 * 文案应答。取舍:拒绝比静默积压诚实(无界队列=OOM 风险+积压不可见),
 * "不丢单"底线由补偿保,而非无限排队;排队的单状态为 PROCESSING,由 SLA 巡检兜底。
 *
 * <p>指标(Micrometer,Prometheus 端自动转下划线):
 * {@code dolphin_graph_queue_depth}(排队深度,持续增长=消化速率不足)/
 * {@code dolphin_graph_pool_active}(正在跑图的线程数,触顶=并发跑满)。
 *
 * <p>Bean 类型为 {@link ExecutorService}:Spring 关闭容器时自动调用 shutdown(),
 * 已入队任务尽量跑完;尚未开始的工单停留在 PROCESSING,由 SLA 巡检升级人工兜底。
 */
@Configuration
@EnableConfigurationProperties(TicketAsyncProperties.class)
public class TicketAsyncConfig {

    @Bean("ticketGraphExecutor")
    public ExecutorService ticketGraphExecutor(TicketAsyncProperties properties, MeterRegistry meterRegistry) {
        AtomicInteger seq = new AtomicInteger();
        int poolSize = Math.max(1, properties.getPoolSize());
        int queueCapacity = Math.max(1, properties.getQueueCapacity());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "ticket-graph-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 队列满即拒绝(而非调用者线程执行):受理方立即感知背压,由 runner 补偿转人工
                new ThreadPoolExecutor.AbortPolicy());
        // 队列/活跃水位 gauge:强引用 executor(bean 单例),采集端未搭先落地供本地验证
        Gauge.builder("dolphin.graph.queue.depth", executor, e -> e.getQueue().size())
                .description("工单图执行队列排队深度")
                .register(meterRegistry);
        Gauge.builder("dolphin.graph.pool.active", executor, ThreadPoolExecutor::getActiveCount)
                .description("正在执行工单图的线程数")
                .register(meterRegistry);
        return executor;
    }
}
