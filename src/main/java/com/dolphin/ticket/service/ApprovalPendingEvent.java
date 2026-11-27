package com.dolphin.ticket.service;

/**
 * 工单已挂起待人工审批(阶段5 IM 卡片审批的触发点)。
 *
 * <p>为什么用事件而不是在 {@link TicketRoundService} 里直接调通知:审批挂起是图状态机的
 * <b>中断点</b>,而"通知人来看一眼"是旁路关注点——通知失败不该让一轮对话落库失败,
 * 通知方式(钉钉/企微/飞书)也不该让事务边界的写死在上游。发布者只声明"工单待审批了"这一事实,
 * 谁关心谁自己订阅(见 {@code com.dolphin.im.ApprovalCardNotifier})。
 *
 * <p>事件只带工单号:{@code pendingAction} 等展示细节在发布点并不权威(由图状态/检查点在
 * 查询时给出),订阅方按号现取,避免把可能已过期的快照塞进事件。
 *
 * <p>消费时机由订阅方声明为 {@code @TransactionalEventListener(AFTER_COMMIT)}:
 * 状态没真落库就不该把卡片发出去,发送动作也不该占着事务。
 *
 * @param ticketId 工单号
 */
public record ApprovalPendingEvent(String ticketId) {
}
