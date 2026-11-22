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
  export FAKE_MYSQL_STATUS_ROW=$'8.4.13\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  export FAKE_MYSQL_ENGINES=$'InnoDB\t5'
  export FAKE_CLIENT_VERSION_LINE='mysql  Ver 8.4.13 for Linux on x86_64 (MySQL Community Server - GPL)'
  export FAKE_MYSQLBINLOG_VERSION_LINE='mysqlbinlog  Ver 8.4.13 for Linux on x86_64 (MySQL Community Server - GPL)'
  export FAKE_MYSQLDUMP_VERSION_LINE='mysqldump  Ver 8.4.13 for Linux on x86_64 (MySQL Community Server - GPL)'
}

setup_env() {
  T=$(mktemp -d)
  # RB-W 追加修正 C: uploads の根の下に版 v0 と current -> v0（production の配置）。廃止した UPLOADS_DIR は使わない
  export BACKUP_WORK_DIR="$T/work" UPLOADS_ROOT="$T/uploads" UPLOADS_POINTER=current
  unset UPLOADS_DIR UPLOADS_STAGING_PARENT
  mkdir -p "$BACKUP_WORK_DIR" "$UPLOADS_ROOT/v0" "$T/tmp" "$T/capath"
  ln -s v0 "$UPLOADS_ROOT/current"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  # hashed CA の fixture（内容は何でも良い。実 connect はしない）
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST='127.0.0.1' MYSQL_PORT='3306' MYSQL_USER='bkp-user-7x9' MYSQL_DATABASE='ses_manager_db'
  export MYSQL_SSL_CAPATH="$T/capath"
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export PREFLIGHT_MIN_FREE_BYTES=1
  export TMPDIR="$T/tmp"
  export FAKE_ARGV_LOG="$T/argv.log"
  unset MYSQL_PWD FAKE_GET_LOCK FAKE_RELEASE_LOCK FAKE_MISSING_VARIABLES QUIESCE_PROVIDER FAKE_QDB_DIR \
    QUIESCE_STALE_SECONDS QUIESCE_DEAD_SECONDS
  reset_fake
}

# ------------------------------------------------------------ RB-QUIESCE BWQ-009: checks.quiesce（exit 19）

setup_quiesce_env() {
  setup_env
  export QUIESCE_PROVIDER=quiesce-db FAKE_QDB_DIR="$T/qdb"
  mkdir -p "$FAKE_QDB_DIR"
}

preflight_grants_without_request_table() {
  printf 'TABLES\t3\nGRANTS_BEGIN\n'
  printf 'GRANT USAGE ON *.* TO `bkp-user-7x9`@`%%`\n'
  printf 'GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON `ses_manager_db`.* TO `bkp-user-7x9`@`%%`\n'
  printf 'GRANT INSERT ON `ses_manager_db`.`sys_backup_quiesce_request` TO `bkp-user-7x9`@`%%`\n'
  printf 'GRANTS_END\n'
  printf 'VERDICT\tOK\t2026-11-22T00:00:06Z\t1763769606000000\t1\tdisabled=0,fresh=1,protocol=0,threshold=0\n'
}

case_preflight_quiesce_skipped_for_local() {
  setup_env
  run_preflight
  assert_zero "$code" "quiesce-local（既定）では書込み静止の検査をしない"
  assert_eq skipped "$(jq -r .checks.quiesce.status <<< "$out")" "checks.quiesce は skipped"
}

case_preflight_quiesce_ok() {
  setup_quiesce_env
  run_preflight
  assert_zero "$code" "信号表・表単位の権限・instance が揃えば exit 0"
  assert_eq ok "$(jq -r .checks.quiesce.status <<< "$out")" "checks.quiesce は ok"
  assert_eq 3 "$(jq -r .checks.quiesce.signal_tables <<< "$out")" "信号表は 3 表"
  assert_eq true "$(jq -r .checks.quiesce.request_table_grant <<< "$out")" "要求の表への表単位の INSERT・UPDATE がある"
  assert_contains "$(cut -f2 "$FAKE_QDB_DIR/calls.log")" "preflight" "読むだけの SQL（preflight.sql）を実行する"
  assert_not_contains "$out" 'ses_manager_db' "DB 名を出力しない"
  assert_not_contains "$out" 'bkp-user-7x9' "user を出力しない（SHOW GRANTS の行を出さない）"
  assert_eq 0 "$(find "$T/tmp" -type f 2>/dev/null | wc -l)" "一時 file（SQL・option file）を残さない"
}

case_preflight_quiesce_failures_exit_19() {
  local kind expect
  for kind in grant tables disabled protocol settings; do
    setup_quiesce_env
    case "$kind" in
      grant)
        preflight_grants_without_request_table > "$FAKE_QDB_DIR/preflight.out"
        expect="表単位の INSERT・UPDATE がありません" ;;
      tables)
        printf 'TABLES\t2\nGRANTS_BEGIN\nGRANTS_END\n' > "$FAKE_QDB_DIR/preflight.out"
        printf '1\n' > "$FAKE_QDB_DIR/preflight.rc"
        expect="信号表" ;;
      disabled)
        { printf 'TABLES\t3\nGRANTS_BEGIN\n'
          printf 'GRANT INSERT, UPDATE ON `ses_manager_db`.`sys_backup_quiesce_request` TO `bkp-user-7x9`@`%%`\n'
          printf 'GRANTS_END\nVERDICT\tFAIL_DISABLED\t2026-11-22T00:00:06Z\t1763769606000000\t0\tdisabled=1\n'
          printf 'INSTANCE\tdead-0001\tDISABLED\t0\t-\t-\t-\tapp-host-9\t10.0.0.9\t2026-11-01T00:00:00.000000Z\t-\t-\t-\t0\t300000\t900000\tRUNNING\tDISABLED\n'
        } > "$FAKE_QDB_DIR/preflight.out"
        expect="停止口" ;;
      protocol)
        { printf 'TABLES\t3\nGRANTS_BEGIN\n'
          printf 'GRANT INSERT, UPDATE ON `ses_manager_db`.`sys_backup_quiesce_request` TO `bkp-user-7x9`@`%%`\n'
          printf 'GRANTS_END\nVERDICT\tFAIL_PROTOCOL\t2026-11-22T00:00:06Z\t1763769606000000\t1\tprotocol=1\n'
        } > "$FAKE_QDB_DIR/preflight.out"
        expect="protocol の版" ;;
      settings)
        export QUIESCE_STALE_SECONDS=300 QUIESCE_DEAD_SECONDS=300
        expect="QUIESCE_STALE_SECONDS" ;;
    esac
    run_preflight
    assert_eq 19 "$code" "checks.quiesce の不合格（$kind）は exit 19"
    assert_eq fail "$(jq -r .checks.quiesce.status <<< "$out")" "checks.quiesce は fail（$kind）"
    assert_contains "$out" "$expect" "理由を出す（$kind）"
    assert_no_secret "$out"
    if [[ "$kind" == disabled ]]; then
      # 2d review P3-4: instance は ID と件数だけで示し、app の host 名・接続元を出力 JSON に出さない
      assert_contains "$out" "1 件（dead-0001）" "停止口の instance を ID と件数で示す"
      assert_not_contains "$out" "app-host-9" "app の host 名を出力しない"
      assert_not_contains "$out" "10.0.0.9" "app の接続元を出力しない"
    fi
    unset QUIESCE_STALE_SECONDS QUIESCE_DEAD_SECONDS
  done
}

# 2d review P2-1: SQL の失敗の文が理由に出る（command substitution で失われない）。password と接続の識別子
# （user@host・MYSQL_HOST・DB 名・user）は伏せる
case_preflight_quiesce_sql_error_is_shown_without_identifiers() {
  local kind
  for kind in tables instances; do
    setup_quiesce_env
    if [[ "$kind" == tables ]]; then
      printf 'TABLES\t0\nGRANTS_BEGIN\nGRANTS_END\n' > "$FAKE_QDB_DIR/preflight.out"
      printf '%s\n' "ERROR 1146 (42S02) at line 18: Table 'ses_manager_db.sys_backup_quiesce_instance' doesn't exist" \
        > "$FAKE_QDB_DIR/preflight.err"
    else
      { printf 'TABLES\t3\nGRANTS_BEGIN\n'
        printf 'GRANT INSERT, UPDATE ON `ses_manager_db`.`sys_backup_quiesce_request` TO `bkp-user-7x9`@`%%`\n'
        printf 'GRANTS_END\n'
      } > "$FAKE_QDB_DIR/preflight.out"
      printf '%s\n' "ERROR 1142 (42000) at line 21: SELECT command denied to user 'bkp-user-7x9'@'10.1.2.3' for table 'sys_backup_quiesce_instance'" \
        "password=$FAKE_PW 127.0.0.1" > "$FAKE_QDB_DIR/preflight.err"
    fi
    printf '1\n' > "$FAKE_QDB_DIR/preflight.rc"
    run_preflight
    assert_eq 19 "$code" "SQL の失敗は exit 19（$kind）"
    if [[ "$kind" == tables ]]; then
      assert_contains "$out" "doesn't exist" "MySQL の失敗の文を理由に出す（$kind）"
      assert_contains "$out" "<db>.sys_backup_quiesce_instance" "DB 名を伏せて表の名前は残す（$kind）"
    else
      assert_contains "$out" "instance の検査を実行できません: ERROR 1142 (42000)" "MySQL の失敗の文を理由に出す（$kind）"
      assert_contains "$out" "SELECT command denied" "失敗の理由（権限の不足）が分かる（$kind）"
      assert_not_contains "$out" "10.1.2.3" "接続元を出力しない（$kind）"
      assert_not_contains "$out" "127.0.0.1" "MYSQL_HOST を出力しない（$kind）"
    fi
    assert_not_contains "$out" "ses_manager_db" "DB 名を出力しない（$kind）"
    assert_not_contains "$out" "bkp-user-7x9" "user を出力しない（$kind）"
    assert_no_secret "$out"
  done
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
  assert_contains "$out" '"mysql_client_version": "8.4.13"' "client version 出力"
  assert_contains "$out" '"server_version": "8.4.13"' "server version 出力"
  assert_contains "$out" '"binlog_on": true' "log_bin ON"
  assert_contains "$out" '"binlog_checksum": "CRC32"' "checksum 出力"
  assert_contains "$out" '"gtid_mode": "ON"' "GTID 出力"
  assert_contains "$out" '"tls_ok": true' "TLS OK"
  assert_contains "$out" '"all_tables_innodb": true' "InnoDB 判定"
  assert_contains "$out" 'sha256:test-digest-001' "tool digest 出力"
  # RB-W 追加修正 C: pointer を解決し、指す版を出力する
  assert_contains "$out" '"pointer": "current"' "uploads の pointer の名前を出力"
  assert_contains "$out" '"version": "v0"' "uploads の pointer が指す版を出力"
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

# RB-W 8.4 対応: production は MySQL 8.4 LTS だけ。8.0 の server は exit 11。
case_preflight_server_80() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.0.36\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 11 "$code" "server 8.0 は exit 11"
  assert_contains "$out" "MySQL 8.4 ではありません" "対応系列でないことを通知"
  assert_no_secret "$out"
}

# 同じ 8.4 系列なら patch の違う server（8.4.1）は受け付ける（client と同じ major.minor）
case_preflight_server_same_series_other_patch() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.4.1\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_zero "$code" "server 8.4.1 と client 8.4.13 は同じ系列で exit 0"
}

# mysql・mysqlbinlog・mysqldump はどれも server と同じ 8.4 系列であること（server が 8.4 でも、どれか 1 つが 8.0 なら exit 10）
case_preflight_tool_series_mismatch() {
  setup_env
  export FAKE_MYSQLDUMP_VERSION_LINE='mysqldump  Ver 8.0.46 for Linux on x86_64 (MySQL Community Server - GPL)'
  run_preflight
  assert_eq 10 "$code" "mysqldump 8.0 は exit 10"
  assert_contains "$out" "mysqldump が MySQL 8.4 ではありません" "理由を通知"
  setup_env
  export FAKE_MYSQLBINLOG_VERSION_LINE='mysqlbinlog  Ver 8.0.46 for Linux on x86_64 (MySQL Community Server - GPL)'
  run_preflight
  assert_eq 10 "$code" "mysqlbinlog 8.0 は exit 10"
  assert_contains "$out" "mysqlbinlog が MySQL 8.4 ではありません" "理由を通知（mysqlbinlog）"
  setup_env
  export FAKE_CLIENT_VERSION_LINE='mysql  Ver 8.0.46 for Linux on x86_64 (MySQL Community Server - GPL)'
  run_preflight
  assert_eq 10 "$code" "mysql client 8.0 は exit 10"
  assert_contains "$out" "mysql client が MySQL 8.4 ではありません" "理由を通知（mysql client）"
}

# RB-W 8.4 対応: server の変数は SHOW GLOBAL VARIABLES で読み、無い変数は名前を挙げて失敗する（1 つの SELECT 全体の
# 失敗にしない）。判定に使わない変数（binlog_format 等。8.4 で非推奨）が無いだけなら失敗しない。
case_preflight_missing_server_variable() {
  setup_env
  export FAKE_MISSING_VARIABLES="binlog_checksum"
  run_preflight
  assert_eq 11 "$code" "判定に要る変数が無ければ exit 11"
  assert_contains "$out" "server の変数を取得できません: binlog_checksum" "無い変数の名前を通知"
  export FAKE_MISSING_VARIABLES="binlog_format binlog_transaction_compression"
  run_preflight
  assert_zero "$code" "判定に使わない変数が無いだけなら exit 0"
  unset FAKE_MISSING_VARIABLES
}

case_preflight_logbin_off() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.4.13\t'"$FAKE_UUID"$'\t0\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 12 "$code" "log_bin OFF は exit 12"
  assert_contains "$out" '"binlog_on": false' "log_bin false 出力"
  assert_no_secret "$out"
}

case_preflight_checksum_off() {
  setup_env
  export FAKE_MYSQL_STATUS_ROW=$'8.4.13\t'"$FAKE_UUID"$'\t1\tROW\tOFF\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
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
  export FAKE_MYSQL_STATUS_ROW=$'8.4.13\t'"$FAKE_UUID"$'\t1\tROW\tCRC32\t0\tON\tNO\t/var/lib/mysql/binlog.000001\t2592000'
  run_preflight
  assert_eq 15 "$code" "TLS 無効は exit 15"
  assert_contains "$out" '"tls_ok": false' "TLS false 出力"
  assert_no_secret "$out"
}

# RB-W 追加修正 C: UPLOADS_ROOT の pointer を provider と同じ規則で解決できなければ exit 16。廃止した UPLOADS_DIR が
# 残っていても exit 16（版の directory や根を pointer を介さずに backup しない）
case_preflight_uploads_missing() {
  setup_env
  export UPLOADS_ROOT="$T/not-exists"
  run_preflight
  assert_eq 16 "$code" "uploads の根が無ければ exit 16"
  assert_contains "$out" "uploads の根が実 directory ではありません" "理由を通知: 根が無い"
  assert_no_secret "$out"

  setup_env
  rm "$UPLOADS_ROOT/current"
  run_preflight
  assert_eq 16 "$code" "pointer が無ければ exit 16"
  assert_contains "$out" "current がありません" "理由を通知: pointer が無い"
  assert_contains "$out" '"status": "pointer-unresolved"' "uploads の状態を出力"

  setup_env
  rm "$UPLOADS_ROOT/current"
  ln -s "$UPLOADS_ROOT/v0" "$UPLOADS_ROOT/current"
  run_preflight
  assert_eq 16 "$code" "絶対 path の pointer は exit 16"
  assert_contains "$out" "1 段の名前ではありません" "理由を通知: 絶対 path"

  setup_env
  export UPLOADS_DIR="$T/uploads/v0"
  run_preflight
  unset UPLOADS_DIR
  assert_eq 16 "$code" "廃止した UPLOADS_DIR が残っていれば exit 16"
  assert_contains "$out" "UPLOADS_DIR は廃止しました" "理由を通知: 廃止した設定"
  assert_contains "$out" '"status": "deprecated-key"' "uploads の状態を出力: 廃止した設定"
}

# RB-W 追加修正 C: UPLOADS_STAGING_PARENT も指定されていれば、pointer の指す版と staging の親が同じ mount（st_dev が同じ）で
# あることを provider と同じ stat -L -c %d で確かめる。違う・取れなければ exit 16（初回 backup の前に配置の誤りを見つける）
case_preflight_uploads_staging_same_mount() {
  local real_dev
  setup_env
  mkdir -p "$T/staging"
  export UPLOADS_STAGING_PARENT="$T/staging"
  run_preflight
  assert_zero "$code" "uploads の版と同じ mount の staging なら exit 0"
  assert_contains "$out" '"staging_same_filesystem": true' "同じ mount であることを記録"

  # stat の stub で staging の st_dev だけを違う値にする（別々に mount した access point を再現）
  real_dev=$(stat -L -c %d "$T/staging")
  mkdir -p "$T/bin"
  cat > "$T/bin/stat" <<EOF
#!/usr/bin/env bash
if [[ "\$*" == "-L -c %d $T/staging" ]]; then echo $((real_dev + 1)); exit 0; fi
exec "$(command -v stat)" "\$@"
EOF
  chmod +x "$T/bin/stat"
  out=$(PATH="$T/bin:$PATH" BACKUP_TOOL_IMAGE_DIGEST='sha256:test-digest-001' "$PREFLIGHT" --json 2>&1)
  code=$?
  assert_eq 16 "$code" "staging が別の mount なら exit 16"
  assert_contains "$out" "同じ mount ではありません" "理由を通知: 別の mount"
  assert_contains "$out" '"staging_same_filesystem": false' "別の mount であることを記録"

  export UPLOADS_STAGING_PARENT="$T/no-staging"
  run_preflight
  assert_eq 16 "$code" "staging の親が無ければ exit 16"
  assert_contains "$out" "filesystem（st_dev）を取得できません" "理由を通知: staging の親が無い"
  unset UPLOADS_STAGING_PARENT
  assert_no_secret "$out"
}

# RB-W P3-6: binlog_format の変数があり ROW でなければ exit 12（DBA の前提は ROW）。変数そのものが無い版は判定しない
# （case_preflight_missing_server_variable）
case_preflight_binlog_format_not_row() {
  local fmt
  for fmt in MIXED STATEMENT; do
    setup_env
    export FAKE_MYSQL_STATUS_ROW=$'8.4.13\t'"$FAKE_UUID"$'\t1\t'"$fmt"$'\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
    run_preflight
    assert_eq 12 "$code" "binlog_format が $fmt なら exit 12"
    assert_contains "$out" "binlog_format が ROW ではありません: $fmt" "理由を通知: $fmt"
    assert_no_secret "$out"
  done
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
  # mysql-options は common::trap_add に依存するため、必ず common.sh を先に source する。
  # （ACC-OPS-P2-001: 依存未ロードのまま init を呼ぶと fail-closed で非 0 になる）
  # shellcheck disable=SC1091
  . "$ROOT/ops/backup/lib/common.sh"
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
run_case case_preflight_server_80
run_case case_preflight_server_same_series_other_patch
run_case case_preflight_tool_series_mismatch
run_case case_preflight_missing_server_variable
run_case case_preflight_logbin_off
run_case case_preflight_checksum_off
run_case case_preflight_noninnodb
run_case case_preflight_empty_db_engines
run_case case_preflight_tls_off
run_case case_preflight_uploads_missing
run_case case_preflight_uploads_staging_same_mount
run_case case_preflight_binlog_format_not_row
run_case case_preflight_disk_full
run_case case_preflight_mysql_pwd_env
run_case case_preflight_tls_config_missing
run_case case_preflight_help
run_case case_preflight_quiesce_skipped_for_local
run_case case_preflight_quiesce_ok
run_case case_preflight_quiesce_failures_exit_19
run_case case_preflight_quiesce_sql_error_is_shown_without_identifiers

# 全 case 出力の秘密 grep（framework log 含む）
if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-001 preflight"
