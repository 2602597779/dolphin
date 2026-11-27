package com.dolphin.router.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 会话粘性状态仓库(表 session_context),session_id(调用方给的会话号)一行 */
public interface SessionContextRepository extends JpaRepository<SessionContextEntity, String> {

    /**
     * 空闲过期清理:删除 last_accessed_at 早于 {@code deadline} 的会话行。
     *
     * <p>与进程内实现同口径(默认 30 分钟未访问即移除),但多实例下是共享清理:
     * 任一节点巡检一次,全体节点都看不到过期会话。
     *
     * @param deadline 截止时刻(epoch 毫秒),早于它的会话被删除
     * @return 实际删除行数(0=无可清理会话)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SessionContextEntity s where s.lastAccessedAt < :deadline")
    int deleteIdleBefore(@Param("deadline") long deadline);
}
