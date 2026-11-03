package com.dolphin.metrics;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 运营指标 REST 接口(阶段2)。
 *
 * <p>运营侧接口:与告警 webhook、人工池同属内部网络边界,不走客户 API-Key 鉴权
 * (见 {@code ApiKeyAuthFilter},仅保护 {@code /api/tickets/**})。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * 全量汇总:自动化结单率/人工升级率/审批触发率/质检通过率/MTTR。
     */
    @GetMapping("/summary")
    public MetricsService.Summary summary() {
        return metricsService.summary();
    }

    /**
     * 时间窗口报表:from/to 为 ISO-8601 时刻(如 2026-11-01T00:00:00Z),
     * 缺省 from=epoch、to=当前时刻;返回窗口内创建工单的状态/优先级分布、按日创建/结单曲线与 MTTR。
     */
    @GetMapping("/tickets")
    public ResponseEntity<?> tickets(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from == null || from.isBlank() ? null : Instant.parse(from);
            toInstant = to == null || to.isBlank() ? null : Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "from/to 须为 ISO-8601 时刻(如 2026-11-01T00:00:00Z)"));
        }
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "from 不能晚于 to"));
        }
        return ResponseEntity.ok(metricsService.tickets(fromInstant, toInstant));
    }
}
