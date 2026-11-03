package com.dolphin.metrics;

import com.dolphin.ticket.infra.audit.AuditLogEntity;
import com.dolphin.ticket.infra.audit.AuditLogRepository;
import com.dolphin.ticket.repo.ApprovalDecisionRepository;
import com.dolphin.ticket.repo.HumanTaskRepository;
import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import com.dolphin.ticket.repo.HumanTaskEntity;
import com.dolphin.ticket.repo.ApprovalDecisionEntity;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段2 运营指标回归:造可控数据 → 断言 summary/tickets 每个数字可手工复核。
 *
 * <p>口径(与 MetricsService javadoc/README 一致):
 * 自动化结单率=CLOSED 且无人工池无审批 ÷ 总量;人工升级率=ESCALATED 或进过人工池 ÷ 总量;
 * 审批触发率=待审批或已有决策 ÷ 总量;质检通过率=PASS ÷ (PASS+REJECTED),契约无效不计分母;
 * MTTR=结单时间戳与创建时间差值的均值/中位数。
 *
 * <p>时间类指标(创建/结单时间戳)用直存实体的方式精确构造,非时间类走真实服务入口。
 */
@DataJpaTest
class MetricsServiceTest {

    /** 测试基准时刻(UTC,断言不受本地时区影响) */
    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private HumanTaskRepository humanTaskRepository;
    @Autowired
    private ApprovalDecisionRepository approvalDecisionRepository;

    private MetricsService service() {
        return new MetricsService(ticketRepository, auditLogRepository);
    }

    // ---- 全量汇总 ----

    @Test
    void summaryComputesEveryRateAndMttr() {
        // 数据布局(共 6 单,4 结单,2 全自动闭环):
        // t1/t2:纯自动结单 | t3:走审批后结单 | t4:转人工后结单 | t5:待审批中 | t6:刚创建
        ticket("t1", TicketService.STATUS_CLOSED, T0, T0.plusSeconds(60));   // MTTR 60s
        ticket("t2", TicketService.STATUS_CLOSED, T0, T0.plusSeconds(120));  // MTTR 120s
        ticket("t3", TicketService.STATUS_CLOSED, T0, T0.plusSeconds(300));  // MTTR 300s,有审批
        ticket("t4", TicketService.STATUS_CLOSED, T0, T0.plusSeconds(600));  // MTTR 600s,进人工池
        ticket("t5", TicketService.STATUS_PENDING_APPROVAL, T0, null);
        ticket("t6", TicketService.STATUS_NEW, T0, null);
        approvalDecision("t3");
        humanTask("t4");
        qualityAudit("PASS opinion=ok");
        qualityAudit("PASS opinion=good");
        qualityAudit("REJECTED → 已重开人工复核 opinion=bad");
        qualityAudit("CONTRACT_INVALID(不重开,仅留痕) opinion=x");

        MetricsService.Summary s = service().summary();

        assertThat(s.totalTickets()).isEqualTo(6);
        assertThat(s.byStatus()).containsEntry("CLOSED", 4L)
                .containsEntry("PENDING_APPROVAL", 1L)
                .containsEntry("NEW", 1L);
        assertThat(s.closedTickets()).isEqualTo(4);

        // 自动化闭环:仅 t1/t2(无人工池、无审批) → 2/6
        assertThat(s.automatedClosedTickets()).isEqualTo(2);
        assertThat(s.automationRatePct()).isEqualTo(33.3);

        // 人工升级:仅 t4(进过人工池) → 1/6
        assertThat(s.humanEscalatedTickets()).isEqualTo(1);
        assertThat(s.escalationRatePct()).isEqualTo(16.7);

        // 审批触发:t3(已有决策) + t5(待审批) → 2/6
        assertThat(s.approvalTickets()).isEqualTo(2);
        assertThat(s.approvalRatePct()).isEqualTo(33.3);

        // 质检:2 PASS + 1 REJECTED + 1 契约无效;通过率 = 2/(2+1)
        assertThat(s.quality().reviewed()).isEqualTo(4);
        assertThat(s.quality().passed()).isEqualTo(2);
        assertThat(s.quality().rejected()).isEqualTo(1);
        assertThat(s.quality().contractInvalid()).isEqualTo(1);
        assertThat(s.quality().passRatePct()).isEqualTo(66.7);

        // MTTR:60/120/300/600 → 均值 270,中位数 (120+300)/2 = 210
        assertThat(s.mttr().sampleSize()).isEqualTo(4);
        assertThat(s.mttr().meanSeconds()).isEqualTo(270.0);
        assertThat(s.mttr().medianSeconds()).isEqualTo(210.0);
    }

    @Test
    void emptyDatabaseYieldsZeroRatesWithoutDivisionError() {
        MetricsService.Summary s = service().summary();
        assertThat(s.totalTickets()).isZero();
        assertThat(s.automationRatePct()).isZero();
        assertThat(s.escalationRatePct()).isZero();
        assertThat(s.approvalRatePct()).isZero();
        assertThat(s.quality().passRatePct()).isZero();
        assertThat(s.mttr().sampleSize()).isZero();
        assertThat(s.mttr().meanSeconds()).isZero();
    }

    @Test
    void escalatedByQualityRejectCountsAsHumanEscalation() {
        // 质检驳回重开 ESCALATED(不在人工池):也应计入人工升级率
        ticket("t1", TicketService.STATUS_ESCALATED, T0, null);
        ticket("t2", TicketService.STATUS_CLOSED, T0, T0.plusSeconds(60));

        MetricsService.Summary s = service().summary();
        assertThat(s.humanEscalatedTickets()).isEqualTo(1);
        assertThat(s.escalationRatePct()).isEqualTo(50.0);
    }

    // ---- 时间窗口 ----

    @Test
    void ticketsWindowFiltersByCreationTimeWithDistributionAndMttr() {
        // w1:窗口内创建并结单(P1,600s);w2:窗口内创建未结单(老数据 null 优先级 → 按 P3 兜底);
        // w3:窗口外创建(ESCALATED),不应出现在窗口统计
        // (时刻取 12:00Z:任意系统时区偏移 [-12,+14] 内按日分组都不跨日)
        ticket("w1", TicketService.STATUS_CLOSED, "P1",
                Instant.parse("2026-11-01T12:00:00Z"), Instant.parse("2026-11-01T12:10:00Z"));
        ticket("w2", TicketService.STATUS_NEW, null,
                Instant.parse("2026-11-02T12:00:00Z"), null);
        ticket("w3", TicketService.STATUS_ESCALATED, "P2",
                Instant.parse("2026-11-03T12:00:00Z"), null);

        MetricsService.WindowStats w = service().tickets(
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"));

        assertThat(w.createdTickets()).isEqualTo(2);
        assertThat(w.byStatus()).containsEntry("CLOSED", 1L).containsEntry("NEW", 1L);
        assertThat(w.byPriority()).containsEntry("P1", 1L).containsEntry("P3", 1L);
        assertThat(w.byPriority()).doesNotContainKey("P2");

        // 按日序列:11-01 创建 1 结单 1;11-02 创建 1 结单 0
        assertThat(w.daily()).hasSize(2);
        assertThat(w.daily().get(0).date()).isEqualTo("2026-11-01");
        assertThat(w.daily().get(0).created()).isEqualTo(1);
        assertThat(w.daily().get(0).closed()).isEqualTo(1);
        assertThat(w.daily().get(1).date()).isEqualTo("2026-11-02");
        assertThat(w.daily().get(1).created()).isEqualTo(1);
        assertThat(w.daily().get(1).closed()).isZero();

        // 窗口内结单:仅 w1 → MTTR 600s
        assertThat(w.closedTickets()).isEqualTo(1);
        assertThat(w.mttr().sampleSize()).isEqualTo(1);
        assertThat(w.mttr().meanSeconds()).isEqualTo(600.0);
        assertThat(w.mttr().medianSeconds()).isEqualTo(600.0);
    }

    @Test
    void ticketsWindowDefaultsToAllTimeWhenParamsMissing() {
        ticket("w1", TicketService.STATUS_NEW, "P3", T0, null);
        MetricsService.WindowStats w = service().tickets(null, null);
        assertThat(w.createdTickets()).isEqualTo(1);
        assertThat(w.from()).isEqualTo(Instant.EPOCH.toString());
    }

    // ---- 结单时间戳落戳/清空(台账服务行为,MTTR 的数据前提) ----

    @Test
    void updateStatusStampsClosedAtAndClearsOnReopen() throws InterruptedException {
        TicketService ticketService = new TicketService(ticketRepository);
        String id = ticketService.create("指标时间戳验证工单", "cust-001");

        // 结单 → 落 closed_at 戳;重复置 CLOSED 保持首戳
        ticketService.updateStatus(id, TicketService.STATUS_CLOSED);
        Instant firstClose = ticketRepository.findById(id).orElseThrow().getClosedAt();
        assertThat(firstClose).isNotNull();
        ticketService.updateStatus(id, TicketService.STATUS_CLOSED);
        assertThat(ticketRepository.findById(id).orElseThrow().getClosedAt()).isEqualTo(firstClose);

        // 重开(质检驳回场景) → 清空;再次结单 → 重新落戳
        ticketService.updateStatus(id, TicketService.STATUS_ESCALATED);
        assertThat(ticketRepository.findById(id).orElseThrow().getClosedAt()).isNull();
        Thread.sleep(20); // Windows 时钟粒度约 15ms,保证重新落戳时间与首戳可区分
        ticketService.updateStatus(id, TicketService.STATUS_CLOSED);
        Instant secondClose = ticketRepository.findById(id).orElseThrow().getClosedAt();
        assertThat(secondClose).isNotNull();
        assertThat(secondClose.isAfter(firstClose)).isTrue();
    }

    @Test
    void closedWithoutTimestampExcludedFromMttr() {
        // 老数据:CLOSED 但无结单时间戳 → 不参与 MTTR,也不算异常
        ticketRepository.save(new TicketEntity("legacy", "老数据", TicketService.STATUS_CLOSED,
                "cust-001", null, T0, T0, null));
        ticket("fresh", TicketService.STATUS_CLOSED, T0, T0.plusSeconds(90));

        MetricsService.Summary s = service().summary();
        assertThat(s.closedTickets()).isEqualTo(2);
        assertThat(s.mttr().sampleSize()).isEqualTo(1);
        assertThat(s.mttr().meanSeconds()).isEqualTo(90.0);
    }

    // ---- 造数辅助 ----

    private void ticket(String id, String status, Instant createdAt, Instant closedAt) {
        ticket(id, status, "P3", createdAt, closedAt);
    }

    private void ticket(String id, String status, String priority, Instant createdAt, Instant closedAt) {
        ticketRepository.save(new TicketEntity(id, "m-" + id, status, "cust-001",
                priority, createdAt, createdAt, closedAt));
    }

    private void approvalDecision(String ticketId) {
        approvalDecisionRepository.save(new ApprovalDecisionEntity(
                ticketId, true, "ops", T0, "指标测试审批", null));
    }

    private void humanTask(String ticketId) {
        humanTaskRepository.save(new HumanTaskEntity(
                ticketId, "指标测试转人工", "详情", HumanTicketPool.STATUS_WAITING, "", T0, T0));
    }

    private void qualityAudit(String detail) {
        auditLogRepository.save(new AuditLogEntity("metrics-test", "quality-review", detail, T0));
    }
}
