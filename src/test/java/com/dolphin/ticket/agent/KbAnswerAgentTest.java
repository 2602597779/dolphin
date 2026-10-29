package com.dolphin.ticket.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KbAnswerAgent user 消息构建单测:验证 RAG 召回片段正确拼入 prompt,
 * 空召回时显式标注"知识库暂无召回内容"(与 RagQaHandler 拼接约定一致)。
 */
class KbAnswerAgentTest {

    @Test
    void knowledgeSnippetsAreSplicedIntoUserMessage() {
        List<String> snippets = List.of(
                "[来源:kb-slb-001] 健康检查不通过的常见原因之一:后端端口未监听。",
                "[来源:kb-slb-002] 安全组未放行健康检查探测流量也会导致失败。");
        String user = KbAnswerAgent.buildUserMessage("SLB 健康检查不通过?", Map.of("product", "SLB"), snippets);
        // 工单原文与产品名必须保留
        assertTrue(user.contains("工单原文:SLB 健康检查不通过?"));
        assertTrue(user.contains("涉及产品:SLB"));
        // 两个片段均按原格式拼入,以 --- 分隔
        assertTrue(user.contains("[来源:kb-slb-001]"));
        assertTrue(user.contains("[来源:kb-slb-002]"));
        assertTrue(user.contains("\n---\n"));
        assertFalse(user.contains("(知识库暂无召回内容)"));
    }

    @Test
    void emptyRetrievalMarksNoRecallExplicitly() {
        String user = KbAnswerAgent.buildUserMessage("怎么开发票?", Map.of("product", "计费"), List.of());
        assertTrue(user.contains("(知识库暂无召回内容)"));
        assertTrue(user.contains("工单原文:怎么开发票?"));
    }

    @Test
    void nullRetrievalIsTolerated() {
        String user = KbAnswerAgent.buildUserMessage("怎么开发票?", Map.of(), null);
        assertTrue(user.contains("(知识库暂无召回内容)"));
        // 产品缺失时回退为"未知"
        assertTrue(user.contains("涉及产品:未知"));
    }
}
