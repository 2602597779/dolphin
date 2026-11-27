package com.dolphin.ticket.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一轮对话的短事务边界(阶段4优化 O4)。
 *
 * <p>reply 原先"取历史 → 落客户消息 → 改状态 → 跑图 → 落系统回复"各自独立提交:
 * 图执行异常时会出现"客户消息已落库、线程没有答复、状态仍停在 RUNNING"的脏中间态。
 * 这里把每轮的首尾两次多表写入收敛为短事务:
 * <ul>
 *   <li>{@link #beginRound}:取历史 + 落客户消息,同一事务;</li>
 *   <li>{@link #completeRound}:落系统回复 + 按需写状态,同一事务。</li>
 * </ul>
 *
 * <p><b>关键边界</b>:图执行(含云 API 调用与指数退避轮询,耗时可达秒级)绝不包进事务。
 * 阶段 O8 起图执行移入独立线程池({@link TicketGraphRunner}),本服务只保留事务边界。
 *
 * <p><b>状态翻转归属</b>(阶段 O8):beginRound 不再写状态——受理闸门
 * ({@link TicketService#tryTransition} 条件更新)由受理链路在调用本方法前完成,
 * 保证"读状态 → 校验 → 翻转 → 落消息"的互斥语义集中在闸门一处。
 */
@Component
public class TicketRoundService {

    private final ConversationService conversationService;
    private final TicketService ticketService;
    private final ApplicationEventPublisher events;

    public TicketRoundService(ConversationService conversationService, TicketService ticketService,
                             ApplicationEventPublisher events) {
        this.conversationService = conversationService;
        this.ticketService = ticketService;
        this.events = events;
    }

    /**
     * 开启一轮追问:先取历史(不含本轮),再落客户消息,同一事务提交。
     * (状态已由受理链路先行翻转为 PROCESSING,此处不重复写。)
     *
     * @return 本轮之前的对话历史转录,供注入图状态 CONVERSATION_HISTORY
     */
    @Transactional
    public String beginRound(String ticketId, String message, String requestId) {
        String history = conversationService.transcript(ticketId);
        conversationService.appendUser(ticketId, message, requestId);
        return history;
    }

    /**
     * 收尾一轮追问:系统回复 + 状态写在同一事务。
     *
     * @param status 需要落库的状态;传 {@code null} 表示图已落终态(CLOSED/ESCALATED),
     *               不重复覆盖——避免异步质检刚判定的"驳回重开"被结单写回盖掉
     */
    @Transactional
    public void completeRound(String ticketId, String status, String agentReply) {
        conversationService.appendAgent(ticketId, agentReply);
        if (status != null && !status.isBlank()) {
            ticketService.updateStatus(ticketId, status);
            if (TicketService.STATUS_PENDING_APPROVAL.equals(status)) {
                // 挂起待审批 = 通知卡片的触发点。事件在此发布,但消费方声明为 AFTER_COMMIT:
                // 状态没真提交就不该发卡片,发送动作也不该占着这个事务(见 ApprovalCardNotifier)。
                events.publishEvent(new ApprovalPendingEvent(ticketId));
            }
        }
    }
}
