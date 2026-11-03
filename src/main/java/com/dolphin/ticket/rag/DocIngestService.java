package com.dolphin.ticket.rag;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识库文档摄取服务:切分 → Embedding → 写入向量库。
 *
 * <p>流程:原始文档 → {@link TokenTextSplitter} 按 token 切分为片段
 * → VectorStore.add 时由绑定的 EmbeddingModel 逐片段向量化并存储。
 * 每次摄取后同步落盘向量库快照(含 embedding,重启不重算),@PreDestroy 兜底再存一次。
 */
@Service
public class DocIngestService {

    private final VectorStore vectorStore;
    private final VectorStoreFilePersistence persistence;
    private final TokenTextSplitter textSplitter = new TokenTextSplitter();

    public DocIngestService(VectorStore vectorStore, VectorStoreFilePersistence persistence) {
        this.vectorStore = vectorStore;
        this.persistence = persistence;
    }

    /**
     * 摄取一篇文档:切分后写入向量库并落盘快照。
     *
     * @param docId   文档标识(写入 metadata,检索结果可溯源)
     * @param content 文档正文
     */
    public void ingest(String docId, String content) {
        Document doc = Document.builder()
                .id(docId)
                .text(content)
                .metadata(Map.of("source", docId))
                .build();
        List<Document> chunks = textSplitter.apply(List.of(doc));
        vectorStore.add(chunks);
        persistence.saveFrom(asSimpleStore());
    }

    @PreDestroy
    void saveOnShutdown() {
        persistence.saveFrom(asSimpleStore());
    }

    private SimpleVectorStore asSimpleStore() {
        return (SimpleVectorStore) vectorStore;
    }
}
