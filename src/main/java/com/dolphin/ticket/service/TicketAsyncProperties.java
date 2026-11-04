package com.dolphin.ticket.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 工单异步受理配置({@code dolphin.async.*},阶段 O8)。
 *
 * <p>受理即 202:submit/reply/approve 只做校验与受理落库,图执行投递到独立线程池;
 * HTTP 线程不再被 LLM 调用与云操作轮询占住(原同步 invoke 可达秒级)。
 */
@ConfigurationProperties(prefix = "dolphin.async")
public class TicketAsyncProperties {

    /**
     * 图执行线程池大小:并发跑图的工单数上限。
     * 队列无界(受理必达,宁可排队不拒单);排队的单状态为 PROCESSING,
     * 由 SLA 巡检兜底(响应计时器把 PROCESSING 计入"未响应"口径)。
     */
    private int poolSize = 4;

    /**
     * SSE 连接超时(0=不超时):工单到达终态时服务端主动 complete,
     * 客户端断开由 onError/onCompletion 回调清理,不依赖超时兜底。
     */
    private Duration sseTimeout = Duration.ZERO;

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }
}
