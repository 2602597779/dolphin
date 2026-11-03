package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 审批决策留痕仓库(表 approval_decision),按工单号取唯一有效决策 */
public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecisionEntity, Long> {

    Optional<ApprovalDecisionEntity> findByTicketId(String ticketId);
}
