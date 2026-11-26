package com.dolphin.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 本地任务队列(默认实现):包装进程内线程池,投递语义与改造前逐条对齐。
 *
 * <p>保持的既有契约:线程池本身仍由 {@code TicketAsyncConfig} 装配
 * ({@code ticketGraphExecutor},有界队列 500 + AbortPolicy,两个 gauge 指标不变);
 * 本类只负责把"投递被拒绝"这一个异常翻译成返回值 {@code false},让
 * {@code TicketGraphRunner} 用同一分支就地补偿转人工。
 *
 * <p>为什么拒绝细节只进日志、不进补偿文案:人工池 reason 与审计是给运营看的<b>稳定文案</b>
 * (不随异常消息漂移),而"队列满"还是"执行器已关"(服务停机/发布中)这类细节留在本类 WARN 日志里,
 * 排障时一眼可见。
 */
public class LocalTicketTaskQueue implements TicketTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(LocalTicketTaskQueue.class);

    private final Executor executor;

    public LocalTicketTaskQueue(Executor executor) {
        this.executor = executor;
    }

    @Override
    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // 有界队列满 / 执行器已关闭:拒绝比静默积压诚实,交回调用方补偿
            log.warn("[task-queue] 任务投递被拒绝,交由调用方补偿转人工: {}", e.getMessage());
            return false;
        }
    }
}
