#!/usr/bin/env bash
# ============================================================
# write-enable provider（隔離環境向け・version 管理された executable）
#
# 復元対象の read-only 状態を解除し、アプリの write 経路を有効化する。
# 隔離環境では recovery target の control schema（ses_recovery_control）に
# cutover_state を書く。production では deployment の write 再開手順
# （HFP-03-PROD-004）を実装すること。
#
# 秘密は argv / 環境変数に載せない。TARGET_PASSWORD_FILE を 0600 option file
# 経由で渡し、MYSQL_PWD は拒否する（AC-008-01）。
#
# 環境変数: TARGET_HOST, TARGET_PORT, TARGET_USER, TARGET_PASSWORD_FILE,
# TARGET_DATABASE, PLAN_ID, TARGET_SSL_CAPATH（任意）, TARGET_TLS_MODE（任意）
# ============================================================
set -Eeuo pipefail
umask 077

require_env() {
  local v=$1
  [[ -n "${!v:-}" ]] || { echo "write-enable-local: $v が未設定です" >&2; exit 1; }
}

[[ -z "${MYSQL_PWD:-}" ]] || {
  echo "write-enable-local: MYSQL_PWD の使用は禁止です（TARGET_PASSWORD_FILE を使用してください）" >&2
  exit 18
}
unset MYSQL_PWD 2>/dev/null || true

require_env TARGET_HOST
require_env TARGET_USER
require_env TARGET_PASSWORD_FILE
require_env TARGET_DATABASE
require_env PLAN_ID
TARGET_PORT=${TARGET_PORT:-3306}
[[ -r "$TARGET_PASSWORD_FILE" ]] || {
  echo "write-enable-local: パスワード file が読めません: $TARGET_PASSWORD_FILE" >&2
  exit 1
}

optfile=$(mktemp)
trap 'rm -f "$optfile"' EXIT
{
  echo '[client]'
  echo "user=$TARGET_USER"
  echo "port=$TARGET_PORT"
  echo "password=$(head -n1 "$TARGET_PASSWORD_FILE")"
  echo "ssl-mode=${TARGET_TLS_MODE:-VERIFY_CA}"
  [[ -n "${TARGET_SSL_CAPATH:-}" ]] && echo "ssl-capath=$TARGET_SSL_CAPATH"
} > "$optfile"
chmod 600 "$optfile"

# --defaults-extra-file を argv 先頭に置く。-p$PW / MYSQL_PWD は使わない。
mysql --defaults-extra-file="$optfile" -h"$TARGET_HOST" \
  ses_recovery_control \
  -e "INSERT INTO cutover_state (plan_id, write_enabled_at) VALUES ('$PLAN_ID', NOW())
      ON DUPLICATE KEY UPDATE write_enabled_at=NOW();" > /dev/null
