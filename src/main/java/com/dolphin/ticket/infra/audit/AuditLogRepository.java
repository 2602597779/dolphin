package com.dolphin.ticket.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 审计留痕仓库(表 audit_log) */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 按动作名查询(如 quality-review:运营指标质检通过率的数据来源) */
    List<AuditLogEntity> findByAction(String action);
}
