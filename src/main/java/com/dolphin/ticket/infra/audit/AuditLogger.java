package com.dolphin.ticket.infra.audit;

import com.dolphin.cloud.CloudApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计留痕:所有工单动作(查详情、查监控、执行写操作等)统一在此打结构化日志。
 * 骨架阶段只落 log.info,TODO:将来接审计库/消息队列做持久化与合规查询。
 *
 * <p>每条审计行必须携带 {@code provider}(当前生效的数据源 sim|http):数据源可插拔后,
 * 同一动作可能打向进程内模拟器或真实云 API——审计/复盘时如果分不清"这次重启是真是假",
 * HITL 场景下代价极高。provider 在启动装配期确定,运行期不变。
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final CloudApiProperties cloudProperties;

    public AuditLogger(CloudApiProperties cloudProperties) {
        this.cloudProperties = cloudProperties;
    }

    /**
     * 记录一次操作留痕。
     *
     * @param ticketId     工单号
     * @param action       动作名(如 queryTicketDetail / restartInstance)
     * @param paramsSummary 参数摘要(严禁包含密钥等敏感信息)
     */
    public void log(String ticketId, String action, String paramsSummary) {
        log.info("[AUDIT] provider={} ticketId={} action={} params={}",
                cloudProperties.getProvider(), ticketId, action, paramsSummary);
    }
}
