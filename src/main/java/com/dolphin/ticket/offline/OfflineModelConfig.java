package com.dolphin.ticket.offline;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * offline 演示模式配置:不依赖任何外部 API Key 即可完整跑通工单演示。
 *
 * <p>只在最底层 ChatModel/EmbeddingModel 打桩(均为 @Profile("offline") + @Primary),
 * 上层真实的 ChatClient 流式 API、graph 编排、动作执行器、向量库全部原样走通;
 * 桩按系统提示词特征识别分诊/诊断/问答/回复四类调用,返回脚本化输出,
 * 驱动三条演示剧本命中三条不同路由(咨询直接结单 / 故障进审批中断再 resume / 免审批直接执行)。
 *
 * <p>使用真实模型:不激活 offline profile,并配置 DOLPHIN_API_KEY 环境变量即可,业务代码零改动。
 */
@Configuration
@Profile("offline")
public class OfflineModelConfig {

    /** 脚本化 ChatModel 桩(替换真实 OpenAI 兼容模型调用) */
    @Bean
    @Primary
    public ChatModel offlineChatModel() {
        return new ScriptedChatModel();
    }

    /** 确定性哈希向量 Embedding 桩(让 SimpleVectorStore 增查不联网) */
    @Bean
    @Primary
    public EmbeddingModel offlineEmbeddingModel() {
        return new HashEmbeddingModel();
    }
}
