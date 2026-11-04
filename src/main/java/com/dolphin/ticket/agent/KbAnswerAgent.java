package com.dolphin.ticket.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识问答 Agent:处理咨询类工单,基于知识库检索结果直接生成答案。
 *
 * <p>RAG 拼接约定与 {@code RagQaHandler} 一致:召回片段为空时显式告知
 * "知识库暂无召回内容",提示词要求片段未覆盖时才用产品知识补充,
 * 抑制幻觉。
 */
@Component
public class KbAnswerAgent {

    private final ChatClient chatClient;

    public KbAnswerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptLoader.load("kb-answer.st"))
                .build();
    }

    /**
     * 回答咨询类工单。
     *
     * @param rawMessage       用户原始工单文本(多轮场景为最新一条客户消息)
     * @param classification   分诊结果
     * @param similarKnowledge 向量检索召回的知识片段(形如 "[来源:docId] 内容",可为空)
     * @param history          对话历史转录("客户: …"/"客服: …"),首轮为空串
     * @return 知识答案文本
     */
    public String answer(String rawMessage, Map<String, Object> classification,
                         List<String> similarKnowledge, String history) {
        String user = buildUserMessage(rawMessage, classification, similarKnowledge, history);
        return chatClient.prompt().user(user).call().content();
    }

    /** 构建 user 消息(package 静态,便于单测覆盖拼接分支) */
    static String buildUserMessage(String rawMessage, Map<String, Object> classification,
                                   List<String> similarKnowledge, String history) {
        String knowledgeBlock = (similarKnowledge == null || similarKnowledge.isEmpty())
                ? "(知识库暂无召回内容)"
                : String.join("\n---\n", similarKnowledge);
        return "工单原文:" + rawMessage
                + "\n涉及产品:" + classification.getOrDefault("product", "未知")
                + (history == null || history.isBlank()
                        ? ""
                        : "\n对话历史(客户此前沟通,请结合上下文回答):\n" + history)
                + "\n知识库召回片段:\n" + knowledgeBlock;
    }
}
