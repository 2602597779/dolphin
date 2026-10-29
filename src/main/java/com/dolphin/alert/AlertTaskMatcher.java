package com.dolphin.alert;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * alert_task 匹配器:把一条告警匹配到 {@link AlertProperties.Task} 目录项。
 *
 * <p>规则(按配置顺序,首个命中即返回):
 * <ol>
 *   <li>labels.alertname 与 task.alertName 精确相等;</li>
 *   <li>task.labels 非空时,其中每个 key/value 都必须出现在告警 labels 中(子集匹配)。</li>
 * </ol>
 * 渲染上下文模板时,占位符 {@code {key}} 取自告警 labels,未命中的占位符渲染为 {@code ?}。
 */
@Component
public class AlertTaskMatcher {

    /** 渲染后的匹配结果:任务 + 上下文 */
    public record MatchedTask(AlertProperties.Task task, String context) {
    }

    private final AlertProperties properties;

    public AlertTaskMatcher(AlertProperties properties) {
        this.properties = properties;
    }

    /** 匹配告警;未命中返回 empty(调用方仅收告警不诊断) */
    public Optional<MatchedTask> match(AlertmanagerPayload.AlertItem alert) {
        Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
        for (AlertProperties.Task task : properties.getTasks()) {
            if (!task.getAlertName().equals(alert.alertName())) {
                continue;
            }
            if (!labelsMatch(task.getLabels(), labels)) {
                continue;
            }
            return Optional.of(new MatchedTask(task, renderContext(task.getContextTemplate(), labels)));
        }
        return Optional.empty();
    }

    /** task.labels 是告警 labels 的子集(全部命中) */
    static boolean labelsMatch(Map<String, String> expected, Map<String, String> actual) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            if (!actual.getOrDefault(e.getKey(), "").equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** 模板 {key} 占位符渲染;缺失的 key 渲染为 ?,便于在诊断报告里暴露配置与数据不一致 */
    static String renderContext(String template, Map<String, String> labels) {
        if (template == null || template.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                sb.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, open);
            String key = template.substring(open + 1, close);
            sb.append(labels.getOrDefault(key, "?"));
            i = close + 1;
        }
        return sb.toString();
    }
}
