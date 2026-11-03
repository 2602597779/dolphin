package com.dolphin.ticket.auth;

import com.dolphin.customer.CustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 客户工单 API 鉴权过滤器:请求头 {@code X-API-Key} → 客户身份。
 *
 * <p>保护范围:仅 {@code /api/tickets/**}(客户侧接口);
 * 告警 webhook、人工池、指标等运营侧接口由内部网络边界保护,不走本过滤器。
 * 鉴权通过后把 customerId 写入 request attribute,Controller 从上下文取归属,
 * 请求体不携带任何身份字段(无法伪造他人 customerId)。
 *
 * <p>安全语义:缺失/错误/已吊销的 key 统一 401 + 同一文案,不给探测者反馈差异。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** 鉴权通过后写入 request attribute 的客户身份键 */
    public static final String CUSTOMER_ID_ATTR = "customerId";

    private static final String HEADER = "X-API-Key";
    private static final String PROTECTED_PATH = "/api/tickets";
    private static final String ERROR_MESSAGE = "认证失败:请提供有效的 X-API-Key";

    private final CustomerService customerService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(CustomerService customerService, ObjectMapper objectMapper) {
        this.customerService = customerService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦截客户工单接口,其余路径放行
        return !request.getRequestURI().startsWith(PROTECTED_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        var customer = customerService.authenticate(apiKey);
        if (customer.isEmpty()) {
            log.warn("[auth] 拒绝访问: uri={}, apiKey {}", request.getRequestURI(),
                    apiKey == null ? "缺失" : "无效或已吊销");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", ERROR_MESSAGE)));
            return;
        }
        request.setAttribute(CUSTOMER_ID_ATTR, customer.get().id());
        filterChain.doFilter(request, response);
    }
}
