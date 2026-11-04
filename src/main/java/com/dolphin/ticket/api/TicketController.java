package com.dolphin.ticket.api;

import com.dolphin.ticket.auth.ApiKeyAuthFilter;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.memory.TicketCheckpointStore;
import com.dolphin.ticket.orchestration.state.TicketState;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.ConversationService;
import com.dolphin.ticket.service.TicketAsyncProperties;
import com.dolphin.ticket.service.TicketEventPublisher;
import com.dolphin.ticket.service.TicketGraphRunner;
import com.dolphin.ticket.service.TicketRoundService;
import com.dolphin.ticket.service.TicketService;
import com.dolphin.ticket.service.TicketStatusService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工单入口 API。
 *
 * <p><b>受理即 202(阶段 O8)</b>:submit/reply/approve 只做校验、受理闸门(条件状态翻转)
 * 与消息落库,图执行投递到独立线程池({@link TicketGraphRunner}),HTTP 线程立即返回 202;
 * 客户端经 {@code GET /{id}/status} 轮询或 {@code GET /{id}/events}(SSE)获取进度与结论。
 *
 * <p>受理互斥(同单串行,消息顺序保证):受理闸门是一条原子条件 UPDATE——
 * 仅当状态仍为受理时读到的值才翻转 PROCESSING,并发请求只有一个赢家(多实例安全);
 * 在途轮次未结束前,同一工单的新受理被拒(409),线程消息按整轮交替不穿插。
 *
 * <p>多轮对话(阶段4):reply 清旧断点后按最新上下文从头重跑(语义不变),
 * 对话历史注入图状态,线程经 messages 接口全程可追溯。
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

    private final TicketService ticketService;
    private final ApprovalService approvalService;
    private final ConversationService conversationService;
    private final TicketCheckpointStore checkpointStore;
    private final TicketRoundService roundService;
    private final AuditLogger auditLogger;
    private final TicketGraphRunner graphRunner;
    private final TicketStatusService statusService;
    private final TicketEventPublisher eventPublisher;
    private final TicketAsyncProperties asyncProperties;

    public TicketController(TicketService ticketService,
                            ApprovalService approvalService,
                            ConversationService conversationService,
                            TicketCheckpointStore checkpointStore,
                            TicketRoundService roundService,
                            AuditLogger auditLogger,
                            TicketGraphRunner graphRunner,
                            TicketStatusService statusService,
                            TicketEventPublisher eventPublisher,
                            TicketAsyncProperties asyncProperties) {
        this.ticketService = ticketService;
        this.approvalService = approvalService;
        this.conversationService = conversationService;
        this.checkpointStore = checkpointStore;
        this.roundService = roundService;
        this.auditLogger = auditLogger;
        this.graphRunner = graphRunner;
        this.statusService = statusService;
        this.eventPublisher = eventPublisher;
        this.asyncProperties = asyncProperties;
    }

    /**
     * 提交工单:受理即 202。创建工单 + 首条消息入线程 + 状态翻转为 PROCESSING,
     * 图执行异步进行;归属取自鉴权上下文(API-Key 对应客户)。
     */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody SubmitRequest request,
                                                       HttpServletRequest httpRequest) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message 不能为空"));
        }
        String customerId = currentCustomer(httpRequest);
        String ticketId = ticketService.create(request.message(), customerId);
        // 对话线程首条消息:后续多轮 reply 的上下文来源(阶段4)
        conversationService.appendUser(ticketId, request.message());
        ticketService.updateStatus(ticketId, TicketService.STATUS_PROCESSING);

        if (!graphRunner.enqueueSubmitRound(ticketId, request.message())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "系统繁忙,受理队列已满,工单已转人工跟进: " + ticketId));
        }
        return accepted(ticketId);
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

    /**
     * 轮询工单状态(阶段 O8):受理后客户端轮询本接口直到 done=true。
     * PENDING_APPROVAL 也算本轮已有结论(等待人工审批),CLOSED/ESCALATED 为终态。
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id, HttpServletRequest httpRequest) {
        return ticketService.getForCustomer(id, currentCustomer(httpRequest))
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(statusService.view(id)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "工单不存在: " + id)));
    }

    /**
     * 订阅工单事件(阶段 O8,SSE):连接即推当前状态快照(status 事件),
     * 本轮图执行完成后推 result 事件并在终态结束连接。
     * 租户隔离:非本客户的单 404,不泄露存在性。
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<SseEmitter> events(@PathVariable String id, HttpServletRequest httpRequest) {
        if (ticketService.getForCustomer(id, currentCustomer(httpRequest)).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        SseEmitter emitter = new SseEmitter(asyncProperties.getSseTimeout().toMillis());
        eventPublisher.subscribe(id, emitter);
        try {
            // 订阅即推快照:连接晚于完成事件的客户端也能立即拿到当前状态,不丢信息
            Map<String, Object> snapshot = statusService.view(id);
            emitter.send(SseEmitter.event().name("status").data(snapshot, MediaType.APPLICATION_JSON));
            if (Boolean.TRUE.equals(snapshot.get("done"))) {
                emitter.complete();
                // 立即完成也显式摘除:complete() 的 onCompletion 回调依赖容器异步链路驱动,
                // 不及时触发会泄漏订阅(与发布器内部摘除双保险,remove 幂等)
                eventPublisher.detach(id, emitter);
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /**
     * 人工审批:受理即 202。决策落库 → 状态翻转 PROCESSING → 异步 resume 续跑
     * (仅工单归属人可审批)。翻转失败=并发审批已受理,返回 409。
     */
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
        // 受理闸门先行:并发双审只有一个赢家(输家 409),决策只在赢家处落库,
        // 避免"后到的决策覆盖先受理方的审批意图"
        if (!ticketService.tryTransition(id, TicketService.STATUS_PENDING_APPROVAL,
                TicketService.STATUS_PROCESSING)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "工单状态已变化(可能已被并发受理),请刷新后重试"));
        }
        // 探针结论:approval 节点读不到 checkpoint 更新,决策必须先落 ApprovalService 再 resume;
        // 批准令牌由 approval 节点在 resume 链路中签发(绑定图状态里的权威待执行动作),此处只记录决策留痕
        approvalService.recordDecision(id, approved,
                request == null ? null : request.approver(),
                request == null ? null : request.reason());
        if (!graphRunner.enqueueApprovalResume(id, approved)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "系统繁忙,受理队列已满,工单已转人工跟进: " + id));
        }
        return accepted(id);
    }

    /**
     * 多轮追问(阶段4):基于工单历史上下文重新评估本轮诉求,受理即 202。
     *
     * <p>语义为"按最新上下文从头重跑":异步任务先清旧 checkpoint(旧断点停留在上一轮中断点,
     * 带着它 invoke 会直接 resume 而非重新评估),再把对话历史注入图状态 invoke。
     * PENDING_APPROVAL 单被追问时同样从头评估,客户可边沟通边补全背景。
     *
     * <p>状态约束:CLOSED 已结单不允许追问(409);ESCALATED 已转人工,只把追问记录进线程
     * 供专员查看,不再触发 AI 重跑;上一轮仍在处理中(PROCESSING)拒绝新追问(409),
     * 客户端应先轮询等待本轮完成。
     *
     * <p>阶段4优化(幂等/作废留痕)语义不变:可选 requestId 入口幂等;待审批单被追问时
     * 旧动作作废留痕(approval-invalidated);图执行异常由异步任务补偿(转人工 + 审计)。
     */
    @PostMapping("/{id}/reply")
    public ResponseEntity<Map<String, Object>> reply(@PathVariable String id,
                                                     @RequestBody ReplyRequest request,
                                                     HttpServletRequest httpRequest) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message 不能为空"));
        }
        String customerId = currentCustomer(httpRequest);
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
            body.put("rounds", conversationService.count(id));
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
        if (TicketService.STATUS_PROCESSING.equals(status)) {
            // 上一轮仍在途:拒绝新受理,保证同单串行与线程消息不穿插
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "工单正在处理上一轮请求,请先通过 GET /api/tickets/"
                            + id + "/status 等待本轮完成"));
        }

        // 受理闸门:条件状态翻转(原子)。失败=并发请求已抢先受理 → 409
        if (!ticketService.tryTransition(id, status, TicketService.STATUS_PROCESSING)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "工单状态已变化(可能已被并发受理),请刷新后重试"));
        }

        // O3:待审批单被追问会清掉旧断点,旧 PENDING_ACTION 随之失效;
        // 审批人手里的旧链接将无法再执行,必须在清断点前(受理线程内)把"原动作"写进审计,保证可追溯。
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

        // O4:取历史 + 落客户消息同一短事务(状态已由闸门翻转,不重复写);图执行在事务之外
        String history = roundService.beginRound(id, request.message(), request.requestId());
        if (!graphRunner.enqueueReplyRound(id, request.message(), history)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "系统繁忙,受理队列已满,工单已转人工跟进: " + id));
        }
        Map<String, Object> body = acceptedBody(id);
        body.put("rounds", conversationService.count(id));
        return ResponseEntity.accepted()
                .location(URI.create("/api/tickets/" + id + "/status"))
                .body(body);
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

    /** 202 受理响应:状态 + 轮询/SSE 地址 */
    private ResponseEntity<Map<String, Object>> accepted(String ticketId) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/tickets/" + ticketId + "/status"))
                .body(acceptedBody(ticketId));
    }

    private Map<String, Object> acceptedBody(String ticketId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("status", TicketService.STATUS_PROCESSING);
        body.put("accepted", true);
        body.put("poll", "/api/tickets/" + ticketId + "/status");
        body.put("events", "/api/tickets/" + ticketId + "/events");
        return body;
    }
}
