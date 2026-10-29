package com.dolphin.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock 推理服务:故障模式决定状态码、计数维度、指标文本格式。
 */
class MockModelServiceTest {

    private MockModelService service;

    @BeforeEach
    void setUp() {
        service = new MockModelService();
    }

    @Test
    void 正常模式返回200并计数() {
        assertThat(service.invoke("u1", "qwen")).isEqualTo(200);
        assertThat(service.renderMetrics()).contains("model_requests_total{uid=\"u1\",model=\"qwen\",code=\"200\"} 1");
    }

    @Test
    void 注入5xx后全部返回500() {
        service.setFault("u1", "qwen", MockModelService.FaultMode.HTTP_5XX);
        assertThat(service.invoke("u1", "qwen")).isEqualTo(500);
        assertThat(service.invoke("u1", "qwen")).isEqualTo(500);
        assertThat(service.renderMetrics()).contains("code=\"500\"} 2");
    }

    @Test
    void 限流模式返回429且不影响其他uid() {
        service.setFault("u1", "qwen", MockModelService.FaultMode.THROTTLED);
        assertThat(service.invoke("u1", "qwen")).isEqualTo(429);
        assertThat(service.invoke("u2", "qwen")).isEqualTo(200);
    }

    @Test
    void 解除故障恢复NORMAL() {
        service.setFault("u1", "qwen", MockModelService.FaultMode.HTTP_5XX);
        service.setFault("u1", "qwen", MockModelService.FaultMode.NORMAL);
        assertThat(service.invoke("u1", "qwen")).isEqualTo(200);
        assertThat(service.faultStatus()).isEmpty();
    }

    @Test
    void burst批量调用返回分维度计数() {
        service.setFault("u1", "qwen", MockModelService.FaultMode.HTTP_5XX);
        Map<String, Long> counters = service.invokeBurst("u1", "qwen", 50);
        assertThat(counters).containsEntry("u1|qwen|500", 50L);
    }

    @Test
    void 指标输出符合Prometheus文本格式() {
        service.invoke("u1", "qwen");
        String metrics = service.renderMetrics();
        assertThat(metrics).startsWith("# HELP model_requests_total");
        assertThat(metrics).contains("# TYPE model_requests_total counter");
    }
}
