package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 工单对话消息仓库(表 ticket_message),按工单号取完整线程(旧→新) */
public interface TicketMessageRepository extends JpaRepository<TicketMessageEntity, Long> {

    /** 本工单全部消息,按时间正序(对话线程的展示/转录顺序) */
    List<TicketMessageEntity> findByTicketIdOrderByCreatedAtAscIdAsc(String ticketId);

    /** 幂等判重:同一工单下是否已存在该客户端请求号(阶段4优化 O7) */
    Optional<TicketMessageEntity> findByTicketIdAndClientMsgId(String ticketId, String clientMsgId);

    /** 本工单最新一条指定角色的消息(阶段 O8 状态视图取"最新客服答复",避免整线程加载) */
    Optional<TicketMessageEntity> findFirstByTicketIdAndRoleOrderByIdDesc(String ticketId, String role);

    /** 本工单消息数(阶段 O8 状态视图的 rounds 口径) */
    long countByTicketId(String ticketId);
}
