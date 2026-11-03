package com.dolphin.alert.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 已受理告警事件仓库(表 alert_event) */
public interface AlertEventRepository extends JpaRepository<AlertEventEntity, String> {

    List<AlertEventEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
