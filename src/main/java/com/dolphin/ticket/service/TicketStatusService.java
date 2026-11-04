package com.dolphin.ticket.service;

import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.state.TicketState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工单状态视图(阶段 O8):轮询接口、SSE 订阅快照、完成事件共用的统一口径。
 *
 * <p>受理即 202 后,客户端通过本视图感知进度:
 * <ul>
 *   <li>{@code processing}:图在后台执行中(受理闸门已翻转 PROCESSING);</li>
 *   <li>{@code done}:本轮是否已有结论——NEW/RUNNING/PROCESSING 之外的任意状态。
 *       PENDING_APPROVAL 也是"本轮已答复"(等待人工审批),CLOSED/ESCALATED 为终态;</li>
 *   <li>{@code finalReply}:线程里最新一条客服侧消息(结单终稿/待审批草稿/人工完结答复);</li>
 *   <li>{@code pendingAction}:仅 PENDING_APPROVAL 时给出,取自 checkpoint 的权威待执行动作
 *       (其他状态下 checkpoint 可能残留旧值,不展示以免误导);</li>
 *   <li>{@code rounds}:线程消息数。</li>
 * </ul>
 *
 * <p>调用方需已完成存在性/租户校验;工单不存在时按 NEW 兜底(视图仅作展示,不承担鉴权)。
 */
@Component
public class TicketStatusService {

    /** 尚无本轮结论的状态(未受理完/图在跑/旧链路中间态) */
    private static final Set<String> IN_FLIGHT_STATUSES =
            Set.of(TicketService.STATUS_NEW, TicketService.STATUS_RUNNING, TicketService.STATUS_PROCESSING);

    private final TicketService ticketService;
    private final ConversationService conversationService;
    private final TicketCheckpointStore checkpointStore;

    public TicketStatusService(TicketService ticketService,
                               ConversationService conversationService,
                               TicketCheckpointStore checkpointStore) {
        this.ticketService = ticketService;
        this.conversationService = conversationService;
        this.checkpointStore = checkpointStore;
    }

    /** 组装状态视图(轮询/SSE 快照/完成事件的三方统一口径) */
    public Map<String, Object> view(String ticketId) {
        String status = ticketService.get(ticketId)
                .map(TicketService.Ticket::status)
                .orElse(TicketService.STATUS_NEW);
        boolean processing = TicketService.STATUS_PROCESSING.equals(status);
        boolean done = !IN_FLIGHT_STATUSES.contains(status);

        Map<String, Object> snapshot = checkpointStore.snapshotState(ticketId).orElse(Map.of());
        String replyDraft = strOrNull(snapshot.get(TicketState.REPLY_DRAFT));
        String pendingAction = TicketService.STATUS_PENDING_APPROVAL.equals(status)
                ? strOrNull(snapshot.get(TicketState.PENDING_ACTION))
                : null;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticketId", ticketId);
        view.put("status", status);
        view.put("processing", processing);
        view.put("done", done);
        view.put("replyDraft", replyDraft);
        view.put("finalReply", conversationService.latestAgentReply(ticketId));
        view.put("pendingAction", pendingAction);
        view.put("rounds", conversationService.count(ticketId));
        return view;
    }

    private static String strOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }
}
