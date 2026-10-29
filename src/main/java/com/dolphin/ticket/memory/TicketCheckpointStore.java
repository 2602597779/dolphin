package com.dolphin.ticket.memory;

import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverConstant;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.springframework.stereotype.Component;

/**
 * 工单图 checkpoint 存储(会话记忆)。
 *
 * <p>checkpoint 即多轮会话记忆:图在每个节点后快照状态,按 threadId(工单号)隔离,
 * 审批 resume 时从快照续跑。骨架阶段用内存 MemorySaver,进程重启即丢失。
 * TODO:替换为持久化 saver(如 Redis/数据库实现 BaseCheckpointSaver),并加过期清理。
 */
@Component
public class TicketCheckpointStore {

    private final MemorySaver memorySaver = new MemorySaver();

    /** 构建供 CompileConfig 使用的 saver 配置(注册为 MEMORY 类型) */
    public SaverConfig saverConfig() {
        return SaverConfig.builder()
                .register(SaverConstant.MEMORY, memorySaver)
                .build();
    }

    /** 暴露底层 saver,便于 TODO 替换持久化实现时统一收口 */
    public MemorySaver saver() {
        return memorySaver;
    }
}
