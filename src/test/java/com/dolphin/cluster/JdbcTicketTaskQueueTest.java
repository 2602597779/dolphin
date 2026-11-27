package com.dolphin.cluster;

import com.dolphin.cluster.repo.TicketTaskEntity;
import com.dolphin.cluster.repo.TicketTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link JdbcTicketTaskQueue} 共享任务表语义(真实 H2 内存库):
 * 投递留痕 → 抢占 → 终态收口、执行器拒绝时返回 false(交由调用方补偿)、
 * 执行体异常落 FAILED 不外抛、以及孤儿任务巡检标记 STUCK。
 *
 * <p>测试用同线程执行器({@code Runnable::run}),让执行路径与断言共处同一事务,
 * 才能观察到落库结果({@code @DataJpaTest} 默认事务回滚,不留脏数据)。
 */
@DataJpaTest
class JdbcTicketTaskQueueTest {

    @Autowired
    private TicketTaskRepository repository;

    private JdbcTicketTaskQueue queue(Executor executor, Duration staleAfter) {
        return new JdbcTicketTaskQueue(executor, repository, staleAfter, "pid-test@host");
    }

    @Test
    void 投递成功则落PENDING并执行到DONE() {
        AtomicBoolean executed = new AtomicBoolean(false);

        boolean accepted = queue(Runnable::run, Duration.ofMinutes(5)).submit(() -> executed.set(true));

        assertThat(accepted).isTrue();
        assertThat(executed).isTrue();

        List<TicketTaskEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        TicketTaskEntity row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo("DONE");
        assertThat(row.getWorkerId()).isEqualTo("pid-test@host");
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(row.getDetail()).isNull();
    }

    @Test
    void 执行器拒绝时返回false并落REJECTED() {
        // 与进程内实现同一语义:投递失败必须返回 false,调用方据此走失败补偿(转人工)
        Executor shutdown = task -> {
            throw new RejectedExecutionException("executor shutdown");
        };

        boolean accepted = queue(shutdown, Duration.ofMinutes(5)).submit(() -> { });

        assertThat(accepted).isFalse();
        TicketTaskEntity row = repository.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo("REJECTED");
        assertThat(row.getDetail()).contains("本地执行器拒绝");
    }

    @Test
    void 执行体抛异常时落FAILED且不外抛() {
        JdbcTicketTaskQueue queue = queue(Runnable::run, Duration.ofMinutes(5));

        assertThatCode(() -> queue.submit(() -> {
            throw new IllegalStateException("图执行炸了");
        })).doesNotThrowAnyException();

        TicketTaskEntity row = repository.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getDetail()).contains("IllegalStateException").contains("图执行炸了");
        assertThat(row.getFinishedAt()).isNotNull();
    }

    @Test
    void 巡检把滞留未终态任务标记为STUCK() {
        JdbcTicketTaskQueue queue = queue(Runnable::run, Duration.ofMinutes(5));
        Instant old = Instant.now().minus(30, ChronoUnit.MINUTES);
        repository.save(new TicketTaskEntity("t-stale-pending", "PENDING", null, null, old, null, null));
        repository.save(new TicketTaskEntity("t-stale-running", "RUNNING", "pid-other@host", null, old, old, null));
        repository.save(new TicketTaskEntity("t-fresh", "PENDING", null, null, Instant.now(), null, null));
        repository.save(new TicketTaskEntity("t-done", "DONE", "pid-other@host", null, old, old, Instant.now()));

        queue.sweepStale();

        assertThat(repository.findById("t-stale-pending").orElseThrow().getStatus()).isEqualTo("STUCK");
        TicketTaskEntity running = repository.findById("t-stale-running").orElseThrow();
        assertThat(running.getStatus()).isEqualTo("STUCK");
        assertThat(running.getDetail()).contains("滞留超过");
        assertThat(running.getFinishedAt()).isNotNull();
        // 未滞留的、已终态的都不动
        assertThat(repository.findById("t-fresh").orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(repository.findById("t-done").orElseThrow().getStatus()).isEqualTo("DONE");
    }

    @Test
    void 抢占是条件更新_已终态任务抢不到() {
        Instant now = Instant.now();
        repository.save(new TicketTaskEntity("t-open", "PENDING", null, null, now, null, null));
        repository.save(new TicketTaskEntity("t-closed", "DONE", "pid-x@host", null, now, now, now));

        assertThat(repository.claim("t-open", "PENDING", "RUNNING", "pid-test@host", now)).isEqualTo(1);
        // 重复抢占(其他实例也看到这条 PENDING)在 SQL 层被挡住,只有首个事务能成功
        assertThat(repository.claim("t-open", "PENDING", "RUNNING", "pid-other@host", now)).isZero();
        assertThat(repository.claim("t-closed", "PENDING", "RUNNING", "pid-test@host", now)).isZero();

        TicketTaskEntity claimed = repository.findById("t-open").orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");
        assertThat(claimed.getWorkerId()).isEqualTo("pid-test@host");
    }
}
