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
     * LLM 调用为 IO 密集,该值应按下游模型服务的并发配额定(建议配额×0.8),而非 CPU 核数。
     * 排队的单状态为 PROCESSING,由 SLA 巡检兜底(响应计时器把 PROCESSING 计入"未响应"口径)。
     */
    private int poolSize = 4;

    /**
     * 图执行任务队列容量(有界):超过后新任务被拒绝,走失败补偿转人工——
     * 拒绝比静默积压诚实(无界队列=OOM 风险+积压不可见);"不丢单"底线由补偿保,
     * 而非无限排队。默认 500 ≈ 风暴场景(200 单/5 分钟)的充分缓冲。
     */
    private int queueCapacity = 500;

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

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }
}
