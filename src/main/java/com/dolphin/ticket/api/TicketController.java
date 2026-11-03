package com.dolphin.ticket.api;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工单入口 API。
 *
 * <p>提交工单同步跑图:无写操作直达结单;有写操作在 approval 节点前中断,
 * 返回 PENDING_APPROVAL,待人工调用 approve 接口后 resume 续跑。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    /** 提交请求体 */
    public record SubmitRequest(String message) {
    }

    /** 审批请求体(approver/reason 可选,用于决策留痕) */
    public record ApproveRequest(boolean approved, String approver, String reason) {
    }

    private final TicketGraphCompiler compiler;
    private final TicketService ticketService;
    private final ApprovalService approvalService;

    public TicketController(TicketGraphCompiler compiler,
                            TicketService ticketService,
                            ApprovalService approvalService) {
        this.compiler = compiler;
        this.ticketService = ticketService;
        this.approvalService = approvalService;
    }

    /** 提交工单,同步执行图流程;归属取自鉴权上下文(API-Key 对应客户) */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody SubmitRequest request,
                                                       HttpServletRequest httpRequest) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message 不能为空"));
        }
        String customerId = currentCustomer(httpRequest);
        String ticketId = ticketService.create(request.message(), customerId);
        ticketService.updateStatus(ticketId, TicketService.STATUS_RUNNING);
        RunnableConfig config = RunnableConfig.builder().threadId(ticketId).build();

        Optional<OverAllState> result;
        try {
            // P0 并发根治:每请求独立编译,图模板不再跨请求共享,invoke 只传本单数据即可
            CompiledGraph graph = compiler.compile();
            result = graph.invoke(Map.of(
                    TicketState.TICKET_ID, ticketId,
                    TicketState.RAW_MESSAGE, request.message()), config);
        } catch (Exception e) {
            log.error("工单图执行失败 ticketId={}", ticketId, e);
            ticketService.updateStatus(ticketId, TicketService.STATUS_NEW);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "工单处理失败: " + e.getMessage()));
        }

        OverAllState state = result.orElse(null);
        String pendingAction = state == null ? "" : TicketState.str(state, TicketState.PENDING_ACTION, "");
        String finalReply = state == null ? null : TicketState.str(state, TicketState.FINAL_REPLY, null);
        String status;
        if (finalReply == null || finalReply.isBlank()) {
            if (!pendingAction.isBlank()) {
                // 图停在 approval 前,等待人工决策
                status = TicketService.STATUS_PENDING_APPROVAL;
                ticketService.updateStatus(ticketId, status);
            } else {
                // 图未产出终稿(异常中断),回退状态
                status = ticketService.get(ticketId).map(TicketService.Ticket::status)
                        .orElse(TicketService.STATUS_NEW);
            }
        } else {
            status = ticketService.get(ticketId).map(TicketService.Ticket::status)
                    .orElse(TicketService.STATUS_CLOSED);
        }

        return ResponseEntity.ok(buildResponse(ticketId, status, state));
    }

    /** 查询工单(租户隔离:非本客户的单返回 404,不泄露存在性) */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id, HttpServletRequest httpRequest) {
        return ticketService.getForCustomer(id, currentCustomer(httpRequest))
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(Map.of(
                        "ticketId", t.id(),
                        "rawMessage", t.rawMessage(),
                        "status", t.status())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "工单不存在: " + id)));
    }

    /** 工单列表(只看本客户的;status 可选过滤,新→旧) */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "20") int limit,
                                  HttpServletRequest httpRequest) {
        List<Map<String, Object>> tickets = ticketService
                .listForCustomer(currentCustomer(httpRequest), status, limit)
                .stream()
                .map(t -> Map.<String, Object>of(
                        "ticketId", t.id(),
                        "status", t.status()))
                .toList();
        return ResponseEntity.ok(Map.of("tickets", tickets, "count", tickets.size()));
    }

    /** 人工审批:记录决策 → updateState + resume 续跑图(仅工单归属人可审批) */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id,
                                                      @RequestBody ApproveRequest request,
                                                      HttpServletRequest httpRequest) {
        Optional<TicketService.Ticket> ticket = ticketService.getForCustomer(id, currentCustomer(httpRequest));
        if (ticket.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "工单不存在: " + id));
        }
        if (!TicketService.STATUS_PENDING_APPROVAL.equals(ticket.get().status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "工单当前状态不允许审批: " + ticket.get().status()));
        }

        boolean approved = request != null && request.approved();
        // 探针结论:approval 节点读不到 checkpoint 更新,决策必须先落 ApprovalService 再 resume;
        // 批准令牌由 approval 节点在 resume 链路中签发(绑定图状态里的权威待执行动作),此处只记录决策留痕
        approvalService.recordDecision(id, approved,
                request == null ? null : request.approver(),
                request == null ? null : request.reason());
        RunnableConfig config = RunnableConfig.builder().threadId(id).build();

        OverAllState state;
        try {
            // P0 并发根治:每请求独立编译;新图模板为空,先从 DB checkpoint 水合本单状态
            // (node action 读的是图模板,checkpoint 数据只用于确定续跑起点)
            CompiledGraph graph = compiler.compile();
            graph.overAllState().input(graph.getState(config).state().data());
            graph.updateState(config, Map.of(TicketState.APPROVED, approved), null);
            graph.resume(new OverAllState.HumanFeedback(Map.of("approved", approved), null), config);
            // resume 返回的快照是独立 state 对象,节点输出写在本请求独立模板上,
            // 终稿/执行结果从模板读取;resume 同步跑完后模板即本单终态
            state = graph.overAllState();
        } catch (Exception e) {
            log.error("工单审批续跑失败 ticketId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "审批续跑失败: " + e.getMessage()));
        }

        String finalReply = state == null ? null : TicketState.str(state, TicketState.FINAL_REPLY, null);
        String status = finalReply == null || finalReply.isBlank()
                ? ticketService.get(id).map(TicketService.Ticket::status).orElse(TicketService.STATUS_RUNNING)
                : ticketService.get(id).map(TicketService.Ticket::status).orElse(TicketService.STATUS_CLOSED);
        return ResponseEntity.ok(buildResponse(id, status, state));
    }

    /** 从鉴权上下文取当前客户;HTTP 链路由 ApiKeyAuthFilter 写入,直调(单测)为 null=内部渠道 */
    private String currentCustomer(HttpServletRequest request) {
        return request == null ? null : (String) request.getAttribute(ApiKeyAuthFilter.CUSTOMER_ID_ATTR);
    }

    /** 统一组装响应 */
    private Map<String, Object> buildResponse(String ticketId, String status, OverAllState state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("status", status);
        body.put("replyDraft", state == null ? null : TicketState.str(state, TicketState.REPLY_DRAFT, null));
        body.put("finalReply", state == null ? null : TicketState.str(state, TicketState.FINAL_REPLY, null));
        body.put("pendingAction", state == null ? null : TicketState.str(state, TicketState.PENDING_ACTION, null));
        return body;
    }
}
