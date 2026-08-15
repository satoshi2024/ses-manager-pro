#!/usr/bin/env bash
# ============================================================
# HFP-03-012 restore drill（実復元の定期リハーサル）
#
# 本番相当の復元（plan -> integrity -> restore -> validate ->
# read-only cutover リハーサル -> rollback）を実際に実行し、RPO / RTO
# segment 時間・markers・evidence SHA を記録する。
#
# - 各 step は必ず実 script を実行する（`mysqladmin ping` のみの
#   代替は受け付けない）
# - いずれかの step が失敗・skip・evidence 欠如・目標超過なら非 0
# - 本番の write 経路には触れない（write-enable は実施しない）
#
# usage: restore-drill.sh --target <utc> [--report-dir DIR]
# 環境変数: INDEX_DIR, BINLOG_INDEX, PLANS_DIR, BACKUP_WORK_DIR,
# BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, RESTIC_REPOSITORY,
# TARGET_*（restore/validate と同一）, APPROVAL_*,
# RTO_SECONDS（既定 14400 = 4h）, RPO_MAX_SECONDS（既定 900 = 15m）
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
# shellcheck disable=SC1091
. "$LIB_DIR/common.sh"

RESTIC_BIN=${RESTIC_BIN:-restic}
MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
RTO_SECONDS=${RTO_SECONDS:-14400}
RPO_MAX_SECONDS=${RPO_MAX_SECONDS:-900}

usage() {
  cat <<'EOF'
Usage: restore-drill.sh --target <utc> [--report-dir DIR]
環境変数: INDEX_DIR, BINLOG_INDEX, PLANS_DIR, BACKUP_WORK_DIR,
BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, RESTIC_REPOSITORY,
TARGET_HOST / TARGET_USER / TARGET_PASSWORD_FILE / TARGET_DATABASE,
TARGET_ALLOWLIST_FILE, APPROVAL_PUBKEY_DIR,
RTO_SECONDS（既定 14400）, RPO_MAX_SECONDS（既定 900）
EOF
}

drill::fail() { echo "[drill] ERROR: $*" >&2; exit 1; }

# step 実行 + segment 時間計測（start ラベルを記録）
drill::segment_start() { # label
  DRILL_CURRENT=$1
  DRILL_START_EPOCH=$(date +%s)
}

drill::segment_end() { # label
  local end
  end=$(date +%s)
  local dur=$((end - DRILL_START_EPOCH))
  echo "$DRILL_CURRENT|$dur" >> "$DRILL_SEGMENTS"
  echo "[drill] $DRILL_CURRENT: ${dur}s"
  DRILL_TOTAL_SECONDS=$((DRILL_TOTAL_SECONDS + dur))
}

drill::require_evidence() { # path label
  [[ -f "$1" && -s "$1" ]] || drill::fail "evidence がありません: $1（$2）"
}

main() {
  common::require_env INDEX_DIR
  common::require_env PLANS_DIR
  common::require_env BACKUP_WORK_DIR
  common::require_env TARGET_HOST
  common::require_env TARGET_USER
  common::require_env TARGET_PASSWORD_FILE
  common::require_env TARGET_DATABASE
  common::require_env TARGET_ALLOWLIST_FILE
  common::require_env RESTIC_REPOSITORY
  common::require_env RESTIC_PASSWORD_FILE

  local report_dir=${REPORT_DIR:-"$BACKUP_WORK_DIR/drill"}
  mkdir -p "$report_dir"
  DRILL_SEGMENTS="$report_dir/segments.txt"
  : > "$DRILL_SEGMENTS"
  DRILL_TOTAL_SECONDS=0
  DRILL_START_WALL=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  echo "== drill: plan（検証 + RPO 記録） =="
  drill::segment_start plan
  local plan_path="$PLANS_DIR/$PLAN_ID.json"
  # shellcheck disable=SC1091
  . "$LIB_DIR/plan.sh"
  plan::verify "$plan_path" || drill::fail "plan の検証に失敗しました"
  local pst
  pst=$(plan::status "$plan_path")
  [[ "$pst" == "APPLYABLE" ]] || drill::fail "plan は適用できません（status=$pst）"
  PLAN_SHA=$(sha256sum "$plan_path" | awk '{print $1}')
  RPO_SECONDS=$(jq -r '.rpo_seconds // -1' "$plan_path")
  drill::segment_end plan
  echo "[drill] plan_id=$PLAN_ID rpo=${RPO_SECONDS}s"

  echo "== drill: repository integrity + restore verify =="
  drill::segment_start integrity
  local integrity_log="$report_dir/integrity.log"
  "$RESTIC_BIN" -r "$RESTIC_REPOSITORY" check --read-data > "$integrity_log" 2>&1 \
    || drill::fail "restic check に失敗しました"
  local base_snap
  base_snap=$(jq -r '.base_full_snap // empty' "$plan_path" 2>/dev/null || true)
  if [[ -n "$base_snap" ]]; then
    "$RESTIC_BIN" -r "$RESTIC_REPOSITORY" restore "$base_snap" \
      --target "$report_dir/integrity-verify" --verify >> "$integrity_log" 2>&1 \
      || drill::fail "restore verify に失敗しました"
  fi
  drill::require_evidence "$integrity_log" "integrity"
  drill::segment_end integrity

  echo "== drill: restore（DB full + binlog replay + uploads staging） =="
  drill::segment_start restore
  local restore_log="$report_dir/restore.log"
  if ! TARGET_SSL_CAPATH="${TARGET_SSL_CAPATH:-}" TARGET_TLS_MODE="${TARGET_TLS_MODE:-VERIFY_CA}" \
    "$SCRIPT_DIR/restore.sh" --plan "$PLAN_ID" \
    --approval "$DRILL_CLAIM1" --approval "$DRILL_CLAIM2" > "$restore_log" 2>&1; then
    echo "[drill] restore ログ（末尾）:" >&2
    tail -5 "$restore_log" >&2
    drill::fail "restore に失敗しました"
  fi
  UPLOADS_READY=$(grep -oE '"uploads_ready": "[^"]+"' "$restore_log" | cut -d'"' -f4)
  [[ -n "$UPLOADS_READY" ]] || drill::fail "uploads_ready がありません"
  drill::require_evidence "$restore_log" "restore"
  drill::segment_end restore

  echo "== drill: validate + read-only smoke =="
  drill::segment_start validate
  local validate_log="$report_dir/validate.log"
  local smoke=${DRILL_SMOKE_SCRIPT:-}
  local smoke_args=()
  [[ -z "$smoke" ]] || smoke_args=(--smoke "$smoke")
  TARGET_SSL_CAPATH="${TARGET_SSL_CAPATH:-}" TARGET_TLS_MODE="${TARGET_TLS_MODE:-VERIFY_CA}" \
    "$SCRIPT_DIR/validate-restore.sh" --plan "$PLAN_ID" \
    --uploads-dir "$UPLOADS_READY" "${smoke_args[@]}" > "$validate_log" 2>&1 || true
  local vstate
  vstate=$(sed -n '/^{/,$p' "$validate_log" 2>/dev/null | jq -r '.state // ""' 2>/dev/null || echo "")
  if [[ "$vstate" != "READY_FOR_CUTOVER" ]]; then
    echo "[drill] validate ログ（先頭）:" >&2
    head -8 "$validate_log" >&2
    echo "[drill] validate ログ（末尾）:" >&2
    tail -8 "$validate_log" >&2
    drill::fail "validate が READY_FOR_CUTOVER でない（state=$vstate。mysqladmin ping のみの代替は受け付けません）"
  fi
  drill::require_evidence "$validate_log" "validate"
  drill::segment_end validate

  echo "== drill: read-only cutover リハーサル → rollback =="
  drill::segment_start cutover
  local cs="$report_dir/cutover-state.json"
  CUTOVER_STATE_FILE="$cs" VALIDATION_STATE_FILE="$validate_log" \
    TARGET_SSL_CAPATH="${TARGET_SSL_CAPATH:-}" TARGET_TLS_MODE="${TARGET_TLS_MODE:-VERIFY_CA}" \
    "$SCRIPT_DIR/cutover.sh" --plan "$PLAN_ID" \
    --approval "$DRILL_CLAIM1" --approval "$DRILL_CLAIM2" > "$report_dir/cutover.log" 2>&1 || true
  local cstate
  cstate=$(jq -r '.state // ""' "$cs" 2>/dev/null || echo "")
  # ドリルは write-enable を実施しないため、smoke 通過後の rolled-back までは許容
  # （rollback は read-only smoke が PASS した状態に戻すだけ）
  case "$cstate" in
    rolled-back) : ;;
    single-writer|write-enabled)
      drill::fail "ドリルで write-enable が実施されました（state=$cstate）"
      ;;
    *)
      drill::fail "cutover リハーサルが期待状態になりません（state=$cstate）"
      ;;
  esac
  drill::segment_end cutover

  echo "== drill: report =="
  local report="$report_dir/drill-report.json"
  jq -n \
    --arg state "SUCCESS" \
    --arg run_started_at_utc "$DRILL_START_WALL" \
    --arg plan_id "$PLAN_ID" \
    --arg plan_sha256 "$PLAN_SHA" \
    --arg rpo_seconds "$RPO_SECONDS" \
    --argjson rto_seconds "$RTO_SECONDS" \
    --argjson total_seconds "$DRILL_TOTAL_SECONDS" \
    --arg target "$TARGET_TS" \
    --arg validate_state "$vstate" \
    --arg cutover_state "$cstate" \
    --rawfile segs_raw "$DRILL_SEGMENTS" \
    '{kind: "restore-drill", state: $state,
      run_started_at_utc: $run_started_at_utc,
      target_utc: $target, plan_id: $plan_id, plan_sha256: $plan_sha256,
      rpo_seconds: ($rpo_seconds|tonumber), rto_seconds: $rto_seconds,
      total_seconds: $total_seconds,
      rto_ok: ($total_seconds <= $rto_seconds),
      rpo_ok: (($rpo_seconds|tonumber) <= 0 or ($rpo_seconds|tonumber) <= '"$RPO_MAX_SECONDS"'),
      validate_state: $validate_state, cutover_state: $cutover_state,
      segments: [$segs_raw | split("\n")[] | select(length > 0) | split("|") | {label: .[0], seconds: (.[1]|tonumber)}]}' \
    > "$report"
  drill::require_evidence "$report" "drill report"
  cat "$report"

  local rto_ok
  rto_ok=$(jq -r '.rto_ok' "$report")
  local rpo_ok
  rpo_ok=$(jq -r '.rpo_ok' "$report")
  [[ "$rto_ok" == "true" ]] || drill::fail "RTO 超過（total=${DRILL_TOTAL_SECONDS}s > ${RTO_SECONDS}s）"
  [[ "$rpo_ok" == "true" ]] || drill::fail "RPO 超過（rpo=${RPO_SECONDS}s > ${RPO_MAX_SECONDS}s）"
  echo "[drill] SUCCESS total=${DRILL_TOTAL_SECONDS}s rpo=${RPO_SECONDS}s"
}

DRILL_CLAIM1=""
DRILL_CLAIM2=""
PLAN_ID=""
TARGET_TS=""
REPORT_DIR=""
while (($#)); do
  case "$1" in
    --plan) PLAN_ID=$2; shift 2 ;;
    --plan=*) PLAN_ID=${1#--plan=}; shift ;;
    --target) TARGET_TS=$2; shift 2 ;;
    --target=*) TARGET_TS=${1#--target=}; shift ;;
    --report-dir) REPORT_DIR=$2; shift 2 ;;
    --approval) if [[ -z "$DRILL_CLAIM1" ]]; then DRILL_CLAIM1=$2; else DRILL_CLAIM2=$2; fi; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$PLAN_ID" ]] || { usage >&2; exit 2; }
main
