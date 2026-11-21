#!/usr/bin/env bash
# ============================================================
# 書込み静止 provider quiesce-app-stopped（APP_STOPPED。RB-QUIESCE BWQ-010。.kiro/specs/backup-write-quiesce/design.md §9）
#
# app が動いていない時の backup（初回・配備の時・保守）。backup-full.sh・create-checkpoint.sh の --app-stopped でだけ選ばれる
# （lib/quiesce.sh は QUIESCE_PROVIDER=quiesce-app-stopped が環境変数から来たら拒否する。定期の job から選ばせない。U-2・§9.3）。
# PROCESS 権限は誰にも与えないため、heartbeat を止めたまま書き続ける instance は機械では見つけられない。runbook の §2 の4 で DBA が
# 管理用の account でアプリ接続 0 件を確かめることが最後の防線である（§17 L-4）。
#
# contract（lib/quiesce.sh が呼ぶ。sub command は quiesce-db.sh と同じ）:
#   quiesce-app-stopped.sh hold <state_dir> <FULL|CHECKPOINT>     保持 process（quiesce-db.sh と同じ。§7.6）
#   quiesce-app-stopped.sh acquire <state_dir> <FULL|CHECKPOINT>
#     保持 process が DDL lock を取ったことを確かめ → 事前の確認（有効な要求・停止していない instance・停止口の行が無いこと）
#     → APP_STOPPED の保持を INSERT して commit（保持 process が延長を始める）→ 新しい transaction で分類し直し、停止していない
#     instance が 1 つでもあれば保持を ABORTED にして失敗する（§9.2）。成功すると quiesce.json（mode APP_STOPPED）を書く。
#   quiesce-app-stopped.sh verify <state_dir>    保持が有効で、同じ分類で停止していない instance が 0 件であること
#   quiesce-app-stopped.sh release <state_dir>   保持を解く（RESUMED を待つ instance は無い）
# 保持の間に起動した app は port を開く前に起動を中止する（§6.8。停止口の DISABLED でも同じ）。保持の間に起動しようとして中止し
# STOPPED を書いた instance は数えない（保持は ABORTED にならない。D3-P2-2）。
# 終了コード: 0 = 成功、1 = 失敗、2 = 引数不正
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/../lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"
. "$LIB_DIR/quiesce-db-common.sh"

quiesce_db_stopped::reason() { # <判定> <結果>
  local v=$1 out=$2
  case "$v" in
    FAIL_ACTIVE_REQUEST) echo "他の静止要求が有効です（有効な要求は同時に 1 つだけ。前の backup が実行中か、要求が残っています）" ;;
    FAIL_DISABLED) echo "停止口（BACKUP_QUIESCE_ENABLED=false）で動いている、または異常に止まった停止口の instance の行があります: $(quiesce_db::instances_text "$out" '^DISABLED$')。停止口の行は heartbeat が古くても数えます。ECS の task が止まり processlist が 0 件であることを確かめてから行を片付けます（design.md §9.4）" ;;
    FAIL_ALIVE) echo "停止していない app の instance があります（STOPPED でなく、heartbeat が QUIESCE_DEAD_SECONDS=${QDB_DEAD} 秒の内か保持の後に heartbeat を書いた）: $(quiesce_db::instances_text "$out" '^ALIVE$')。app を止めてから実行します。SIGKILL 等で STOPPED を書かずに止まった instance は ${QDB_DEAD} 秒を待ちます（deploy-runbook §2 の4）" ;;
    FAIL_REQUEST_GONE) echo "保持の要求が既に解かれています（${QDB_DETAIL:-}）" ;;
    FAIL_MODE) echo "保持の要求の mode が APP_STOPPED ではありません（${QDB_DETAIL:-}）" ;;
    FAIL_LEASE_EXPIRED) echo "保持の lease の期限が切れました（保持の間に app が起動できた可能性があります）" ;;
    *) echo "判定 $v（${QDB_DETAIL:-}）" ;;
  esac
}

quiesce_db_stopped::params() {
  printf '%s\n' "int:stale_seconds=$QDB_STALE" "int:dead_seconds=$QDB_DEAD" "int:full=$QDB_FULL"
}

quiesce_db_stopped::acquire() { # <state_dir> <job_kind>
  local d=$1 kind=$2 out rc=0 token
  local -a params=() req=()
  quiesce_db::load_settings "$kind" || return 1
  quiesce_db::init_mysql || return 1
  mapfile -t params < <(quiesce_db_stopped::params)

  # 1. 保持 process が DDL 凍結の lock を取ったこと
  quiesce_db::wait_ddl_lock "$d" || return 1

  # 2. 事前の確認（助言）
  out=$(quiesce_db::run_sql stopped-precheck "${params[@]}") || rc=$?
  if (( rc != 0 )); then
    quiesce_db::log "事前の確認の SQL が失敗しました: ${QDB_SQL_ERROR:-rc=$rc}"
    return 1
  fi
  quiesce_db::read_verdict "$out" || return 1
  if [[ "$QDB_VERDICT" != OK ]]; then
    quiesce_db::log "app の停止を確かめられないため保持を登録しません: $(quiesce_db_stopped::reason "$QDB_VERDICT" "$out")"
    return 1
  fi

  # 3. APP_STOPPED の保持の INSERT と commit。番号を state dir に書く（保持 process が延長を始める）
  token=$(quiesce_db::random_token) || { quiesce_db::log "要求の token を作れません"; return 1; }
  rc=0
  out=$(quiesce_db::run_sql insert-request "hex:token=$token" "enum:mode=APP_STOPPED" "enum:job_kind=$kind" \
    "int:lease_seconds=$QDB_LEASE" "hex:requested_by_hex=$(quiesce_db::hex_of "$QDB_REQUESTED_BY")" \
    "int:protocol_version=$QUIESCE_DB_PROTOCOL_VERSION") || rc=$?
  if (( rc != 0 )); then
    if [[ "$QDB_SQL_ERROR" == *uk_bq_request_active* ]]; then
      quiesce_db::log "他の静止要求が有効なため保持を登録できません（有効な要求は同時に 1 つだけ）"
    else
      quiesce_db::log "保持を登録できません: ${QDB_SQL_ERROR:-rc=$rc}"
    fi
    return 1
  fi
  IFS=$'\t' read -r -a req <<< "$(quiesce_db::row "$out" REQUEST || true)"
  if [[ ! "${req[1]:-}" =~ ^[0-9]{1,19}$ || ! "${req[4]:-}" =~ ^[0-9]{1,19}$ ]]; then
    quiesce_db::log "登録した保持の番号を読めません（lease の期限 $QDB_LEASE 秒で保持は切れます）"
    return 1
  fi
  quiesce_db::write_request_env "$d" "${req[1]}" APP_STOPPED "$kind" "$token" "${req[2]}" "${req[4]}"
  quiesce_db::log "app の停止を確かめる保持を登録しました（番号 ${req[1]}、APP_STOPPED、$kind、lease $QDB_LEASE 秒）"

  # 4. 登録の後に新しい transaction で分類し直す（登録と instance の登録が重なっても、どちらかが他方を見る。§5.3）
  rc=0
  out=$(quiesce_db::run_sql stopped-check "int:request_id=${req[1]}" "${params[@]}") || rc=$?
  if (( rc != 0 )) || ! quiesce_db::read_verdict "$out"; then
    quiesce_db::log "保持の後の確認を実行できません: ${QDB_SQL_ERROR:-rc=$rc}"
    quiesce_db::release_request "$d" || true
    return 1
  fi
  if [[ "$QDB_VERDICT" != OK ]]; then
    quiesce_db::log "保持の後の確認で app の停止を確かめられません: $(quiesce_db_stopped::reason "$QDB_VERDICT" "$out")"
    quiesce_db::release_request "$d" || true
    return 1
  fi

  # 5. quiesce.json（instance は空。整合時刻は手順 4 を確かめた DB の時刻）
  jq -n --arg provider quiesce-app-stopped --arg mode APP_STOPPED --arg job_kind "$kind" --argjson request_id "${req[1]}" \
    --arg request_token "$token" --arg requested_at_utc "${req[2]}" --arg started_at_utc "$QDB_NOW_UTC" \
    --argjson started_at_epoch_us "$QDB_NOW_EPOCH_US" --arg ddl_lock "$QUIESCE_DB_LOCK_NAME" --arg state_dir "$d" \
    '{provider: $provider, mode: $mode, job_kind: $job_kind, request_id: $request_id, request_token: $request_token,
      requested_at_utc: $requested_at_utc, started_at_utc: $started_at_utc, started_at_epoch_us: $started_at_epoch_us,
      instances: [], ddl_lock: $ddl_lock, verified_at_utc: null, released_at_utc: null, release_reason: null,
      state_dir: $state_dir}' > "$d/quiesce.json.tmp" || { quiesce_db::release_request "$d" || true; return 1; }
  chmod 600 "$d/quiesce.json.tmp"
  mv -f "$d/quiesce.json.tmp" "$d/quiesce.json"
  quiesce_db::log "app の停止を確かめました（保持の番号 ${req[1]}、整合時刻 $QDB_NOW_UTC）"
  return 0
}

quiesce_db_stopped::verify_fail() { # <state_dir> <理由>
  quiesce_db::mark "$1/verify-failed" "$2"
  quiesce_db::log "verify が失敗しました（VALID にしません）: $2"
  return 1
}

quiesce_db_stopped::verify() { # <state_dir>
  local d=$1 out rc=0 problem
  local -a params=()
  rm -f "$d/verified" "$d/verify-failed"
  quiesce_db::load_request_env "$d" || { quiesce_db::mark "$d/verify-failed" "保持の要求がありません"; return 1; }
  quiesce_db::load_settings "$QDB_REQUEST_KIND" || return 1
  quiesce_db::init_mysql || return 1
  mapfile -t params < <(quiesce_db_stopped::params)
  if ! problem=$(quiesce_db::hold_problem "$d"); then
    quiesce_db_stopped::verify_fail "$d" "$problem"
    return 1
  fi
  out=$(quiesce_db::run_sql stopped-check "int:request_id=$QDB_REQUEST_ID" "${params[@]}") || rc=$?
  if (( rc != 0 )) || ! quiesce_db::read_verdict "$out"; then
    quiesce_db_stopped::verify_fail "$d" "保持の確認を実行できません: ${QDB_SQL_ERROR:-rc=$rc}"
    return 1
  fi
  if [[ "$QDB_VERDICT" != OK ]]; then
    quiesce_db_stopped::verify_fail "$d" "$(quiesce_db_stopped::reason "$QDB_VERDICT" "$out")"
    return 1
  fi
  quiesce_db::mark "$d/verified" "$QDB_NOW_UTC"
  jq --arg t "$QDB_NOW_UTC" '.verified_at_utc = $t | .verified_instance_count = 0' \
    "$d/quiesce.json" > "$d/quiesce.json.tmp" && chmod 600 "$d/quiesce.json.tmp" && mv -f "$d/quiesce.json.tmp" "$d/quiesce.json"
  quiesce_db::log "verify が通りました（保持の番号 $QDB_REQUEST_ID、停止していない instance 0 件、$QDB_NOW_UTC）"
  return 0
}

quiesce_db_stopped::release() { # <state_dir>
  local d=$1
  if quiesce_db::load_request_env "$d" 2>/dev/null; then
    quiesce_db::load_settings "$QDB_REQUEST_KIND" || return 1
    quiesce_db::init_mysql || return 1
    quiesce_db::release "$d" none
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
    [[ -n "$state_dir" && -d "$state_dir" ]] || { echo "quiesce-app-stopped: state_dir がありません" >&2; exit 2; }
    [[ "$job_kind" == FULL || "$job_kind" == CHECKPOINT ]] \
      || { echo "quiesce-app-stopped: job の種類（FULL / CHECKPOINT）がありません" >&2; exit 2; }
    if [[ "$cmd" == hold ]]; then
      quiesce_db::hold "$state_dir" "$job_kind"
    else
      quiesce_db_stopped::acquire "$state_dir" "$job_kind"
    fi
    ;;
  verify)
    [[ -n "$state_dir" && -d "$state_dir" ]] || { echo "quiesce-app-stopped: state_dir がありません" >&2; exit 2; }
    quiesce_db_stopped::verify "$state_dir"
    ;;
  release)
    [[ -n "$state_dir" && -d "$state_dir" ]] || { echo "quiesce-app-stopped: state_dir がありません" >&2; exit 2; }
    quiesce_db_stopped::release "$state_dir"
    ;;
  *)
    echo "Usage: quiesce-app-stopped.sh hold|acquire <state_dir> <FULL|CHECKPOINT> | verify|release <state_dir>" >&2
    exit 2
    ;;
esac
