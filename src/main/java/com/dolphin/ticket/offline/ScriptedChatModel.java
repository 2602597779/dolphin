package com.dolphin.ticket.offline;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 脚本化 ChatModel 桩:offline 演示模式专用,按系统提示词特征识别调用方并返回固定话术。
 *
 * <p>识别标记与各 Agent 的系统提示词(prompts/*.st)一一对应:
 * 分诊助手→triage.st、故障诊断专家→diagnosis.st、知识问答助手→kb-answer.st、
 * 回复撰写助手→reply.st、质检→quality.st。剧本内容按用户消息关键词判定,
 * 诊断剧本按契约在报告末行输出 PROPOSAL:NONE 或 PROPOSAL:{json} 结构化动作建议,
 * 由 DiagnosisAgent 解析后驱动三条路由(进审批/免审批直执/无动作结单)。
 */
public class ScriptedChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = extract(prompt, MessageType.SYSTEM);
        String user = extract(prompt, MessageType.USER);
        String text = script(system, user);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Override
    public OpenAiChatOptions getDefaultOptions() {
        return OpenAiChatOptions.builder().model("offline-scripted-stub").build();
    }

    /** 按系统提示词特征分流;未命中工单四类 Agent 时返回占位回复 */
    private String script(String system, String user) {
        String s = system == null ? "" : system;
        if (s.contains("分诊助手")) {
            return triage(user);
        }
        if (s.contains("故障诊断专家")) {
            return diagnosis(user);
        }
        if (s.contains("知识问答助手")) {
            return kbAnswer();
        }
        if (s.contains("回复撰写助手")) {
            return reply(user);
        }
        if (s.contains("质检")) {
            return "通过\n(offline 桩:演示模式不做真实质检)\nVERDICT:PASS";
        }
        return "offline 模式:该请求未命中工单演示剧本,桩返回占位回复。";
    }

    /**
     * 本轮诉求文本(多轮上下文感知):多轮时提示词末尾带"对话历史"转录,
     * 取标记之前的内容即为本轮;避免历史里的旧关键词(如上轮"连不上")
     * 压过本轮新诉求(如本轮改说"443 端口"),导致剧本判定停留在上一轮。
     */
    private String currentTurn(String user) {
        String u = user == null ? "" : user;
        int idx = u.indexOf("对话历史");
        return idx >= 0 ? u.substring(0, idx) : u;
    }

    /**
     * 分诊:优先结构化 JSON(TriageAgent 走 entity(Class) 反序列化),
     * 若被要求按行输出(兜底路径)则输出 产品:/类型:/紧急度: 三行。
     * 多轮时本轮诉求优先判定;本轮无任何产品特征时回退全文,
     * 让"有进展吗"这类模糊追问仍沿历史剧本续跑而非脱离上下文。
     */
    private String triage(String user) {
        String u = user == null ? "" : user;
        String head = currentTurn(u);
        String base = hasTriageSignal(head) ? head : u;
        String product;
        String type;
        String urgency;
        if (base.contains("连不上") || base.contains("重启") || base.contains("宕机") || base.contains("不可用")) {
            product = "ECS";
            type = "故障";
            urgency = "高";
        } else if (base.contains("安全组") || base.contains("443") || base.contains("端口")) {
            product = "ECS";
            type = "故障";
            urgency = "中";
        } else if (base.contains("健康检查") || base.contains("什么原因") || base.contains("怎么")
                || base.contains("如何") || base.contains("计费") || base.contains("开通")) {
            product = "SLB";
            type = "咨询";
            urgency = "低";
        } else {
            product = "未知产品";
            type = "咨询";
            urgency = "低";
        }
        if (u.contains("请严格只输出三行")) {
            return "产品:" + product + "\n类型:" + type + "\n紧急度:" + urgency;
        }
        return "{\"product\":\"" + product + "\",\"type\":\"" + type + "\",\"urgency\":\"" + urgency + "\"}";
    }

    /** 分诊是否命中任一产品/诉求特征(用于"本轮优先"判定) */
    private boolean hasTriageSignal(String text) {
        return text.contains("连不上") || text.contains("重启") || text.contains("宕机") || text.contains("不可用")
                || text.contains("安全组") || text.contains("443") || text.contains("端口")
                || text.contains("健康检查") || text.contains("什么原因") || text.contains("怎么")
                || text.contains("如何") || text.contains("计费") || text.contains("开通");
    }

    /**
     * 诊断报告:按工单关键词分剧本,末尾附 PROPOSAL 契约行(结构化动作建议)。
     * 含"5xx/限流/429"→告警自动诊断剧本(M2 告警链路复用诊断 Agent);
     * 含"重启/连不上"→restartInstance(需审批,进中断);
     * 含"安全组/443"→authorizeSecurityGroup(免审批直执);
     * 其余→PROPOSAL:NONE(无待执行动作,直接结单)。
     */
    private String diagnosis(String user) {
        String u = user == null ? "" : user;
        // 多轮上下文:本轮诉求优先(如上轮"连不上"改口本轮"443 端口",按本轮改道);
        // 本轮无剧本特征时回退全文,模糊追问沿历史剧本续跑
        String head = currentTurn(u);
        String base = hasDiagnosisSignal(head) ? head : u;
        if (base.contains("5xx") || base.contains("限流") || base.contains("429")) {
            return """
                    【故障摘要】推理服务持续返回 5xx/限流,uid=u-demo 的模型 qwen-plus 调用错误率超过阈值。
                    【根因分析】1) 上游模型服务过载或实例异常(可能性高);2) 平台配额/限流策略触发(可能性中);3) 客户端流量突增(可能性低)。
                    【依据】告警上下文显示 5xx/429 速率超过 0.1 次/秒持续触发;任务上下文含具体 uid/model 标识。
                    【处置建议】建议先在平台侧确认该 uid/model 的配额余量与服务健康状态;如确认为服务实例异常,可对承载实例执行重启恢复;若处置后仍持续则升级人工排查上游依赖。
                    PROPOSAL:NONE""";
        }
        if (base.contains("重启") || base.contains("连不上")) {
            return """
                    【故障摘要】ECS 实例 i-abc123 远程连接(SSH/RDP)超时,实例疑似系统夯机。
                    【根因分析】1) 操作系统夯死导致网络栈无响应(可能性高);2) CPU/内存打满连接队列溢出(可能性中);3) 网络配置变更(可能性低,近期无变更记录)。
                    【依据】getInstanceStatus 显示实例为 Running 但无响应;getMetrics 显示近 30 分钟 CPU 持续 98%、内存接近上限。
                    【处置建议】对实例执行一次重启以恢复系统响应,重启后观察监控与连通性;若仍异常则升级人工排查系统盘与内核日志。
                    PROPOSAL:{"action":"restartInstance","target":"i-abc123","params":{},"confidence":0.9}""";
        }
        if (base.contains("安全组") || base.contains("443")) {
            return """
                    【故障摘要】客户通过 HTTPS(443 端口)访问目标服务失败,诉求为安全组 sg-demo 开放 443 端口。
                    【根因分析】1) 安全组 sg-demo 入方向规则未放行 TCP 443(可能性高);2) 后端服务未监听 443(可能性中)。
                    【依据】外部探测 TCP 443 超时;查询 sg-demo 入方向规则仅含 22/80,无 443 放行条目。
                    【处置建议】为安全组 sg-demo 添加入方向 TCP 443 放行规则,属低风险可逆配置变更,可自动执行;完成后请客户复测 HTTPS 访问。
                    PROPOSAL:{"action":"authorizeSecurityGroup","target":"sg-demo","params":{"port":"443"},"confidence":0.95}""";
        }
        return """
                【故障摘要】未识别到明确故障特征。
                【根因分析】工单信息不足,暂无法定位。
                【依据】无有效监控与状态数据。
                【处置建议】建议客户补充具体现象与报错信息后重新提交。
                PROPOSAL:NONE""";
    }

    /** 诊断是否命中任一剧本特征(用于"本轮优先"判定) */
    private boolean hasDiagnosisSignal(String text) {
        return text.contains("5xx") || text.contains("限流") || text.contains("429")
                || text.contains("重启") || text.contains("连不上")
                || text.contains("安全组") || text.contains("443");
    }

    /** 咨询剧本答案(SLB 健康检查) */
    private String kbAnswer() {
        return """
                SLB 健康检查不通过的常见原因:
                1. 后端服务器端口未监听或应用进程异常——确认后端 ECS 上服务进程与监听端口正常;
                2. 安全组/防火墙未放行负载均衡器健康检查探测流量——需放行来自 SLB 私网网段的探测端口;
                3. 健康检查配置与实际不符——检查检查路径(如 /)、域名、预期响应码是否与应用一致;
                4. 后端服务器过载或响应超时——CPU/连接数打满导致探测超时被摘除;
                5. 网络链路异常——后端 ECS 与 SLB 之间路由/子网配置问题。
                排查入口:控制台-负载均衡-实例管理-监听器-健康检查,查看异常后端服务器与检查日志,按上述顺序逐项核对。""";
    }

    /** 回复撰写:按内部处理内容关键词输出客户可见终稿草稿 */
    private String reply(String user) {
        String u = user == null ? "" : user;
        // 多轮上下文:本轮诉求优先,避免历史里的旧关键词压过本轮新诉求导致回复答非所问
        String head = currentTurn(u);
        String base = hasReplySignal(head) ? head : u;
        if (base.contains("重启")) {
            return """
                    您好,关于实例 i-abc123 无法连接的问题:经诊断,实例操作系统疑似夯死导致远程连接无响应。\
                    重启实例属于变更操作,已提交人工审批,审批通过后将立即执行并同步结果;若处理后仍无法连接,\
                    我们将升级人工进一步排查系统盘与内核日志。给您带来不便敬请谅解。""";
        }
        if (base.contains("健康检查")) {
            return """
                    您好,SLB 健康检查不通过通常与后端服务未正常监听、安全组未放行健康检查探测、\
                    健康检查路径或预期响应码配置不符、后端资源过载超时有关。建议您在控制台-负载均衡-监听器-健康检查\
                    中查看异常后端服务器,并按以上顺序逐项核对;如仍无法定位,可回复本工单由我们协助排查。""";
        }
        if (base.contains("安全组")) {
            return """
                    您好,关于 443 端口访问失败的问题:经诊断,安全组 sg-demo 入方向未放行 TCP 443。\
                    该配置变更风险低且可逆,系统将自动为您添加 443 端口放行规则,完成后请复测 HTTPS 访问;\
                    若仍有异常请回复本工单,我们将继续排查服务监听配置。""";
        }
        return "您好,您的工单已受理,我们将尽快处理并同步进展。";
    }

    /** 回复撰写是否命中任一剧本特征(用于"本轮优先"判定) */
    private boolean hasReplySignal(String text) {
        return text.contains("重启") || text.contains("健康检查")
                || text.contains("安全组") || text.contains("443");
    }

    /** 提取指定类型消息的拼接文本 */
    private String extract(Prompt prompt, MessageType type) {
        List<Message> messages = prompt.getInstructions();
        if (messages == null) {
            return "";
        }
        return messages.stream()
                .filter(m -> m.getMessageType() == type)
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
    }
}
