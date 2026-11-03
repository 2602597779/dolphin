package com.dolphin.ticket.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 基础设施配置。
 *
 * <p>当前使用 Spring AI 内置的 {@link SimpleVectorStore}(内存 + 余弦相似度),
 * 个人项目数据量(千级向量)下完全够用;后续切换 Qdrant/pgvector 只需替换此 Bean,
 * 上层 {@link TicketRetriever}/{@link DocIngestService} 面向 VectorStore 接口编程,无需改动。
 *
 * <p>构建后立即从文件快照恢复({@link VectorStoreFilePersistence}):快照内含 embedding,
 * 重启不重新调用远端 API。
 */
@Configuration
public class RagConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, VectorStoreFilePersistence persistence) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        persistence.loadInto(store);
        return store;
    }
}
