#!/usr/bin/env bash
# ============================================================
# CI の backup integration の静止応答（RB-QUIESCE BWQ-012。.kiro/specs/backup-write-quiesce/design.md §16）
#
# docker-compose.integration.yml の responder service（tool image）で動く、app の静止応答の代わりの shell の loop。
# 試験の fixture で、本番では使わない。app（com.ses.backup.quiesce.BackupQuiesceResponder と
# JdbcBackupQuiesceSignalRepository）と同じ信号表の SQL を、専用の合成 user で mysql client から実行する:
#   instance の登録（STARTING。db_client_host は USER() の host 部、db_connection_id は CONNECTION_ID()）→
#   app の停止を確かめる保持（APP_STOPPED）が有効なら起動を中止して STOPPED を書く → RUNNING・OPEN の heartbeat →
#   有効な要求の読取り（1 秒ごと）→ 排水（業務の書込みを持たないので直ちに終わる）→ 接続元の観測（同じ account の
#   PROCESSLIST。port を外して host=件数）→ QUIESCED の ack（hold_until は要求の LEAST(lease の期限, 登録 + 最大保持時間)）→
#   要求に従う（hold 期限の延長、verify の要求の後の観測し直し、解除・中止・lease の期限切れ・最大保持時間で RESUMED）→
#   stop の file で STOPPED を書いて終わる。
# 時刻は全て UTC_TIMESTAMP(6) で書き・比べる（§3.10）。session の time_zone は app の JVM（Asia/Tokyo）と同じ +09:00 にし、
# 判定が session の time_zone によらないことを integration でも確かめる。
#
# 制御の file（RESPONDER_DIR の下。integration-pitr.sh が置く）:
#   client.cnf        接続の option file（[mysql] の host・port・user・password・database。専用の合成 user）
#   start             置かれるまで DB に接続しない（integration-pitr.sh が user を作り、最初の full を取った後に置く）
#   freeze            置かれている間は DB に何もしない（応答の止まった app。heartbeat も止まる）
#   freeze-after-ack  次に QUIESCED を書いた直後に freeze を置く（静止の途中で応答の止まった app の負例）
#   stop              STOPPED を書いて終わる
# 状態の file: state（WAITING / RUNNING / FROZEN / ABORTED_STARTUP / STOPPED）・instance-id。log は標準出力（docker logs）
# に出し、パスワードを出さない。
# 環境変数: RESPONDER_DIR（既定 /work/responder）、RESPONDER_TICK_SECONDS（既定 1）、MYSQL_CLIENT_BIN（既定 mysql）
# ============================================================
set -uo pipefail

RESPONDER_DIR=${RESPONDER_DIR:-/work/responder}
TICK=${RESPONDER_TICK_SECONDS:-1}
MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
CNF="$RESPONDER_DIR/client.cnf"

# app の既定と同じ値（BackupQuiesceProperties。provider の閾値の式 §5.2 を満たす）
PROTOCOL_VERSION=1
POLL_MS=1000
HEARTBEAT_MS=5000
FENCE_MS=20000
MAX_HOLD_CHECKPOINT_SECONDS=300
MAX_HOLD_FULL_SECONDS=3600
HEARTBEAT_EVERY=5
OBSERVE_EVERY=5
OBSERVED_HOSTS_MAX_LENGTH=1024
DATETIME_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}$'

INSTANCE_ID=""
STATUS=STARTING
GATE=UNARMED
LAST_BEAT=-1000000
# 静止の作業（要求ごと）。STEP: IDLE / QUIESCED / RESUMING / STOPPED_HOLD
STEP=IDLE
W_ID=""
W_WRITTEN_HOLD=""
W_PENDING_HOLD=""
W_HOLD_CAPPED=0
W_LAST_VERIFY="-"
W_OBSERVE_DUE=0
W_NEXT_OBSERVE=0
W_LOCAL_DEADLINE=0
W_RESUME_REASON=""
HANDLED=" "

log() { printf '%s [responder] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

set_state() {
  printf '%s\n' "$1" > "$RESPONDER_DIR/state.tmp" && mv -f "$RESPONDER_DIR/state.tmp" "$RESPONDER_DIR/state"
}

# SQL を 1 つの接続で実行する（結果は tab 区切り・見出し無し）。1 行目の印は harness-selftest-test.sh の fake の mysql が見分ける
sql() { # <名前> <SQL>
  "$MYSQL_CLIENT_BIN" --defaults-file="$CNF" -N -B --execute "-- responder:$1
SET SESSION time_zone = '+09:00';
SET SESSION innodb_lock_wait_timeout = 5;
$2"
}

MAX_HOLD_SQL="CASE WHEN job_kind = 'FULL' THEN $MAX_HOLD_FULL_SECONDS ELSE $MAX_HOLD_CHECKPOINT_SECONDS END"
HOLD_SQL="LEAST(lease_expires_at, TIMESTAMPADD(SECOND, $MAX_HOLD_SQL, requested_at))"
REQUEST_COLUMNS="SELECT id, mode, job_kind, status, COALESCE(release_reason, '-'), protocol_version,
  CASE WHEN lease_expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END,
  CASE WHEN TIMESTAMPADD(SECOND, $MAX_HOLD_SQL, requested_at) <= UTC_TIMESTAMP(6) THEN 1 ELSE 0 END,
  CASE WHEN lease_expires_at >= TIMESTAMPADD(SECOND, $MAX_HOLD_SQL, requested_at) THEN 1 ELSE 0 END,
  DATE_FORMAT($HOLD_SQL, '%Y-%m-%d %H:%i:%s.%f'),
  TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), $HOLD_SQL),
  COALESCE(DATE_FORMAT(verify_requested_at, '%Y-%m-%d %H:%i:%s.%f'), '-')
  FROM sys_backup_quiesce_request"

# 要求を 1 件読む（R_*）。0 = 読めた、1 = 行が無い、2 = 読めない
read_request() { # <名前> <WHERE 以降>
  local out
  out=$(sql "$1" "$REQUEST_COLUMNS $2;") || return 2
  [[ -n "$out" ]] || return 1
  IFS=$'\t' read -r R_ID R_MODE R_KIND R_STATUS R_REASON R_PROTOCOL R_LEASE_VALID R_MAX_HOLD R_CAPPED R_HOLD \
    R_REMAIN_US R_VERIFY <<< "${out%%$'\n'*}"
  [[ "${R_ID:-}" =~ ^[0-9]+$ && "${R_PROTOCOL:-}" =~ ^[0-9]+$ && "${R_REMAIN_US:-}" =~ ^-?[0-9]+$ \
     && "${R_HOLD:-}" =~ $DATETIME_RE && ( "${R_VERIFY:-}" == - || "${R_VERIFY:-}" =~ $DATETIME_RE ) ]] || return 2
  R_ACTIVE=0
  [[ "$R_STATUS" == REQUESTED && "$R_LEASE_VALID" == 1 ]] && R_ACTIVE=1
  return 0
}

# PROCESSLIST の HOST から port を外す（BackupQuiesceHosts.fromProcesslist と同じ規則）
host_of() { # <PROCESSLIST.HOST>
  local v=$1
  if [[ "$v" =~ ^\[([^]]*)\](:[0-9]+)?$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$v" =~ ^(.+):([0-9]+)$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    printf '%s\n' "$v"
  fi
}

# 同じ account の接続元を観測する（OBS_AT・OBS_HOSTS。BackupQuiesceHosts.aggregate と同じ形: host=件数を host の昇順に , で）
observe() {
  local out line at host joined="" n=0
  local -a hosts=()
  out=$(sql observe "SELECT DATE_FORMAT(t.observed_at, '%Y-%m-%d %H:%i:%s.%f'), COALESCE(p.HOST, '-')
  FROM (SELECT UTC_TIMESTAMP(6) AS observed_at) t
  LEFT JOIN information_schema.PROCESSLIST p ON p.USER = SUBSTRING_INDEX(USER(), '@', 1);") || return 1
  OBS_AT=""
  while IFS=$'\t' read -r at host; do
    [[ -n "$at" ]] || continue
    OBS_AT=$at
    [[ "$host" == - ]] || hosts+=("$(host_of "$host")")
  done <<< "$out"
  [[ "$OBS_AT" =~ $DATETIME_RE ]] || return 1
  if (( ${#hosts[@]} > 0 )); then
    while read -r n host; do
      joined+="${joined:+,}$host=$n"
    done < <(printf '%s\n' "${hosts[@]}" | LC_ALL=C sort | uniq -c)
    n=$(printf '%s\n' "${hosts[@]}" | LC_ALL=C sort -u | wc -l)
  fi
  # 列の長さを超える・SQL の文字列に入れられない値は、照合に必ず失敗する値にする（安全側）
  if (( ${#joined} > OBSERVED_HOSTS_MAX_LENGTH )) || [[ ! "$joined" =~ ^[A-Za-z0-9._:%=,-]*$ ]]; then
    joined="OVERFLOW=$n"
  fi
  OBS_HOSTS=$joined
  return 0
}

register() {
  local host=${HOSTNAME:-unknown}
  host=${host//[^A-Za-z0-9._-]/_}
  INSTANCE_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || true)
  [[ "$INSTANCE_ID" =~ ^[0-9a-f-]{36}$ ]] || INSTANCE_ID=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n' \
    | sed -E 's/^(.{8})(.{4})(.{4})(.{4})(.{12})$/\1-\2-\3-\4-\5/')
  sql register "INSERT INTO sys_backup_quiesce_instance (instance_id, host_name, db_client_host, db_connection_id, app_commit,
    protocol_version, poll_interval_ms, heartbeat_interval_ms, fence_after_ms, max_hold_checkpoint_ms, max_hold_full_ms,
    scheduling_enabled, status, gate_state, started_at, last_beat_at)
  SELECT '$INSTANCE_ID', '${host:0:255}', SUBSTRING_INDEX(USER(), '@', -1), CONNECTION_ID(), 'integration',
    $PROTOCOL_VERSION, $POLL_MS, $HEARTBEAT_MS, $FENCE_MS, $(( MAX_HOLD_CHECKPOINT_SECONDS * 1000 )),
    $(( MAX_HOLD_FULL_SECONDS * 1000 )), 1, 'STARTING', 'UNARMED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6);" > /dev/null || return 1
  printf '%s\n' "$INSTANCE_ID" > "$RESPONDER_DIR/instance-id"
  log "instance を登録しました（$INSTANCE_ID）"
}

heartbeat() {
  if sql heartbeat "UPDATE sys_backup_quiesce_instance SET last_beat_at = UTC_TIMESTAMP(6), status = '$STATUS',
    gate_state = '$GATE' WHERE instance_id = '$INSTANCE_ID' AND status <> 'STOPPED';" > /dev/null; then
    LAST_BEAT=$SECONDS
  fi
}

mark_stopped() {
  sql stopped "UPDATE sys_backup_quiesce_instance SET status = 'STOPPED',
    gate_state = CASE WHEN gate_state = 'DISABLED' THEN 'DISABLED' ELSE 'SHUTDOWN' END,
    stopped_at = UTC_TIMESTAMP(6), last_beat_at = UTC_TIMESTAMP(6)
  WHERE instance_id = '$INSTANCE_ID' AND status <> 'STOPPED';" > /dev/null
}

mark_resumed() { # <理由>
  sql resumed "UPDATE sys_backup_quiesce_ack SET state = 'RESUMED', resumed_at = UTC_TIMESTAMP(6), resume_reason = '$1'
  WHERE request_id = $W_ID AND instance_id = '$INSTANCE_ID' AND state = 'QUIESCED';" > /dev/null
}

remember() { HANDLED+="$1 "; }

finish() {
  STEP=IDLE W_ID="" W_WRITTEN_HOLD="" W_PENDING_HOLD="" W_LAST_VERIFY="-" W_OBSERVE_DUE=0 W_RESUME_REASON=""
  GATE=OPEN
}

# 要求の前の準備（排水・hold 期限・観測）を終えて QUIESCED を書く（BackupQuiesceResponder の start〜insertQuiesced）
quiesce() {
  local request_id=$R_ID
  W_ID=$request_id
  GATE=CLOSING
  # 排水: 業務の書込みを持たないので直ちに終わる。hold 期限は改めて読む（readHold）
  if ! read_request hold "WHERE id = $request_id" || (( R_ACTIVE != 1 )) || [[ "$R_MAX_HOLD" == 1 ]] \
      || (( R_REMAIN_US <= 0 )); then
    log "静止要求（番号 $request_id）が応答の前に終わったため、更新を再開しました"
    finish
    return 0
  fi
  W_WRITTEN_HOLD=$R_HOLD
  W_HOLD_CAPPED=$R_CAPPED
  W_LOCAL_DEADLINE=$(( SECONDS + R_REMAIN_US / 1000000 ))
  if ! observe; then
    log "接続元を観測できないため、静止要求（番号 $request_id）に応答しません（次の要求を待ちます）"
    finish
    return 0
  fi
  if ! sql ack "INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, drain_millis, hold_until,
    observed_app_hosts, observed_at, acked_at)
  VALUES ($request_id, '$INSTANCE_ID', 'QUIESCED', 'PAUSED', 0, '$W_WRITTEN_HOLD', '$OBS_HOSTS', '$OBS_AT', UTC_TIMESTAMP(6));" \
      > /dev/null; then
    # 結果が不明でも閉じたまま要求に従う（I-3。RESUMED は QUIESCED の行にだけ書かれる）
    log "WARN: QUIESCED の書込みの結果が不明です（番号 $request_id）。閉じたまま要求に従います"
  else
    log "静止要求に応答しました（番号 $request_id、QUIESCED、hold 期限 $W_WRITTEN_HOLD UTC、接続元 $OBS_HOSTS）"
  fi
  GATE=CLOSED
  STEP=QUIESCED
  W_NEXT_OBSERVE=$(( SECONDS + OBSERVE_EVERY ))
  if [[ -e "$RESPONDER_DIR/freeze-after-ack" ]]; then
    rm -f "$RESPONDER_DIR/freeze-after-ack"
    : > "$RESPONDER_DIR/freeze"
    log "freeze-after-ack: QUIESCED の直後に応答を止めます（負例）"
  fi
}

refuse() { # <理由>
  sql refuse "INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, drain_millis,
    observed_app_hosts, observed_at, refuse_reason, acked_at)
  VALUES ($R_ID, '$INSTANCE_ID', 'REFUSED', 'PAUSED', 0, '', UTC_TIMESTAMP(6), '$1', UTC_TIMESTAMP(6));" > /dev/null \
    || log "WARN: REFUSED を書けませんでした（番号 $R_ID）"
  log "静止要求を拒否しました（番号 $R_ID、REFUSED（$1））"
}

idle_poll() {
  local rc=0
  read_request poll "WHERE status = 'REQUESTED' ORDER BY id DESC LIMIT 1" || rc=$?
  (( rc == 0 )) || return 0
  [[ "$HANDLED" == *" $R_ID "* ]] && return 0
  remember "$R_ID"
  if (( R_ACTIVE != 1 )); then
    log "lease の期限が切れた静止要求（番号 $R_ID）は無効として扱います"
    return 0
  fi
  log "静止要求を読みました（番号 $R_ID、$R_MODE、$R_KIND）"
  if (( R_PROTOCOL != PROTOCOL_VERSION )); then
    refuse UNSUPPORTED_PROTOCOL
    return 0
  fi
  if [[ "$R_MODE" == APP_STOPPED ]]; then
    log "動いている instance が app の停止を確かめる保持（番号 $R_ID）を読みました。更新を止めます（ack は書きません）"
    W_ID=$R_ID
    GATE=CLOSED
    STEP=STOPPED_HOLD
    return 0
  fi
  quiesce
}

# QUIESCED の間: 要求に従う（BackupQuiesceResponder.followRequest・extendHold・observeAgain）
follow() {
  local rc=0
  read_request follow "WHERE id = $W_ID" || rc=$?
  if (( rc == 2 )); then
    # 読めなくても手元の期限で開く（I-3 (b)）
    if (( SECONDS >= W_LOCAL_DEADLINE )); then
      if (( W_HOLD_CAPPED == 1 )); then W_RESUME_REASON=MAX_HOLD; else W_RESUME_REASON=LEASE_EXPIRED; fi
      STEP=RESUMING
    fi
    return 0
  fi
  if (( rc == 1 )); then W_RESUME_REASON=REQUEST_GONE; STEP=RESUMING; return 0; fi
  if [[ "$R_STATUS" == RELEASED ]]; then W_RESUME_REASON=RELEASED; STEP=RESUMING; return 0; fi
  if [[ "$R_STATUS" != REQUESTED ]]; then
    [[ "$R_REASON" == NEW_INSTANCE ]] && log "full の最中に新しい instance が登録されたため、backup が静止を解きました（番号 $W_ID）"
    W_RESUME_REASON=REQUEST_GONE
    STEP=RESUMING
    return 0
  fi
  if [[ "$R_LEASE_VALID" != 1 ]]; then W_RESUME_REASON=LEASE_EXPIRED; STEP=RESUMING; return 0; fi
  if [[ "$R_MAX_HOLD" == 1 ]]; then W_RESUME_REASON=MAX_HOLD; STEP=RESUMING; return 0; fi
  if [[ "$R_HOLD" > "$W_WRITTEN_HOLD" ]]; then
    W_LOCAL_DEADLINE=$(( SECONDS + R_REMAIN_US / 1000000 ))
    W_PENDING_HOLD=$R_HOLD
  fi
  W_HOLD_CAPPED=$R_CAPPED
  if [[ "$R_VERIFY" != - && ( "$W_LAST_VERIFY" == - || "$R_VERIFY" > "$W_LAST_VERIFY" ) ]]; then
    W_LAST_VERIFY=$R_VERIFY
    W_OBSERVE_DUE=1
  fi
}

quiesced_tick() {
  follow
  [[ "$STEP" == QUIESCED ]] || return 0
  if [[ -n "$W_PENDING_HOLD" ]]; then
    if sql extend "UPDATE sys_backup_quiesce_ack SET hold_until = '$W_PENDING_HOLD'
      WHERE request_id = $W_ID AND instance_id = '$INSTANCE_ID' AND state = 'QUIESCED' AND hold_until < '$W_PENDING_HOLD';" \
        > /dev/null; then
      W_WRITTEN_HOLD=$W_PENDING_HOLD
      W_PENDING_HOLD=""
    fi
  fi
  if (( W_OBSERVE_DUE == 1 || SECONDS >= W_NEXT_OBSERVE )); then
    if observe && sql observation "UPDATE sys_backup_quiesce_ack SET observed_app_hosts = '$OBS_HOSTS', observed_at = '$OBS_AT'
        WHERE request_id = $W_ID AND instance_id = '$INSTANCE_ID' AND state = 'QUIESCED';" > /dev/null; then
      W_OBSERVE_DUE=0
    fi
    W_NEXT_OBSERVE=$(( SECONDS + OBSERVE_EVERY ))
  fi
}

resuming_tick() {
  if mark_resumed "$W_RESUME_REASON"; then
    log "更新を再開しました（番号 $W_ID、理由 $W_RESUME_REASON）"
    finish
  fi
}

stopped_hold_tick() {
  local rc=0
  read_request follow "WHERE id = $W_ID" || rc=$?
  (( rc == 2 )) && return 0
  if (( rc == 1 || R_ACTIVE != 1 )); then
    log "app の停止を確かめる保持（番号 $W_ID）が終わったため、更新を再開しました"
    finish
  fi
}

stop_and_exit() {
  if [[ "$STEP" == QUIESCED || "$STEP" == RESUMING ]]; then
    mark_resumed SHUTDOWN || log "WARN: 停止の前に RESUMED を書けませんでした（番号 $W_ID）"
  fi
  if mark_stopped; then
    log "停止しました（STOPPED）"
  else
    log "WARN: STOPPED を書けませんでした"
  fi
  set_state STOPPED
  exit 0
}

main() {
  [[ -d "$RESPONDER_DIR" ]] || { log "RESPONDER_DIR がありません: $RESPONDER_DIR"; exit 1; }
  set_state WAITING
  log "start の file を待ちます（$RESPONDER_DIR/start）"
  until [[ -e "$RESPONDER_DIR/start" ]]; do
    [[ -e "$RESPONDER_DIR/stop" ]] && { set_state STOPPED; exit 0; }
    sleep "$TICK"
  done
  [[ -f "$CNF" ]] || { log "接続の option file がありません: $CNF"; exit 1; }
  until register; do
    log "WARN: instance を登録できません（再試行します）"
    sleep "$TICK"
  done
  # 起動の時の確認: app の停止を確かめる保持が有効なら起動を中止する（BACKUP_QUIESCE_STARTUP_ABORT 相当）
  if read_request startup "WHERE status = 'REQUESTED' ORDER BY id DESC LIMIT 1" && (( R_ACTIVE == 1 )) \
      && [[ "$R_MODE" == APP_STOPPED ]]; then
    log "app の停止を確かめる backup の保持（番号 $R_ID）が有効なため、起動を中止します"
    mark_stopped || log "WARN: STOPPED を書けませんでした"
    set_state ABORTED_STARTUP
    exit 1
  fi
  STATUS=RUNNING
  GATE=OPEN
  heartbeat
  set_state RUNNING
  log "起動しました（RUNNING）"
  while :; do
    if [[ -e "$RESPONDER_DIR/stop" ]]; then
      stop_and_exit
    fi
    if [[ -e "$RESPONDER_DIR/freeze" ]]; then
      set_state FROZEN
      sleep "$TICK"
      continue
    fi
    [[ "$(cat "$RESPONDER_DIR/state" 2>/dev/null)" == RUNNING ]] || set_state RUNNING
    if (( SECONDS - LAST_BEAT >= HEARTBEAT_EVERY )); then
      heartbeat
    fi
    case "$STEP" in
      IDLE) idle_poll ;;
      QUIESCED) quiesced_tick ;;
      RESUMING) resuming_tick ;;
      STOPPED_HOLD) stopped_hold_tick ;;
    esac
    sleep "$TICK"
  done
}

main "$@"
