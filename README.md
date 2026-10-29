# Dolphin — 云服务工单多智能体系统

## 离线演示（offline profile）

无需任何外部 API Key、无需联网，即可完整跑通工单多 Agent 演示链路。

### 前置条件

无。不需要配置 `DOLPHIN_API_KEY`，不需要任何 LLM/Embedding 服务端点。

### 启动命令

```powershell
# 项目未带 Maven Wrapper，用本机 mvn（或 IDEA 自带：D:\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3\bin\mvn.cmd）
mvn spring-boot:run "-Dspring-boot.run.profiles=offline"
# 或打包后直接运行:
mvn -q package -DskipTests; java -jar target\dolphin-0.0.1-SNAPSHOT.jar --spring.profiles.active=offline
```

启动日志可见 `The following 1 profile is active: "offline"` 与 `[rag] 种子知识灌入完成,共 7 篇`（Embedding 桩离线生效）。

> 提示：Windows PowerShell 下 curl 内联中文 JSON 易踩编码坑，建议把请求体存为 **UTF-8（无 BOM）** 文件后用 `--data-binary "@文件"` 发送。以下输出均为本机真实运行结果。

### 剧本 1：咨询 → 知识问答 → 直接结单

请求（`body1.json`）：

```bash
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "Content-Type: application/json" --data-binary "@body1.json"
```

```json
{"message":"SLB 健康检查不通过一般是什么原因？"}
```

实际响应（工单 `TMVRH8GIY`，状态 `CLOSED`，无待执行动作直达结单）：

```json
{
    "ticketId":  "TMVRH8GIY",
    "status":  "CLOSED",
    "replyDraft":  "您好,SLB 健康检查不通过通常与后端服务未正常监听、安全组未放行健康检查探测、健康检查路径或预期响应码配置不符、后端资源过载超时有关。建议您在控制台-负载均衡-监听器-健康检查中查看异常后端服务器,并按以上顺序逐项核对;如仍无法定位,可回复本工单由我们协助排查。",
    "finalReply":  "您好,SLB 健康检查不通过通常与后端服务未正常监听、安全组未放行健康检查探测、健康检查路径或预期响应码配置不符、后端资源过载超时有关。建议您在控制台-负载均衡-监听器-健康检查中查看异常后端服务器,并按以上顺序逐项核对;如仍无法定位,可回复本工单由我们协助排查。",
    "pendingAction":  ""
}
```

路由：`triage(咨询) → kbAnswer → reply → 无动作 → followup 结单`。

### 剧本 2：故障 → 诊断 → 审批中断 → approve 续跑执行 → 结单

提交（`body2.json`）：

```bash
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "Content-Type: application/json" --data-binary "@body2.json"
```

```json
{"message":"服务器连不上了，帮我重启一下实例 i-abc123"}
```

实际响应——图在 approval 节点前 **中断**，状态 `PENDING_APPROVAL`（工单 `TMVRH8GMR`）：

```json
{
    "ticketId":  "TMVRH8GMR",
    "status":  "PENDING_APPROVAL",
    "replyDraft":  "您好,关于实例 i-abc123 无法连接的问题:经诊断,实例操作系统疑似夯死导致远程连接无响应。重启实例属于变更操作,已提交人工审批,审批通过后将立即执行并同步结果;若处理后仍无法连接,我们将升级人工进一步排查系统盘与内核日志。给您带来不便敬请谅解。",
    "finalReply":  "",
    "pendingAction":  "restartInstance:i-demo"
}
```

审批前查询（GET 看到"待审批"）：

```bash
curl.exe -s http://localhost:8080/api/tickets/TMVRH8GMR
```

```json
{
    "ticketId":  "TMVRH8GMR",
    "status":  "PENDING_APPROVAL",
    "rawMessage":  "服务器连不上了，帮我重启一下实例 i-abc123"
}
```

人工审批通过（`approve.json` 内容 `{"approved":true}`）：

```bash
curl.exe -s -X POST http://localhost:8080/api/tickets/TMVRH8GMR/approve -H "Content-Type: application/json" --data-binary "@approve.json"
```

```json
{
    "ticketId":  "TMVRH8GMR",
    "status":  "CLOSED",
    "replyDraft":  "您好,关于实例 i-abc123 无法连接的问题:经诊断,实例操作系统疑似夯死导致远程连接无响应。重启实例属于变更操作,已提交人工审批,审批通过后将立即执行并同步结果;若处理后仍无法连接,我们将升级人工进一步排查系统盘与内核日志。给您带来不便敬请谅解。",
    "finalReply":  "",
    "pendingAction":  "restartInstance:i-demo"
}
```

审批后查询（GET 看到"已结单"）：

```bash
curl.exe -s http://localhost:8080/api/tickets/TMVRH8GMR
```

```json
{
    "ticketId":  "TMVRH8GMR",
    "status":  "CLOSED",
    "rawMessage":  "服务器连不上了，帮我重启一下实例 i-abc123"
}
```

resume 续跑执行链路的服务端审计日志（证明 restartInstance 真实走完 白名单→审批门禁→幂等→提交→真实轮询验证）：

```text
[graph] approval 读取决策 ticketId=TMVRQRXDP approved=true
[AUDIT] ticketId=TMVRQRXDP action=restartInstance:APPROVAL-TOKEN params=令牌校验通过(动作=restartInstance,剩余有效期=599s) target=i-abc123
[AUDIT] ticketId=TMVRQRXDP action=restartInstance params=target=i-abc123 approval=令牌已验证 risk=WRITE
[AUDIT] ticketId=TMVRQRXDP action=restartInstance:VERIFY params=通过:第3次轮询观测到状态 RUNNING,耗时 5s
```

路由：`triage(故障) → diagnosis(报告含"重启") → reply → 需审批动作 → approval 中断 → approve/resume → execute → followup 结单`。

### 剧本 3：故障 → 诊断 → 免审批动作直接执行 → 结单

请求（`body3.json`）：

```bash
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "Content-Type: application/json" --data-binary "@body3.json"
```

```json
{"message":"帮安全组 sg-demo 开放 443 端口"}
```

实际响应（工单 `TMVRH8GPE`，`authorizeSecurityGroup` 免审批直接执行后结单，`finalReply` 附带处理结果）：

```json
{
    "ticketId":  "TMVRH8GPE",
    "status":  "CLOSED",
    "replyDraft":  "您好,关于 443 端口访问失败的问题:经诊断,安全组 sg-demo 入方向未放行 TCP 443。该配置变更风险低且可逆,系统将自动为您添加 443 端口放行规则,完成后请复测 HTTPS 访问;若仍有异常请回复本工单,我们将继续排查服务监听配置。",
    "finalReply":  "您好,关于 443 端口访问失败的问题:经诊断,安全组 sg-demo 入方向未放行 TCP 443。该配置变更风险低且可逆,系统将自动为您添加 443 端口放行规则,完成后请复测 HTTPS 访问;若仍有异常请回复本工单,我们将继续排查服务监听配置。\n\n处理结果:提交:已向安全组 sg-demo 添加规则:放行 TCP 443 | 验证:外部探测 localhost:57138 连通成功(第2次尝试)",
    "pendingAction":  "authorizeSecurityGroup:sg-demo:port=443"
}
```

服务端审计日志：

```text
[AUDIT] ticketId=TMVRH8GPE action=authorizeSecurityGroup params=target=sg-demo approved=true risk=WRITE
```

路由：`triage(故障) → diagnosis(报告含"安全组") → reply → 免审批动作 → execute → followup 结单`。

### offline 模式做了什么

- **ChatModel 桩**（`com.dolphin.ticket.offline.ScriptedChatModel`，`@Profile("offline") + @Primary`）：在最底层替换模型输出，按系统提示词特征识别分诊/诊断/问答/回复四类 Agent 调用并返回脚本化文本；真实的 ChatClient 流式 API、graph 编排、审批中断/resume、动作执行器管线全部原样走通。
- **EmbeddingModel 桩**（`HashEmbeddingModel`）：文本 hash 派生确定性 64 维单位向量，SimpleVectorStore 的写入/检索完全离线。
- **profile 隔离**（`OfflineModelConfig` + `application-offline.yml`）：`application-offline.yml` 仅为兜底覆盖 `spring.ai.openai` 的 `api-key`/`base-url` 占位符解析，桩 Bean 只在 offline profile 下注册。
- **切换真实模型**：不激活 offline profile，配置 `DOLPHIN_API_KEY`（及可选 `DOLPHIN_BASE_URL`/`DOLPHIN_MODEL`）环境变量即可，业务代码零改动。
- **结果验证基线（受理≠完成）**：写操作提交前由 `ResultVerifier.capture` 采样基线（实例重启序号 / 安全组规则版本 / 退款旧流水号），提交后必须观测到基线之后的变化才判通过；只看"变更前旧 RUNNING、旧规则、旧流水"不会误判成功。审计可见 `:BASELINE` 与 `:VERIFY` 两条留痕，例如：
  ```text
  [AUDIT] action=restartInstance:BASELINE params=restartGeneration=0 ruleVersion=0 refundSerial=无 target=i-abc123
  [AUDIT] action=restartInstance:VERIFY params=通过:第3次轮询观测到本次重启(序号 0→1)后状态 RUNNING,耗时 5s
  ```

已知限制：CompiledGraph 单例共享模板 state 的串扰是骨架期 TODO（见 `TicketGraphConfig` 类注释）；`TicketController.submit` 在 invoke 输入中显式清空 `pendingAction`/`finalReply` 残值，保证连续提交多张工单时路由与状态判定不被上一单污染。

## P1 功能补全（告警闭环 / RAG 拼接 / 异步质检 / 人工池）

### 告警自动诊断闭环（M2）

告警链路从"接收-去重-留痕"补全为 **告警受理 → alert_task 匹配 → Agent 诊断 → 钉钉通知**：

- **匹配**（`AlertTaskMatcher` + `dolphin.alert.tasks` 配置）：按 `alertname`（+可选 labels 子集）命中任务，`context-template` 中的 `{uid}`/`{model}` 等占位符由告警 labels 渲染，缺失渲染为 `?`。默认配置对接 `deploy/prometheus/alert-rules.yml` 的 `ModelHttp5xxRateHigh`/`ModelThrottled` 两条规则。
- **诊断**：直调 `DiagnosisAgent`（不走工单图，规避单例 CompiledGraph 串扰风险），在 `alert-diagnosis` 守护线程池异步执行，webhook 立即返回受理计数。
- **通知**（`DingTalkNotifier`）：配置 `DOLPHIN_DINGTALK_WEBHOOK_URL`/`DOLPHIN_DINGTALK_SECRET`（加签）即真实推送 markdown；**未配置自动降级为审计日志**，offline 演示无需任何外部依赖。诊断中的动作建议在通知里显式标注"仅供人工决策"，告警侧不自动执行写操作。
- **查看**：`GET /api/alert/diagnoses?limit=20` 返回诊断记录（报告/动作建议/通知结果），`GET /api/alert/events` 查看受理的告警事件。

手动触发一条告警诊断（无需 Prometheus 栈）：

```bash
curl -X POST http://localhost:8080/api/alert/webhook -H "Content-Type: application/json" -d '{
  "alerts": [{"status":"firing","labels":{"alertname":"ModelHttp5xxRateHigh","uid":"u-demo","model":"qwen-plus","severity":"critical"},"annotations":{"summary":"模型 5xx 错误率突增"}}]}'
# → {"accepted":1,"diagnosisScheduled":1,...}  随后 GET /api/alert/diagnoses 查看诊断结论
```

### 咨询问答接入 RAG 检索

`kbAnswer` 节点检索的知识片段（`[来源:docId] 内容`）现拼入 user 消息喂给模型，与 `RagQaHandler` 同约定：召回为空显式标注"知识库暂无召回内容"；提示词要求片段已覆盖的严格按片段作答、未覆盖才用产品知识补充，抑制幻觉。结单结论仍回流向量库，形成检索素材闭环。

### 结单后异步质检

结单时在独立线程池异步触发 `QualityAgent` 审查终稿（`VERDICT:PASS/REJECT` 契约行）：REJECT → 工单重开 `ESCALATED` 交人工复核并审计留痕；契约行缺失/多行视为输出不可信，只留痕不重开（质检解析故障不惩罚正常结单）。审计可见 `quality-review` 事件。

### 人工工单池

`escalateHuman` 节点（执行验证失败 / 建议不可信 / 高危超范围）不再返回占位文案，而是落队 `HumanTicketPool` 并在回复中给出升级原因：

```bash
GET  /api/human-tickets?status=WAITING      # 待办列表(新→旧)
POST /api/human-tickets/{ticketId}/claim    # 认领 {"owner":"专员A"} → CLAIMED
POST /api/human-tickets/{ticketId}/resolve  # 完结 {"note":"已恢复"} → RESOLVED 且工单转 CLOSED
```

非法状态迁移（未认领直接完结、重复认领）返回 409；任务不存在返回 404。池为内存实现，TODO: 持久化与真实人工系统对接。
