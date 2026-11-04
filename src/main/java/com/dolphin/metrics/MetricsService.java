package com.dolphin.metrics;

import com.dolphin.ticket.infra.audit.AuditLogEntity;
import com.dolphin.ticket.infra.audit.AuditLogRepository;
import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import com.dolphin.ticket.service.TicketService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 运营指标服务(阶段2):从工单台账/审批决策/人工池/审计日志聚合运营数字,
 * 让"自动化解决率 X%、MTTR Y 分钟"这类上线效果话术来自系统本身,可复核。
 *
 * <p>口径定义(README 与面试话术与此保持一致):
 * <ul>
 *   <li><b>自动化结单率</b> = 全程无人工介入(未进人工池、无审批决策)的结单工单 ÷ 工单总量;</li>
 *   <li><b>人工升级率</b> = 进过人工环节(当前 ESCALATED 或进过人工池)的工单 ÷ 工单总量;</li>
 *   <li><b>审批触发率</b> = 走过审批流(待审批或已有决策)的工单 ÷ 工单总量;</li>
 *   <li><b>质检通过率</b> = audit_log 中 quality-review PASS ÷ (PASS + REJECTED),
 *       契约无效(模型未按约定输出)不计入分母——属质检管线故障,不是质检结论;</li>
 *   <li><b>MTTR</b> = 结单工单 closed_at - created_at 的均值/中位数;
 *       重开(质检驳回)会清空结单时间,再次结单重新计时;</li>
 *   <li><b>SLA 达成率</b> = (工单总量 - 超时留痕工单) ÷ 工单总量;
 *       超时判定与留痕见 {@code SlaMonitor}(阶段3)。</li>
 * </ul>
 */
@Service
public class MetricsService {

    /** 质检审计动作名(与 QualityReviewService 落痕约定一致) */
    static final String ACTION_QUALITY_REVIEW = "quality-review";

    private final TicketRepository ticketRepository;
    private final AuditLogRepository auditLogRepository;

    public MetricsService(TicketRepository ticketRepository, AuditLogRepository auditLogRepository) {
        this.ticketRepository = ticketRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /** 全量汇总指标 */
    public record Summary(String generatedAt, long totalTickets, Map<String, Long> byStatus,
                          Map<String, Long> byPriority,
                          long closedTickets, long automatedClosedTickets, double automationRatePct,
                          long humanEscalatedTickets, double escalationRatePct,
                          long approvalTickets, double approvalRatePct,
                          Quality quality, Mttr mttr, Sla sla) {
    }

    /** SLA 达成情况:达成率 = (总量 - 超时留痕工单) ÷ 总量 */
    public record Sla(long breachedTickets, double complianceRatePct) {
    }

    /** 质检指标:passRatePct 分母为 PASS + REJECTED(契约无效不计入) */
    public record Quality(long reviewed, long passed, long rejected, long contractInvalid,
                          double passRatePct) {
    }

    /** MTTR(秒):sampleSize 为参与统计的结单工单数(缺结单时间戳的老数据不参与) */
    public record Mttr(long sampleSize, double meanSeconds, double medianSeconds) {
    }

    /** 时间窗口报表:创建于 [from, to] 的工单分布 + 窗口内结单的 MTTR */
    public record WindowStats(String from, String to, long createdTickets,
                              Map<String, Long> byStatus, Map<String, Long> byPriority,
                              List<DailyCount> daily, long closedTickets, Mttr mttr) {
    }

    /** 按日汇总(系统时区) */
    public record DailyCount(String date, long created, long closed) {
    }

    /** 全量汇总:自动化结单率/人工升级率/审批触发率/质检通过率/MTTR/SLA 达成率/优先级分布 */
    public Summary summary() {
        long total = ticketRepository.count();
        Map<String, Long> byStatus = new TreeMap<>(ticketRepository.countGroupByStatus().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1])));
        Map<String, Long> byPriority = new TreeMap<>(ticketRepository.countGroupByPriority().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1])));
        long closed = byStatus.getOrDefault(TicketService.STATUS_CLOSED, 0L);
        long automatedClosed = ticketRepository.countAutomatedClosed(TicketService.STATUS_CLOSED);
        long humanEscalated = ticketRepository.countHumanEscalated(TicketService.STATUS_ESCALATED);
        long approvalTouched = ticketRepository.countApprovalTouched(TicketService.STATUS_PENDING_APPROVAL);
        long breached = ticketRepository.countBySlaBreachAtNotNull();
        return new Summary(Instant.now().toString(), total, byStatus, byPriority,
                closed, automatedClosed, pct(automatedClosed, total),
                humanEscalated, pct(humanEscalated, total),
                approvalTouched, pct(approvalTouched, total),
                quality(), mttr(ticketRepository.findClosedWithTimestamp(TicketService.STATUS_CLOSED)),
                new Sla(breached, pct(total - breached, total)));
    }

    /** 时间窗口报表:from/to 为 ISO-8601;null 时 from 取 epoch、to 取当前时刻 */
    public WindowStats tickets(Instant from, Instant to) {
        Instant f = from == null ? Instant.EPOCH : from;
        Instant t = to == null ? Instant.now() : to;
        List<TicketEntity> created = ticketRepository.findByCreatedAtBetween(f, t);
        List<TicketEntity> closed = ticketRepository.findByClosedAtBetween(f, t);
        return new WindowStats(f.toString(), t.toString(), created.size(),
                created.stream().collect(Collectors.groupingBy(
                        TicketEntity::getStatus, TreeMap::new, Collectors.counting())),
                created.stream().collect(Collectors.groupingBy(
                        MetricsService::priorityOrDefault, TreeMap::new, Collectors.counting())),
                dailySeries(created, closed),
                closed.size(), mttr(closed));
    }

    // ---- 内部计算 ----

    /** 质检结论分布:按审计行 detail 前缀分类(与 QualityReviewService 落痕格式约定一致) */
    private Quality quality() {
        List<String> details = auditLogRepository.findByAction(ACTION_QUALITY_REVIEW).stream()
                .map(AuditLogEntity::getDetail)
                .toList();
        long passed = countPrefix(details, "PASS");
        long rejected = countPrefix(details, "REJECTED");
        long invalid = countPrefix(details, "CONTRACT_INVALID");
        return new Quality(passed + rejected + invalid, passed, rejected, invalid,
                pct(passed, passed + rejected));
    }

    private static long countPrefix(List<String> details, String prefix) {
        return details.stream().filter(d -> d != null && d.startsWith(prefix)).count();
    }

    /** 均值 + 中位数(秒,一位小数);样本为空时全 0,不给假数字 */
    private static Mttr mttr(List<TicketEntity> closedTickets) {
        List<Double> seconds = closedTickets.stream()
                .filter(e -> e.getClosedAt() != null && !e.getClosedAt().isBefore(e.getCreatedAt()))
                .map(e -> Duration.between(e.getCreatedAt(), e.getClosedAt()).toMillis() / 1000.0)
                .sorted()
                .toList();
        if (seconds.isEmpty()) {
            return new Mttr(0, 0.0, 0.0);
        }
        double mean = round1(seconds.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        double median = round1(seconds.size() % 2 == 1
                ? seconds.get(seconds.size() / 2)
                : (seconds.get(seconds.size() / 2 - 1) + seconds.get(seconds.size() / 2)) / 2);
        return new Mttr(seconds.size(), mean, median);
    }

    /** 创建/结单按日汇总(系统时区),日期升序 */
    private static List<DailyCount> dailySeries(List<TicketEntity> created, List<TicketEntity> closed) {
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        created.forEach(e -> byDay.computeIfAbsent(
                LocalDate.ofInstant(e.getCreatedAt(), zone), d -> new long[2])[0]++);
        closed.stream()
                .filter(e -> e.getClosedAt() != null)
                .forEach(e -> byDay.computeIfAbsent(
                        LocalDate.ofInstant(e.getClosedAt(), zone), d -> new long[2])[1]++);
        return byDay.entrySet().stream()
                .map(en -> new DailyCount(en.getKey().toString(), en.getValue()[0], en.getValue()[1]))
                .toList();
    }

    /** 未分级(老数据 null)按业务规则兜底 P3 */
    private static String priorityOrDefault(TicketEntity e) {
        return e.getPriority() == null || e.getPriority().isBlank() ? "P3" : e.getPriority();
    }

    /** 百分比(一位小数);分母为 0 时返回 0.0,真实样本数由相邻计数字段给出 */
    private static double pct(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
