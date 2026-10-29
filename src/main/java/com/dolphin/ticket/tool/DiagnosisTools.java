package com.dolphin.ticket.tool;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.rag.TicketRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 诊断辅助工具:检索相似历史工单,为诊断 Agent 提供参考案例。
 */
@Component
public class DiagnosisTools {

    private final TicketRetriever ticketRetriever;
    private final AuditLogger auditLogger;

    public DiagnosisTools(TicketRetriever ticketRetriever, AuditLogger auditLogger) {
        this.ticketRetriever = ticketRetriever;
        this.auditLogger = auditLogger;
    }

    /** 检索相似历史工单 */
    @Tool(name = "searchSimilarTickets", description = "按问题描述检索相似的历史工单,返回处理结论供参考")
    public String searchSimilarTickets(@ToolParam(description = "问题描述关键词") String query,
                                       @ToolParam(description = "返回条数,建议 3") int topK) {
        // TODO: topK 上限校验;真实检索需按产品线过滤
        auditLogger.log("-", "searchSimilarTickets", "query=" + query + " topK=" + topK);
        return String.join("\n", ticketRetriever.retrieve(query, topK));
    }
}
