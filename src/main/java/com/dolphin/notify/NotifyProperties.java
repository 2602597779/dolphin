package com.dolphin.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知通道配置({@code dolphin.notify.*})。
 *
 * <p>{@code channel=direct} 同步直发(既有语义,默认) | {@code mq} 进程内 MQ 语义
 * (有界缓冲 + 限速消费 + 失败重试 + 死信计数)。
 */
@ConfigurationProperties(prefix = "dolphin.notify")
public class NotifyProperties {

    /** 通道:direct=同步直发(默认) | mq=进程内 MQ 语义 */
    private String channel = "direct";

    /** mq 模式缓冲队列容量(有界;满即拒绝,旁路可丢) */
    private int queueCapacity = 200;

    /** mq 模式两条通知最小间隔(毫秒):默认 3000=20 条/分钟,对齐钉钉机器人限流 */
    private long intervalMs = 3000;

    /** 单条消息失败重试次数(共尝试 max-retries+1 次),耗尽进死信 */
    private int maxRetries = 2;

    /** 线性退避基数(毫秒):第 n 次重试前等待 retry-backoff-ms × n */
    private long retryBackoffMs = 1000;

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }
}
