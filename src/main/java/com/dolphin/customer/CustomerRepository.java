package com.dolphin.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 客户(租户)仓库(表 customer) */
public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {

    /** 按 API-Key 精确查询(唯一约束,鉴权路径每次请求调用) */
    Optional<CustomerEntity> findByApiKey(String apiKey);
}
