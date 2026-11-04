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

- [ ] 阶段 4：工单多轮对话 `POST /api/tickets/{id}/reply`（对话历史进图状态，PENDING 单基于上下文续跑）——业务真实感最大单点
- [ ] 阶段 5：钉钉卡片审批（审批人在钉钉点通过 → webhook 回调 resume），可截图讲的落地场景
- [ ] 阶段 6：运营看板简单页面（消费指标 API）
- [ ] 阶段 7：GitHub Actions CI（mvn test）
- [ ] 阶段 8：Flyway 管 schema（替换 ddl-auto: update）

## 叙事边界（面试话术，勿越界）

不说"生产运行中"；说法见仓库根 README 及本文件开头。技术亮点故事：①单例图串扰发现与根治；②受理≠完成的基线验证设计。

---

## 当前断点（每次会话结束必须更新）

**最后更新**：2026-11-04（第四次会话收尾）
**已完成**：H0~H3；**阶段 1 全部完成**（已 commit）；**阶段 2 全部完成**（已 commit）；**阶段 3 全部完成**（SLA：SlaPriorityRules 确定性分级 + SlaMonitor 双计时器巡检 + sla_breach_at 留痕/幂等升级人工池/钉钉加急降级 + 指标 API byPriority/sla 字段 + 12 个新用例 + README 剧本 5 真实输出，`mvn test` 181 用例 0 失败）
**下一步**：第一梯队（阶段 1~3）全部收口，可停手；若继续，从第二梯队**阶段 4 工单多轮对话**（`POST /api/tickets/{id}/reply`，业务真实感最大单点）开始
**git 状态**：阶段 3 全部改动（sla 包 + ticket/metrics 接线 + application.yml + README + 本文件）以阶段 3 msg 入库后，工作区应保持干净

### 已提交的进度凭证（git log 摘要）

- `P0:状态全落库+并发串扰根治+离线演示链路`（H2 全部 P0 改造 + H0/H1/H3 清理顺带入库）
- `docs: 新增企业化改造计划(PLAN.md)断点续传文档`（本文件）
- `阶段1:客户/租户模型+API-Key鉴权+工单归属隔离`（customer 包 + ApiKeyAuthFilter + Ticket 归属/列表 + TenantIsolationTest 9 用例 + README 双客户剧本）
- `fix：dolohin`（阶段 2 全部代码：metrics 包 + TicketEntity closed_at/priority + 指标聚合查询 + MetricsServiceTest，由用户手动提交）
- `阶段2:运营指标API(MTTR/自动化解决率/升级率/质检通过率)`（README 剧本 4 真实输出 + 本文件勾选）
- `阶段3:SLA优先级+超时自动升级人工池`（sla 包 4 类 + triage 分级落库 + sla_breach_at 留痕 + 指标 API 扩展 + 12 用例 + README 剧本 5）

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

