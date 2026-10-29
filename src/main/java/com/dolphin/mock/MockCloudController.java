package com.dolphin.mock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

/**
 * Mock 云端点 REST API({@code /__mock/**}):路径与 {@code dolphin.cloud.paths} 模板一一对应,
 * 是 provider=http 数据源(S2-4 HttpCloudApi)的对接目标——offline 环境下的"假远端云"。
 * 状态机见 {@link MockCloudStore},两者共同构成与真实云 API 同构的 HTTP 契约。
 *
 * <p>响应约定:
 * <ul>
 *   <li>写操作(POST)返回受理回执 {@code {message}}——受理≠生效,生效与否靠读路径核对;</li>
 *   <li>独立读路径(退款流水/规则/探测)查不到时返回 <b>404</b>,调用方(HttpCloudApi)
 *       据此映射为 {@code Optional.empty()},禁止把 404 伪装成"已生效";</li>
 *   <li>实例快照始终 200(与模拟器"未知实例 = RUNNING、generation 0"语义一致)。</li>
 * </ul>
 */
@RestController
@RequestMapping("/__mock")
public class MockCloudController {

    private final MockCloudStore store;

    public MockCloudController(MockCloudStore store) {
        this.store = store;
    }

    /** GET 实例快照:state/restartGeneration 机器可判定,供结果验证轮询 */
    @GetMapping("/v1/instances/{instanceId}")
    public Map<String, Object> instance(@PathVariable String instanceId) {
        MockCloudStore.Snapshot s = store.snapshot(instanceId);
        return Map.of("state", s.state(), "detail", s.detail(), "restartGeneration", s.restartGeneration());
    }

    /** POST 重启受理:只返回受理回执,状态迁移延迟由 MockCloudStore 模拟 */
    @PostMapping("/v1/instances/{instanceId}/reboot")
    public Map<String, Object> reboot(@PathVariable String instanceId) {
        return Map.of("message", store.reboot(instanceId));
    }

    /** GET 实例监控指标 */
    @GetMapping("/v1/instances/{instanceId}/metrics")
    public Map<String, Object> metrics(@PathVariable String instanceId,
                                       @RequestParam("metric") String metricName) {
        return Map.of("message", store.metrics(instanceId, metricName));
    }

    /** POST 退款受理:生成 serialNo 流水,异步落账后才可查 */
    @PostMapping("/v1/orders/{orderId}/refund")
    public Map<String, Object> refund(@PathVariable String orderId) {
        return Map.of("message", store.refund(orderId));
    }

    /** GET 退款流水对账(独立读路径):未受理/未落账/落账丢失 → 404 */
    @GetMapping("/v1/orders/{orderId}/refunds/latest")
    public ResponseEntity<Map<String, Object>> latestRefund(@PathVariable String orderId) {
        Optional<MockCloudStore.RefundEntry> entry = store.latestRefund(orderId);
        return entry
                .<ResponseEntity<Map<String, Object>>>map(e -> ResponseEntity.ok(Map.of(
                        "serialNo", e.serialNo(), "acceptedAt", e.acceptedAt().toString())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** POST 安全组放行端口:受理即返回,version 单调递增,生效延迟由 MockCloudStore 模拟 */
    @PostMapping("/v1/security-groups/{sgId}/rules")
    public Map<String, Object> authorize(@PathVariable String sgId, @RequestParam("port") int port) {
        return Map.of("message", store.authorize(sgId, port));
    }

    /** GET 安全组规则(独立读路径):不存在 → 404;已受理未生效也返回(带 version 供比对) */
    @GetMapping("/v1/security-groups/{sgId}/rules")
    public ResponseEntity<Map<String, Object>> rule(@PathVariable String sgId,
                                                    @RequestParam("port") int port) {
        Optional<MockCloudStore.RuleEntry> rule = store.rule(sgId, port);
        return rule
                .<ResponseEntity<Map<String, Object>>>map(r -> ResponseEntity.ok(Map.of(
                        "effectiveAt", r.effectiveAt().toString(), "version", r.version())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** GET 探测端点解析:规则不存在/未生效/下发丢失(ID 含 "void") → 404 */
    @GetMapping("/v1/security-groups/{sgId}/probe")
    public ResponseEntity<Map<String, Object>> probe(@PathVariable String sgId,
                                                     @RequestParam("port") int port) {
        Optional<InetSocketAddress> endpoint = store.probe(sgId, port);
        return endpoint
                .<ResponseEntity<Map<String, Object>>>map(a -> ResponseEntity.ok(Map.of(
                        "host", a.getAddress().getHostAddress(), "port", a.getPort())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
