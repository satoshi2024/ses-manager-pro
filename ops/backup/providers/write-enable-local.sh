#!/usr/bin/env bash
# ============================================================
# write-enable provider（隔離環境向け・version 管理された executable）
#
# 復元対象の read-only 状態を解除し、アプリの write 経路を有効化する。
# 隔離環境では recovery target の control schema（ses_recovery_control）に
# cutover_state を書く。production では deployment の write 再開手順
# （HFP-03-PROD-004）を実装すること。
#
# 環境変数: TARGET_HOST, TARGET_PORT, TARGET_USER, TARGET_PASSWORD,
# TARGET_DATABASE, PLAN_ID
# ============================================================
set -Eeuo pipefail

require_env() {
  local v=$1
  [[ -n "${!v:-}" ]] || { echo "write-enable-local: $v が未設定です" >&2; exit 1; }
}

require_env TARGET_HOST
require_env TARGET_USER
require_env TARGET_PASSWORD
require_env TARGET_DATABASE
require_env PLAN_ID
TARGET_PORT=${TARGET_PORT:-3306}

mysql -h"$TARGET_HOST" -P"$TARGET_PORT" -u"$TARGET_USER" -p"$TARGET_PASSWORD" \
  ses_recovery_control \
  -e "INSERT INTO cutover_state (plan_id, write_enabled_at) VALUES ('$PLAN_ID', NOW())
      ON DUPLICATE KEY UPDATE write_enabled_at=NOW();" > /dev/null 2>&1
