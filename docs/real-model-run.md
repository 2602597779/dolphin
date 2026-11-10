# 真实模型验证轮（阶段 9 / T9.1）

> 状态：**未执行**。原因：本机 `DOLPHIN_API_KEY` 未配置（2026-11-10 实测 `KEY=NOT_SET`）。
> 本文档禁止虚构数据；未跑完前，面试一律采用下方 T9.4 兜底口径。

## 一、执行指引（拿到 Key 后照此操作）

1. 配置环境变量（勿写入任何文件）：

```powershell
[Environment]::SetEnvironmentVariable("DOLPHIN_API_KEY", "<百炼Key>", "User")
```

2. 不激活 offline profile，正常启动：

```powershell
mvn spring-boot:run
```

3. 跑 `demo/walkthrough.sh` 六幕同剧本（或按 README 剧本 1~6 逐条 curl），逐幕记录：

| 记录项 | 说明 |
|---|---|
| 分诊输出 | type/product/urgency/priority 原文 |
| 诊断输出 | 报告原文 + PROPOSAL 行 + invalid 标记 |
| 回复终稿 | 客户可见文本 |
| 审批中断 | 是否 PENDING_APPROVAL + pendingAction |
| 执行验证 | BASELINE / VERIFY 两条审计 + 轮询次数 |
| 质检判定 | PASS/REJECT + 意见 |
| 指标 | `GET /api/metrics/summary` 完整 JSON |

4. 与离线桩同剧本对比：延迟、措辞自然度、分诊/诊断准确性、轮次消耗。

## 二、真实模型输出记录

（待填写——每幕一节：请求体 / 响应 JSON / 审计日志 / 耗时 / 结论）

## 三、失败与误判样本

（保留，不删除——面试讲"真实模型也会误判，所以有 escalateHuman + 人工池兜底"）

## 四、T9.4 兜底口径（无 Key 期间的面试表述）

> "项目当前完成的是**离线桩全链路验证**：路由、图编排、审批中断/resume、幂等、基线验证、质检、人工池全部跑通（225 用例 + 六幕剧本可复核）。
> 架构上做了**零代码切换**设计：注入 `DOLPHIN_API_KEY` 即切真实模型（`OfflineModelConfig` 的桩 Bean 带 `@Profile("offline") + @Primary`，不激活 profile 即失效）。
> 真实模型下的意图识别准确率与 token 成本我还没完成专项验证——这是我当前排期的第一件事。"

**红线**：不得把离线桩数字说成真实模型效果；不得声称"生产运行中"。

## 五、对比结论（待填）

- 离线桩 vs 真实模型：分诊准确率 ____ / 诊断可用性 ____ / 延迟 ____ / 措辞自然度 ____
- 结论：____
