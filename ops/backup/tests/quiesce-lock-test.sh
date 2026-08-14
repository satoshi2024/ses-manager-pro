#!/usr/bin/env bash
# HFP-03-002 lock / quiesce / uploads snapshot provider test
#
# baseline §5 failure inventory のうち HFP-03-002 担当分 + RQ-002/006/008/009:
# lock race、timeout、provider partial failure、replica 未静止、scheduler active、
# DDL lock conflict、解除 failure、special file 拒否。
# fake mysql / fake replica・scheduler fixture を使い、実 DB には接続しない。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
PROVIDERS="$ROOT/ops/backup/providers"
FIXTURES="$HERE/fixtures/bin"

FAKE_PW='S3cr3t-Value-XYZ-002'

setup_common() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp" REPOSITORY_LOCK_DIR="$T/locks"
  mkdir -p "$TMPDIR" "$REPOSITORY_LOCK_DIR" "$T/capath"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER='bkp-user-7x9'
  export MYSQL_SSL_CAPATH="$T/capath"
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export FAKE_ARGV_LOG="$T/argv.log"
  unset MYSQL_PWD
}

setup_quiesce() {
  setup_common
  export REPLICA_HEARTBEAT_DIR="$T/replicas" SCHEDULER_ACK_DIR="$T/scheduler"
  mkdir -p "$REPLICA_HEARTBEAT_DIR" "$SCHEDULER_ACK_DIR"
  # 既定: 2 replica（fresh heartbeat）
  touch "$REPLICA_HEARTBEAT_DIR/app1.heartbeat" "$REPLICA_HEARTBEAT_DIR/app2.heartbeat"
  # scheduler fixture: stop-request を見たら ack する
  (
    while :; do
      [[ -f "$SCHEDULER_ACK_DIR/scheduler.stop-request" ]] && touch "$SCHEDULER_ACK_DIR/scheduler.stopped" && break
      sleep 0.1
    done
  ) &
  SCHEDULER_PID=$!
  # replica fixture: quiesce-requested を見たら ack する
  (
    while :; do
      for req in "$REPLICA_HEARTBEAT_DIR"/*.quiesce-requested; do
        [[ -e "$req" ]] || continue
        touch "${req%.quiesce-requested}.quiesced"
        rm -f "$req"
      done
      sleep 0.1
    done
  ) &
  REPLICA_ACK_PID=$!
  export QUIESCE_DEADLINE_SECONDS=20 QUIESCE_STALE_SECONDS=60 FAKE_GET_LOCK_HOLD=1
}

teardown_quiesce() {
  kill "${SCHEDULER_PID:-}" "${REPLICA_ACK_PID:-}" 2>/dev/null || true
}

case_lock_shared_and_exclusive() {
  setup_common
  # shellcheck disable=SC1091
  . "$LIB/repository-lock.sh"
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  repository_lock::acquire shared 5 "test-a"
  assert_zero "$?" "shared lock 取得"
  assert_file "$REPOSITORY_LOCK_DIR/owner.json" "owner metadata あり"
  assert_contains "$(cat "$REPOSITORY_LOCK_DIR/owner.json")" '"mode": "shared"' "owner mode"
  assert_contains "$(cat "$REPOSITORY_LOCK_DIR/owner.json")" '"owner": "test-a"' "owner 名"
  # 別 process から exclusive は timeout で失敗する
  capture_exit code bash -c "cd '$ROOT' && . '$LIB/common.sh' && . '$LIB/repository-lock.sh' && REPOSITORY_LOCK_DIR='$REPOSITORY_LOCK_DIR' repository_lock::acquire exclusive 2 'other'"
  assert_nonzero "$code" "排他要求は 2s timeout で失敗"
  # shared は並行取得できる
  capture_exit code2 bash -c "cd '$ROOT' && . '$LIB/common.sh' && . '$LIB/repository-lock.sh' && REPOSITORY_LOCK_DIR='$REPOSITORY_LOCK_DIR' repository_lock::acquire shared 2 'other-shared'"
  assert_zero "$code2" "shared は並行取得できる"
  repository_lock::release
}

case_lock_bad_mode() {
  setup_common
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/repository-lock.sh"
  capture_exit code repository_lock::acquire bogus 5 "t"
  assert_nonzero "$code" "不正 mode は非 0"
  capture_exit code2 repository_lock::acquire shared abc "t"
  assert_nonzero "$code2" "不正 timeout は非 0"
}

case_quiesce_all_fresh() {
  setup_quiesce
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  quiesce::acquire
  assert_zero "$?" "全 replica fresh で静止取得成功"
  local st
  st=$(quiesce::status_json)
  assert_contains "$st" '"provider": "quiesce-local"' "provider 名"
  assert_contains "$st" '"started_at_utc"' "開始時刻あり"
  assert_contains "$st" '"app1"' "replica app1 記載"
  assert_contains "$st" '"app2"' "replica app2 記載"
  assert_contains "$st" '"ddl_lock": "ses_backup_ddl_freeze"' "DDL lock 名"
  quiesce::release
  assert_zero "$?" "静止解除成功"
  st=$(quiesce::status_json)
  assert_contains "$st" '"released_at_utc": "20' "解除時刻が記載される"
  teardown_quiesce
}

case_quiesce_one_replica_stale() {
  setup_quiesce
  touch -d '5 minutes ago' "$REPLICA_HEARTBEAT_DIR/app2.heartbeat"
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "1 replica 未静止なら非 0"
  assert_contains "$out" "app2" "未静止 replica 名を通知"
  teardown_quiesce
}

case_quiesce_scheduler_no_ack() {
  setup_quiesce
  kill "$SCHEDULER_PID" 2>/dev/null || true
  export QUIESCE_DEADLINE_SECONDS=3
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "scheduler ack なしは deadline 超過で非 0"
  assert_contains "$out" "scheduler" "scheduler 関連の通知"
  teardown_quiesce
}

case_quiesce_scheduler_dir_missing() {
  setup_quiesce
  unset SCHEDULER_ACK_DIR
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "SCHEDULER_ACK_DIR 未設定は非 0（fail-closed）"
  assert_contains "$out" "SCHEDULER_ACK_DIR" "理由を通知"
  teardown_quiesce
}

case_quiesce_ddl_lock_conflict() {
  setup_quiesce
  export FAKE_GET_LOCK=0
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "DDL lock 取得失敗は非 0"
  assert_contains "$out" "GET_LOCK" "GET_LOCK 失敗を通知"
  teardown_quiesce
}

case_quiesce_release_after_ddl_conflict() {
  # acquire 失敗後に release しても安全（DDL lock 未取得のままエラーにならない）
  setup_quiesce
  export FAKE_GET_LOCK=0
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  quiesce::acquire > /dev/null 2>&1
  capture_exit code quiesce::release
  assert_zero "$code" "acquire 失敗後の release は exit 0"
  teardown_quiesce
}

case_uploads_snapshot_normal() {
  setup_common
  mkdir -p "$T/uploads/sub" "$T/staging"
  printf 'hello world\n' > "$T/uploads/a.txt"
  printf '日本語\n' > "$T/uploads/sub/b.txt"
  local out=""
  "$PROVIDERS/uploads-local.sh" snapshot "$T/uploads" "$T/staging" "$T/snap.json"
  assert_zero "$?" "snapshot 成功"
  assert_file "$T/snap.json" "snapshot JSON あり"
  local sj
  sj=$(cat "$T/snap.json")
  assert_contains "$sj" '"snapshot_id"' "snapshot ID あり"
  assert_contains "$sj" '"a.txt"' "inventory に a.txt"
  assert_contains "$sj" '"sub/b.txt"' "inventory に sub/b.txt"
  assert_contains "$sj" '"file_count": 2' "file_count 2"
  # source 不変
  assert_eq "$(find "$T/uploads" -type f | wc -l)" "2" "source file 数不変"
  # staging の内容が source と一致
  local s1 s2
  s1=$(sha256sum "$T/uploads/a.txt" | awk '{print $1}')
  s2=$(find "$T/staging" -name a.txt -exec sha256sum {} \; | awk '{print $1}')
  assert_eq "$s1" "$s2" "staging の a.txt が source と一致"
}

case_uploads_snapshot_symlink() {
  setup_common
  mkdir -p "$T/uploads" "$T/staging"
  printf 'x\n' > "$T/uploads/a.txt"
  ln -s a.txt "$T/uploads/link.txt"
  local out=""
  out=$("$PROVIDERS/uploads-local.sh" snapshot "$T/uploads" "$T/staging" "$T/snap.json" 2>&1)
  local code=$?
  assert_nonzero "$code" "symlink は非 0"
  assert_contains "$out" "拒否" "symlink 拒否を通知"
}

case_uploads_snapshot_fifo() {
  setup_common
  mkdir -p "$T/uploads" "$T/staging"
  mkfifo "$T/uploads/pipe.fifo"
  local out=""
  out=$("$PROVIDERS/uploads-local.sh" snapshot "$T/uploads" "$T/staging" "$T/snap.json" 2>&1)
  local code=$?
  assert_nonzero "$code" "FIFO は非 0"
  assert_contains "$out" "拒否" "FIFO 拒否を通知"
}

case_uploads_snapshot_hardlink() {
  setup_common
  mkdir -p "$T/uploads" "$T/staging"
  printf 'x\n' > "$T/uploads/a.txt"
  ln "$T/uploads/a.txt" "$T/uploads/b.txt"
  local out=""
  out=$("$PROVIDERS/uploads-local.sh" snapshot "$T/uploads" "$T/staging" "$T/snap.json" 2>&1)
  local code=$?
  assert_nonzero "$code" "hardlink は非 0"
  assert_contains "$out" "拒否" "hardlink 拒否を通知"
}

case_uploads_snapshot_encoded_name() {
  # ..%2f のようなエンコード名は実 FS 上は単なる file 名であり、
  # literal '..' 成分の拒否対象ではない（防御は復元側 safe-extract でも行う）
  setup_common
  mkdir -p "$T/uploads" "$T/staging"
  printf 'x\n' > "$T/uploads/normal.txt"
  printf 'y\n' > "$T/uploads/..%2fetc"
  local out=""
  "$PROVIDERS/uploads-local.sh" snapshot "$T/uploads" "$T/staging" "$T/snap.json" 2>&1
  assert_zero "$?" "エンコード名は file として正常処理"
  assert_contains "$(cat "$T/snap.json")" '..%2fetc' "エンコード名が inventory に残る"
}

case_quiesce_no_secret_leak() {
  setup_quiesce
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  quiesce::acquire > /dev/null 2>&1
  quiesce::release > /dev/null 2>&1
  teardown_quiesce
  if [[ -f "$FAKE_ARGV_LOG" ]]; then
    assert_not_contains "$(cat "$FAKE_ARGV_LOG")" "$FAKE_PW" "argv に秘密なし"
  fi
  assert_not_contains "$(cat "$T/quiesce.json" 2>/dev/null || true)" "$FAKE_PW" "quiesce.json に秘密なし"
  assert_not_contains "$(grep -r . "$REPOSITORY_LOCK_DIR" 2>/dev/null | head -c 2000)" "$FAKE_PW" "lock metadata に秘密なし"
}

run_case case_lock_shared_and_exclusive
run_case case_lock_bad_mode
run_case case_quiesce_all_fresh
run_case case_quiesce_one_replica_stale
run_case case_quiesce_scheduler_no_ack
run_case case_quiesce_scheduler_dir_missing
run_case case_quiesce_ddl_lock_conflict
run_case case_quiesce_release_after_ddl_conflict
run_case case_uploads_snapshot_normal
run_case case_uploads_snapshot_symlink
run_case case_uploads_snapshot_fifo
run_case case_uploads_snapshot_hardlink
run_case case_uploads_snapshot_encoded_name
run_case case_quiesce_no_secret_leak

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-002 quiesce/lock/uploads"
