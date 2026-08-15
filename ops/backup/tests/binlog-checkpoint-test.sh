#!/usr/bin/env bash
# HFP-03-004 binlog archive / checkpoint test
#
# baseline §5 failure inventory のうち HFP-03-004 担当分:
#   (6) active/truncated binlog が snapshot 対象になる → 除外・拒否
#   + RQ-004: 欠番、checksum error、別 UUID、重複 server id、archiver restart
#   resume、active file exclusion、2 回 rotation。
# fake mysql/mysqlbinlog + 実 restic を使う。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"
ARCHIVE="$ROOT/ops/backup/archive-binlog.sh"
SNAPSHOT="$ROOT/ops/backup/snapshot-binlog.sh"
CHECKPOINT="$ROOT/ops/backup/create-checkpoint.sh"

FAKE_PW='S3cr3t-Value-XYZ-004'
FAKE_UUID='11111111-2222-3333-4444-555555555555'

setup_common() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath" "$T/repo"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER='bkp-user-7x9'
  export MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export RESTIC_BIN="$FIXTURES/restic" RESTIC_REAL_BIN=/usr/local/bin/restic
  export RESTIC_ARGV_LOG="$T/restic-argv.log" FAKE_ARGV_LOG="$T/argv.log"
  export RESTIC_PASSWORD_FILE="$T/pw" BACKUP_REPOSITORY="$T/repo"
  export FAKE_GET_LOCK_HOLD=1
  # 前 case の fake 制御 env が漏れないようにする
  unset FAKE_BINLOG_VERIFY_RC FAKE_ARCHIVE_RC FAKE_FLUSH_RC FAKE_GET_LOCK FAKE_RELEASE_LOCK \
    FAKE_DUMP_FILE FULL_COORDINATE_FILE BINLOG_SERVER_ID
  unset MYSQL_PWD
}

setup_binlog_env() {
  setup_common
  export BINLOG_RAW_DIR="$T/raw" BINLOG_IMMUTABLE_DIR="$T/immutable" BINLOG_STATE="$T/state.json"
  export BINLOG_INDEX="$T/immutable/binlog-index.json"
  mkdir -p "$BINLOG_RAW_DIR" "$BINLOG_IMMUTABLE_DIR"
  export FAKE_BINLOG_STATE="$T/binlogs"
  printf 'binlog.000001\t180\nbinlog.000002\t200\n' > "$FAKE_BINLOG_STATE"
  export FAKE_SERVER_ID_LOG="$T/server-id.log"
}

start_quiesce_fixtures() {
  mkdir -p "$REPLICA_HEARTBEAT_DIR" "$SCHEDULER_ACK_DIR"
  touch "$REPLICA_HEARTBEAT_DIR/app1.heartbeat"
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
  FIX_PID=$!
}

setup_checkpoint_env() {
  setup_binlog_env
  export BACKUP_WORK_DIR="$T/work" UPLOADS_DIR="$T/uploads" UPLOADS_STAGING_PARENT="$T/staging"
  export REPLICA_HEARTBEAT_DIR="$T/replicas" SCHEDULER_ACK_DIR="$T/scheduler"
  export APP_COMMIT=test FLYWAY_VERSION=42 MYSQL_DATABASE=ses_manager_db
  export CHECKPOINT_ARCHIVER_WAIT=3
  mkdir -p "$BACKUP_WORK_DIR" "$UPLOADS_DIR/sub" "$UPLOADS_STAGING_PARENT"
  printf 'uploads-data\n' > "$UPLOADS_DIR/sub/f.txt"
  start_quiesce_fixtures
}

run_archive_once() {
  ARCHIVE_OUT=$("$ARCHIVE" --once 2>&1)
  ARCHIVE_CODE=$?
}

case_archive_first_run_without_coordinate_fails() {
  # R1 P1-06: 初回起動に FULL_COORDINATE_FILE が無い場合は黙って現行から
  # 開始せず失敗する（先行 binlog の未アーカイブ化を防ぐ）
  setup_binlog_env
  unset FULL_COORDINATE_FILE
  run_archive_once
  assert_nonzero "$ARCHIVE_CODE" "初回起動で coordinate 欠如は非 0"
  assert_contains "$ARCHIVE_OUT" "FULL_COORDINATE_FILE" "理由"
}

case_archive_normal_once() {
  setup_binlog_env
  export FAKE_ARCHIVE_FILES="binlog.000001 binlog.000002"
  export FULL_COORDINATE_FILE="$T/full-coordinate"
  printf 'binlog.000001\n' > "$FULL_COORDINATE_FILE"
  run_archive_once
  assert_zero "$ARCHIVE_CODE" "archive --once 成功"
  assert_file "$BINLOG_RAW_DIR/binlog.000001" "raw file 1 あり"
  assert_file "$BINLOG_RAW_DIR/binlog.000002" "raw file 2 あり"
  assert_file "$BINLOG_STATE" "state あり"
  assert_contains "$(cat "$BINLOG_STATE")" "$FAKE_UUID" "state に source UUID"
  assert_contains "$(cat "$BINLOG_STATE")" '"connection_server_id"' "state に server id"
  assert_file "$FAKE_SERVER_ID_LOG" "server id 記録あり"
  local sid=""
  sid=$(cat "$FAKE_SERVER_ID_LOG")
  assert_eq "yes" "$(echo "$sid" | grep -qE '^[0-9]+$' && echo yes || echo no)" "server id は整数"
  assert_contains "$(cat "$FAKE_ARGV_LOG")" "--connection-server-id=" "argv に server id"
  assert_contains "$(cat "$FAKE_ARGV_LOG")" "binlog.000001" "argv に initial log"
}

case_archive_restart_resume() {
  setup_binlog_env
  export FAKE_ARCHIVE_FILES="binlog.000002 binlog.000003"
  # 前回 file が source の File_size(180) 以上で完全に存在する
  head -c 200 /dev/zero | tr '\0' 'a' > "$BINLOG_RAW_DIR/binlog.000001"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t157\n' > "$FAKE_BINLOG_STATE"
  jq -n --arg last "binlog.000001" --arg sid "50001" --arg uuid "$FAKE_UUID" \
    '{last_file: $last, connection_server_id: $sid, source_server_uuid: $uuid}' > "$BINLOG_STATE"
  export BINLOG_SERVER_ID=50001
  run_archive_once
  assert_zero "$ARCHIVE_CODE" "restart resume 成功"
  assert_contains "$(cat "$FAKE_ARGV_LOG")" "binlog.000002" "最後の complete file の次から開始"
  assert_file "$BINLOG_RAW_DIR/binlog.000001" "既存 file を上書きしない"
}

case_archive_restart_incomplete() {
  setup_binlog_env
  export FAKE_ARCHIVE_FILES="binlog.000001 binlog.000002"
  printf 'short\n' > "$BINLOG_RAW_DIR/binlog.000001"
  jq -n --arg last "binlog.000001" --arg sid "50002" --arg uuid "$FAKE_UUID" \
    '{last_file: $last, connection_server_id: $sid, source_server_uuid: $uuid}' > "$BINLOG_STATE"
  export BINLOG_SERVER_ID=50002
  run_archive_once
  assert_zero "$ARCHIVE_CODE" "不完全 file の取り直しで成功"
  assert_contains "$(cat "$FAKE_ARGV_LOG")" "binlog.000001" "不完全 file から取り直し"
  assert_contains "$(cat "$BINLOG_RAW_DIR/binlog.000001")" "fake-binlog-content" "取り直した file が新しい"
}

case_archive_uuid_mismatch() {
  setup_binlog_env
  jq -n --arg last "binlog.000001" --arg sid "50003" --arg uuid "99999999-9999-9999-9999-999999999999" \
    '{last_file: $last, connection_server_id: $sid, source_server_uuid: $uuid}' > "$BINLOG_STATE"
  export BINLOG_SERVER_ID=50003
  run_archive_once
  assert_nonzero "$ARCHIVE_CODE" "UUID 不一致は非 0"
  assert_contains "$ARCHIVE_OUT" "UUID" "理由を通知"
}

case_archive_server_id_mismatch() {
  setup_binlog_env
  jq -n --arg last "binlog.000001" --arg sid "55555" --arg uuid "$FAKE_UUID" \
    '{last_file: $last, connection_server_id: $sid, source_server_uuid: $uuid}' > "$BINLOG_STATE"
  export BINLOG_SERVER_ID=50004
  run_archive_once
  assert_nonzero "$ARCHIVE_CODE" "server id 不一致は非 0"
  assert_contains "$ARCHIVE_OUT" "connection-server-id" "理由を通知"
}

case_archive_state_ahead_of_source() {
  setup_binlog_env
  printf 'binlog.000001\t180\n' > "$FAKE_BINLOG_STATE"
  jq -n --arg last "binlog.000009" --arg sid "50005" --arg uuid "$FAKE_UUID" \
    '{last_file: $last, connection_server_id: $sid, source_server_uuid: $uuid}' > "$BINLOG_STATE"
  export BINLOG_SERVER_ID=50005
  run_archive_once
  assert_nonzero "$ARCHIVE_CODE" "state が source より新しい場合は非 0"
}

case_snapshot_binlog_closed_only() {
  setup_binlog_env
  # raw: binlog.000001（正常 closed）+ binlog.000002（truncated）+ 最新は対象外
  head -c 190 /dev/zero | tr '\0' 'b' > "$BINLOG_RAW_DIR/binlog.000001"
  printf 'short' > "$BINLOG_RAW_DIR/binlog.000002"
  printf 'binlog.000001\t180\nbinlog.000002\t200\nbinlog.000003\t157\n' > "$FAKE_BINLOG_STATE"
  local out=""
  out=$("$SNAPSHOT" 2>&1)
  assert_zero "$?" "snapshot-binlog 成功"
  assert_file "$BINLOG_IMMUTABLE_DIR/binlog.000001" "closed 済み file を immutable 化"
  assert_no_file "$BINLOG_IMMUTABLE_DIR/binlog.000002" "truncated file は immutable 化しない"
  assert_no_file "$BINLOG_IMMUTABLE_DIR/binlog.000003" "active file は immutable 化しない"
  assert_contains "$(cat "$BINLOG_INDEX")" 'binlog.000001' "index に 000001"
  assert_not_contains "$(cat "$BINLOG_INDEX")" 'binlog.000002' "index に 000002 なし"
  local snaps=""
  snaps=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null)
  assert_contains "$snaps" 'kind=binlog' "restic tag kind=binlog"
  assert_contains "$snaps" 'status=valid' "restic tag status=valid"
  assert_not_contains "$(cat "$BINLOG_INDEX")" 'binlog.000003' "active を index に載せない"
}

case_snapshot_binlog_checksum_fail() {
  setup_binlog_env
  head -c 190 /dev/zero | tr '\0' 'b' > "$BINLOG_RAW_DIR/binlog.000001"
  export FAKE_BINLOG_VERIFY_RC=1
  local out=""
  out=$("$SNAPSHOT" 2>&1)
  assert_zero "$?" "checksum 失敗 file はスキップして続行"
  assert_no_file "$BINLOG_IMMUTABLE_DIR/binlog.000001" "checksum NG は immutable 化しない"
}

case_continuity_gap() {
  setup_common
  # shellcheck disable=SC1091
  . "$LIB/binlog.sh"
  mkdir -p "$T/dir"
  touch "$T/dir/binlog.000010" "$T/dir/binlog.000012"
  binlog::files_continuous "$T/dir" binlog.000010 binlog.000012
  assert_nonzero "$?" "欠番は非 0"
  touch "$T/dir/binlog.000011"
  binlog::files_continuous "$T/dir" binlog.000010 binlog.000012
  assert_zero "$?" "欠番なしは 0"
}

case_checkpoint_normal() {
  setup_checkpoint_env
  head -c 210 /dev/zero | tr '\0' 'a' > "$BINLOG_RAW_DIR/binlog.000002"
  local out=""
  out=$("$CHECKPOINT" 2>&1)
  local code=$?
  if [[ -n "${DEBUG_CHECKPOINT_OUT:-}" ]]; then
    echo "DEBUG OUT code=$code: $out" >&2
  fi
  assert_zero "$code" "checkpoint 成功"
  assert_contains "$out" '"status": "VALID"' "checkpoint VALID"
  assert_contains "$out" '"binlog_end"' "binlog_end 記載"
  assert_contains "$out" 'binlog.000002' "closed file 記載"
  assert_file "$BACKUP_WORK_DIR"/index/checkpoint-*.json "index 登録あり"
  local snaps=""
  snaps=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null)
  assert_contains "$snaps" 'kind=checkpoint' "restic tag kind=checkpoint"
  assert_contains "$snaps" 'status=valid' "restic tag status=valid"
  kill "$FIX_PID" 2>/dev/null || true
}

case_checkpoint_truncated_reject() {
  setup_checkpoint_env
  printf 'short\n' > "$BINLOG_RAW_DIR/binlog.000002"
  local out=""
  out=$("$CHECKPOINT" 2>&1)
  local code=$?
  assert_nonzero "$code" "truncated closed file は非 0"
  assert_contains "$out" "追従" "archiver 追従失敗を通知"
  local snaps=""
  snaps=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null)
  assert_eq 0 "$(printf '%s' "$snaps" | jq 'length')" "checkpoint snapshot を発行しない"
  kill "$FIX_PID" 2>/dev/null || true
}

case_checkpoint_checksum_reject() {
  setup_checkpoint_env
  head -c 210 /dev/zero | tr '\0' 'a' > "$BINLOG_RAW_DIR/binlog.000002"
  export FAKE_BINLOG_VERIFY_RC=1
  local out=""
  out=$("$CHECKPOINT" 2>&1)
  local code=$?
  assert_nonzero "$code" "checksum NG は非 0"
  assert_contains "$out" "checksum" "checksum 理由を通知"
  kill "$FIX_PID" 2>/dev/null || true
}

case_checkpoint_rotation_twice() {
  setup_checkpoint_env
  # 2 回目の checkpoint: FLUSH で binlog.000002 が closed になる
  head -c 190 /dev/zero | tr '\0' 'b' > "$BINLOG_RAW_DIR/binlog.000001"
  head -c 210 /dev/zero | tr '\0' 'a' > "$BINLOG_RAW_DIR/binlog.000002"
  local out=""
  out=$("$CHECKPOINT" 2>&1)
  assert_zero "$?" "2 回目 checkpoint 成功"
  assert_contains "$out" 'binlog.000002' "2 回目は binlog.000002 が closed"
  kill "$FIX_PID" 2>/dev/null || true
}

run_case case_archive_normal_once
run_case case_archive_first_run_without_coordinate_fails
run_case case_archive_restart_resume
run_case case_archive_restart_incomplete
run_case case_archive_uuid_mismatch
run_case case_archive_server_id_mismatch
run_case case_archive_state_ahead_of_source
run_case case_snapshot_binlog_closed_only
run_case case_snapshot_binlog_checksum_fail
run_case case_continuity_gap
run_case case_checkpoint_normal
run_case case_checkpoint_truncated_reject
run_case case_checkpoint_checksum_reject
run_case case_checkpoint_rotation_twice

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-004 binlog archive/checkpoint"
