package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 工单对话消息仓库(表 ticket_message),按工单号取完整线程(旧→新) */
public interface TicketMessageRepository extends JpaRepository<TicketMessageEntity, Long> {

    /** 本工单全部消息,按时间正序(对话线程的展示/转录顺序) */
    List<TicketMessageEntity> findByTicketIdOrderByCreatedAtAscIdAsc(String ticketId);
}
