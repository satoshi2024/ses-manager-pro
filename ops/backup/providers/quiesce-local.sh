#!/usr/bin/env bash
# ============================================================
# 書込み静止 provider（隔離/ローカル環境向け・version 管理された executable）
#
# contract:
#   quiesce-local.sh acquire <state_dir>
#     - 全 app replica の heartbeat が fresh であること（REPLICA_HEARTBEAT_DIR）
#     - scheduler の停止 ack が得られること（SCHEDULER_ACK_DIR）
#     - source MySQL の DDL 凍結 lock（GET_LOCK）を取得できること
#     を bounded deadline 内に確認し、quiesce.json を書く。
#   quiesce-local.sh release <state_dir>
#     - DDL lock を解放し、released_at を quiesce.json に追記する。
#
# replica 側 protocol:
#   - 各 replica は <REPLICA_HEARTBEAT_DIR>/<name>.heartbeat を定期 touch する
#     （QUIESCE_STALE_SECONDS 内に fresh であること）。
#   - provider が <name>.quiesce-requested を書き、replica が
#     <name>.quiesced を touch して ack する。
# scheduler 側 protocol:
#   - provider が SCHEDULER_ACK_DIR/scheduler.stop-request を書き、
#     scheduler が scheduler.stopped を touch して ack する。
# DDL 凍結:
#   - GET_LOCK('ses_backup_ddl_freeze', timeout) を source へ取得する。
#     app の deploy/DDL は同じ lock 名を尊重する規約とする。
# 任意の bash -c 実行は行わない（BL-015 対応）。
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/../lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"

DDL_LOCK_NAME=ses_backup_ddl_freeze

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}
MYSQLDUMP_BIN=${MYSQLDUMP_BIN:-mysqldump}

quiesce_local::deadline_left() { # deadline_epoch -> seconds (min 0)
  local now
  now=$(date +%s)
  local left=$(( $1 - now ))
  (( left < 0 )) && left=0
  echo "$left"
}

quiesce_local::check_replicas() { # state_dir deadline_epoch
  local state_dir=$1 deadline=$2
  common::require_env REPLICA_HEARTBEAT_DIR
  [[ -d "$REPLICA_HEARTBEAT_DIR" ]] || { echo "quiesce-local: REPLICA_HEARTBEAT_DIR がありません: $REPLICA_HEARTBEAT_DIR" >&2; return 1; }

  local stale_seconds=${QUIESCE_STALE_SECONDS:-60}
  common::is_int "$stale_seconds" || { echo "quiesce-local: QUIESCE_STALE_SECONDS が不正です" >&2; return 1; }

  local heartbeats=()
  # shellcheck disable=SC2044
  for hb in "$REPLICA_HEARTBEAT_DIR"/*.heartbeat; do
    [[ -e "$hb" ]] || continue
    heartbeats+=("$hb")
  done
  if (( ${#heartbeats[@]} == 0 )); then
    echo "quiesce-local: heartbeat を持つ replica がありません" >&2
    return 1
  fi

  local stale_list=""
  local now=0
  now=$(date +%s)
  local hb_name=""
  local hb_age=0
  for hb in "${heartbeats[@]}"; do
    hb_name=$(basename "$hb" .heartbeat)
    hb_age=$(( now - $(stat -c %Y "$hb" 2>/dev/null || echo 0) ))
    if (( hb_age > stale_seconds )); then
      stale_list="$stale_list $hb_name(${hb_age}s)"
    fi
  done

  if [[ -n "$stale_list" ]]; then
    echo "quiesce-local: 静止していない replica:$stale_list" >&2
    return 1
  fi

  # ack を要求
  for hb in "${heartbeats[@]}"; do
    hb_name=$(basename "$hb" .heartbeat)
    : > "$REPLICA_HEARTBEAT_DIR/$hb_name.quiesce-requested"
  done

  # 全 ack を deadline まで待つ
  local pending=""
  while :; do
    pending=""
    for hb in "${heartbeats[@]}"; do
      hb_name=$(basename "$hb" .heartbeat)
      if [[ ! -f "$REPLICA_HEARTBEAT_DIR/$hb_name.quiesced" ]]; then
        pending="$pending $hb_name"
      fi
    done
    [[ -n "$pending" ]] || break
    if (( $(quiesce_local::deadline_left "$deadline") <= 0 )); then
      echo "quiesce-local: ack 待ちで deadline 超過: $pending" >&2
      return 1
    fi
    sleep 1
  done

  # ack が stale でないこと（取得直後）
  local replicas=""
  for hb in "${heartbeats[@]}"; do
    hb_name=$(basename "$hb" .heartbeat)
    local ack_time
    ack_time=$(stat -c %Y "$REPLICA_HEARTBEAT_DIR/$hb_name.quiesced" 2>/dev/null || echo 0)
    if (( ack_time < $(date +%s) - stale_seconds )); then
      echo "quiesce-local: ack が古い replica: $hb_name" >&2
      return 1
    fi
  done
  return 0
}

quiesce_local::check_scheduler() { # state_dir deadline_epoch
  local state_dir=$1 deadline=$2
  if [[ -z "${SCHEDULER_ACK_DIR:-}" ]]; then
    echo "quiesce-local: SCHEDULER_ACK_DIR が未設定です（scheduler 停止を確認できません）" >&2
    return 1
  fi
  [[ -d "$SCHEDULER_ACK_DIR" ]] || { echo "quiesce-local: SCHEDULER_ACK_DIR がありません: $SCHEDULER_ACK_DIR" >&2; return 1; }
  : > "$SCHEDULER_ACK_DIR/scheduler.stop-request"
  local stale_seconds=${QUIESCE_STALE_SECONDS:-60}
  while :; do
    if [[ -f "$SCHEDULER_ACK_DIR/scheduler.stopped" ]]; then
      local ack_time
      ack_time=$(stat -c %Y "$SCHEDULER_ACK_DIR/scheduler.stopped" 2>/dev/null || echo 0)
      if (( ack_time >= $(date +%s) - stale_seconds )); then
        return 0
      fi
      echo "quiesce-local: scheduler の ack が古いです" >&2
      return 1
    fi
    if (( $(quiesce_local::deadline_left "$deadline") <= 0 )); then
      echo "quiesce-local: scheduler ack 待ちで deadline 超過" >&2
      return 1
    fi
    sleep 1
  done
}

# GET_LOCK は接続単位の lock のため、session（mysql process）を生かしたまま
# 保持する。server 側で長時間 SLEEP すると client kill 時に lock が解放され
# ないため、stdin から DO 0; を流し続け、接続を idle 状態で維持する
# （client kill → server が socket EOF を検知 → 即時解放される）。
quiesce_local::ddl_lock_acquire() { # state_dir timeout_seconds
  local state_dir=$1 timeout=${2:-30}
  if ! mysql_options::init; then
    echo "quiesce-local: mysql option file を作成できませんでした" >&2
    return 1
  fi
  local tmpout=""
  tmpout=$(mktemp)
  {
    printf "SELECT GET_LOCK('%s', %s);\n" "$DDL_LOCK_NAME" "$timeout"
    while :; do printf 'DO 0;\n'; sleep 1; done
  } | "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B --unbuffered \
        > "$tmpout" 2>&1 &
  DDL_SESSION_PID=$!

  local first=""
  for _ in $(seq 1 40); do
    if [[ -s "$tmpout" ]]; then
      first=$(head -n1 "$tmpout")
      break
    fi
    if ! kill -0 "$DDL_SESSION_PID" 2>/dev/null; then
      echo "quiesce-local: DDL lock session が異常終了しました: $(common::redact <<< "$(cat "$tmpout")")" >&2
      rm -f "$tmpout"
      return 1
    fi
    sleep 0.5
  done

  if [[ "$first" != "1" ]]; then
    echo "quiesce-local: DDL lock（GET_LOCK）を取得できませんでした: ${first:-<no response>}" >&2
    kill "$DDL_SESSION_PID" 2>/dev/null || true
    rm -f "$tmpout"
    return 1
  fi
  # session が生きていること（lock 保持の前提）
  if ! kill -0 "$DDL_SESSION_PID" 2>/dev/null; then
    echo "quiesce-local: DDL lock session が即終了しました（lock 保持不能）" >&2
    rm -f "$tmpout"
    return 1
  fi
  rm -f "$tmpout"
  printf '%s\n' "$DDL_SESSION_PID" > "$state_dir/ddl-session.pid"
  chmod 600 "$state_dir/ddl-session.pid"
  DDL_LOCK_HELD=1
  return 0
}

quiesce_local::ddl_lock_release() { # state_dir
  local state_dir=$1
  local pidfile="$state_dir/ddl-session.pid"
  if [[ ! -f "$pidfile" ]]; then
    DDL_LOCK_HELD=0
    return 0
  fi
  local pid=""
  pid=$(cat "$pidfile" 2>/dev/null || echo "")
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
  fi
  rm -f "$pidfile"
  DDL_LOCK_HELD=0
  return 0
}

quiesce_local::acquire() {
  local state_dir=$1
  mkdir -p "$state_dir"
  local started
  started=$(common::now_utc)
  local deadline_seconds=${QUIESCE_DEADLINE_SECONDS:-120}
  common::is_int "$deadline_seconds" || { echo "quiesce-local: QUIESCE_DEADLINE_SECONDS が不正です" >&2; return 1; }
  local deadline=$(( $(date +%s) + deadline_seconds ))

  quiesce_local::check_scheduler "$state_dir" "$deadline" || return 1
  quiesce_local::check_replicas "$state_dir" "$deadline" || return 1
  quiesce_local::ddl_lock_acquire "$state_dir" "$deadline_seconds" || return 1

  local replicas_json="[]"
  local names=""
  for hb in "$REPLICA_HEARTBEAT_DIR"/*.heartbeat; do
    [[ -e "$hb" ]] || continue
    local name
    name=$(basename "$hb" .heartbeat)
    names="$names $name"
  done
  local arr="[]"
  for n in $names; do
    arr=$(printf '%s' "$arr" | jq -c --arg n "$n" '. + [{name: $n, quiesced: true}]')
  done

  jq -n \
    --arg provider "quiesce-local" \
    --arg started_at_utc "$started" \
    --argjson replicas "$arr" \
    --arg ddl_lock "$DDL_LOCK_NAME" \
    --arg state_dir "$state_dir" \
    '{provider: $provider, started_at_utc: $started_at_utc, released_at_utc: null,
      replicas: $replicas, ddl_lock: $ddl_lock, state_dir: $state_dir}' \
    > "$state_dir/quiesce.json"
  chmod 600 "$state_dir/quiesce.json"
  return 0
}

quiesce_local::release() {
  local state_dir=$1
  local rc=0
  quiesce_local::ddl_lock_release "$state_dir" || rc=1
  local released
  released=$(common::now_utc)
  if [[ -f "$state_dir/quiesce.json" ]]; then
    jq --arg t "$released" '.released_at_utc = $t' "$state_dir/quiesce.json" > "$state_dir/quiesce.json.tmp"
    mv "$state_dir/quiesce.json.tmp" "$state_dir/quiesce.json"
    chmod 600 "$state_dir/quiesce.json"
  fi
  # ack をリセット（次回静止のため）
  rm -f "$REPLICA_HEARTBEAT_DIR"/*.quiesce-requested "$REPLICA_HEARTBEAT_DIR"/*.quiesced \
     "$SCHEDULER_ACK_DIR/scheduler.stop-request" "$SCHEDULER_ACK_DIR/scheduler.stopped" 2>/dev/null || true
  return "$rc"
}

cmd=${1:-}
state_dir=${2:-}
case "$cmd" in
  acquire)
    [[ -n "$state_dir" ]] || { echo "quiesce-local: state_dir がありません" >&2; exit 2; }
    # 注意: DDL lock は session を生かしたまま保持するため、ここで EXIT trap を
    # 張ると provider 終了時に lock が解放されてしまう。lock の解放は
    # release 呼び出し（state_dir/ddl-session.pid 経由）でのみ行う。
    quiesce_local::acquire "$state_dir"
    ;;
  release)
    [[ -n "$state_dir" ]] || { echo "quiesce-local: state_dir がありません" >&2; exit 2; }
    quiesce_local::release "$state_dir"
    ;;
  *)
    echo "Usage: quiesce-local.sh acquire|release <state_dir>" >&2
    exit 2
    ;;
esac
