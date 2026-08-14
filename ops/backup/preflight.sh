#!/usr/bin/env bash
# ============================================================
# HFP-03-001 読み取り専用 preflight（環境契約検査）
# MySQL 8 client/server、source UUID、binlog、table engine、
# TLS、空き容量、uploads topology を機械判定し JSON を出力する。
# 読み取り専用であり、DB へ一切書込みを行わない。
#
# 使い方: preflight.sh [--json|--help]
#
# exit code:
#   0   全チェック PASS
#   10  mysql client が不正（MariaDB / 非 8.0）
#   11  server version が 8.0 以外、または client/server 不整合
#   12  log_bin = OFF
#   13  binlog checksum が OFF
#   14  対象 DB に非 InnoDB table がある
#   15  TLS が無効
#   16  UPLOADS_DIR 指定だが存在しない
#   17  空き容量が PREFLIGHT_MIN_FREE_BYTES 未満
#   18  MYSQL_PWD の使用を検出
#   1   上記以外（env 不足、接続失敗）
#
# 注意: 出力 JSON に host / user / DB 名 / パスワードは含めない。
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"

EXIT_CLIENT=10
EXIT_SERVER=11
EXIT_BINLOG=12
EXIT_CHECKSUM=13
EXIT_ENGINE=14
EXIT_TLS=15
EXIT_UPLOADS=16
EXIT_DISK=17
EXIT_MYSQL_PWD=18

usage() {
  cat <<'EOF'
Usage: preflight.sh [--json|--help]

環境契約検査を実行し、結果 JSON を標準出力へ出力する。
終了コード: 0=PASS, 10=client不正, 11=server version不整合, 12=log_bin OFF,
13=checksum OFF, 14=非InnoDB, 15=TLS無効, 16=uploads不在, 17=空き容量不足,
18=MYSQL_PWD使用検出, 1=env不足/接続失敗

環境変数: MYSQL_HOST, MYSQL_PORT, MYSQL_USER, MYSQL_PASSWORD_FILE,
MYSQL_DATABASE, BACKUP_WORK_DIR, UPLOADS_DIR(任意), PREFLIGHT_MIN_FREE_BYTES
EOF
}

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}
MYSQLDUMP_BIN=${MYSQLDUMP_BIN:-mysqldump}
# shellcheck disable=SC2153  # MYSQL_HOST は common::require_env で動的に検証済み

reasons=()
fail_code=0

# 最初の失敗コードを保持する（複数失敗時は先頭を使う）
note_fail() { # exit_code message
  local code=$1 msg=$2
  reasons+=("$msg")
  if [[ "$fail_code" -eq 0 ]]; then
    fail_code=$code
  fi
}

main() {
  common::require_env MYSQL_HOST
  common::require_env MYSQL_USER
  common::require_env MYSQL_DATABASE
  common::require_env BACKUP_WORK_DIR
  MYSQL_PORT=${MYSQL_PORT:-3306}

  # MYSQL_PWD 使用は即拒否（argv/env/log へ秘密を出さない）
  if [[ -n "${MYSQL_PWD:-}" ]]; then
    note_fail "$EXIT_MYSQL_PWD" "MYSQL_PWD の使用は禁止されています（MYSQL_PASSWORD_FILE を使用）"
  fi

  if ! mysql_options::init; then
    if [[ "$fail_code" -eq 0 ]]; then fail_code=1; fi
    note_fail 1 "mysql option file を作成できませんでした"
  fi

  local client_ver="" server_ver="" server_uuid="" log_bin=""
  local binlog_format="" binlog_checksum="" binlog_compression=""
  local gtid_mode="" have_ssl="" log_bin_basename="" binlog_expire=""
  local engines_out="" disk_free_bytes=""

  # --- client 検査（Oracle MySQL 8 のみ） ---
  local client_line=""
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    if client_line=$("$MYSQL_CLIENT_BIN" --version 2>&1); then
      case "$client_line" in
        *[Mm]ariaDB*)
          note_fail "$EXIT_CLIENT" "mysql client が MariaDB 系です: $client_line"
          ;;
        *)
          client_ver=$(printf '%s' "$client_line" | grep -oE '8\.0\.[0-9]+' | head -n1 || true)
          [[ -n "$client_ver" ]] || note_fail "$EXIT_CLIENT" "mysql client が MySQL 8.0 ではありません: $client_line"
          ;;
      esac
    else
      note_fail "$EXIT_CLIENT" "mysql client を実行できません: $MYSQL_CLIENT_BIN"
    fi
  fi

  # mysqlbinlog / mysqldump の存在確認
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    local bl=""
    if bl=$("$MYSQLBINLOG_BIN" --version 2>&1); then
      case "$bl" in
        *[Mm]ariaDB*) note_fail "$EXIT_CLIENT" "mysqlbinlog が MariaDB 系です: $bl" ;;
        *) [[ "$bl" == *"Ver 8.0"* ]] || note_fail "$EXIT_CLIENT" "mysqlbinlog が MySQL 8.0 ではありません: $bl" ;;
      esac
    else
      note_fail "$EXIT_CLIENT" "mysqlbinlog を実行できません: $MYSQLBINLOG_BIN"
    fi
    local dl=""
    if dl=$("$MYSQLDUMP_BIN" --version 2>&1); then
      case "$dl" in
        *[Mm]ariaDB*) note_fail "$EXIT_CLIENT" "mysqldump が MariaDB 系です: $dl" ;;
        *) [[ "$dl" == *"Ver 8.0"* ]] || note_fail "$EXIT_CLIENT" "mysqldump が MySQL 8.0 ではありません: $dl" ;;
      esac
    else
      note_fail "$EXIT_CLIENT" "mysqldump を実行できません: $MYSQLDUMP_BIN"
    fi
  fi

  # --- server status 検査（読み取りのみ） ---
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    local status_row=""
    # shellcheck disable=SC2153  # MYSQL_HOST は require_env で検証済みの env 値
    if status_row=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
        --execute "SELECT @@version, @@server_uuid, @@log_bin, @@binlog_format, @@binlog_checksum, @@binlog_transaction_compression, @@gtid_mode, @@have_ssl, @@log_bin_basename, @@binlog_expire_logs_seconds;" 2>&1); then
      IFS=$'\t' read -r server_ver server_uuid log_bin binlog_format binlog_checksum \
        binlog_compression gtid_mode have_ssl log_bin_basename binlog_expire <<< "$status_row"
    else
      note_fail 1 "server status を取得できません（接続/TLS を確認してください）: $(printf '%s' "$status_row" | common::redact)"
    fi
  fi

  if [[ -n "$server_ver" ]]; then
    [[ "$server_ver" == 8.0.* ]] || note_fail "$EXIT_SERVER" "server version が MySQL 8.0 ではありません: $server_ver"
    if [[ -n "$client_ver" ]]; then
      [[ "$client_ver" == 8.0.* && "$server_ver" == 8.0.* ]] || note_fail "$EXIT_SERVER" "client/server の major.minor が一致しません: client=$client_ver server=$server_ver"
    fi
  fi

  case "$log_bin" in
    1|ON) : ;;
    *) note_fail "$EXIT_BINLOG" "log_bin が OFF です" ;;
  esac

  case "$binlog_checksum" in
    ""|OFF|NONE) note_fail "$EXIT_CHECKSUM" "binlog checksum が無効です: ${binlog_checksum:-OFF}" ;;
  esac

  if [[ -n "$server_uuid" ]]; then
    : # UUID は JSON へ出力（secret ではない）
  else
    note_fail "$EXIT_SERVER" "server_uuid を取得できませんでした"
  fi

  # --- table engine 検査（対象 DB の BASE TABLE のみ） ---
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    local db_esc=${MYSQL_DATABASE//\'/\'\'}
    local sql="SELECT engine, COUNT(*) FROM information_schema.tables WHERE table_schema='$db_esc' AND table_type='BASE TABLE' GROUP BY engine;"
    if engines_out=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B --execute "$sql" 2>&1); then
      local noninnodb=""
      if [[ -n "$engines_out" ]]; then
        noninnodb=$(printf '%s\n' "$engines_out" | awk -F'\t' '$1 != "InnoDB" {print $1}' | tr '\n' ' ')
      fi
      if [[ -n "$noninnodb" ]]; then
        note_fail "$EXIT_ENGINE" "非 InnoDB table が存在します: $noninnodb"
      fi
    else
      note_fail 1 "table engine 検査を実行できません: $(printf '%s' "$engines_out" | common::redact)"
    fi
  fi

  # --- TLS 検査 ---
  case "$have_ssl" in
    YES) : ;;
    *) note_fail "$EXIT_TLS" "TLS が無効です（have_ssl=${have_ssl:-NO}）" ;;
  esac

  # --- uploads topology（任意。指定されたら存在チェック） ---
  local uploads_status="not-configured"
  local uploads_fs="" uploads_free=""
  if [[ -n "${UPLOADS_DIR:-}" ]]; then
    if [[ -d "$UPLOADS_DIR" ]]; then
      uploads_status="ok"
      uploads_fs=$(stat -f -c %T "$UPLOADS_DIR" 2>/dev/null || echo unknown)
      uploads_free=$(df -P -B1 "$UPLOADS_DIR" | awk 'NR==2 {print $4}')
    else
      uploads_status="missing"
      note_fail "$EXIT_UPLOADS" "UPLOADS_DIR が存在しません: $UPLOADS_DIR"
    fi
  fi

  # --- 空き容量検査 ---
  local min_free=${PREFLIGHT_MIN_FREE_BYTES:-536870912}
  if common::is_int "$min_free" && [[ -d "$BACKUP_WORK_DIR" ]]; then
    disk_free_bytes=$(df -P -B1 "$BACKUP_WORK_DIR" | awk 'NR==2 {print $4}')
    if (( disk_free_bytes < min_free )); then
      note_fail "$EXIT_DISK" "空き容量不足です: free=${disk_free_bytes}B min=${min_free}B"
    fi
  else
    note_fail "$EXIT_DISK" "BACKUP_WORK_DIR が存在しません: $BACKUP_WORK_DIR"
  fi

  local status="OK"
  local reasons_json="[]"
  if (( ${#reasons[@]} > 0 )); then
    status="FAIL"
    reasons_json=$(printf '%s\n' "${reasons[@]}" | jq -R . | jq -s .)
  fi

  # 各チェックの成否（reasons から機械判定ではなく、個別変数で持つ）
  local client_ok=false server_ok=false binlog_on=false tls_ok=false innodb_ok=false
  [[ -n "$client_ver" && "$client_ver" == 8.0.* ]] && client_ok=true
  [[ -n "$server_ver" && "$server_ver" == 8.0.* ]] && server_ok=true
  [[ "$log_bin" == 1 || "$log_bin" == "ON" ]] && binlog_on=true
  [[ "$have_ssl" == "YES" ]] && tls_ok=true
  local noninnodb_all=""
  noninnodb_all=$(printf '%s\n' "$engines_out" | awk -F'\t' '$1 != "InnoDB" {print}' | tr -d '[:space:]')
  [[ -z "$noninnodb_all" ]] && innodb_ok=true

  local checked_at
  checked_at=$(common::now_utc)

  jq -n \
    --arg schema_version "1" \
    --arg status "$status" \
    --arg checked_at_utc "$checked_at" \
    --argjson reasons "$reasons_json" \
    --arg client_ok "$client_ok" \
    --arg server_ok "$server_ok" \
    --arg binlog_on "$binlog_on" \
    --arg binlog_format "${binlog_format:-}" \
    --arg binlog_checksum "${binlog_checksum:-}" \
    --arg binlog_compression "${binlog_compression:-}" \
    --arg gtid_mode "${gtid_mode:-}" \
    --arg tls_ok "$tls_ok" \
    --arg all_tables_innodb "$innodb_ok" \
    --arg server_uuid "$server_uuid" \
    --arg server_version "$server_ver" \
    --arg mysql_client_version "$client_ver" \
    --arg log_bin_basename "${log_bin_basename:-}" \
    --arg tool_image_digest "${BACKUP_TOOL_IMAGE_DIGEST:-unknown}" \
    --arg binlog_expire_seconds "${binlog_expire:-}" \
    --arg uploads_status "$uploads_status" \
    --arg uploads_fs "$uploads_fs" \
    --arg uploads_free_bytes "$uploads_free" \
    --arg disk_free_bytes "$disk_free_bytes" \
    --arg work_dir "$BACKUP_WORK_DIR" \
    '{
      schema_version: $schema_version,
      status: $status,
      checked_at_utc: $checked_at_utc,
      reasons: $reasons,
      checks: {
        client_ok: ($client_ok == "true"),
        server_ok: ($server_ok == "true"),
        binlog_on: ($binlog_on == "true"),
        binlog_format: $binlog_format,
        binlog_checksum: $binlog_checksum,
        binlog_compression: $binlog_compression,
        gtid_mode: $gtid_mode,
        tls_ok: ($tls_ok == "true"),
        all_tables_innodb: ($all_tables_innodb == "true"),
        server_uuid: $server_uuid,
        server_version: $server_version,
        mysql_client_version: $mysql_client_version,
        log_bin_basename: $log_bin_basename,
        tool_image_digest: $tool_image_digest,
        binlog_expire_seconds: ($binlog_expire_seconds // ""),
        uploads: {status: $uploads_status, filesystem: $uploads_fs, free_bytes: ($uploads_free_bytes // "")},
        disk_free_bytes: ($disk_free_bytes // ""),
        work_dir: $work_dir
      }
    }'

  exit "$fail_code"
}

case "${1:---json}" in
  --json) main ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
