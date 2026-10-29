package com.dolphin.ticket.api;

import com.dolphin.ticket.service.HumanTicketPool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 人工工单池 REST 接口:专员查询待办、认领、完结。
 *
 * <p>来源:图编排 escalateHuman 节点落队的转人工任务(执行验证失败/建议不可信/高危超范围等)。
 * 完结时工单状态自动转 CLOSED。
 */
@RestController
@RequestMapping("/api/human-tickets")
public class HumanTicketController {

    /** 认领请求体(owner 可选,默认 anonymous) */
    public record ClaimRequest(String owner) {
    }

    /** 完结请求体(note 可选,处理结论留痕) */
    public record ResolveRequest(String note) {
    }

    private final HumanTicketPool pool;

    public HumanTicketController(HumanTicketPool pool) {
        this.pool = pool;
    }

    /** 查询人工任务(status 可选:WAITING/CLAIMED/RESOLVED,缺省全量) */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status) {
        List<HumanTicketPool.HumanTask> tasks = pool.list(status);
        return Map.of("total", tasks.size(), "tasks", tasks);
    }

    /** 认领:WAITING → CLAIMED */
    @PostMapping("/{ticketId}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable String ticketId,
                                                     @RequestBody(required = false) ClaimRequest request) {
        Optional<HumanTicketPool.HumanTask> task = pool.get(ticketId);
        if (task.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "人工任务不存在: " + ticketId));
        }
        return pool.claim(ticketId, request == null ? null : request.owner())
                .<ResponseEntity<Map<String, Object>>>map(t -> ResponseEntity.ok(Map.of(
                        "ticketId", t.ticketId(), "status", t.status(), "owner", t.owner())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "任务当前状态不允许认领: " + task.get().status())));
    }

    /** 完结:CLAIMED → RESOLVED,工单转 CLOSED */
    @PostMapping("/{ticketId}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String ticketId,
                                                       @RequestBody(required = false) ResolveRequest request) {
        Optional<HumanTicketPool.HumanTask> task = pool.get(ticketId);
        if (task.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "人工任务不存在: " + ticketId));
        }
        return pool.resolve(ticketId, request == null ? null : request.note())
                .<ResponseEntity<Map<String, Object>>>map(t -> ResponseEntity.ok(Map.of(
                        "ticketId", t.ticketId(), "status", t.status(), "owner", t.owner())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "任务当前状态不允许完结(需先认领): " + task.get().status())));
    }
}
