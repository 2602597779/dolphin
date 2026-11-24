package com.dolphin.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 下游保护配置(阶段 O9):前缀 {@code dolphin.llm-guard.*}。
 *
 * <p>maxConcurrency 是全系统 LLM 并发闸:所有 Agent 的模型调用共用一个信号量,
 * 上限按下游模型服务的并发配额预留(生产建议配额 ×0.8)。工单线程池削的是
 * "图任务"的峰,本闸封的是"真正打到 LLM 的并发"——削峰≠提速,消化速率
 * 最终由下游配额决定,天花板在下游不在自己代码。
 */
@ConfigurationProperties(prefix = "dolphin.llm-guard")
public class LlmGuardProperties {

    /** 全系统 LLM 最大并发数,默认 16(按下游配额 ×0.8 预留) */
    private int maxConcurrency = 16;

    /** 单次调用失败后的最大重试次数,默认 2(总共尝试 3 次) */
    private int maxRetries = 2;

    /** 线性退避基数(毫秒):第 n 次重试前等待 retryBackoffMs × n,默认 500 */
    private long retryBackoffMs = 500;

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
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
