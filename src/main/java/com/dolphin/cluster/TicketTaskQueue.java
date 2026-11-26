package com.dolphin.cluster;

/**
 * 任务投递队列(P0 多实例就绪:第 3 处接口留口)。
 *
 * <p>现状问题:{@code TicketGraphRunner} 直接往进程内线程池({@code ticketGraphExecutor})
 * 丢任务。单实例下这是对的:有界队列 + AbortPolicy + 投递失败就地补偿转人工,
 * "拒绝比静默积压诚实"。但多实例部署后,任务只在<b>收下 HTTP 请求的那个进程</b>里排队执行,
 * 队列深浅不共享,一个实例排满(节点过载)而其他实例空闲,也没法在实例重启/发布时
 * 不丢在途任务。
 *
 * <p>留口方式:把"投递"这一个动作抽成接口,调用方只关心结果(yes=已接收 / no=背压拒绝),
 * 不关心任务最终在哪执行:
 * <ul>
 *   <li>{@link LocalTicketTaskQueue}(默认):包装现有进程内线程池,语义与改造前完全一致
 *       (拒绝即返回 false,runner 照旧补偿转人工);</li>
 *   <li>未来的外置实现(数据库任务表 + 抢占式消费 / 真实 MQ)同样只实现本接口:
 *       投递变成写任务行或发消息,"执行"由任一实例的消费者领取,天然支持跨实例分发与重启不丢任务。
 *       在装配处标 {@code @Primary} 覆盖默认实现,{@code TicketGraphRunner} 一行不改。</li>
 * </ul>
 *
 * <p>为什么现在不做真实现:引入任务表/中间件会给受理链路加一次外部 IO 与一套运维对象,
 * 收益只在"多实例 + 有在途任务"时才成立;当前单实例演示/测试用不上,属于诚实的延后项。
 */
public interface TicketTaskQueue {

    /**
     * 投递一个图执行任务。
     *
     * @return true=已接收(将被执行);false=被拒绝(队列已满 / 执行器已关闭),
     *         调用方必须走失败补偿路径,不得假定任务会执行
     */
    boolean submit(Runnable task);
}
