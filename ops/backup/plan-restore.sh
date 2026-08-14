#!/usr/bin/env bash
# ============================================================
# HFP-03-006 restore plan 生成（read-only）
#
# 要求時刻（RFC 3339 UTC）→ 実効復旧点（最新 VALID checkpoint <= target）
# → base full（同一 lineage の最新 <= checkpoint）→ 必要 binlog の連続性・
# snapshot 解決 → canonical plan JSON + plan.sha256 を保存。
# RPO > 15 分や依存不足は plan を RPO_MISSED / 不生成とし apply 不可にする。
#
# usage: plan-restore.sh --target 2026-08-14T02:30:00Z [--plan-dir DIR]
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/selector.sh"
. "$LIB_DIR/plan.sh"

INDEX_DIR=${INDEX_DIR:-${BACKUP_WORK_DIR:-/var/lib/ses-backup}/index}
BINLOG_INDEX=${BINLOG_INDEX:-${BINLOG_IMMUTABLE_DIR:-/var/lib/ses-backup/binlog}/binlog-index.json}
PLANS_DIR=${PLANS_DIR:-${BACKUP_WORK_DIR:-/var/lib/ses-backup}/plans}

PLAN_VALID_HOURS=${PLAN_VALID_HOURS:-24}
RPO_MAX_SECONDS=${RPO_MAX_SECONDS:-900}

usage() {
  cat <<'EOF'
Usage: plan-restore.sh --target YYYY-MM-DDTHH:MM:SSZ [--plan-dir DIR] [--help]

環境変数: BACKUP_WORK_DIR, BINLOG_INDEX, PLANS_DIR, PLAN_VALID_HOURS,
RPO_MAX_SECONDS, ALLOWLIST_REF
EOF
}

main() {
  common::require_env TARGET
  local target=$TARGET
  local target_epoch=""
  if ! target_epoch=$(selector::parse_target "$target"); then
    common::fail "target を解釈できません: $target"
  fi

  [[ -d "$INDEX_DIR" ]] || common::fail "index ディレクトリがありません: $INDEX_DIR"
  [[ -f "$BINLOG_INDEX" ]] || common::fail "binlog-index がありません: $BINLOG_INDEX"

  local entries
  entries=$(selector::load_entries "$INDEX_DIR")
  local lineage
  lineage=$(selector::latest_lineage "$entries")
  [[ -n "$lineage" ]] || common::fail "VALID な checkpoint がありません"

  local ckpt
  ckpt=$(selector::effective_checkpoint "$entries" "$target_epoch" "$lineage")
  if [[ -z "$ckpt" || "$ckpt" == "null" ]]; then
    common::fail "要求時刻以前の VALID checkpoint がありません: $target"
  fi

  local ckpt_time
  ckpt_time=$(printf '%s' "$ckpt" | jq -r '.consistency_time_utc')
  local ckpt_epoch
  ckpt_epoch=$(printf '%s' "$ckpt" | jq -r '.consistency_epoch')

  # RPO 計算（要求時刻 - 実効復旧点）
  local rpo_seconds=$(( target_epoch - ckpt_epoch ))
  local rpo_missed=false
  if (( rpo_seconds > RPO_MAX_SECONDS )); then
    rpo_missed=true
  fi

  local full
  full=$(selector::base_full "$entries" "$ckpt_epoch" "$lineage")
  if [[ -z "$full" || "$full" == "null" ]]; then
    common::fail "実効復旧点以前の VALID full がありません"
  fi
  local full_time
  full_time=$(printf '%s' "$full" | jq -r '.consistency_time_utc')

  # 必要 binlog: full の start → checkpoint の end
  local start_file end_file
  start_file=$(printf '%s' "$full" | jq -r '.binlog_start.file // empty')
  end_file=$(printf '%s' "$ckpt" | jq -r '.binlog_end.file // empty')
  [[ -n "$start_file" && -n "$end_file" ]] || common::fail "coordinate が index にありません"

  local binlogs="[]"
  if ! binlogs=$(selector::resolve_binlogs "$BINLOG_INDEX" "$start_file" "$end_file"); then
    common::fail "必要 binlog の解決に失敗しました"
  fi
  local binlog_count
  binlog_count=$(printf '%s' "$binlogs" | jq 'length')

  local ckpt_id
  ckpt_id=$(printf '%s' "$ckpt" | jq -r '.index_file // empty' | sed 's/\.json$//')
  local full_id
  full_id=$(printf '%s' "$full" | jq -r '.index_file // empty' | sed 's/\.json$//')
  local uploads_id
  uploads_id=$(printf '%s' "$ckpt" | jq -r '.uploads_snapshot_id // empty')

  local state="READY"
  if [[ "$rpo_missed" == "true" ]]; then
    state="RPO_MISSED"
  fi

  local valid_until
  valid_until=$(date -u -d "@$(( $(date +%s) + PLAN_VALID_HOURS * 3600 ))" +%Y-%m-%dT%H:%M:%SZ)

  local plan_json
  plan_json=$(jq -n \
    --arg schema_version "$PLAN_SCHEMA_VERSION" \
    --arg created_at_utc "$(common::now_utc)" \
    --arg requested_target "$target" \
    --arg state "$state" \
    --arg rpo_seconds "$rpo_seconds" \
    --arg effective_checkpoint "$ckpt_time" \
    --arg effective_checkpoint_id "$ckpt_id" \
    --arg base_full "$full_time" \
    --arg base_full_id "$full_id" \
    --arg start_file "$start_file" \
    --arg end_file "$end_file" \
    --argjson binlogs "$binlogs" \
    --arg uploads_snapshot_id "$uploads_id" \
    --arg source_lineage "$lineage" \
    --arg allowlist_ref "${ALLOWLIST_REF:-default}" \
    --arg min_approvals "2" \
    --arg valid_until_utc "$valid_until" \
    '{schema_version: $schema_version,
      kind: "restore-plan",
      created_at_utc: $created_at_utc,
      requested_target: $requested_target,
      state: $state,
      rpo_seconds: ($rpo_seconds|tonumber),
      effective_checkpoint: {time: $effective_checkpoint, index: $effective_checkpoint_id},
      base_full: {time: $base_full, index: $base_full_id},
      binlog_replay: {start_file: $start_file, end_file: $end_file,
                      files: $binlogs},
      uploads_snapshot_id: $uploads_snapshot_id,
      source_lineage: $source_lineage,
      target: {allowlist_ref: $allowlist_ref, min_approvals: ($min_approvals|tonumber)},
      valid_until_utc: $valid_until_utc}')

  local canonical
  canonical=$(plan::write "$plan_json" "$PLANS_DIR")
  local plan_id
  plan_id=$(printf '%s' "$canonical" | jq -r '.plan_id')
  local plan_sha
  plan_sha=$(printf '%s' "$(plan::content_for_sha "$canonical")" | sha256sum | awk '{print $1}')

  printf '%s\n' "$canonical" | jq -S .
  echo "plan_id=$plan_id" >&2
  echo "plan_sha256=$plan_sha" >&2
  echo "rpo_seconds=$rpo_seconds binlog_files=$binlog_count" >&2
  return 0
}

TARGET=""
PLANS_DIR_ARG=""
while (($#)); do
  case "$1" in
    --target) TARGET=$2; shift 2 ;;
    --target=*) TARGET=${1#--target=}; shift ;;
    --plan-dir) PLANS_DIR_ARG=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$PLANS_DIR_ARG" ]] && PLANS_DIR=$PLANS_DIR_ARG
main
