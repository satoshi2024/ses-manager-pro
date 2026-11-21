#!/usr/bin/env bash
# ============================================================
# 書込み静止 provider quiesce-db（ONLINE。RB-QUIESCE BWQ-009。.kiro/specs/backup-write-quiesce/design.md §7）
#
# app（BackupQuiesceResponder）と DB の信号表（V202 の sys_backup_quiesce_request・_instance・_ack）で静止を取得・確認・
# 解除する。version 管理された固定の executable で、明示の引数だけを受け付ける（任意の bash -c は実行しない）。
#
# contract（lib/quiesce.sh が呼ぶ）:
#   quiesce-db.sh hold <state_dir> <FULL|CHECKPOINT>
#     保持 process（§7.6）。job 本体が `exec {QUIESCE_HOLD_FD}> >(…)` で起動し、stdin は job 本体の pipe の読み口。
#     DDL 凍結の lock（GET_LOCK('ses_backup_ddl_freeze')）の session を持ち、要求が登録されたら lease を延ばし、ONLINE の
#     full では新しい instance を見つけたら要求を中止する（U-13）。job 本体が終わる（SIGKILL を含む）と EOF で終わる。
#   quiesce-db.sh acquire <state_dir> <FULL|CHECKPOINT>
#     保持 process が DDL lock を取ったことを確かめ → 事前の確認 → 要求の INSERT と commit → 毎回新しい transaction で
#     分類し直して、必要な全 instance（1 つ以上）の QUIESCED を待つ（§7.1）。成功すると quiesce.json を書く。
#   quiesce-db.sh verify <state_dir>
#     verify_requested_at を書き、観測し直しを待ってから、ack・hold 期限・整合時刻・停止口・観測を評価する（§7.2）。
#   quiesce-db.sh release <state_dir>
#     要求を解き（verify 済みなら RELEASED / COMPLETED、そうでなければ ABORTED）、保持 process を終わらせ、この要求に
#     QUIESCED を持ち heartbeat が新しい instance の RESUMED を待つ（§7.3）。確かめられなければ exit 3（重大）。
# 終了コード: 0 = 成功、1 = 失敗（静止できない・VALID にしない）、2 = 引数不正、3 = 再開を確かめられない（重大）
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/../lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"
. "$LIB_DIR/quiesce-db-common.sh"

# 判定（VERDICT）を利用者向けの理由にする
quiesce_db_online::reason() { # <判定> <結果>
  local v=$1 out=$2
  case "$v" in
    FAIL_ACTIVE_REQUEST) echo "他の静止要求が有効です（有効な要求は同時に 1 つだけ。前の backup が実行中か、要求が残っています）" ;;
    FAIL_DISABLED) echo "停止口（BACKUP_QUIESCE_ENABLED=false）で動いている、または異常に止まった停止口の instance の行があります: $(quiesce_db::instances_text "$out" '^DISABLED$')。停止口の間の backup は全て失敗します。異常に止まった行は、ECS の task が止まり processlist が 0 件であることを確かめてから片付けます（design.md §9.4）" ;;
    FAIL_STALE) echo "状態の分からない instance があります（heartbeat が QUIESCE_STALE_SECONDS=${QDB_STALE} 秒より古く、停止済みの閾値 QUIESCE_DEAD_SECONDS=${QDB_DEAD} 秒の内で、ack がありません）: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_NO_INSTANCE) echo "静止に応答する app の instance がありません。app が停止中なら --app-stopped を使ってください（tool-image.sh compose run --rm backup --app-stopped。deploy-runbook §2 の5）" ;;
    FAIL_PROTOCOL) echo "protocol の版が違う instance があります（provider の版 $QUIESCE_DB_PROTOCOL_VERSION）: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_THRESHOLD) echo "instance の閾値（fence_after・heartbeat・poll の間隔）が provider の設定（QUIESCE_STALE_SECONDS=${QDB_STALE}・QUIESCE_LEASE_SECONDS=${QDB_LEASE}・QUIESCE_LEASE_RENEW_SECONDS=${QDB_RENEW}）と整合しません（design.md §5.2）: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_REFUSED) echo "app が静止を拒否しました: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_RESUMED) echo "app が静止を既に解いています: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_REQUEST_GONE) echo "静止要求が既に解かれています（${QDB_DETAIL:-}）" ;;
    FAIL_LEASE_EXPIRED) echo "静止要求の lease の期限が切れました（app は自動で再開しています）" ;;
    FAIL_SCHEDULER) echo "ack の scheduler の状態が PAUSED・DISABLED ではありません: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_HOLD_UNTIL) echo "app の hold 期限（hold_until − QUIESCE_LEASE_MARGIN_SECONDS=${QDB_MARGIN} 秒）を過ぎています（app が自動で再開し得ます）: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_LATE_ACK) echo "要求の前からの instance が整合時刻の後に ack した、または ack がありません（静止区間の初めから静止していたと確かめられません。D-P2-4 (b)）: $(quiesce_db::instances_text "$out" '^REQUIRED$')" ;;
    FAIL_NOT_VERIFY_REQUESTED) echo "verify の要求（verify_requested_at）が書かれていません" ;;
    *) echo "判定 $v（${QDB_DETAIL:-}）" ;;
  esac
}

# 判定に使う設定の引数（SQL の前置き）
quiesce_db_online::common_params() {
  printf '%s\n' "int:stale_seconds=$QDB_STALE" "int:dead_seconds=$QDB_DEAD" \
    "int:protocol_version=$QUIESCE_DB_PROTOCOL_VERSION" "int:lease_seconds=$QDB_LEASE" "int:renew_seconds=$QDB_RENEW" \
    "int:full=$QDB_FULL"
}

quiesce_db_online::acquire() { # <state_dir> <job_kind>
  local d=$1 kind=$2 out rc=0 token deadline foreign
  local -a params=()
  quiesce_db::load_settings "$kind" || return 1
  quiesce_db::init_mysql || return 1
  mapfile -t params < <(quiesce_db_online::common_params)

  # 1. 保持 process が DDL 凍結の lock を取ったこと（取れなければ要求を登録しない）
  quiesce_db::wait_ddl_lock "$d" || return 1

  # 2. 事前の確認（助言）。期限切れの有効な要求を EXPIRED にしてから分類する
  out=$(quiesce_db::run_sql precheck "${params[@]}") || rc=$?
  if (( rc != 0 )); then
    quiesce_db::log "事前の確認の SQL が失敗しました: ${QDB_SQL_ERROR:-rc=$rc}"
    return 1
  fi
  quiesce_db::read_verdict "$out" || return 1
  if [[ "$(quiesce_db::col "$out" EXPIRED 2 || true)" =~ ^[1-9] ]]; then
    quiesce_db::log "期限切れの有効な要求を EXPIRED にしました（$(quiesce_db::col "$out" EXPIRED 2) 件）"
  fi
  if [[ "$QDB_VERDICT" != OK ]]; then
    quiesce_db::log "事前の確認で静止を取得しません: $(quiesce_db_online::reason "$QDB_VERDICT" "$out")"
    return 1
  fi

  # 3. 要求の INSERT と commit。番号を state dir に書く（保持 process が延長を始める）
  token=$(quiesce_db::random_token) || { quiesce_db::log "要求の token を作れません"; return 1; }
  rc=0
  out=$(quiesce_db::run_sql insert-request "hex:token=$token" "enum:mode=ONLINE" "enum:job_kind=$kind" \
    "int:lease_seconds=$QDB_LEASE" "hex:requested_by_hex=$(quiesce_db::hex_of "$QDB_REQUESTED_BY")" \
    "int:protocol_version=$QUIESCE_DB_PROTOCOL_VERSION") || rc=$?
  if (( rc != 0 )); then
    if [[ "$QDB_SQL_ERROR" == *uk_bq_request_active* ]]; then
      quiesce_db::log "他の静止要求が有効なため要求を登録できません（有効な要求は同時に 1 つだけ）"
    else
      quiesce_db::log "静止要求を登録できません: ${QDB_SQL_ERROR:-rc=$rc}"
    fi
    return 1
  fi
  local -a req=()
  IFS=$'\t' read -r -a req <<< "$(quiesce_db::row "$out" REQUEST || true)"
  if [[ ! "${req[1]:-}" =~ ^[0-9]{1,19}$ || ! "${req[4]:-}" =~ ^[0-9]{1,19}$ ]]; then
    quiesce_db::log "登録した要求の番号を読めません（lease の期限 $QDB_LEASE 秒で app は自動で再開します）"
    return 1
  fi
  quiesce_db::write_request_env "$d" "${req[1]}" ONLINE "$kind" "$token" "${req[2]}" "${req[4]}"
  quiesce_db::log "静止要求を登録しました（番号 ${req[1]}、ONLINE、$kind、lease $QDB_LEASE 秒）"

  # 4. 必要な全 instance（1 つ以上）の QUIESCED を待つ（毎回新しい transaction で分類し直す）
  deadline=$(( $(quiesce_db::now) + QDB_DEADLINE ))
  while :; do
    local problem=""
    if ! problem=$(quiesce_db::hold_problem "$d"); then
      quiesce_db::log "静止を取得しません: $problem"
      quiesce_db::release_request "$d" || true
      return 1
    fi
    rc=0
    out=$(quiesce_db::run_sql poll-acks "int:request_id=${req[1]}" "${params[@]}" "int:margin_seconds=$QDB_MARGIN") || rc=$?
    if (( rc == 0 )) && quiesce_db::read_verdict "$out"; then
      case "$QDB_VERDICT" in
        OK) break ;;
        WAIT) : ;;
        *)
          quiesce_db::log "静止を取得しません: $(quiesce_db_online::reason "$QDB_VERDICT" "$out")"
          quiesce_db::release_request "$d" || true
          return 1
          ;;
      esac
    else
      quiesce_db::log "WARN: ack を読めません（再試行します）: ${QDB_SQL_ERROR:-rc=$rc}"
    fi
    if (( $(quiesce_db::now) >= deadline )); then
      quiesce_db::log "ack 待ちの上限（QUIESCE_DEADLINE_SECONDS=$QDB_DEADLINE 秒）を超えました。ack の無い必要な instance: $(quiesce_db::instances_text "$out" '^REQUIRED$')"
      quiesce_db::release_request "$d" || true
      return 1
    fi
    sleep 1
  done

  # 5. 観測した接続元が ack した必要な instance の接続元だけであること（§5.4）
  foreign=$(quiesce_db::foreign_hosts "$out")
  if [[ -n "$foreign" ]]; then
    quiesce_db::log "静止を取得しません: ack した必要な instance の接続元以外から app account の接続が観測されました（$foreign。heartbeat の無い接続元・中継の可能性）"
    quiesce_db::release_request "$d" || true
    return 1
  fi
  quiesce_db::warn_short_max_hold "$out"

  # 6. quiesce.json（整合時刻 started_at_utc = 全 ack を確かめた DB の時刻）
  local instances
  instances=$(quiesce_db::instances_json "$out") || { quiesce_db::release_request "$d" || true; return 1; }
  jq -n --arg provider quiesce-db --arg mode ONLINE --arg job_kind "$kind" --argjson request_id "${req[1]}" \
    --arg request_token "$token" --arg requested_at_utc "${req[2]}" --arg started_at_utc "$QDB_NOW_UTC" \
    --argjson started_at_epoch_us "$QDB_NOW_EPOCH_US" --argjson instances "$instances" \
    --arg ddl_lock "$QUIESCE_DB_LOCK_NAME" --arg state_dir "$d" \
    '{provider: $provider, mode: $mode, job_kind: $job_kind, request_id: $request_id, request_token: $request_token,
      requested_at_utc: $requested_at_utc, started_at_utc: $started_at_utc, started_at_epoch_us: $started_at_epoch_us,
      instances: $instances, ddl_lock: $ddl_lock, verified_at_utc: null, released_at_utc: null, release_reason: null,
      state_dir: $state_dir}' > "$d/quiesce.json.tmp" || { quiesce_db::release_request "$d" || true; return 1; }
  chmod 600 "$d/quiesce.json.tmp"
  mv -f "$d/quiesce.json.tmp" "$d/quiesce.json"
  quiesce_db::log "静止を取得しました（番号 ${req[1]}、必要な instance ${QDB_COUNT} 件、整合時刻 $QDB_NOW_UTC）"
  return 0
}

quiesce_db_online::verify_fail() { # <state_dir> <理由>
  quiesce_db::mark "$1/verify-failed" "$2"
  quiesce_db::log "verify が失敗しました（VALID にしません）: $2"
  return 1
}

quiesce_db_online::verify() { # <state_dir>
  local d=$1 out rc=0 problem consistency start foreign
  local -a params=()
  rm -f "$d/verified" "$d/verify-failed"
  quiesce_db::load_request_env "$d" || { quiesce_db::mark "$d/verify-failed" "要求がありません"; return 1; }
  quiesce_db::load_settings "$QDB_REQUEST_KIND" || return 1
  quiesce_db::init_mysql || return 1
  mapfile -t params < <(quiesce_db_online::common_params)
  consistency=$(jq -r '.started_at_epoch_us // empty' "$d/quiesce.json" 2>/dev/null || true)
  [[ "$consistency" =~ ^[0-9]{1,19}$ ]] || { quiesce_db_online::verify_fail "$d" "quiesce.json に整合時刻がありません"; return 1; }
  if ! problem=$(quiesce_db::hold_problem "$d"); then
    quiesce_db_online::verify_fail "$d" "$problem"
    return 1
  fi
  # 1. app に接続元を観測し直させる
  out=$(quiesce_db::run_sql verify-request "int:request_id=$QDB_REQUEST_ID") || rc=$?
  if (( rc != 0 )); then
    quiesce_db_online::verify_fail "$d" "verify の要求を書けません: ${QDB_SQL_ERROR:-rc=$rc}"
    return 1
  fi
  if [[ "$(quiesce_db::col "$out" VERIFY_REQUESTED 2 || true)" != 1 ]]; then
    quiesce_db_online::verify_fail "$d" "静止要求が既に解かれています（full の最中の新しい instance による中止・lease の期限切れ等）"
    return 1
  fi
  # 2〜3. 観測し直しを最長 QUIESCE_VERIFY_OBSERVE_SECONDS 待ち、新しい transaction で評価する
  start=$(quiesce_db::now)
  while :; do
    if ! problem=$(quiesce_db::hold_problem "$d"); then
      quiesce_db_online::verify_fail "$d" "$problem"
      return 1
    fi
    rc=0
    out=$(quiesce_db::run_sql verify-check "int:request_id=$QDB_REQUEST_ID" "${params[@]}" \
      "int:margin_seconds=$QDB_MARGIN" "int:consistency_epoch_us=$consistency") || rc=$?
    if (( rc == 0 )) && quiesce_db::read_verdict "$out"; then
      case "$QDB_VERDICT" in
        OK) break ;;
        WAIT) : ;;
        *) quiesce_db_online::verify_fail "$d" "$(quiesce_db_online::reason "$QDB_VERDICT" "$out")"; return 1 ;;
      esac
    else
      quiesce_db::log "WARN: verify の評価を読めません（再試行します）: ${QDB_SQL_ERROR:-rc=$rc}"
    fi
    if (( $(quiesce_db::now) - start >= QDB_OBSERVE )); then
      quiesce_db_online::verify_fail "$d" "観測し直し・ack が QUIESCE_VERIFY_OBSERVE_SECONDS=$QDB_OBSERVE 秒の内に揃いません（${QDB_DETAIL:-判定なし}）: $(quiesce_db::instances_text "$out" '^REQUIRED$')"
      return 1
    fi
    sleep 1
  done
  foreign=$(quiesce_db::foreign_hosts "$out")
  if [[ -n "$foreign" ]]; then
    quiesce_db_online::verify_fail "$d" "ack した必要な instance の接続元以外から app account の接続が観測されました（$foreign）"
    return 1
  fi
  quiesce_db::mark "$d/verified" "$QDB_NOW_UTC"
  jq --arg t "$QDB_NOW_UTC" --argjson n "${QDB_COUNT:-0}" '.verified_at_utc = $t | .verified_instance_count = $n' \
    "$d/quiesce.json" > "$d/quiesce.json.tmp" && chmod 600 "$d/quiesce.json.tmp" && mv -f "$d/quiesce.json.tmp" "$d/quiesce.json"
  quiesce_db::log "verify が通りました（番号 $QDB_REQUEST_ID、必要な instance ${QDB_COUNT} 件、$QDB_NOW_UTC）"
  return 0
}

quiesce_db_online::release() { # <state_dir>
  local d=$1
  if quiesce_db::load_request_env "$d" 2>/dev/null; then
    quiesce_db::load_settings "$QDB_REQUEST_KIND" || return 1
    quiesce_db::init_mysql || return 1
    quiesce_db::release "$d" online
  else
    quiesce_db::stop_hold "$d"
    quiesce_db::hold_log_summary "$d"
    return 0
  fi
}

cmd=${1:-}
state_dir=${2:-}
job_kind=${3:-}
case "$cmd" in
  hold|acquire)
    [[ -n "$state_dir" && -d "$state_dir" ]] || { echo "quiesce-db: state_dir がありません" >&2; exit 2; }
    [[ "$job_kind" == FULL || "$job_kind" == CHECKPOINT ]] || { echo "quiesce-db: job の種類（FULL / CHECKPOINT）がありません" >&2; exit 2; }
    if [[ "$cmd" == hold ]]; then
      quiesce_db::hold "$state_dir" "$job_kind"
    else
      quiesce_db_online::acquire "$state_dir" "$job_kind"
    fi
    ;;
  verify)
    [[ -n "$state_dir" && -d "$state_dir" ]] || { echo "quiesce-db: state_dir がありません" >&2; exit 2; }
    quiesce_db_online::verify "$state_dir"
    ;;
  release)
    [[ -n "$state_dir" && -d "$state_dir" ]] || { echo "quiesce-db: state_dir がありません" >&2; exit 2; }
    quiesce_db_online::release "$state_dir"
    ;;
  *)
    echo "Usage: quiesce-db.sh hold|acquire <state_dir> <FULL|CHECKPOINT> | verify|release <state_dir>" >&2
    exit 2
    ;;
esac
