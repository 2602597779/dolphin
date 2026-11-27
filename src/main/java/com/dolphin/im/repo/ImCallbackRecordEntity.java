package com.dolphin.im.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * IM 回调投递记录实体(表 im_callback_record)。
 *
 * <p>平台重投是常态(应答不合规/超时/网络抖动都会重投),所以回调入口必须幂等。
 * 主键 (platform, callback_id) 既是去重键也是并发兜底:同一平台同一事件只可能有一行,
 * 重复投递只会读到首次处理结果,"只执行一次"这件事由存储层保证,而非应用层自觉。
 */
@Entity
@Table(name = "im_callback_record")
@IdClass(ImCallbackRecordEntity.Key.class)
public class ImCallbackRecordEntity {

    /** 已受理:驱动了审批续跑 */
    public static final String RESULT_ACCEPTED = "ACCEPTED";

    /** 已拒绝:请求本身非法(验签失败/缺字段),平台重投也不会变好 */
    public static final String RESULT_REJECTED = "REJECTED";

    /** 已忽略:请求合法但无需处置(工单不存在/状态不符/并发重复审批) */
    public static final String RESULT_IGNORED = "IGNORED";

    @Id
    @Column(name = "platform", nullable = false, length = 32)
    private String platform;

    @Id
    @Column(name = "callback_id", nullable = false, length = 128)
    private String callbackId;

    @Column(name = "ticket_id", length = 64)
    private String ticketId;

    @Column(name = "result", nullable = false, length = 32)
    private String result;

    /** 结果说明:拒绝原因 / 忽略原因,供重投时原样回放 */
    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImCallbackRecordEntity() {
    }

    public ImCallbackRecordEntity(String platform, String callbackId, String ticketId,
                                  String result, String detail, Instant createdAt) {
        this.platform = platform;
        this.callbackId = callbackId;
        this.ticketId = ticketId;
        this.result = result;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public String getPlatform() {
        return platform;
    }

    public String getCallbackId() {
        return callbackId;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 复合主键:平台 + 事件唯一号 */
    public static class Key implements Serializable {

        private String platform;

        private String callbackId;

        public Key() {
        }

        public Key(String platform, String callbackId) {
            this.platform = platform;
            this.callbackId = callbackId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(platform, key.platform) && Objects.equals(callbackId, key.callbackId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(platform, callbackId);
        }
    }
}
