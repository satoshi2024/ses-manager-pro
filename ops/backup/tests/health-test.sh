#!/usr/bin/env bash
# HFP-03-005 watermark 監視 test
#
# baseline §5 failure inventory のうち HFP-03-005 担当分:
#   (7) 古い file が 1 件あるだけで health failure になる一方、
#       最新 source lag を測らない → watermark 基準で判定する
#   + RQ-010: full 26h / checkpoint 20m,30m / archiver stop + source advance /
#     gap / repository check / drill overdue / clock skew は対象外（UTC 固定）
# fake mysql を使い、index/binlog-index/heartbeat を fixture で作る。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"
CHECK="$ROOT/ops/backup/check-backup.sh"

FAKE_PW='S3cr3t-Value-XYZ-005'

setup_health() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath" "$T/index" "$T/binlog"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER='bkp-user-7x9'
  export MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql"
  export BACKUP_WORK_DIR="$T" BINLOG_INDEX="$T/binlog/binlog-index.json"
  export BINLOG_STATE="$T/archive-state.json" REPO_CHECK_TS="$T/last-repo-check" DRILL_TS="$T/last-drill"
  export FAKE_BINLOG_STATE="$T/source-binlogs"
  unset MYSQL_PWD
}

write_index() { # kind name age_seconds
  local kind=$1 name=$2 age=$3
  local ts
  ts=$(date -u -d "@$(( $(date +%s) - age ))" +%Y-%m-%dT%H:%M:%SZ)
  jq -n --arg k "$kind" --arg s "VALID" --arg t "$ts" \
    '{kind: $k, status: $s, consistency_time_utc: $t}' > "$T/index/$name.json"
}

write_binlog_index() { # "file1 age1 file2 age2 ..."（age は archived_at からの秒）
  local entries=$1
  local arr="[]"
  local line=""
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    local -a parts=()
    read -ra parts <<< "$line"
    local i=0
    for ((i = 0; i < ${#parts[@]}; i += 2)); do
      local f=${parts[i]} age=${parts[i + 1]}
      local ts
      ts=$(date -u -d "@$(( $(date +%s) - age ))" +%Y-%m-%dT%H:%M:%SZ)
      arr=$(printf '%s' "$arr" | jq -c --arg f "$f" --arg t "$ts" '. + [{file: $f, archived_at_utc: $t}]')
    done
  done <<< "$entries"
  printf '%s' "$arr" > "$BINLOG_INDEX"
}

touch_age() { # path age_seconds
  local p=$1 age=$2
  touch -d "@$(( $(date +%s) - age ))" "$p" 2>/dev/null || touch -d "now -${age} seconds" "$p"
}

run_check() {
  OUT=$("$CHECK" --json 2>&1)
  RC=$?
  if [[ -n "${DEBUG_HEALTH_OUT:-}" ]]; then
    echo "DEBUG OUT rc=$RC: $OUT" >&2
  fi
}

case_health_normal_no_false_alert() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 300
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  # 古い file が raw に残っていても false alert にしない（AC-010-02）
  mkdir -p "$T/raw"
  touch_age "$T/raw/binlog.000001" 604800
  run_check
  assert_eq 0 "$RC" "正常系 exit 0"
  assert_contains "$OUT" '"status": "OK"' "status OK"
  assert_contains "$OUT" '"rpo_available": true' "rpo_available true"
  assert_contains "$OUT" '"gap_count": 0' "gap 0"
  assert_contains "$OUT" '"last_closed_file": "binlog.000003"' "last closed file"
  assert_contains "$OUT" '"source_current_file": "binlog.000004"' "source current"
  assert_not_contains "$OUT" 'binlog.000001' "古い file の存在で判定しない"
}

case_health_full_stale() {
  setup_health
  write_index full full-1 100000
  write_index checkpoint ckpt-1 300
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  run_check
  assert_eq 2 "$RC" "full 26h 超は CRITICAL(2)"
  assert_contains "$OUT" '"status": "CRITICAL"' "status CRITICAL"
  assert_contains "$OUT" 'full' "full の理由"
}

case_health_checkpoint_warn() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 1500   # 25m
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  run_check
  assert_eq 1 "$RC" "checkpoint 20m 超は WARN(1)"
  assert_contains "$OUT" '"status": "WARN"' "status WARN"
}

case_health_checkpoint_critical() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 2000   # 33m
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  run_check
  assert_eq 2 "$RC" "checkpoint 30m 超は CRITICAL(2)"
}

case_health_archiver_stopped_source_advanced() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 300
  # archived は binlog.000002 で止まり、source は binlog.000006 まで進んだ
  write_binlog_index "binlog.000002 4000"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t157\nbinlog.000004\t157\nbinlog.000005\t157\nbinlog.000006\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 4000
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  run_check
  assert_eq 2 "$RC" "archiver 停止 + source advance は CRITICAL(2)"
  assert_contains "$OUT" '"binlog_event_lag_seconds": 5400' "lag 秒（3 file x 30m）"
  assert_contains "$OUT" '"status": "CRITICAL"' "status CRITICAL"
}

case_health_gap() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 300
  write_binlog_index "binlog.000002 120 binlog.000004 60"   # 000003 欠番
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  run_check
  assert_eq 2 "$RC" "gap は CRITICAL(2)"
  assert_contains "$OUT" '"gap_count": 1' "gap 1 件"
  assert_contains "$OUT" '"rpo_available": false' "rpo_available false"
}

case_health_repo_check_stale() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 300
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 700000   # 8 日
  touch_age "$DRILL_TS" 86400
  run_check
  assert_eq 1 "$RC" "repo check 期限超過は WARN(1)"
  assert_contains "$OUT" 'repository' "repo check の理由"
}

case_health_drill_overdue() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 300
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400   # 1 日: OK
  run_check
  assert_eq 0 "$RC" "drill 1 日は OK"
  touch_age "$DRILL_TS" $((86400 * 95))   # 95 日
  run_check
  assert_eq 1 "$RC" "drill 95 日は WARN(1)"
  assert_contains "$OUT" '"last_drill_age_days": 95' "drill 日数"
}

case_health_source_unreachable() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 300
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  # source を読めない（fake mysql を失敗させる）
  export MYSQL_CLIENT_BIN=/bin/false
  run_check
  assert_eq 3 "$RC" "source 接続不能は UNKNOWN(3)"
  assert_contains "$OUT" '"status": "UNKNOWN"' "status UNKNOWN"
}

case_health_rpo_missed() {
  setup_health
  write_index full full-1 7200
  write_index checkpoint ckpt-1 1200   # 20 分 > 15 分
  write_binlog_index "binlog.000002 120 binlog.000003 60"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t300\nbinlog.000004\t157\n' > "$FAKE_BINLOG_STATE"
  touch_age "$BINLOG_STATE.heartbeat" 10
  touch_age "$REPO_CHECK_TS" 86400
  touch_age "$DRILL_TS" 86400
  run_check
  assert_contains "$OUT" '"rpo_available": false' "RPO 15 分超で false"
  assert_contains "$OUT" '"checkpoint_age_seconds": 1200' "checkpoint age"
}

run_case case_health_normal_no_false_alert
run_case case_health_full_stale
run_case case_health_checkpoint_warn
run_case case_health_checkpoint_critical
run_case case_health_archiver_stopped_source_advanced
run_case case_health_gap
run_case case_health_repo_check_stale
run_case case_health_drill_overdue
run_case case_health_source_unreachable
run_case case_health_rpo_missed

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-005 watermark health"
