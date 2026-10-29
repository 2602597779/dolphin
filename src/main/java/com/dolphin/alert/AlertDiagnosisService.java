package com.dolphin.alert;

import com.dolphin.ticket.agent.DiagnosisAgent;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.rag.TicketRetriever;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 告警自动诊断服务:告警受理 → alert_task 匹配 → 直调诊断 Agent → 钉钉通知(可降级)。
 *
 * <p>设计决策(与 P0-1 并发债务解耦):<b>直调 {@link DiagnosisAgent},不走 ticketGraph</b>——
 * 单例 CompiledGraph 共享模板 state 存在跨请求串扰风险,告警回调天然与工单提交并发,
 * 直调无状态的诊断 Agent 既复用其 PROPOSAL 契约解析与单测覆盖,又完全不触碰图编排状态。
 *
 * <p>M2/M3 范围内诊断结论只通知人(动作建议标注"仅供人工决策"),不执行任何写操作;
 * 告警侧没有审批会话,自动执行安全约束不足,不越界。
 *
 * <p>诊断在独立单线程守护池中异步执行:webhook 立即返回受理结果,
 * LLM 调用慢/失败不阻塞 Alertmanager 回调,同时天然串行化避免诊断风暴。
 */
@Component
public class AlertDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(AlertDiagnosisService.class);

    private final AlertTaskMatcher taskMatcher;
    private final DiagnosisAgent diagnosisAgent;
    private final TicketRetriever ticketRetriever;
    private final DingTalkNotifier notifier;
    private final AlertDiagnosisStore store;
    private final AuditLogger auditLogger;
    private final Executor executor;
    /** 本服务自建并负责关闭的线程池;测试注入外部 Executor 时为 null */
    private final ExecutorService ownedPool;

    @Autowired
    public AlertDiagnosisService(AlertTaskMatcher taskMatcher,
                                 DiagnosisAgent diagnosisAgent,
                                 TicketRetriever ticketRetriever,
                                 DingTalkNotifier notifier,
                                 AlertDiagnosisStore store,
                                 AuditLogger auditLogger) {
        this(taskMatcher, diagnosisAgent, ticketRetriever, notifier, store, auditLogger,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "alert-diagnosis");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** 测试构造器:注入同步 Executor(Runnable::run)即可同步验证完整链路 */
    AlertDiagnosisService(AlertTaskMatcher taskMatcher,
                          DiagnosisAgent diagnosisAgent,
                          TicketRetriever ticketRetriever,
                          DingTalkNotifier notifier,
                          AlertDiagnosisStore store,
                          AuditLogger auditLogger,
                          Executor executor) {
        this.taskMatcher = taskMatcher;
        this.diagnosisAgent = diagnosisAgent;
        this.ticketRetriever = ticketRetriever;
        this.notifier = notifier;
        this.store = store;
        this.auditLogger = auditLogger;
        this.executor = executor;
        this.ownedPool = null;
    }

    @PreDestroy
    void shutdown() {
        if (ownedPool != null) {
            ownedPool.shutdownNow();
        }
    }

    /**
     * 尝试调度一次告警诊断。
     *
     * @return true=命中 alert_task 且已提交异步诊断;false=未命中/任务关闭诊断(仅收告警)
     */
    public boolean schedule(AlertmanagerPayload.AlertItem alert) {
        Optional<AlertTaskMatcher.MatchedTask> matched = taskMatcher.match(alert);
        if (matched.isEmpty()) {
            store.add(alert.fingerprint(), alert.alertName(), "",
                    AlertDiagnosisStore.OUTCOME_NO_TASK, "", "", false, "未命中 alert_task,不触发诊断");
            log.debug("[alert-diagnosis] 未命中 alert_task,仅受理 fingerprint={} alertname={}",
                    alert.fingerprint(), alert.alertName());
            return false;
        }
        AlertTaskMatcher.MatchedTask match = matched.get();
        if (!match.task().isDiagnoseEnabled()) {
            return false;
        }
        executor.execute(() -> run(match, alert));
        return true;
    }

    /** 完整诊断链路:组装上下文 → Agent 诊断 → 通知 → 落记录(任何异常只留痕,不上抛) */
    void run(AlertTaskMatcher.MatchedTask match, AlertmanagerPayload.AlertItem alert) {
        AlertProperties.Task task = match.task();
        try {
            String rawMessage = ("告警摘要:" + alert.summary()
                    + "\n告警任务:" + task.getName()
                    + "\n任务上下文:" + match.context()).strip();
            Map<String, Object> classification = Map.of(
                    "product", task.getProduct(),
                    "type", "故障",
                    "urgency", urgencyOf(alert));
            List<String> similar = retrieveSafe(alert.summary());
            DiagnosisAgent.DiagnosisOutcome outcome =
                    diagnosisAgent.diagnose(rawMessage, classification, String.join("\n", similar));

            if (outcome.report() == null || outcome.report().isBlank()) {
                store.add(alert.fingerprint(), alert.alertName(), task.getName(),
                        AlertDiagnosisStore.OUTCOME_FAILED, "", "", false,
                        "诊断 Agent 未产出报告(见服务端 WARN 日志)");
                auditLogger.log(alert.fingerprint(), "alert-diagnosis", "outcome=FAILED task=" + task.getName());
                return;
            }

            String proposalText = outcome.proposal() == null ? ""
                    : outcome.proposal().toStateString() + "(置信度 " + outcome.proposal().confidence() + ")";
            String notifyText = buildNotifyText(alert, task, outcome, proposalText);
            boolean sent = notifier.sendMarkdown("Dolphin 告警诊断:" + alert.alertName(), notifyText);
            String notifyDetail = notifier.isConfigured()
                    ? (sent ? "钉钉接收成功" : "钉钉发送失败(见服务端 WARN)")
                    : "未配置钉钉 webhook,降级日志";

            store.add(alert.fingerprint(), alert.alertName(), task.getName(),
                    AlertDiagnosisStore.OUTCOME_DIAGNOSED, outcome.report(), proposalText, sent, notifyDetail);
            auditLogger.log(alert.fingerprint(), "alert-diagnosis",
                    "outcome=DIAGNOSED task=%s proposal=%s notified=%s".formatted(
                            task.getName(),
                            outcome.proposal() == null ? "NONE" : outcome.proposal().toStateString(),
                            sent));
        } catch (Exception e) {
            log.error("[alert-diagnosis] 诊断链路异常 fingerprint={}", alert.fingerprint(), e);
            store.add(alert.fingerprint(), alert.alertName(), task.getName(),
                    AlertDiagnosisStore.OUTCOME_FAILED, "", "", false, "诊断链路异常:" + e.getMessage());
        }
    }

    /** 组装钉钉 markdown 正文(动作建议显式标注仅供人工决策) */
    String buildNotifyText(AlertmanagerPayload.AlertItem alert, AlertProperties.Task task,
                           DiagnosisAgent.DiagnosisOutcome outcome, String proposalText) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Dolphin 告警自动诊断\n");
        sb.append("- 告警:%s(%s)\n".formatted(alert.alertName(), alert.severity()));
        sb.append("- 指纹:%s\n".formatted(alert.fingerprint()));
        sb.append("- 匹配任务:%s\n".formatted(task.getName()));
        sb.append("\n**诊断报告**\n\n").append(outcome.report()).append("\n");
        if (!proposalText.isBlank()) {
            sb.append("\n**待执行动作建议**(仅供人工决策,系统不会自动执行)\n\n")
                    .append(proposalText).append("\n");
        }
        if (outcome.proposalInvalid()) {
            sb.append("\n> 注意:诊断动作建议不可信(契约缺失/低置信度),请人工介入核实。\n");
        }
        return sb.toString();
    }

    /** severity → urgency:critical→高,warning→中,其余→低 */
    static String urgencyOf(AlertmanagerPayload.AlertItem alert) {
        return switch (alert.severity()) {
            case "critical" -> "高";
            case "warning" -> "中";
            default -> "低";
        };
    }

    /** 相似知识检索:Embedding 不可用时降级为空列表,不阻断诊断(与 RagQaHandler 同策略) */
    private List<String> retrieveSafe(String query) {
        try {
            return ticketRetriever.retrieve(query, 3);
        } catch (Exception e) {
            log.debug("[alert-diagnosis] 相似知识检索降级为空: {}", e.getMessage());
            return List.of();
        }
    }
}
