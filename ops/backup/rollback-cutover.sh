#!/usr/bin/env bash
# ============================================================
# HFP-03-009 rollback（write-enable 前に限定）
#
# 条件:
#   - CUTOVER_STATE が write-enabled でないこと
#     （write-enabled 後の rollback は新規 transaction を失うため禁止）
#   - 旧環境の read-only smoke（OLD_ENV_SMOKE_SCRIPT）が PASS すること
# 成功時 state=rolled-back とし、新環境は activation されない。
#
# usage: rollback-cutover.sh --plan <plan-id>
# 環境変数: PLANS_DIR, CUTOVER_STATE_FILE, OLD_ENV_SMOKE_SCRIPT,
# OLD_ENV_HOST / OLD_ENV_PORT / OLD_ENV_USER / OLD_ENV_PASSWORD / OLD_ENV_DATABASE
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
. "$LIB_DIR/cutover-state.sh"

usage() {
  cat <<'EOF'
Usage: rollback-cutover.sh --plan <plan-id>
環境変数: PLANS_DIR, CUTOVER_STATE_FILE, OLD_ENV_SMOKE_SCRIPT
EOF
}

main() {
  common::require_env PLANS_DIR
  common::require_env CUTOVER_STATE_FILE

  local plan_path="$PLANS_DIR/$PLAN_ID.json"
  [[ -f "$plan_path" ]] || { echo "[rollback] ERROR: plan がありません: $plan_path" >&2; exit 1; }
  plan::verify "$plan_path" || { echo "[rollback] ERROR: plan の検証に失敗しました" >&2; exit 1; }

  local cur
  cur=$(cutover::read_state)
  case "$cur" in
    write-enabled)
      echo "[rollback] ERROR: write-enabled 後の rollback は禁止です（新規 transaction を失う）" >&2
      exit 1
      ;;
    rolled-back)
      echo "[rollback] ERROR: すでに rolled-back 済みです" >&2
      exit 1
      ;;
    initial|staged|read-only-smoke-passed|single-writer)
      :  # rollback 可能（write-enable 前）
      ;;
    *)
      echo "[rollback] ERROR: 未知の state です: $cur" >&2
      exit 1
      ;;
  esac

  # 旧環境の read-only smoke が PASS すること
  [[ -n "${OLD_ENV_SMOKE_SCRIPT:-}" && -x "$OLD_ENV_SMOKE_SCRIPT" ]] || {
    echo "[rollback] ERROR: OLD_ENV_SMOKE_SCRIPT がありません（旧環境の健全性を確認できません）" >&2
    exit 1
  }
  local smoke_rc=0
  OLD_ENV_HOST="${OLD_ENV_HOST:-}" OLD_ENV_PORT="${OLD_ENV_PORT:-}" \
  OLD_ENV_USER="${OLD_ENV_USER:-}" OLD_ENV_PASSWORD="${OLD_ENV_PASSWORD:-}" \
  OLD_ENV_DATABASE="${OLD_ENV_DATABASE:-}" \
    bash "$OLD_ENV_SMOKE_SCRIPT" || smoke_rc=$?
  if (( smoke_rc != 0 )); then
    echo "[rollback] ERROR: 旧環境の read-only smoke が失敗しました（rollback 不可）" >&2
    exit 1
  fi

  cutover::write_state "rolled-back" "$PLAN_ID"
  jq -n --arg state "rolled-back" --arg plan_id "$PLAN_ID" \
    '{state: $state, plan_id: $plan_id}'
  return 0
}

PLAN_ID=""
while (($#)); do
  case "$1" in
    --plan) PLAN_ID=$2; shift 2 ;;
    --plan=*) PLAN_ID=${1#--plan=}; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$PLAN_ID" ]] || { usage >&2; exit 2; }
main
