package com.dolphin.mock;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * mock 大模型推理服务:演示告警场景的"被监控对象"。
 *
 * <p>能力:
 * <ul>
 *   <li>按 uid+model 维度注入故障模式(正常/5xx/限流429),模拟线上异常;</li>
 *   <li>请求计数器按 (uid, model, code) 累计,暴露 Prometheus 文本格式指标;</li>
 *   <li>Prometheus 抓取 model_requests_total 后用 rate() 评估 5xx/限流告警规则。</li>
 * </ul>
 *
 * <p>骨架说明:手写指标输出(AtomicLong 计数 + 文本渲染),不引 micrometer 注册器,
 * 便于理解 Prometheus 抓取协议;真实项目应使用 micrometer-registry-prometheus。
 */
@Service
public class MockModelService {

    /** 故障模式 */
    public enum FaultMode {
        /** 正常放行 */
        NORMAL,
        /** 全部返回 500(模拟服务端错误突增) */
        HTTP_5XX,
        /** 全部返回 429(模拟触发限流) */
        THROTTLED
    }

    /** uid|model → 故障模式开关 */
    private final ConcurrentHashMap<String, FaultMode> faultSwitches = new ConcurrentHashMap<>();

    /** uid|model|code → 请求计数 */
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    /**
     * 模拟一次模型推理调用。
     *
     * @return HTTP 状态码(200/500/429)
     */
    public int invoke(String uid, String model) {
        FaultMode mode = faultSwitches.getOrDefault(key(uid, model), FaultMode.NORMAL);
        int code = switch (mode) {
            case HTTP_5XX -> 500;
            case THROTTLED -> 429;
            case NORMAL -> 200;
        };
        counters.computeIfAbsent(key(uid, model, String.valueOf(code)), k -> new LongAdder()).increment();
        return code;
    }

    /** 设置/解除某 uid+model 的故障模式 */
    public void setFault(String uid, String model, FaultMode mode) {
        if (mode == null || mode == FaultMode.NORMAL) {
            faultSwitches.remove(key(uid, model));
        } else {
            faultSwitches.put(key(uid, model), mode);
        }
    }

    /** 当前全部故障开关(观测用) */
    public Map<String, FaultMode> faultStatus() {
        return Map.copyOf(faultSwitches);
    }

    /** 渲染 Prometheus 文本暴露格式(text/plain; version=0.0.4) */
    public String renderMetrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP model_requests_total mock 模型服务请求总数(按 uid/model/code 维度)\n")
                .append("# TYPE model_requests_total counter\n");
        counters.forEach((k, adder) -> {
            String[] parts = k.split("\\|", 3);
            sb.append("model_requests_total{uid=\"").append(parts[0])
                    .append("\",model=\"").append(parts[1])
                    .append("\",code=\"").append(parts[2])
                    .append("\"} ").append(adder.sum()).append('\n');
        });
        return sb.toString();
    }

    private static String key(String uid, String model) {
        return uid + "|" + model;
    }

    private static String key(String uid, String model, String code) {
        return uid + "|" + model + "|" + code;
    }

    /** 供测试与演示:重置全部计数与开关 */
    public void reset() {
        counters.clear();
        faultSwitches.clear();
    }

    /** 便捷方法:批量模拟 N 次请求(演示脚本用) */
    public Map<String, Long> invokeBurst(String uid, String model, int times) {
        for (int i = 0; i < times; i++) {
            invoke(uid, model);
        }
        return counters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(uid + "|" + model + "|"))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
    }
}
