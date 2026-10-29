package com.dolphin.ticket.service;

import java.util.Map;

/**
 * HTTP 执行接缝:{@link HttpCloudApi} 只负责"CloudApi 契约方法 → HTTP 调用描述 → 契约返回值"
 * 的纯映射,真正的建连/超时/鉴权/重试全部由实现方完成。
 *
 * <p>拆出接缝的目的:
 * <ul>
 *   <li>单测用 fake transport 替身,不启动真实 HTTP 栈即可覆盖映射逻辑与 404 语义;</li>
 *   <li>超时/鉴权/重试集中在实现层(见 RestCloudTransport),且重试策略按 HTTP method
 *       区分:<b>GET(读)可有限重试,POST(写)绝不重试</b>——写操作的幂等由受理方
 *       clientToken 保证,提交方盲目重试会造成重复重启/重复退款。</li>
 * </ul>
 *
 * <p>实现方约定:连接失败/超时直接抛异常(显式失败);HTTP 状态码原样带回,
 * 语义判定(200=成功 / 404=独立读路径资源不存在 / 其他=失败)统一在 {@link HttpCloudApi}
 * 完成,transport 层禁止吞错或伪造响应。
 */
public interface HttpTransport {

    /**
     * HTTP 调用描述:method 只会是 GET/POST;path 已完成路径模板占位符替换;
     * query 为查询参数(可为空 Map)。URL 编码由实现方负责。
     */
    record HttpCall(String method, String path, Map<String, String> query) {

        public static HttpCall get(String path, Map<String, String> query) {
            return new HttpCall("GET", path, query);
        }

        public static HttpCall post(String path, Map<String, String> query) {
            return new HttpCall("POST", path, query);
        }
    }

    /** HTTP 响应:status 为原始状态码,body 为原始文本(调用方据此判定语义) */
    record HttpResult(int status, String body) {

        public boolean isOk() {
            return status >= 200 && status < 300;
        }

        public boolean isNotFound() {
            return status == 404;
        }
    }

    /** 执行一次 HTTP 调用;连接失败/超时抛异常,成功时返回原始状态码与响应体 */
    HttpResult exchange(HttpCall call);
}
