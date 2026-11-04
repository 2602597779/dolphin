package com.dolphin.ticket.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 O8 SSE 发布器单测:用可记录调用的 SseEmitter 桩(外部构造注入)验证
 * 订阅/广播/终态完成/断连清理语义。
 */
class TicketEventPublisherTest {

    /**
     * 记录型 SseEmitter 桩:拦截 send(SseEventBuilder) 与 onCompletion,绕开真实 servlet 依赖。
     * Spring 6.2 的 SseEmitter.send(SseEventBuilder) 直连内部通道(不经 send(Object, MediaType)),
     * 故桩必须在 builder 入口拦截:展开 build() 的数据帧(event 名文本 + JSON 数据体)。
     * complete() 后驱动完成回调,模拟容器的异步完成链路。
     */
    static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>();
        final List<Runnable> onCompletionCallbacks = new ArrayList<>();
        boolean completed;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            onCompletionCallbacks.add(callback);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType frame : builder.build()) {
                sent.add(frame.getData());
            }
        }

        @Override
        public synchronized void complete() {
            completed = true;
            onCompletionCallbacks.forEach(Runnable::run);
        }
    }

    /** 发送必抛的 SseEmitter 桩:模拟客户端已断开 */
    static class BrokenEmitter extends RecordingEmitter {
        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client gone");
        }
    }

    private final TicketEventPublisher publisher = new TicketEventPublisher();

    @Test
    void publishWithoutSubscribersIsNoOp() {
        publisher.publishResult("T-1", Map.of("done", true));
        assertThat(publisher.subscriberCount("T-1")).isZero();
    }

    @Test
    void resultIsBroadcastWithEventNameAndTerminalCompletes() {
        RecordingEmitter first = new RecordingEmitter();
        RecordingEmitter second = new RecordingEmitter();
        publisher.subscribe("T-1", first);
        publisher.subscribe("T-1", second);
        assertThat(publisher.subscriberCount("T-1")).isEqualTo(2);

        // 本轮已有结论但非终态(PENDING_APPROVAL):广播后订阅保留,等待后续轮次
        Map<String, Object> pending = Map.of("ticketId", "T-1", "status", "PENDING_APPROVAL",
                "done", false, "pendingAction", "restartInstance:i-abc123");
        publisher.publishResult("T-1", pending);
        assertThat(publisher.subscriberCount("T-1")).isEqualTo(2);
        assertThat(first.completed).isFalse();

        // 终态:广播 result 事件并完成全部订阅
        Map<String, Object> terminal = Map.of("ticketId", "T-1", "status", "CLOSED", "done", true);
        publisher.publishResult("T-1", terminal);
        assertThat(first.completed).isTrue();
        assertThat(second.completed).isTrue();
        assertThat(publisher.subscriberCount("T-1")).isZero();

        // SseEmitter 事件帧:event 名 + "data:" 前缀文本、JSON 数据体都被送出
        assertThat(first.sent).contains("event:result\ndata:", terminal);
        assertThat(second.sent).contains("event:result\ndata:", pending, terminal);
    }

    @Test
    void brokenSubscriberIsDetachedAndHealthyOnesStillReceive() {
        RecordingEmitter broken = new BrokenEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        publisher.subscribe("T-2", broken);
        publisher.subscribe("T-2", healthy);
        assertThat(publisher.subscriberCount("T-2")).isEqualTo(2);

        Map<String, Object> view = Map.of("ticketId", "T-2", "status", "CLOSED", "done", true);
        publisher.publishResult("T-2", view);

        // 断开的订阅者被摘除(收不到数据、不被 complete),健康的订阅者照常收到事件
        assertThat(publisher.subscriberCount("T-2")).isZero();
        assertThat(healthy.sent).contains("event:result\ndata:", view);
        assertThat(healthy.completed).isTrue();
        assertThat(broken.sent).isEmpty();
        assertThat(broken.completed).isFalse();
    }

    @Test
    void completionCallbackAndExplicitDetachAreBothIdempotent() {
        RecordingEmitter emitter = new RecordingEmitter();
        publisher.subscribe("T-3", emitter);
        assertThat(publisher.subscriberCount("T-3")).isEqualTo(1);

        // 发布器显式摘除(publishResult 内 complete 后 unsubscribe)已生效
        publisher.publishResult("T-3", Map.of("done", true, "status", "ESCALATED"));
        assertThat(publisher.subscriberCount("T-3")).isZero();
        assertThat(emitter.completed).isTrue();

        // 重复驱动完成回调(容器与发布器双保险路径)不应异常:清理幂等
        emitter.onCompletionCallbacks.forEach(Runnable::run);
        assertThat(publisher.subscriberCount("T-3")).isZero();
    }
}
