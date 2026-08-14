#!/usr/bin/env bash
# ============================================================
# HFP-03-009 cutover（write-enable 境界の制御）
#
# 前提: validate-restore.sh が READY_FOR_CUTOVER を返している。
# 流れ:
#   1. plan 検証（plan::verify + status=APPLYABLE）
#   2. validation report の state == READY_FOR_CUTOVER を確認
#   3. 状態遷移 guard（CUTOVER_STATE_FILE が単一の真実）
#      initial/staged -> read-only-smoke-passed -> single-writer -> write-enabled
#      （rollback は write-enable 前のみ: rollback-cutover.sh）
#   4. 二者承認（plan SHA / target UUID に bind）
#   5. read-only smoke 失敗 -> state=rolled-back で exit 3（旧環境へ）
#   6. WRITE_ENABLE_COMMAND（version 管理された executable）で write-enable
#
# usage: cutover.sh --plan <plan-id> --approval <claim1> --approval <claim2>
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
# shellcheck disable=SC1091
. "$LIB_DIR/common.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/plan.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/approval.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/cutover-state.sh"

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}

usage() {
  cat <<'EOF'
Usage: cutover.sh --plan <plan-id> --approval <claim1.json> --approval <claim2.json>
環境変数: PLANS_DIR, CUTOVER_STATE_FILE, VALIDATION_STATE_FILE,
APP_SMOKE_SCRIPT, WRITE_ENABLE_COMMAND, TARGET_HOST, TARGET_USER,
TARGET_PASSWORD_FILE, TARGET_DATABASE, TARGET_SSL_CAPATH,
APPROVAL_PUBKEY_DIR
EOF
}

main() {
  common::require_env PLANS_DIR
  common::require_env CUTOVER_STATE_FILE
  common::require_env VALIDATION_STATE_FILE
  common::require_env TARGET_HOST
  common::require_env TARGET_USER
  common::require_env TARGET_PASSWORD_FILE
  common::require_env TARGET_DATABASE
  TARGET_PORT=${TARGET_PORT:-3306}
  trap 'rm -f "${TARGET_OPTFILE:-}"' EXIT

  local plan_path="$PLANS_DIR/$PLAN_ID.json"
  [[ -f "$plan_path" ]] || cutover::fail "plan がありません: $plan_path"
  plan::verify "$plan_path" || cutover::fail "plan の検証に失敗しました"
  local pst
  pst=$(plan::status "$plan_path")
  [[ "$pst" == "APPLYABLE" ]] || cutover::fail "plan は適用できません（status=$pst）"

  # validation が READY_FOR_CUTOVER であること
  [[ -f "$VALIDATION_STATE_FILE" ]] || cutover::fail "validation report がありません: $VALIDATION_STATE_FILE"
  local vstate
  vstate=$(jq -r '.state // ""' "$VALIDATION_STATE_FILE")
  [[ "$vstate" == "READY_FOR_CUTOVER" ]] || cutover::fail "validation が READY_FOR_CUTOVER ではありません（state=$vstate）"

  # 状態遷移（initial/staged から開始）
  local cur
  cur=$(cutover::read_state)
  cutover::guard_transition "$cur" "read-only-smoke-passed" || cutover::fail "現在の state から cutover を開始できません（state=$cur）"

  # target UUID（claim の bind 検証に使用）
  local target_uuid_optfile
  target_uuid_optfile=$(mktemp)
  TARGET_OPTFILE=$target_uuid_optfile
  {
    echo '[client]'
    echo "user=$TARGET_USER"
    echo "port=$TARGET_PORT"
    echo "database=$TARGET_DATABASE"
    echo "password=$(head -n1 "$TARGET_PASSWORD_FILE")"
    echo "ssl-mode=${TARGET_TLS_MODE:-VERIFY_CA}"
    [[ -n "${TARGET_SSL_CAPATH:-}" ]] && echo "ssl-capath=$TARGET_SSL_CAPATH"
  } > "$target_uuid_optfile"
  chmod 600 "$target_uuid_optfile"

  local target_uuid
  target_uuid=$("$MYSQL_CLIENT_BIN" --defaults-extra-file="$target_uuid_optfile" -h "$TARGET_HOST" -N -B \
    --execute "SELECT @@server_uuid;" 2>/dev/null)
  [[ -n "$target_uuid" ]] || cutover::fail "target UUID を取得できません"

  # 二者承認（plan SHA / target UUID に bind）
  approval::collect_and_verify "$plan_path" "$target_uuid" "$CLAIM1" "$CLAIM2" \
    || cutover::fail "承認が不足または不正です"

  cutover::write_state "read-only-smoke-passed" "$PLAN_ID"

  # read-only smoke（rollback 判定の境界）
  if [[ -n "${APP_SMOKE_SCRIPT:-}" && -x "$APP_SMOKE_SCRIPT" ]]; then
    local smoke_rc=0
    TARGET_HOST="$TARGET_HOST" TARGET_PORT="$TARGET_PORT" TARGET_USER="$TARGET_USER" \
    TARGET_PASSWORD="$(head -n1 "$TARGET_PASSWORD_FILE")" TARGET_DATABASE="$TARGET_DATABASE" \
      bash "$APP_SMOKE_SCRIPT" || smoke_rc=$?
    if (( smoke_rc != 0 )); then
      echo "cutover: read-only smoke 失敗（rc=$smoke_rc）。旧環境への rollback 指示（state=rolled-back）。" >&2
      cutover::write_state "rolled-back" "$PLAN_ID"
      exit 3
    fi
  else
    cutover::fail "APP_SMOKE_SCRIPT がありません（read-only smoke 未実施では cutover 不可）"
  fi

  # single-writer -> write-enabled
  cutover::guard_transition "$(cutover::read_state)" "single-writer" || cutover::fail "状態遷移に失敗"
  cutover::write_state "single-writer" "$PLAN_ID"

  local wc=${WRITE_ENABLE_COMMAND:-}
  [[ -n "$wc" && -x "$wc" ]] || cutover::fail "WRITE_ENABLE_COMMAND がありません（write-enable 未実施は cutover 不可）"
  if ! TARGET_HOST="$TARGET_HOST" TARGET_USER="$TARGET_USER" TARGET_PORT="$TARGET_PORT" \
    TARGET_PASSWORD="$(head -n1 "$TARGET_PASSWORD_FILE")" TARGET_DATABASE="$TARGET_DATABASE" \
    PLAN_ID="$PLAN_ID" \
    bash "$wc"; then
    cutover::write_state "single-writer" "$PLAN_ID"   # write-enable 失敗は single-writer のまま
    cutover::fail "write-enable に失敗しました（state は single-writer のまま）"
  fi

  cutover::write_state "write-enabled" "$PLAN_ID"
  jq -n \
    --arg state "write-enabled" \
    --arg plan_id "$PLAN_ID" \
    --arg target_uuid "$target_uuid" \
    '{state: $state, plan_id: $plan_id, target_uuid: $target_uuid}'
  return 0
}

cutover::fail() { echo "[cutover] ERROR: $*" >&2; exit 1; }

PLAN_ID=""
CLAIM1=""
CLAIM2=""
TARGET_OPTFILE=""
while (($#)); do
  case "$1" in
    --plan) PLAN_ID=$2; shift 2 ;;
    --plan=*) PLAN_ID=${1#--plan=}; shift ;;
    --approval) if [[ -z "$CLAIM1" ]]; then CLAIM1=$2; else CLAIM2=$2; fi; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$PLAN_ID" && -n "$CLAIM1" && -n "$CLAIM2" ]] || { usage >&2; exit 2; }
main
