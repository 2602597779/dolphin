package com.dolphin.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多实例就绪配置({@code dolphin.cluster.*},P0 三件套接口化)。
 *
 * <p>只放"有真实取舍"的开关:发号策略(local/worker 两套实现都真实存在,
 * 所以值得做成配置切换)。SLA 防重守卫、任务队列、会话存储当前只有<b>一个</b>实现,
 * 不做假开关——那三处靠"接口 + 装配处 {@code @Primary} 覆盖"留口,
 * 避免出现"配置项写 redis 但根本没有 Redis 实现"的装饰性配置。
 */
@ConfigurationProperties(prefix = "dolphin.cluster.ticket-id")
public class TicketIdProperties {

    /** 发号策略:local=毫秒+进程内序号(默认,单实例) | worker=时间戳+实例号+序列(多实例号段隔离) */
    private String strategy = "local";

    /** worker 策略下的实例号(0~1023):多实例部署必须唯一(K8s StatefulSet 序号 / 部署编号) */
    private int workerId = 0;

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public int getWorkerId() {
        return workerId;
    }

    public void setWorkerId(int workerId) {
        this.workerId = workerId;
    }
}
