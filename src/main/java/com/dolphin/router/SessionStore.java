package com.dolphin.router;

/**
 * 会话存储(P0 多实例就绪:从"进程内 Map"抽成接口)。
 *
 * <p>会话承载<b>路由粘性</b>(activeCapability)与<b>待审批工单号</b>(pendingTicketId),
 * 这两者是"同一轮对话必须落到同一进程"的粘性来源。单实例下用进程内 Map 即可;
 * 多实例(负载均衡不保证会话粘性)下必须换成共享存储(Redis / DB 表),
 * 否则用户下一条消息可能落到另一节点、粘性状态丢失。
 *
 * <p>因此契约里显式区分<b>读</b>与<b>写</b>:
 * <ul>
 *   <li>{@link #getOrCreate(String)}:取会话(不存在则新建),调用方在返回对象上就地改状态;</li>
 *   <li>{@link #save(DialogContext)}:把修改后的会话写回存储。进程内实现是<b>空实现</b>
 *       (同一对象引用,本来就地生效);外部化实现必须在这里做持久化,
 *       否则跨请求/跨节点的修改会丢——入口 {@code ChatController} 每次处理完都调用它,
 *       保证"换了实现也不用再改调用方"。</li>
 * </ul>
 *
 * <p>与 {@link com.dolphin.notify.NotifySender} 同款范式:接口 + 默认实现
 * ({@link LocalSessionStore}) + 未来实现标 {@code @Primary} 覆盖,调用方零改动。
 */
public interface SessionStore {

    /** 取会话(不存在则按 sessionId 新建)。 */
    DialogContext getOrCreate(String sessionId);

    /**
     * 写回会话状态。
     *
     * <p>进程内实现为空实现(对象就地修改即生效);外部化实现需持久化。
     * 调用方保证:一轮请求处理完成后调用一次。
     */
    void save(DialogContext ctx);

    /** 清理空闲超时的会话(进程内实现由定时任务调用;外部化实现可用存储 TTL 替代)。 */
    void evictExpired();

    /** 当前会话数(测试与观测用)。 */
    int size();
}
