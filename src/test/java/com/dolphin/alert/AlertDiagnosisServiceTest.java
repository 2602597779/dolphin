package com.dolphin.alert;

import com.dolphin.alert.repo.AlertDiagnosisRepository;
import com.dolphin.ticket.agent.DiagnosisAgent;
import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.rag.TicketRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AlertDiagnosisService} 单测:同步 Executor 注入验证完整链路——
 * 未命中任务不诊断、命中任务产出报告并通知、Agent 失败落 DIAGNOSIS_FAILED、
 * 通知失败不影响诊断结论落库、严重级别映射 urgency。
 */
@DataJpaTest
class AlertDiagnosisServiceTest {

    private final AlertTaskMatcher taskMatcher = mock(AlertTaskMatcher.class);
    private final DiagnosisAgent diagnosisAgent = mock(DiagnosisAgent.class);
    private final TicketRetriever retriever = mock(TicketRetriever.class);
    private final DingTalkNotifier notifier = mock(DingTalkNotifier.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final Executor directExecutor = Runnable::run;

    @Autowired
    private AlertDiagnosisRepository diagnosisRepository;

    private AlertDiagnosisStore store;

    @BeforeEach
    void setUpStore() {
        store = new AlertDiagnosisStore(diagnosisRepository);
    }

    private AlertDiagnosisService service() {
        return new AlertDiagnosisService(taskMatcher, diagnosisAgent, retriever,
                notifier, store, auditLogger, directExecutor);
    }

    private AlertmanagerPayload.AlertItem alert(String alertName, String severity) {
        return new AlertmanagerPayload.AlertItem("firing", "fp-1", "2026-01-01T00:00:00Z", "",
                Map.of("alertname", alertName, "uid", "u-demo", "model", "qwen-plus",
                        "severity", severity),
                Map.of("summary", "模型 5xx 错误率突增"));
    }

    private AlertTaskMatcher.MatchedTask matched(AlertProperties.Task task) {
        return new AlertTaskMatcher.MatchedTask(task, "推理服务持续返回 5xx,uid=u-demo,model=qwen-plus");
    }

    @Test
    void noMatchingTaskOnlyRecordsAndSkipsDiagnosis() {
        when(taskMatcher.match(any())).thenReturn(java.util.Optional.empty());

        assertFalse(service().schedule(alert("UnknownAlert", "critical")));

        assertEquals(1, store.total());
        assertEquals(AlertDiagnosisStore.OUTCOME_NO_TASK, store.recent(1).get(0).outcome());
        Mockito.verifyNoInteractions(diagnosisAgent, notifier);
    }

    @Test
    void matchedTaskProducesReportProposalAndNotifies() {
        AlertProperties.Task task = new AlertProperties.Task();
        task.setName("模型5xx高错误率");
        task.setProduct("模型推理服务");
        when(taskMatcher.match(any())).thenReturn(java.util.Optional.of(matched(task)));
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of("[来源:kb-1] 相关知识"));
        when(diagnosisAgent.diagnose(anyString(), any(), anyString())).thenReturn(
                new DiagnosisAgent.DiagnosisOutcome("【故障摘要】推理服务 5xx。",
                        com.dolphin.ticket.action.ActionProposal.parse("restartInstance:i-abc123"), false));
        when(notifier.isConfigured()).thenReturn(true);
        when(notifier.sendMarkdown(anyString(), anyString())).thenReturn(true);

        assertTrue(service().schedule(alert("ModelHttp5xxRateHigh", "critical")));

        AlertDiagnosisStore.DiagnosisRecord record = store.recent(1).get(0);
        assertEquals(AlertDiagnosisStore.OUTCOME_DIAGNOSED, record.outcome());
        assertEquals("模型5xx高错误率", record.taskName());
        assertTrue(record.report().contains("【故障摘要】"));
        assertTrue(record.proposal().contains("restartInstance:i-abc123"));
        assertTrue(record.notified());

        // 诊断上下文必须包含告警摘要与任务上下文;classification.product 来自任务配置
        Mockito.verify(diagnosisAgent).diagnose(
                anyString(), eq(Map.of("product", "模型推理服务", "type", "故障", "urgency", "高")), anyString());
        // 通知正文包含报告与人工决策标注
        Mockito.verify(notifier).sendMarkdown(anyString(), Mockito.contains("仅供人工决策"));
    }

    @Test
    void diagnosisFailureRecordsOutcomeWithoutThrowing() {
        AlertProperties.Task task = new AlertProperties.Task();
        task.setName("模型5xx高错误率");
        when(taskMatcher.match(any())).thenReturn(java.util.Optional.of(matched(task)));
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(diagnosisAgent.diagnose(anyString(), any(), anyString())).thenReturn(
                new DiagnosisAgent.DiagnosisOutcome("", null, true));

        assertTrue(service().schedule(alert("ModelHttp5xxRateHigh", "critical")));

        assertEquals(AlertDiagnosisStore.OUTCOME_FAILED, store.recent(1).get(0).outcome());
        Mockito.verifyNoInteractions(notifier);
    }

    @Test
    void notifyFailureDoesNotBreakDiagnosisRecord() {
        AlertProperties.Task task = new AlertProperties.Task();
        task.setName("模型限流");
        when(taskMatcher.match(any())).thenReturn(java.util.Optional.of(matched(task)));
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(diagnosisAgent.diagnose(anyString(), any(), anyString())).thenReturn(
                new DiagnosisAgent.DiagnosisOutcome("【故障摘要】限流。", null, false));
        when(notifier.isConfigured()).thenReturn(true);
        when(notifier.sendMarkdown(anyString(), anyString())).thenReturn(false);

        assertTrue(service().schedule(alert("ModelThrottled", "warning")));

        AlertDiagnosisStore.DiagnosisRecord record = store.recent(1).get(0);
        assertEquals(AlertDiagnosisStore.OUTCOME_DIAGNOSED, record.outcome());
        assertFalse(record.notified());
    }

    @Test
    void severityMapsToUrgency() {
        assertEquals("高", AlertDiagnosisService.urgencyOf(alert("A", "critical")));
        assertEquals("中", AlertDiagnosisService.urgencyOf(alert("A", "warning")));
        assertEquals("低", AlertDiagnosisService.urgencyOf(alert("A", "info")));
    }

    @Test
    void notifyTextMarksProposalAsHumanDecisionOnly() {
        AlertProperties.Task task = new AlertProperties.Task();
        task.setName("模型限流");
        DiagnosisAgent.DiagnosisOutcome outcome = new DiagnosisAgent.DiagnosisOutcome(
                "【故障摘要】限流。",
                com.dolphin.ticket.action.ActionProposal.parse("restartInstance:i-abc123"),
                false);
        String text = service().buildNotifyText(alert("ModelThrottled", "warning"), task, outcome,
                "restartInstance:i-abc123(置信度 0.9)");
        assertTrue(text.contains("仅供人工决策"));
        assertTrue(text.contains("restartInstance:i-abc123"));
        // proposalInvalid 时追加人工介入提示
        String invalidText = service().buildNotifyText(alert("ModelThrottled", "warning"), task,
                new DiagnosisAgent.DiagnosisOutcome("报告", null, true), "");
        assertTrue(invalidText.contains("人工介入"));
    }
}
