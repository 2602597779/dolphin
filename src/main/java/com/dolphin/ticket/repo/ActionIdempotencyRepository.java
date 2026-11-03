package com.dolphin.ticket.repo;

import org.springframework.data.jpa.repository.JpaRepository;

/** 写操作幂等仓库(表 action_idempotency) */
public interface ActionIdempotencyRepository extends JpaRepository<ActionIdempotencyEntity, String> {
}
