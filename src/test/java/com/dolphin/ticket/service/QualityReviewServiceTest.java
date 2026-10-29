package com.dolphin.ticket.service;

import com.dolphin.ticket.agent.QualityAgent;
import com.dolphin.ticket.infra.audit.AuditLogger;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QualityReviewService} 单测:同步 Executor 注入验证完整链路——
 * PASS 不动工单状态、REJECT 重开 ESCALATED、契约无效只留痕不重开、Agent 异常不上抛。
 */
class QualityReviewServiceTest {

    private final QualityAgent qualityAgent = mock(QualityAgent.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final Executor directExecutor = Runnable::run;

    private QualityReviewService service() {
        return new QualityReviewService(qualityAgent, ticketService, auditLogger, directExecutor);
    }

    @Test
    void passVerdictKeepsTicketClosed() {
        when(qualityAgent.review(anyString()))
                .thenReturn(new QualityAgent.Verdict(true, true, "合规完整。"));

        service().reviewAsync("T1", "终稿");

        verify(ticketService, never()).updateStatus(anyString(), anyString());
        verify(auditLogger).log(eq("T1"), eq("quality-review"), contains("PASS"));
    }

    @Test
    void rejectVerdictReopensTicketToEscalated() {
        when(qualityAgent.review(anyString()))
                .thenReturn(new QualityAgent.Verdict(false, true, "承诺了未经审批的重启。"));

        service().reviewAsync("T2", "终稿");

        verify(ticketService).updateStatus("T2", TicketService.STATUS_ESCALATED);
        verify(auditLogger).log(eq("T2"), eq("quality-review"), contains("REJECTED"));
    }

    @Test
    void invalidContractOnlyAuditsWithoutReopen() {
        when(qualityAgent.review(anyString()))
                .thenReturn(new QualityAgent.Verdict(false, false, "契约行缺失,输出不可信。"));

        service().reviewAsync("T3", "终稿");

        verify(ticketService, never()).updateStatus(anyString(), anyString());
        verify(auditLogger).log(eq("T3"), eq("quality-review"), contains("CONTRACT_INVALID"));
    }

    @Test
    void agentExceptionIsSwallowedAndLogged() {
        when(qualityAgent.review(anyString())).thenThrow(new RuntimeException("模型超时"));

        service().reviewAsync("T4", "终稿");

        verify(ticketService, never()).updateStatus(anyString(), anyString());
    }

    @Test
    void auditExceptionIsSwallowed() {
        when(qualityAgent.review(anyString()))
                .thenReturn(new QualityAgent.Verdict(true, true, "ok"));
        doThrow(new RuntimeException("审计写入失败")).when(auditLogger).log(anyString(), anyString(), anyString());

        service().reviewAsync("T5", "终稿");

        verify(ticketService, never()).updateStatus(anyString(), anyString());
    }
}
