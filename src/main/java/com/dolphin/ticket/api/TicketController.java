package com.dolphin.ticket.api;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.TicketGraphCompiler;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.HumanTicketPool;
import com.dolphin.ticket.service.TicketLockRegistry;
import com.dolphin.ticket.service.TicketRoundService;
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
 *
 * <p>多轮对话(阶段4):客户经 reply 接口在同一工单下追问,对话历史注入图状态,
 * 系统按最新上下文从头重跑;线程经 messages 接口全程可追溯。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    /** 提交请求体 */
    public record SubmitRequest(String message) {
    }

    /** 追问请求体(多轮对话:基于工单上下文重新评估本轮诉求;requestId 可选,用于入口幂等) */
    public record ReplyRequest(String message, String requestId) {
        public ReplyRequest(String message) {
            this(message, null);
        }
    }

    /** 审批请求体(approver/reason 可选,用于决策留痕) */
    public record ApproveRequest(boolean approved, String approver, String reason) {
    }

    private final TicketGraphCompiler compiler;
    private final TicketService ticketService;
    private final ApprovalService approvalService;
    private final ConversationService conversationService;
    private final TicketCheckpointStore checkpointStore;
    private final TicketLockRegistry lockRegistry;
    private final TicketRoundService roundService;
    private final AuditLogger auditLogger;
    private final HumanTicketPool humanTicketPool;

    public TicketController(TicketGraphCompiler compiler,
                            TicketService ticketService,
                            ApprovalService approvalService,
                            ConversationService conversationService,
                            TicketCheckpointStore checkpointStore,
                            TicketLockRegistry lockRegistry,
                            TicketRoundService roundService,
                            AuditLogger auditLogger,
                            HumanTicketPool humanTicketPool) {
        this.compiler = compiler;
        this.ticketService = ticketService;
        this.approvalService = approvalService;
        this.conversationService = conversationService;
        this.checkpointStore = checkpointStore;
        this.lockRegistry = lockRegistry;
        this.roundService = roundService;
        this.auditLogger = auditLogger;
        this.humanTicketPool = humanTicketPool;
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
        // 对话线程首条消息:后续多轮 reply 的上下文来源(阶段4)
        conversationService.appendUser(ticketId, request.message());
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
        String replyDraft = state == null ? null : TicketState.str(state, TicketState.REPLY_DRAFT, null);
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
        // 落客服侧回复:结单落终稿;待审批时 reply 节点已产出草稿,作为首次答复入线程
        conversationService.appendAgent(ticketId,
                finalReply != null && !finalReply.isBlank() ? finalReply : replyDraft);

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
        // 审批结单终稿入对话线程(阶段4)
        conversationService.appendAgent(id, finalReply);
        return ResponseEntity.ok(buildResponse(id, status, state));
    }

    /**
     * 多轮追问(阶段4):基于工单历史上下文重新评估本轮诉求。
     *
     * <p>语义为"按最新上下文从头重跑":先清旧 checkpoint(旧断点停留在上一轮中断点,
     * 带着它 invoke 会直接 resume 而非重新评估),再把对话历史注入图状态 invoke。
     * PENDING_APPROVAL 单被追问时同样从头评估,客户可边沟通边补全背景。
     *
     * <p>状态约束:CLOSED 已结单不允许追问(409);ESCALATED 已转人工,
     * 只把追问记录进线程供专员查看,不再触发 AI 重跑。
     *
     * <p>阶段4优化:同一工单的追问按 ticketId 串行化(避免两请求同时清断点/跑图踩踏);
     * 可选 requestId 做入口幂等;待审批单被追问时旧动作作废留痕;图执行异常补系统答复。
     */
    @PostMapping("/{id}/reply")
    public ResponseEntity<Map<String, Object>> reply(@PathVariable String id,
                                                     @RequestBody ReplyRequest request,
                                                     HttpServletRequest httpRequest) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message 不能为空"));
        }
        String customerId = currentCustomer(httpRequest);
        if (ticketService.getForCustomer(id, customerId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "工单不存在: " + id));
        }
        // O1:同一工单的"读状态 → 判重 → 清断点 → invoke → 落库收尾"必须是一个临界区,
        // 否则两个并发追问会读到同一个旧状态、同时清断点并以同一 threadId 跑图,线程与状态互相覆盖。
        return lockRegistry.withLock(id, () -> doReply(id, customerId, request));
    }

    /** reply 临界区:锁内重新读状态,保证串行化后的判断基于最新数据 */
    private ResponseEntity<Map<String, Object>> doReply(String id, String customerId, ReplyRequest request) {
        Optional<TicketService.Ticket> ticket = ticketService.getForCustomer(id, customerId);
        if (ticket.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "工单不存在: " + id));
        }
        String status = ticket.get().status();

        // O7 入口幂等:同一工单下同一 requestId 已处理过,直接返回首次结果标记,不重复落消息/跑图。
        // 判重放在 CLOSED 校验之前:首次请求结单后客户端重试同一 requestId,应返回 200 duplicated 而非 409。
        if (request.requestId() != null && !request.requestId().isBlank()
                && conversationService.hasRequest(id, request.requestId())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketId", id);
            body.put("status", status);
            body.put("duplicated", true);
            body.put("note", "重复请求(相同 requestId),本次未重复落消息,也未重复执行");
            body.put("rounds", conversationService.messages(id).size());
            return ResponseEntity.ok(body);
        }

        if (TicketService.STATUS_CLOSED.equals(status)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "工单已结单,如需继续处理请重新提交: " + id));
        }
        if (TicketService.STATUS_ESCALATED.equals(status)) {
            // 已转人工:AI 不再介入,追问只入线程供人工专员跟进
            conversationService.appendUser(id, request.message(), request.requestId());
            return ResponseEntity.ok(Map.of(
                    "ticketId", id,
                    "status", status,
                    "note", "工单已转人工处理,您的追问已记录,将由专员跟进回复"));
        }

        // O3:待审批单被追问会清掉旧断点,旧 PENDING_ACTION 随之失效;
        // 审批人手里的旧链接将无法再执行,必须在清断点前把"原动作"写进审计,保证可追溯。
        if (TicketService.STATUS_PENDING_APPROVAL.equals(status)) {
            String previousAction = checkpointStore.snapshotState(id)
                    .map(s -> String.valueOf(s.getOrDefault(TicketState.PENDING_ACTION, "")))
                    .orElse("")
                    .trim();
            String detail = "客户追问触发重新评估,原待审批动作作废:"
                    + (previousAction.isEmpty() ? "(旧动作未取到,按 PENDING_APPROVAL 状态判定作废)" : previousAction);
            auditLogger.log(id, "approval-invalidated", detail);
            log.info("审批动作作废 ticketId={} previousAction={}", id, previousAction);
        }

        // O4:历史 + 客户消息 + RUNNING 状态同一短事务提交;图执行在事务之外(耗时不占连接)
        String history = roundService.beginRound(id, request.message(), request.requestId());
        RunnableConfig config = RunnableConfig.builder().threadId(id).build();
        Optional<OverAllState> result;
        try {
            // 清旧断点:reply 是从头重跑而非续跑,否则会直接 resume 到上一轮中断点
            checkpointStore.clear(id);
            CompiledGraph graph = compiler.compile();
            result = graph.invoke(Map.of(
                    TicketState.TICKET_ID, id,
                    TicketState.RAW_MESSAGE, request.message(),
                    TicketState.CONVERSATION_HISTORY, history), config);
        } catch (Exception e) {
            log.error("工单追问重跑失败 ticketId={}", id, e);
            // 不得回滚客户消息(保留证据):补一条系统说明并转人工,避免"客户消息已落、线程无答复"
            compensateReplyFailure(id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "追问处理失败,已转人工跟进: " + e.getMessage()));
        }

        OverAllState state = result.orElse(null);
        String pendingAction = state == null ? "" : TicketState.str(state, TicketState.PENDING_ACTION, "");
        String finalReply = state == null ? null : TicketState.str(state, TicketState.FINAL_REPLY, null);
        String replyDraft = state == null ? null : TicketState.str(state, TicketState.REPLY_DRAFT, null);
        String newStatus;
        String statusPatch = null; // 仅当图未落状态(待审批)时才需要写;终态由节点落库,避免覆盖异步质检结论
        if (finalReply == null || finalReply.isBlank()) {
            if (!pendingAction.isBlank()) {
                newStatus = TicketService.STATUS_PENDING_APPROVAL;
                statusPatch = newStatus;
            } else {
                newStatus = ticketService.get(id).map(TicketService.Ticket::status)
                        .orElse(TicketService.STATUS_RUNNING);
            }
        } else {
            newStatus = ticketService.get(id).map(TicketService.Ticket::status)
                    .orElse(TicketService.STATUS_CLOSED);
        }
        // O4:系统回复(+ 待审批状态)同一短事务提交
        roundService.completeRound(id, statusPatch,
                finalReply != null && !finalReply.isBlank() ? finalReply : replyDraft);

        Map<String, Object> body = buildResponse(id, newStatus, state);
        body.put("rounds", conversationService.messages(id).size());
        return ResponseEntity.ok(body);
    }

    /** reply 失败补偿:系统说明 + 转人工 + 审计,尽量不再抛出掩盖原始异常 */
    private void compensateReplyFailure(String ticketId, Exception cause) {
        try {
            roundService.completeRound(ticketId, TicketService.STATUS_ESCALATED,
                    "系统繁忙,您的诉求已记录,将转人工跟进。");
            humanTicketPool.add(ticketId, "AI 追问处理异常", "图执行异常: " + cause.getMessage());
        } catch (Exception compensationError) {
            log.error("追问失败补偿写入异常 ticketId={}", ticketId, compensationError);
        }
        try {
            auditLogger.log(ticketId, "reply-failed", "图执行异常:" + cause.getMessage());
        } catch (Exception auditError) {
            log.error("追问失败审计写入异常 ticketId={}", ticketId, auditError);
        }
    }

    /** 对话线程查询(租户隔离):按时间序返回本工单全部沟通记录 */
    @GetMapping("/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable String id, HttpServletRequest httpRequest) {
        return ticketService.getForCustomer(id, currentCustomer(httpRequest))
                .<ResponseEntity<?>>map(t -> {
                    List<Map<String, Object>> msgs = conversationService.messages(id).stream()
                            .map(m -> Map.<String, Object>of(
                                    "role", m.role(),
                                    "content", m.content(),
                                    "createdAt", m.createdAt().toString()))
                            .toList();
                    return ResponseEntity.ok(Map.of(
                            "ticketId", id,
                            "ticketStatus", t.status(),
                            "messages", msgs,
                            "count", msgs.size()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "工单不存在: " + id)));
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
