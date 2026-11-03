package com.dolphin.ticket.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时向向量库灌入种子知识(演示用,均为自造样例,不含任何真实客户/内部数据)。
 *
 * <p>Embedding 依赖远端模型服务,若 API Key 未配置或端点不支持 embeddings,
 * 仅告警跳过,不阻塞应用启动(路由框架其余功能仍可离线验证)。
 */
@Component
public class KnowledgeSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    private final DocIngestService docIngestService;
    private final VectorStoreFilePersistence persistence;

    public KnowledgeSeeder(DocIngestService docIngestService, VectorStoreFilePersistence persistence) {
        this.docIngestService = docIngestService;
        this.persistence = persistence;
    }

    @Override
    public void run(String... args) {
        // 快照已存在(重启场景):load 已恢复全部种子文档与 embedding,跳过灌入避免重复调用 Embedding
        if (persistence.exists()) {
            log.info("[rag] 向量库快照已存在,跳过种子灌入(dolphin.rag.vector-store-path)");
            return;
        }
        try {
            SEED_DOCS.forEach(docIngestService::ingest);
            log.info("[rag] 种子知识灌入完成,共 {} 篇", SEED_DOCS.size());
        } catch (Exception e) {
            log.warn("[rag] 种子知识灌入失败(检查 Embedding 端点与 DOLPHIN_API_KEY),RAG 检索将返回空: {}",
                    e.getMessage());
        }
    }

    /** 知识库 + 历史工单样例 */
    private static final Map<String, String> SEED_DOCS = Map.ofEntries(
            Map.entry("kb-ecs-reset-password", """
                    ECS 实例重置密码流程:登录控制台进入实例列表,选择目标实例,
                    点击"更多-密码/密钥对-重置实例密码",输入新密码后需重启实例才能生效。
                    若实例安装了云助手 Agent 且状态正常,部分场景支持免重启重置。"""),
            Map.entry("kb-rds-backup", """
                    RDS 备份策略:默认开启自动备份,保留周期 7~730 天可配置。
                    全量备份在业务低峰期执行,日志备份(-binlog)支持恢复到任意时间点(PITR)。
                    跨地域备份需单独开启并会产生额外存储费用。"""),
            Map.entry("kb-oss-open", """
                    对象存储 OSS 开通步骤:控制台开通服务后创建 Bucket,选择地域与读写权限。
                    上传大文件建议分片上传(>100MB),跨地域访问需评估带宽与传输加速开关。"""),
            Map.entry("ticket-10001", """
                    历史工单#10001:ECS 实例 CPU 持续打满,根因定位为凌晨定时任务风暴
                    (crontab 配置错误导致任务堆积)。处理:kill 堆积进程并修正 crontab,
                    后续建议接入云监控 CPU 告警。"""),
            Map.entry("ticket-10002", """
                    历史工单#10002:RDS 连接池耗尽导致业务超时,根因为应用侧连接泄漏
                    (未归还连接)。处理:重启应用并调大 max_connections,建议升级连接池版本修复泄漏。"""),
            Map.entry("ticket-10003", """
                    历史工单#10003:模型推理服务返回 5xx 突增,排查发现单 UID 流量激增
                    触发后端实例 OOM。处理:对该 UID 限流并扩容实例,建议按 UID 维度配置配额告警。"""),
            Map.entry("kb-throttle-quota", """
                    限流与配额说明:API 默认按 UID+模型 维度限流(QPS 与 TPM 双阈值),
                    超限返回 429/Throttled。配额可在控制台提交工单申请提升,审批一般 1 个工作日内完成。"""));
}
