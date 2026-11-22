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
  # RB-W 追加修正 C: uploads の根の下に版 v0 と current -> v0（production の配置）
  mkdir -p "$TMPDIR" "$T/capath" "$T/work" "$T/uploads/v0/sub" "$T/staging" \
    "$T/replicas" "$T/scheduler" "$T/repo"
  ln -s v0 "$T/uploads/current"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  printf 'hello world\n' > "$T/uploads/v0/a.txt"
  printf '日本語データ\n' > "$T/uploads/v0/sub/b.txt"
  touch "$T/replicas/app1.heartbeat"

  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 \
    MYSQL_USER='bkp-user-7x9' MYSQL_DATABASE='ses_manager_db'
  export MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export RESTIC_BIN="$FIXTURES/restic" RESTIC_REAL_BIN=/usr/local/bin/restic
  export RESTIC_ARGV_LOG="$T/restic-argv.log" FAKE_ARGV_LOG="$T/argv.log"
  export RESTIC_PASSWORD_FILE="$T/pw" BACKUP_REPOSITORY="$T/repo"
  export BACKUP_WORK_DIR="$T/work" UPLOADS_ROOT="$T/uploads" UPLOADS_POINTER=current UPLOADS_STAGING_PARENT="$T/staging"
  unset UPLOADS_DIR
  export REPLICA_HEARTBEAT_DIR="$T/replicas" SCHEDULER_ACK_DIR="$T/scheduler"
  export PREFLIGHT_MIN_FREE_BYTES=1 QUIESCE_DEADLINE_SECONDS=20 QUIESCE_STALE_SECONDS=60
  # PATH の preflight.sh（image に写した版）ではなく、試験する repository の preflight.sh を使う（RB-QUIESCE: image の写しは
  # build の時点の版で、作業中の変更（checks.quiesce 等）を含まないことがある）
  export PREFLIGHT_BIN="$PREFLIGHT"
  export APP_COMMIT=test-commit-abc FLYWAY_VERSION=42 CRITICAL_TABLES=sys_use
  export BACKUP_TOOL_IMAGE_DIGEST=sha256:unit-test-digest
  # R1 P1-06: full coordinate は必須（archiver の初回起点）
  export FULL_COORDINATE_FILE="$T/full-coordinate"
  export FAKE_GET_LOCK_HOLD=1
  export FAKE_MYSQL_STATUS_ROW=$'8.4.13\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  unset MYSQL_PWD FAKE_DUMP_FILE FAKE_BINLOG_VERIFY_RC FAKE_GET_LOCK FAKE_RELEASE_LOCK \
    CONTENT_HASH_TABLES FAKE_CONTENT_ROWS FAKE_CONTENT_RC FAKE_CONTENT_COLUMNS FAKE_CONTENT_COLUMNS_EMPTY \
    FAKE_SCHEMA_ROWS FAKE_SCHEMA_RC QUIESCE_PROVIDER FAKE_QDB_DIR QUIESCE_STATE_DIR QUIESCE_LEASE_RENEW_SECONDS \
    QUIESCE_RESUME_CONFIRM_SECONDS QUIESCE_FULL_NEW_INSTANCE_CHECK_SECONDS FAKE_DUMP_SLEEP FAKE_DUMP_PID_FILE FAKE_DUMP_RC

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
  assert_contains "$out" 'binlog.000010' "coordinate file は metadata と一致"
  assert_contains "$out" '"position": 154' "coordinate position"
  # RB-W（2026-11-18 利用者決定）: mysqldump に --no-tablespaces を渡す（dump account に PROCESS を要らなくする）
  local dump_argv=""
  dump_argv=$(grep -e '--source-data=2' "$FAKE_ARGV_LOG" 2>/dev/null || true)
  assert_contains "$dump_argv" "--no-tablespaces" "mysqldump に --no-tablespaces を渡す"
  assert_contains "$dump_argv" "--single-transaction" "mysqldump に --single-transaction を渡す"
  # RB-W 追加修正 C: どの版（pointer の指す先）を backup したかを結果・index・metadata に記録する
  assert_contains "$out" '"uploads_pointer": {' "結果 JSON に pointer と版を記録"
  assert_contains "$out" '"target": "v0"' "結果 JSON に backup した版を記録"
  local fidx
  fidx=$(ls "$T/work"/index/full-*.json 2>/dev/null | head -n 1)
  assert_eq "current/v0" "$(jq -r '"\(.uploads_pointer.name)/\(.uploads_pointer.target)"' "$fidx" 2>/dev/null)" \
    "index（= metadata.json）に pointer と版を記録"
  assert_eq "v0" "$(jq -r '.uploads.pointer_target // empty' "$fidx" 2>/dev/null)" "uploads の snapshot JSON にも版を記録"
  # R1 P1-06: full coordinate file が書き出されていること
  if [[ -f "$FULL_COORDINATE_FILE" ]] && grep -qx 'binlog.000010' "$FULL_COORDINATE_FILE"; then
    test_assert "FULL_COORDINATE_FILE を書き出し（archiver の初回起点）"
  else
    test_fail "FULL_COORDINATE_FILE を書き出し（archiver の初回起点）" "coordinate file が正しくない"
  fi
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

# RB-W 8.4 対応: 8.4 の mysqldump が書く CHANGE REPLICATION SOURCE TO の coordinate を読み、旧形式（CHANGE MASTER TO。
# 8.4 の server には無い）だけの dump は coordinate を取れずに失敗する（fail-closed）。
case_backup_full_coord_source_syntax() {
  setup_full
  printf '%s\n' "-- MySQL dump 10.13  Distrib 8.4.13, for Linux (x86_64)" \
    "-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='binlog.000123', SOURCE_LOG_POS=4567;" > "$T/source-dump.sql"
  export FAKE_DUMP_FILE="$T/source-dump.sql"
  local out="" code idx
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_zero "$code" "CHANGE REPLICATION SOURCE TO の dump で full backup 成功"
  idx=$(ls "$T/work"/index/full-*.json 2>/dev/null | head -n 1)
  assert_eq "binlog.000123/4567" "$(jq -r '"\(.binlog_start.file)/\(.binlog_start.position)"' "$idx" 2>/dev/null)" \
    "SOURCE_LOG_FILE / SOURCE_LOG_POS を記録"
  teardown_full

  setup_full
  printf '%s\n' "-- MySQL dump" "-- CHANGE MASTER TO MASTER_LOG_FILE='binlog.000123', MASTER_LOG_POS=4567;" > "$T/master-dump.sql"
  export FAKE_DUMP_FILE="$T/master-dump.sql"
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "旧形式（CHANGE MASTER TO）だけの dump は coordinate を取れずに失敗"
  assert_contains "$out" "coordinate" "理由を通知"
  unset FAKE_DUMP_FILE
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

# RB-W 追加修正 C: 廃止した UPLOADS_DIR が残っている設定・pointer を解決できない配置では backup しない（fail-closed）
case_backup_full_rejects_old_uploads_setting_and_unresolved_pointer() {
  local out="" code
  setup_full
  export UPLOADS_DIR="$T/uploads/v0"
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  unset UPLOADS_DIR
  assert_nonzero "$code" "廃止した UPLOADS_DIR が残っていれば非 0"
  assert_contains "$out" "UPLOADS_DIR は廃止しました" "廃止を通知"
  teardown_full

  setup_full
  rm "$T/uploads/current"
  ln -s "$T/uploads/v0" "$T/uploads/current"
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "絶対 path の pointer では非 0"
  assert_not_contains "$out" '"status": "VALID"' "VALID を出さない"
  local count
  count=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq 'length')
  assert_eq 0 "${count:-0}" "snapshot を発行しない"
  teardown_full
}

case_backup_full_uploads_symlink() {
  setup_full
  ln -s a.txt "$T/uploads/v0/link.txt"
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

case_backup_full_failure_releases_quiesce() {
  setup_full
  # R1 P1-03: 失敗時にも静止解除（DDL lock session 終了）が必ず走ること。
  # quiesce 取得後に dump を失敗させ、release が実行されることを確認する
  export QUIESCE_STATE_DIR="$T/quiesce-state"
  export FAKE_DUMP_RC=1
  local out=""
  out=$("$BACKUP_FULL" 2>&1)
  local code=$?
  assert_nonzero "$code" "dump 失敗で backup-full は非 0"
  assert_no_file "$QUIESCE_STATE_DIR/ddl-session.pid" "失敗後も DDL session pid が残らない（quiesce 解放済み）"
  # R2 P2-04: trap 連結により option file（秘密）が残らないこと
  local leftove
  leftover=$(ls "$TMPDIR"/ses-backup-mysql.* 2>/dev/null | wc -l)
  assert_eq "0" "$leftover" "失敗後も option file が残らない（trap 連結）"
  unset QUIESCE_STATE_DIR FAKE_DUMP_RC
  teardown_full
}

case_backup_full_no_secret_leak() {
  setup_full
  "$BACKUP_FULL" > /dev/null 2>&1 || true
  teardown_full
  assert_not_contains "$(cat "$RESTIC_ARGV_LOG" 2>/dev/null || true)" "$FAKE_PW" "restic argv に秘密なし"
  assert_not_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null || true)" "$FAKE_PW" "mysql argv に秘密なし"
}

# A-NF09-5: CONTENT_HASH_TABLES の table は静止区間内に順序付き内容ハッシュを採取し、
# metadata（manifest 対象）と index に記録する。
case_backup_full_records_content_hash() {
  setup_full
  export CONTENT_HASH_TABLES=sys_use
  export FAKE_CONTENT_ROWS='1\tadmin\n2\tsales\n'
  local out="" code expected idx
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_zero "$code" "内容ハッシュ付き full backup 成功"
  expected=$(printf '%b' "$FAKE_CONTENT_ROWS" | sha256sum | awk '{print $1}')
  idx=$(ls "$T/work"/index/full-*.json 2>/dev/null | head -n 1)
  assert_eq "$expected" "$(jq -r '.content_hashes.sys_use.sha256 // empty' "$idx" 2>/dev/null)" "index に内容ハッシュを記録"
  assert_eq "mysql-batch-pk-quote-sha256-v2" "$(jq -r '.content_hashes.sys_use.algorithm // empty' "$idx" 2>/dev/null)" "算出方式を記録"
  assert_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null)" "time_zone = '+00:00'" "session time_zone を固定して内容を取得"
  assert_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null)" 'ORDER BY `id`;' "主キー（だけ）の順に並べて内容を取得"
  # W1-B1 r2: SQL の NULL は囲まれない NULL、値は QUOTE() で ' に囲む（NULL と文字列 'NULL' を区別する）
  assert_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null)" "IF(\`id\` IS NULL, 'NULL', QUOTE(\`id\`)), IF(\`name\` IS NULL, 'NULL', QUOTE(\`name\`))" \
    "全列を NULL と値を区別して取得"
  unset CONTENT_HASH_TABLES FAKE_CONTENT_ROWS
  teardown_full
}

# W1-B1 r2: 主キーの無い table・存在しない table は内容ハッシュの対象にできないため、full を VALID にしない。
case_backup_full_content_hash_requires_primary_key() {
  setup_full
  export CONTENT_HASH_TABLES=sys_use FAKE_CONTENT_COLUMNS='id\t\nname\t\n'
  local out="" code
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "主キーの無い table を指定した full は非 0"
  assert_contains "$out" "主キーの無い table は内容ハッシュの対象にできません" "主キー不在を通知"
  unset FAKE_CONTENT_COLUMNS
  teardown_full

  setup_full
  export CONTENT_HASH_TABLES=sys_use FAKE_CONTENT_COLUMNS_EMPTY=1
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "存在しない table を指定した full は非 0"
  assert_contains "$out" "table がありません（列情報 0 件）: sys_use" "table 不在を通知"
  if compgen -G "$T/work/index/full-*.json" > /dev/null; then
    test_fail "内容ハッシュを計算できない full は index 登録しない" "index が登録されています"
  else
    test_assert "内容ハッシュを計算できない full は index 登録しない"
  fi
  unset CONTENT_HASH_TABLES FAKE_CONTENT_COLUMNS_EMPTY
  teardown_full
}

# 指定 table の内容ハッシュを計算できなければ full を VALID にせず、静止は解除する（fail-closed）。
case_backup_full_content_hash_failure_fails_closed() {
  setup_full
  export QUIESCE_STATE_DIR="$T/quiesce-state"
  export CONTENT_HASH_TABLES=sys_use FAKE_CONTENT_RC=1
  local out="" code
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "内容ハッシュを計算できなければ非 0"
  assert_contains "$out" "内容ハッシュを計算できません" "原因を通知"
  if compgen -G "$T/work/index/full-*.json" > /dev/null; then
    test_fail "内容ハッシュ失敗時は full を index 登録しない" "index が登録されています"
  else
    test_assert "内容ハッシュ失敗時は full を index 登録しない"
  fi
  assert_no_file "$QUIESCE_STATE_DIR/ddl-session.pid" "失敗後も静止を解除する"
  unset QUIESCE_STATE_DIR CONTENT_HASH_TABLES FAKE_CONTENT_RC
  teardown_full
}

run_case case_backup_full_normal
run_case case_backup_full_restore_verify_content
run_case case_manifest_metadata_late_write
run_case case_manifest_corrupt_one_byte
run_case case_manifest_extra_file
run_case case_manifest_absolute_path
run_case case_backup_full_coord_parse_failure
run_case case_backup_full_coord_source_syntax
run_case case_backup_full_quiesce_failure
run_case case_backup_full_rejects_old_uploads_setting_and_unresolved_pointer
run_case case_backup_full_uploads_symlink
run_case case_backup_full_restic_failure
run_case case_backup_full_failure_releases_quiesce
run_case case_backup_full_no_secret_leak
# G-04（RB-W W-1）: schema fingerprint は設定によらず静止区間内に採取し、metadata（manifest 対象）と index に
# 記録する。期待値は算出方式名の 1 行 + LC_ALL=C で並べた行の SHA-256（lib を使わずに計算する）。
case_backup_full_records_schema_fingerprint() {
  setup_full
  export FAKE_SCHEMA_ROWS="schema\t'utf8mb4'\t'utf8mb4_unicode_ci'\ncolumn\t'sys_use'\t'2'\t'name'\t'varchar(64)'\t'YES'\tNULL\t'utf8mb4'\t'utf8mb4_0900_ai_ci'\t''\t''\tNULL\ntable\t'sys_use'\t'BASE TABLE'\t'InnoDB'\t'utf8mb4_0900_ai_ci'\t''\ncolumn\t'sys_use'\t'1'\t'id'\t'bigint'\t'NO'\tNULL\tNULL\tNULL\t'auto_increment'\t''\tNULL\n"
  local out="" code expected idx
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_zero "$code" "schema fingerprint 付き full backup 成功"
  expected=$(printf '%s\n%s\n' mysql-information-schema-sha256-v2 "$(printf '%b' "$FAKE_SCHEMA_ROWS" | LC_ALL=C sort)" \
    | sha256sum | awk '{print $1}')
  idx=$(ls "$T/work"/index/full-*.json 2>/dev/null | head -n 1)
  assert_eq "$expected" "$(jq -r '.schema_fingerprint.sha256 // empty' "$idx" 2>/dev/null)" "index に schema fingerprint を記録"
  assert_eq "mysql-information-schema-sha256-v2" "$(jq -r '.schema_fingerprint.algorithm // empty' "$idx" 2>/dev/null)" \
    "算出方式を記録"
  assert_eq "2" "$(jq -r '.schema_fingerprint.sections.column.count // empty' "$idx" 2>/dev/null)" "区分ごとの件数を記録"
  # RB-W P2-1: DB の既定の文字集合・照合順序を記録する（restore.sh が復元 DB をこの値で作る）
  assert_eq "utf8mb4/utf8mb4_unicode_ci" \
    "$(jq -r '"\(.schema_fingerprint.database.character_set)/\(.schema_fingerprint.database.collation)"' "$idx" 2>/dev/null)" \
    "DB の既定の文字集合・照合順序を記録"
  assert_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null)" "ses-backup:schema-fingerprint" "information_schema から schema を取得"
  assert_contains "$(grep 'ses-backup:schema-fingerprint' "$FAKE_ARGV_LOG" 2>/dev/null)" "ses_manager_db" "対象 DB を指定して取得"
  unset FAKE_SCHEMA_ROWS
  teardown_full
}

# schema fingerprint を計算できなければ full を VALID にせず、静止は解除する（fail-closed）。
case_backup_full_schema_fingerprint_failure_fails_closed() {
  setup_full
  export QUIESCE_STATE_DIR="$T/quiesce-state"
  export FAKE_SCHEMA_RC=1
  local out="" code
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "schema fingerprint を計算できなければ非 0"
  assert_contains "$out" "schema fingerprint を計算できません" "原因を通知"
  if compgen -G "$T/work/index/full-*.json" > /dev/null; then
    test_fail "schema fingerprint 失敗時は full を index 登録しない" "index が登録されています"
  else
    test_assert "schema fingerprint 失敗時は full を index 登録しない"
  fi
  assert_no_file "$QUIESCE_STATE_DIR/ddl-session.pid" "失敗後も静止を解除する"
  unset QUIESCE_STATE_DIR FAKE_SCHEMA_RC
  teardown_full
}

# ------------------------------------------------------------ RB-QUIESCE BWQ-009（quiesce-db の full）

# fake の mysql が受けた SQL の名前を、job の本体が呼んだ順に並べる。保持 process（lease の延長 `renew` と
# full の最中の新しい instance の確認 `full-new-instance`）は別の process から timer で呼ぶため、解除の直後に
# 停止までの間の1回が本体の呼出しの間に入ることがある（CI で release と resume-check の間に入った）。
# 本体の順を確かめる assertion はこの関数の出力を使う。保持 process の振舞い（延長・延長の停止・新しい instance
# による中止）は quiesce-db-test.sh の case_hold_keeps_renewing_after_acquire_returns 等で確かめる。解除の後の1回は
# renew.sql・full-new-instance.sql とも `status = 'REQUESTED'` の条件で0行の更新になり、要求を戻さない。
qdb_main_calls() {
  cut -f2 "$FAKE_QDB_DIR/calls.log" | grep -v -x -e renew -e full-new-instance | tr '\n' ' '
}

# quiesce-db の設定（fake の mysql が DB の protocol に応答する。heartbeat の directory は要らない）
setup_full_qdb() {
  setup_full
  kill "${FIXTURE_PID:-}" 2>/dev/null || true
  unset REPLICA_HEARTBEAT_DIR SCHEDULER_ACK_DIR
  export QUIESCE_PROVIDER=quiesce-db FAKE_QDB_DIR="$T/qdb" QUIESCE_STATE_DIR="$T/qstate" \
    QUIESCE_LEASE_RENEW_SECONDS=1 QUIESCE_RESUME_CONFIRM_SECONDS=2 QUIESCE_FULL_NEW_INSTANCE_CHECK_SECONDS=1
  mkdir -p "$FAKE_QDB_DIR"
}

# §3.7: 書込み静止の信号表は CONTENT_HASH_TABLES に指定できない（計算の前に失敗し、full を VALID にしない）
case_backup_full_content_hash_rejects_signal_tables() {
  setup_full
  export CONTENT_HASH_TABLES="sys_use,sys_backup_quiesce_ack"
  local out code
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "信号表を CONTENT_HASH_TABLES に指定すると full は非 0"
  assert_contains "$out" "書込み静止の信号表は CONTENT_HASH_TABLES に指定できません" "理由を通知"
  assert_not_contains "$(cat "$FAKE_ARGV_LOG" 2>/dev/null)" "sys_backup_quiesce_ack" "信号表の内容を読まない（計算の前に失敗する）"
  assert_eq 0 "$(full_index_count)" "index に登録しない"
  unset CONTENT_HASH_TABLES
  teardown_full
}

full_index_count() { compgen -G "$T/work/index/full-*.json" | wc -l; }

# verify は静止区間の採取（dump・uploads・count・内容ハッシュ・schema fingerprint）の後、解除の直前に呼ぶ（§7.2）。
# mysqldump を background で動かしても正常に終われば VALID（D4-P3-4 の正常終了）
case_backup_full_quiesce_db_verifies_before_release() {
  setup_full_qdb
  local out code idx steps
  out=$("$BACKUP_FULL" 2> "$T/err.log")
  code=$?
  assert_zero "$code" "quiesce-db の full が成功する（heartbeat の directory は要らない）"
  assert_eq VALID "$(jq -r .status <<< "$out" 2>/dev/null)" "結果は VALID"
  assert_eq quiesce-db "$(jq -r .quiesce.provider <<< "$out" 2>/dev/null)" "結果に provider を記録"
  assert_eq 7 "$(jq -r .quiesce.request_id <<< "$out" 2>/dev/null)" "結果に要求の番号を記録"
  assert_eq 2026-11-22T00:00:06Z "$(jq -r .quiesce.verified_at_utc <<< "$out" 2>/dev/null)" "結果に verify の時刻を記録"
  assert_eq 2026-11-22T00:00:06Z "$(jq -r .consistency_time_utc <<< "$out" 2>/dev/null)" "整合時刻は全 ack を確かめた DB の時刻"
  idx=$(compgen -G "$T/work/index/full-*.json" | head -n 1)
  assert_eq true "$(jq -r '.quiesce.window_seconds | type == "number"' "$idx" 2>/dev/null)" "index に静止区間の秒数を記録"
  assert_eq ONLINE "$(jq -r '.quiesce.mode' "$idx" 2>/dev/null)" "index（metadata）に mode を記録"
  # 2d review P3-4: backup の保存期間まで残る metadata（restic・index）には quiesce.json の投影だけを入れる。instance は ID と件数で、
  # app の host 名・接続元・要求の token・state dir の path を入れない（quiesce.json の全体は state dir にだけ残る）
  assert_eq 7 "$(jq -r '.quiesce.request_id' "$idx" 2>/dev/null)" "index（metadata）に要求の番号を記録"
  assert_eq 2026-11-22T00:00:06Z "$(jq -r '.quiesce.verified_at_utc' "$idx" 2>/dev/null)" "index（metadata）に verify の時刻を記録"
  assert_eq 1 "$(jq -r '.quiesce.instance_count' "$idx" 2>/dev/null)" "index（metadata）に instance の件数を記録"
  assert_eq 0b7c6a2e-1111-4a5b-9c3d-000000000001 "$(jq -r '.quiesce.instance_ids[0]' "$idx" 2>/dev/null)" \
    "index（metadata）に instance の ID を記録"
  assert_eq 0 "$(jq '[.quiesce | .. | objects | keys[] | select(. == "host_name" or . == "db_client_host" or . == "request_token"
      or . == "state_dir")] | length' "$idx" 2>/dev/null)" "index（metadata）に host 名・接続元・token・state dir の key が無い"
  assert_not_contains "$(cat "$idx")" "app-host-1" "index（metadata）に app の host 名が無い"
  assert_not_contains "$(cat "$idx")" "10.0.0.11" "index（metadata）に app の接続元が無い"
  assert_not_contains "$(cat "$idx")" "$QUIESCE_STATE_DIR" "index（metadata）に state dir の path が無い"
  assert_eq app-host-1 "$(jq -r '.instances[0].host_name' "$QUIESCE_STATE_DIR/quiesce.json" 2>/dev/null)" \
    "quiesce.json の全体（host 名を含む）は job の state dir にだけ残る"
  steps=$(cut -f1 "$QUIESCE_STATE_DIR/window.steps" | tr '\n' ' ')
  assert_eq "acquire dump uploads table_counts schema_fingerprint verify release " "$steps" \
    "verify は静止区間の採取の後・解除の直前に呼ぶ（内訳の順）"
  assert_contains "$(qdb_main_calls)" "verify-request verify-check release resume-check" \
    "verify の後に解除し、再開を確かめる"
  assert_contains "$(awk -F'\t' '$2 == "release" { print $3 }' "$FAKE_QDB_DIR/calls.log")" "reason = 'COMPLETED';" \
    "verify 済みの解除は COMPLETED"
  assert_contains "$(awk -F'\t' '$2 == "insert-request" { print $3 }' "$FAKE_QDB_DIR/calls.log")" "job_kind = 'FULL';" "full の要求"
  assert_file "$FAKE_QDB_DIR/ddl-lock.released" "DDL 凍結の lock を返す"
  teardown_full
}

case_backup_full_quiesce_db_verify_failure_not_valid() {
  setup_full_qdb
  printf 'VERDICT\tFAIL_LATE_ACK\t2026-11-22T00:00:09Z\t1763769609000000\t1\tlate=1\n' > "$FAKE_QDB_DIR/verify-check.out"
  local out code count
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "verify が失敗すれば full は非 0"
  assert_contains "$out" "静止が途中で途切れたため VALID にしません" "理由を通知"
  assert_eq 0 "$(full_index_count)" "verify の失敗で index に VALID を登録しない"
  count=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq 'length')
  assert_eq 0 "${count:-0}" "verify の失敗で snapshot を発行しない"
  assert_contains "$(awk -F'\t' '$2 == "release" { print $3 }' "$FAKE_QDB_DIR/calls.log")" "reason = 'VERIFY_FAILED';" \
    "cleanup が VERIFY_FAILED で解除する"
  assert_file "$QUIESCE_STATE_DIR/hold.exited" "失敗の後も保持 process を終わらせる"
  teardown_full
}

# D4-P3-4: background の mysqldump の失敗は wait の終了コードで判定し、静止を解除して非 0 で終わる
case_backup_full_quiesce_db_dump_failure() {
  setup_full_qdb
  export FAKE_DUMP_RC=3
  local out code
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "mysqldump の失敗で非 0"
  assert_contains "$out" "mysqldump が失敗しました" "mysqldump の失敗を通知"
  assert_eq 0 "$(full_index_count)" "mysqldump の失敗で index に登録しない"
  assert_contains "$(awk -F'\t' '$2 == "release" { print $3 }' "$FAKE_QDB_DIR/calls.log")" "status = 'ABORTED'; reason = 'ABORTED';" \
    "verify の前の失敗は ABORTED で解除する"
  assert_eq 0 "$(awk -F'\t' '$2 == "verify-request" { c++ } END { print c + 0 }' "$FAKE_QDB_DIR/calls.log")" "失敗した full は verify しない"
  unset FAKE_DUMP_RC
  teardown_full
}

# U-13・D3-P3-4・D4-P3-4: ONLINE の full の最中に新しい instance が登録されたら、保持 process が要求を中止し、job は
# mysqldump を止めて（kill の後に wait）10 秒以内に非 0 で終わる
case_backup_full_new_instance_aborts_dump() {
  setup_full_qdb
  export FAKE_DUMP_SLEEP=60 FAKE_DUMP_PID_FILE="$T/dump.pid"
  printf 'ABORT\t1\t1\n' > "$FAKE_QDB_DIR/full-new-instance.2.out"
  local out code end mark since dump_pid
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  end=$(date +%s)
  assert_nonzero "$code" "full の中止で非 0"
  assert_contains "$out" "BACKUP_QUIESCE_FULL_ABORTED" "中止の errorCode を通知"
  assert_contains "$out" "取り直してください" "取り直しを案内する"
  assert_contains "$out" "mysqldump を止めました" "mysqldump を止めたことを通知"
  # 2d review P3-8: tasks の字面どおり、中止の印（full-aborted）が書かれてから job が終わるまでを 10 秒以内とする
  mark=$(stat -c %Y "$QUIESCE_STATE_DIR/full-aborted" 2>/dev/null || true)
  if [[ "$mark" =~ ^[0-9]+$ ]]; then
    since=$(( end - mark ))
    if (( since <= 10 )); then
      test_assert "中止の印から 10 秒以内に非 0 で終わる（${since} 秒。mysqldump の完了の 60 秒を待たない）"
    else
      test_fail "中止の印から 10 秒以内に非 0 で終わる" "${since} 秒"
    fi
  else
    test_fail "中止の印から 10 秒以内に非 0 で終わる" "中止の印（full-aborted）がありません"
  fi
  dump_pid=$(cat "$T/dump.pid" 2>/dev/null || true)
  if [[ -n "$dump_pid" ]] && ! kill -0 "$dump_pid" 2>/dev/null; then
    test_assert "mysqldump の process を止めた"
  else
    test_fail "mysqldump の process を止めた" "pid=${dump_pid:-なし} が残っています"
    kill "$dump_pid" 2>/dev/null
  fi
  assert_eq 0 "$(full_index_count)" "中止した full を index に登録しない"
  unset FAKE_DUMP_SLEEP FAKE_DUMP_PID_FILE
  teardown_full
}

# BWQ-010: app が動いていない時の full は --app-stopped でだけ選ぶ（QUIESCE_PROVIDER の環境変数では選べない。§9.3）
case_backup_full_app_stopped() {
  setup_full_qdb
  local out code
  out=$("$BACKUP_FULL" --app-stopped 2> "$T/err.log")
  code=$?
  assert_zero "$code" "--app-stopped の full が成功する"
  assert_eq VALID "$(jq -r .status <<< "$out" 2>/dev/null)" "--app-stopped の full は VALID"
  assert_eq APP_STOPPED "$(jq -r .quiesce.mode <<< "$out" 2>/dev/null)" "結果に mode APP_STOPPED を記録"
  assert_eq quiesce-app-stopped "$(jq -r .quiesce.provider <<< "$out" 2>/dev/null)" "結果に provider を記録"
  assert_eq APP_STOPPED "$(jq -r '.quiesce.mode' "$(compgen -G "$T/work/index/full-*.json" | head -n 1)" 2>/dev/null)" \
    "index（metadata）に mode APP_STOPPED を記録"
  assert_contains "$(qdb_main_calls)" "ddl-lock stopped-precheck insert-request stopped-check" \
    "停止確認の手順（§9.2）"
  assert_contains "$(cut -f2 "$FAKE_QDB_DIR/calls.log" | tr '\n' ' ')" "preflight" "preflight は信号表と権限を検査する"
  teardown_full
}

case_backup_full_rejects_app_stopped_from_environment() {
  setup_full_qdb
  export QUIESCE_PROVIDER=quiesce-app-stopped
  local out code count
  out=$("$BACKUP_FULL" 2>&1)
  code=$?
  assert_nonzero "$code" "QUIESCE_PROVIDER=quiesce-app-stopped を環境変数で与えると full は始まらない"
  assert_contains "$out" "環境変数では選べません" "--app-stopped で明示するよう示す"
  assert_eq "" "$(cat "$FAKE_QDB_DIR/calls.log" 2>/dev/null)" "DB の信号表に触れない"
  count=$(RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    /usr/local/bin/restic snapshots --json 2>/dev/null | jq 'length')
  assert_eq 0 "${count:-0}" "snapshot を発行しない"
  out=$("$BACKUP_FULL" --app-stopped 2>&1)
  code=$?
  assert_nonzero "$code" "環境変数の停止確認は --app-stopped と組でも拒否する"
  export QUIESCE_PROVIDER=quiesce-db
  teardown_full
}

run_case case_backup_full_app_stopped
run_case case_backup_full_rejects_app_stopped_from_environment
run_case case_backup_full_quiesce_db_verifies_before_release
run_case case_backup_full_quiesce_db_verify_failure_not_valid
run_case case_backup_full_quiesce_db_dump_failure
run_case case_backup_full_new_instance_aborts_dump
run_case case_backup_full_content_hash_rejects_signal_tables
run_case case_backup_full_records_content_hash
run_case case_backup_full_content_hash_failure_fails_closed
run_case case_backup_full_content_hash_requires_primary_key
run_case case_backup_full_records_schema_fingerprint
run_case case_backup_full_schema_fingerprint_failure_fails_closed

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-003 full backup + manifest"
