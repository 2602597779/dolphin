# Dolphin 企业化改造计划（断点续传文档）

> **用途**：多会话协作的进度凭证。每次会话开始时，先读本文件 + `git log --oneline` 确定断点，再继续执行。
> **规则**：
> 1. 完成一项就把状态改为 `[x]` 并更新"当前断点"章节
> 2. 每完成一个阶段必须 git commit（commit 信息带阶段号）
> 3. 中途被打断时，把正在做的事记在"当前断点"章节再退出
> 4. 验证命令必须真实跑过才能勾完成

---

## 项目背景（新会话速读）

Dolphin = 云服务工单多智能体系统（Spring Boot 3.5 + Spring AI 1.0 + Spring AI Alibaba Graph + JPA/H2）。
现状：多 Agent 图编排闭环、审批中断/resume、幂等/防重放/审计、状态全落库、告警诊断、RAG、质检、人工池均已实现，26 个测试类 153 用例全绿。
目标：从"技术真实的工具"升级为"业务真实的产品"，达到面试可讲、经得起追问的企业级形态。
技术深度线（代码质量）**已够用，不再投入**；剩余工作全部是业务真实感补全。

## 已知遗留硬伤（改造前顺手清理）

- [x] H0. ~~删除 `src/main/java/com/dolphin/test/LeetcodeTest.java`~~ **已撤销（2026-11-04 用户要求保留，从 git 历史还原）。后续会话禁止删除此文件**
- [x] H1. `.gitignore` 增加 `.trae/`
- [x] H2. 当前 P0 持久化改造未提交 → commit 进版本库（commit msg: "P0:状态全落库+并发串扰根治+离线演示链路"）
- [x] H3. ~~删除 `com/dolphin/test` 目录~~ **已撤销：同 H0，用户要求保留**

---

## 第一梯队（面试话术价值 ÷ 工作量最高，做完可停手）

### 阶段 1：客户/租户模型 + API-Key 鉴权（约 1~2 天）

**业务目标**：工单归属客户，客户只能看自己的单，跨租户访问返回 404/403。

任务拆解：
- [x] T1.1 `customer` 表 + `CustomerEntity` + `CustomerRepository`（字段：id、name、apiKey、enabled；启动种子 2 个客户）——`com/dolphin/customer` 包，`CustomerSeeder` 幂等灌入 cust-001/cust-002
- [x] T1.2 `ticket` 表加 `customer_id` 列；`TicketService.create` 带归属；实体加字段（Hibernate ddl-auto update 自动加列，老数据 null 不影响）
- [x] T1.3 `XApiKeyAuthFilter`（OncePerRequestFilter）：请求头 `X-API-Key` → 解析客户；仅 `/api/tickets/**` 强制鉴权；缺失/错误/已吊销统一 401 同文案（防探测）
- [x] T1.4 归属注入：请求体不携带身份字段，归属只从鉴权上下文（API-Key 对应客户）取，无法伪造；告警 webhook 等运营路径不受影响（实现比原计划更干净）
- [x] T1.5 `GET /api/tickets/{id}` 做归属校验：非本客户的单返回 404（不泄露存在性）；approve 接口同样校验
- [x] T1.6 列表接口 `GET /api/tickets?status=&limit=`（只看本客户，新→旧，limit 钳制 1~100）
- [x] T1.7 单测：`TenantIsolationTest` 9 用例（401/文案一致/注入归属/非工单路径放行/吊销/互相不可见/状态过滤/内部渠道全量/Controller 归属校验）
- [x] T1.8 README 剧本更新：双客户鉴权说明 + 两个 API-Key 互相看不见对方的单
- [x] T1.9 测试全绿后 commit（msg: "阶段1:客户/租户模型+API-Key鉴权+工单归属隔离"）——`mvn test` 162 用例 0 失败（原 153 + 新增 9，无回归）

**完成标准**：两个不同 API-Key 的客户无法读取对方工单；`mvn test` 全绿；README 有双客户剧本。

### 阶段 2：运营指标 API（约 1 天）

**业务目标**：上线效果可量化，面试能说出"自动化解决率 X%、MTTR Y 分钟"且数字来自系统本身。

任务拆解：
- [x] T2.1 `MetricsService`：基于 `ticket` 表聚合——工单总量、自动化结单率（CLOSED 且未经人工池/审批）、人工升级率（进过 ESCALATED/human_task）、审批触发率、质检通过率（audit_log 的 quality-review 事件）——跨表判定用 JPQL 下推 SQL
- [x] T2.2 `ticket` 表补 `created_at`/`closed_at`/`priority` 时间戳字段（为 MTTR/SLA 用）——`updateStatus` 结单落 `closed_at` 戳、重开清空、重复结单保持首戳；`priority` 默认 P3，老数据 null 聚合时按 P3 兜底
- [x] T2.3 MTTR：CLOSED 工单的 closed_at - created_at 中位数/均值（缺结单时间戳的老数据不参与，样本数单列）
- [x] T2.4 `GET /api/metrics/summary` 返回全部指标；`GET /api/metrics/tickets?from=&to=` 支持时间窗口（byStatus/byPriority/按日创建结单曲线/窗口内 MTTR；from 晚于 to 返回 422，非法时刻 400）
- [x] T2.5 单测：指标计算正确性（造数据断言数字）——`MetricsServiceTest` 7 用例（全量口径/空库零除/质检驳回计入升级率/窗口过滤+按日序列/缺省全量/结单戳落戳清空/老数据不进 MTTR）
- [x] T2.6 README 增加指标示例输出；commit（msg: "阶段2:运营指标API(MTTR/自动化解决率/升级率/质检通过率)"）——README 剧本 4 已写入真实运行输出（5 单：80% 自动化/20% 审批/质检 5/5/MTTR 均值 7.5s 中位 0.1s），可手工复核；代码由 commit `8557cc8` 入库，README+本文件以阶段 2 msg 补交

**完成标准**：造 5 条工单数据后，指标接口的数字能用 README 剧本手工复核。✅ 已达成（3 咨询 + 1 安全组直执 + 1 重启审批，summary 每个数字与剧本一一对应）

### 阶段 3：SLA 优先级 + 超时升级（约 1~2 天）

**业务目标**：P1~P4 优先级 + 响应/解决双计时器，超时自动升级人工池并加急通知。

任务拆解：
- [x] T3.1 分诊节点输出优先级——`SlaPriorityRules` 确定性规则推导（不依赖模型自觉）：故障+宕机信号（连不上/宕机/无法连接/不可用等）=P1、普通故障=P2、咨询=P4、其余及未分级=P3 兜底；triage 节点随分诊即时落 `ticket.priority`
- [x] T3.2 `dolphin.sla` 配置——`SlaProperties`（response/resolve 双 Map）+ application.yml（P1 5m/30m ~ P4 240m/24h + scan-interval/scan-initial-delay 可调）
- [x] T3.3 `SlaMonitor`（`@Scheduled` 巡检）：NEW/RUNNING 超响应时限或未 CLOSED 超解决时限 → 落 `sla_breach_at` 留痕 + 升级 `HumanTicketPool` + 审计 `sla-breach` + 钉钉加急（未配 webhook 自动降级日志）；留痕后跳过（幂等）、已认领不重置、不改工单状态不阻断审批续跑
- [x] T3.4 指标 API 补充——`summary` 新增 `byPriority`（coalesce 兜底 P3）与 `sla`（达成率=(总量-超时留痕)÷总量）
- [x] T3.5 单测——`SlaMonitorTest` 7 用例（@DataJpaTest+固定时钟+秒级时限：双计时器/超时升级/留痕幂等/认领不重置/CLOSED 免追责/null 兜底 P3）+ `SlaPriorityRulesTest` 5 用例（规则矩阵）+ `MetricsServiceTest` 扩展
- [x] T3.6 README 剧本 5 已写入真实运行输出（P1 单 1 分钟不审批 → 巡检自动入池+加急+留痕，指标同步反映）；本会话独立复跑验证通过；commit（msg: "阶段3:SLA优先级+超时自动升级人工池"）

**完成标准**：造一条 P1 工单，把时限配成 1 分钟，观察其被自动升级进人工池且有留痕。✅ 已达成（2026-11-04 独立复验：提交 09:37:31 P1 待审批 → 09:38:40 被巡检 breach，人工池 WAITING + 审计 2 条 + 加急降级日志，后续多个扫描周期无重复；`mvn test` 181 用例 0 失败）

## 第二梯队（有余力再做，不影响主线）

- [x] 阶段 4：工单多轮对话 `POST /api/tickets/{id}/reply`（对话历史进图状态，PENDING 单基于上下文续跑）——业务真实感最大单点
- [ ] 阶段 5：钉钉卡片审批（审批人在钉钉点通过 → webhook 回调 resume），可截图讲的落地场景
- [ ] 阶段 6：运营看板简单页面（消费指标 API）
- [ ] 阶段 7：GitHub Actions CI（mvn test）
- [ ] 阶段 8：Flyway 管 schema（替换 ddl-auto: update）

### 阶段 4：工单多轮对话（约 1~2 天）

**业务目标**：客户在同一工单下追问，对话历史注入图状态，PENDING 单基于上下文续跑，客户无需重复背景。

任务拆解：
- [x] T4.1 `ticket_message` 表 + `TicketMessageEntity` + `TicketMessageRepository`（ticket_id 索引）；`ConversationService`：追加客户/系统消息（空系统回复不落）、按序取线程、历史转录（"客户: "/"客服: "逐行，尾部 6000 字符截断防撑爆提示词）
- [x] T4.2 `TicketState` 新增 `CONVERSATION_HISTORY` 状态键（ReplaceStrategy）；diagnosis/kb/reply 三 Agent 签名加 history 参数并拼入 user 提示词（空历史不加段落）；告警链路传空串
- [x] T4.3 `submit` 首条诉求入线程、结单终稿/待审批草稿入线程；`approve` 结单终稿入线程
- [x] T4.4 `POST /api/tickets/{id}/reply`：归属校验（跨客户 404）→ CLOSED 409 → 先取历史再落本轮消息 → ESCALATED 只记录不重跑（note 说明）→ 清旧 checkpoint（reply 是"从头重跑"而非 resume 旧断点）→ 历史注入图状态 invoke → 状态推进与终稿/草稿入线程；`GET /api/tickets/{id}/messages` 线程查询（租户隔离）
- [x] T4.5 离线演示多轮感知：`ScriptedChatModel` 分诊/诊断/回复均"本轮诉求优先"（取"对话历史"标记前的本轮文本判定，本轮无特征回退全文），避免历史旧关键词压过本轮改口
- [x] T4.6 单测——`ConversationServiceTest` 6 用例（线程排序/空系统回复跳过/空线程/转录格式/尾部截断/线程隔离）+ `MultiTurnConversationTest` 5 用例（PENDING 单改口重跑结单+线程 4 条完整/CLOSED 409/ESCALATED 只记录/空消息 400/跨客户 404+线程租户隔离）+ `KbAnswerAgentTest` 补历史拼接用例
- [x] T4.7 README 剧本 6 已写入真实运行输出（第 1 轮重启审批中断 → 第 2 轮改口安全组从头重跑直执结单，含基线验证与线程回看）；`mvn test` 193 用例 0 失败；commit（msg: "阶段4:工单多轮对话(对话历史进图状态+PENDING单上下文续跑)"）

**完成标准**：PENDING_APPROVAL 单追问改口后按本轮诉求从头重跑结单；线程全程可回看；`mvn test` 全绿。✅ 已达成（2026-11-04 真实运行复验：工单 TMW1IC0QD1，第 1 轮 restartInstance 审批中断 → 第 2 轮改口安全组 → authorizeSecurityGroup 免审批直执 + 规则版本 0→1 基线验证 + 质检 PASS，rounds=4）

### 阶段 4 关键落点（面试话术/后续阶段引用）

- 数据模型：`ticket_message` 表一单一线程（USER/AGENT 角色 + 时间戳，ticket_id 索引）；`ConversationService.transcript` 渲染为"客户: /客服: "逐行转录，尾部 6000 字符截断（历史是上下文参考，最新沟通相关性最高）
- 核心语义：reply = **按最新上下文从头重跑**，不是续跑旧断点——旧 checkpoint 停留在上一轮中断点（如审批前），带着它 invoke 会直接 resume；故先 `checkpointStore.clear(threadId)` 再注入 `CONVERSATION_HISTORY` invoke。对比 approve = 基于原断点 resume
- 状态约束：CLOSED 409（结单后重新提交）、ESCALATED 追问只入线程供专员查看（AI 不再介入）、空消息 400、跨客户 404（复用 getForCustomer，不泄露存在性）
- Agent 侧：diagnosis/kb/reply 的 user 提示词均拼"对话历史"段落（空历史不加段落，首轮零影响）；离线桩"本轮诉求优先"判定（标记前文本命中剧本特征则按本轮，否则回退全文）
- 验证双保险：`MultiTurnConversationTest` 5 用例（offline+H2 直调 Controller）+ 真实运行复验（改口重跑 + 审计 :BASELINE/:VERIFY 留痕 + 质检 PASS，线程 4 条完整）

### 阶段 4 后续优化（阶段 4.1，已完成 2026-11-04）

> 详细任务清单（含代码证据、实现方案、验收标准、执行顺序）：`.trae/spec/p4-conversation-optimize/tasks.md`
> 该目录在 .gitignore 内不入库，故此处保留摘要；新会话优先读该文件，读不到则按下列摘要执行。

- [x] O1 reply 并发串行化：`TicketLockRegistry` 按 ticketId 加锁，把"读状态→判重→清断点→invoke→落库收尾"整段串行化；锁对象引用计数归零即移除。多实例需 DB 乐观锁/分布式锁，已写入类注释（`TicketLockRegistryTest` 3 用例 + 并发 reply 集成用例）
- [x] O2 人工完结答复入线程：`HumanTicketPool.resolve` 完结后 `appendAgent`（note 空则用默认文案），重复 resolve 不重复落消息（`HumanTicketPoolTest` +2 用例）
- [x] O3 审批作废留痕：reply 在 clear 前读旧 checkpoint 的 `PENDING_ACTION`，落审计 `approval-invalidated`（含原动作，取不到则按状态标记）；`TicketCheckpointStore.snapshotState` 新增（集成用例断言审计含 `restartInstance:i-abc123`）
- [x] O4 短事务与失败补偿：`TicketRoundService.beginRound/completeRound` 两个短事务；`graph.invoke` 保持在事务外；异常分支保留客户消息、补系统说明、转人工池、写 `reply-failed` 审计（`ReplyFailureCompensationTest` 1 用例）
- [x] O5 转录逐行截断：`ConversationService.tail` 按整行从尾部累加，单行超预算才截断并加 `…`；`ConversationServiceTest` 3 用例覆盖整行保留/单行截断/预算边界
- [x] O6 检索带上文：`TicketGraphConfig.retrievalQuery` = 历史尾部 300 字 + 本轮原文，历史为空回退纯原文（`TicketGraphRetrievalQueryTest` 2 用例）
- [x] O7 对话入口幂等：`ReplyRequest` 加可选 `requestId`；`ticket_message.client_msg_id` + `(ticket_id, client_msg_id)` 唯一约束；重复请求返回 `duplicated=true` 且不重复落消息（集成用例覆盖"首次结单后同号重试仍 200"）
- [x] O8 异步跑图（原计划只写口径，后于阶段 4.2 实际落地，见下节）
- [x] 收尾验证：`mvn test` 207 用例 0 失败（原 193 + 新增 14）；README 剧本 6 补阶段 4.1 加固说明；本文件勾选
- [x] 收尾 commit：本地 commit（msg: `阶段4优化:并发保护+人工回复入线程+审批作废留痕+短事务补偿`，**仅本地，未推远程**）

#### 阶段 4.2 O8 异步化落地（受理即 202 + 轮询/SSE）

- [x] 图执行线程池：`TicketAsyncConfig`（`dolphin.async.pool-size` 可配，固定池，Bean 类型 ExecutorService 随容器 shutdown）+ `TicketAsyncProperties`
- [x] 异步执行器：`TicketGraphRunner`——submit/reply/approve 三类轮次投递到池；图异常/收尾落库异常/线程池拒绝统一失败补偿（系统说明 + 转人工 + 审计），工单绝不留在 PROCESSING；收尾后发布 SSE 完成事件
- [x] 受理闸门替代单机锁：`TicketService.tryTransition` 条件状态翻转（原子条件 UPDATE，多实例安全）——并发受理只有一个赢家，输家 409；O1 的 `TicketLockRegistry` 随之移除（同单串行语义由闸门保证，消息按整轮交替）
- [x] Controller 受理即 202：submit/reply/approve 只做校验 + 闸门翻转 + 消息落库，响应含 poll/events 地址；队列满（仅进程关闭期）503 且已就地补偿转人工
- [x] 轮询接口：`GET /{id}/status` + `TicketStatusService` 统一视图（status/processing/done/finalReply/pendingAction/rounds；PENDING_APPROVAL 算本轮已有结论）
- [x] SSE：`GET /{id}/events` + `TicketEventPublisher`——连接即推 status 快照（晚连不丢信息），完成后推 result 事件并在终态结束连接；订阅生命周期回调摘除 + 显式摘除双保险
- [x] PROCESSING 超时兜底：SLA 响应计时器口径覆盖 PROCESSING（卡死场景由巡检升级人工池，与 Runner 失败补偿互补）
- [x] 验证：`mvn test` 225 用例 0 失败（原 207 + 新增 18：异步受理集成 6 + SSE 发布器 4 + 图执行器 8）；`AsyncTicketAcceptanceTest` 用闸门 Executor 把受理与图执行在时间上拉开，确定性验证 202/409 互斥/轮询口径/SSE 快照与 result 事件

#### 阶段 4.1 关键落点（面试话术）

- 并发：锁的临界区覆盖"读最新状态→requestId 判重→审批作废留痕→清 checkpoint→invoke→写线程/状态"，锁内二次读状态保证串行后的判断基于最新数据；锁 map 带引用计数避免按工单号无限堆积
- 幂等：先判 `(ticketId, requestId)` 再判 CLOSED——首次请求已结单后客户端重试同号应返回 `duplicated=true`（200），而不是误导性的 409；空 requestId 统一存 null，避免撞唯一约束
- 事务边界：只把"消息 + 状态"的短写收敛进事务，`graph.invoke`（云调用 + 退避轮询）始终在事务外；失败补偿不回滚客户消息（保留证据）
- 可观测：`approval-invalidated` 与 `reply-failed` 两条审计是面试可讲的可追溯性设计；`duplicated` 标记让客户端重试语义明确

---

## 叙事边界（面试话术，勿越界）

不说"生产运行中"；说法见仓库根 README 及本文件开头。技术亮点故事：①单例图串扰发现与根治；②受理≠完成的基线验证设计。

---

## 当前断点（每次会话结束必须更新）

**最后更新**：2026-11-04（第七次会话：阶段 4.2 O8 异步化落地）
**已完成**：H0~H3；**阶段 1~3 全部完成**（均已 commit）；**阶段 4 全部完成**（commit `f63a28c`）；**阶段 4.1 O1~O7 全部完成**（commit `c945eb8`：并发串行化、人工完结答复入线程、审批作废审计、短事务 + 失败补偿、转录逐行截断、检索带上文、入口幂等）；**阶段 4.2 O8 异步化全部完成**（受理即 202 + 受理闸门 `tryTransition` 替代单机锁 + `TicketGraphRunner` 异步执行/失败补偿 + 轮询 `/status` + SSE `/events` + 异步质检接线；`mvn test` 225 用例 0 失败 = 原 207 + 新增 18）
**下一步**：可停手；若继续，从**阶段 5 钉钉卡片审批**（webhook 回调 resume）开始
**git 状态**：阶段 4.2 全部改动（`TicketAsyncConfig`/`TicketAsyncProperties`/`TicketGraphRunner`/`TicketStatusService`/`TicketEventPublisher` 五个新类 + `TicketLockRegistry` 删除 + Controller 受理即 202 改造 + SlaMonitor PROCESSING 口径 + TicketFlowHandler 消费终态意图 + 3 个新测试类 + 4 个测试类适配 + 本文件）已本地 commit `b2e9e7f`（**仅本地未推远程**）；工作区干净

### 已提交的进度凭证（git log 摘要）

- `P0:状态全落库+并发串扰根治+离线演示链路`（H2 全部 P0 改造 + H0/H1/H3 清理顺带入库）
- `docs: 新增企业化改造计划(PLAN.md)断点续传文档`（本文件）
- `阶段1:客户/租户模型+API-Key鉴权+工单归属隔离`（customer 包 + ApiKeyAuthFilter + Ticket 归属/列表 + TenantIsolationTest 9 用例 + README 双客户剧本）
- `fix：dolohin`（阶段 2 全部代码：metrics 包 + TicketEntity closed_at/priority + 指标聚合查询 + MetricsServiceTest，由用户手动提交）
- `阶段2:运营指标API(MTTR/自动化解决率/升级率/质检通过率)`（README 剧本 4 真实输出 + 本文件勾选）
- `阶段3:SLA优先级+超时自动升级人工池`（sla 包 4 类 + triage 分级落库 + sla_breach_at 留痕 + 指标 API 扩展 + 12 用例 + README 剧本 5）
- `阶段4:工单多轮对话(对话历史进图状态+PENDING单上下文续跑)`（ticket_message 三件套 + reply/messages 接口 + Agent history 注入 + 12 用例 + README 剧本 6）
- `阶段4优化:并发保护+人工回复入线程+审批作废留痕+短事务补偿`（阶段 4.1 O1~O7：TicketLockRegistry 串行锁 + TicketRoundService 短事务 + requestId 幂等 + `approval-invalidated`/`reply-failed` 审计 + 逐行截断转录 + 检索带上文 + 人工完结回线程 + 14 新用例 + README 剧本 6 加固段）

### 阶段 1 关键落点（面试话术/后续阶段引用）

- 演示租户：cust-001（Key `ak-demo-cust-001`）/ cust-002（Key `ak-demo-cust-002`），`CustomerSeeder` 启动幂等灌入
- 鉴权：`X-API-Key` 头 → `ApiKeyAuthFilter`（仅拦 `/api/tickets/**`）→ request attribute 注入 customerId；缺失/错误/已吊销统一 401 同文案
- 隔离：`TicketService.getForCustomer/listForCustomer`；`ticket.customer_id=null` 表示内部渠道（告警自动开单/历史数据），内部直调全量可见
- 审批接口 `/api/tickets/{id}/approve` 同样做归属校验（他客户的单 404，不能代审）

### 阶段 2 关键落点（面试话术/后续阶段引用）

- 口径（README 剧本 4 与代码 javadoc 一致）：自动化结单率=CLOSED 且无人工池/审批 ÷ 总量；人工升级率=ESCALATED 或进过人工池 ÷ 总量；审批触发率=待审批或已有决策 ÷ 总量；质检通过率=PASS ÷ (PASS+REJECTED)（契约无效不计分母）；MTTR=结单戳-创建时间均值/中位数
- `ticket.closed_at`：`updateStatus` 结单落戳、重开清空、重复结单保持首戳；`priority` 默认 P3（阶段 3 接入分诊产出）
- `MetricsServiceTest`（@DataJpaTest）7 用例；端到端真实运行：5 单 → 80% 自动化/20% 审批/0% 升级/质检 5/5/MTTR 均值 7.5s 中位 0.1s，数字可手工复核

### 阶段 3 关键落点（面试话术/后续阶段引用）

- 分级：`SlaPriorityRules.derive(type, rawMessage)` 纯函数规则表——故障+宕机信号=P1 / 普通故障=P2 / 咨询=P4 / 其余=P3；triage 节点随分诊即时落 `ticket.priority`，可审计可复现，离线演示同样生效
- 计时器：`dolphin.sla.response/resolve` 双 Map 配置，级别缺失取 P3 档兜底；响应=卡 NEW/RUNNING 未产出回复、解决=未 CLOSED（含待审批/转人工）
- 巡检：`SlaMonitor.scan()` `@Scheduled`（周期/首延迟可配），输入=`findByStatusNotAndSlaBreachAtIsNull`；超时一次性动作：落 `sla_breach_at` → 未入池则入队 → 审计 `sla-breach` → 钉钉 markdown 加急（未配 webhook 降级日志）；设计要点：只拉人兜底不改工单状态，审批中断单仍可 approve 续跑结单
- 指标：`summary` 新增 `byPriority`（JPQL `coalesce(priority,'P3')` 聚合）与 `sla{breachedTickets, complianceRatePct}`
- 验证双保险：`SlaMonitorTest` 固定时钟秒级时限确定性断言（7 用例）+ 2026-11-04 真实复跑（P1 单 1 分钟不审批 → 巡检线程自动 breach 入池加急，多扫描周期无重复，日志/人工池/指标三处一致）

### 会话恢复指引（新会话照此操作）

1. 读本文件"当前断点"章节 + 跑 `git log --oneline -10` + `git status --short`
2. 找到状态为 `[ ]` 的最靠前任务，按其"完成标准"实现
3. 做完勾选、更新本章节、commit
4. 若任务做一半被打断：把已完成的部分 + 剩余步骤写在下面"进行中备忘"里

### 进行中备忘

（空）

