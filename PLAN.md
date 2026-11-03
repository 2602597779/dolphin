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

- [x] H0. 删除 `src/main/java/com/dolphin/test/LeetcodeTest.java`（刷题代码混入主源码）
- [x] H1. `.gitignore` 增加 `.trae/`
- [x] H2. 当前 P0 持久化改造未提交 → commit 进版本库（commit msg: "P0:状态全落库+并发串扰根治+离线演示链路"）
- [x] H3. LeetcodeTest 同包目录 `com/dolphin/test` 一并删除

---

## 第一梯队（面试话术价值 ÷ 工作量最高，做完可停手）

### 阶段 1：客户/租户模型 + API-Key 鉴权（约 1~2 天）

**业务目标**：工单归属客户，客户只能看自己的单，跨租户访问返回 404/403。

任务拆解：
- [ ] T1.1 `customer` 表 + `CustomerEntity` + `CustomerRepository`（字段：id、name、apiKey、enabled；启动种子 2 个客户）
- [ ] T1.2 `ticket` 表加 `customer_id` 列；`TicketService.create` 带归属；实体加字段（Hibernate ddl-auto update 自动加列，老数据 null 不影响）
- [ ] T1.3 `XApiKeyAuthFilter`（Servlet Filter 或 HandlerInterceptor）：请求头 `X-API-Key` → 解析客户；`/api/tickets/**` 强制鉴权；鉴权失败 401
- [ ] T1.4 `SubmitRequest` 增加可选 customerId 校验：API-Key 对应客户即归属，不传则用 Key 的客户；无 Key 的接口路径（告警 webhook 等）不受影响
- [ ] T1.5 `GET /api/tickets/{id}` 做归属校验：非本客户的单返回 404
- [ ] T1.6 列表接口 `GET /api/tickets?status=&limit=`（只看本客户）——顺带补上真实系统必备的列表查询
- [ ] T1.7 单测：鉴权过滤器（401/通过/客户隔离）
- [ ] T1.8 README 剧本更新：两个 API-Key 提交两条工单，互相看不见对方的单
- [ ] T1.9 测试全绿后 commit（msg: "阶段1:客户/租户模型+API-Key鉴权+工单归属隔离"）

**完成标准**：两个不同 API-Key 的客户无法读取对方工单；`mvn test` 全绿；README 有双客户剧本。

### 阶段 2：运营指标 API（约 1 天）

**业务目标**：上线效果可量化，面试能说出"自动化解决率 X%、MTTR Y 分钟"且数字来自系统本身。

任务拆解：
- [ ] T2.1 `MetricsService`：基于 `ticket` 表聚合——工单总量、自动化结单率（CLOSED 且未经人工池/审批）、人工升级率（进过 ESCALATED/human_task）、审批触发率、质检通过率（audit_log 的 quality-review 事件）
- [ ] T2.2 `ticket` 表补 `created_at`/`closed_at`/`priority` 时间戳字段（为 MTTR/SLA 用）
- [ ] T2.3 MTTR：CLOSED 工单的 closed_at - created_at 中位数/均值
- [ ] T2.4 `GET /api/metrics/summary` 返回全部指标；`GET /api/metrics/tickets?from=&to=` 支持时间窗口
- [ ] T2.5 单测：指标计算正确性（造数据断言数字）
- [ ] T2.6 README 增加指标示例输出；commit（msg: "阶段2:运营指标API(MTTR/自动化解决率/升级率/质检通过率)"）

**完成标准**：造 5 条工单数据后，指标接口的数字能用 README 剧本手工复核。

### 阶段 3：SLA 优先级 + 超时升级（约 1~2 天）

**业务目标**：P1~P4 优先级 + 响应/解决双计时器，超时自动升级人工池并加急通知。

任务拆解：
- [ ] T3.1 分诊节点输出优先级（triage 的意图分类附带 P1~P4：故障=P2、故障且"连不上/宕机"=P1、咨询=P4），落 `ticket.priority`
- [ ] T3.2 `dolphin.sla` 配置：各级别响应/解决时限（如 P1 响应 5 分钟/解决 30 分钟）
- [ ] T3.3 `SlaMonitor`（`@Scheduled` 每分钟扫一次）：未闭环且超响应时限 → 升级 `HumanTicketPool` + 标记 breach；超解决时限 → 加急（钉钉加急或审计 ALARM 留痕）
- [ ] T3.4 指标 API 补充：SLA 达成率、各优先级分布
- [ ] T3.5 单测：超时扫描逻辑（可注入时钟或缩短配置）
- [ ] T3.6 README 剧本：P1 单故意不处理 → 观察超时升级；commit（msg: "阶段3:SLA优先级+超时自动升级人工池"）

**完成标准**：造一条 P1 工单，把时限配成 1 分钟，观察其被自动升级进人工池且有留痕。

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

**最后更新**：2026-11-03
**已完成**：H0~H3 全部完成（刷题代码已删、.trae/ 已忽略、P0 持久化改造已 commit、mvn compile 验证通过）
**下一步**：阶段 1 客户/租户模型 + API-Key 鉴权，从 T1.1 开始
**git 状态**：P0 改造+清理合并为一个 commit，PLAN.md 单独 commit；此后工作区应保持干净

### 已提交的进度凭证（git log 摘要）

- `P0:状态全落库+并发串扰根治+离线演示链路`（H2 全部 P0 改造 + H0/H1/H3 清理顺带入库）
- `docs: 新增企业化改造计划(PLAN.md)断点续传文档`（本文件）

### 会话恢复指引（新会话照此操作）

1. 读本文件"当前断点"章节 + 跑 `git log --oneline -10` + `git status --short`
2. 找到状态为 `[ ]` 的最靠前任务，按其"完成标准"实现
3. 做完勾选、更新本章节、commit
4. 若任务做一半被打断：把已完成的部分 + 剩余步骤写在下面"进行中备忘"里

### 进行中备忘

（空）

