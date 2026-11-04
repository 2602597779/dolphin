package com.dolphin.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工单 SSE 事件发布器(阶段 O8)。
 *
 * <p>按工单号维护订阅者(emitter)列表:图执行完成后广播 {@code result} 事件并
 * 在终态时结束全部订阅。订阅即推快照由 Controller 完成(连接晚于完成事件的客户端
 * 也能立即拿到当前状态,不丢信息)。
 *
 * <p>生命周期清理:complete/timeout/error 回调统一摘除 emitter,客户端断开
 * 不随时间堆积;工单无订阅者时广播为空操作。
 */
@Component
public class TicketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TicketEventPublisher.class);

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** 订阅工单事件:注册 emitter 的生命周期清理(complete/timeout/error 统一摘除) */
    public void subscribe(String ticketId, SseEmitter emitter) {
        subscribers.computeIfAbsent(ticketId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable detach = () -> unsubscribe(ticketId, emitter);
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(e -> detach.run());
    }

    /**
     * 广播本轮完成事件;视图标记 done(终态)时顺带结束该工单全部订阅。
     * 单个 emitter 发送失败(客户端已断开)只摘除该订阅,不影响其他订阅者。
     */
    public void publishResult(String ticketId, Map<String, Object> view) {
        List<SseEmitter> emitters = subscribers.get(ticketId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        boolean terminal = Boolean.TRUE.equals(view.get("done"));
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event()
                        .name("result")
                        .data(view, MediaType.APPLICATION_JSON));
                if (terminal) {
                    emitter.complete();
                    // complete() 的 onCompletion 回调由 servlet 异步链路触发;
                    // 此处显式摘除保证清理不依赖回调(list.remove 幂等,双摘除安全)
                    unsubscribe(ticketId, emitter);
                }
            } catch (Exception e) {
                log.debug("[sse] 推送失败,摘除订阅 ticketId={}: {}", ticketId, e.getMessage());
                unsubscribe(ticketId, emitter);
            }
        }
    }

    /** 当前挂起的工单订阅数(观测/测试用) */
    public int subscriberCount(String ticketId) {
        List<SseEmitter> emitters = subscribers.get(ticketId);
        return emitters == null ? 0 : emitters.size();
    }

    /**
     * 手动完成订阅时摘除:complete() 的 onCompletion 回调依赖容器异步链路驱动,
     * 订阅方(如 Controller 对已完成工单立即 complete)需显式摘除保证不泄漏
     * (list.remove 幂等,与生命周期回调双摘除安全)。
     */
    public void detach(String ticketId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(ticketId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    private void unsubscribe(String ticketId, SseEmitter emitter) {
        detach(ticketId, emitter);
    }
}
