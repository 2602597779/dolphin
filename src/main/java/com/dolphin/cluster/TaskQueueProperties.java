package com.dolphin.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 任务队列配置({@code dolphin.cluster.task-queue.*},P0 第 3 处接口留口的实现切换)。
 *
 * <p>同 {@link SessionStoreProperties}:两套实现都真实存在(进程内线程池 / 共享任务表),
 * 配置只决定用哪一套。
 */
@ConfigurationProperties(prefix = "dolphin.cluster.task-queue")
public class TaskQueueProperties {

    /** 队列实现:local=进程内线程池(默认,投递语义与改造前一致) | jdbc=共享任务表 ticket_task */
    private String kind = "local";

    /** jdbc 实现:未终态任务超过该时长判定为孤儿(标记 STUCK 留痕,不重建执行) */
    private Duration staleAfter = Duration.ofMinutes(5);

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }
}
