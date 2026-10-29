package com.dolphin.mock;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * mock 推理服务 API:调用模拟、故障注入、指标暴露。
 *
 * <p>演示剧本:设置故障 → 批量 invoke → Prometheus 抓取指标触发告警 →
 * Alertmanager 回调 /api/alert/webhook。
 */
@RestController
@RequestMapping("/mock")
public class MockModelController {

    private final MockModelService mockModelService;

    public MockModelController(MockModelService mockModelService) {
        this.mockModelService = mockModelService;
    }

    /** 模拟一次模型调用,返回对应故障状态码 */
    @PostMapping("/model/invoke")
    public ResponseEntity<Map<String, Object>> invoke(@RequestParam(defaultValue = "u-demo") String uid,
                                                      @RequestParam(defaultValue = "qwen-plus") String model) {
        int code = mockModelService.invoke(uid, model);
        Map<String, Object> body = Map.of("uid", uid, "model", model, "upstreamStatus", code);
        return ResponseEntity.status(code).body(body);
    }

    /** 批量模拟 N 次调用(制造告警所需流量) */
    @PostMapping("/model/burst")
    public Map<String, Object> burst(@RequestParam(defaultValue = "u-demo") String uid,
                                     @RequestParam(defaultValue = "qwen-plus") String model,
                                     @RequestParam(defaultValue = "100") int times) {
        return Map.of("counters", mockModelService.invokeBurst(uid, model, Math.min(times, 10000)));
    }

    /** 注入/解除故障:mode = NORMAL | HTTP_5XX | THROTTLED */
    @PostMapping("/fault/set")
    public Map<String, Object> setFault(@RequestParam(defaultValue = "u-demo") String uid,
                                       @RequestParam(defaultValue = "qwen-plus") String model,
                                       @RequestParam MockModelService.FaultMode mode) {
        mockModelService.setFault(uid, model, mode);
        return Map.of("uid", uid, "model", model, "mode", mode, "status", "ok");
    }

    /** 查看当前全部故障开关 */
    @GetMapping("/fault/status")
    public Map<String, MockModelService.FaultMode> faultStatus() {
        return mockModelService.faultStatus();
    }

    /** Prometheus 抓取端点(标准文本暴露格式) */
    @GetMapping(value = "/model/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/plain; version=0.0.4; charset=utf-8"))
                .body(mockModelService.renderMetrics());
    }
}
