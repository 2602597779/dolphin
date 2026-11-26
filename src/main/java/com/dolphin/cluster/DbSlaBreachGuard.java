package com.dolphin.cluster;

import com.dolphin.ticket.repo.TicketRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 默认 SLA 防重守卫:把"抢占 + 留痕"压成一条条件 UPDATE,靠数据库行级原子性跨实例防重。
 *
 * <p>与 {@code TicketRepository.transitionStatusIf} 同款范式:@Modifying 条件更新
 * ({@code where id=:id and sla_breach_at is null})。并发两个节点同时执行时,
 * 数据库保证只有一个事务能把 {@code sla_breach_at} 从 NULL 改成非 NULL,另一个更新到 0 行,
 * 于是重复升级在 SQL 层就被挡住——不需要分布式锁、不需要 Redis、不需要把状态挪出数据库。
 *
 * <p>为什么复用 {@code sla_breach_at} 而不是另设一张防重表:这个字段本来就是
 * "已升级留痕"(升级一次就不再升级),它天然就是去重键;新增表反而多一处可能与
 * 工单状态漂移的副本数据。
 */
public class DbSlaBreachGuard implements SlaBreachGuard {

    private final TicketRepository repository;

    public DbSlaBreachGuard(TicketRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean claim(String ticketId, Instant at) {
        // 条件更新的影响行数就是抢占结果:1=我抢到了,0=已被别人抢过(或工单已不存在)
        return repository.claimSlaBreach(ticketId, at) > 0;
    }
}
