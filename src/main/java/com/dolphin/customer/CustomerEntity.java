package com.dolphin.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 客户(租户)主数据实体(表 customer)。
 *
 * <p>工单按客户归属隔离:ticket.customer_id 外键语义指向本表 id;
 * API-Key 为客户侧调用凭证(演示环境固定值,生产应走客户入驻发放+定期轮转)。
 */
@Entity
@Table(name = "customer")
public class CustomerEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 调用凭证:唯一;泄露后可置 enabled=false 一键吊销 */
    @Column(name = "api_key", nullable = false, unique = true, length = 128)
    private String apiKey;

    /** 吊销开关:false 后该客户所有调用立即 401,存量工单台账保留 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CustomerEntity() {
    }

    public CustomerEntity(String id, String name, String apiKey, boolean enabled, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
