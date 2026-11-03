package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

/** 图 checkpoint 仓库(表 graph_checkpoint),threadId(工单号)一行 */
public interface GraphCheckpointRepository extends JpaRepository<GraphCheckpointEntity, String> {
}
