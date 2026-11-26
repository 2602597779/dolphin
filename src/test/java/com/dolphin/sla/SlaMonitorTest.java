package com.dolphin.sla;

import com.dolphin.cluster.DbSlaBreachGuard;
import com.dolphin.cluster.LocalTicketIdGenerator;
import com.dolphin.cluster.SlaBreachGuard;
import com.dolphin.notify.NotifySender;
import com.dolphin.cloud.CloudApiProperties;
import com.dolphin.ticket.infra.audit.AuditLogEntity;
import com.dolphin.ticket.infra.audit.AuditLogRepository;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.repo.HumanTaskRepository;
import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阶段3 SLA 超时巡检回归:固定时钟 + 秒级时限,扫描逻辑确定性验证。
 *
 * <p>验证点:双计时器判定(响应/解决)、超时升级人工池、breach 留痕幂等(重复扫描不重复加急)、
 * 已在人工池(被认领)不重置处理进度、CLOSED 不再追责、null 优先级按 P3 兜底。
 */
@DataJpaTest
class SlaMonitorTest {

    /** 固定时钟:扫描一律发生在 NOW */
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private HumanTaskRepository humanTaskRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private NotifySender notifier;
    private SlaMonitor monitor;
    private HumanTicketPool pool;
    private AuditLogger auditLogger;
    private SlaProperties properties;
    private SlaBreachGuard breachGuard;

    @BeforeEach
    void setUp() {
        notifier = mock(NotifySender.class);
        when(notifier.send(startsWith("[SLA加急]"), contains("P1"))).thenReturn(true);
        auditLogger = new AuditLogger(new CloudApiProperties(), auditLogRepository);
        pool = new HumanTicketPool(humanTaskRepository, auditLogger,
                new TicketService(ticketRepository, new LocalTicketIdGenerator()),
                mock(ConversationService.class));
        // 秒级时限,固定时钟推进判定,无需真实等待
        properties = new SlaProperties();
        properties.setResponse(Map.of("P1", Duration.ofSeconds(30), "P2", Duration.ofMinutes(30),
                "P4", Duration.ofSeconds(30)));
        properties.setResolve(Map.of("P1", Duration.ofSeconds(60), "P2", Duration.ofMinutes(30),
                "P3", Duration.ofMinutes(5)));
        // 防重守卫默认走 DB 条件更新(与多实例部署同一实现)
        breachGuard = new DbSlaBreachGuard(ticketRepository);
        monitor = new SlaMonitor(ticketRepository, pool, auditLogger, notifier, properties,
                breachGuard, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void p1PendingApprovalPastResolveLimitIsEscalatedWithTrace() {
        // P1 待审批,创建于 5 分钟前(解决时限 60s)→ RESOLVE 超时
        ticket("t1", TicketService.STATUS_PENDING_APPROVAL, "P1", NOW.minus(Duration.ofMinutes(5)));

        monitor.scan();

        TicketEntity after = ticketRepository.findById("t1").orElseThrow();
        assertThat(after.getSlaBreachAt()).isEqualTo(NOW);
        assertThat(pool.get("t1")).isPresent();
        assertThat(pool.get("t1").orElseThrow().reason()).contains("SLA超时:RESOLVE").contains("P1");
        assertThat(auditRows("sla-breach")).hasSize(1);
        verify(notifier, times(1)).send(startsWith("[SLA加急]"), contains("t1"));
    }

    @Test
    void newTicketPastResponseLimitBreachesResponse() {
        // 卡在 NEW(图未产出回复),创建于 5 分钟前(P1 响应时限 30s)→ RESPONSE 超时
        ticket("t2", TicketService.STATUS_NEW, "P1", NOW.minus(Duration.ofMinutes(5)));

        monitor.scan();

        assertThat(ticketRepository.findById("t2").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
        assertThat(pool.get("t2")).isPresent();
        assertThat(auditRows("sla-breach")).hasSize(1);
    }

    @Test
    void processingTicketPastResponseLimitBreachesResponse() {
        // 阶段 O8 超时补偿兜底:受理即 202 后图卡死(线程丢失/云调用悬挂)卡在 PROCESSING,
        // 响应计时器把 PROCESSING 计入"未响应"口径(P1 响应时限 30s)→ RESPONSE 超时升级人工
        ticket("t2b", TicketService.STATUS_PROCESSING, "P1", NOW.minus(Duration.ofMinutes(5)));

        monitor.scan();

        assertThat(ticketRepository.findById("t2b").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
        assertThat(pool.get("t2b")).isPresent();
        assertThat(pool.get("t2b").orElseThrow().reason()).contains("SLA超时:RESPONSE");
        assertThat(auditRows("sla-breach")).hasSize(1);
    }

    @Test
    void ticketWithinLimitsIsUntouched() {
        // P2 待审批,创建 1 分钟(时限 30 分钟)→ 未超时
        ticket("t3", TicketService.STATUS_PENDING_APPROVAL, "P2", NOW.minus(Duration.ofMinutes(1)));

        monitor.scan();

        assertThat(ticketRepository.findById("t3").orElseThrow().getSlaBreachAt()).isNull();
        assertThat(pool.get("t3")).isEmpty();
        verifyNoInteractions(notifier);
        assertThat(auditRows("sla-breach")).isEmpty();
    }

    @Test
    void closedTicketIsNeverBreached() {
        ticket("t4", TicketService.STATUS_CLOSED, "P1", NOW.minus(Duration.ofHours(1)));

        monitor.scan();

        assertThat(ticketRepository.findById("t4").orElseThrow().getSlaBreachAt()).isNull();
        verifyNoInteractions(notifier);
    }

    @Test
    void repeatedScansDoNotDuplicateBreachOrUrgency() {
        ticket("t5", TicketService.STATUS_PENDING_APPROVAL, "P1", NOW.minus(Duration.ofMinutes(5)));

        monitor.scan();
        monitor.scan();
        monitor.scan();

        // breach 留痕只落一次,加急只发一次,人工池只有一行
        assertThat(auditRows("sla-breach")).hasSize(1);
        verify(notifier, times(1)).send(startsWith("[SLA加急]"), contains("t5"));
        assertThat(humanTaskRepository.findAll()).hasSize(1);
    }

    @Test
    void alreadyClaimedPoolEntryIsNotReset() {
        // 已被专员认领(CLAIMED)的转人工工单超时:只加急,不重置认领状态
        ticket("t6", TicketService.STATUS_ESCALATED, "P1", NOW.minus(Duration.ofMinutes(5)));
        pool.add("t6", "诊断动作建议不可信", "原始诉求");
        pool.claim("t6", "专员A");

        monitor.scan();

        assertThat(ticketRepository.findById("t6").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
        HumanTicketPool.HumanTask task = pool.get("t6").orElseThrow();
        assertThat(task.status()).isEqualTo(HumanTicketPool.STATUS_CLAIMED);
        assertThat(task.owner()).isEqualTo("专员A");
        verify(notifier, times(1)).send(startsWith("[SLA加急]"), contains("t6"));
    }

    @Test
    void nullPriorityFallsBackToP3Limits() {
        // 优先级缺失(老数据/内部渠道单):按 P3 档(解决 5 分钟)判定,创建于 10 分钟前 → 超时
        ticket("t7", TicketService.STATUS_PENDING_APPROVAL, null, NOW.minus(Duration.ofMinutes(10)));

        monitor.scan();

        assertThat(ticketRepository.findById("t7").orElseThrow().getSlaBreachAt()).isEqualTo(NOW);
    }

    @Test
    void breachGuardRejectionSkipsAllSideEffects() {
        // 多实例语义:另一个节点已抢占这张超时单的处置权 → 本节点即使扫描到也必须
        // 跳过全部副作用(不入池、不审计、不加急),否则同一超时单会被重复升级刷屏
        ticket("t8", TicketService.STATUS_PENDING_APPROVAL, "P1", NOW.minus(Duration.ofMinutes(5)));
        SlaMonitor otherNode = new SlaMonitor(ticketRepository, pool, auditLogger, notifier, properties,
                (id, at) -> false, Clock.fixed(NOW, ZoneOffset.UTC));

        otherNode.scan();

        assertThat(pool.get("t8")).isEmpty();
        assertThat(auditRows("sla-breach")).isEmpty();
        verifyNoInteractions(notifier);
    }

    // ---- 造数辅助 ----

    private void ticket(String id, String status, String priority, Instant createdAt) {
        ticketRepository.save(new TicketEntity(id, "m-" + id, status, "cust-001",
                priority, createdAt, createdAt, null));
    }

    private List<AuditLogEntity> auditRows(String action) {
        return auditLogRepository.findByAction(action);
    }
}
