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

### 鉴权（租户模型）：所有 /api/tickets 请求须携带 X-API-Key

启动时自动灌入两个演示租户（生产环境由客户入驻流程发钥，demo key 仅限离线演示）：

| 客户 | API-Key |
|---|---|
| 海豚云客户A | `ak-demo-cust-001` |
| 海豚云客户B | `ak-demo-cust-002` |

无 Key / 错误 Key / 已吊销 Key 统一返回 `401`（不区分文案，避免 key 探测）；工单按客户归属隔离：**A 查不到 B 的工单（404 不泄露存在性）**，列表接口只看本客户。以下剧本均以客户A 的 Key 发起。

```bash
# 无 Key 访问被拒:
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "Content-Type: application/json" --data-binary "@body1.json"
# → {"error":"认证失败:请提供有效的 X-API-Key"}   (HTTP 401)

# 客户A 提交(带 Key),随后客户B 查不到这条单:
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@body1.json"
# → {"ticketId":"T...","status":"CLOSED",...}
curl.exe -s http://localhost:8080/api/tickets/{ticketId} -H "X-API-Key: ak-demo-cust-002"
# → {"error":"工单不存在: T..."}   (HTTP 404,租户隔离生效)

# 客户A 看自己的工单列表(新→旧,可按状态过滤):
curl.exe -s "http://localhost:8080/api/tickets?limit=20" -H "X-API-Key: ak-demo-cust-001"
# → {"tickets":[{"ticketId":"T...","status":"CLOSED"},...],"count":N}
```

说明：告警 webhook、人工工单池、指标等运营侧接口位于内部网络边界，不走客户鉴权。

### 剧本 1：咨询 → 知识问答 → 直接结单

请求（`body1.json`）：

```bash
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@body1.json"
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
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@body2.json"
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
curl.exe -s http://localhost:8080/api/tickets/TMVRH8GMR -H "X-API-Key: ak-demo-cust-001"
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
curl.exe -s -X POST http://localhost:8080/api/tickets/TMVRH8GMR/approve -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@approve.json"
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
curl.exe -s http://localhost:8080/api/tickets/TMVRH8GMR -H "X-API-Key: ak-demo-cust-001"
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
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@body3.json"
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

### 剧本 4：运营指标——上线效果可量化

运营指标 API 从工单台账/审批决策/人工池/审计日志聚合（运营侧接口，不走客户鉴权）：

```bash
GET /api/metrics/summary                 # 全量汇总
GET /api/metrics/tickets?from=&to=       # 时间窗口报表(from/to 为 ISO-8601,缺省为全量)
```

口径：**自动化结单率** = CLOSED 且无人工池/审批介入 ÷ 总量；**人工升级率** = ESCALATED 或进过人工池 ÷ 总量；**审批触发率** = 待审批或已有决策 ÷ 总量；**质检通过率** = PASS ÷ (PASS+REJECTED)，契约无效不计分母；**MTTR** = 结单时间戳 - 创建时间的均值/中位数（质检驳回重开会清空结单时间、再次结单重新计时）。

复现：全新数据目录起服，以客户A 的 Key 提交 5 条工单——3 条咨询（自动结单）、1 条安全组 443（免审批直执结单）、1 条重启（审批通过后结单，approve 比提交晚了约半分钟，拉高了均值 MTTR）：

```bash
curl.exe -s http://localhost:8080/api/metrics/summary
```

```json
{
    "generatedAt":  "2026-11-03T09:28:46.832232Z",
    "totalTickets":  5,
    "byStatus":  { "CLOSED":  5 },
    "closedTickets":  5,
    "automatedClosedTickets":  4,
    "automationRatePct":  80.0,
    "humanEscalatedTickets":  0,
    "escalationRatePct":  0.0,
    "approvalTickets":  1,
    "approvalRatePct":  20.0,
    "quality":  {
                    "reviewed":  5, "passed":  5, "rejected":  0,
                    "contractInvalid":  0, "passRatePct":  100.0
                },
    "mttr":  { "sampleSize":  5, "meanSeconds":  7.5, "medianSeconds":  0.1 }
}
```

手工复核：5 单全部结单；4 单全程无人介入（3 咨询 + 1 免审批直执）→ 自动化 4/5=80%；仅重启单走审批 → 20%；无转人工 → 0%；5 次异步质检全 PASS；MTTR 中位数 0.1s（自动闭环即时结单），均值被审批等待时间拉高到 7.5s。

```bash
curl.exe -s "http://localhost:8080/api/metrics/tickets?from=2026-11-03T00:00:00Z"
```

```json
{
    "from":  "2026-11-03T00:00:00Z",
    "to":  "2026-11-03T09:29:52.324983700Z",
    "createdTickets":  5,
    "byStatus":  { "CLOSED":  5 },
    "byPriority":  { "P3":  5 },
    "daily":  [ { "date":  "2026-11-03", "created":  5, "closed":  5 } ],
    "closedTickets":  5,
    "mttr":  { "sampleSize":  5, "meanSeconds":  7.5, "medianSeconds":  0.1 }
}
```

（该输出录制于阶段 3 之前，`byPriority` 全为默认 P3；分诊接入 P1~P4 后，`summary` 会额外返回 `byPriority` 与 `sla` 字段（见剧本 5），此剧本复现时 3 条咨询为 P4、安全组为 P2、重启为 P1。非法时间参数返回 400。）

路由：`GET /api/metrics/** → MetricsService 聚合（JPQL 下推 count/group by + 审计事件分类）`。

### 剧本 5：SLA 优先级与超时自动升级——P1 单卡住 1 分钟，系统自己拉人

优先级为**确定性规则推导**（`SlaPriorityRules`，不依赖模型自觉）：分诊为**故障**且原文含宕机类信号（连不上/宕机/无法连接/不可用等）→ **P1**；普通故障 → **P2**；咨询 → **P4**；高危/超范围等其余类型及未分级老数据 → **P3** 兜底。各级别配双计时器（`dolphin.sla.response/resolve`，响应=产出客户可见回复、解决=CLOSED），`SlaMonitor` 周期巡检；超时一次性动作：落 `ticket.sla_breach_at` 留痕 → 未入池则升级人工池 → 审计 `sla-breach` → 钉钉加急（webhook 未配置自动降级日志，不中断流程）。已留痕工单后续扫描跳过（不重复加急）；已在池中被认领的只加急、不重置处理进度；超时升级只拉人不改工单状态，不阻断审批续跑。

复现（把 P1 解决时限调成 1 分钟、巡检 10 秒，全新数据目录起服）：

```bash
java -jar target/dolphin-0.0.1-SNAPSHOT.jar --spring.profiles.active=offline --dolphin.sla.resolve.P1=1m --dolphin.sla.scan-interval=10s --dolphin.sla.scan-initial-delay=10s
```

提交 P1 工单（`body2.json`，含"连不上"宕机信号 → 故障 + P1，重启动作需审批）后**故意不审批**，等约 1 分钟：

```bash
curl.exe -s -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@body2.json"
# → {"ticketId":"TMW1FPHNH1","status":"PENDING_APPROVAL","pendingAction":"restartInstance:i-abc123",...}
```

服务端日志（提交后整 1 分钟，巡检线程 `scheduling-1`）：

```text
09:24:54 [graph] triage 结果: {product=ECS, urgency=高, type=故障, priority=P1} priority=P1
09:26:02 [AUDIT] ticketId=TMW1FPHNH1 action=human-pool params=入队 reason=SLA超时:RESOLVE(优先级P1,已耗时1分钟,时限1分钟)
09:26:02 [AUDIT] ticketId=TMW1FPHNH1 action=sla-breach params=SLA超时:RESOLVE(优先级P1,已耗时1分钟,时限1分钟) 已升级人工池
09:26:02 [alert-notify] 未配置钉钉 webhook,降级日志通知。title=[SLA加急] 工单 TMW1FPHNH1
### [SLA加急] SLA超时:RESOLVE(优先级P1,已耗时1分钟,时限1分钟)
- 工单号:TMW1FPHNH1  - 优先级:P1  - 状态:PENDING_APPROVAL
- 原始诉求:ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复
请尽快认领处理(POST /api/human-tickets/TMW1FPHNH1/claim)。
09:26:02 [sla] SLA超时:RESOLVE(优先级P1,已耗时1分钟,时限1分钟) ticketId=TMW1FPHNH1 priority=P1 age=1m notified=false
```

人工池（运营接口）已出现该单，`reason` 即超时口径：

```bash
curl.exe -s "http://localhost:8080/api/human-tickets?status=WAITING"
```

```json
{
    "total":  1,
    "tasks":  [
                  {
                      "ticketId":  "TMW1FPHNH1",
                      "reason":  "SLA超时:RESOLVE(优先级P1,已耗时1分钟,时限1分钟)",
                      "detail":  "原始诉求:ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复",
                      "status":  "WAITING",
                      "owner":  "",
                      "createdAt":  "2026-11-04T01:26:02.893124Z",
                      "updatedAt":  "2026-11-04T01:26:02.893124Z"
                  }
              ]
}
```

指标接口同步反映（阶段 3 新增 `byPriority` 与 `sla`，SLA 达成率 = (总量 - 超时留痕) ÷ 总量）：

```bash
curl.exe -s http://localhost:8080/api/metrics/summary
```

```json
{
    "generatedAt":  "2026-11-04T01:26:19.690608500Z",
    "totalTickets":  1,
    "byStatus":  { "PENDING_APPROVAL":  1 },
    "byPriority":  { "P1":  1 },
    "closedTickets":  0,
    "automatedClosedTickets":  0,
    "automationRatePct":  0.0,
    "humanEscalatedTickets":  1,
    "escalationRatePct":  100.0,
    "approvalTickets":  1,
    "approvalRatePct":  100.0,
    "sla":  { "breachedTickets":  1, "complianceRatePct":  0.0 }
}
```

手工复核：1 单 P1 待审批未闭环，超 1 分钟解决时限 → SLA 达成率 (1-1)/1=0%；人工升级率 100%（被 SLA 拉进人工池）。此时仍可正常审批结单（升级只拉人不改状态）；人工池 `claim/resolve` 处理后工单转 CLOSED。

路由：`triage 节点 → SlaPriorityRules 推导 priority 落 ticket.priority → SlaMonitor(@Scheduled) 扫未闭环+未留痕工单 → 双计时器判定 → breach(留痕 + 入池 + 审计 + 加急)`。

### 剧本 6：工单多轮对话——PENDING 单基于上下文续跑，客户无需重复背景

真实客服场景里客户很少一次说清诉求。多轮对话（阶段 4）允许客户在同一工单下追问：对话历史（`ticket_message` 表）注入图状态，分诊/诊断/问答/回复各 Agent 均能看到此前沟通，按**最新上下文从头重跑**——客户上轮说"连不上"，本轮改口"其实是安全组没放行 443"，系统按本轮诉求改道（不再卡在上一轮的重启审批断点）。

第 1 轮：提交故障单，诊断出需审批的重启动作 → 中断在审批前，系统回复草稿作为首次答复入线程：

```bash
curl.exe -s --noproxy "*" -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@round1.json"
# round1.json: {"message":"我的ECS实例连不上了,ssh 都登不上"}
```

```json
{
    "ticketId":  "TMW1IC0QD1",
    "status":  "PENDING_APPROVAL",
    "replyDraft":  "您好,关于实例 i-abc123 无法连接的问题:经诊断,实例操作系统疑似夯死导致远程连接无响应。重启实例属于变更操作,已提交人工审批,审批通过后将立即执行并同步结果;若处理后仍无法连接,我们将升级人工进一步排查系统盘与内核日志。给您带来不便敬请谅解。",
    "finalReply":  null,
    "pendingAction":  "restartInstance:i-abc123"
}
```

第 2 轮：客户追问改口 → 先清旧 checkpoint（reply 语义是"从头重跑"而非"续跑旧断点"），再把对话历史注入图状态重新评估 → 本轮命中免审批的安全组放行动作，直执+基线验证后结单：

```bash
curl.exe -s --noproxy "*" -X POST http://localhost:8080/api/tickets/TMW1IC0QD1/reply -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" --data-binary "@round2.json"
# round2.json: {"message":"其实是安全组没放行443端口,帮我开一下"}
```

```json
{
    "ticketId":  "TMW1IC0QD1",
    "status":  "CLOSED",
    "replyDraft":  "您好,关于 443 端口访问失败的问题:经诊断,安全组 sg-demo 入方向未放行 TCP 443。……",
    "finalReply":  "您好,关于 443 端口访问失败的问题:……\n\n处理结果:提交:已向安全组 sg-demo 添加规则:放行 TCP 443 | 验证:外部探测 localhost:63560 连通成功(第2次尝试,观测到本次新规则版本 0→1)",
    "pendingAction":  "authorizeSecurityGroup:sg-demo:port=443",
    "rounds":  4
}
```

关键点：第 2 轮的 finalReply 是围绕**本轮**安全组诉求（含真实执行与验证结果），而不是沿用上轮的"重启实例"话术——对话历史只是上下文参考，最新诉求优先。

全程线程可回看（客户/系统消息按序交替，租户隔离）：

```bash
curl.exe -s --noproxy "*" http://localhost:8080/api/tickets/TMW1IC0QD1/messages -H "X-API-Key: ak-demo-cust-001"
```

```json
{
    "ticketId":  "TMW1IC0QD1",
    "ticketStatus":  "CLOSED",
    "count":  4,
    "messages":  [
        { "role":  "USER",  "content":  "我的ECS实例连不上了,ssh 都登不上", "createdAt":  "2026-11-04T02:38:25.025272Z" },
        { "role":  "AGENT", "content":  "您好,关于实例 i-abc123 无法连接的问题:……已提交人工审批……", "createdAt":  "2026-11-04T02:38:25.163847Z" },
        { "role":  "USER",  "content":  "其实是安全组没放行443端口,帮我开一下", "createdAt":  "2026-11-04T02:38:37.539320Z" },
        { "role":  "AGENT", "content":  "您好,关于 443 端口访问失败的问题:……验证:外部探测 localhost:63560 连通成功(第2次尝试,观测到本次新规则版本 0→1)", "createdAt":  "2026-11-04T02:38:39.637163Z" }
    ]
}
```

状态约束：`CLOSED` 已结单拒绝追问（HTTP 409，如需继续请重新提交）；`ESCALATED` 已转人工的单，追问只入线程供专员查看，AI 不再介入（返回 note 说明）；空消息 400；跨客户 reply/messages 一律 404。审计侧第 2 轮完整留痕：`authorizeSecurityGroup`（免审批）→ `:BASELINE`（ruleVersion=0）→ `:VERIFY`（观测到本次新规则版本 0→1）→ 异步质检 PASS。

路由：`reply 接口(归属校验/CLOSED 409/ESCALATED 只记录) → 清旧 checkpoint → 对话历史注入 CONVERSATION_HISTORY → 图从头重跑 → 终稿/草稿落线程`。

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

~~已知限制：CompiledGraph 单例共享模板 state 的串扰~~ 已在 **P0 根治**（每请求编译 + DB checkpoint，`submit` 清残值补丁已删除），见下方 [P0 章节](#p0-持久化--并发根治状态全落库--单例图串扰根治)。

## P0 持久化 + 并发根治（状态全落库 + 单例图串扰根治）

### 状态全落库（默认 H2 文件，零外部依赖）

所有原内存态 store 改为 JPA 落库，store 对外接口不变、调用方零改动。默认数据源 `jdbc:h2:file:./data/dolphin`（`data/` 已加入 `.gitignore`），表清单：

| 表 | 内容 |
|---|---|
| `ticket` | 工单台账 |
| `approval_decision` | 审批决策（留痕不清行，撤销仅置空 token） |
| `action_idempotency` | 写操作幂等（idem_key=`ticketId|action|target`） |
| `approval_token_nonce` | 令牌 nonce（唯一约束防重放，并发安全；`purge` 按过期时间删） |
| `human_task` | 人工工单池 |
| `graph_checkpoint` | 图执行快照（`node_id` + `next_node`，resume 从 `next_node` 续跑） |
| `alert_event` / `alert_diagnosis` / `audit_log` | 告警事件（按指纹 upsert）/ 诊断记录 / 审计日志 |

配套持久化：

- **向量库文件**（`dolphin.rag.vector-store-path`，默认 `./data/vector-store.json`）：ingest 后落盘、启动时加载，重启不重新调用 Embedding API；文件已存在则跳过种子灌入。
- **Checkpoint 落库**：`DbCheckpointSaver` 按 `threadId=工单号` 单行 upsert，重启后全新实例读同一库即可续跑（`JdbcCheckpointSaverTest` 验证往返/覆盖/重启语义）。

### 并发串扰根治

~~CompiledGraph 单例共享模板 state~~ → `TicketGraphCompiler` **每请求编译独立图**（CompileConfig 含 DB saver + approval 中断点），checkpoint 按工单号天然隔离；`submit` 的"清残值"与 `approve` 的"回灌"补丁已删除，invoke 输入只传 `TICKET_ID` + `RAW_MESSAGE`。并发回归 `TicketGraphConcurrencyTest`：双工单同时提交进审批中断 + 同时审批 resume，终态/回复/审批决策互不串扰。

### 重启验收剧本（offline，全程无外部依赖）

```bash
# 1. offline 启动后提交"重启"工单 → 诊断给出 restartInstance 建议 → 图在 approval 前中断
curl -X POST http://localhost:8080/api/tickets/submit -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" \
     -d '{"message":"ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复"}'
# → {"ticketId":"T...","status":"PENDING_APPROVAL",...}   记下 ticketId

# 2. 直接杀掉进程，重新启动（offline 模式同落库 ./data/dolphin）

# 3. 审批续跑：从 DB checkpoint 水合图状态，批准后自动走完 重启→验证→结单
curl -X POST http://localhost:8080/api/tickets/{ticketId}/approve -H "X-API-Key: ak-demo-cust-001" -H "Content-Type: application/json" \
     -d '{"approved":true,"approver":"ops","reason":"重启验收"}'
# → status:CLOSED，finalReply 含"已向云平台提交实例 i-abc123 … 处理结果"
```

重启后台账、幂等键、审批决策、审计日志均在 H2 文件中完好，续跑不重复执行写操作（幂等键拦截）。

### MySQL profile

生产切换：`-Dspring.profiles.active=mysql`，数据源从环境变量读取（业务代码零改动）：

```bash
DOLPHIN_DB_HOST=rds-xxx.mysql.rds.aliyuncs.com \
DOLPHIN_DB_NAME=dolphin \
DOLPHIN_DB_USER=dolphin \
DOLPHIN_DB_PASSWORD=*** \
java -jar dolphin.jar --spring.profiles.active=mysql
```

schema 仍由 Hibernate `ddl-auto: update` 管理，生产建议后续切换 Flyway 管 schema。

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

非法状态迁移（未认领直接完结、重复认领）返回 409；任务不存在返回 404。人工任务已落库（表 `human_task`，P0），重启不丢；TODO: 与真实人工系统对接。
