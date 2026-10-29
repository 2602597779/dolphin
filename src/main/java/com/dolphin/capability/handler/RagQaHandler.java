package com.dolphin.capability.handler;

import com.dolphin.capability.CapabilityDescriptor;
import com.dolphin.capability.CapabilityHandler;
import com.dolphin.router.DialogContext;
import com.dolphin.ticket.rag.TicketRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 知识问答能力:向量检索召回知识片段 → 拼上下文 → LLM 作答。
 *
 * <p>检索复用 {@link TicketRetriever}(与工单诊断共享同一向量库),
 * 资料不足时明确要求模型说明"资料中未提及",抑制幻觉。
 */
@Component
public class RagQaHandler implements CapabilityHandler {

    private static final int TOP_K = 4;

    private final ChatClient chat;
    private final TicketRetriever ticketRetriever;

    public RagQaHandler(ChatClient.Builder builder, TicketRetriever ticketRetriever) {
        this.ticketRetriever = ticketRetriever;
        this.chat = builder.defaultSystem("""
                你是云服务知识问答助手。请严格基于提供的资料回答用户问题,
                资料中没有的信息要明确说明"资料中未提及",不要编造。
                回答用中文,简洁分点。""").build();
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return CapabilityDescriptor.of(
                "rag-qa", "知识问答",
                "询问云产品的使用方法、功能说明、流程、配额、计费等知识类问题,或咨询历史相似故障的解决办法",
                List.of("ECS 实例怎么重置密码", "RDS 的备份策略是什么", "对象存储怎么开通", "连接池耗尽以前是怎么处理的"),
                List.of("怎么", "如何", "什么是", "流程", "步骤", "开通", "备份", "配额", "支持吗", "多少钱", "文档"));
    }

    @Override
    public String handle(String message, DialogContext ctx) {
        List<String> contexts;
        String contextBlock;
        try {
            contexts = ticketRetriever.retrieve(message, TOP_K);
        } catch (Exception e) {
            // Embedding 服务不可用时降级为无上下文直答(明确告知知识库不可用)
            contexts = List.of();
        }
        contextBlock = contexts.isEmpty()
                ? "(知识库暂无召回内容)"
                : String.join("\n---\n", contexts);
        try {
            String answer = chat.prompt().user("""
                    已知资料:
                    %s

                    用户问题:%s""".formatted(contextBlock, message)).call().content();
            return answer == null ? "知识问答暂时不可用,请稍后再试。" : answer;
        } catch (Exception e) {
            return "知识问答服务暂时不可用(模型调用失败):" + e.getMessage();
        }
    }
}
