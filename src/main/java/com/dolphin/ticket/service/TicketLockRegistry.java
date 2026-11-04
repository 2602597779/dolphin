package com.dolphin.ticket.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 工单级串行锁(阶段4优化 O1)。
 *
 * <p>同一工单的两轮追问若并发进入 reply,会出现"两请求同时清 checkpoint + 以同一 threadId
 * invoke"的踩踏:图状态互相覆盖、对话消息重复落库、状态前后不一致。这里按 ticketId
 * 提供进程内互斥,把"清断点 → invoke → 落库收尾"整段串行化。
 *
 * <p>锁对象带引用计数:最后一个持有者释放后从 map 移除,避免长期运行后按工单号无限堆积。
 *
 * <p><b>边界(面试口径)</b>:本实现只在单进程内有效。多实例部署时同一工单的请求可能落到
 * 不同节点,需改用数据库乐观锁(ticket.version)或分布式锁(Redis),否则串行化语义不成立。
 */
@Component
public class TicketLockRegistry {

    private final ConcurrentHashMap<String, LockRef> locks = new ConcurrentHashMap<>();

    /** 在工单锁内执行动作;锁在 finally 释放,异常不影响后续请求 */
    public <T> T withLock(String ticketId, Supplier<T> action) {
        LockRef ref = locks.compute(ticketId, (key, existing) -> {
            LockRef target = existing == null ? new LockRef() : existing;
            // holders 由 ref 自身监视器保护:与 finally 的递减互斥,保证"归零移除"无竞态
            synchronized (target) {
                target.holders++;
            }
            return target;
        });
        ref.lock.lock();
        try {
            return action.get();
        } finally {
            ref.lock.unlock();
            synchronized (ref) {
                if (--ref.holders == 0) {
                    locks.remove(ticketId, ref);
                }
            }
        }
    }

    /** 当前持锁/等锁的工单数(运维观测与单测断言用) */
    public int size() {
        return locks.size();
    }

    private static final class LockRef {
        private final ReentrantLock lock = new ReentrantLock();
        private int holders;
    }
}
