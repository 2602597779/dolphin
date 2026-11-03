package com.dolphin.ticket.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * 审批令牌已消费 nonce 实体(表 approval_token_nonce):一次性语义的持久化保障,
 * 重启后同一令牌重放仍会被拒。过期行由校验链路机会式清理。
 *
 * <p>实现 {@link Persistable} 恒返回新实体:nonce 行纯插入、永不更新,
 * save 走 persist 而非 merge——并发下重复主键插入触发唯一约束冲突,作为重放拒绝的并发兜底。
 */
@Entity
@Table(name = "approval_token_nonce")
public class ApprovalTokenNonceEntity implements Persistable<String> {

    @Id
    @Column(name = "nonce", nullable = false, length = 64)
    private String nonce;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    protected ApprovalTokenNonceEntity() {
    }

    public ApprovalTokenNonceEntity(String nonce, long expiresAt) {
        this.nonce = nonce;
        this.expiresAt = expiresAt;
    }

    public String getNonce() {
        return nonce;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    /** nonce 只写一次:恒为新实体,save 强制 INSERT(冲突即重放) */
    @Override
    public boolean isNew() {
        return true;
    }

    @Override
    public String getId() {
        return nonce;
    }
}
