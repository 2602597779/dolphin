package com.dolphin.cluster;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地发号器(默认实现):毫秒(base36) + 进程内序号(base36),前缀 {@code T}。
 *
 * <p>这就是 P0 并发修复后原样搬过来的算法,行为逐字节不变:
 * 同毫秒并发提交由序号位区分,不碰撞;号段跨重启不复用(时间戳只增)。
 *
 * <p>边界(诚实说明):序号是<b>进程内</b>的——多实例部署时,两个进程在同一毫秒
 * 各自从 1 开始,会撞号。多实例请切 {@link WorkerTicketIdGenerator}
 * ({@code dolphin.cluster.ticket-id.strategy=worker})。
 */
public class LocalTicketIdGenerator implements TicketIdGenerator {

    /** 进程内自增序号:并发提交落在同一毫秒时由序号位区分,避免主键冲突 */
    private final AtomicLong seq = new AtomicLong();

    @Override
    public String next() {
        return "T" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + Long.toString(seq.incrementAndGet(), 36).toUpperCase();
    }
}
