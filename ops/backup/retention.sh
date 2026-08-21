#!/usr/bin/env bash
# ============================================================
# HFP-03-010 dependency-aware retention
#
# index/ のメタデータ（full/checkpoint）と binlog-index.json から
# dependency graph を構築し、PITR window（既定 30 日）+ 日次/週次/月次代表 +
# weekly 8 / monthly 12 full-only を保持、それ以外を削除候補とする。
#
# - dry-run: report を出力するだけで変更しない（restic に触れない）
# - apply:   report を再計算して一致を確認した上で二者承認（report SHA bind）
#           を検証し、maintenance lock のもとで restic forget --prune を実行
# - retention role でのみ実行可能（backup 等の writer は削除不可）
#
# usage: retention.sh --dry-run | --apply --report <report.json>
#                     [--approval <claim1> --approval <claim2>]
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
# shellcheck disable=SC1091
. "$LIB_DIR/common.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/repository-lock.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/approval.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/dependency-graph.sh"

RESTIC_BIN=${RESTIC_BIN:-restic}
RETENTION_PITR_DAYS=${RETENTION_PITR_DAYS:-30}
RETENTION_DAILY_DAYS=${RETENTION_DAILY_DAYS:-30}
RETENTION_WEEKLY_COUNT=${RETENTION_WEEKLY_COUNT:-8}
RETENTION_MONTHLY_COUNT=${RETENTION_MONTHLY_COUNT:-12}
RETENTION_LOCK_TIMEOUT=${RETENTION_LOCK_TIMEOUT:-30}

usage() {
  cat <<'EOF'
Usage: retention.sh --dry-run
       retention.sh --apply --report <report.json> --approval <claim1> --approval <claim2>
環境変数: INDEX_DIR, BINLOG_INDEX, RETENTION_ROLE（retention または admin）,
REPOSITORY_LOCK_DIR, RESTIC_BIN, RESTIC_PASSWORD_FILE, APPROVAL_PUBKEY_DIR,
RETENTION_PITR_DAYS / RETENTION_DAILY_DAYS / RETENTION_WEEKLY_COUNT /
RETENTION_MONTHLY_COUNT / RETENTION_LOCK_TIMEOUT
EOF
}

retention::fail() { echo "[retention] ERROR: $*" >&2; exit 1; }

retention::require_role() {
  local role=${RETENTION_ROLE:-}
  case "$role" in
    retention|admin) : ;;
    *)
      if [[ -z "$role" ]]; then
        retention::fail "RETENTION_ROLE が未設定です（retention/admin が必要）"
      else
        retention::fail "role=$role では削除できません（retention/admin が必要）"
      fi
      ;;
  esac
}

# report を生成（dry-run / apply の両方で使用。apply は一致確認に再計算する）
retention::build_report() { # -> report JSON
  local index_dir=${INDEX_DIR:?}
  local binlog_index=${BINLOG_INDEX:-}
  local now
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  local graph
  graph=$(dep::build_graph "$index_dir" "$binlog_index")
  local dec
  dec=$(dep::compute_retention "$graph" "$RETENTION_PITR_DAYS" "$RETENTION_DAILY_DAYS" \
    "$RETENTION_WEEKLY_COUNT" "$RETENTION_MONTHLY_COUNT" "$now")

  # 各 checkpoint の PITR_AVAILABLE（チェーン完全性）
  # age_days は dep::age_days（UTC）。fromdateiso8601 は使わない。
  local rows="[]" chain avail="{}" row ts age
  while IFS= read -r row; do
    [[ -n "$row" ]] || continue
    ts=$(printf '%s' "$row" | jq -r '.consistency_time_utc // empty')
    age=$(dep::age_days "$now" "$ts") || continue
    rows=$(printf '%s' "$rows" | jq -c --argjson r "$row" --argjson a "$age" \
      '. + [$r + {age_days: $a}]')
  done <<< "$(printf '%s' "$graph" | jq -c '.checkpoints[] | select(.status == "VALID")')"
  while IFS= read -r row; do
    [[ -n "$row" ]] || continue
    local id
    id=$(printf '%s' "$row" | jq -r '.restic_snapshot_id')
    chain=$(dep::chain_for_checkpoint "$graph" "$row")
    local complete
    complete=$(dep::chain_complete "$chain")
    avail=$(printf '%s' "$avail" | jq -c --arg id "$id" --arg c "$complete" \
      '. + {($id): ($c == "true")}')
  done <<< "$(printf '%s' "$rows" | jq -r '.[] | @json')"

  jq -n \
    --arg generated_at_utc "$now" \
    --arg pitr_days "$RETENTION_PITR_DAYS" \
    --arg daily_days "$RETENTION_DAILY_DAYS" \
    --arg weekly_count "$RETENTION_WEEKLY_COUNT" \
    --arg monthly_count "$RETENTION_MONTHLY_COUNT" \
    --argjson kept "$(printf '%s' "$dec" | jq -c '.kept_snapshots')" \
    --argjson deletable "$(printf '%s' "$dec" | jq -c '.deletable_snapshots')" \
    --argjson del_bins "$(printf '%s' "$dec" | jq -c '.deletable_binlog_snapshots')" \
    --argjson avail "$avail" \
    --argjson graph "$graph" \
    '{schema_version: 1, kind: "retention-report",
      generated_at_utc: $generated_at_utc,
      policy: {pitr_days: ($pitr_days|tonumber), daily_days: ($daily_days|tonumber),
               weekly_count: ($weekly_count|tonumber), monthly_count: ($monthly_count|tonumber)},
      graph: $graph, kept_snapshots: $kept, deletable_snapshots: $deletable,
      deletable_binlog_snapshots: $del_bins, pitr_available: $avail}'
}

# report の内容署名（apply の承認 bind に使用）
retention::report_sha() { # report_json
  printf '%s' "$1" | jq -S -c 'del(.generated_at_utc)' | sha256sum | awk '{print $1}'
}

retention::apply() { # report_file claim1 claim2
  local report_file=$1 claim1=$2 claim2=$3
  [[ -f "$report_file" ]] || retention::fail "report がありません: $report_file"
  local before
  before=$(cat "$report_file")

  # 再計算して report と一致すること（AC-009-03）
  local fresh
  fresh=$(retention::build_report)
  local sha_before sha_fresh
  sha_before=$(retention::report_sha "$before")
  sha_fresh=$(retention::report_sha "$fresh")
  if [[ "$sha_before" != "$sha_fresh" ]]; then
    retention::fail "apply report が現在の状態と一致しません（dry-run を再実行してください）"
  fi

  # 削除候補が空なら何もしない
  local del
  del=$(printf '%s' "$before" | jq -r '.deletable_snapshots | length')
  local del_bins
  del_bins=$(printf '%s' "$before" | jq -r '.deletable_binlog_snapshots | length')
  if [[ "$del" -eq 0 && "$del_bins" -eq 0 ]]; then
    jq -n --argjson c 0 '{state: "NO_OP", deletable_count: $c}'
    return 0
  fi

  # 二者承認（report file の SHA に bind。target = repository 自体）
  local repo_id
  [[ -n "${RESTIC_REPOSITORY:-}" ]] || retention::fail "RESTIC_REPOSITORY が未設定です"
  repo_id=$("$RESTIC_BIN" -r "$RESTIC_REPOSITORY" cat config 2>/dev/null | jq -r '.id // empty')
  [[ -n "$repo_id" ]] || retention::fail "repository id を取得できません"
  approval::collect_and_verify "$report_file" "$repo_id" "$claim1" "$claim2" \
    || retention::fail "承認が不足または不正です"

  # maintenance lock（bounded timeout。取得失敗は非 0 + alert 文言）
  repository_lock::acquire maintenance "$RETENTION_LOCK_TIMEOUT" "retention" \
    || retention::fail "maintenance lock を取得できません（prune 競合。timeout=${RETENTION_LOCK_TIMEOUT}s。alert 対象）"
  repository_lock::trap_release

  # restic forget --prune（repository に実在する削除候補のみ。位置引数で指定）
  local repo_snaps targets
  repo_snaps=$("$RESTIC_BIN" -r "$RESTIC_REPOSITORY" snapshots --json 2>/dev/null \
    | jq -r '[.[].id]' 2>/dev/null || echo "[]")
  targets=$(jq -n \
    --argjson del "$(printf '%s' "$before" | jq -c '.deletable_snapshots + .deletable_binlog_snapshots')" \
    --argjson repo "$repo_snaps" \
    '[ $del[] | select(. as $d | $repo | index($d)) ]')
  local del_targets
  del_targets=$(printf '%s' "$targets" | jq -r 'length')
  local targs=()
  local id
  while IFS= read -r id; do
    [[ -n "$id" ]] && targs+=("$id")
  done <<< "$(printf '%s' "$targets" | jq -r '.[]')"
  if ((${#targs[@]})); then
    local log
    log="${TMPDIR:-/tmp}/retention-restic.log"
    if ! "$RESTIC_BIN" -r "$RESTIC_REPOSITORY" forget "${targs[@]}" --prune > "$log" 2>&1; then
      retention::fail "restic forget に失敗しました: $(common::redact < "$log")"
    fi
  fi

  jq -n --argjson del_count "$del_targets" --argjson skipped "$((del + del_bins - del_targets))" \
    '{state: "APPLIED", deleted_snapshot_count: $del_count, skipped_absent_count: $skipped}'
}

main() {
  common::require_env INDEX_DIR
  retention::require_role

  if (( DRY_RUN )); then
    retention::build_report
    return 0
  fi
  [[ -n "$CLAIM1" && -n "$CLAIM2" ]] || { usage >&2; exit 2; }
  retention::apply "$REPORT_FILE" "$CLAIM1" "$CLAIM2"
}

DRY_RUN=0
REPORT_FILE=""
CLAIM1=""
CLAIM2=""
while (($#)); do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --apply) DRY_RUN=0; shift ;;
    --report) REPORT_FILE=$2; shift 2 ;;
    --approval) if [[ -z "$CLAIM1" ]]; then CLAIM1=$2; else CLAIM2=$2; fi; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
main
