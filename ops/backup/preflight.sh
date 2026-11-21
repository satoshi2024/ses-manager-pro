#!/usr/bin/env bash
# ============================================================
# HFP-03-001 読み取り専用 preflight（環境契約検査）
# MySQL 8.4 LTS の client/server、source UUID、binlog、table engine、
# TLS、空き容量、uploads topology を機械判定し JSON を出力する。
# 読み取り専用であり、DB へ一切書込みを行わない。
#
# RB-W 8.4 対応: production の MySQL は 8.4 LTS だけ（2026-11-13 決定）。mysql・mysqlbinlog・mysqldump と server が
# すべて SUPPORTED_MYSQL_SERIES（8.4）であることを求める（patch は違ってよい）。server の変数は
# SHOW GLOBAL VARIABLES で読み（無い変数は行が出ないだけで、1 つの SELECT 全体が失敗しない）、判定に要る変数が
# 無ければその名前を挙げて失敗する。TLS は @@have_ssl（8.4 で残るかは CI で確認。使わない）ではなく、
# この接続の Ssl_cipher（session status）が空でないことで判定する。
#
# 使い方: preflight.sh [--json|--help]
#
# exit code:
#   0   全チェック PASS
#   10  mysql client / mysqlbinlog / mysqldump が不正（MariaDB / 8.4 以外）
#   11  server version が 8.4 以外、client/server の系列不整合、または判定に要る server の変数を取得できない
#   12  log_bin = OFF、または binlog_format の変数があり ROW でない（RB-W P3-6）
#   13  binlog checksum が OFF
#   14  対象 DB に非 InnoDB table がある
#   15  TLS が無効
#   16  UPLOADS_ROOT 指定だが pointer（UPLOADS_POINTER、既定 current）を解決できない、廃止した UPLOADS_DIR が残っている、
#       または UPLOADS_STAGING_PARENT 指定時に pointer の指す版と staging の親が同じ mount（st_dev）でない
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
. "$LIB_DIR/uploads-pointer.sh"

EXIT_CLIENT=10
EXIT_SERVER=11
EXIT_BINLOG=12
EXIT_CHECKSUM=13
EXIT_ENGINE=14
EXIT_TLS=15
EXIT_UPLOADS=16
EXIT_DISK=17
EXIT_MYSQL_PWD=18

# 対応する MySQL の系列（major.minor）
SUPPORTED_MYSQL_SERIES=8.4

# --version の出力から x.y.z を取り出す（「Ver 8.4.13」または旧形式の「Distrib 5.7.44」）。取れなければ空
mysql_tool_version() { # <--version の出力>
  if [[ "$1" =~ Ver[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$1" =~ Distrib[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
  return 0
}

# x.y.z（または x.y.z-suffix）の major.minor
mysql_series() { # <version>
  [[ "$1" =~ ^([0-9]+\.[0-9]+)\. ]] && printf '%s\n' "${BASH_REMATCH[1]}"
  return 0
}

usage() {
  cat <<'EOF'
Usage: preflight.sh [--json|--help]

環境契約検査を実行し、結果 JSON を標準出力へ出力する。
終了コード: 0=PASS, 10=client不正, 11=server version不整合, 12=log_bin OFF・binlog_format が ROW でない,
13=checksum OFF, 14=非InnoDB, 15=TLS無効, 16=uploads の pointer を解決できない・staging と同じ mount でない, 17=空き容量不足,
18=MYSQL_PWD使用検出, 1=env不足/接続失敗

環境変数: MYSQL_HOST, MYSQL_PORT, MYSQL_USER, MYSQL_PASSWORD_FILE,
MYSQL_DATABASE, BACKUP_WORK_DIR, UPLOADS_ROOT(任意), UPLOADS_POINTER(任意。既定 current),
UPLOADS_STAGING_PARENT(任意。UPLOADS_ROOT と同じ mount か確かめる), PREFLIGHT_MIN_FREE_BYTES
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
  local gtid_mode="" tls_cipher="" log_bin_basename="" binlog_expire=""
  local engines_out="" disk_free_bytes=""

  # --- client 検査（Oracle MySQL 8.4 のみ） ---
  local client_line=""
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    if client_line=$("$MYSQL_CLIENT_BIN" --version 2>&1); then
      case "$client_line" in
        *[Mm]ariaDB*)
          note_fail "$EXIT_CLIENT" "mysql client が MariaDB 系です: $client_line"
          ;;
        *)
          client_ver=$(mysql_tool_version "$client_line")
          if [[ "$(mysql_series "$client_ver")" != "$SUPPORTED_MYSQL_SERIES" ]]; then
            note_fail "$EXIT_CLIENT" "mysql client が MySQL $SUPPORTED_MYSQL_SERIES ではありません: $client_line"
            client_ver=""
          fi
          ;;
      esac
    else
      note_fail "$EXIT_CLIENT" "mysql client を実行できません: $MYSQL_CLIENT_BIN"
    fi
  fi

  # mysqlbinlog / mysqldump も同じ系列であること
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    local bl=""
    if bl=$("$MYSQLBINLOG_BIN" --version 2>&1); then
      case "$bl" in
        *[Mm]ariaDB*) note_fail "$EXIT_CLIENT" "mysqlbinlog が MariaDB 系です: $bl" ;;
        *) [[ "$(mysql_series "$(mysql_tool_version "$bl")")" == "$SUPPORTED_MYSQL_SERIES" ]] \
             || note_fail "$EXIT_CLIENT" "mysqlbinlog が MySQL $SUPPORTED_MYSQL_SERIES ではありません: $bl" ;;
      esac
    else
      note_fail "$EXIT_CLIENT" "mysqlbinlog を実行できません: $MYSQLBINLOG_BIN"
    fi
    local dl=""
    if dl=$("$MYSQLDUMP_BIN" --version 2>&1); then
      case "$dl" in
        *[Mm]ariaDB*) note_fail "$EXIT_CLIENT" "mysqldump が MariaDB 系です: $dl" ;;
        *) [[ "$(mysql_series "$(mysql_tool_version "$dl")")" == "$SUPPORTED_MYSQL_SERIES" ]] \
             || note_fail "$EXIT_CLIENT" "mysqldump が MySQL $SUPPORTED_MYSQL_SERIES ではありません: $dl" ;;
      esac
    else
      note_fail "$EXIT_CLIENT" "mysqldump を実行できません: $MYSQLDUMP_BIN"
    fi
  fi

  # --- server status 検査（読み取りのみ。RB-W 8.4 対応） ---
  # 変数ごとに有無を見るため SHOW GLOBAL VARIABLES で読む（1 つの SELECT @@a, @@b … は、どれか 1 つが無いだけで
  # 全体が失敗する）。判定に要る変数（REQUIRED）が無ければ名前を挙げて失敗し、記録だけに使う変数（binlog_format
  # （8.4 で非推奨）・binlog_transaction_compression・gtid_mode・log_bin_basename・binlog_expire_logs_seconds）は
  # 無ければ空で出力する。TLS はこの接続の Ssl_cipher で判定する。
  local -A server_vars=()
  local vars_out="" var_name var_value missing_vars=""
  local -a required_vars=(version server_uuid log_bin binlog_checksum Ssl_cipher)
  if [[ "$fail_code" -eq 0 || "$fail_code" -eq 18 ]]; then
    # shellcheck disable=SC2153  # MYSQL_HOST は require_env で検証済みの env 値
    if vars_out=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
        --execute "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('version', 'server_uuid', 'log_bin', 'binlog_format', 'binlog_checksum', 'binlog_transaction_compression', 'gtid_mode', 'log_bin_basename', 'binlog_expire_logs_seconds'); SHOW SESSION STATUS WHERE Variable_name = 'Ssl_cipher';" 2>&1); then
      while IFS=$'\t' read -r var_name var_value; do
        [[ -n "$var_name" ]] && server_vars[$var_name]=$var_value
      done <<< "$vars_out"
      for var_name in "${required_vars[@]}"; do
        [[ -n "${server_vars[$var_name]+set}" ]] || missing_vars+="${missing_vars:+, }$var_name"
      done
      if [[ -n "$missing_vars" ]]; then
        note_fail "$EXIT_SERVER" "server の変数を取得できません: $missing_vars（MySQL $SUPPORTED_MYSQL_SERIES に無い・権限不足の可能性）"
      fi
      server_ver=${server_vars[version]:-}
      server_uuid=${server_vars[server_uuid]:-}
      log_bin=${server_vars[log_bin]:-}
      binlog_format=${server_vars[binlog_format]:-}
      binlog_checksum=${server_vars[binlog_checksum]:-}
      binlog_compression=${server_vars[binlog_transaction_compression]:-}
      gtid_mode=${server_vars[gtid_mode]:-}
      log_bin_basename=${server_vars[log_bin_basename]:-}
      binlog_expire=${server_vars[binlog_expire_logs_seconds]:-}
      tls_cipher=${server_vars[Ssl_cipher]:-}
    else
      note_fail 1 "server status を取得できません（接続/TLS を確認してください）: $(printf '%s' "$vars_out" | common::redact)"
    fi
  fi

  if [[ -n "$server_ver" ]]; then
    [[ "$(mysql_series "$server_ver")" == "$SUPPORTED_MYSQL_SERIES" ]] \
      || note_fail "$EXIT_SERVER" "server version が MySQL $SUPPORTED_MYSQL_SERIES ではありません: $server_ver"
    if [[ -n "$client_ver" ]]; then
      [[ "$(mysql_series "$client_ver")" == "$(mysql_series "$server_ver")" ]] \
        || note_fail "$EXIT_SERVER" "client/server の major.minor が一致しません: client=$client_ver server=$server_ver"
    fi
  fi

  case "$log_bin" in
    1|ON) : ;;
    *) note_fail "$EXIT_BINLOG" "log_bin が OFF です" ;;
  esac

  case "$binlog_checksum" in
    ""|OFF|NONE) note_fail "$EXIT_CHECKSUM" "binlog checksum が無効です: ${binlog_checksum:-OFF}" ;;
  esac

  # binlog_format（RB-W P3-6）: 8.4 で非推奨だが変数は残る。DBA の前提は ROW。変数があり ROW でなければ失敗する
  # （変数そのものが無い版では判定しない。その場合も記録は空のまま）。
  if [[ -n "${server_vars[binlog_format]+set}" && "$binlog_format" != ROW ]]; then
    note_fail "$EXIT_BINLOG" "binlog_format が ROW ではありません: ${binlog_format:-空}"
  fi

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

  # --- TLS 検査（この接続の Ssl_cipher。空なら TLS で接続していない） ---
  if [[ -z "$tls_cipher" ]]; then
    note_fail "$EXIT_TLS" "TLS が無効です（この接続の Ssl_cipher が空です）"
  fi

  # --- uploads topology（任意。指定されたら存在チェック） ---
  # RB-W 追加修正 C: UPLOADS_ROOT を指定したら、pointer（UPLOADS_POINTER、既定 current）を provider と同じ規則
  # （lib/uploads-pointer.sh）で解決できることを確かめる。廃止した UPLOADS_DIR が残っていれば失敗する。
  # UPLOADS_STAGING_PARENT も指定されていれば、pointer の指す版の directory と staging の親が同じ mount（st_dev が同じ）で
  # あることを provider と同じ方法（stat -L -c %d）で確かめる。違う・取れない場合は失敗する（初回 backup の前に、mount を
  # 1 回にまとめていない配置を見つける。README「uploads の配置と mount」の SRE の確認方法）。
  local uploads_status="not-configured"
  local uploads_fs="" uploads_free="" uploads_pointer="" uploads_version="" pointer_out=""
  local uploads_same_fs="" uploads_dev="" staging_dev=""
  if [[ -n "${UPLOADS_DIR:-}" ]]; then
    uploads_status="deprecated-key"
    note_fail "$EXIT_UPLOADS" "UPLOADS_DIR は廃止しました（UPLOADS_ROOT と UPLOADS_POINTER を設定する。README「設定」）"
  elif [[ -n "${UPLOADS_ROOT:-}" ]]; then
    uploads_pointer=${UPLOADS_POINTER:-current}
    if pointer_out=$(uploads_pointer::resolve "$UPLOADS_ROOT" "$uploads_pointer" 2>&1); then
      uploads_status="ok"
      uploads_version=$pointer_out
      uploads_fs=$(stat -f -c %T "$UPLOADS_ROOT/$uploads_version" 2>/dev/null || echo unknown)
      uploads_free=$(df -P -B1 "$UPLOADS_ROOT/$uploads_version" | awk 'NR==2 {print $4}')
      if [[ -n "${UPLOADS_STAGING_PARENT:-}" ]]; then
        uploads_dev=$(stat -L -c %d "$UPLOADS_ROOT/$uploads_version" 2>/dev/null) || uploads_dev=""
        staging_dev=$(stat -L -c %d "$UPLOADS_STAGING_PARENT" 2>/dev/null) || staging_dev=""
        if [[ ! "$uploads_dev" =~ ^[0-9]+$ || ! "$staging_dev" =~ ^[0-9]+$ ]]; then
          uploads_same_fs=false
          note_fail "$EXIT_UPLOADS" "uploads の版と staging の親（UPLOADS_STAGING_PARENT）の filesystem（st_dev）を取得できません（uploads=${uploads_dev:-なし} staging=${staging_dev:-なし}）"
        elif [[ "$uploads_dev" != "$staging_dev" ]]; then
          uploads_same_fs=false
          note_fail "$EXIT_UPLOADS" "uploads の版と staging の親（UPLOADS_STAGING_PARENT）が同じ mount ではありません（st_dev: uploads=$uploads_dev staging=$staging_dev。mount を 1 回にまとめる）"
        else
          uploads_same_fs=true
        fi
      fi
    else
      uploads_status="pointer-unresolved"
      note_fail "$EXIT_UPLOADS" "$pointer_out"
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
  [[ -n "$client_ver" && "$(mysql_series "$client_ver")" == "$SUPPORTED_MYSQL_SERIES" ]] && client_ok=true
  [[ -n "$server_ver" && "$(mysql_series "$server_ver")" == "$SUPPORTED_MYSQL_SERIES" ]] && server_ok=true
  [[ "$log_bin" == 1 || "$log_bin" == "ON" ]] && binlog_on=true
  [[ -n "$tls_cipher" ]] && tls_ok=true
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
    --arg uploads_pointer "$uploads_pointer" \
    --arg uploads_version "$uploads_version" \
    --arg uploads_same_fs "$uploads_same_fs" \
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
        uploads: {status: $uploads_status, filesystem: $uploads_fs, free_bytes: ($uploads_free_bytes // ""),
                  pointer: $uploads_pointer, version: $uploads_version,
                  staging_same_filesystem: (if $uploads_same_fs == "" then null else ($uploads_same_fs == "true") end)},
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
