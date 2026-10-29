package com.dolphin.ticket.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识问答 Agent:处理咨询类工单,基于知识库检索结果直接生成答案。
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
     * @param rawMessage     用户原始工单文本
     * @param classification 分诊结果
     * @return 知识答案文本
     */
    public String answer(String rawMessage, Map<String, Object> classification) {
        String user = "工单原文:" + rawMessage
                + "\n涉及产品:" + classification.getOrDefault("product", "未知");
        // TODO: 接入真实 RAG 后,把向量检索到的知识片段拼进 user 消息
        return chatClient.prompt().user(user).call().content();
    }
}
