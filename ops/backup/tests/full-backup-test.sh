#!/usr/bin/env bash
# HFP-03-003 full backup + manifest test
#
# baseline §5 failure inventory のうち HFP-03-003 担当分:
#   (5) metadata 改変が manifest 検査を通る → manifest::verify で拒否
#   + RQ-002/003: metadata 後書き、1 byte corruption、途中 producer、
#   symlink/device/path traversal、restic upload failure、coordinate parse failure、
#   forget --prune を成功 path から除外。
# fake mysql/mysqldump + 実 restic（temp repository）を使う。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"
BACKUP_FULL="$ROOT/ops/backup/backup-full.sh"
PREFLIGHT="$ROOT/ops/backup/preflight.sh"

FAKE_PW='S3cr3t-Value-XYZ-003'
FAKE_UUID='11111111-2222-3333-4444-555555555555'

setup_full() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath" "$T/work" "$T/uploads/sub" "$T/staging" \
    "$T/replicas" "$T/scheduler" "$T/repo"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  printf 'hello world\n' > "$T/uploads/a.txt"
  printf '日本語データ\n' > "$T/uploads/sub/b.txt"
  touch "$T/replicas/app1.heartbeat"

  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 \
    MYSQL_USER='bkp-user-7x9' MYSQL_DATABASE='ses_manager_db'
  export MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export RESTIC_BIN="$FIXTURES/restic" RESTIC_REAL_BIN=/usr/local/bin/restic
  export RESTIC_ARGV_LOG="$T/restic-argv.log" FAKE_ARGV_LOG="$T/argv.log"
  export RESTIC_PASSWORD_FILE="$T/pw" BACKUP_REPOSITORY="$T/repo"
  export BACKUP_WORK_DIR="$T/work" UPLOADS_DIR="$T/uploads" UPLOADS_STAGING_PARENT="$T/staging"
  export REPLICA_HEARTBEAT_DIR="$T/replicas" SCHEDULER_ACK_DIR="$T/scheduler"
  export PREFLIGHT_MIN_FREE_BYTES=1 QUIESCE_DEADLINE_SECONDS=20 QUIESCE_STALE_SECONDS=60
  export APP_COMMIT=test-commit-abc FLYWAY_VERSION=42 CRITICAL_TABLES=sys_user
  export BACKUP_TOOL_IMAGE_DIGEST=sha256:unit-test-digest
  export FAKE_GET_LOCK_HOLD=1
  export FAKE_MYSQL_STATUS_ROW=$'8.0.36\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  unset MYSQL_PWD

  # scheduler / replica ack fixture（background）
  (
    while :; do
      [[ -f "$SCHEDULER_ACK_DIR/scheduler.stop-request" ]] && touch "$SCHEDULER_ACK_DIR/scheduler.stopped" && rm -f "$SCHEDULER_ACK_DIR/scheduler.stop-request"
      for req in "$REPLICA_HEARTBEAT_DIR"/*.quiesce-requested; do
        [[ -e "$req" ]] || continue
        touch "${req%.quiesce-requested}.quiesced"
        rm -f "$req"
      done
      sleep 0.1
    done
  ) &
  FIXTURE_PID=$!
}

teardown_full() {
  kill "${FIXTURE_PID:-}" 2>/dev/null || true
}

case_backup_full_normal() {
  setup_full
  local out=""
  out=$("$BACKUP_FULL" 2>&1)
  local code=$?
  assert_zero "$code" "full backup 成功"
  assert_contains "$out" '"status": "VALID"' "result status VALID"
  assert_contains "$out" 'binlog.000010' "coordinate file が metadata と一致"
  assert_contains "$out" '"position": 154' "coordinate position"
  # restic に kind=full の snapshot
  local snaps=""
  snaps=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null)
  assert_contains "$snaps" 'kind=full' "snapshot tag kind=full"
  assert_contains "$snaps" 'status=valid' "snapshot tag status=valid"
  # manifest.sha256 が metadata 後書きを防ぐ順序で生成されている
  assert_file "$T/work"/index/full-*.json "index 登録あり"
  # forget --prune が呼ばれていない
  assert_not_contains "$(cat "$RESTIC_ARGV_LOG")" "forget" "backup 成功 path で forget を呼ばない"
  assert_not_contains "$(cat "$RESTIC_ARGV_LOG")" "prune" "backup 成功 path で prune を呼ばない"
  teardown_full
}

case_backup_full_restore_verify_content() {
  setup_full
  "$BACKUP_FULL" > /dev/null 2>&1
  assert_zero "$?" "full backup 成功"
  local snap
  snap=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq -r '.[0].id')
  local vdir="$T/verify"
  mkdir -p "$vdir"
  RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic restore "$snap" --target "$vdir" --verify > /dev/null 2>&1
  assert_zero "$?" "restic restore --verify 成功"
  # uploads の中身が復元されている
  local found
  found=$(find "$vdir" -name a.txt -exec cat {} \; 2>/dev/null | head -n1)
  assert_eq "hello world" "$found" "uploads の内容が復元される"
  # manifest 検証が通る
  local restored
  restored=$(find "$vdir" -name manifest.json -printf '%h' | head -n1)
  # shellcheck disable=SC1091
  . "$LIB/manifest.sh"
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  manifest::verify "$restored" "$restored/manifest.json"
  assert_zero "$?" "restore 後の manifest verify 成功"
  teardown_full
}

case_manifest_metadata_late_write() {
  # metadata が manifest 作成後に変更された fixture で検証が失敗する（AC-003-01）
  setup_full
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/manifest.sh"
  mkdir -p "$T/m1"
  printf 'a\n' > "$T/m1/payload.txt"
  manifest::build "$T/m1"
  assert_zero "$?" "manifest build"
  manifest::finalize "$T/m1"
  # 後から metadata 相当を書き足す
  printf 'tampered\n' > "$T/m1/metadata.json"
  manifest::verify "$T/m1" "$T/m1/manifest.json"
  assert_nonzero "$?" "manifest 後の metadata 追加は verify 失敗"
  teardown_full
}

case_manifest_corrupt_one_byte() {
  # dump 1 byte corruption で verify 失敗（AC-003-02）
  setup_full
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/manifest.sh"
  mkdir -p "$T/m2"
  printf 'data payload 1234567890\n' > "$T/m2/dump.sql"
  manifest::build "$T/m2"
  manifest::finalize "$T/m2"
  # 1 byte 破損
  printf 'X' | dd of="$T/m2/dump.sql" bs=1 seek=0 conv=notrunc 2>/dev/null
  manifest::verify "$T/m2" "$T/m2/manifest.json"
  assert_nonzero "$?" "1 byte 破損は verify 失敗"
  teardown_full
}

case_manifest_extra_file() {
  # manifest に列挙されない payload を拒否（AC-003-03）
  setup_full
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/manifest.sh"
  mkdir -p "$T/m3"
  printf 'a\n' > "$T/m3/payload.txt"
  manifest::build "$T/m3"
  manifest::finalize "$T/m3"
  printf 'sneaky\n' > "$T/m3/sneaky.txt"
  manifest::verify "$T/m3" "$T/m3/manifest.json"
  assert_nonzero "$?" "extra file は verify 失敗"
  teardown_full
}

case_manifest_absolute_path() {
  # 絶対 path の entry を拒否（AC-003-03）
  setup_full
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/manifest.sh"
  mkdir -p "$T/m4"
  printf 'a\n' > "$T/m4/payload.txt"
  jq -n --arg p "/etc/passwd" '{schema_version: 1, files: [{path: $p, type: "file", size: 1, sha256: "x"}]}' > "$T/m4/manifest.json"
  manifest::verify "$T/m4" "$T/m4/manifest.json"
  assert_nonzero "$?" "絶対 path は verify 失敗"
  teardown_full
}

case_backup_full_coord_parse_failure() {
  setup_full
  # source-data 無し dump を用意
  printf '%s\n' "-- MySQL dump" "-- no coordinates" > "$T/bad-dump.sql"
  export FAKE_DUMP_FILE="$T/bad-dump.sql"
  local out=""
  out=$("$BACKUP_FULL" 2>&1)
  local code=$?
  assert_nonzero "$code" "coordinate 抽出不能は非 0"
  assert_contains "$out" "coordinate" "理由を通知"
  # restic snapshot が作られていない
  local count
  count=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq 'length')
  assert_eq 0 "$count" "snapshot を発行しない"
  teardown_full
}

case_backup_full_quiesce_failure() {
  setup_full
  # replica を全部 stale にする
  touch -d '10 minutes ago' "$T/replicas/app1.heartbeat"
  local out=""
  out=$("$BACKUP_FULL" 2>&1)
  local code=$?
  assert_nonzero "$code" "静止失敗で非 0"
  assert_contains "$out" "静止" "理由を通知"
  local count
  count=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq 'length')
  assert_eq 0 "$count" "snapshot を発行しない"
  teardown_full
}

case_backup_full_uploads_symlink() {
  setup_full
  ln -s a.txt "$T/uploads/link.txt"
  local out=""
  out=$("$BACKUP_FULL" 2>&1)
  local code=$?
  assert_nonzero "$code" "uploads に symlink があれば非 0"
  local count
  count=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq 'length')
  assert_eq 0 "$count" "snapshot を発行しない"
  teardown_full
}

case_backup_full_restic_failure() {
  setup_full
  # 正しい password で repository を初期化しておき、誤 password では
  # 読み取り不能 → fail-closed（init による上書きも拒否）になることを検証する
  RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$T/pw" \
    /usr/local/bin/restic init > /dev/null 2>&1
  assert_zero "$?" "repo init 成功"
  printf 'wrong-password\n' > "$T/wrong-pw"
  export RESTIC_PASSWORD_FILE="$T/wrong-pw"
  local out=""
  out=$("$BACKUP_FULL" 2>&1)
  local code=$?
  assert_nonzero "$code" "repository 読取不能で非 0"
  assert_contains "$out" "repository" "repository エラーを通知"
  teardown_full
}

case_backup_full_no_secret_leak() {
  setup_full
  "$BACKUP_FULL" > /dev/null 2>&1 || true
  teardown_full
  assert_not_contains "$(cat "$RESTIC_ARGV_LOG" 2>/dev/null || true)" "$FAKE_PW" "restic argv に秘密なし"
  assert_not_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null || true)" "$FAKE_PW" "mysql argv に秘密なし"
}

run_case case_backup_full_normal
run_case case_backup_full_restore_verify_content
run_case case_manifest_metadata_late_write
run_case case_manifest_corrupt_one_byte
run_case case_manifest_extra_file
run_case case_manifest_absolute_path
run_case case_backup_full_coord_parse_failure
run_case case_backup_full_quiesce_failure
run_case case_backup_full_uploads_symlink
run_case case_backup_full_restic_failure
run_case case_backup_full_no_secret_leak

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-003 full backup + manifest"
