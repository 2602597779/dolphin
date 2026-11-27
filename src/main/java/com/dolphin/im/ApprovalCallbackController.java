package com.dolphin.im;

import com.dolphin.im.repo.ImCallbackRecordEntity;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.ApprovalService;
import com.dolphin.ticket.service.TicketGraphRunner;
import com.dolphin.ticket.service.TicketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * IM 审批回调入口(平台无关):把"平台私有回调"翻译成一条标准审批指令,再走既有审批三段式。
 *
 * <p>分层:本控制器只负责<b>编排</b>——路由适配器 → 验签 → 幂等 → 状态闸门 → 落决策 → 续跑图 →
 * 回平台应答;平台私有的签名/加解密/报文解析全部封在 {@link ImCallbackAdapter} 里
 * (钉钉参考实现见 {@link DingTalkCallbackAdapter})。换平台只需新增一个适配器 Bean,本类零改动。
 *
 * <p>路径 {@code /api/im/callback/{platform}} 不在 {@code ApiKeyAuthFilter} 的拦截前缀内:
 * 回调的信任边界是<b>平台签名</b>(而非 API Key),验签不通过一律 400,不触碰任何工单状态。
 *
 * <p>幂等语义(平台重投是常态):同一 {@code callbackId} 只处理一次,重投直接回放首次结论并回
 * 200(平台才会停止重投)。并发下的真正防重执行仍由工单状态闸门
 * {@link TicketService#tryTransition} 裁决——两张网各有分工:去重表负责"重投不重复执行 + 留痕",
 * 状态闸门负责"并发只有一方能接单"。
 *
 * <p>应答体保持平台私有格式({@link ImCallbackAdapter#ack},钉钉为加密 JSON),
 * 故本次处理结论另用响应头 {@value #RESULT_HEADER} 暴露,便于联调与观测。
 */
@RestController
public class ApprovalCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalCallbackController.class);

    /** 回调路径模板(平台标识由适配器 {@link ImCallbackAdapter#platform()} 声明) */
    public static final String PATH = "/api/im/callback/{platform}";

    /** 应答头:平台无关的处理结论(ACCEPTED / REJECTED / IGNORED / REPLAYED) */
    public static final String RESULT_HEADER = "X-Dolphin-Callback-Result";

    /** 重投回放:留痕里存的是首次结论,故应答头单独标记"本次未执行、只回放" */
    public static final String RESULT_REPLAYED = "REPLAYED";

    /** 审计动作名 */
    public static final String AUDIT_ACTION = "im-approval-callback";

    private final Map<String, ImCallbackAdapter> adapters;
    private final ImCallbackDedupService dedupService;
    private final TicketService ticketService;
    private final ApprovalService approvalService;
    private final TicketGraphRunner graphRunner;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public ApprovalCallbackController(List<ImCallbackAdapter> adapters,
                                      ImCallbackDedupService dedupService,
                                      TicketService ticketService,
                                      ApprovalService approvalService,
                                      TicketGraphRunner graphRunner,
                                      AuditLogger auditLogger,
                                      ObjectMapper objectMapper) {
        this.adapters = index(adapters);
        this.dedupService = dedupService;
        this.ticketService = ticketService;
        this.approvalService = approvalService;
        this.graphRunner = graphRunner;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    /** 按平台标识建索引;同平台重复注册属配置错误,保留先注册者并留 error 日志(不因此拒绝启动) */
    private static Map<String, ImCallbackAdapter> index(List<ImCallbackAdapter> adapters) {
        Map<String, ImCallbackAdapter> byPlatform = new LinkedHashMap<>();
        for (ImCallbackAdapter adapter : adapters) {
            String platform = normalize(adapter.platform());
            ImCallbackAdapter existing = byPlatform.putIfAbsent(platform, adapter);
            if (existing != null) {
                log.error("[im-callback] 平台 {} 注册了多个适配器,保留 {} 忽略 {}",
                        platform, existing.getClass().getSimpleName(), adapter.getClass().getSimpleName());
            }
        }
        return Map.copyOf(byPlatform);
    }

    /**
     * 接收平台审批回调。
     *
     * @param platform 平台标识(如 dingtalk)
     * @param rawBody  原始请求体——必须先于任何反序列化验签,故按 String 接收
     * @param request  原始请求(用于合并"请求头 + 查询参数":钉钉把验签三要素放在 query 上)
     */
    @PostMapping(PATH)
    public ResponseEntity<String> callback(@PathVariable String platform,
                                           @RequestBody(required = false) String rawBody,
                                           HttpServletRequest request) {
        ImCallbackAdapter adapter = adapters.get(normalize(platform));
        if (adapter == null) {
            return error(HttpStatus.NOT_FOUND, "未知回调平台:" + platform);
        }

        Map<String, String> view = requestView(request);
        String body = rawBody == null ? "" : rawBody;

        ImCallbackAdapter.ParseResult parsed = adapter.parse(view, body);
        if (!parsed.accepted()) {
            // 验签失败/凭证未配置:入口关闭(未配 = 配错,同一失败路径),不触碰任何工单状态
            log.warn("[im-callback] 拒绝回调 platform={} reason={}", adapter.platform(), parsed.rejectReason());
            return error(HttpStatus.BAD_REQUEST, parsed.rejectReason());
        }
        StandardApprovalCommand command = parsed.command();

        // 1) 幂等:重投回放首次结论,不重复驱动状态机
        Optional<ImCallbackRecordEntity> handled = dedupService.find(command.platform(), command.callbackId());
        if (handled.isPresent()) {
            ImCallbackRecordEntity record = handled.get();
            log.info("[im-callback] 重投回放 platform={} callbackId={} ticketId={} result={}",
                    command.platform(), command.callbackId(), record.getTicketId(), record.getResult());
            return ack(adapter, view, body, RESULT_REPLAYED);
        }

        // 2) 状态闸门:只有待审批工单能被接单;工单不存在/已流转 → 忽略(应答成功,避免平台无限重投)
        if (!ticketService.tryTransition(command.ticketId(),
                TicketService.STATUS_PENDING_APPROVAL, TicketService.STATUS_PROCESSING)) {
            dedupService.remember(command.platform(), command.callbackId(), command.ticketId(),
                    ImCallbackRecordEntity.RESULT_IGNORED, "工单不在待审批状态或不存在");
            auditLogger.log(command.ticketId(), AUDIT_ACTION,
                    "ignored:" + command.platform() + " status≠PENDING_APPROVAL");
            log.info("[im-callback] 忽略回调 ticketId={} callbackId={} 工单不在待审批状态",
                    command.ticketId(), command.callbackId());
            return ack(adapter, view, body, ImCallbackRecordEntity.RESULT_IGNORED);
        }

        // 3) 审批三段式(与 REST 入口 TicketController.approve、会话入口 TicketFlowHandler 同款):
        //    探针结论——approval 节点读不到 checkpoint 更新,决策必须先落 ApprovalService 再 resume
        approvalService.recordDecision(command.ticketId(), command.approved(),
                command.approver(), command.reason());
        auditLogger.log(command.ticketId(), AUDIT_ACTION,
                (command.approved() ? "approved:" : "rejected:") + command.approver()
                        + " via " + command.platform());

        if (!graphRunner.enqueueApprovalResume(command.ticketId(), command.approved())) {
            // 背压:不登记幂等,允许平台重投;工单已在 PROCESSING,重投会走状态闸门 → IGNORED(不重复执行)
            log.warn("[im-callback] 审批续跑队列繁忙 ticketId={} callbackId={}",
                    command.ticketId(), command.callbackId());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "审批续跑队列繁忙,请重试");
        }

        String result = command.approved()
                ? ImCallbackRecordEntity.RESULT_ACCEPTED : ImCallbackRecordEntity.RESULT_REJECTED;
        dedupService.remember(command.platform(), command.callbackId(), command.ticketId(), result,
                command.approver() + (command.reason() == null || command.reason().isBlank()
                        ? "" : " / " + command.reason()));
        return ack(adapter, view, body, result);
    }

    /** 平台约定的成功应答(钉钉为加密 JSON);结论另由 {@value #RESULT_HEADER} 头暴露 */
    private ResponseEntity<String> ack(ImCallbackAdapter adapter, Map<String, String> view,
                                       String rawBody, String result) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(RESULT_HEADER, result)
                .body(adapter.ack(view, rawBody));
    }

    private ResponseEntity<String> error(HttpStatus status, String message) {
        String body;
        try {
            body = objectMapper.writeValueAsString(
                    Map.of("error", message == null || message.isBlank() ? "回调被拒绝" : message));
        } catch (JsonProcessingException e) {
            log.warn("[im-callback] 错误应答序列化失败 err={}", e.toString());
            body = "{\"error\":\"callback rejected\"}";
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** 合并"请求头(键统一小写)+ 查询参数"视图:同名时以请求头为准,保证适配器只有一份取值口径 */
    static Map<String, String> requestView(HttpServletRequest request) {
        Map<String, String> view = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name != null) {
                view.put(normalize(name), request.getHeader(name));
            }
        }
        request.getParameterMap().forEach((name, values) -> {
            if (name != null && values != null && values.length > 0) {
                view.putIfAbsent(normalize(name), values[0]);
            }
        });
        return view;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
