package com.dolphin.web;

import com.dolphin.capability.CapabilityHandler;
import com.dolphin.capability.CapabilityRegistry;
import com.dolphin.router.DialogContext;
import com.dolphin.router.IntentRouter;
import com.dolphin.router.RouteDecision;
import com.dolphin.router.SessionStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一对话入口:消息 → 意图路由 → 能力注册表分发 → 对应 Handler。
 *
 * <p>路由可观测:响应携带 capability 与 matchedBy,便于调试"这句话为什么去了那个分支"。
 */
@RestController
@RequestMapping("/api/dolphin")
public class ChatController {

    private final IntentRouter intentRouter;
    private final CapabilityRegistry capabilityRegistry;
    private final SessionStore sessionStore;

    public ChatController(IntentRouter intentRouter,
                          CapabilityRegistry capabilityRegistry,
                          SessionStore sessionStore) {
        this.intentRouter = intentRouter;
        this.capabilityRegistry = capabilityRegistry;
        this.sessionStore = sessionStore;
    }

    /**
     * @param sessionId 会话号(缺省 default;驱动路由粘性)
     * @param message   用户消息
     */
    public record ChatRequest(String sessionId, String message) {
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        DialogContext ctx = sessionStore.getOrCreate(
                request.sessionId() == null || request.sessionId().isBlank() ? "default" : request.sessionId());

        RouteDecision decision = intentRouter.route(message, ctx);
        CapabilityHandler handler = capabilityRegistry.get(decision.capabilityCode());
        // 粘性由流程型能力自行管理(如工单等待审批时保持、结单后清除),入口不越权设置

        String reply = handler.handle(message, ctx);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", reply);
        body.put("capability", decision.capabilityCode());
        body.put("matchedBy", decision.matchedBy());
        body.put("confidence", decision.confidence());
        return body;
    }
}
