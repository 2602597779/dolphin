package com.dolphin.ticket.service;

import com.dolphin.ticket.agent.QualityAgent;
import com.dolphin.ticket.infra.audit.AuditLogger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步质检流水线:结单后审查终稿,不合格重开人工复核。
 *
 * <p>接线点在图的 followup 节点(结单动作完成后触发),独立单线程守护池异步执行——
 * 质检耗时/失败不影响结单主链路的响应;REJECT 时工单重开为 ESCALATED 交人工复核,
 * 契约无效(模型未按 VERDICT 行输出)只留痕不重开,质检解析故障不惩罚正常结单。
 */
@Component
public class QualityReviewService {

    private static final Logger log = LoggerFactory.getLogger(QualityReviewService.class);

    private final QualityAgent qualityAgent;
    private final TicketService ticketService;
    private final AuditLogger auditLogger;
    private final Executor executor;
    private final ExecutorService ownedPool;

    @Autowired
    public QualityReviewService(QualityAgent qualityAgent, TicketService ticketService, AuditLogger auditLogger) {
        this(qualityAgent, ticketService, auditLogger,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "ticket-quality");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** 测试构造器:注入同步 Executor(Runnable::run)即可同步验证完整链路 */
    QualityReviewService(QualityAgent qualityAgent, TicketService ticketService,
                         AuditLogger auditLogger, Executor executor) {
        this.qualityAgent = qualityAgent;
        this.ticketService = ticketService;
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

    /** 结单后调度异步质检(调用方不等待结果) */
    public void reviewAsync(String ticketId, String finalReply) {
        executor.execute(() -> run(ticketId, finalReply));
    }

    /** 完整质检链路:审查 → 结论分流(任何异常只留痕,不上抛) */
    void run(String ticketId, String finalReply) {
        try {
            QualityAgent.Verdict verdict = qualityAgent.review(finalReply);
            if (!verdict.contractValid()) {
                auditLogger.log(ticketId, "quality-review",
                        "CONTRACT_INVALID(不重开,仅留痕) opinion=" + verdict.opinion());
                return;
            }
            if (verdict.passed()) {
                auditLogger.log(ticketId, "quality-review",
                        "PASS opinion=" + verdict.opinion());
                return;
            }
            // 驳回:重开人工复核
            ticketService.updateStatus(ticketId, TicketService.STATUS_ESCALATED);
            auditLogger.log(ticketId, "quality-review",
                    "REJECTED → 已重开人工复核 opinion=" + verdict.opinion());
        } catch (Exception e) {
            log.error("[quality] 异步质检异常 ticketId={}", ticketId, e);
        }
    }
}
