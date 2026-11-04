package com.dolphin.ticket.service;

import com.dolphin.ticket.repo.TicketMessageEntity;
import com.dolphin.ticket.repo.TicketMessageRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 工单对话线程服务(阶段4 多轮对话)。
 *
 * <p>职责:追加客户/系统消息、按序取线程、把历史渲染为喂给 Agent 的转录文本。
 * 线程是"PENDING 单基于上下文续跑"的上下文来源:每轮 reply 把历史注入图状态,
 * 分诊/诊断/问答/回复各 Agent 均能看到此前沟通,客户无需重复背景。
 */
@Component
public class ConversationService {

    /** 对话消息(对外只读) */
    public record Message(String role, String content, Instant createdAt) {
    }

    private final TicketMessageRepository repository;

    public ConversationService(TicketMessageRepository repository) {
        this.repository = repository;
    }

    /** 追加一条客户消息(提交首条诉求/每轮 reply) */
    public void appendUser(String ticketId, String content) {
        append(ticketId, TicketMessageEntity.ROLE_USER, content);
    }

    /** 追加一条系统回复(首轮答复/审批结单终稿) */
    public void appendAgent(String ticketId, String content) {
        if (content == null || content.isBlank()) {
            return; // 图异常中断无终稿时不落空消息,线程只保留客户侧发言
        }
        append(ticketId, TicketMessageEntity.ROLE_AGENT, content);
    }

    /** 本工单完整线程(旧→新);工单无消息返回空列表 */
    public List<Message> messages(String ticketId) {
        return repository.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).stream()
                .map(e -> new Message(e.getRole(), e.getContent(), e.getCreatedAt()))
                .toList();
    }

    /**
     * 历史转录文本(喂给 Agent 的上下文):"客户: …" / "客服: …" 逐行拼接。
     * 无历史返回空串(首轮);长度截断到 6000 字符,防超长线程撑爆提示词。
     */
    public String transcript(String ticketId) {
        String text = messages(ticketId).stream()
                .map(m -> (TicketMessageEntity.ROLE_USER.equals(m.role()) ? "客户: " : "客服: ")
                        + m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return text.length() <= 6000 ? text : text.substring(text.length() - 6000);
    }

    private void append(String ticketId, String role, String content) {
        repository.save(new TicketMessageEntity(ticketId, role, content, Instant.now()));
    }
}
