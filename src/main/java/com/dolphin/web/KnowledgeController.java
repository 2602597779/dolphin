package com.dolphin.web;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.rag.DocIngestService;
import com.dolphin.ticket.rag.TicketRetriever;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API:动态摄取文档 + 检索调试。
 *
 * <p>ingest 是知识库"生长"的入口(M4 诊断留档回流也走同一服务);
 * search 用于调试召回效果,面试演示 RAG 时直观展示命中片段。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    /** 摄取请求体 */
    public record IngestRequest(String docId, String content) {
    }

    /** 检索调试请求体 */
    public record QueryRequest(String query, Integer topK) {
    }

    private final DocIngestService docIngestService;
    private final TicketRetriever ticketRetriever;
    private final AuditLogger auditLogger;

    public KnowledgeController(DocIngestService docIngestService,
                               TicketRetriever ticketRetriever,
                               AuditLogger auditLogger) {
        this.docIngestService = docIngestService;
        this.ticketRetriever = ticketRetriever;
        this.auditLogger = auditLogger;
    }

    /** 摄取一篇文档到向量库(切分+Embedding 由 DocIngestService 完成) */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.docId() == null || request.docId().isBlank()
                || request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "docId 与 content 不能为空"));
        }
        try {
            docIngestService.ingest(request.docId(), request.content());
        } catch (Exception e) {
            // Embedding 服务不可用(无 Key/网络)时明确报错,不静默吞掉
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "摄取失败: " + e.getMessage()));
        }
        auditLogger.log("-", "knowledge-ingest", "docId=" + request.docId());
        return ResponseEntity.ok(Map.of("status", "ok", "docId", request.docId()));
    }

    /** 检索调试:查看某句话在向量库中的召回结果 */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody QueryRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return Map.of("results", List.of());
        }
        int topK = request.topK() == null || request.topK() <= 0 ? 5 : Math.min(request.topK(), 20);
        List<String> results;
        try {
            results = ticketRetriever.retrieve(request.query(), topK);
        } catch (Exception e) {
            return Map.of("results", List.of(), "error", "检索失败: " + e.getMessage());
        }
        return Map.of("query", request.query(), "results", results);
    }
}
