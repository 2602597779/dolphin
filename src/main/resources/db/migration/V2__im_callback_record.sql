-- 阶段5(D1):IM 审批回调落库
-- 语义:平台回调天然重投(钉钉失败会重试),以 (platform, callback_id) 作幂等键,
--       同一回调只生效一次;重复投递直接返回首次结果,不重复落审批决策。
CREATE TABLE im_callback_record (
    platform    VARCHAR(32)   NOT NULL,
    callback_id VARCHAR(128)  NOT NULL,
    ticket_id   VARCHAR(64),
    result      VARCHAR(32)   NOT NULL,
    detail      VARCHAR(1024),
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_im_callback_record PRIMARY KEY (platform, callback_id)
);

-- 按工单回查回调记录(演示/排查用):同一工单可能收到多条回调(先拒绝后批准等异常路径)
CREATE INDEX ix_im_callback_record_ticket ON im_callback_record (ticket_id);
