#!/usr/bin/env bash
# ============================================================================
# Dolphin 全链路导览(P1 全链路可见化):七幕演示剧本,offline profile 零外部依赖。
#
# 用法:
#   终端一: mvn spring-boot:run -Dspring-boot.run.profiles=offline
#   终端二: bash demo/walkthrough.sh
#   可选环境变量: BASE_URL(默认 http://localhost:8080)、API_KEY(默认客户A演示 Key)
#
# 前六幕统一套路:提交 → 轮询状态 → 打印 GET /api/tickets/{id}/timeline,
# 时间线里能看到节点轨迹(graph:<node>:enter|exit)、执行器基线/验证、审批决策与质检留痕。
# 第七幕走聊天入口(POST /api/dolphin/chat,同步响应无需轮询),演示粘性审批与并发受理闸门。
# ============================================================================
set -uo pipefail

BASE_URL=${BASE_URL:-http://localhost:8080}
API_KEY=${API_KEY:-ak-demo-cust-001}
AUTH="X-API-Key: $API_KEY"
JSON="Content-Type: application/json"
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}

say()  { printf '\n\033[1m==== %s ====\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
fail() { printf '\033[31m  %s\033[0m\n' "$*"; }

# 从单行 JSON 提取首个字符串字段(不依赖 jq;值内不含转义引号即可)
str_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p" | head -n 1; }

submit_ticket() {
  curl -s -X POST "$BASE_URL/api/tickets/submit" -H "$AUTH" -H "$JSON" \
    -d "{\"message\":\"$1\"}" | str_field ticketId
}

approve_ticket() {
  curl -s -X POST "$BASE_URL/api/tickets/$1/approve" -H "$AUTH" -H "$JSON" \
    -d "$2" > /dev/null
}

# 聊天入口:sessionId 在请求体(非 header),同步返回 {reply, capability, matchedBy, confidence}
chat() {
  curl -s -X POST "$BASE_URL/api/dolphin/chat" -H "$JSON" \
    -d "{\"sessionId\":\"$1\",\"message\":\"$2\"}" | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
}

# 轮询状态直到命中期望值;提前进入其他非在途状态则返回失败(不空等)
wait_status() {
  local id=$1 expect=$2 deadline=$((SECONDS + TIMEOUT_SECONDS)) status=""
  while ((SECONDS < deadline)); do
    status=$(curl -s -H "$AUTH" "$BASE_URL/api/tickets/$id/status" | str_field status)
    if [ "$status" = "$expect" ]; then
      info "status=$status"
      return 0
    fi
    case "$status" in
      NEW|RUNNING|PROCESSING|"") ;;          # 在途,继续等
      *) info "status=$status"; return 1 ;;  # 审批中断/终态:不空等
    esac
    sleep 1
  done
  fail "等待状态超时: expected=$expect actual=${status:-<空>}"
  return 1
}

show_timeline() {
  local body
  body=$(curl -s -H "$AUTH" "$BASE_URL/api/tickets/$1/timeline")
  if command -v python3 > /dev/null 2>&1; then
    printf '%s' "$body" | python3 -m json.tool
  else
    printf '%s\n' "$body"
  fi
}

# 服务可用性检查(401=Key 不对,000=没启动)
if [ "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE_URL/api/tickets?limit=1")" != "200" ]; then
  fail "服务不可用或 API Key 无效: $BASE_URL(先按 offline profile 启动,并检查 API_KEY)"
  exit 1
fi

# ----------------------------------------------------------------------------
# 第一幕 咨询单:submit → triage → kbAnswer → reply → followup → CLOSED
#   代码入口: TicketController.submit:109 → TicketGraphConfig triage/kbAnswer 节点:93/126
#   预期: 202 受理;时间线 nodePath=[triage,kbAnswer,reply,followup],不含诊断/审批/执行
# ----------------------------------------------------------------------------
say "第一幕 咨询单:知识问答直达结单"
TID=$(submit_ticket "SLB 健康检查一直不通过是什么原因,该如何排查?")
info "ticketId=$TID"
wait_status "$TID" "CLOSED" || true
show_timeline "$TID"

# ----------------------------------------------------------------------------
# 第二幕 故障单全闭环:submit → diagnosis → reply → approval(中断)→ execute → followup
#   代码入口: TicketGraphConfig diagnosis/approval/execute 节点:108/152/169
#            ActionExecutor 基线采样:107 验证:134 → TicketController.approve:201
#   预期: 第一段轮询停在 PENDING_APPROVAL(时间线已含 triage/diagnosis/reply);
#          approve 后续跑,时间线新增 approval→execute→followup 与 :BASELINE/:VERIFY、quality-review
# ----------------------------------------------------------------------------
say "第二幕 故障单全闭环:审批中断 → 批准 → 执行重启 → 验证 → 结单"
TID2=$(submit_ticket "ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复")
info "ticketId=$TID2(此时时间线只到审批中断)"
wait_status "$TID2" "PENDING_APPROVAL" || true
show_timeline "$TID2"

info "approve(approved=true,模拟人工审批)"
approve_ticket "$TID2" '{"approved":true,"approver":"demo-ops","reason":"演示:批准重启"}'
wait_status "$TID2" "CLOSED" || true
show_timeline "$TID2"

# ----------------------------------------------------------------------------
# 第三幕 拒绝单:approve(false) → approval 不签令牌 → execute 门禁 REJECTED → escalateHuman
#   代码入口: ApprovalService.recordDecision → ActionExecutor 令牌门禁 → 条件边分流
#   预期: 终态 ESCALATED;时间线里 executeStatus=REJECTED、nodePath 终点 escalateHuman
# ----------------------------------------------------------------------------
say "第三幕 拒绝单:驳回后令牌门禁拒绝 → 转人工"
TID3=$(submit_ticket "ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复")
info "ticketId=$TID3"
wait_status "$TID3" "PENDING_APPROVAL" || true
info "approve(approved=false)"
approve_ticket "$TID3" '{"approved":false,"approver":"demo-ops","reason":"演示:驳回写操作"}'
wait_status "$TID3" "ESCALATED" || true
show_timeline "$TID3"

# ----------------------------------------------------------------------------
# 第四幕 免审批直执(替代高危演示):安全组开放 443 → reply 后直接 execute → followup
#   代码入口: ActionRegistry.requireApproval=false → 条件边 autoExecute → ActionExecutor
#   说明: offline 桩分诊只产出 故障/咨询,无法复现"高危/超范围"分支;
#         真实模型下分诊 type=高危 时由 triage 条件边直达 escalateHuman(同一时间线可查)。
#   预期: CLOSED;nodePath=[triage,diagnosis,reply,execute,followup](无 approval)
# ----------------------------------------------------------------------------
say "第四幕 免审批直执:安全组放行 443 → 直接执行 → 结单"
TID4=$(submit_ticket "我的 ECS 安全组没有放行 443 端口,帮我开一下")
info "ticketId=$TID4"
wait_status "$TID4" "CLOSED" || true
show_timeline "$TID4"

# ----------------------------------------------------------------------------
# 第五幕 人工池:第三幕的转人工单 → list → claim → resolve(工单自动转 CLOSED)
#   代码入口: HumanTicketController.list:43 / claim:50 / resolve:66 → HumanTicketPool
#   预期: WAITING → CLAIMED → RESOLVED;随后该工单时间线/index 状态为 CLOSED
# ----------------------------------------------------------------------------
say "第五幕 人工池:认领并完结第三幕的转人工工单"
info "WAITING 列表:"
curl -s "$BASE_URL/api/human-tickets?status=WAITING" -H "$AUTH" | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
info "claim $TID3"
curl -s -X POST "$BASE_URL/api/human-tickets/$TID3/claim" -H "$AUTH" -H "$JSON" \
  -d '{"owner":"demo-agent"}' | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
info "resolve $TID3"
curl -s -X POST "$BASE_URL/api/human-tickets/$TID3/resolve" -H "$AUTH" -H "$JSON" \
  -d '{"note":"演示:人工恢复实例连接"}' | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
info "工单 $TID3 当前状态:"
curl -s -H "$AUTH" "$BASE_URL/api/tickets/$TID3/status" | str_field status

# ----------------------------------------------------------------------------
# 第六幕 告警链路:webhook → 去重 → alert_task 匹配 → 异步诊断 → GET diagnoses
#   代码入口: AlertInboundController.webhook:48 → AlertTaskMatcher → AlertDiagnosisService
#   预期: accepted=1、diagnosisScheduled=1;diagnoses 列表出现本条告警的诊断报告
# ----------------------------------------------------------------------------
say "第六幕 告警链路:webhook 受理 → 异步诊断"
curl -s -X POST "$BASE_URL/api/alert/webhook" -H "$JSON" -d '{
  "alerts": [{"status":"firing","labels":{"alertname":"ModelHttp5xxRateHigh","uid":"u-demo","model":"qwen-plus","severity":"critical"},"annotations":{"summary":"模型 5xx 错误率突增"}}]
}' | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
sleep 2
info "GET /api/alert/diagnoses?limit=5:"
curl -s "$BASE_URL/api/alert/diagnoses?limit=5" -H "$AUTH" | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }

# ----------------------------------------------------------------------------
# 第七幕 聊天入口:POST /api/dolphin/chat → 意图路由 → 工单能力 → 会话粘性审批
#   代码入口: ChatController.chat:46 → IntentRouter 六级降级(STICKY/EXACT/REGEX/KEYWORD/LLM/FALLBACK)
#            → TicketFlowHandler.handleApprovalReply:120(受理闸门 tryTransition:130,与 REST 同款)
#   预期: 7.1 建单同步跑图停在审批前(matchedBy=REGEX,回复含"待审批处置动作");
#         7.2 同会话回复「同意」→ 闸门 → 决策落库 → resume 续跑结单(matchedBy=STICKY);
#         7.3 并发双发「同意」仅一个赢家,输家收到"已不在待审批状态...未生效"
# ----------------------------------------------------------------------------
say "第七幕 聊天入口:自然语言建单 + 会话粘性审批(聊天驱动 HITL)"
info "7.1 建单:一句话描述故障(命中 REGEX 话术 → ticket-flow,同步跑图停在审批前)"
chat "demo-chat" "帮我提个工单,ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复"

info "7.2 粘性审批:同会话回复「同意」→ 受理闸门 → 决策落库 → resume 续跑结单"
chat "demo-chat" "同意"

info "7.3 闸门演示:新会话建单后并发双发「同意」,条件状态翻转保证只有一个赢家"
chat "demo-gate" "帮我提个工单,ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复" > /dev/null
GA=$(mktemp)
GB=$(mktemp)
chat "demo-gate" "同意" > "$GA" &
chat "demo-gate" "同意" > "$GB" &
wait
info "响应 A:"; cat "$GA"
info "响应 B:"; cat "$GB"
rm -f "$GA" "$GB"
info "说明:一条以「已批准」开头(赢家,决策落库后续跑结单),另一条为「已不在待审批状态...本次回复未生效」(输家,闸门拦截)"

say "导览结束:REST 工单都可用 GET /api/tickets/{id}/timeline 回放完整链路;聊天工单为内部渠道单,按租户隔离对客户 Key 返回 404,链路留痕在审计日志中(内部直调视角可回放)"
