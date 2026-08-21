#!/usr/bin/env bash
# ============================================================
# HFP-03-005 watermark 監視（check-backup.sh）
# 最新の成功 watermark（index / binlog-index / heartbeat）と source の
# 現行 coordinate の差で判定する。古い file の存在だけでは FAIL にしない。
#
# usage: check-backup.sh [--json|--help]
# exit: 0=OK 1=WARN 2=CRITICAL 3=UNKNOWN
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"
. "$LIB_DIR/health.sh"

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}

INDEX_DIR=${BACKUP_WORK_DIR:-/var/lib/ses-backup}/index
BINLOG_INDEX=${BINLOG_INDEX:-${BINLOG_IMMUTABLE_DIR:-/var/lib/ses-backup/binlog}/binlog-index.json}
BINLOG_STATE=${BINLOG_STATE:-/var/lib/ses-backup/archive-state.json}
REPO_CHECK_TS=${REPO_CHECK_TS:-/var/lib/ses-backup/last-repo-check}
DRILL_TS=${DRILL_TS:-/var/lib/ses-backup/last-drill}

main() {
  common::require_env MYSQL_HOST
  common::require_env MYSQL_USER
  common::require_env MYSQL_PASSWORD_FILE
  MYSQL_PORT=${MYSQL_PORT:-3306}

  local full_age ckpt_age
  read -r full_age ckpt_age <<< "$(health::snapshot_ages "$INDEX_DIR")"
  local last_file gap_count newest_epoch
  read -r last_file gap_count newest_epoch <<< "$(health::binlog_watermark "$BINLOG_INDEX")"
  local source_current=""
  local source_ok=true
  if ! source_current=$(health::source_current_file); then
    source_ok=false
    source_current="UNKNOWN"
  fi
  local hb_age
  hb_age=$(health::archiver_heartbeat_age "$BINLOG_STATE")
  local rc_age dr_age
  rc_age=$(health::file_age "$REPO_CHECK_TS")
  dr_age=$(health::file_age "$DRILL_TS")

  health::evaluate "$INDEX_DIR" "$BINLOG_INDEX" "$BINLOG_STATE" "$REPO_CHECK_TS" "$DRILL_TS"
  # source 接続不能は UNKNOWN を最優先にしない（evaluate が raise 済み）

  local status="OK"
  case "$HEALTH_STATE" in
    0) status="OK" ;;
    1) status="WARN" ;;
    2) status="CRITICAL" ;;
    3) status="UNKNOWN" ;;
  esac

  local reasons_json="[]"
  if (( ${#HEALTH_REASONS[@]} > 0 )); then
    reasons_json=$(printf '%s\n' "${HEALTH_REASONS[@]}" | jq -R . | jq -s .)
  fi

  # 実効 lag 秒（最新 closed watermark と source の差の指標）
  local binlog_event_lag_seconds=0
  if [[ -n "$last_file" && "$source_current" != "UNKNOWN" ]]; then
    local ls lc
    ls=${last_file##*.}
    lc=${source_current##*.}
    if [[ "$ls" =~ ^[0-9]+$ && "$lc" =~ ^[0-9]+$ ]]; then
      local behind=$(( 10#$lc - 10#$ls - 1 ))
      (( behind < 0 )) && behind=0
      binlog_event_lag_seconds=$(( behind * HEALTH_CHECKPOINT_CRITICAL_SECONDS ))
    fi
  fi

  local rpo_available=false
  if (( ckpt_age >= 0 && ckpt_age <= 900 )) && (( gap_count == 0 )) \
     && [[ "$source_current" != "UNKNOWN" ]] && [[ -n "$last_file" ]]; then
    rpo_available=true
  fi

  jq -n \
    --arg status "$status" \
    --arg full_age_seconds "$full_age" \
    --arg checkpoint_age_seconds "$ckpt_age" \
    --arg binlog_event_lag_seconds "$binlog_event_lag_seconds" \
    --arg last_closed_file "${last_file:-}" \
    --arg source_current_file "$source_current" \
    --arg gap_count "$gap_count" \
    --arg repository_check_age_seconds "$rc_age" \
    --arg last_drill_age_days "$( (( dr_age >= 0 )) && echo $(( dr_age / 86400 )) || echo -1 )" \
    --arg rpo_available "$rpo_available" \
    --argjson reasons "$reasons_json" \
    '{status: $status,
      full_age_seconds: ($full_age_seconds | tonumber?),
      checkpoint_age_seconds: ($checkpoint_age_seconds | tonumber?),
      binlog_event_lag_seconds: ($binlog_event_lag_seconds | tonumber?),
      last_closed_file: $last_closed_file,
      source_current_file: $source_current_file,
      gap_count: ($gap_count | tonumber?),
      repository_check_age_seconds: ($repository_check_age_seconds | tonumber?),
      last_drill_age_days: ($last_drill_age_days | tonumber?),
      rpo_available: ($rpo_available == "true"),
      reasons: $reasons}'

  exit "$HEALTH_STATE"
}

case "${1:---json}" in
  --json) main ;;
  --help|-h) cat <<'EOF'
Usage: check-backup.sh [--json|--help]
環境変数: MYSQL_HOST, MYSQL_USER, MYSQL_PASSWORD_FILE, BACKUP_WORK_DIR,
BINLOG_INDEX, BINLOG_STATE, REPO_CHECK_TS, DRILL_TS
exit: 0=OK 1=WARN 2=CRITICAL 3=UNKNOWN
EOF
    exit 0 ;;
  *) echo "Usage: check-backup.sh [--json|--help]" >&2; exit 2 ;;
esac
