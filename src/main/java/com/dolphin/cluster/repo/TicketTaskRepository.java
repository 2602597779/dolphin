package com.dolphin.cluster.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

/**
 * 图执行任务仓库(表 ticket_task)。
 *
 * <p>三个写方法都自带事务边界:它们的调用点要么在异步执行线程里(没有可传播的事务,
 * 条件 UPDATE 必须在真实事务里才会落库并释放行锁),要么在 {@code @Scheduled} 巡检线程里。
 * 这一点与 {@code TicketRepository} 的条件更新不同——后者由调用方(B@Transactional 的服务方法)给事务。
 */
public interface TicketTaskRepository extends JpaRepository<TicketTaskEntity, String> {

    /**
     * 抢占:仅当任务仍是 PENDING 时置为 RUNNING 并写下抢占者(条件 UPDATE,原子)。
     *
     * 多实例下同一张任务表可能被多个巡检/执行者看到,但"从 PENDING 改成 RUNNING"
     * 在数据库行级只有一个事务能成功,重复执行在 SQL 层就被挡住。
     *
     * @return 1=我抢到并开始执行;0=已被其他实例抢走或已进入终态
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketTaskEntity t set t.status = :running, t.workerId = :worker, t.startedAt = :at"
            + " where t.taskId = :taskId and t.status = :pending")
    int claim(@Param("taskId") String taskId,
              @Param("pending") String pending,
              @Param("running") String running,
              @Param("worker") String worker,
              @Param("at") Instant at);

    /** 终态收口:置 DONE / FAILED / REJECTED 并写终态时间与说明 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketTaskEntity t set t.status = :status, t.detail = :detail, t.finishedAt = :at"
            + " where t.taskId = :taskId")
    int finish(@Param("taskId") String taskId,
               @Param("status") String status,
               @Param("detail") String detail,
               @Param("at") Instant at);

    /**
     * 孤儿任务巡检:把"仍未终态且滞留超过 deadline"的任务标记为 STUCK。
     *
     * 滞留起点取 {@code startedAt}(已抢抢占的用开工时间),没开工的用 {@code createdAt}。
     * 只改状态留痕,不重建执行(需要消息契约,见表注释)。
     *
     * @return 本次判定为孤儿的行数
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TicketTaskEntity t set t.status = :stuck, t.detail = :detail, t.finishedAt = :at"
            + " where t.status in (:openStatuses) and coalesce(t.startedAt, t.createdAt) < :deadline")
    int markStuck(@Param("openStatuses") Collection<String> openStatuses,
                  @Param("deadline") Instant deadline,
                  @Param("stuck") String stuck,
                  @Param("detail") String detail,
                  @Param("at") Instant at);
}
