package com.dolphin.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * SLA 时限配置({@code dolphin.sla.*}):各级别的响应/解决双计时器。
 *
 * <p>语义(与 README 剧本一致):
 * <ul>
 *   <li><b>响应时限</b>:工单创建后多久内必须产出客户可见回复;图流程同步运行,
 *       正常单提交即带回复,卡在 NEW/RUNNING(图中断/异常)视为未响应;</li>
 *   <li><b>解决时限</b>:工单创建后多久内必须 CLOSED(未闭环的 PENDING_APPROVAL/ESCALATED
 *       均在计时,督促审批与人工处理加速)。</li>
 * </ul>
 * 超时动作见 {@link SlaMonitor}(升级人工池 + breach 留痕 + 钉钉加急)。
 */
@ConfigurationProperties(prefix = "dolphin.sla")
public class SlaProperties {

    /** 各优先级的响应时限(未产出回复的未结单超此值 → 响应超时) */
    private Map<String, Duration> response;

    /** 各优先级的解决时限(未 CLOSED 超此值 → 解决超时) */
    private Map<String, Duration> resolve;

    public Map<String, Duration> getResponse() {
        return response;
    }

    public void setResponse(Map<String, Duration> response) {
        this.response = response;
    }

    public Map<String, Duration> getResolve() {
        return resolve;
    }

    public void setResolve(Map<String, Duration> resolve) {
        this.resolve = resolve;
    }

    /** 响应时限,级别未配置时取 P3 档(保守兜底) */
    public Duration responseOf(String priority) {
        return of(response, priority, Duration.ofHours(1));
    }

    /** 解决时限,级别未配置时取 P3 档(保守兜底) */
    public Duration resolveOf(String priority) {
        return of(resolve, priority, Duration.ofHours(8));
    }

    private static Duration of(Map<String, Duration> map, String priority, Duration fallback) {
        if (map == null || map.isEmpty()) {
            return fallback;
        }
        Duration d = map.get(priority);
        if (d == null) {
            d = map.get(SlaPriorityRules.P3);
        }
        return d == null ? fallback : d;
    }
}
