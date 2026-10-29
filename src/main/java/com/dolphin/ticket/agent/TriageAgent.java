package com.dolphin.ticket.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 分诊 Agent:把用户工单文本分类为 产品/类型/紧急度。
 *
 * <p>结构化输出走 entity(record) 反序列化;LLM 调用或解析失败时,
 * 先兜底行式解析("产品:xxx"逐行提取),再兜底默认分类(咨询/低),保证图流程不中断。
 */
@Component
public class TriageAgent {

    private static final Logger log = LoggerFactory.getLogger(TriageAgent.class);

    /** 合法的类型取值 */
    private static final java.util.Set<String> VALID_TYPES = java.util.Set.of("咨询", "故障", "高危", "超范围");

    /** 结构化分类结果 */
    public record Classification(String product, String type, String urgency) {
    }

    private final ChatClient chatClient;

    public TriageAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(PromptLoader.load("triage.st"))
                .build();
    }

    /**
     * 分诊一条工单。
     *
     * @param rawMessage 用户原始工单文本
     * @return Map 结构:{product, type, urgency}
     */
    public Map<String, Object> classify(String rawMessage) {
        Classification c = null;
        try {
            c = chatClient.prompt()
                    .user(rawMessage)
                    .call()
                    .entity(Classification.class);
        } catch (Exception e) {
            log.warn("分诊结构化输出失败,尝试行式兜底解析: {}", e.getMessage());
        }
        if (c == null || !VALID_TYPES.contains(c.type())) {
            c = parseLinewise(chatOnce(rawMessage));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("product", c.product() == null ? "未知产品" : c.product());
        result.put("type", VALID_TYPES.contains(c.type()) ? c.type() : "咨询");
        result.put("urgency", c.urgency() == null ? "低" : c.urgency());
        return result;
    }

    /** 让模型按行输出 产品:/类型:/紧急度: 的兜底调用 */
    private String chatOnce(String rawMessage) {
        try {
            return chatClient.prompt()
                    .user(rawMessage + "\n\n请严格只输出三行:产品:xxx、类型:xxx、紧急度:xxx")
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("分诊兜底调用也失败,使用默认分类: {}", e.getMessage());
            return "";
        }
    }

    /** 行式解析 "产品:xxx" 形式的输出 */
    private Classification parseLinewise(String text) {
        String product = null;
        String type = null;
        String urgency = null;
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                String s = line.trim();
                if (s.startsWith("产品:")) {
                    product = s.substring(3).trim();
                } else if (s.startsWith("类型:")) {
                    type = s.substring(3).trim();
                } else if (s.startsWith("紧急度:")) {
                    urgency = s.substring(4).trim();
                }
            }
        }
        return new Classification(product, type, urgency);
    }
}
