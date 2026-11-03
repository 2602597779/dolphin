package com.dolphin.customer;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 客户鉴权服务:API-Key → 客户身份。
 *
 * <p>鉴权语义:key 不存在或客户被吊销(enabled=false)一律返回 empty,
 * 调用方统一按 401 处理,不区分"key 错误"与"key 已吊销"(避免探测泄露)。
 */
@Service
public class CustomerService {

    /** 客户领域对象(record,对外只读) */
    public record Customer(String id, String name) {
    }

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    /** 校验 API-Key,返回启用状态的客户;不存在/已吊销返回 empty */
    public Optional<Customer> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByApiKey(apiKey)
                .filter(CustomerEntity::isEnabled)
                .map(e -> new Customer(e.getId(), e.getName()));
    }
}
