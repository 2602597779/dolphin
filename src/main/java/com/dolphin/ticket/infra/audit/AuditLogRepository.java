package com.dolphin.ticket.infra.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 审计留痕仓库(表 audit_log) */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /** 按动作名查询(如 quality-review:运营指标质检通过率的数据来源) */
    List<AuditLogEntity> findByAction(String action);

    /**
     * 按业务主标识(工单号)查询全部留痕,按自增 id 升序。
     *
     * <p>时间线还原必须用 id 而不是 at:同一毫秒内的多条事件 at 会并列,id 严格单调。
     */
    List<AuditLogEntity> findBySessionIdOrderByIdAsc(String sessionId);
}
