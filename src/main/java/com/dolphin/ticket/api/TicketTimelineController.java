package com.dolphin.ticket.api;

import com.dolphin.ticket.infra.audit.AuditLogEntity;
import com.dolphin.ticket.infra.audit.AuditLogRepository;
import com.dolphin.ticket.repo.TicketEntity;
import com.dolphin.ticket.repo.TicketRepository;
import com.dolphin.ticket.service.ApprovalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工单时间线 API(P1 全链路可见化):把一张工单的节点轨迹、审批决策与关键动作
 * 按发生顺序还原成一条可读时间线,支撑"任意一单可追溯、可演示"。
 *
 * <p>数据源全部复用既有表(零 schema 变更):节点轨迹来自 audit_log 的
 * {@code graph:<node>:enter|exit|error},审批决策来自 approval_decision。
 * 排序键必须用 audit_log.id(严格单调),{@code at} 仅作展示——同毫秒事件会并列。
 *
 * <p>404 仅当工单不存在;审计为空也返回 200 + 空 events(YAGNI,不做分页)。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketTimelineController {

    private static final String GRAPH_PREFIX = "graph:";

    private final TicketRepository ticketRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalService approvalService;

    public TicketTimelineController(TicketRepository ticketRepository,
                                    AuditLogRepository auditLogRepository,
                                    ApprovalService approvalService) {
        this.ticketRepository = ticketRepository;
        this.auditLogRepository = auditLogRepository;
        this.approvalService = approvalService;
    }

    /** 工单时间线:工单快照 + 审批决策 + 审计事件 + 节点路径 */
    @GetMapping("/{id}/timeline")
    public ResponseEntity<Map<String, Object>> timeline(@PathVariable String id) {
        Optional<TicketEntity> found = ticketRepository.findById(id);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "工单不存在: " + id));
        }
        TicketEntity ticket = found.get();

        // 一次查询同时产出事件列表与节点路径:避免对 audit_log 做两次扫描
        List<AuditLogEntity> logs = auditLogRepository.findBySessionIdOrderByIdAsc(id);
        List<Map<String, Object>> events = new ArrayList<>(logs.size());
        Set<String> nodePath = new LinkedHashSet<>();
        for (AuditLogEntity log : logs) {
            events.add(eventView(log));
            nodeOf(log.getAction()).ifPresent(nodePath::add);
        }

        List<Map<String, Object>> decisions = approvalService.decisionOf(id)
                .map(d -> List.of(Map.<String, Object>of(
                        "approved", d.approved(),
                        "approver", d.approver(),
                        "reason", d.reason() == null ? "" : d.reason(),
                        "decidedAt", d.decidedAt().toString())))
                .orElse(List.of());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", id);
        body.put("ticket", ticketView(ticket));
        body.put("decisions", decisions);
        body.put("events", events);
        body.put("nodePath", List.copyOf(nodePath));
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> ticketView(TicketEntity ticket) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", ticket.getStatus());
        view.put("rawMessage", ticket.getRawMessage());
        view.put("createdAt", ticket.getCreatedAt().toString());
        view.put("updatedAt", ticket.getUpdatedAt().toString());
        return view;
    }

    private static Map<String, Object> eventView(AuditLogEntity log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("at", log.getAt().toString());
        view.put("phase", phaseOf(log.getAction()));
        view.put("action", log.getAction());
        view.put("summary", log.getDetail() == null ? "" : log.getDetail());
        return view;
    }

    /**
     * 动作相位:节点轨迹 → node;基线/验证 → verify;意图路由 → router;质检 → quality;
     * 其余(执行器动作、人工池操作等)→ action。由 action 命名约定推导,不落库。
     */
    private static String phaseOf(String action) {
        if (action == null) {
            return "action";
        }
        if (action.startsWith(GRAPH_PREFIX)) {
            return "node";
        }
        if (action.contains(":BASELINE") || action.contains(":VERIFY")) {
            return "verify";
        }
        if ("intent-route".equals(action)) {
            return "router";
        }
        if ("quality-review".equals(action)) {
            return "quality";
        }
        return "action";
    }

    /** 从 {@code graph:<node>:enter} 提取节点名(仅 enter 计入路径,exit/error 不重复计数) */
    private static Optional<String> nodeOf(String action) {
        String enterSuffix = ":enter";
        if (action == null || !action.startsWith(GRAPH_PREFIX) || !action.endsWith(enterSuffix)) {
            return Optional.empty();
        }
        String node = action.substring(GRAPH_PREFIX.length(), action.length() - enterSuffix.length());
        return node.isBlank() ? Optional.empty() : Optional.of(node);
    }
}
