#!/usr/bin/env bash
# ============================================================
# MySQL 接続秘密の option file 管理（HFP-03-001 / RQ-008 / BL-012）
# - MYSQL_PWD は使わない。mode 0600 の一時 option file へ書き、
#   --defaults-extra-file を argv の先頭に置いて接続する。
# - option file は trap で必ず削除する。
# - TLS は fail-closed: MYSQL_SSL_CAPATH（hashed CA dir）または
#   MYSQL_SSL_CA のどちらかが無ければ接続できない。
#   2026-08-14 実測: MySQL 8.0.46 client は --ssl-ca + VERIFY_* で
#   "SSL_CTX_set_default_verify_paths failed" となり使用不能。
#   hashed ssl-capath では VERIFY_CA/VERIFY_IDENTITY が機能する
#   （ID 照合には server cert の CN/SAN が host 名と一致する必要がある）。
#   したがって CA は hashed capath で提供するのを推奨とし、
#   ssl-ca は MYSQL_SSL_CAPATH 未指定時のみのフォールバックとする。
# ============================================================

MYSQL_OPTFILE=""
# shellcheck disable=SC2034  # 他 script が source して参照する共有配列
MYSQL_OPT_ARGS=()

_mysql_options::cleanup() {
  [[ -n "$MYSQL_OPTFILE" && -f "$MYSQL_OPTFILE" ]] && rm -f "$MYSQL_OPTFILE"
}

# 事前チェック: MYSQL_PWD の使用は禁止、TLS 設定は必須
mysql_options::init() {
  # ACC-OPS-P2-001 fail-closed: 依存 lib（common.sh）が未 source だと common::trap_add が
  # 未定義になり、option file cleanup trap を連結できない。従来はこの状態でも
  # 「command not found を出しつつ末尾の return 0 で成功扱い」になる false-green だった。
  # ここで明示的に検出し非 0 で返すことで、依存未ロードを必ず失敗として顕在化させる。
  # （option file はまだ作成していないため秘密の残置も無い）
  if ! declare -F common::trap_add > /dev/null 2>&1; then
    echo "mysql-options: common.sh が未ロードです（common::trap_add が未定義）。先に common.sh を source してください" >&2
    return 19
  fi
  [[ -z "${MYSQL_PWD:-}" ]] || {
    echo "mysql-options: MYSQL_PWD の使用は禁止です（MYSQL_PASSWORD_FILE を使用してください）" >&2
    return 18
  }
  [[ -n "${MYSQL_PASSWORD_FILE:-}" ]] || { echo "mysql-options: MYSQL_PASSWORD_FILE が未設定です" >&2; return 1; }
  [[ -n "${MYSQL_USER:-}" ]] || { echo "mysql-options: MYSQL_USER が未設定です" >&2; return 1; }
  [[ -r "$MYSQL_PASSWORD_FILE" ]] || { echo "mysql-options: パスワード file が読めません: $MYSQL_PASSWORD_FILE" >&2; return 1; }
  [[ -n "${MYSQL_SSL_CAPATH:-}" || -n "${MYSQL_SSL_CA:-}" ]] || {
    echo "mysql-options: TLS 設定がありません（MYSQL_SSL_CAPATH または MYSQL_SSL_CA を指定してください）" >&2
    return 1
  }
  if [[ -n "${MYSQL_SSL_CAPATH:-}" && ! -d "$MYSQL_SSL_CAPATH" ]]; then
    echo "mysql-options: MYSQL_SSL_CAPATH が存在しません: $MYSQL_SSL_CAPATH" >&2
    return 1
  fi
  if [[ -n "${MYSQL_SSL_CA:-}" && ! -r "$MYSQL_SSL_CA" ]]; then
    echo "mysql-options: MYSQL_SSL_CA が読めません: $MYSQL_SSL_CA" >&2
    return 1
  fi

  local tmpdir=${TMPDIR:-/tmp}
  mkdir -p "$tmpdir"
  MYSQL_OPTFILE=$(mktemp "$tmpdir/ses-backup-mysql.XXXXXX")

  local pw=""
  IFS= read -r pw < "$MYSQL_PASSWORD_FILE" || true

  local tls_mode=${MYSQL_TLS_MODE:-VERIFY_CA}
  case "$tls_mode" in
    VERIFY_IDENTITY|VERIFY_CA) : ;;
    *) tls_mode=VERIFY_CA ;;  # VERIFY 系以外へ落とさない
  esac

  {
    echo '[client]'
    echo "user=$MYSQL_USER"
    [[ -n "${MYSQL_PORT:-}" ]] && echo "port=$MYSQL_PORT"
    echo "password=$pw"
    echo "ssl-mode=$tls_mode"
    if [[ -n "${MYSQL_SSL_CAPATH:-}" ]]; then
      echo "ssl-capath=$MYSQL_SSL_CAPATH"
    else
      echo "ssl-ca=$MYSQL_SSL_CA"
    fi
  } > "$MYSQL_OPTFILE"
  chmod 600 "$MYSQL_OPTFILE"

  # shellcheck disable=SC2034  # 他 script が source して参照する共有配列
  MYSQL_OPT_ARGS=(--defaults-extra-file="$MYSQL_OPTFILE")
  # R2 P2-04: 呼び出し元の trap に上書きされないよう連結する
  common::trap_add _mysql_options::cleanup
  return 0
}
