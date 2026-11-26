package com.dolphin.cluster;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多实例发号器(可选实现):时间戳 + 实例号 + 序列,号段按实例号分段,跨进程不撞号。
 *
 * <p>号段构成(纯 Java 计算,零依赖):
 * <pre>
 *   T{自纪元毫秒 base36}-{实例号 base36}-{进程内序列 base36}
 *   例:T1PF3K2-3-1 / T1PF3K2-3-2 / T1PF3K2-4-1(另一实例)
 * </pre>
 * 实例号取 {@code dolphin.cluster.worker-id}(0~1023,多实例必须唯一,通常来自
 * K8s StatefulSet 序号或部署配置)。两个进程即使在同一毫秒发号,实例号位不同,
 * 整号必然不同;同实例内序列单调递增,也不会重复。
 *
 * <p>为什么不用标准 64 位 Snowflake:本系统的工单号是<b>给人看的字符串</b>(URL、
 * 审计、演示里都会出现),可读性优先于位宽;这里只需要"跨进程不重复"这一个性质,
 * 不需要单调递增数值/时间回拨保护。真要接标准 Snowflake,同样落在这个接口后面。
 */
public class WorkerTicketIdGenerator implements TicketIdGenerator {

    /** 实例号上限:1024 个实例,常规部署规模足够;超出直接启动失败(宁早失败不静默撞号) */
    private static final int MAX_WORKER_ID = 1023;

    /**
     * 自定义纪元:2020-01-01T00:00:00Z。
     * 用相对纪元而非绝对毫秒,是为了把时间戳位压短(当前约 5 位 base36 而非 7 位),
     * 号段更短;纪元一旦上线不再更改(改纪元等于换号段格式,老号仍可读)。
     */
    private static final long EPOCH_MILLIS = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();

    private final int workerId;
    /** 进程内序列:同实例内单调递增,保证同毫秒多单也不重复 */
    private final AtomicLong seq = new AtomicLong();

    public WorkerTicketIdGenerator(int workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "dolphin.cluster.ticket-id.worker-id 必须在 0~" + MAX_WORKER_ID + " 之间,当前=" + workerId);
        }
        this.workerId = workerId;
    }

    @Override
    public String next() {
        long relativeMillis = System.currentTimeMillis() - EPOCH_MILLIS;
        return "T" + Long.toString(relativeMillis, 36).toUpperCase()
                + "-" + Long.toString(workerId, 36).toUpperCase()
                + "-" + Long.toString(seq.incrementAndGet(), 36).toUpperCase();
    }
}
