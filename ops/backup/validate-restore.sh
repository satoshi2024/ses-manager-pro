#!/usr/bin/env bash
# ============================================================
# HFP-03-008 復元検証（staging / recovery target を read-only で検証）
#
# 全チェックが PASS した場合のみ READY_FOR_CUTOVER を返す。
# 検証項目:
#   1. plan 検証（tamper / EXPIRED）
#   2. DB: Flyway / CHECK TABLE / critical counts / markers / 参照解決
#   3. uploads: inventory SHA（checkpoint metadata 照合）/ 参照存在 / orphan report
#   4. read-only app smoke（APP_SMOKE_SCRIPT。scheduler/Flyway/mail/外部API/cleanup を
#      recovery 用 profile で無効化した状態を前提とする）
#
# usage: validate-restore.sh --plan <plan-id> [--uploads-dir DIR]
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/plan.sh"
. "$LIB_DIR/validate-db.sh"
. "$LIB_DIR/validate-uploads.sh"
. "$LIB_DIR/mysql-options.sh"

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
VALIDATION_STATE="VALIDATING"
REPORT_JSON=""

usage() {
  cat <<'EOF'
Usage: validate-restore.sh --plan <plan-id> [--uploads-dir DIR] [--smoke SCRIPT]
環境変数: TARGET_HOST, TARGET_PORT, TARGET_USER, TARGET_PASSWORD_FILE,
TARGET_DATABASE, TARGET_SSL_CAPATH, PLANS_DIR, BACKUP_WORK_DIR,
VALIDATE_TABLES, VALIDATE_MARKERS_JSON, VALIDATE_DB_REFERENCE_QUERIES
EOF
}

main() {
  common::require_env TARGET_HOST
  common::require_env TARGET_PORT
  common::require_env TARGET_USER
  common::require_env TARGET_PASSWORD_FILE
  common::require_env TARGET_DATABASE
  common::require_env PLANS_DIR
  common::require_env BACKUP_WORK_DIR
  TARGET_PORT=${TARGET_PORT:-3306}

  # AC-008-01: MYSQL_PWD 禁止。秘密は option file のみ。
  if [[ -n "${MYSQL_PWD:-}" ]]; then
    echo "validate-db: MYSQL_PWD の使用は禁止です（TARGET_PASSWORD_FILE を使用してください）" >&2
    exit 18
  fi
  unset MYSQL_PWD 2>/dev/null || true

  # R1 P1-05: 検証先への接続は VERIFY 系 TLS のみ許可（DISABLED/平文は拒否）
  case "${TARGET_TLS_MODE:-VERIFY_CA}" in
    VERIFY_IDENTITY|VERIFY_CA) TARGET_TLS_MODE_SAFE=${TARGET_TLS_MODE:-VERIFY_CA} ;;
    *)
      validate_db::fail "TARGET_TLS_MODE は VERIFY_CA / VERIFY_IDENTITY のみ許可されます（受信: ${TARGET_TLS_MODE:-未設定}）"
      ;;
  esac
  [[ -n "${TARGET_SSL_CAPATH:-}" ]] || validate_db::fail "TARGET_SSL_CAPATH が未設定です（VERIFY 系 TLS には CA 証明書が必要）"

  local plan_path="$PLANS_DIR/$PLAN_ID.json"
  plan::verify "$plan_path" || validate_db::fail "plan の検証に失敗しました"
  local state
  state=$(plan::status "$plan_path")
  [[ "$state" == "APPLYABLE" || "$state" == "RESTORED" ]] || validate_db::fail "plan は検証対象外（state=$state）"
  local plan_json
  plan_json=$(cat "$plan_path")

  # target 接続（mysql_options::init を再利用）
  # 注意: VAR=val func 形式は bash の local/pop_var_context 不具合を起こすため使わない
  MYSQL_USER="$TARGET_USER"
  MYSQL_PASSWORD_FILE="$TARGET_PASSWORD_FILE"
  MYSQL_PORT="$TARGET_PORT"
  MYSQL_SSL_CAPATH="${TARGET_SSL_CAPATH:-}"
  MYSQL_TLS_MODE="$TARGET_TLS_MODE_SAFE"
  mysql_options::init || validate_db::fail "mysql option file を作成できません（接続設定を確認してください）"
  TARGET_OPT_ARGS=("${MYSQL_OPT_ARGS[@]}" -h "$TARGET_HOST" "$TARGET_DATABASE")
  # mysql_options::init の trap が option file を掃除する

  # R1 P1-04: checkpoint metadata は plan が参照する checkpoint の index file に限定
  # （find | head -1 の任意 file ではなく、plan の effective_checkpoint.index を読む）
  local ckpt_file
  ckpt_file=$(printf '%s' "$plan_json" | jq -r '.effective_checkpoint.index // empty' | sed 's/\.json$//')
  local ckpt_index=""
  if [[ -n "$ckpt_file" && -f "$BACKUP_WORK_DIR/index/$ckpt_file.json" ]]; then
    ckpt_index="$BACKUP_WORK_DIR/index/$ckpt_file.json"
  else
    validate_db::fail "plan の effective_checkpoint の index file がありません: $ckpt_file（検証は plan の checkpoint に限定）"
  fi
  local flyway_expected="" uploads_inv="[]"
  flyway_expected=$(jq -r '.flyway_max_success // empty' "$ckpt_index")
  uploads_inv=$(jq -r '.uploads.inventory // []' "$ckpt_index" 2>/dev/null)

  local checks_json="{}"
  local ok=true

  # DB 検証
  if validate_db::flyway "$flyway_expected"; then
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {flyway: "PASS"}')
  else
    ok=false
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {flyway: "FAIL"}')
  fi

  if validate_db::check_tables "${VALIDATE_TABLES:-}"; then
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {check_table: "PASS"}')
  else
    ok=false
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {check_table: "FAIL"}')
  fi

  local counts_json
  counts_json=$(jq -r '.critical_table_counts // {}' "$ckpt_index" 2>/dev/null || echo '{}')
  if validate_db::counts "$counts_json"; then
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {counts: "PASS"}')
  else
    ok=false
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {counts: "FAIL"}')
  fi

  if validate_db::markers "${VALIDATE_MARKERS_JSON:-}"; then
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {markers: "PASS"}')
  else
    ok=false
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {markers: "FAIL"}')
  fi

  # DB→uploads 参照解決
  local refs=""
  refs=$(validate_db::references "${VALIDATE_DB_REFERENCE_QUERIES:-}")

  # uploads 検証
  local uploads_dir="${UPLOADS_DIR_ARG:-}"
  if [[ -n "$uploads_dir" && -d "$uploads_dir" ]]; then
    if validate_uploads::hash_check "$uploads_dir" "$uploads_inv"; then
      checks_json=$(printf '%s' "$checks_json" | jq -c '. + {uploads_hash: "PASS"}')
    else
      ok=false
      checks_json=$(printf '%s' "$checks_json" | jq -c '. + {uploads_hash: "FAIL"}')
    fi

    if validate_uploads::check_references "$uploads_dir" "$refs"; then
      checks_json=$(printf '%s' "$checks_json" | jq -c '. + {uploads_references: "PASS"}')
    else
      ok=false
      checks_json=$(printf '%s' "$checks_json" | jq -c '. + {uploads_references: "FAIL"}')
    fi

    local orphans
    orphans=$(validate_uploads::orphan_report "$uploads_dir" "$refs")
    checks_json=$(printf '%s' "$checks_json" | jq -c --argjson o "$orphans" '. + {orphan_files: $o}')
  else
    ok=false
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {uploads: "SKIP(no dir)"}')
  fi

  # read-only app smoke（version 管理された executable）
  if [[ -n "${APP_SMOKE_SCRIPT:-}" && -x "$APP_SMOKE_SCRIPT" ]]; then
    local smoke_rc=0
    TARGET_HOST="$TARGET_HOST" TARGET_PORT="$TARGET_PORT" TARGET_DATABASE="$TARGET_DATABASE" \
    TARGET_OPT_ARGS_JSON="$(printf '%s' "${TARGET_OPT_ARGS[*]}")" \
      bash "$APP_SMOKE_SCRIPT" || smoke_rc=$?
    if (( smoke_rc == 0 )); then
      checks_json=$(printf '%s' "$checks_json" | jq -c '. + {app_smoke: "PASS"}')
    else
      ok=false
      checks_json=$(printf '%s' "$checks_json" | jq -c --argjson rc "$smoke_rc" '. + {app_smoke: ("FAIL rc=" + ($rc|tostring))}')
    fi
  else
    ok=false
    checks_json=$(printf '%s' "$checks_json" | jq -c '. + {app_smoke: "SKIP(no script)"}')
  fi

  local final_state="FAILED_VALIDATION"
  [[ "$ok" == "true" ]] && final_state="READY_FOR_CUTOVER"
  VALIDATION_STATE=$final_state

  REPORT_JSON=$(jq -n \
    --arg state "$VALIDATION_STATE" \
    --arg plan_id "$PLAN_ID" \
    --arg target_database "$TARGET_DATABASE" \
    --argjson checks "$checks_json" \
    '{state: $state, plan_id: $plan_id, target_database: $target_database, checks: $checks}')
  printf '%s\n' "$REPORT_JSON"
  if [[ "$ok" == "true" ]]; then
    return 0
  fi
  return 1
}

PLAN_ID=""
UPLOADS_DIR_ARG=""
APP_SMOKE_SCRIPT=""
while (($#)); do
  case "$1" in
    --plan) PLAN_ID=$2; shift 2 ;;
    --plan=*) PLAN_ID=${1#--plan=}; shift ;;
    --uploads-dir) UPLOADS_DIR_ARG=$2; shift 2 ;;
    --smoke) APP_SMOKE_SCRIPT=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$PLAN_ID" ]] || { usage >&2; exit 2; }
main
