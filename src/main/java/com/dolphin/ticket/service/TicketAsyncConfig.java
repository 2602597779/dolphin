package com.dolphin.ticket.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工单异步执行装配(阶段 O8):图执行线程池。
 *
 * <p>受理即 202 后,图(含 LLM 调用、云 API 轮询验证)全部在该池执行,
 * 不再占用 Servlet 工作线程。池大小由 {@code dolphin.async.pool-size} 控制;
 * 任务队列无界——受理必达(排队中的单同样是 PROCESSING,SLA 响应计时器兜底),
 * 只有进程关闭期的拒绝才走失败补偿(转人工 + 审计)。
 *
 * <p>Bean 类型为 {@link ExecutorService}:Spring 关闭容器时自动调用 shutdown(),
 * 已入队任务尽量跑完;尚未开始的工单停留在 PROCESSING,由 SLA 巡检升级人工兜底。
 */
@Configuration
@EnableConfigurationProperties(TicketAsyncProperties.class)
public class TicketAsyncConfig {

    @Bean("ticketGraphExecutor")
    public ExecutorService ticketGraphExecutor(TicketAsyncProperties properties) {
        AtomicInteger seq = new AtomicInteger();
        int poolSize = Math.max(1, properties.getPoolSize());
        return Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "ticket-graph-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
