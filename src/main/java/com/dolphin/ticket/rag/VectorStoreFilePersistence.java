package com.dolphin.ticket.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 向量库文件持久化:SimpleVectorStore(内存实现)的快照落盘与启动恢复。
 *
 * <p>{@code save(File)} 导出的文件内含已算好的 embedding——重启后 {@code load(File)}
 * 直接恢复,不再调用远端 Embedding API;每次 ingest 后同步落盘,
 * DocIngestService 的 @PreDestroy 兜底再存一次。文件损坏只降级为空库(检索为空),不阻塞应用启动。
 */
@Component
public class VectorStoreFilePersistence {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFilePersistence.class);

    private final Path path;

    public VectorStoreFilePersistence(
            @Value("${dolphin.rag.vector-store-path:./data/vector-store.json}") String path) {
        this.path = Path.of(path);
    }

    /** 快照文件是否已存在(重启后 KnowledgeSeeder 据此跳过种子灌入,避免重复调用 Embedding) */
    public boolean exists() {
        return Files.exists(path);
    }

    /** 启动时从快照恢复向量库(文件存在才 load,含 embedding 不再调用远端 API) */
    public void loadInto(SimpleVectorStore store) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            store.load(path.toFile());
            log.info("[rag] 向量库快照已恢复: {}", path);
        } catch (Exception e) {
            log.warn("[rag] 向量库快照恢复失败(降级为空库,仅检索为空): {} - {}", path, e.getMessage());
        }
    }

    /** 当前向量库快照落盘(synchronized 串行化并发 ingest 的并发写) */
    public synchronized void saveFrom(SimpleVectorStore store) {
        if (store == null) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            store.save(path.toFile());
        } catch (Exception e) {
            log.warn("[rag] 向量库快照落盘失败(不影响主流程): {} - {}", path, e.getMessage());
        }
    }
}
