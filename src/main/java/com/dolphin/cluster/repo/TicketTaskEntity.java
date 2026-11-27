package com.dolphin.cluster.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 图执行任务实体(表 ticket_task):一次异步投递一行,记录"投递事实 + 抢占者 + 终态"。
 *
 * <p>它解决的是可观测性盲区(任务有没有被收下、谁在执行、有没有卡死),
 * 而不是跨进程重建执行——任务体绑定在投放进程内,进程没了任务体就没了,
 * 表里留下的是可被巡检发现并告警的"孤儿行"(详情见表注释与
 * {@link com.dolphin.cluster.JdbcTicketTaskQueue} 类注释)。
 */
@Entity
@Table(name = "ticket_task")
public class TicketTaskEntity {

    @Id
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    /** PENDING / RUNNING / DONE / FAILED / REJECTED / STUCK */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 抢占者标识(进程 pid@host,多实例排查"是谁在执行") */
    @Column(name = "worker_id", length = 64)
    private String workerId;

    /** 终态说明(失败原因 / 拒绝原因 / 孤儿判定说明) */
    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 被抢占开始执行的时间(RUNNING 起) */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 终态时间(DONE / FAILED / REJECTED / STUCK) */
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected TicketTaskEntity() {
    }

    public TicketTaskEntity(String taskId, String status, String workerId, String detail,
                            Instant createdAt, Instant startedAt, Instant finishedAt) {
        this.taskId = taskId;
        this.status = status;
        this.workerId = workerId;
        this.detail = detail;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getStatus() {
        return status;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
