package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 人工任务仓库(表 human_task) */
public interface HumanTaskRepository extends JpaRepository<HumanTaskEntity, String> {

    List<HumanTaskEntity> findByStatusIgnoreCaseOrderByUpdatedAtDesc(String status);

    List<HumanTaskEntity> findAllByOrderByUpdatedAtDesc();
}
