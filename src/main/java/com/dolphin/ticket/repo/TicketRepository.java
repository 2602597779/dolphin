package com.dolphin.ticket.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 工单台账仓库(表 ticket) */
public interface TicketRepository extends JpaRepository<TicketEntity, String> {

    /** 按工单号 + 归属客户查询(租户隔离:非本客户的单查不到) */
    Optional<TicketEntity> findByIdAndCustomerId(String id, String customerId);

    /** 本客户的工单列表(按创建时间新→旧),status 可为 null 表示不过滤 */
    default List<TicketEntity> findByCustomer(String customerId, String status, Pageable pageable) {
        if (status == null || status.isBlank()) {
            return findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        }
        return findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, status, pageable);
    }

    List<TicketEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    List<TicketEntity> findByCustomerIdAndStatusOrderByCreatedAtDesc(String customerId, String status,
                                                                     Pageable pageable);

    /** 全量工单列表(运营视角),按创建时间新→旧 */
    List<TicketEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按状态的全量工单列表(运营视角),按创建时间新→旧 */
    List<TicketEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
