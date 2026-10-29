package com.dolphin.ticket.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工单台账服务(骨架阶段内存实现)。
 *
 * <p>TODO:替换为数据库持久化(MySQL/Postgres),并补充工单流转历史、SLA 计时等字段。
 */
@Component
public class TicketService {

    /** 工单状态常量 */
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_ESCALATED = "ESCALATED";

    /**
     * 工单实体(骨架阶段用 record 表达)。
     *
     * @param id        工单号
     * @param rawMessage 用户原始工单文本
     * @param status    当前状态
     */
    public record Ticket(String id, String rawMessage, String status) {
    }

    private final ConcurrentHashMap<String, Ticket> store = new ConcurrentHashMap<>();

    /** 创建工单,返回生成的工单号 */
    public String create(String rawMessage) {
        String id = "T" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        store.put(id, new Ticket(id, rawMessage, STATUS_NEW));
        return id;
    }

    /** 按工单号查询 */
    public Optional<Ticket> get(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /** 更新工单状态(工单不存在时静默忽略) */
    public void updateStatus(String id, String status) {
        store.computeIfPresent(id, (k, t) -> new Ticket(t.id(), t.rawMessage(), status));
    }
}
