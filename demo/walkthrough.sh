#!/usr/bin/env bash
# ============================================================================
# Dolphin 全链路导览(P1 全链路可见化):八幕演示剧本,offline profile 零外部依赖。
#
# 用法:
#   终端一: mvn spring-boot:run -Dspring-boot.run.profiles=offline
#   终端二: bash demo/walkthrough.sh
#   可选环境变量: BASE_URL(默认 http://localhost:8080)、API_KEY(默认客户A演示 Key)
#                 IM_TOKEN / IM_AES_KEY / IM_RECEIVE_ID(第八幕回调验签凭证,需与应用侧启动参数一致)
#
# 前六幕统一套路:提交 → 轮询状态 → 打印 GET /api/tickets/{id}/timeline,
# 时间线里能看到节点轨迹(graph:<node>:enter|exit)、执行器基线/验证、审批决策与质检留痕。
# 第七幕走聊天入口(POST /api/dolphin/chat,受理即回+「进展」查询),演示异步受理、粘性审批与并发受理闸门。
# 第八幕走 IM 回调入口(POST /api/im/callback/dingtalk),演示审批闭环搬到 IM:验签 → 幂等 → 闸门 → 决策落库 → resume。
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

# 请求体一律经 stdin 传(--data-binary @-),不用 -d 走命令行参数:
# Git Bash(MSYS) 调用原生 Windows curl 时,argv 里的非 ASCII 会按系统代码页(CP936)转换,
# 中文 JSON 体会被压坏导致服务端 UTF-8 解析失败返回 400;stdin 则原样透传字节。
# 该写法在 Linux/macOS 同样成立,属于可移植写法。
submit_ticket() {
  printf '{"message":"%s"}' "$1" \
    | curl -s -X POST "$BASE_URL/api/tickets/submit" -H "$AUTH" -H "$JSON" --data-binary @- \
    | str_field ticketId
}

approve_ticket() {
  printf '%s' "$2" \
    | curl -s -X POST "$BASE_URL/api/tickets/$1/approve" -H "$AUTH" -H "$JSON" --data-binary @- > /dev/null
}

# 聊天入口:sessionId 在请求体(非 header),同步返回 {reply, capability, matchedBy, confidence}
chat() {
  printf '{"sessionId":"%s","message":"%s"}' "$1" "$2" \
    | curl -s -X POST "$BASE_URL/api/dolphin/chat" -H "$JSON" --data-binary @- \
    | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
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

# 服务可用性检查:必须返回 200 且响应体是工单 JSON(含 "tickets")。
# 只判 200 会把「同端口上的其他 Web 服务」误判为可用(例如任意路径都回 200 + SPA HTML 的桌面端),
# 随后所有 JSON 解析静默失败、状态轮询空转超时,排查成本极高。
health_body=$(curl -s -H "$AUTH" "$BASE_URL/api/tickets?limit=1")
if [ "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE_URL/api/tickets?limit=1")" != "200" ] \
   || ! printf '%s' "$health_body" | grep -q '"tickets"'; then
  fail "服务不可用或响应非工单 JSON: $BASE_URL"
  fail "  实际响应: $(printf '%s' "$health_body" | head -c 120)"
  fail "  排查: 先按 offline profile 启动 Dolphin(默认 8080 常被其他程序占用,建议 BASE_URL=http://localhost:39999),并检查 API_KEY"
  exit 1
fi
info "服务就绪: $BASE_URL(响应体含 tickets 字段)"

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
printf '%s' '{"owner":"demo-agent"}' | curl -s -X POST "$BASE_URL/api/human-tickets/$TID3/claim" -H "$AUTH" -H "$JSON" \
  --data-binary @- | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
info "resolve $TID3"
printf '%s' '{"note":"演示:人工恢复实例连接"}' | curl -s -X POST "$BASE_URL/api/human-tickets/$TID3/resolve" -H "$AUTH" -H "$JSON" \
  --data-binary @- | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
info "工单 $TID3 当前状态:"
curl -s -H "$AUTH" "$BASE_URL/api/tickets/$TID3/status" | str_field status

# ----------------------------------------------------------------------------
# 第六幕 告警链路:webhook → 去重 → alert_task 匹配 → 异步诊断 → GET diagnoses
#   代码入口: AlertInboundController.webhook:48 → AlertTaskMatcher → AlertDiagnosisService
#   预期: accepted=1、diagnosisScheduled=1;diagnoses 列表出现本条告警的诊断报告
# ----------------------------------------------------------------------------
say "第六幕 告警链路:webhook 受理 → 异步诊断"
# fingerprint 是告警去重键,也是 AlertEventEntity 的主键来源(手工赋值):payload 必须携带,
# 否则 persist 时主键为 null 会直接 500。这里每次运行取唯一值,保证受理数恒为 1(而非 duplicate)。
FP="fp-walkthrough-$(date +%s)"
info "fingerprint=$FP"
printf '{
  "version": "4",
  "status": "firing",
  "alerts": [{"status":"firing","fingerprint":"%s","startsAt":"%s","endsAt":"0001-01-01T00:00:00Z","labels":{"alertname":"ModelHttp5xxRateHigh","uid":"u-demo","model":"qwen-plus","severity":"critical"},"annotations":{"summary":"模型 5xx 错误率突增"}}]
}' "$FP" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  | curl -s -X POST "$BASE_URL/api/alert/webhook" -H "$JSON" --data-binary @- \
  | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }
sleep 2
info "GET /api/alert/diagnoses?limit=5:"
curl -s "$BASE_URL/api/alert/diagnoses?limit=5" -H "$AUTH" | { command -v python3 > /dev/null 2>&1 && python3 -m json.tool || cat; }

# ----------------------------------------------------------------------------
# 第七幕 聊天入口:POST /api/dolphin/chat → 意图路由 → 工单能力 → 异步受理 + 粘性审批
#   代码入口: ChatController.chat:46 → IntentRouter 六级降级(STICKY/EXACT/REGEX/KEYWORD/LLM/FALLBACK)
#            → TicketFlowHandler.submitTicket(受理落库+粘性预置,图投递 TicketGraphRunner 异步执行)
#            → TicketFlowHandler.handleApprovalReply(受理闸门 tryTransition 同步,resume 投递异步)
#   预期: 7.1 建单受理即回"已受理...正在诊断处理"(matchedBy=REGEX,HTTP 线程不被图执行占住);
#         7.2 同会话回复「同意」→ 闸门+决策落库(同步) → resume 投递异步,回复"已批准,正在执行"(matchedBy=STICKY);
#         7.2b 回复「进展」→ 按工单实际状态回复,终态给出结论并清除粘性;
#         7.3 并发双发「同意」仅一个赢家,输家按实际状态告知(执行中/已完成)
# ----------------------------------------------------------------------------
say "第七幕 聊天入口:自然语言建单 + 异步受理 + 会话粘性审批(聊天驱动 HITL)"
info "7.1 建单:一句话描述故障(命中 REGEX 话术 → ticket-flow,受理即回,图后台异步执行)"
chat "demo-chat" "帮我提个工单,ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复"

info "7.2 粘性审批:同会话回复「同意」→ 受理闸门+决策落库(同步) → resume 投递异步"
chat "demo-chat" "同意"

info "7.2b 进展查询:稍候后回复「进展」,按实际状态回复(终态给出结论并清除粘性)"
sleep 8
chat "demo-chat" "进展"

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
info "说明:一条以「已批准」开头(赢家,决策落库后续跑投递异步),另一条为输家(闸门拦截,按实际状态告知:执行中→正在处理中;若赢家已完成→已完成+终稿)"

# ----------------------------------------------------------------------------
# 第八幕 IM 卡片审批:回调入口驱动 HITL(平台无关入口 + 钉钉参考实现)
#   代码入口: ApprovalCallbackController.callback → ImCallbackAdapter.parse(验签+协议翻译)
#            → ImCallbackDedupService(幂等:同平台同事件只执行一次)
#            → TicketService.tryTransition(状态闸门)→ ApprovalService.recordDecision(决策落库)
#            → TicketGraphRunner.enqueueApprovalResume(唯一续跑入口)
#   预期: 8.1 合法签名回调 → 200 + X-Dolphin-Callback-Result: ACCEPTED,工单续跑至 CLOSED;
#         8.2 同 EventId 重投 → 200 + REPLAYED(回放首次结论,不重复执行);
#         8.3 篡改签名 → 400(验签失败,状态不变)。
#   前置: 回调入口默认关闭(凭证留空 = 适配器自己拒绝),第八幕需带凭证启动:
#         --dolphin.im.callback.dingtalk.token/aes-key/receive-id(见 README 剧本 7)
#   报文现造: demo/GenDingTalkCallback.java 复用生产实现 DingTalkCrypto(纯 JDK,零 SDK),
#         以 java 单文件源码模式运行,classpath 只需 target/classes(先 mvn -o -q compile)。
# ----------------------------------------------------------------------------
say "第八幕 IM 卡片审批:回调驱动 HITL(验签 → 幂等 → 闸门 → 决策落库 → resume)"
IM_TOKEN=${IM_TOKEN:-demo-im-token}
IM_AES_KEY=${IM_AES_KEY:-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG}
IM_RECEIVE_ID=${IM_RECEIVE_ID:-demo-receive-id}
CB_URL="$BASE_URL/api/im/callback/dingtalk"

TID8=$(submit_ticket "ECS 服务器 i-abc123 连不上了,请帮我重启实例恢复")
info "ticketId=$TID8(回调前卡在待审批,等 IM 侧的审批动作)"
wait_status "$TID8" "PENDING_APPROVAL" || true

TMP8=$(mktemp)          # 只由 bash/sed 读写:原生 Windows curl 拿 MSYS 的 /tmp/xxx 会写不进去
QUERY8=""; BODY8=""; CODE8=""; RESP8=""
if command -v java > /dev/null 2>&1 && [ -f target/classes/com/dolphin/im/DingTalkCrypto.class ]; then
  EVT8="evt-im-$(date +%s)"
  java -cp target/classes demo/GenDingTalkCallback.java \
    "$IM_TOKEN" "$IM_AES_KEY" "$IM_RECEIVE_ID" "$TID8" "$EVT8" approve "approver-demo" > "$TMP8"
  QUERY8=$(sed -n '1p' "$TMP8"); BODY8=$(sed -n '2p' "$TMP8")
  info "8.1 合法签名回调(EventId=$EVT8 是幂等键,平台重投必带同一个):"
  # 用 -i 把应答头并进 stdout 再切分,不用 -o/-D 临时文件
  RESP8=$(curl -s -i -X POST "$CB_URL?$QUERY8" -H "$JSON" --data-binary "$BODY8" | tr -d '\r')
  CODE8=$(printf '%s\n' "$RESP8" | sed -n '1p' | awk '{print $2}')
  RESULT8=$(printf '%s\n' "$RESP8" | grep -i '^X-Dolphin-Callback-Result:' | awk '{print $2}')
  ACK8=$(printf '%s\n' "$RESP8" | awk 'f{print; exit} /^$/{f=1}')
  info "  HTTP $CODE8 · X-Dolphin-Callback-Result: ${RESULT8:-<无>}"
  info "  应答体(钉钉要加密应答,回明文会被判失败并重投): $(printf '%s' "$ACK8" | head -c 72)…"
else
  CODE8="SKIP"
fi

if [ "$CODE8" = "200" ]; then
  info "  回调已受理 → resume 图执行,等结单:"
  wait_status "$TID8" "CLOSED" || true
  show_timeline "$TID8"

  info "8.2 同 EventId 重投(平台重投是常态,幂等层须回放首次结论):"
  RESP8=$(curl -s -i -X POST "$CB_URL?$QUERY8" -H "$JSON" --data-binary "$BODY8" | tr -d '\r')
  info "  HTTP $(printf '%s\n' "$RESP8" | sed -n '1p' | awk '{print $2}') · X-Dolphin-Callback-Result: $(printf '%s\n' "$RESP8" | grep -i '^X-Dolphin-Callback-Result:' | awk '{print $2}')"

  info "8.3 篡改签名(验签失败 → 400,状态与决策都不动):"
  TAMPERED8=$(printf '%s' "$QUERY8" | sed 's/msg_signature=[0-9a-fA-F]*/msg_signature=deadbeef/')
  RESP8=$(curl -s -i -X POST "$CB_URL?$TAMPERED8" -H "$JSON" --data-binary "$BODY8" | tr -d '\r')
  info "  HTTP $(printf '%s\n' "$RESP8" | sed -n '1p' | awk '{print $2}') · $(printf '%s\n' "$RESP8" | awk 'f{print; exit} /^$/{f=1}')"
  info "  工单状态(回调被拒,应仍为终态): $(curl -s -H "$AUTH" "$BASE_URL/api/tickets/$TID8/status" | str_field status)"
elif [ "$CODE8" = "SKIP" ]; then
  fail "第八幕跳过:找不到 java 或 target/classes —— 先 mvn -o -q compile 再重跑本脚本"
else
  fail "第八幕跳过:回调入口未配置凭证(默认关闭,HTTP=${CODE8:-<无>})"
  fail "  实际响应: $(printf '%s' "$RESP8" | head -c 160)"
  fail "  带参数重启应用后可复现: --dolphin.im.callback.dingtalk.token/aes-key/receive-id(见 README 剧本 7)"
fi
rm -f "$TMP8"

say "导览结束:REST 工单都可用 GET /api/tickets/{id}/timeline 回放完整链路;聊天工单为内部渠道单,按租户隔离对客户 Key 返回 404,链路留痕在审计日志中(内部直调视角可回放)"
