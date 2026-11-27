-- =====================================================================
-- V4 图执行任务表(D5 持久化 2/2)
-- 背景:进程内线程池里的排队任务只存在于"收下请求的那个进程",重启即丢、
--       多实例互不可见(运维视角盲区:不知道某张单的异步执行到底有没有被收下)。
-- 语义:每次投递落一行,status 走状态机
--       PENDING(已收下待执行) → RUNNING(已被某实例抢占执行) → DONE / FAILED
--       旁路:REJECTED(本地执行器已关闭/队列满,调用方走失败补偿)
--       巡检:STUCK(未终态且滞留超过 stale-after,即实例崩溃留下的孤儿任务,只留痕告警)
-- 诚实边界:本表记录的是「投递事实 + 抢占者 + 终态」,任务体(Runnable)仍绑定在投放进程内,
--       因此它提供的是「跨实例可见 + 孤儿任务可检测」,<b>不是</b>「跨进程重建执行」——
--       后者需要消息契约与消费者(真 MQ),不在本阶段范围(不引中间件)。
-- =====================================================================
CREATE TABLE ticket_task (
    task_id     VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    worker_id   VARCHAR(64),
    detail      VARCHAR(512),
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    started_at  TIMESTAMP(6) WITH TIME ZONE,
    finished_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_ticket_task PRIMARY KEY (task_id)
);

-- 孤儿巡检按 (status, created_at) 过滤;运维按状态查积压
CREATE INDEX ix_ticket_task_status ON ticket_task (status, created_at);
