#!/usr/bin/env bash
# HFP-03-009 cutover / rollback test
#
# 正常: validation READY → 承認 → smoke PASS → write-enable → write-enabled
# 負:   smoke FAIL → rolled-back / write-enable 後 rollback 禁止 /
#       validation 未 READY / 承認不足 / write-enable 失敗 / 既 write-enabled
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
CUTOVER="$ROOT/ops/backup/cutover.sh"
ROLLBACK="$ROOT/ops/backup/rollback-cutover.sh"
VERIFIER="$ROOT/ops/backup/providers/approval-verifier-local.sh"

FAKE_PW='S3cr3t-Value-XYZ-009'

setup_cutover() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/plans" "$T/pubkeys"
  export PLANS_DIR="$T/plans" CUTOVER_STATE_FILE="$T/state.json" \
    VALIDATION_STATE_FILE="$T/validation.json"
  # ダミー plan（plan::write で SHA sidecar 生成）
  # shellcheck disable=SC1091
  . "$LIB/plan.sh"
  local content
  content=$(jq -n '{schema_version:1, kind:"restore-plan",
    state:"READY",
    requested_target:"2026-08-14T09:15:00Z", rpo_seconds:0,
    source_server_uuid:"11111111-2222-3333-4444-555555555555",
    target:{allowlist_ref:"default", min_approvals:2},
    valid_until_utc:"2099-01-01T00:00:00Z"}')
  plan::write "$content" "$PLANS_DIR" > /dev/null
  export PLAN_ID
  PLAN_ID=$(jq -r '.plan_id' "$PLANS_DIR"/*.json)
  # validation report
  jq -n --arg p "$PLAN_ID" '{state:"READY_FOR_CUTOVER", plan_id:$p}' > "$T/validation.json"
  # smoke fixtures
  cat > "$T/smoke-ok.sh" <<'EOF'
#!/usr/bin/env bash
exit "${SMOKE_RC:-0}"
EOF
  cat > "$T/old-smoke-ok.sh" <<'EOF'
#!/usr/bin/env bash
exit "${OLD_SMOKE_RC:-0}"
EOF
  chmod +x "$T/smoke-ok.sh" "$T/old-smoke-ok.sh"
  export APP_SMOKE_SCRIPT="$T/smoke-ok.sh" OLD_ENV_SMOKE_SCRIPT="$T/old-smoke-ok.sh"
  # write-enable provider（argv / environ に秘密を載せないことを検証）
  cat > "$T/write-enable.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "argv:$*" >> "${WRITE_ENABLE_LOG:-/dev/null}"
env | sort >> "${WRITE_ENABLE_ENV_LOG:-/dev/null}"
if printf '%s' "$*" | grep -q -- '-p'; then
  echo "write-enable: password on argv" >&2
  exit 9
fi
if [[ -n "${TARGET_PASSWORD:-}" || -n "${MYSQL_PWD:-}" ]]; then
  echo "write-enable: password in environ" >&2
  exit 9
fi
[[ -n "${TARGET_PASSWORD_FILE:-}" ]] || { echo "TARGET_PASSWORD_FILE required" >&2; exit 1; }
exit "${WRITE_RC:-0}"
EOF
  chmod +x "$T/write-enable.sh"
  export WRITE_ENABLE_COMMAND="$T/write-enable.sh"
  export WRITE_ENABLE_LOG="$T/we-argv.log" WRITE_ENABLE_ENV_LOG="$T/we-env.log"
  : > "$WRITE_ENABLE_LOG" ; : > "$WRITE_ENABLE_ENV_LOG"
  # 承認鍵
  openssl genrsa -out "$T/priv1.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv1.pem" -pubout -out "$T/pub1.pem" 2>/dev/null
  openssl genrsa -out "$T/priv2.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv2.pem" -pubout -out "$T/pub2.pem" 2>/dev/null
  cp "$T/pub1.pem" "$T/pubkeys/alice.pem"
  cp "$T/pub2.pem" "$T/pubkeys/bob.pem"
  export APPROVAL_PUBKEY_DIR="$T/pubkeys"
  # fake mysql（target UUID）
  export MYSQL_CLIENT_BIN="$HERE/fixtures/bin/mysql"
  export TARGET_HOST=10.0.0.9 TARGET_USER=restore-svc TARGET_PORT=3306
  mkdir -p "$T/capath"
  export TARGET_SSL_CAPATH="$T/capath" TARGET_TLS_MODE=VERIFY_CA
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  export TARGET_PASSWORD_FILE="$T/pw" TARGET_DATABASE=ses_manager_db
  export FAKE_UUID='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
}

make_claims() {
  local plan_path="$PLANS_DIR/$PLAN_ID.json"
  local plan_sha
  plan_sha=$(tr -d ' \t\r\n' < "$plan_path.sha256")
  local future
  future=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)
  local a
  for a in alice bob; do
    local key
    [[ "$a" == "alice" ]] && key="$T/priv1.pem" || key="$T/priv2.pem"
    jq -n --arg ps "$plan_sha" --arg tu "$FAKE_UUID" --arg a "$a" --arg r manager \
      --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$future" --arg ct "CHG-009" \
      '{plan_sha256:$ps,target_uuid:$tu,change_ticket:$ct,actor:$a,role:$r,
        issued_at_utc:$i,expires_at_utc:$e}' > "$T/claim-$a.json"
    "$VERIFIER" sign "$T/claim-$a.json" "$key"
  done
}

run_cutover() {
  OUT=$("$CUTOVER" --plan "$PLAN_ID" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  RC=$?
}

state_now() { jq -r '.state' "$T/state.json"; }

case_cutover_normal() {
  setup_cutover
  make_claims
  export SMOKE_RC=0 WRITE_RC=0
  run_cutover
  assert_zero "$RC" "正常 cutover 成功"
  assert_contains "$OUT" '"state": "write-enabled"' "write-enabled を返す"
  assert_eq "write-enabled" "$(state_now)" "state file が write-enabled"
  if grep -q "$FAKE_PW" "$WRITE_ENABLE_LOG" "$WRITE_ENABLE_ENV_LOG" "$TEST_LOG" 2>/dev/null; then
    test_fail "cutover-secret-scan" "write-enable 経路に秘密が露出"
  else
    test_assert "cutover-secret-scan"
  fi
  if grep -q 'TARGET_PASSWORD=' "$WRITE_ENABLE_ENV_LOG" 2>/dev/null; then
    test_fail "cutover-no-target-password-env" "TARGET_PASSWORD が environ に残存"
  else
    test_assert "cutover-no-target-password-env"
  fi
}

case_cutover_mysql_pwd_rejected() {
  setup_cutover
  make_claims
  export MYSQL_PWD="$FAKE_PW" SMOKE_RC=0 WRITE_RC=0
  run_cutover
  assert_eq 18 "$RC" "MYSQL_PWD 使用検出で exit 18"
  assert_contains "$OUT" "MYSQL_PWD" "MYSQL_PWD を拒否メッセージに含む"
  unset MYSQL_PWD
}

case_cutover_smoke_fail_rollback() {
  setup_cutover
  make_claims
  export SMOKE_RC=7 WRITE_RC=0
  run_cutover
  assert_eq 3 "$RC" "smoke 失敗は exit 3（rollback 指示）"
  assert_contains "$OUT" "rollback" "rollback を通知"
  assert_eq "rolled-back" "$(state_now)" "state file が rolled-back"
  assert_not_contains "$OUT" "write-enabled" "write-enable を実行しない"
}

case_cutover_validation_not_ready() {
  setup_cutover
  make_claims
  jq -n --arg p "$PLAN_ID" '{state:"FAILED_VALIDATION"}' > "$T/validation.json"
  export SMOKE_RC=0
  run_cutover
  assert_nonzero "$RC" "validation 未 READY は非 0"
  assert_contains "$OUT" "READY_FOR_CUTOVER" "理由を表示"
}

case_cutover_approval_missing() {
  setup_cutover
  make_claims
  export SMOKE_RC=0
  OUT=$("$CUTOVER" --plan "$PLAN_ID" --approval "$T/claim-alice.json" --approval "$T/claim-alice.json" 2>&1)
  assert_nonzero "$?" "同一 actor の承認は拒否"
  assert_contains "$OUT" "承認" "承認理由"
}

case_cutover_write_enable_fail() {
  setup_cutover
  make_claims
  export SMOKE_RC=0 WRITE_RC=1
  run_cutover
  assert_nonzero "$RC" "write-enable 失敗は非 0"
  assert_eq "single-writer" "$(state_now)" "state は single-writer のまま"
}

case_cutover_already_write_enabled() {
  setup_cutover
  make_claims
  jq -n --arg p "$PLAN_ID" '{state:"write-enabled", plan_id:$p}' > "$T/state.json"
  export SMOKE_RC=0
  run_cutover
  assert_nonzero "$RC" "write-enabled 後は非 0"
  assert_contains "$OUT" "write-enabled" "理由を表示"
}

case_cutover_no_smoke_script() {
  setup_cutover
  make_claims
  unset APP_SMOKE_SCRIPT
  run_cutover
  assert_nonzero "$RC" "smoke script 未設定は非 0"
}

case_rollback_after_write_enabled_forbidden() {
  setup_cutover
  jq -n --arg p "$PLAN_ID" '{state:"write-enabled", plan_id:$p}' > "$T/state.json"
  export OLD_SMOKE_RC=0
  OUT=$("$ROLLBACK" --plan "$PLAN_ID" 2>&1)
  assert_nonzero "$?" "write-enabled 後 rollback は禁止"
  assert_contains "$OUT" "禁止" "理由を表示"
}

case_rollback_state_file_missing_forbidden() {
  setup_cutover
  # R1 P1-07: state file が存在しない（write-enabled 後に削除された可能性）場合は
  # rollback を許可しない（unknown 扱い）
  rm -f "$T/state.json"
  export OLD_SMOKE_RC=0
  OUT=$("$ROLLBACK" --plan "$PLAN_ID" 2>&1)
  assert_nonzero "$?" "state file 欠如の rollback は拒否"
  assert_contains "$OUT" "CUTOVER_STATE_FILE" "理由を表示"
}

case_rollback_normal() {
  setup_cutover
  jq -n --arg p "$PLAN_ID" '{state:"read-only-smoke-passed", plan_id:$p}' > "$T/state.json"
  export OLD_SMOKE_RC=0
  OUT=$("$ROLLBACK" --plan "$PLAN_ID" 2>&1)
  assert_zero "$?" "rollback 成功"
  assert_eq "rolled-back" "$(state_now)" "state file が rolled-back"
}

case_rollback_from_single_writer() {
  setup_cutover
  jq -n --arg p "$PLAN_ID" '{state:"single-writer", plan_id:$p}' > "$T/state.json"
  export OLD_SMOKE_RC=0
  OUT=$("$ROLLBACK" --plan "$PLAN_ID" 2>&1)
  assert_zero "$?" "single-writer からの rollback 成功（write-enable 前）"
  assert_eq "rolled-back" "$(state_now)" "state file が rolled-back"
}

case_rollback_old_env_fail() {
  setup_cutover
  jq -n --arg p "$PLAN_ID" '{state:"staged", plan_id:$p}' > "$T/state.json"
  export OLD_SMOKE_RC=1
  OUT=$("$ROLLBACK" --plan "$PLAN_ID" 2>&1)
  assert_nonzero "$?" "旧環境 smoke 失敗は rollback 拒否"
  assert_eq "staged" "$(state_now)" "state は staged のまま"
}

run_case case_cutover_normal
run_case case_cutover_mysql_pwd_rejected
run_case case_cutover_smoke_fail_rollback
run_case case_cutover_validation_not_ready
run_case case_cutover_approval_missing
run_case case_cutover_write_enable_fail
run_case case_cutover_already_write_enabled
run_case case_cutover_no_smoke_script
run_case case_rollback_after_write_enabled_forbidden
run_case case_rollback_state_file_missing_forbidden
run_case case_rollback_normal
run_case case_rollback_from_single_writer
run_case case_rollback_old_env_fail

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-009 cutover/rollback"
