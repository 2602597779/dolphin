package com.dolphin.cluster;

import java.time.Instant;

/**
 * SLA 超时处置权守卫(P0 多实例就绪:第 2 处接口留口)。
 *
 * <p>现状问题:{@code SlaMonitor} 判定超时后是"读-改-写"(先查出工单,
 * 再 {@code setSlaBreachAt(now)} 后 save)。单实例靠"巡检前先筛
 * {@code sla_breach_at is null}"保证幂等;多实例下两个节点的巡检线程会同时筛到同一张单,
 * 双双认定"我是第一个发现的",于是同一个超时单被<b>重复升级 + 重复加急</b>
 * (人工池重复行、钉钉重复通知)。判重状态在各自进程内存里,天然不可靠。
 *
 * <p>留口方式:把"本次超时由我处置"这件事变成一个<b>原子的跨实例抢占动作</b>,
 * 调用方只问 yes/no,不关心抢占是怎么实现的:
 * <ul>
 *   <li>{@link DbSlaBreachGuard}(默认):条件 UPDATE 抢占
 *       ({@code set sla_breach_at=:at where id=:id and sla_breach_at is null}),
 *       单条 SQL 的原子性天然跨实例成立——行数为 1 才算抢占成功;</li>
 *   <li>未来的集中式实现(Redis {@code SETNX} / 分布式锁 / 号段协调)同样只实现本接口,
 *       在装配处标 {@code @Primary} 覆盖默认实现,{@code SlaMonitor} 一行不改。</li>
 * </ul>
 *
 * <p>与工单状态迁移的关系:占位字段复用 {@code sla_breach_at} 本身(既有的"已升级留痕"),
 * 不新增状态位;抢占成功 = 这次升级动作的所有权归我。
 */
public interface SlaBreachGuard {

    /**
     * 抢占工单的超时处置权,并同时落"已升级"留痕(两者是同一个原子动作)。
     *
     * @param ticketId 工单号
     * @param at       本次判定时刻(写入 {@code sla_breach_at})
     * @return true=本次抢占成功(首次升级,调用方继续入人工池/加急通知);
     *         false=已被他人(其他实例或上一轮巡检)抢占,调用方必须<b>跳过</b>全部副作用
     */
    boolean claim(String ticketId, Instant at);
}
