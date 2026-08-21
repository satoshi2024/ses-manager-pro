#!/usr/bin/env bash
# HFP-03-001 preflight 契約 test
#
# baseline §5 の failure inventory のうち HFP-03-001 担当分:
#   (8) MYSQL_PWD が child process environment に存在する（=使用検出で非0）
# および HFP-03-AC-001-01/02、HFP-03-RQ-008（secret argv/env/log 非含有）を固定する。
# fake mysql client を使い、実 DB には接続しない。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091  # HERE 経由の source は静的解決できない
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
PREFLIGHT="$ROOT/ops/backup/preflight.sh"
FIXTURES="$HERE/fixtures/bin"

FAKE_UUID='11111111-2222-3333-4444-555555555555'
FAKE_PW='S3cr3t-Value-XYZ-001'

reset_fake() {
  export FAKE_MYSQL_STATUS_ROW=$'8.0.36\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  export FAKE_MYSQL_ENGINES=$'InnoDB\t5'
  export FAKE_CLIENT_VERSION_LINE='mysql  Ver 8.0.36 for Linux on x86_64 (MySQL Community Server - GPL)'
  export FAKE_MYSQLBINLOG_VERSION_LINE='mysqlbinlog  Ver 8.0.36 for Linux on x86_64 (MySQL Community Server - GPL)'
  export FAKE_MYSQLDUMP_VERSION_LINE='mysqldump  Ver 8.0.36 for Linux on x86_64 (MySQL Community Server - GPL)'
}

setup_env() {
  T=$(mktemp -d)
  export BACKUP_WORK_DIR="$T/work" UPLOADS_DIR="$T/uploads"
  mkdir -p "$BACKUP_WORK_DIR" "$UPLOADS_DIR" "$T/tmp" "$T/capath"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  # hashed CA の fixture（内容は何でも良い。実 connect はしない）
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST='127.0.0.1' MYSQL_PORT='3306' MYSQL_USER='bkp-user-7x9' MYSQL_DATABASE='ses_manager_db'
  export MYSQL_SSL_CAPATH="$T/capath"
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export PREFLIGHT_MIN_FREE_BYTES=1
  export TMPDIR="$T/tmp"
  export FAKE_ARGV_LOG="$T/argv.log"
  unset MYSQL_PWD FAKE_GET_LOCK FAKE_RELEASE_LOCK
  reset_fake
}

run_preflight() {
  out=$(env BACKUP_TOOL_IMAGE_DIGEST='sha256:test-digest-001' "$PREFLIGHT" --json 2>&1)
  code=$?
}

assert_no_secret() { # out に FAKE_PW が現れないこと
  assert_not_contains "$1" "$FAKE_PW" "秘密が出力に含まれない"
}

case_preflight_normal() {
  setup_env
  run_preflight
  assert_zero "$code" "正常系 exit 0"
  assert_contains "$out" '"status": "OK"' "status OK"
  assert_contains "$out" '"server_uuid": "'"$FAKE_UUID"'"' "server_uuid 出力"
  assert_contains "$out" '"mysql_client_version": "8.0.36"' "client version 出力"
  assert_contains "$out" '"server_version": "8.0.36"' "server version 出力"
  assert_contains "$out" '"binlog_on": true' "log_bin ON"
  assert_contains "$out" '"binlog_checksum": "CRC32"' "checksum 出力"
  assert_contains "$out" '"gtid_mode": "ON"' "GTID 出力"
  assert_contains "$out" '"tls_ok": true' "TLS OK"
  assert_contains "$out" '"all_tables_innodb": true' "InnoDB 判定"
  assert_contains "$out" 'sha256:test-digest-001' "tool digest 出力"
  assert_not_contains "$out" '127.0.0.1' "host を出力しない"
  assert_not_contains "$out" 'ses_manager_db' "DB 名を出力しない"
  assert_not_contains "$out" 'bkp-user-7x9' "user を出力しない"
  assert_no_secret "$out"
  # optfile が argv 先頭、かつ後で削除されている
  assert_file "$FAKE_ARGV_LOG" "argv log あり"
  first=$(grep -E '^--defaults-extra-file=' "$FAKE_ARGV_LOG" | head -n1)
  assert_contains "$first" '--defaults-extra-file=' "argv 先頭が defaults-extra-file"
  leftover=$(find "$T/tmp" -type f 2>/dev/null | wc -l)
  assert_eq 0 "$leftover" "option file が trap で削除される"
  assert_no_secret "$first" "argv に秘密なし"
}

case_preflight_mariadb_client() {
  setup_env
  export FAKE_CLIENT_VERSION_LINE='mysql  Ver 15.1 Distrib 10.11.6-MariaDB, for Linux (x86_64) using readline 5.1'
  run_preflight
  assert_eq 10 "$code" "MariaDB client は exit 10"
  assert_contains "$out" 'MariaDB' "MariaDB と判別"
  assert_no_secret "$out"
}

case_preflight_client_57() {
  setup_env
  export FAKE_CLIENT_VERSION_LINE='mysql  Ver 5.7.44 for Linux on x86_64 (MySQL Community Server - GPL)'
  run_preflight
  assert_eq 10 "$code" "client 5.7 は exit 10"
  assert_no_secret "$out"
}

case_preflight_server_84() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.4.1\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 11 "$code" "server 8.4 は exit 11"
  assert_no_secret "$out"
}

case_preflight_logbin_off() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.0.36\t'"$FAKE_UUID"$'\t0\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 12 "$code" "log_bin OFF は exit 12"
  assert_contains "$out" '"binlog_on": false' "log_bin false 出力"
  assert_no_secret "$out"
}

case_preflight_checksum_off() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.0.36\t'"$FAKE_UUID"$'\t1\tROW\tOFF\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 13 "$code" "checksum OFF は exit 13"
  assert_no_secret "$out"
}

case_preflight_noninnodb() {
  setup_env
  export FAKE_MYSQL_ENGINES=$'InnoDB\t4\nMyISAM\t1'
  run_preflight
  assert_eq 14 "$code" "非 InnoDB は exit 14"
  assert_contains "$out" 'MyISAM' "engine 名を出力"
  assert_no_secret "$out"
}

case_preflight_tls_off() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.0.36\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tNO\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 15 "$code" "TLS 無効は exit 15"
  assert_contains "$out" '"tls_ok": false' "TLS false 出力"
  assert_no_secret "$out"
}

case_preflight_uploads_missing() {
  setup_env
  export UPLOADS_DIR="$T/not-exists"
  run_preflight
  assert_eq 16 "$code" "uploads 不在は exit 16"
  assert_no_secret "$out"
}

case_preflight_disk_full() {
  setup_env
  export PREFLIGHT_MIN_FREE_BYTES=9999999999999999
  run_preflight
  assert_eq 17 "$code" "空き容量不足は exit 17"
  assert_no_secret "$out"
}

case_preflight_mysql_pwd_env() {
  setup_env
  export MYSQL_PWD="$FAKE_PW"
  run_preflight
  assert_eq 18 "$code" "MYSQL_PWD 使用検出で exit 18"
  assert_contains "$out" 'MYSQL_PWD' "MYSQL_PWD を拒否メッセージに含む"
  assert_no_secret "$out"
  # argv log にも出ない
  if [[ -f "$FAKE_ARGV_LOG" ]]; then
    assert_no_secret "$(cat "$FAKE_ARGV_LOG")"
  fi
}

case_preflight_empty_db_engines() {
  setup_env
  export FAKE_MYSQL_ENGINES=""
  run_preflight
  assert_zero "$code" "空 DB の engine 検査は exit 0"
  assert_contains "$out" '"all_tables_innodb": true' "空 DB で innodb true"
  assert_no_secret "$out"
}

case_preflight_tls_config_missing() {
  setup_env
  unset MYSQL_SSL_CAPATH MYSQL_SSL_CA
  run_preflight
  assert_nonzero "$code" "TLS 設定なしは非 0"
  assert_contains "$out" 'TLS 設定' "TLS 設定不足を拒否"
  assert_no_secret "$out"
}

case_preflight_optionfile_contains_capath() {
  setup_env
  # lib を直接 source して option file の内容を検査する（preflight は
  # 終了時に trap で削除するため、生存中に検査する必要がある）
  # shellcheck disable=SC1091
  . "$ROOT/ops/backup/lib/mysql-options.sh"
  local rc=0
  mysql_options::init || rc=$?
  assert_zero "$rc" "mysql_options::init exit 0"
  assert_file "$MYSQL_OPTFILE" "option file あり"
  local content=""
  content=$(cat "$MYSQL_OPTFILE")
  assert_contains "$content" 'ssl-capath=' "option file に ssl-capath"
  assert_contains "$content" 'ssl-mode=VERIFY_CA' "option file に VERIFY_CA"
  assert_eq "600" "$(stat -c %a "$MYSQL_OPTFILE" 2>/dev/null || echo n/a)" "option file mode 600"
  _mysql_options::cleanup
}

case_preflight_help() {
  setup_env
  out=$("$PREFLIGHT" --help 2>&1)
  assert_zero "$?" "--help exit 0"
  assert_contains "$out" 'preflight' "usage 出力"
}

run_case case_preflight_normal
run_case case_preflight_optionfile_contains_capath
run_case case_preflight_mariadb_client
run_case case_preflight_client_57
run_case case_preflight_server_84
run_case case_preflight_logbin_off
run_case case_preflight_checksum_off
run_case case_preflight_noninnodb
run_case case_preflight_empty_db_engines
run_case case_preflight_tls_off
run_case case_preflight_uploads_missing
run_case case_preflight_disk_full
run_case case_preflight_mysql_pwd_env
run_case case_preflight_tls_config_missing
run_case case_preflight_help

# 全 case 出力の秘密 grep（framework log 含む）
if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-001 preflight"
