package com.dolphin.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 客户种子数据:演示环境首次启动灌入两个租户(表空才灌,可重复启动幂等)。
 *
 * <p>demo key 为固定明文,仅用于 offline 演示与 README 剧本;生产环境密钥
 * 应由客户入驻流程发放并入库,不经过本类。
 */
@Component
public class CustomerSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSeeder.class);

    /** README 剧本引用的演示 key(两个租户互不可见对方工单) */
    public static final String DEMO_KEY_ALPHA = "ak-demo-cust-001";
    public static final String DEMO_KEY_BETA = "ak-demo-cust-002";

    private final CustomerRepository repository;

    public CustomerSeeder(CustomerRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            log.info("[tenant] 客户表已有 {} 条数据,跳过种子灌入", repository.count());
            return;
        }
        Instant now = Instant.now();
        repository.save(new CustomerEntity("cust-001", "海豚云客户A", DEMO_KEY_ALPHA, true, now));
        repository.save(new CustomerEntity("cust-002", "海豚云客户B", DEMO_KEY_BETA, true, now));
        log.warn("[tenant] 已灌入演示租户 cust-001/cust-002(固定 demo key,仅限演示环境,生产由入驻流程发钥)");
    }
}
