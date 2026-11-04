package com.dolphin.ticket.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverConstant;
import com.dolphin.ticket.repo.GraphCheckpointRepository;
import org.springframework.stereotype.Component;

/**
 * 工单图 checkpoint 存储(会话记忆)。
 *
 * <p>checkpoint 即多轮会话记忆:图在每个节点后快照状态,按 threadId(工单号)隔离,
 * 审批 resume 时从快照续跑。P0 起由内存 MemorySaver 换为 {@link DbCheckpointSaver}(表 graph_checkpoint):
 * 进程重启后审批中断的工单仍可续跑,审批等待不再惧怕重启。
 *
 * <p>仍注册在 {@link SaverConstant#MEMORY} 类型下:单 saver 时 {@code SaverConfig.get()}
 * 直接返回注册实例,对上层(TicketGraphCompiler/CompileConfig)完全透明。
 */
@Component
public class TicketCheckpointStore {

    private final DbCheckpointSaver saver;

    public TicketCheckpointStore(GraphCheckpointRepository repository) {
        this.saver = new DbCheckpointSaver(repository);
    }

    /** 构建供 CompileConfig 使用的 saver 配置(注册为 MEMORY 类型) */
    public SaverConfig saverConfig() {
        return SaverConfig.builder()
                .register(SaverConstant.MEMORY, saver)
                .build();
    }

    /** 暴露底层 saver,便于测试与运维诊断 */
    public BaseCheckpointSaver saver() {
        return saver;
    }

    /**
     * 清除本工单的图 checkpoint(阶段4 reply 续跑用):
     * 旧断点停留在上一轮的中断点(如 approval 前),带着它 invoke 会直接 resume 而非重新评估;
     * reply 语义是"按最新上下文从头重跑",先清旧断点再 invoke。
     */
    public void clear(String ticketId) {
        saver.clear(RunnableConfig.builder().threadId(ticketId).build());
    }
}
