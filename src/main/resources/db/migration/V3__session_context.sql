-- =====================================================================
-- V3 会话粘性状态外置(D5 持久化 1/2)
-- 背景:会话承载「路由粘性(active_capability)」与「待审批工单(pending_ticket_id)」,
--       进程内实现是单机 ConcurrentHashMap:多实例下同一用户的下一次请求可能落到
--       另一台机器,粘性丢失、审批上下文丢失。本表把这份状态放到共享存储。
-- 语义:session_id 主键一行,读-改-写;last_accessed_at 为 epoch 毫秒
--       (与 DialogContext.lastAccessedAt 字段一一对应,免时区换算),是空闲过期的唯一判据。
-- 说明:与进程内实现同款口径——默认 30 分钟未访问即清理(由 @Scheduled 周期删除)。
-- =====================================================================
CREATE TABLE session_context (
    session_id        VARCHAR(128) NOT NULL,
    active_capability VARCHAR(64),
    pending_ticket_id VARCHAR(64),
    last_accessed_at  BIGINT       NOT NULL,
    CONSTRAINT pk_session_context PRIMARY KEY (session_id)
);

-- 空闲过期清理按 last_accessed_at 扫描(清理是周期全表条件删除,索引避免全表扫)
CREATE INDEX ix_session_context_last_accessed ON session_context (last_accessed_at);
