package com.dolphin.ticket.tool;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.TicketService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工单域工具:供 Agent 以 function-calling 方式查询工单台账。
 * 骨架阶段为薄壳——参数转换 + 调 service + 审计留痕。
 */
@Component
public class TicketTools {

    private final TicketService ticketService;
    private final AuditLogger auditLogger;

    public TicketTools(TicketService ticketService, AuditLogger auditLogger) {
        this.ticketService = ticketService;
        this.auditLogger = auditLogger;
    }

    /** 查询工单详情 */
    @Tool(name = "queryTicketDetail", description = "根据工单号查询工单详情,包含原始描述与当前状态")
    public String queryTicketDetail(@ToolParam(description = "工单号,如 T1234ABCD") String ticketId) {
        // TODO: 权限校验(只允许查询当前会话关联的工单)
        auditLogger.log(ticketId, "queryTicketDetail", "ticketId=" + ticketId);
        return ticketService.get(ticketId)
                .map(t -> "工单 " + t.id() + " 状态=" + t.status() + " 原始描述:" + t.rawMessage())
                .orElse("未找到工单 " + ticketId);
    }
}
