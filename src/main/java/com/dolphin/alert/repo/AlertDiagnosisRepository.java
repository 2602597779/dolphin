package com.dolphin.alert.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 告警诊断记录仓库(表 alert_diagnosis) */
public interface AlertDiagnosisRepository extends JpaRepository<AlertDiagnosisEntity, Long> {

    List<AlertDiagnosisEntity> findAllByOrderBySeqDesc(Pageable pageable);
}
