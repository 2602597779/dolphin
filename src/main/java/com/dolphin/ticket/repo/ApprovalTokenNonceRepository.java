package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** 审批令牌已消费 nonce 仓库(表 approval_token_nonce) */
public interface ApprovalTokenNonceRepository extends JpaRepository<ApprovalTokenNonceEntity, String> {

    /** 清理已过期 nonce(令牌过期后不再需要占位防重放) */
    @Transactional
    void deleteByExpiresAtBefore(long expiresAt);
}
