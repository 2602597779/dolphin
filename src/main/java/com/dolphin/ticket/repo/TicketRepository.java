package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

/** 工单台账仓库(表 ticket) */
public interface TicketRepository extends JpaRepository<TicketEntity, String> {
}
