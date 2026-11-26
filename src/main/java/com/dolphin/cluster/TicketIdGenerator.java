package com.dolphin.cluster;

import com.dolphin.ticket.service.TicketService;

/**
 * 工单号发号器(P0 多实例就绪:第 1 处接口留口)。
 *
 * <p>现状问题:发号逻辑原来内联在 {@link TicketService} 里,号段 = "毫秒(base36) + 进程内序号"。
 * 单实例绝对够用,但多实例部署时两个进程在同一毫秒各自从 1 开始递增,序号位会撞号
 * (主键冲突)。号段的"唯一性来源"必须从进程内状态换成跨实例可区分的东西。
 *
 * <p>留口方式:抽出发号接口,挪走进程内状态。
 * <ul>
 *   <li>{@link LocalTicketIdGenerator}(默认):毫秒 base36 + 进程内序号,单实例语义与改造前一致;</li>
 *   <li>{@link WorkerTicketIdGenerator}(可切):时间戳 + 实例号 + 序列,号段按实例号分段,
 *       多实例互不重叠,零新增依赖(纯 Java 计数,不引 Redis/ZooKeeper)。</li>
 * </ul>
 *
 * <p>切换:{@code dolphin.cluster.ticket-id.strategy=local|worker}(默认 local)。
 * 换实现只换 Bean,{@link TicketService} 与所有调用方零改动——与
 * {@code com.dolphin.notify.NotifySender} 同款范式;接生产发号服务
 * (号段服务 / Snowflake 服务)时同样只替换这一个实现。
 */
public interface TicketIdGenerator {

    /** 生成下一个工单号(实现须保证同实例内不重复;多实例安全性由实现自行界定) */
    String next();
}
