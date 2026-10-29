package com.dolphin.alert;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlertTaskMatcher} 单测:alertname 精确匹配、labels 子集匹配、
 * 上下文模板 {label} 占位符渲染(含缺失渲染为 ?)。
 */
class AlertTaskMatcherTest {

    private AlertProperties.Task task(String alertName, Map<String, String> labels, String template) {
        AlertProperties.Task t = new AlertProperties.Task();
        t.setName("任务-" + alertName);
        t.setAlertName(alertName);
        t.setLabels(labels);
        t.setContextTemplate(template);
        return t;
    }

    private AlertProperties props(AlertProperties.Task... tasks) {
        AlertProperties p = new AlertProperties();
        p.setTasks(List.of(tasks));
        return p;
    }

    private AlertmanagerPayload.AlertItem alert(String alertName, Map<String, String> labels) {
        return new AlertmanagerPayload.AlertItem("firing", "fp-1", "2026-01-01T00:00:00Z", "",
                labels, Map.of("summary", "摘要"));
    }

    @Test
    void matchesByAlertNameAndRendersLabelPlaceholders() {
        AlertProperties p = props(task("ModelHttp5xxRateHigh", Map.of(),
                "推理服务持续返回 5xx,uid={uid},model={model}。"));
        AlertTaskMatcher matcher = new AlertTaskMatcher(p);

        var matched = matcher.match(alert("ModelHttp5xxRateHigh",
                Map.of("alertname", "ModelHttp5xxRateHigh", "uid", "u-demo", "model", "qwen-plus")));

        assertTrue(matched.isPresent());
        assertEquals("任务-ModelHttp5xxRateHigh", matched.get().task().getName());
        assertEquals("推理服务持续返回 5xx,uid=u-demo,model=qwen-plus。", matched.get().context());
    }

    @Test
    void missingLabelPlaceholderRendersAsQuestionMark() {
        AlertProperties p = props(task("ModelThrottled", Map.of(), "限流:uid={uid},region={region}。"));
        AlertTaskMatcher matcher = new AlertTaskMatcher(p);

        var matched = matcher.match(alert("ModelThrottled",
                Map.of("alertname", "ModelThrottled", "uid", "u-1")));

        assertTrue(matched.isPresent());
        assertEquals("限流:uid=u-1,region=?。", matched.get().context());
    }

    @Test
    void labelSubsetRequiredWhenConfigured() {
        // task 要求 uid=u-2,告警 labels 是 uid=u-1 → 不匹配
        AlertProperties p = props(task("ModelThrottled", Map.of("uid", "u-2"), "ctx"));
        AlertTaskMatcher matcher = new AlertTaskMatcher(p);

        assertTrue(matcher.match(alert("ModelThrottled", Map.of("alertname", "ModelThrottled", "uid", "u-1")))
                .isEmpty());
        assertTrue(matcher.match(alert("ModelThrottled", Map.of("alertname", "ModelThrottled", "uid", "u-2")))
                .isPresent());
    }

    @Test
    void unknownAlertNameReturnsEmpty() {
        AlertProperties p = props(task("ModelHttp5xxRateHigh", Map.of(), "ctx"));
        AlertTaskMatcher matcher = new AlertTaskMatcher(p);
        assertTrue(matcher.match(alert("SomeOtherAlert", Map.of())).isEmpty());
    }

    @Test
    void firstMatchingTaskWins() {
        AlertProperties p = props(
                task("ModelThrottled", Map.of("severity", "warning"), "通用限流任务"),
                task("ModelThrottled", Map.of(), "兜底限流任务"));
        AlertTaskMatcher matcher = new AlertTaskMatcher(p);

        var matched = matcher.match(alert("ModelThrottled",
                Map.of("alertname", "ModelThrottled", "severity", "warning")));
        assertEquals("通用限流任务", matched.get().context());

        // severity 不满足第一条 → 落到第二条兜底
        var fallback = matcher.match(alert("ModelThrottled",
                Map.of("alertname", "ModelThrottled", "severity", "critical")));
        assertEquals("兜底限流任务", fallback.get().context());
    }
}
