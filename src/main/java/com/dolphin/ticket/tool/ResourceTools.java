package com.dolphin.ticket.tool;

import com.dolphin.ticket.infra.audit.AuditLogger;
import com.dolphin.ticket.service.CloudApi;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 云资源只读查询工具:实例状态、监控指标。
 * 只读操作,无需审批即可由诊断 Agent 调用。
 */
@Component
public class ResourceTools {

    private final CloudApi cloudApiService;
    private final AuditLogger auditLogger;

    public ResourceTools(CloudApi cloudApiService, AuditLogger auditLogger) {
        this.cloudApiService = cloudApiService;
        this.auditLogger = auditLogger;
    }

    /** 查询实例运行状态 */
    @Tool(name = "getInstanceStatus", description = "查询云服务器实例的运行状态与负载概况")
    public String getInstanceStatus(@ToolParam(description = "实例 ID,如 i-abc123") String instanceId) {
        // TODO: 真实云 API 调用需加超时/重试与租户隔离
        auditLogger.log("-", "getInstanceStatus", "instanceId=" + instanceId);
        return cloudApiService.getInstanceStatus(instanceId);
    }

    /** 查询实例监控指标 */
    @Tool(name = "getMetrics", description = "查询实例的监控指标(如 CPU、内存、磁盘、网络)近期数据")
    public String getMetrics(@ToolParam(description = "实例 ID") String instanceId,
                             @ToolParam(description = "指标名,如 cpu/memory/disk") String metricName) {
        // TODO: 支持时间范围参数与聚合粒度
        auditLogger.log("-", "getMetrics", "instanceId=" + instanceId + " metric=" + metricName);
        return cloudApiService.getMetrics(instanceId, metricName);
    }
}
