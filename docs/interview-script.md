# Dolphin 面试话术稿

> 用途：面试专用。配合 PLAN.md 使用。
> 原则：**只讲做过且有代码可证的事**；未验证的明确说"未验证"。
> 叙事边界：**不说"生产运行中"**。

---

## 一、3 分钟项目自述（背诵版）

### 开场（业务问题，30 秒）

> "我做了一个云服务工单的多智能体系统，叫 Dolphin。解决的是云服务客服的三个真实痛点：
> 一是**人工客服响应慢**，客户半夜提个'服务器连不上'要等到第二天；
> 二是**重复问答**，'SLB 健康检查不通过'这类问题占咨询量大头，每次都要人敲一遍；
> 三是**处置动作没有管控**——让 AI 直接操作客户的实例重启、安全组变更，风险不可控。
> 所以我的设计目标是：**能自动的处理快，该人控的管得住，全程可审计**。"

### 架构分层（60 秒）

> "整体是**意图路由 + 图编排**两层：
> 第一层是**意图路由**——用户消息进来先过路由链：精确匹配、关键词、正则、粘性会话、LLM 兜底分类，五层降级，路由决策完全确定性、可测试、零 token 成本；LLM 分类只在关键词没命中时兜底。
> 命中工单意图后进入第二层：**图编排引擎**（基于 Spring AI Alibaba Graph）。8 个节点：分诊 → 诊断/问答 → 回复 → 审批 → 执行 → 结单，外加转人工兜底。分诊节点输出类型和 SLA 优先级；诊断 Agent 挂只读工具查实例状态、安全组规则，产出带结构化动作建议的诊断报告；回复 Agent 把报告润色成客户可见终稿。
> 关键设计：**每个 Agent 职责单一、提示词外置在 resources/prompts 里可独立迭代**，Agent 之间通过图状态对象传递结构化数据，不共享对话文本。"

### 两个亮点故事（90 秒）

**故事 1：单例图串扰——发现与根治**

> "上线前压测发现一个诡异现象：两个客户同时提交工单，A 客户的回复里出现了 B 客户的实例 ID。排查后发现根因很隐蔽：CompiledGraph 是单例 Bean，graph-core 的 invoke 内部复用同一个模板 state 对象，并发请求互相覆盖状态。
> 我的修复是三层：一、**每请求编译独立图**——把编译逻辑从 @Bean 挪到 Runner 里每次新建，模板 state 天然隔离；二、**checkpoint 按工单号（threadId）隔离**，中断续跑不会拿到别人的断点；三、删掉了最初'submit 前清残值'的错误补丁——那是在症状上贴创可贴，真正的问题是共享可变状态。
> 修复后专门写了并发回归测试：双工单同时提交进审批中断、同时审批 resume，终态、回复、审批决策互不串扰。"

**故事 2：受理≠完成——基线验证**

> "第二个坑是关于'成功'的定义。第一版执行器只看云 API 返回 200 就判成功、就结单。但真实场景里'提交成功≠执行成功'——重启指令提交了，实例可能起不来。
> 所以我在执行前加了**基线采样**：记录实例的重启序号、安全组的规则版本、退款的流水号；执行后**必须观测到基线之后的变化**才判通过，否则重试轮询，超时转人工。
> 举个真实例子：客户说'服务器连不上，帮我重启'，系统诊断出 OS 夯死，走审批，批准后执行——审计日志里能看到 BASELINE（序号 0）和 VERIFY（第 3 次轮询观测到序号 0→1、状态 RUNNING）两条留痕。只看'变更前旧 RUNNING'会误判成功，这种'幻觉式成功'被基线验证挡住了。"

### 收尾（20 秒）

> "项目目前 49 个测试类 310 用例全绿，有 GitHub Actions CI 和 Flyway 版本化迁移；并发链路完成专项改造（受理 34ms、审批 49ms、7 项业务指标可观测）；通知链路还做了进程内 MQ 语义实现（缓冲+限速+重试，对齐钉钉 20 条/分硬限流）。定位是**离线全链路验证 + 真实模型小规模验证**，不是生产系统——但每个工程决策我都有代码和测试可以展开讲。"

---

## 二、高频追问问答（按追问概率排序）

### Q1：你的 Agent 之间怎么协作的？（必问）

**答**："不是自由对话，是**流水线分工**：每个 Agent 只做一件事——分诊、诊断、问答、回复、质检——通过图状态对象传递结构化数据。诊断 Agent 吐出的动作建议有固定契约（PROPOSAL 行），解析失败就走转人工，绝不硬解析。这种设计的取舍是：牺牲灵活性，换确定性和可审计性。"

**证据**：
- 图定义与节点：`ticket/orchestration/TicketGraphConfig.java:57`（类注释含完整流程）
- 节点实现：triage :93 / diagnosis :108 / kbAnswer :126 / reply :139 / approval :152 / execute :169 / followup :186 / escalateHuman :209
- 提示词外置：`src/main/resources/prompts/{triage,diagnosis,kb-answer,reply,quality}.st`

**追问预判**："为什么不用多 Agent 自由对话？" → "工单处理是流程型任务，路径可枚举；自由对话不可控、贵、难调试。我让 LLM 只负责需要语义理解的环节（分诊/诊断/回复），流程控制留在代码里——这是 workflow 和 agent 的边界判断。"

---

### Q2：LLM 输出不可靠怎么办？（必问）

**答**："四层防御：① 用户输入永不进系统提示词；② 分诊输出是受约束的键值对格式，不是自由文本；③ 诊断动作建议必须带置信度且过白名单——模型说'帮我删库'也不在动作白名单里；④ 写操作一律先审批再执行，模型建议只是'输入'，不是'指令'。"

**证据**：
- 输入隔离 + 路由降级：`router/IntentRouter.java:20` + `router/matcher/`（ExactMatcher :18 / KeywordMatcher :22 / RegexMatcher :19 / StickySessionMatcher :20 / LlmIntentClassifier :26）
- 白名单 + 门禁 + 幂等 + 基线：`ticket/action/ActionExecutor.java:31`
- 动作注册表：`ticket/action/ActionRegistry.java:21`
- 审批令牌：`ticket/action/ApprovalTokenService.java:44`

**追问预判**："模型幻觉怎么办？" → "幻觉的实质风险是幻觉出'动作'。我的设计里动作建议必须过 ActionRegistry 白名单 + 人工审批双闸门，模型幻觉最多导致'建议不可信'标记 → 转人工，不会导致误操作。"

---

### Q3：怎么保证写操作不出事？（高概率）

**答**："写操作走五道闸门：**白名单 → 一次性审批令牌 → 幂等键 → 基线采样 → 执行后验证**。令牌是一次性的（nonce 唯一约束防重放），拒绝不签发；幂等键是 ticketId|action|target，重复提交直接返回；执行前记基线，执行后必须观测到变化才判通过。"

**证据**：`ticket/action/ActionExecutor.java:31`（管线全在这）；`ticket/service/TicketRoundService.java:25`（短事务 + 失败补偿）

**追问预判**："为什么 graph.invoke 不在事务里？" → "图执行含云 API 调用 + 退避轮询，可能几十秒；事务只包'消息 + 状态'的短写。长事务占连接池，是反模式。"

---

### Q4：多轮对话怎么处理的？（高概率）

**答**："客户追问时，对话历史注入图状态，**从头重跑而不是续跑旧断点**——因为客户可能改口（上轮说'连不上'，这轮说'其实是安全组没开 443'），带着旧结论续跑会答非所问。追问前会读旧 checkpoint 里的待执行动作并写审计——旧审批链接随即作废，审批人可追溯。"

**证据**：`ticket/api/TicketController.java:249`（reply 方法）；`ticket/service/ConversationService.java:23`

**追问预判**："CLOSED 后还能追问吗？" → "不能，409 拒了，让他重新提交；ESCALATED 只记录不重跑——AI 已让位给人工，不再抢答。"（见 `TicketController.java:277-288`）

---

### Q5：并发问题怎么解决的？（高概率，2026-11 完成专项改造）

**答（总纲一句话）**："三件事各管一段：**工单线程池削峰**（有界队列+拒绝补偿）、**LlmGuard 封顶下游并发**（全系统 LLM 调用共用一个信号量）、**受理闸门管正确性**（条件状态翻转）。削峰≠提速——消化速率由下游 LLM 配额决定，天花板在下游不在自己代码。"

**展开（30 秒版）**："原来 HTTP 线程要陪着一张图跑完（LLM 调用+云 API 轮询，一单 2-5 秒），50 个并发就把 Tomcat 线程池打满。改造后受理和执行分离：受理只做落库+状态翻转+投递，**实测 34ms**（原 2-3s）；图在独立线程池跑，队列有界 500，满了走 AbortPolicy 拒绝，由 runner 就地补偿转人工——**拒绝比静默积压诚实**，'不丢单'底线由补偿保，不靠无限排队。"

**正确性关键**："异步化的是'跑图'，不是'判定'。审批闸门（`tryTransition` 条件 UPDATE）保持同步执行——聊天和 REST 两个渠道并发审批同一单，只有一个赢家落决策；实测并发双发「同意」，赢家收到'已批准,正在执行'，输家不落决策、清粘性、按工单实际状态如实告知。"

**证据**：
- 受理闸门（同步）：`ticket/service/TicketService.java:123`（tryTransition）
- 聊天异步受理：`capability/handler/TicketFlowHandler.java`（submitTicket 受理即回 + handleApprovalReply 闸门同步）
- 有界线程池：`ticket/service/TicketAsyncConfig.java:39`（ThreadPoolExecutor + AbortPolicy 注释）
- LLM 保护闸：`llm/LlmGuard.java`（Semaphore + 线性退避重试）
- 并发实测：`demo/walkthrough.sh` 第七幕（7.3 并发双发）

**追问预判**：
- "为什么不用分布式锁？" → "状态机 + 条件更新就够了，锁是多余的依赖，且多实例部署天然安全（DB 条件更新不依赖进程内状态）。"
- "队列为什么有界？无界不是吞吐更高吗？" → "无界是假削峰：OOM 风险 + 积压不可见（客户端以为在处理，实际排到天荒地老）。有界+拒绝+补偿才是真削峰。"
- "削峰之后为什么用户还是觉得慢？" → "削峰≠提速。队列只是把'打不进去的电话'变成'排队等待'，消化速率由下游 LLM 配额封顶——这就是为什么我加了 LlmGuard：并发封顶到配额×0.8，重试带退避，配额水位有指标可看。"
- "为什么不上 MQ 削峰？" → "判断标准是这段链路的瓶颈是不是'排队能解决'。处置队列的瓶颈在下游 LLM 配额，进 MQ 也只是换个地方排队；通知链路则不同（钉钉 20 条/分硬限流），所以只给通知上了 MQ 语义——**同一系统两个队列两种判断**，详见 3.5。"

---

### Q6：客户数据安全怎么保证？（中概率）

**答**："租户模型：工单归属只从鉴权上下文取（API-Key → 客户），请求体不带身份字段、伪造不了；跨客户访问一律 404 不泄露存在性；错误文案统一，防 key 探测。运维接口（告警、人工池、指标）走内网边界，不走客户鉴权。"

**证据**：`ticket/auth/ApiKeyAuthFilter.java:28`；`customer/CustomerSeeder.java:17`；`TicketController.java:131`（getForCustomer 模式）

---

### Q7：上线效果怎么量化？（中概率，注意口径）

**答**："指标从系统自己的台账聚合：自动化结单率、人工升级率、审批触发率、质检通过率、MTTR。离线演示口径：5 单里 4 单全程无人介入（3 咨询 + 1 免审批直执）= 80% 自动化，20% 走审批，MTTR 中位数 0.1s、均值被审批等待拉高到 7.5s。"

**诚实边界**："这是**离线桩模型**下的链路验证数据，证明的是'流程能自动闭环'；真实模型下的意图识别准确率还没测——这是当前最大的待办。"

**证据**：`metrics/MetricsService.java`；README.md 剧本 4（行 190-227）

---

### Q8：为什么用 Spring AI 不用 LangChain？（低频但考选型）

**答**："团队栈是 Java + Spring，Spring AI 把 ChatClient、ChatModel、VectorStore 做成了 Spring 风格的抽象，业务代码零框架痕迹；graph-core 补了图编排。代价是生态比 LangChain 薄，所以我把提示词、路由、动作全部外置成可替换件，将来换框架只动 adapter 层。"

**证据**：`pom.xml`（spring-ai-starter-model-openai + spring-ai-alibaba-graph-core）；`ticket/offline/OfflineModelConfig.java:22` + `ScriptedChatModel.java:24`（可替换性的直接证据：换模型零改业务代码）

---

### Q9：这个项目最大的不足？（低频，送分题也是陷阱）

**答**："三个：① 真实模型的效果数据还没测（离线桩验证的是链路不是模型智力）；② IM 审批闭环没做（设计好了平台无关回调协议，缺真机验证）；③ 并发改造是单机方案（进程内线程池+信号量），多实例部署原本缺三件套——这个问题我已经**按接口留口的方式提前拆解了**：发号器（`TicketIdGenerator`，当前 `local`，另有去中心化的 `worker` 策略）、SLA 防重（`SlaBreachGuard`，条件更新抢处置权）、会话（`SessionStore`，读写分离契约）、任务队列（`TicketTaskQueue`，拒绝即补偿转人工）四处都抽成了「接口 + 进程内默认实现 + 配置切换」，将来接 Redis / MQ 只换实现类，调用方不动。边界我也说清楚：**没有引入真实 Redis/MQ**，现在的默认实现仍是进程内的，缺的是分布式存储与跨进程分发；闸门本身不受影响（DB 条件更新天然多实例安全）。"

**禁区**：不说"生产运行中"、不说"服务 X 万用户"。定位话术："个人完整实现的企业级架构实践"。

---

## 三、并发专题深挖（2026-11 专项改造，叙事链完整版）

> 本章是并发话题的完整弹药库：从"要不要做"到"怎么设计"到"怎么落地"到"怎么证明"。Q5 是 30 秒版，本章是被深挖时的展开版。

### 3.1 叙事主线（四段递进）

**第 1 段·现状评估（为什么改）**：
> "改造前系统是同步阻塞模型：HTTP 线程陪跑整张图（LLM 调用 + 云 API 轮询验证），一单 2-5 秒。并发能力受三层约束：Tomcat 工作线程数（长任务占线程）、下游 LLM 配额（真正的吞吐天花板）、以及并发正确性（同单双审/跨渠道竞争）。瓶颈不在'代码慢'，在'线程被长任务占着'。"

**第 2 段·目标定级（B 端口径）**：
> "内部 B 端工单系统的并发量级是客服坐席 + 系统集成，峰值几十到几百并发，不是电商秒杀。所以优先级是：**P0 正确性**（不丢单、不重复执行、跨渠道审批唯一赢家）> **P1 受理延迟**（用户发消息要毫秒级被接住）> **P2 吞吐**（消化速率，受 LLM 配额约束）。这个排序决定了后面的每个取舍。"

**第 3 段·主管视角设计（SLO + 关键决策）**：
> "如果我是项目负责人，SLO 定三条：受理 P99 < 100ms、执行完成 P95 < 60s（受下游配额约束）、零丢单。落到设计上是四个决策：① **受理与执行分离**（受理即回，图异步跑）；② **队列有界**（500，满了拒绝——拒绝比静默积压诚实）；③ **拒绝走补偿**（就地转人工+审计，工单不停留 PROCESSING——这是业务语义取舍：工单系统不丢单，不同于电商快速失败）；④ **判定不同步化**（跑图可以异步，审批判定必须同步）。"

**第 4 段·单机正解（回应"要不要上 MQ"）**：
> "不考虑外部机器的情况下，**队列 + 异步化是正解**——但有三个前提：任务是长任务（LLM 秒级）、调用方可异步容忍（受理即回 + 进展查询）、用户可等待。MQ 是多实例阶段的事，单机阶段引入是过度设计。落地四步全部完成：聊天链路异步化 → 队列有界化 → LlmGuard 下游保护 → 指标可观测。"

### 3.2 三件事各管一段（架构总纲，必背）

| 组件 | 管什么 | 为什么不能互相替代 |
|---|---|---|
| 工单线程池（有界 500 + AbortPolicy） | **削峰**：把瞬时风暴变成可消化的排队 | 管不了下游配额——队列再深，LLM 就那么快 |
| LlmGuard（Semaphore 16 + 退避重试） | **封顶下游并发**：所有 Agent 的 LLM 调用共用一个闸 | 管不了正确性——它不知道哪笔审批该谁赢 |
| 受理闸门（tryTransition 条件 UPDATE，同步） | **正确性**：跨渠道并发审批唯一赢家 | 不能异步化——判定一旦异步，双赢/丢单都可能 |
| 通知队列（进程内 MQ 语义，仅 `channel=mq`） | **通知限速**：把钉钉 20 条/分硬限流变成缓冲+限速+重试 | 管不了主链路——它是旁路，通知失败不影响工单状态推进与正确性 |

> 一句话："削峰≠提速，天花板在下游 LLM 配额不在自己代码；正确性靠闸门不靠队列。"

### 3.3 深挖追问预判（按被问概率排序）

**"LlmGuard 为什么不做超时？"**
> "诚实的技术判断：同步 Supplier 无法被安全中断，强做超时要么线程泄漏要么中断不干净。请求级超时归 HTTP 客户端层（Spring AI 底层 RestClient 的 read-timeout），LlmGuard 只做它擅长的两件事：并发封顶 + 失败重试。不把所有责任都塞进一个组件。"

**"重试会不会重复执行写操作？"**
> "不会。LlmGuard 重试的是**模型调用**（读 prompt 出报告），不是动作执行。写操作链路另有四道闸：白名单 → 一次性令牌 → 幂等键 → 基线验证。两层保护互不越界。"

**"队列满了用户体验是什么？"**
> "受理方立即感知：聊天侧回复'系统繁忙，工单 X 受理队列已满，已转人工跟进'并清粘性；REST 侧 503。工单不丢单——runner 在拒绝时就地补偿（系统说明入线程 + 转人工 + 审计留痕）。这是业务取舍：工单系统宁可慢，不能丢。"

**"怎么知道系统现在健不健康？"**
> "四个业务指标：`dolphin_graph_queue_depth`（排队深度，持续增长=消化不足）、`dolphin_graph_pool_active`（跑图线程数，触顶=并发跑满）、`dolphin_llm_guard_permits`（LLM 配额水位，持续为 0=下游是瓶颈——'天花板在下游'的直接证据）、`dolphin_llm_call_seconds`（按动作分桶的调用耗时）。已通过 /actuator/prometheus 暴露并本地验证。"

**"多实例部署要改什么？"**
> "闸门不用改（DB 条件更新天然多实例安全）；要外置三件套：发号器（AtomicLong → Redis/DB 段号）、SlaMonitor 防重与会话外置（进程内定时器会重复巡检）、任务队列（进程内 LinkedBlockingQueue → MQ 或 DB 轮询）。这些已明确标注为多实例前置项，不在单机阶段过度设计。"

**"异步化后粘性会话/审批状态怎么保证线程安全？"**
> "粘性字段全 volatile（写可见性安全）；乐观预置+终态清除的生命周期设计让残留无害（30min TTL + 任意消息经状态查询兜底）；审批决策落库走同步闸门，异步的只是后续 resume 投递。"

### 3.4 实测数据（offline 口径，2026-11-24，全部有验证记录）

| 场景 | 改造前 | 改造后 | 验证方式 |
|---|---|---|---|
| 聊天建单受理 | 2-3s（同步跑图） | **34ms** | offline 实测 curl 计时 |
| 粘性审批 | ~5.3s（同步 resume） | **49ms** | 同上 |
| 并发双发「同意」 | — | 1 赢家 1 输家（输家按实际状态告知） | walkthrough 7.3 并发实测 |
| 测试 | 225 用例 | **310 用例全绿**（49 类） | `mvn test` 两轮 |
| 指标端点 | 无 | **7 项业务指标**（LLM 2 / 图 2 / 通知 3）+ actuator 基础指标 | curl /actuator/prometheus |

> 口径提醒：以上均为 **offline 桩模型**下的链路验证数据（证明架构与正确性，不证明模型智力）；真实模型数据待 `DOLPHIN_API_KEY`。

### 3.5 MQ 专题：同一系统两个队列两种判断（2026-11 完成）

**一句话总纲**："**通知链路上 MQ，处置队列不上 MQ**——判断标准不是'MQ 高级所以上'，而是这段链路的瓶颈能不能靠排队解决。"

**为什么通知链路上 MQ（真价值）**：
> 钉钉自定义机器人有 **20 条/分钟**硬限流（官方限制，超了直接限流报错）。两个生产者（告警诊断、SLA 加急）都是'有事立刻发'，突发时必然超限。所以把通知收敛到 `NotifySender` 接口后面：`direct`（默认，同步直发，行为与改造前一致）/ `mq`（进程内队列：有界 200 + `notify-consumer` 单线程按 3000ms/条限速 + 失败退避重试 2 次 + 重试耗尽转死信 + 队列满立即拒绝并计数）。这里**缓冲 + 限速 + 重试**三件套是真需求，不是装饰。

**为什么处置队列不上 MQ**：
> 图执行队列的瓶颈在下游 LLM 配额（LlmGuard 并发 16，单图 2-5s），消息进 MQ 也只是换个地方排队，**总耗时一点不缩短**，还多一个中间件依赖和运维面。当前选择更诚实：有界 500 + AbortPolicy + 拒绝就地补偿转人工——拒绝是**明确告知**，比在 MQ 里静默积压好。

**"你这个 MQ 是真的吗？"（必须诚实）**：
> "**是进程内 MQ 语义实现，不是真实中间件**。`ArrayBlockingQueue` + 单 daemon 消费线程，pom 零新增依赖、无 broker 进程。它兑现了 MQ 的核心语义——缓冲、限速、重试、死信；但不具备真实 MQ 的两件事：**持久化（重启即清空）和跨进程分发（单实例）**。这正是生产替换 RocketMQ 的理由，也是我把它称为'实现替换'而非'架构升级'的原因。"

**"换真 MQ 要改多少？"**：
> "只换 `MqNotifySender` 一个类的两端：发端 `send()` 的 offer 换 `rocketMQTemplate.syncSend`，消费端 `consumeLoop()` 换 `@RocketMQMessageListener`，限速/重试/死信交给 broker 与消费重试策略。`NotifySender` 接口、两个调用方（`SlaMonitor` / `AlertDiagnosisService`）、配置开关都不动——这就是抽接口留口的目的。"

**"为什么默认还是 direct？"**：
> "离线演示、单元测试、以及没有钉钉 webhook 的环境不该为可选优化付复杂度。`channel=direct` 保持改造前的同步语义，既有用例零改动；`DOLPHIN_NOTIFY_CHANNEL=mq` 一行切换。"

**证据**：
- 接口与实现：`notify/NotifySender.java`、`notify/MqNotifySender.java`、`notify/NotifyConfig.java`（`@ConditionalOnProperty` 按 channel 选 bean）
- 调用方改造：`sla/SlaMonitor.java`、`alert/AlertDiagnosisService.java`（原依赖 `DingTalkNotifier` → `NotifySender`；`DingTalkNotifier` 本体零改动）
- 配置：`application.yml` 的 `dolphin.notify` 块（channel/queue-capacity/interval-ms/max-retries/retry-backoff-ms）
- 实测（offline，`--dolphin.notify.channel=mq`，2026-11-25）：告警 webhook → 异步诊断 → `[notify-consumer]` 线程投递降级日志；同一次运行里 SLA 加急通知同样由 `notify-consumer` 投递（两个生产者都被队列接管）；`dolphin_notify_queue_depth` / `_queue_dropped_total` / `_dead_letter_total` 在 `/actuator/prometheus` 可见
- 测试：`notify/MqNotifySenderTest.java` 9 用例（顺序投递/限速间隔/重试后成功/重试耗尽进死信/未配置不重试/队列满拒绝/shutdown 停线程/channel 选 bean）

### 3.6 多实例就绪专题：四处接口留口（2026-11 完成，Q9 ③ 的展开版）

**一句话总纲**："单机并发改造之后，真正卡多实例的是**进程内状态**。我没引入 Redis/MQ，而是把四处进程内状态**抽成接口 + 默认实现 + 配置切换**，沿用通知链路 `NotifySender` 的同一套范式——**先留口，后换实现**。"

| 位置 | 接口 | 当前默认实现 | 进程内状态是什么 | 多实例时要换什么 |
|---|---|---|---|---|
| 发号器 | `cluster/TicketIdGenerator.next()` | `LocalTicketIdGenerator`（毫秒+进程内序号） | 进程内 `AtomicLong` 序列 | `WorkerTicketIdGenerator`（已实现，`strategy=worker` 一行切换）或 Snowflake |
| SLA 防重 | `cluster/SlaBreachGuard.claim(id, ts)` | `DbSlaBreachGuard`（条件 UPDATE 抢处置权） | **不在进程内**，已靠 DB 条件更新 | 无需更换（天然多实例安全，留口只为可替换/可测） |
| 会话 | `router/SessionStore` | `LocalSessionStore`（进程内 Map） | 会话上下文 | Redis 等外部 KV（契约：`getOrCreate` 取对象 → 就地改 → `save(ctx)` 写回；进程内 `save` 是 touch-only 空操作） |
| 任务队列 | `cluster/TicketTaskQueue.submit(task)` | `LocalTicketTaskQueue`（委托既有 `ticketGraphExecutor`） | 进程内线程池 | 真实 MQ（跨进程分发）；拒绝即补偿转人工的语义不变 |

**"为什么不做成 `@ConditionalOnMissingBean`？"（考点）**：
> "**bean 注册顺序不可靠**——同一个 `@ConditionalOnMissingBean` 判定通过与否取决于扫描顺序，多模块下很难保证'默认实现一定先注册'。所以统一成'**默认 `@Bean` 常驻 + 未来实现标 `@Primary` 覆盖**'，只有发号器这种'两套实现都真实存在'的才用 `@ConditionalOnProperty` 做二选一。"

**"为什么最早只有发号器做了开关？"**：
> "因为一开始**只有发号器真有两套可用实现**（local / worker），做成开关才叫'切换'，另外三处只有一个实现，硬造个 `redis` 配置项就是**装饰性配置**——写了 redis 却没实现，面试官一追问就崩。**D5 之后判断标准没变，只是条件变了**：会话存储和任务队列也各自补上了第二套真实实现（落在项目自带的关系库上，零新增依赖、零中间件），于是它们同样升格为 `@ConditionalOnProperty` 开关（默认分支都带 `matchIfMissing = true`，不配参数时行为与改造前逐字节一致）；SLA 防重守卫仍是单实现，继续保持 `@Primary` 留口。"

**"SLA 防重为什么说天然安全？"**：
> "它压根不依赖进程内状态：`claim()` 是一条带条件的 `UPDATE ... WHERE sla_breach_at IS NULL`，谁先抢到谁赢，DB 行锁保证原子性。多实例同时巡检同一张超时单，只会有一个更新到行。所以它是'接口留口'，不是'多实例缺口'。"

**实测（offline，`strategy=worker` + `worker-id=7`）**：
```text
POST /api/tickets/submit  →  {"ticketId":"T2S2IVPLK-7-1", ...}   # 实例号位=7，序列 1
POST /api/tickets/submit  →  {"ticketId":"T2S2IVPN9-7-2", ...}   # 同实例序列自增 → 2
```
默认 `strategy=local` 下（`demo/walkthrough.sh` 产出）为 `TMWX1LPWM1` 这类**无横杠**格式——两套实现格式不同、默认路径行为与改造前完全一致。

**诚实边界（必说）**：
> "这四处**都不是真实分布式实现**：会话还是进程内 Map，任务队列还是进程内线程池，发号器只是个去中心化的号段隔离方案（`T{相对毫秒 base36}-{实例号 base36}-{序列 base36}`，**不是标准 64 位 Snowflake**——工单号是给人看的字符串，只需要'跨进程不重复'这一个性质）。缺的是**分布式存储（会话持久化）与跨进程分发（队列）**，换实现时调用方一行不动，这才是这次改造的全部价值。这两块 D5 已经用项目自带的关系库补上了第二套实现（共享表 + 配置切换，默认仍是进程内），但共享表**不等于 MQ**：任务表只做投递留痕、条件更新抢占、终态收口和超时巡检，**不提供跨进程重建执行**。"

**踩坑留痕（真事故，可当'你踩过什么坑'答）**：
> "验证 worker 策略时我把属性写成了 `--dolphin.cluster.worker-id=7`，漏了中间的 `ticket-id`，结果 Spring Boot 把它当**未知属性静默忽略**，实例号悄悄退回 0、号还是照发——**没有任何报错**。这说明'配置切换'光跑通不算验证，必须**断言产出物格式**（我核对了工单号里的实例号位）才算。全名是 `dolphin.cluster.ticket-id.worker-id`。"

**证据**：
- 接口与实现：`cluster/TicketIdGenerator.java`、`LocalTicketIdGenerator.java`、`WorkerTicketIdGenerator.java`、`SlaBreachGuard.java`、`DbSlaBreachGuard.java`、`TicketTaskQueue.java`、`LocalTicketTaskQueue.java`、`router/SessionStore.java` + `LocalSessionStore.java`
- 装配与配置：`cluster/ClusterConfig.java`、`cluster/TicketIdProperties.java`、`application.yml` 的 `dolphin.cluster` 块
- 条件更新落地：`ticket/repo/TicketRepository.java`（`claimSlaBreach`）
- 测试：`cluster/TicketIdGeneratorTest`（4）、`cluster/DbSlaBreachGuardTest`（3，含并发抢占只有一个赢家）、`cluster/LocalTicketTaskQueueTest`（3）
- 回归：默认配置下 `demo/walkthrough.sh` 八幕全通（`EXIT=0`，0 次等待超时）；全量 `mvn test` **49 类 310 用例全绿**

### 3.7 D5 持久化专题：会话与任务队列的第二套实现（2026-11-27 完成）

**"你的会话粘性和任务队列，多实例下会怎样？"（考点）**：
> "默认单机语义下没区别。真起两个实例时，`DOLPHIN_CLUSTER_SESSION_STORE=jdbc` + `DOLPHIN_CLUSTER_TASK_QUEUE=jdbc` 就把这两处换到**同一张共享表**上：会话老状态、审批挂起上下文写在 `session_context`，谁实例都读得到（`getOrCreate` 回读实体再回填 capability / pendingTicketId）；任务投递写在 `ticket_task`，`submit` 先落一行 PENDING 再去跑，谁抢到谁把状态条件更新成 RUNNING，收口写 DONE/FAILED/REJECTED。调用方一行没动，换的只是实现类。"

**"这算引入 MQ 了吗？"**：
> "**不算，也没打算算**。`submit` 的签名是 `Runnable`，里面没有工单号也没有可恢复意图——图执行随进程生灭，进程没了那一轮就真没了。所以 `ticket_task` 我只承诺四件事：投递留痕、条件更新抢占（`UPDATE ... WHERE status = 'PENDING'` 抢到才跑）、终态收口、以及**定时巡检把超时未收口的行改判 `STUCK` 留痕**。跨进程重建执行需要真消息契约（工单号 + 可恢复状态 + 幂等消费），那是另一个量级的改造，我明确不做、也不假装做了。"

**实测（offline，双开关起服 + 八幕 walkthrough）**：
```text
[dolphin] [main] c.d.cluster.JdbcTicketTaskQueue : [task-queue] 使用共享任务表实现(表 ticket_task):投递留痕+抢占标记+孤儿巡检,workerId=31800@DESKTOP-…
[dolphin] [main] c.d.router.JdbcSessionStore      : [session-store] 使用共享表实现(表 session_context):多实例共享会话粘性,空闲 TTL=PT30M
[dolphin] [scheduling-1] c.d.cluster.JdbcTicketTaskQueue : [task-queue] 巡检发现 1 个滞留任务,已标记 STUCK 留痕(不重建执行)   # 以 stale-after=1s 重启后复现
```
`demo/walkthrough.sh` 在 jdbc 模式下同样八幕全通（`EXIT=0`、0 次等待超时），第七幕粘性审批命中用的是**从共享表回读**的状态（`StickySessionMatcher 命中 … by STICKY`，此时进程内已无 `LocalSessionStore` Bean）。

**诚实边界（必说）**：
> "两个限制讲清楚：一是共享表实现**不重建执行**，只把'谁在跑、跑没跑完、谁死了'留痕，供人工核对；二是本地 H2 文件库在**硬杀进程**下不保证保留最近几条提交（`Stop-Process -Force` 前后实测行数会不一致，属于 H2 MVStore 的检查点语义，代码里对 `ticket_task` 没有任何 DELETE 语句），生产换 MySQL 不带这个限制。"

**证据**：
- 迁移与实体：`db/migration/V3__session_context.sql`、`V4__ticket_task.sql`、`router/repo/SessionContextEntity.java` + `SessionContextRepository.java`、`cluster/repo/TicketTaskEntity.java` + `TicketTaskRepository.java`
- 两套实现：`router/JdbcSessionStore.java`（对齐进程内版 `LocalSessionStore.java`）、`cluster/JdbcTicketTaskQueue.java`（对齐进程内版 `LocalTicketTaskQueue.java`）
- 装配与配置：`cluster/ClusterConfig.java`、`cluster/SessionStoreProperties.java`、`cluster/TaskQueueProperties.java`、`application.yml` 的 `dolphin.cluster.session-store` / `task-queue` 块
- 测试：`router/JdbcSessionStoreTest`（5：跨实例回读粘性 / 每读一次返回新快照 / 空闲超阈值被巡检清掉 / size 走共享表 / 并发首建回读既有行）、`cluster/JdbcTicketTaskQueueTest`（5：投递→DONE / 执行器拒绝→REJECTED 且 `submit` 返回 false / 执行体抛异常→FAILED 且不外抛 / 巡检把滞留行标 STUCK / 抢占是条件更新只赢一次）

---

## 四、效果数据口径表（T9.2）

| 指标 | 数值 | 来源与口径 | 可信度标注 |
|---|---|---|---|
| 测试用例 | 49 类 310 用例全绿 | `mvn test` 实测（2026-11-27；多实例改造 +3 类 12 用例、D1 IM 审批 +4 类 39 用例、D5 持久化 +2 类 10 用例） | ✅ 已验证 |
| 发号策略切换 | `strategy=worker` + `worker-id=7` → `T2S2IVPLK-7-1` / `T2S2IVPN9-7-2` | `POST /api/tickets/submit` @39999 offline 实测（2026-11-26） | ✅ 已验证 |
| CI | 历史全绿（run 42183646528）；2026-11-23 后多笔推送（c83ae2a/8c9979b/0150598/90b7740/b45ca67）CI 状态未回读 | GitHub Actions（api.github.com 网络受限，需外部环境确认） | ⚠️ 部分未验证 |
| 迁移路径 | 空库/存量库/幂等 三路径通过 | PLAN.md 阶段 8 记录 | ✅ 已验证 |
| 聊天受理延迟 | 34ms（原同步 2-3s） | offline 实测 curl 计时（2026-11-24） | ✅ 已验证（offline 口径） |
| 聊天审批延迟 | 49ms（原同步 ~5.3s） | 同上 | ✅ 已验证（offline 口径） |
| 并发闸门 | 双发「同意」1 赢家 1 输家 | walkthrough 7.3 并发实测 | ✅ 已验证（offline 口径） |
| 业务指标 | 7 项经 /actuator/prometheus 暴露（LLM 2 / 图 2 / 通知 3） | curl 实测（2026-11-25） | ✅ 已验证 |
| 通知链路 MQ（offline） | 告警诊断 + SLA 加急两条通知均由 `notify-consumer` 单线程投递；3 个 notify 指标可见 | offline `--dolphin.notify.channel=mq` 实测（2026-11-25） | ✅ 已验证（offline 口径） |
| 持久化切换（D5） | `session-store.kind=jdbc` + `task-queue.kind=jdbc` → 会话粘性自 `session_context` 回读、任务落 `ticket_task`（DONE 10 / RUNNING 1） | offline 双开关起服 + 八幕 walkthrough + H2 Shell 直连查询（2026-11-27） | ✅ 已验证（offline 口径） |
| 孤儿任务巡检（D5） | `stale-after=1s` 重启 → WARN「巡检发现 1 个滞留任务,已标记 STUCK 留痕(不重建执行)」，行状态 RUNNING→STUCK | offline 实测 + H2 断言（2026-11-27） | ✅ 已验证（offline 口径） |
| 自动化结单率 | 80%（4/5） | 离线桩模型 + 5 单剧本 | ⚠️ 标注 offline |
| 审批触发率 | 20%（1/5） | 同上 | ⚠️ 标注 offline |
| 质检通过率 | 5/5 PASS | 同上 | ⚠️ 标注 offline |
| MTTR | 中位 0.1s / 均值 7.5s | 同上 | ⚠️ 标注 offline |
| 真实模型效果 | **未测** | 待 `DOLPHIN_API_KEY` | ❌ 禁止编造 |

**话术**："离线桩验证的是**链路工程正确性**——路由、编排、门禁、补偿这些不依赖模型智力；模型智力要看真实模型轮的数据，我还没跑完，不编。"

---

## 五、禁区清单（红线）

1. ❌ 不说"生产运行中"、"日活 X"、"服务 X 万用户"
2. ❌ 不虚构真实模型指标（未测就是未测）
3. ❌ 不贬低其他框架（"LangChain 太黑盒"这类话不说，说"选型权衡"）
4. ❌ 不主动提 LeetcodeTest.java（那是刷题，与项目无关，被问就承认）
5. ✅ 所有结论必须能落到 `file:line` 或 README 剧本
6. ✅ 并发数据只说 offline 口径实测值（34ms/49ms/49 类 310 用例），不外推生产压测数字
7. ✅ MQ 只说"进程内 MQ 语义实现"，不说"用了 RocketMQ/真消息队列"；被问持久化/多实例分发时讲清现状：会话粘性与任务留痕已有共享表实现（`session-store.kind=jdbc` / `task-queue.kind=jdbc` 可切换，默认仍是进程内），但任务表**只做投递留痕与孤儿检测、不提供跨进程重建执行、它不是 MQ**；生产替换为 RocketMQ 属实现替换

## 六、待办（面试前完成）

- [ ] T9.1 真实模型验证轮：配 `DOLPHIN_API_KEY` 后跑 `demo/walkthrough.sh`，记录到 `docs/real-model-run.md`（当前无 Key，见 T9.4 兜底）
- [ ] 话术稿通读 3 遍 + 找人模拟追问 1 轮（重点：第三章并发深挖的追问预判）
- [ ] CI 状态回读（api.github.com 网络受限，需外部环境确认最近三笔推送）
