package com.dolphin.cluster;

import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时处置权守卫(P0 第 2 处接口留口):抢占 + 留痕是同一个原子动作,只有一个抢占者能成功。
 *
 * <p>单实例靠"内存幂等"就能防重复升级;多实例必须靠存储层的条件 UPDATE
 * ({@code where sla_breach_at is null}),本测试既验证串行语义,也验证并发下只有一个赢家。
 */
@DataJpaTest
class DbSlaBreachGuardTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private DbSlaBreachGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DbSlaBreachGuard(ticketRepository);
    }

    @Test
    void 首次抢占成功并留痕二次抢占失败() {
        ticket("t1");

        assertThat(guard.claim("t1", NOW)).isTrue();
        assertThat(ticketRepository.findById("t1").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
        // 二次抢占(另一节点/下一轮巡检)必须失败:否则会重复入池、重复加急
        assertThat(guard.claim("t1", NOW)).isFalse();
        assertThat(ticketRepository.findById("t1").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
    }

    @Test
    void 不同工单互不影响() {
        ticket("t2");
        ticket("t3");

        assertThat(guard.claim("t2", NOW)).isTrue();
        assertThat(guard.claim("t3", NOW)).isTrue();
    }

    /**
     * 并发抢占:多节点同时巡检同一张超时单,只能有一个赢家。
     *
     * <p>本方法不用测试事务包裹(NOT_SUPPORTED)——否则造数未提交,其他线程看不见该行。
     * 代价是本用例会真实提交数据,而 {@code @DataJpaTest} 的内存库在同配置下被多个测试类共享,
     * 因此必须:① 每个工作线程用 {@link TransactionTemplate} 给出独立事务边界(条件 UPDATE
     * 必须在真实事务里才会落库并释放行锁);② 用完即删,不给 SlaMonitorTest / MetricsServiceTest
     * 之类"全库口径"的用例留脏数据。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 并发抢占只有一个赢家() throws Exception {
        ticket("t4");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit((Callable<Boolean>) () -> {
                    start.await();
                    return Boolean.TRUE.equals(tx.execute(status -> guard.claim("t4", NOW)));
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get(15, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(ticketRepository.findById("t4").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
        } finally {
            pool.shutdownNow();
            // 本用例真提交过,用完即删:共享内存库里留下这张超时单,会污染其他类的全库口径断言
            ticketRepository.deleteById("t4");
        }
    }

    // ---- 造数辅助 ----

    private void ticket(String id) {
        Instant createdAt = NOW.minusSeconds(600);
        ticketRepository.save(new TicketEntity(id, "m-" + id, "PENDING_APPROVAL", "cust-001",
                "P1", createdAt, createdAt, null));
    }
}
