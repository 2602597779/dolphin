package com.dolphin.cluster;

import com.dolphin.cluster.repo.TicketTaskEntity;
import com.dolphin.cluster.repo.TicketTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 任务队列的共享任务表实现(表 ticket_task):把"投递"从纯进程内动作变成一条可查、可抢、可巡检的流水。
 *
 * <p><b>它提供什么</b>
 * <ul>
 *   <li>投递留痕:每次 {@link #submit} 先落一行 PENDING,运维/演示可直接查表看到收单轨迹;</li>
 *   <li>抢占标记:执行前用条件 UPDATE 把 PENDING 抢成 RUNNING 并写下 workerId(pid@host),
 *       多实例下"谁在执行"有据可查,重复执行在 SQL 层被挡住;</li>
 *   <li>终态收口:DONE / FAILED / REJECTED 全部落库(失败原因截断入库),异常不再只存在于日志里;</li>
 *   <li>孤儿检测:{@link #sweepStale()} 周期巡检,滞留未终态的任务标记 STUCK 并告警
 *       ——覆盖"进程崩在任务执行中"这类原本完全不可见的情况。</li>
 * </ul>
 *
 * <p><b>它不提供什么(诚实边界)</b>:任务体是 {@code Runnable},{@link TicketTaskQueue#submit}
 * 的签名里没有工单号也没有可恢复的执行意图,而且投递方(图执行类)在本阶段冻结不改。
 * 因此本实现<b>不做跨进程重建执行</b>——那需要"消息契约 + 消费者 + 幂等语义"(真 MQ 的活儿),
 * 不在本阶段范围。本实现的价值是可观测与可巡检,不是"重启任务接着跑"。
 *
 * <p>语义对齐:{@link #submit} 返回 false 的含义与进程内实现完全一致——"任务不会被执行",
 * 调用方必须走失败补偿(转人工)。
 */
public class JdbcTicketTaskQueue implements TicketTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(JdbcTicketTaskQueue.class);

    static final String PENDING = "PENDING";
    static final String RUNNING = "RUNNING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";
    static final String REJECTED = "REJECTED";
    static final String STUCK = "STUCK";

    /** 未终态状态集合(孤儿巡检的输入) */
    private static final List<String> OPEN_STATUSES = List.of(PENDING, RUNNING);

    /** 孤儿巡检周期:1 分钟(判定阈值 stale-after 默认 5 分钟) */
    private static final long SWEEP_INTERVAL_MS = 60 * 1000L;

    /** detail 列上限 512,截断留余量 */
    private static final int DETAIL_MAX_LENGTH = 500;

    private final Executor executor;
    private final TicketTaskRepository repository;
    private final Duration staleAfter;
    private final String workerId;

    public JdbcTicketTaskQueue(Executor executor, TicketTaskRepository repository, Duration staleAfter) {
        this(executor, repository, staleAfter, ManagementFactory.getRuntimeMXBean().getName());
    }

    /** 测试可注入固定的 workerId */
    public JdbcTicketTaskQueue(Executor executor, TicketTaskRepository repository, Duration staleAfter,
                               String workerId) {
        this.executor = executor;
        this.repository = repository;
        this.staleAfter = staleAfter;
        this.workerId = workerId;
        log.info("[task-queue] 使用共享任务表实现(表 ticket_task):投递留痕+抢占标记+孤儿巡检,"
                + "workerId={}, 孤儿判定阈值={}", this.workerId, this.staleAfter);
    }

    @Override
    public boolean submit(Runnable task) {
        String taskId = UUID.randomUUID().toString();
        repository.save(new TicketTaskEntity(taskId, PENDING, null, null, Instant.now(), null, null));
        try {
            executor.execute(() -> runLocally(taskId, task));
            return true;
        } catch (RejectedExecutionException e) {
            // 与进程内实现同一语义:投递失败必须让调用方知道,由它走补偿转人工
            finishQuietly(taskId, REJECTED, "本地执行器拒绝(已关闭或队列满):" + e.getMessage());
            log.warn("[task-queue] 任务 {} 投递被拒绝(本地执行器已关闭/队列满),已置 REJECTED 交由调用方补偿",
                    taskId);
            return false;
        }
    }

    /**
     * 每 1 分钟巡检:滞留超过 stale-after 仍未终态的任务判为孤儿,标记 STUCK 并告警。
     *
     * 只留痕不重建执行:原进程已丢任务体,重建需要消息契约(见类注释)。
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void sweepStale() {
        Instant now = Instant.now();
        int stuck = repository.markStuck(OPEN_STATUSES, now.minus(staleAfter), STUCK,
                "滞留超过 " + staleAfter + " 仍未收口(实例崩溃/任务体随进程丢失),需人工核对", now);
        if (stuck > 0) {
            log.warn("[task-queue] 巡检发现 {} 个滞留任务,已标记 STUCK 留痕(不重建执行)", stuck);
        }
    }

    /** 本地执行:先抢占(条件 UPDATE),抢不到说明已被别的实例接管或已终态 */
    private void runLocally(String taskId, Runnable task) {
        if (repository.claim(taskId, PENDING, RUNNING, workerId, Instant.now()) == 0) {
            log.debug("[task-queue] 任务 {} 已被其他实例抢占或已终态,本实例跳过执行", taskId);
            return;
        }
        try {
            task.run();
            finishQuietly(taskId, DONE, null);
        } catch (Throwable t) {
            // 任务自身异常不再抛给线程池(否则只有线程池的默认处理器能看到它),
            // 改为落库 FAILED + 错误日志:失败可见、可查、可统计
            String detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            finishQuietly(taskId, FAILED, detail);
            log.error("[task-queue] 任务执行失败 taskId={} : {}", taskId, detail, t);
        }
    }

    /** 终态落库;落库本身失败只告警,不能反过来掩盖任务执行的结果 */
    private void finishQuietly(String taskId, String status, String detail) {
        try {
            repository.finish(taskId, status, truncate(detail), Instant.now());
        } catch (RuntimeException e) {
            log.warn("[task-queue] 任务终态落库失败 taskId={} status={} : {}", taskId, status, e.toString());
        }
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= DETAIL_MAX_LENGTH ? detail : detail.substring(0, DETAIL_MAX_LENGTH) + "...";
    }
}
