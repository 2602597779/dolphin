package com.dolphin.ticket.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 工单台账仓库(表 ticket) */
public interface TicketRepository extends JpaRepository<TicketEntity, String> {

    /** 按工单号 + 归属客户查询(租户隔离:非本客户的单查不到) */
    Optional<TicketEntity> findByIdAndCustomerId(String id, String customerId);

    /** 本客户的工单列表(按创建时间新→旧),status 可为 null 表示不过滤 */
    default List<TicketEntity> findByCustomer(String customerId, String status, Pageable pageable) {
        if (status == null || status.isBlank()) {
            return findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        }
        return findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, status, pageable);
    }

    List<TicketEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    List<TicketEntity> findByCustomerIdAndStatusOrderByCreatedAtDesc(String customerId, String status,
                                                                     Pageable pageable);

    /** 全量工单列表(运营视角),按创建时间新→旧 */
    List<TicketEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按状态的全量工单列表(运营视角),按创建时间新→旧 */
    List<TicketEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    // ---- 阶段2:运营指标聚合(跨表 JPQL 在同一持久化单元内下推到 SQL 执行) ----

    /** 状态分布:status → 数量 */
    @Query("select t.status, count(t) from TicketEntity t group by t.status")
    List<Object[]> countGroupByStatus();

    /**
     * 自动化闭环工单数:已结单,且全程无人工介入——未进过人工池(human_task)、
     * 无审批决策(approval_decision)。即"AI 受理并结单,人未碰过"。
     */
    @Query("select count(t) from TicketEntity t where t.status = :closed"
            + " and t.id not in (select h.ticketId from HumanTaskEntity h)"
            + " and t.id not in (select a.ticketId from ApprovalDecisionEntity a)")
    long countAutomatedClosed(@Param("closed") String closed);

    /**
     * 进过人工环节的工单数:当前处于 ESCALATED(含质检驳回重开、待人工处理),
     * 或进过人工池。两者取并集,统计"人工升级"触达面。
     */
    @Query("select count(t) from TicketEntity t where t.status = :escalated"
            + " or t.id in (select h.ticketId from HumanTaskEntity h)")
    long countHumanEscalated(@Param("escalated") String escalated);

    /** 走过审批流的工单数:当前待审批(已中断在 approval 节点),或已有审批决策留痕 */
    @Query("select count(t) from TicketEntity t where t.status = :pendingApproval"
            + " or t.id in (select a.ticketId from ApprovalDecisionEntity a)")
    long countApprovalTouched(@Param("pendingApproval") String pendingApproval);

    /** 已结单且落有结单时间戳的工单(MTTR 统计口径;结单时间戳缺失的老数据不参与) */
    @Query("select t from TicketEntity t where t.status = :closed and t.closedAt is not null")
    List<TicketEntity> findClosedWithTimestamp(@Param("closed") String closed);

    /** 创建时间落在 [from, to] 的工单(时间窗口报表) */
    List<TicketEntity> findByCreatedAtBetween(Instant from, Instant to);

    /** 结单时间落在 [from, to] 的工单(时间窗口内的 MTTR 口径) */
    List<TicketEntity> findByClosedAtBetween(Instant from, Instant to);

    // ---- 阶段3:SLA 巡检与指标 ----

    /** 未闭环且从未 SLA 超时留痕的工单(SlaMonitor 周期扫描的输入,幂等保证) */
    List<TicketEntity> findByStatusNotAndSlaBreachAtIsNull(String status);

    /**
     * SLA 超时处置权抢占 + 留痕(原子条件更新,P0 多实例防重)。
     *
     * <p>把原来"SlaMonitor 读-改-写(setSlaBreachAt + save)"换成单条 UPDATE:
     * 只有把 {@code sla_breach_at} 从 NULL 改成非 NULL 的事务能影响 1 行,并发节点其余全部 0 行。
     * 数据库行级原子性天然跨实例成立,因此多实例巡检同一张超时单时只有一个赢家,
     * 重复升级/重复加急在 SQL 层被挡住(无需分布式锁)。
     *
     * @return 受影响行数:1=抢占成功(本次由我处置),0=已被他人抢占/工单不存在
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketEntity t set t.slaBreachAt = :at, t.updatedAt = :at"
            + " where t.id = :id and t.slaBreachAt is null")
    int claimSlaBreach(@Param("id") String id, @Param("at") Instant at);

    // ---- 阶段 O8:异步受理闸门 ----

    /**
     * 受理闸门:仅当工单当前状态等于 {@code from} 时才流转到 {@code to}(原子条件更新)。
     * 返回受影响行数(0=状态已被并发请求或流程改变,受理失败)。
     * 单条 UPDATE 的原子性天然跨实例成立:多节点部署时同一工单的并发受理也只有一个赢家,
     * 取代进程内锁(O1 的 TicketLockRegistry)实现"同单串行、消息不穿插"。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketEntity t set t.status = :to, t.updatedAt = :now"
            + " where t.id = :id and t.status = :from")
    int transitionStatusIf(@Param("id") String id,
                           @Param("from") String from,
                           @Param("to") String to,
                           @Param("now") Instant now);

    /** SLA 超时工单数(达成率分母 = 工单总量) */
    long countBySlaBreachAtNotNull();

    /** 优先级分布(未分级老数据按 P3 兜底):priority → 数量 */
    @Query("select coalesce(t.priority, 'P3'), count(t) from TicketEntity t group by coalesce(t.priority, 'P3')")
    List<Object[]> countGroupByPriority();
}
