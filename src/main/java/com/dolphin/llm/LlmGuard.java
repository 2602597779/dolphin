package com.dolphin.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * LLM 下游保护闸(阶段 O9):全系统所有模型调用的统一入口。
 *
 * <p>三件事各管一段:工单线程池削峰、本闸封顶下游并发、受理闸门管正确性。
 * 本闸只做两件事:
 * <ul>
 * <li><b>并发封顶</b>:{@link Semaphore}(maxConcurrency)限制同时打到 LLM 的
 * 请求数,超出部分在闸前排队等待——等待发生在调用方线程(ticket-graph 池或
 * 聊天异步链路)上,不占下游配额,也不占 HTTP 线程;</li>
 * <li><b>失败重试</b>:瞬时失败(网络抖动/限流)按线性退避重试,每次尝试
 * 重新竞争配额——退避等待期间不占着许可,把并发让给别的调用。</li>
 * </ul>
 *
 * <p>边界(诚实标注):本闸不做超时——同步 {@link Supplier} 无法被安全中断,
 * 请求级超时由 HTTP 客户端层(Spring AI 底层 RestClient)负责;重试对
 * "结构化输出解析失败"同样生效(模型输出不稳定时重试一次常可自愈);
 * 重试耗尽后原样抛出最后一次异常,由调用方既有降级逻辑兜底
 * (诊断转人工/质检降级/路由降下一级/问答降级文案)。
 */
@Component
public class LlmGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmGuard.class);

    private final Semaphore permits;
    private final int maxRetries;
    private final long retryBackoffMs;

    public LlmGuard(LlmGuardProperties properties) {
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrency()));
        this.maxRetries = Math.max(0, properties.getMaxRetries());
        this.retryBackoffMs = Math.max(0, properties.getRetryBackoffMs());
    }

    /**
     * 受保护地执行一次 LLM 调用。
     *
     * @param llmCall 模型调用体(含 prompt 构建与响应解析)
     * @param action  动作标识,用于日志定位(如 "triage"/"diagnosis"/"router-llm")
     * @param <T>     返回类型(content 为 String,entity 为目标 record)
     * @return 模型调用结果
     * @throws IllegalStateException 等待配额或退避时被中断(恢复中断位后抛出)
     * @throws RuntimeException      重试耗尽后原样抛出最后一次失败
     */
    public <T> T call(Supplier<T> llmCall, String action) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleepBeforeRetry(action, attempt);
            }
            acquirePermit(action);
            try {
                return llmCall.get();
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("LLM 调用失败(动作={}, 第 {}/{} 次尝试): {}",
                        action, attempt + 1, maxRetries + 1, e.getMessage());
            } finally {
                permits.release();
            }
        }
        throw lastFailure;
    }

    /** 每次尝试都重新竞争配额:退避等待期间不占许可 */
    private void acquirePermit(String action) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 调用等待并发配额时被中断: " + action, e);
        }
    }

    /** 线性退避:第 n 次重试前等待 retryBackoffMs × n */
    private void sleepBeforeRetry(String action, int attempt) {
        try {
            Thread.sleep(retryBackoffMs * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 调用退避等待时被中断: " + action, e);
        }
    }
}
