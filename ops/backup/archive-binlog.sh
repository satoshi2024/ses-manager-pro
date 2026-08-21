#!/usr/bin/env bash
# ============================================================
# HFP-03-004 継続 binlog archive（BL-007/008 対応）
#
# - 明示 initial log（state の last file または最初の full coordinate）から
#   mysqlbinlog --read-from-remote-server --raw --stop-never を開始する。
# - source ごとに一意な --connection-server-id を使う（state に記録）。
# - TLS は option file 経由（VERIFY_CA/VERIFY_IDENTITY + capath）。
# - source UUID が state と違う場合は自動継続しない。
# - raw file は BINLOG_RAW_DIR へ（restic 対象外。snapshot-binlog が closed
#   検証後に immutable 化する）。
# - --once: bounded catch-up（state/full coordinate から現時点まで）。
#   restart 時は state の last complete file の次から再開し、既存 file を
#   黙って上書きしない（不完全 file は削除して取り直す）。
#
# usage: archive-binlog.sh [--once|--stop-never|--help]
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"
. "$LIB_DIR/binlog.sh"

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}

BINLOG_RAW_DIR=${BINLOG_RAW_DIR:?BINLOG_RAW_DIR is required}
BINLOG_STATE=${BINLOG_STATE:?BINLOG_STATE is required}

# 一意 connection-server-id（BINLOG_SERVER_ID か hostname hash 由来）
binlog_archive::server_id() {
  if [[ -n "${BINLOG_SERVER_ID:-}" ]]; then
    common::is_int "$BINLOG_SERVER_ID" || { echo "archive-binlog: BINLOG_SERVER_ID が不正です" >&2; return 1; }
    printf '%s' "$BINLOG_SERVER_ID"
    return 0
  fi
  local h
  h=$(hostname 2>/dev/null || echo unknown)
  printf '%d' $(( 50000 + 0x$(printf '%s' "$h" | sha256sum | cut -c1-4) % 10000 ))
}

binlog_archive::source_uuid() {
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SELECT @@server_uuid;" 2>/dev/null
}

binlog_archive::load_state() {
  STATE_LAST_FILE=""
  STATE_SERVER_ID=""
  STATE_UUID=""
  if [[ -f "$BINLOG_STATE" ]]; then
    STATE_LAST_FILE=$(jq -r '.last_file // empty' "$BINLOG_STATE" 2>/dev/null || true)
    STATE_SERVER_ID=$(jq -r '.connection_server_id // empty' "$BINLOG_STATE" 2>/dev/null || true)
    STATE_UUID=$(jq -r '.source_server_uuid // empty' "$BINLOG_STATE" 2>/dev/null || true)
  fi
}

binlog_archive::save_state() { # last_file
  local last=$1
  jq -n \
    --arg last_file "$last" \
    --arg connection_server_id "$SERVER_ID" \
    --arg source_server_uuid "$SOURCE_UUID" \
    --arg updated_at_utc "$(common::now_utc)" \
    '{last_file: $last_file, connection_server_id: $connection_server_id,
      source_server_uuid: $source_server_uuid, updated_at_utc: $updated_at_utc}' \
    > "$BINLOG_STATE.tmp" && mv "$BINLOG_STATE.tmp" "$BINLOG_STATE"
  chmod 600 "$BINLOG_STATE"
}

# source の現在の binlog 一覧（最終行 = 最新）
binlog_archive::source_logs() {
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SHOW BINARY LOGS;" 2>/dev/null
}

# 開始 file の決定: state の last file の次 / full coordinate / 現最新
# 重要: source current（active）を complete 扱いして次へ飛ばさない。
# last_file == source current なら同一 file から coordinate 継続する。
binlog_archive::resolve_start() {
  local logs
  logs=$(binlog_archive::source_logs) || return 1
  local current
  current=$(printf '%s\n' "$logs" | awk 'END{print $1}')
  [[ -n "$current" ]] || { echo "archive-binlog: source に binlog がありません" >&2; return 1; }

  local start=""
  if [[ -n "$STATE_LAST_FILE" ]]; then
    local s e
    s=$(binlog::suffix "$STATE_LAST_FILE")
    e=$(binlog::suffix "$current")
    if [[ -n "$s" && -n "$e" && 10#$s -gt 10#$e ]]; then
      echo "archive-binlog: state の last file が source より新しい: $STATE_LAST_FILE" >&2
      return 1
    fi
    if [[ -f "$BINLOG_RAW_DIR/$STATE_LAST_FILE" ]]; then
      local want
      want=$(printf '%s\n' "$logs" | awk -v f="$STATE_LAST_FILE" '$1 == f {print $2; exit}')
      local have
      have=$(stat -c %s "$BINLOG_RAW_DIR/$STATE_LAST_FILE" 2>/dev/null || echo 0)
      if [[ -n "$want" && "$have" -lt "$want" ]]; then
        echo "archive-binlog: 不完全な前回 file を取り直します: $STATE_LAST_FILE" >&2
        rm -f "$BINLOG_RAW_DIR/$STATE_LAST_FILE"
        start=$STATE_LAST_FILE
      elif [[ "$STATE_LAST_FILE" == "$current" ]]; then
        echo "archive-binlog: last_file は source current（active）のため同 file から継続: $STATE_LAST_FILE" >&2
        start=$STATE_LAST_FILE
      else
        start=$(binlog::next_file "$STATE_LAST_FILE") || return 1
      fi
    else
      start=$STATE_LAST_FILE
    fi
  else
    if [[ -z "${FULL_COORDINATE_FILE:-}" ]]; then
      echo "archive-binlog: 初回起動には FULL_COORDINATE_FILE が必要です（backup-full が書き込みます）" >&2
      return 1
    fi
    if [[ ! -f "$FULL_COORDINATE_FILE" ]]; then
      echo "archive-binlog: FULL_COORDINATE_FILE がありません: $FULL_COORDINATE_FILE（backup-full を先に実行してください）" >&2
      return 1
    fi
    local coord
    coord=$(head -n1 "$FULL_COORDINATE_FILE")
    [[ -n "$coord" ]] || {
      echo "archive-binlog: FULL_COORDINATE_FILE が空です: $FULL_COORDINATE_FILE" >&2
      return 1
    }
    local cs
    cs=$(binlog::suffix "$coord")
    local e2
    e2=$(binlog::suffix "$current")
    if [[ -n "$cs" && 10#$cs -le 10#$e2 ]]; then
      start=$coord
    else
      echo "archive-binlog: FULL_COORDINATE_FILE が source より新しい: $coord" >&2
      return 1
    fi
  fi
  echo "$start"
}

# mysqlbinlog を実行（--stop-never / --once）
binlog_archive::run() { # start_file mode
  local start=$1 mode=$2
  local extra=()
  [[ "$mode" == "once" ]] && extra=()
  [[ "$mode" == "stop-never" ]] && extra=(--stop-never)

  "$MYSQLBINLOG_BIN" "${MYSQL_OPT_ARGS[@]}" \
    --read-from-remote-server --raw "${extra[@]}" \
    --connection-server-id="$SERVER_ID" \
    --verify-binlog-checksum \
    --host="$MYSQL_HOST" --port="$MYSQL_PORT" \
    --result-file="$BINLOG_RAW_DIR/" \
    "$start"
}

# 起動時に raw の不完全 file（source size 未達）を削除して取り直し対象にする
binlog_archive::prune_incomplete_raw() {
  local logs
  logs=$(binlog_archive::source_logs) || return 1
  local raw_file=""
  while IFS= read -r -d '' raw_file; do
    local rel
    rel=$(basename "$raw_file")
    local want
    want=$(printf '%s\n' "$logs" | awk -v f="$rel" '$1 == f {print $2; exit}')
    [[ -n "$want" ]] || continue
    local have
    have=$(stat -c %s "$raw_file" 2>/dev/null || echo 0)
    if (( have < want )); then
      echo "archive-binlog: 不完全 file を取り直し対象にします: $rel (have=$have want=$want)" >&2
      rm -f "$raw_file"
    fi
  done < <(find "$BINLOG_RAW_DIR" -maxdepth 1 -type f -print0 2>/dev/null)
  return 0
}

main() {
  common::require_env MYSQL_HOST
  common::require_env MYSQL_USER
  common::require_env MYSQL_PASSWORD_FILE
  MYSQL_PORT=${MYSQL_PORT:-3306}

  if ! mysql_options::init; then
    common::fail "mysql option file を作成できません（接続設定を確認してください）"
  fi

  mkdir -p "$BINLOG_RAW_DIR"
  SOURCE_UUID=$(binlog_archive::source_uuid) || common::fail "source UUID を取得できません"
  [[ -n "$SOURCE_UUID" ]] || common::fail "source UUID が空です"

  binlog_archive::load_state
  if [[ -n "$STATE_UUID" && "$STATE_UUID" != "$SOURCE_UUID" ]]; then
    common::fail "source UUID が state と一致しません（別 lineage への自動継続は禁止）"
  fi
  binlog_archive::prune_incomplete_raw || common::fail "raw の不完全 file 検査に失敗しました"
  SERVER_ID=$(binlog_archive::server_id) || common::fail "connection-server-id を決定できません"
  if [[ -n "$STATE_SERVER_ID" && "$STATE_SERVER_ID" != "$SERVER_ID" ]]; then
    common::fail "connection-server-id が state と異なります（重複 archive の可能性）"
  fi

  local start=""
  start=$(binlog_archive::resolve_start) || common::fail "開始 file を決定できません"
  echo "archive-binlog: start=$start server_id=$SERVER_ID mode=$MODE" >&2

  local rc=0
  # heartbeat（監視用: 30 秒ごとに touch。mysqlbinlog が idle でも生存が分かる）
  (
    while :; do
      touch "$BINLOG_STATE.heartbeat" 2>/dev/null || true
      sleep 30
    done
  ) &
  local hb_pid=$!
  if ! binlog_archive::run "$start" "$MODE"; then
    rc=$?
    echo "archive-binlog: mysqlbinlog が終了しました (rc=$rc)" >&2
  fi
  kill "$hb_pid" 2>/dev/null || true
  touch "$BINLOG_STATE.heartbeat" 2>/dev/null || true
  # state には実際に raw へ落ちた最後の file を記録する（source の current ではない）
  local last_downloaded=""
  last_downloaded=$(find "$BINLOG_RAW_DIR" -maxdepth 1 -type f -printf '%f\n' 2>/dev/null \
    | grep -E '\.[0-9]+$' | sort | tail -n1)
  binlog_archive::save_state "${last_downloaded:-$start}" 2>/dev/null || true
  exit "$rc"
}

MODE=${1:-stop-never}
case "$MODE" in
  --stop-never|stop-never) MODE=stop-never ;;
  --once|once) MODE=once ;;
  --help|-h) cat <<'EOF'
Usage: archive-binlog.sh [--stop-never|--once|--help]
環境変数: BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, MYSQL_HOST, MYSQL_USER,
MYSQL_PASSWORD_FILE, BINLOG_RAW_DIR, BINLOG_STATE, BINLOG_SERVER_ID(任意),
FULL_COORDINATE_FILE(初回起動に必須。backup-full が書き出す coordinate file のパス)
EOF
    exit 0 ;;
  *) echo "Usage: archive-binlog.sh [--stop-never|--once|--help]" >&2; exit 2 ;;
esac
main
