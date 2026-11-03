package com.dolphin.ticket.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/** 审计留痕仓库(表 audit_log) */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
}
