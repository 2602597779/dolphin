package com.dolphin.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 告警链路配置({@code dolphin.alert.*})。
 *
 * <p>两部分:
 * <ul>
 *   <li>{@code tasks}:alert_task 目录——哪类告警(name + labels 匹配)自动触发 Agent 诊断,
 *       上下文模板里的 {@code {label}} 占位符由告警 labels 渲染;</li>
 *   <li>{@code dingtalk}:钉钉群机器人 webhook;URL 未配置时通知降级为审计日志,
 *       保证 offline 演示与单测不依赖外部环境。</li>
 * </ul>
 *
 * <p>安全约定:webhook URL 与加签 secret 只从环境变量注入({@code DOLPHIN_DINGTALK_*}),
 * 禁止写死在配置文件。
 */
@ConfigurationProperties(prefix = "dolphin.alert")
public class AlertProperties {

    private final DingTalk dingtalk = new DingTalk();
    private List<Task> tasks = new ArrayList<>();

    /** 钉钉群机器人配置 */
    public static class DingTalk {
        /** 自定义机器人 webhook 地址(含 access_token);空=降级为日志通知 */
        private String webhookUrl = "";
        /** 加签密钥(机器人安全设置选"加签"时必填);空=不加签 */
        private String secret = "";
        /** 通知 HTTP 读超时 */
        private Duration timeout = Duration.ofSeconds(5);

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** 一条 alert_task:匹配规则 + 诊断上下文模板 */
    public static class Task {
        /** 任务名(审计与通知里的人类可读标识) */
        private String name = "";
        /** 匹配的告警规则名(labels.alertname 精确相等) */
        private String alertName = "";
        /** 可选:附加 label 精确匹配(全部命中才算匹配) */
        private Map<String, String> labels = Map.of();
        /** 诊断上下文模板,{key} 占位符由告警 labels 渲染 */
        private String contextTemplate = "";
        /** 告警涉及产品(传入诊断 Agent 的 classification.product) */
        private String product = "云服务";
        /** 是否自动触发 Agent 诊断(false=仅收告警不诊断) */
        private boolean diagnoseEnabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAlertName() {
            return alertName;
        }

        public void setAlertName(String alertName) {
            this.alertName = alertName;
        }

        public Map<String, String> getLabels() {
            return labels;
        }

        public void setLabels(Map<String, String> labels) {
            this.labels = labels;
        }

        public String getContextTemplate() {
            return contextTemplate;
        }

        public void setContextTemplate(String contextTemplate) {
            this.contextTemplate = contextTemplate;
        }

        public String getProduct() {
            return product;
        }

        public void setProduct(String product) {
            this.product = product;
        }

        public boolean isDiagnoseEnabled() {
            return diagnoseEnabled;
        }

        public void setDiagnoseEnabled(boolean diagnoseEnabled) {
            this.diagnoseEnabled = diagnoseEnabled;
        }
    }

    public DingTalk getDingtalk() {
        return dingtalk;
    }

    public List<Task> getTasks() {
        return tasks;
    }

    public void setTasks(List<Task> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
    }
}
