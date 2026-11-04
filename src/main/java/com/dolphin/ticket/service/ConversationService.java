package com.dolphin.ticket.service;

import com.dolphin.ticket.repo.TicketMessageEntity;
import com.dolphin.ticket.repo.TicketMessageRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工单对话线程服务(阶段4 多轮对话)。
 *
 * <p>职责:追加客户/系统消息、按序取线程、把历史渲染为喂给 Agent 的转录文本。
 * 线程是"PENDING 单基于上下文续跑"的上下文来源:每轮 reply 把历史注入图状态,
 * 分诊/诊断/问答/回复各 Agent 均能看到此前沟通,客户无需重复背景。
 *
 * <p>阶段4优化:转录改为按行从尾部累加(不再按字符硬切),长度预算内保留完整消息行;
 * 客户消息可携带 requestId 用于入口幂等。
 */
@Component
public class ConversationService {

    /** 喂给提示词的历史转录上限(字符) */
    public static final int TRANSCRIPT_MAX_CHARS = 6000;

    /** 单行超预算时的截断标记 */
    private static final String ELLIPSIS = "…";

    /** 对话消息(对外只读) */
    public record Message(String role, String content, Instant createdAt) {
    }

    private final TicketMessageRepository repository;

    public ConversationService(TicketMessageRepository repository) {
        this.repository = repository;
    }

    /** 追加一条客户消息(提交首条诉求/每轮 reply) */
    public void appendUser(String ticketId, String content) {
        appendUser(ticketId, content, null);
    }

    /**
     * 追加一条客户消息并携带客户端请求号(阶段4优化 O7 入口幂等):
     * 同一工单下同一 requestId 只允许落一条,重复请求由调用方直接返回首次结果。
     */
    public void appendUser(String ticketId, String content, String requestId) {
        append(ticketId, TicketMessageEntity.ROLE_USER, content, requestId);
    }

    /** 追加一条系统回复(首轮答复/审批结单终稿) */
    public void appendAgent(String ticketId, String content) {
        if (content == null || content.isBlank()) {
            return; // 图异常中断无终稿时不落空消息,线程只保留客户侧发言
        }
        append(ticketId, TicketMessageEntity.ROLE_AGENT, content, null);
    }

    /** 本工单完整线程(旧→新);工单无消息返回空列表 */
    public List<Message> messages(String ticketId) {
        return repository.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).stream()
                .map(e -> new Message(e.getRole(), e.getContent(), e.getCreatedAt()))
                .toList();
    }

    /** 本工单是否已处理过该 requestId(幂等判重) */
    public boolean hasRequest(String ticketId, String requestId) {
        return requestId != null && !requestId.isBlank()
                && repository.findByTicketIdAndClientMsgId(ticketId, requestId).isPresent();
    }

    /** 本工单最新一条系统回复(阶段 O8 状态视图的 finalReply 口径:终稿/草稿/人工完结答复);无则 null */
    public String latestAgentReply(String ticketId) {
        return repository.findFirstByTicketIdAndRoleOrderByIdDesc(ticketId, TicketMessageEntity.ROLE_AGENT)
                .map(TicketMessageEntity::getContent)
                .orElse(null);
    }

    /** 本工单消息数(状态视图的 rounds 口径,不加载整线程) */
    public long count(String ticketId) {
        return repository.countByTicketId(ticketId);
    }

    /**
     * 历史转录文本(喂给 Agent 的上下文):"客户: …" / "客服: …" 逐行拼接。
     * 无历史返回空串(首轮);超长时按行从尾部保留,不切断半行。
     */
    public String transcript(String ticketId) {
        String text = messages(ticketId).stream()
                .map(this::renderLine)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return tail(text, TRANSCRIPT_MAX_CHARS);
    }

    /**
     * 从文本尾部保留至多 {@code maxChars} 字符,以整行为单位丢弃旧内容(阶段4优化 O5)。
     *
     * <p>原实现 {@code substring(len-6000)} 会把首行切成半句,Agent 看到残缺上下文;
     * 改为按行逆序累加:预算放不下的最旧行整行丢弃,首行必定是完整消息行。
     * 单行自身超预算时只截断该行,并在行首加省略标记。
     */
    public static String tail(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>();
        int total = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line.length() > maxChars) {
                // 单行超预算:只保留该行尾部,行首省略标记提示"更早内容已截断"
                int keep = Math.max(0, maxChars - ELLIPSIS.length());
                line = ELLIPSIS + (keep == 0 ? "" : line.substring(line.length() - keep));
            }
            int cost = line.length() + (kept.isEmpty() ? 0 : 1); // 行间换行符也计入预算
            if (total + cost > maxChars) {
                break; // 放不下的更旧行整行丢弃,保证首行完整
            }
            kept.add(line);
            total += cost;
        }
        Collections.reverse(kept);
        return String.join("\n", kept);
    }

    private String renderLine(Message m) {
        return (TicketMessageEntity.ROLE_USER.equals(m.role()) ? "客户: " : "客服: ") + m.content();
    }

    private void append(String ticketId, String role, String content, String clientMsgId) {
        // 空请求号统一存 null:否则多行空串会撞 (ticket_id, client_msg_id) 唯一约束
        String msgId = clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId;
        repository.save(new TicketMessageEntity(ticketId, role, content, msgId, Instant.now()));
    }
}
