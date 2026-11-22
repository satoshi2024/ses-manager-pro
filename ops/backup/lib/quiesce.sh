#!/usr/bin/env bash
# ============================================================
# 書込み静止 protocol（HFP-03-002 / RQ-002。RB-QUIESCE: .kiro/specs/backup-write-quiesce/design.md §7・§9）
# provider は repository 内の固定 executable（providers/*）と明示引数で呼ぶ。
# APP_STOP_COMMAND のような任意 bash -c の env 実行は行わない。
# 静止取得に失敗したら snapshot を発行してはならない（fail-closed）。
#
# provider は次の 3 つだけ:
#   quiesce-local        隔離・ローカル向け（file の heartbeat と ack。従来のまま。verify は常に成功）
#   quiesce-db           ONLINE（app と DB の信号表。本番）。QUIESCE_PROVIDER=quiesce-db で選ぶ
#   quiesce-app-stopped  app の停止を確かめる backup（APP_STOPPED。BWQ-010）。script の --app-stopped でだけ選ぶ
#                        （QUIESCE_PROVIDER=quiesce-app-stopped が環境変数から来たら拒否する。U-2・§9.3）
#
# DB の provider は、acquire の前に保持 process（§7.6）を job 本体の pipe で起動する:
#   exec {QUIESCE_HOLD_FD}> >(providers/<provider>.sh hold …)
# 保持 process の stdin はこの pipe の読み口で、job 本体が終わる（正常・異常・SIGKILL）と kernel が書き口を閉じ、保持
# process は EOF で DDL lock の session を閉じて終わる。job 本体が起動する長い子 process（mysqldump・cp -a・restic・
# mysqlbinlog・mysql 等）は `{QUIESCE_HOLD_FD}>&-` で書き口を閉じて起動する（子が書き口を持つと、job が kill されても子が
# 終わるまで EOF にならない。D3-P3-2）。tests/script-contract-test.sh がこの指定を確かめる。
# D4-P3-1: 変数が未設定・空のまま `cmd {QUIESCE_HOLD_FD}>&-` と書くと ambiguous redirect で失敗する（rc=1。acquire の前の
# restic、quiesce-local でも書くため）。そこで source の時に QUIESCE_HOLD_FD を /dev/null の fd で必ず初期化し（環境変数の
# 値は使わない）、acquire がそれを閉じてから保持 process の pipe に差し替え、release が閉じてから /dev/null に戻す。
# process substitution の process を待つ（$! と wait）には bash 4.4 以上が要る（tool image は bookworm の bash 5.2）。
# ============================================================

LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROVIDERS_DIR="$LIB_DIR/providers"
[[ -d "$PROVIDERS_DIR" ]] || PROVIDERS_DIR=$(cd "$LIB_DIR/../providers" && pwd)

# 環境変数から来た provider の名前（停止確認を環境変数では選ばせないため、source の時の値を残す）
_QUIESCE_PROVIDER_FROM_ENV=${QUIESCE_PROVIDER:-}
QUIESCE_PROVIDER=${QUIESCE_PROVIDER:-quiesce-local}
QUIESCE_STATE_DIR=${QUIESCE_STATE_DIR:-${TMPDIR:-/tmp}/ses-quiesce-$$}
# job の種類（FULL / CHECKPOINT）。backup-full.sh・create-checkpoint.sh が quiesce::acquire の前に設定する（環境変数は使わない）
QUIESCE_JOB_KIND=""
_QUIESCE_APP_STOPPED_SELECTED=0
_QUIESCE_HOLD_ACTIVE=0
QUIESCE_HOLD_PID=""
_QUIESCE_LAST_MARK_MS=""
exec {QUIESCE_HOLD_FD}>/dev/null

# 保持 process・provider が state dir に残す印（acquire の前に消す。同じ state dir を使い回しても前回の印で判定しない）
_QUIESCE_DB_STATE_FILES=(hold.pid hold.exited ddl-lock.ok ddl-lock.failed ddl-lock.lost ddl-session.pid ddl-session.out
  request.env renew-stopped renew-capped full-aborted release.requested verified verify-failed quiesce.json)

quiesce::_now_ms() { date +%s%3N; }

# --app-stopped で停止確認を選ぶ（script の引数からだけ呼ぶ。BWQ-010）
quiesce::select_app_stopped() {
  QUIESCE_PROVIDER=quiesce-app-stopped
  _QUIESCE_APP_STOPPED_SELECTED=1
}

# provider の選択と、その provider に要る設定を確かめる（job の最初に呼ぶ）。不正なら理由を出して非 0。
quiesce::check_provider() {
  if [[ "$_QUIESCE_PROVIDER_FROM_ENV" == quiesce-app-stopped ]]; then
    echo "quiesce: QUIESCE_PROVIDER=quiesce-app-stopped は環境変数では選べません。app の停止を確かめる backup は --app-stopped で明示してください（定期の job から停止確認を選ばせない。design.md §9.3）" >&2
    return 2
  fi
  case "$QUIESCE_PROVIDER" in
    quiesce-local)
      [[ -n "${REPLICA_HEARTBEAT_DIR:-}" ]] || { echo "quiesce: REPLICA_HEARTBEAT_DIR が未設定です（quiesce-local）" >&2; return 1; }
      [[ -n "${SCHEDULER_ACK_DIR:-}" ]] || { echo "quiesce: SCHEDULER_ACK_DIR が未設定です（quiesce-local）" >&2; return 1; }
      ;;
    quiesce-db|quiesce-app-stopped)
      if [[ "$QUIESCE_PROVIDER" == quiesce-app-stopped ]] && (( _QUIESCE_APP_STOPPED_SELECTED != 1 )); then
        echo "quiesce: quiesce-app-stopped は --app-stopped でだけ選べます" >&2
        return 2
      fi
      if (( BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4) )); then
        echo "quiesce: $QUIESCE_PROVIDER には bash 4.4 以上が要ります（保持 process の process substitution。現在 $BASH_VERSION）" >&2
        return 1
      fi
      ;;
    *)
      echo "quiesce: 未知の provider です: $QUIESCE_PROVIDER（quiesce-local / quiesce-db。停止確認は --app-stopped）" >&2
      return 2
      ;;
  esac
  return 0
}

# 静止区間の内訳の印: 直前の印からの ms を <名前> の区間として記録する（provider によらず記録する。§10.3）
quiesce::step() { # <名前>
  local now
  [[ -n "$_QUIESCE_LAST_MARK_MS" && -d "$QUIESCE_STATE_DIR" ]] || return 0
  now=$(quiesce::_now_ms)
  printf '%s\t%s\n' "$1" "$(( now - _QUIESCE_LAST_MARK_MS ))" >> "$QUIESCE_STATE_DIR/window.steps"
  _QUIESCE_LAST_MARK_MS=$now
}

# 保持 process を job 本体の pipe で起動する（D-P2-2）。出力は state dir の hold.log へ（結果の JSON に混ぜない。D3-P3-2）。
# repository lock の fd（9）は保持 process に渡さない。
quiesce::_start_hold() {
  : > "$QUIESCE_STATE_DIR/hold.log"
  chmod 600 "$QUIESCE_STATE_DIR/hold.log"
  exec {QUIESCE_HOLD_FD}>&-
  exec {QUIESCE_HOLD_FD}> >(exec "$PROVIDERS_DIR/$QUIESCE_PROVIDER.sh" hold "$QUIESCE_STATE_DIR" "$QUIESCE_JOB_KIND" \
    9>&- >> "$QUIESCE_STATE_DIR/hold.log" 2>&1)
  QUIESCE_HOLD_PID=$!
  _QUIESCE_HOLD_ACTIVE=1
}

# 保持 process の pipe を閉じ（延長が止まり DDL lock が返る）、終わりを待つ。fd は /dev/null に戻す。
quiesce::_stop_hold() {
  local i
  (( _QUIESCE_HOLD_ACTIVE == 1 )) || return 0
  exec {QUIESCE_HOLD_FD}>&-
  exec {QUIESCE_HOLD_FD}>/dev/null
  for (( i = 0; i < 200; i++ )); do
    [[ -f "$QUIESCE_STATE_DIR/hold.exited" ]] && break
    [[ -n "$QUIESCE_HOLD_PID" ]] && ! kill -0 "$QUIESCE_HOLD_PID" 2>/dev/null && break
    sleep 0.2
  done
  if [[ -n "$QUIESCE_HOLD_PID" ]]; then
    wait "$QUIESCE_HOLD_PID" 2>/dev/null || true
  fi
  _QUIESCE_HOLD_ACTIVE=0
}

# 静止取得。成功時は $QUIESCE_STATE_DIR/quiesce.json が書かれている。
quiesce::acquire() {
  local f
  mkdir -p "$QUIESCE_STATE_DIR"
  rm -f "$QUIESCE_STATE_DIR/window.steps" "$QUIESCE_STATE_DIR/window.start"
  _QUIESCE_LAST_MARK_MS=$(quiesce::_now_ms)
  printf '%s\n' "$_QUIESCE_LAST_MARK_MS" > "$QUIESCE_STATE_DIR/window.start"
  case "$QUIESCE_PROVIDER" in
    quiesce-local)
      "$PROVIDERS_DIR/quiesce-local.sh" acquire "$QUIESCE_STATE_DIR" || return $?
      ;;
    quiesce-db|quiesce-app-stopped)
      quiesce::check_provider || return $?
      if [[ "$QUIESCE_JOB_KIND" != FULL && "$QUIESCE_JOB_KIND" != CHECKPOINT ]]; then
        echo "quiesce: QUIESCE_JOB_KIND（FULL / CHECKPOINT）が設定されていません" >&2
        return 2
      fi
      for f in "${_QUIESCE_DB_STATE_FILES[@]}"; do
        rm -f "$QUIESCE_STATE_DIR/$f"
      done
      quiesce::_start_hold
      if ! "$PROVIDERS_DIR/$QUIESCE_PROVIDER.sh" acquire "$QUIESCE_STATE_DIR" "$QUIESCE_JOB_KIND" {QUIESCE_HOLD_FD}>&- 9>&-; then
        quiesce::_stop_hold
        return 1
      fi
      ;;
    *)
      echo "quiesce: 未知の provider です: $QUIESCE_PROVIDER" >&2
      return 2
      ;;
  esac
  quiesce::step acquire
  return 0
}

# 静止が区間の終わりまで途切れなかったことを確かめる（解除の直前に呼ぶ。§7.2）。失敗したら VALID にしない。
quiesce::verify() {
  local rc=0
  case "$QUIESCE_PROVIDER" in
    quiesce-local) : ;;
    quiesce-db|quiesce-app-stopped)
      "$PROVIDERS_DIR/$QUIESCE_PROVIDER.sh" verify "$QUIESCE_STATE_DIR" {QUIESCE_HOLD_FD}>&- 9>&- || rc=$?
      ;;
    *) echo "quiesce: 未知の provider です: $QUIESCE_PROVIDER" >&2; return 2 ;;
  esac
  quiesce::step verify
  return "$rc"
}

# ONLINE の full の最中に新しい instance が登録され、保持 process が要求を中止したか（U-13。§7.6）。中止なら 1
quiesce::check_aborted() {
  [[ ! -f "$QUIESCE_STATE_DIR/full-aborted" ]]
}

quiesce::aborted_message() {
  echo "full の最中に新しい app の instance が登録されたため、full を中止しました（errorCode=BACKUP_QUIESCE_FULL_ABORTED。U-13）。app の起動が落ち着いた（readiness が UP で instance が 1 つ）後に full を手で取り直してください（tool-image.sh compose run --rm backup。backup-health-monitoring.md）"
}

# 静止区間の秒数と内訳を quiesce.json に書き、QUIESCE_WINDOW_WARN_SECONDS（既定 60 秒）を超えたら WARN を出す（§10.3）
quiesce::_record_window() {
  local start now ms breakdown='{}' name value warn=${QUIESCE_WINDOW_WARN_SECONDS:-60}
  [[ -f "$QUIESCE_STATE_DIR/window.start" && -f "$QUIESCE_STATE_DIR/quiesce.json" ]] || return 0
  start=$(cat "$QUIESCE_STATE_DIR/window.start" 2>/dev/null || true)
  [[ "$start" =~ ^[0-9]+$ ]] || return 0
  now=$(quiesce::_now_ms)
  ms=$(( now - start ))
  if [[ -f "$QUIESCE_STATE_DIR/window.steps" ]]; then
    while IFS=$'\t' read -r name value; do
      [[ "$name" =~ ^[a-z_]+$ && "$value" =~ ^[0-9]+$ ]] || continue
      breakdown=$(jq -c --arg n "$name" --argjson v "$value" '.[$n] = ((.[$n] // 0) + ($v / 1000))' <<< "$breakdown")
    done < "$QUIESCE_STATE_DIR/window.steps"
  fi
  jq --argjson s "$(( ms / 1000 )).$(printf '%03d' $(( ms % 1000 )))" --argjson b "$breakdown" \
    '.window_seconds = $s | .window_breakdown_seconds = $b' "$QUIESCE_STATE_DIR/quiesce.json" \
    > "$QUIESCE_STATE_DIR/quiesce.json.tmp" && chmod 600 "$QUIESCE_STATE_DIR/quiesce.json.tmp" \
    && mv -f "$QUIESCE_STATE_DIR/quiesce.json.tmp" "$QUIESCE_STATE_DIR/quiesce.json"
  if [[ "$warn" =~ ^[0-9]+$ ]] && (( ms > warn * 1000 )); then
    echo "[quiesce] WARN: 静止区間が $(( ms / 1000 )) 秒でした（QUIESCE_WINDOW_WARN_SECONDS=$warn 秒を超えた。内訳（秒）: $breakdown）" >&2
  fi
  rm -f "$QUIESCE_STATE_DIR/window.start"
  _QUIESCE_LAST_MARK_MS=""
  return 0
}

# 静止解除。失敗時は重大 alert 相当（呼び出し元で incident 扱いにする）。DB の provider は再開を確かめられなければ 3
quiesce::release() {
  local rc=0
  case "$QUIESCE_PROVIDER" in
    quiesce-local)
      "$PROVIDERS_DIR/quiesce-local.sh" release "$QUIESCE_STATE_DIR" || rc=$?
      ;;
    quiesce-db|quiesce-app-stopped)
      "$PROVIDERS_DIR/$QUIESCE_PROVIDER.sh" release "$QUIESCE_STATE_DIR" {QUIESCE_HOLD_FD}>&- 9>&- || rc=$?
      quiesce::_stop_hold
      ;;
    *)
      echo "quiesce: 未知の provider です: $QUIESCE_PROVIDER" >&2
      return 2
      ;;
  esac
  quiesce::step release
  quiesce::_record_window
  return "$rc"
}

# 静止区間の情報（quiesce.json の内容を JSON で返す）
quiesce::status_json() {
  cat "$QUIESCE_STATE_DIR/quiesce.json"
}
