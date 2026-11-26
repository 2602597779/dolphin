package com.dolphin.cluster;

import com.dolphin.ticket.repo.TicketRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

/**
 * 多实例就绪层装配(P0 三件套):发号器 / SLA 防重守卫 / 任务队列。
 *
 * <p>装配口径与 {@code com.dolphin.notify.NotifyConfig} 一致:
 * <b>接口 + 默认实现 + 条件装配</b>,默认实现在,单机行为与改造前一致;
 * 升级为"真多实例"时只替换实现,调用方零改动。
 *
 * <ul>
 *   <li>发号器:两套实现都真实存在,用配置切换
 *       ({@code dolphin.cluster.ticket-id.strategy=local|worker},默认 local);</li>
 *   <li>SLA 防重守卫 / 任务队列:当前只有默认实现,不做假开关;
 *       未来实现(Redis 抢占 / 任务表 / 真 MQ)在装配处标 {@code @Primary} 覆盖即可
 *       ——构造注入按接口解析,{@code @Primary} 优先于默认实现,无需改调用方一行。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(TicketIdProperties.class)
public class ClusterConfig {

    /** 默认发号器:单实例语义与改造前逐字节一致(毫秒 base36 + 进程内序号) */
    @Bean
    @ConditionalOnProperty(name = "dolphin.cluster.ticket-id.strategy", havingValue = "local", matchIfMissing = true)
    public TicketIdGenerator localTicketIdGenerator() {
        return new LocalTicketIdGenerator();
    }

    /** 多实例发号器:号段按实例号分段,跨进程不撞号 */
    @Bean
    @ConditionalOnProperty(name = "dolphin.cluster.ticket-id.strategy", havingValue = "worker")
    public TicketIdGenerator workerTicketIdGenerator(TicketIdProperties properties) {
        return new WorkerTicketIdGenerator(properties.getWorkerId());
    }

    /**
     * 默认 SLA 防重守卫:条件 UPDATE 原子抢占,多实例天然安全。
     *
     * <p>替换为集中式实现(Redis SETNX / 分布式锁)时,新增同类型 Bean 并标 {@code @Primary}。
     */
    @Bean
    public SlaBreachGuard dbSlaBreachGuard(TicketRepository ticketRepository) {
        return new DbSlaBreachGuard(ticketRepository);
    }

    /**
     * 默认任务队列:包装既有 {@code ticketGraphExecutor}(有界 500 + AbortPolicy),
     * 投递语义与改造前一致;外置实现(任务表 / 真 MQ)未来以 {@code @Primary} 覆盖。
     */
    @Bean
    public TicketTaskQueue localTicketTaskQueue(
            @Qualifier("ticketGraphExecutor") ExecutorService ticketGraphExecutor) {
        return new LocalTicketTaskQueue(ticketGraphExecutor);
    }
}
