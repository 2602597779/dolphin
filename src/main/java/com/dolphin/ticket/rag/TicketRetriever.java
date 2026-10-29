package com.dolphin.ticket.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 相似知识检索器:对 query 做 Embedding,到向量库做 topK 相似度检索。
 *
 * <p>同时服务两个场景:工单图内的"相似历史工单参考"(DiagnosisTools)与
 * RAG 问答能力(RagQaHandler)的上下文召回。
 */
@Component
public class TicketRetriever {

    /** 相似度阈值:低于此分数的片段视为不相关,避免向 prompt 注入噪声 */
    private static final double SIMILARITY_THRESHOLD = 0.30;

    private final VectorStore vectorStore;

    public TicketRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 检索与 query 语义最相近的知识片段。
     *
     * @param query 检索语句(用户问题/工单原文)
     * @param topK  返回条数
     * @return 形如 "[来源#docId] 片段内容" 的列表,供拼入 prompt
     */
    public List<String> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build());
        if (docs == null) {
            return List.of();
        }
        return docs.stream()
                .map(d -> "[来源:" + d.getId() + "] " + d.getText())
                .toList();
    }
}
