#!/usr/bin/env bash
# RB-QUIESCE BWQ-009: 書込み静止 provider quiesce-db（ONLINE）・保持 process・verify・release の試験
#
# fake の mysql（fixtures/bin/mysql の quiesce-db の応答。FAKE_QDB_DIR に呼出しを記録し、応答を差し替える）を使い、実 DB には
# 接続しない。判定（必要な instance の分類・ack・hold 期限・整合時刻）は SQL の VERDICT が出すため、ここでは provider が判定を
# どう扱うか（失敗・待ち・要求の中止・印）と、保持 process の終わり方（job の SIGKILL・DDL lock の待ちの間を含む）・lease の
# 延長・full の中止・release の再開の確認・観測した接続元の照合・設定の検査を固定する。SQL の意味は実 MySQL 8.4 の
# BackupQuiesceProviderContractMySqlTest（BWQ-011）が固定する。
# job 本体は case ごとに別の process（$T/job.sh）で動かす（保持 process は job 本体の pipe に結び付くため）。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
PROVIDERS="$ROOT/ops/backup/providers"
FIXTURES="$HERE/fixtures/bin"

FAKE_PW='S3cr3t-Value-XYZ-QDB'
INSTANCE_A=0b7c6a2e-1111-4a5b-9c3d-000000000001
INSTANCE_B=0b7c6a2e-2222-4a5b-9c3d-000000000002

setup_qdb() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath" "$T/state" "$T/qdb"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER='bkp-user-7x9' MYSQL_DATABASE=ses_manager_db
  export MYSQL_SSL_CAPATH="$T/capath"
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export FAKE_QDB_DIR="$T/qdb" QUIESCE_STATE_DIR="$T/state" QUIESCE_PROVIDER=quiesce-db LIB_UNDER_TEST="$LIB"
  export QUIESCE_DEADLINE_SECONDS=8 QUIESCE_LEASE_RENEW_SECONDS=1 QUIESCE_VERIFY_OBSERVE_SECONDS=2 \
    QUIESCE_RESUME_CONFIRM_SECONDS=2 QUIESCE_FULL_NEW_INSTANCE_CHECK_SECONDS=1
  unset MYSQL_PWD FAKE_QDB_LOCK FAKE_QDB_LOCK_DIE_AFTER FAKE_DUMP_SLEEP FAKE_DUMP_PID_FILE QUIESCE_STALE_SECONDS \
    QUIESCE_DEAD_SECONDS QUIESCE_LEASE_SECONDS QUIESCE_LEASE_MAX_SECONDS QUIESCE_REQUESTED_BY QUIESCE_HOLD_FD
  # job 本体: lib/quiesce.sh を読み、引数の手順を順に行う（各手順の結果を標準エラーに「step=rc」で出す）
  cat > "$T/job.sh" <<'JOB'
#!/usr/bin/env bash
set -Eeuo pipefail
. "$LIB_UNDER_TEST/common.sh"
. "$LIB_UNDER_TEST/quiesce.sh"
QUIESCE_JOB_KIND=$1
shift
printf '%s\n' "$$" > "$QUIESCE_STATE_DIR/../job.pid"
quiesce::check_provider
for step in "$@"; do
  rc=0
  case "$step" in
    acquire) quiesce::acquire || rc=$? ;;
    verify) quiesce::verify || rc=$? ;;
    release) quiesce::release || rc=$? ;;
    aborted) quiesce::check_aborted || rc=$? ;;
    sleep:*) sleep "${step#sleep:}" {QUIESCE_HOLD_FD}>&- ;;
    dump) "$MYSQLDUMP_BIN" --no-tablespaces db > /dev/null {QUIESCE_HOLD_FD}>&- || rc=$? ;;
    json) printf '{"result":"ok"}\n' ;;
    *) echo "job: 未知の手順 $step" >&2; exit 2 ;;
  esac
  echo "step:$step=$rc" >&2
  if [[ "$step" == acquire && $rc -ne 0 ]]; then
    exit 11
  fi
done
JOB
  chmod +x "$T/job.sh"
}

teardown_qdb() {
  local p
  for p in "$T/qdb/ddl-lock.pid" "$T/state/hold.pid" "${FAKE_DUMP_PID_FILE:-/nonexistent}"; do
    [[ -f "$p" ]] && kill "$(cat "$p")" 2>/dev/null
  done
  return 0
}

# job を実行し、標準出力を JOB_OUT、標準エラーを JOB_ERR、終了コードを JOB_RC に入れる
run_job() { # <job_kind> <手順...>
  JOB_RC=0
  "$T/job.sh" "$@" > "$T/job.out" 2> "$T/job.err" || JOB_RC=$?
  JOB_OUT=$(cat "$T/job.out")
  JOB_ERR=$(cat "$T/job.err")
}

calls() { cut -f2 "$T/qdb/calls.log" 2>/dev/null | tr '\n' ' '; }
call_count() { # <名前>（calls.log が無ければ 0）
  [[ -f "$T/qdb/calls.log" ]] || { echo 0; return 0; }
  awk -F'\t' -v n="$1" '$2 == n { c++ } END { print c + 0 }' "$T/qdb/calls.log"
}
call_params() { awk -F'\t' -v n="$1" '$2 == n { print $3 }' "$T/qdb/calls.log" 2>/dev/null; }

# 判定の行と instance の行（fake の応答を差し替える）
verdict_line() { # <判定> [<必要な instance の数>] [<内訳>]
  printf 'VERDICT\t%s\t2026-11-22T00:00:06Z\t1763769606000000\t%s\t%s\n' "$1" "${2:-1}" "${3:-test}"
}
instance_line() { # <id> <分類> <新しい> <要求の前から> <ack> <観測> <verify の後の観測> [<status>] [<接続元>]
  printf 'INSTANCE\t%s\t%s\t%s\t%s\t%s\t-\thost-%s\t%s\t2026-11-22T00:00:00.000000Z\t2026-11-22T00:00:05.000000Z\t2026-11-22T00:01:05.000000Z\t%s\t%s\t300000\t1500\t%s\tCLOSED\n' \
    "$1" "$2" "$3" "$4" "$5" "${1:0:4}" "${9:-10.0.0.11}" "$6" "$7" "${8:-RUNNING}"
}

wait_file() { # <path> <秒>
  local i
  for (( i = 0; i < $2 * 10; i++ )); do
    [[ -e "$1" ]] && return 0
    sleep 0.1
  done
  return 1
}

pid_gone_within() { # <pid> <秒>
  local i
  for (( i = 0; i < $2 * 10; i++ )); do
    kill -0 "$1" 2>/dev/null || return 0
    sleep 0.1
  done
  return 1
}

# ------------------------------------------------------------ 取得・verify・解除の成功

case_acquire_verify_release_success() {
  setup_qdb
  run_job CHECKPOINT acquire json verify release
  assert_eq 0 "$JOB_RC" "全 ack で取得・verify・解除が成功する"
  assert_eq '{"result":"ok"}' "$JOB_OUT" "保持 process・provider の出力が結果の JSON（標準出力）に混ざらない"
  local q="$T/state/quiesce.json"
  assert_eq quiesce-db "$(jq -r .provider "$q")" "quiesce.json: provider"
  assert_eq ONLINE "$(jq -r .mode "$q")" "quiesce.json: mode"
  assert_eq CHECKPOINT "$(jq -r .job_kind "$q")" "quiesce.json: job_kind"
  assert_eq 7 "$(jq -r .request_id "$q")" "quiesce.json: request_id"
  assert_eq 2026-11-22T00:00:06Z "$(jq -r .started_at_utc "$q")" "quiesce.json: 整合時刻は全 ack を確かめた DB の時刻"
  assert_eq 1763769606000000 "$(jq -r .started_at_epoch_us "$q")" "quiesce.json: 整合時刻の epoch の μ秒"
  assert_eq "$INSTANCE_A" "$(jq -r '.instances[0].instance_id' "$q")" "quiesce.json: 必要な instance"
  assert_eq 2026-11-22T00:00:05.000000Z "$(jq -r '.instances[0].acked_at_utc' "$q")" "quiesce.json: instance の acked_at"
  assert_eq 2026-11-22T00:00:06Z "$(jq -r .verified_at_utc "$q")" "quiesce.json: verified_at_utc"
  assert_eq COMPLETED "$(jq -r .release_reason "$q")" "verify 済みの解除の理由は COMPLETED"
  assert_eq ses_backup_ddl_freeze "$(jq -r .ddl_lock "$q")" "DDL 凍結の lock の名前（Flyway の callback と同じ）"
  assert_eq true "$(jq -r '.window_seconds | type == "number"' "$q")" "静止区間の秒数を記録する"
  assert_eq true "$(jq -r '.window_breakdown_seconds | has("acquire") and has("verify") and has("release")' "$q")" \
    "静止区間の内訳を記録する"
  assert_contains "$(calls)" "ddl-lock precheck insert-request poll-acks" "DDL lock → 事前の確認 → INSERT → ack の待ちの順"
  assert_contains "$(calls)" "verify-request verify-check" "verify は verify_requested_at を書いてから評価する"
  assert_contains "$(calls)" "release resume-check" "解除の後に再開を確かめる"
  local ins
  ins=$(call_params insert-request)
  assert_contains "$ins" "mode = 'ONLINE';" "INSERT の mode"
  assert_contains "$ins" "job_kind = 'CHECKPOINT';" "INSERT の job_kind"
  assert_contains "$ins" "lease_seconds = 60;" "INSERT の lease（既定 60 秒）"
  if [[ "$ins" =~ token\ =\ \'[0-9a-f]{32}\'\; && "$ins" =~ requested_by_hex\ =\ \'[0-9a-f]+\'\; ]]; then
    test_assert "token と requested_by は hex で渡す（任意の文字列を SQL に埋め込まない）"
  else
    test_fail "token と requested_by は hex で渡す" "$ins"
  fi
  assert_contains "$(call_params release)" "status = 'RELEASED'; reason = 'COMPLETED';" "解除は RELEASED / COMPLETED"
  assert_file "$T/qdb/ddl-lock.released" "DDL 凍結の lock を返した（session の EOF）"
  assert_no_file "$T/qdb/ddl-lock.held" "DDL 凍結の lock を持ち続けない"
  assert_file "$T/state/hold.exited" "保持 process が終わった"
  assert_contains "$(cat "$T/state/hold.log")" "DDL 凍結の lock（ses_backup_ddl_freeze）を取りました" "保持 process の出力は hold.log に出る"
  assert_contains "$JOB_ERR" "保持 process の log" "release は hold.log の要約を job の標準エラーに出す"
  assert_not_contains "$JOB_ERR$(cat "$T/state/hold.log")" "$FAKE_PW" "秘密を出さない"
  teardown_qdb
}

case_release_without_verify_is_aborted() {
  setup_qdb
  run_job FULL acquire release
  assert_eq 0 "$JOB_RC" "verify の前の解除も成功する"
  assert_contains "$(call_params release)" "status = 'ABORTED'; reason = 'ABORTED';" "verify していない要求は ABORTED で解く"
  assert_contains "$(call_params insert-request)" "job_kind = 'FULL';" "full の要求"
  teardown_qdb
}

case_requested_by_default_and_format() {
  setup_qdb
  export QUIESCE_REQUESTED_BY='山田 太郎'
  run_job CHECKPOINT acquire release
  assert_nonzero "$JOB_RC" "requested_by の形式の外（個人名など）は拒否する"
  assert_eq 0 "$(call_count insert-request)" "形式の外なら要求を登録しない"
  unset QUIESCE_REQUESTED_BY
  teardown_qdb
}

# ------------------------------------------------------------ 事前の確認

case_precheck_failures_do_not_register() {
  local v expect
  for v in FAIL_STALE FAIL_NO_INSTANCE FAIL_PROTOCOL FAIL_THRESHOLD FAIL_DISABLED FAIL_ACTIVE_REQUEST; do
    setup_qdb
    case "$v" in
      FAIL_STALE) expect="状態の分からない instance" ;;
      FAIL_NO_INSTANCE) expect="--app-stopped" ;;
      FAIL_PROTOCOL) expect="protocol の版" ;;
      FAIL_THRESHOLD) expect="閾値" ;;
      FAIL_DISABLED) expect="停止口" ;;
      FAIL_ACTIVE_REQUEST) expect="他の静止要求が有効" ;;
    esac
    { printf 'EXPIRED\t0\n'; verdict_line "$v" 0; instance_line "$INSTANCE_A" DISABLED 0 - - - 0; } > "$T/qdb/precheck.out"
    run_job CHECKPOINT acquire
    assert_eq 11 "$JOB_RC" "事前の確認 $v で取得しない"
    assert_eq 0 "$(call_count insert-request)" "事前の確認 $v なら要求を登録しない"
    assert_contains "$JOB_ERR" "$expect" "事前の確認 $v の理由"
    assert_file "$T/qdb/ddl-lock.released" "事前の確認 $v の後に DDL lock を返す"
    teardown_qdb
  done
}

case_disabled_row_fails_until_cleanup() {
  setup_qdb
  # heartbeat が 300 秒より古い STOPPED でない DISABLED の行（D3-P2-3）: SQL は古さによらず FAIL_DISABLED を返す
  { printf 'EXPIRED\t0\n'; verdict_line FAIL_DISABLED 0; instance_line "$INSTANCE_B" DISABLED 0 - - - 0 STARTING; } \
    > "$T/qdb/precheck.1.out"
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "停止口の行があれば ONLINE の取得は失敗する"
  assert_contains "$JOB_ERR" "$INSTANCE_B" "停止口の行の instance を示す"
  assert_contains "$JOB_ERR" "§9.4" "片付けの手順を示す"
  # 片付けの SQL の後（その行が STOPPED になった）: 事前の確認は通る
  run_job CHECKPOINT acquire verify release
  assert_eq 0 "$JOB_RC" "片付けの後は取得・verify が通る"
  teardown_qdb
}

case_settings_are_validated() {
  setup_qdb
  export QUIESCE_STALE_SECONDS=400 QUIESCE_DEAD_SECONDS=300
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "QUIESCE_STALE_SECONDS >= QUIESCE_DEAD_SECONDS は拒否する"
  assert_contains "$JOB_ERR" "QUIESCE_STALE_SECONDS" "設定の誤りを示す"
  assert_eq 0 "$(call_count insert-request)" "設定が不正なら要求を登録しない"
  export QUIESCE_STALE_SECONDS=60 QUIESCE_LEASE_SECONDS=15 QUIESCE_LEASE_RENEW_SECONDS=15
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "延長の間隔 >= lease は拒否する"
  unset QUIESCE_STALE_SECONDS QUIESCE_DEAD_SECONDS QUIESCE_LEASE_SECONDS
  teardown_qdb
}

case_ddl_lock_conflict_does_not_register() {
  setup_qdb
  export FAKE_QDB_LOCK=0
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "DDL 凍結の lock を取れなければ取得しない"
  assert_eq 0 "$(call_count insert-request)" "DDL lock の競合では要求を登録しない"
  assert_eq 0 "$(call_count precheck)" "DDL lock を確かめてから事前の確認をする"
  assert_contains "$JOB_ERR" "DDL 凍結の lock" "DDL lock の競合を示す"
  unset FAKE_QDB_LOCK
  teardown_qdb
}

# ------------------------------------------------------------ ack の待ち

case_poll_failures_abort_request() {
  local v start elapsed
  for v in FAIL_REFUSED FAIL_RESUMED FAIL_DISABLED FAIL_STALE FAIL_NO_INSTANCE FAIL_REQUEST_GONE; do
    setup_qdb
    { verdict_line "$v" 0; instance_line "$INSTANCE_A" REQUIRED 1 1 REFUSED - 0; } > "$T/qdb/poll-acks.out"
    start=$(date +%s)
    run_job CHECKPOINT acquire
    elapsed=$(( $(date +%s) - start ))
    assert_eq 11 "$JOB_RC" "ack の待ちの $v で取得しない"
    if (( elapsed <= 5 )); then
      test_assert "ack の待ちの $v は待たずに失敗する（${elapsed} 秒）"
    else
      test_fail "ack の待ちの $v は待たずに失敗する" "${elapsed} 秒"
    fi
    assert_eq 1 "$(call_count poll-acks)" "ack の待ちの $v は 1 回の読取りで失敗する"
    assert_contains "$(call_params release)" "status = 'ABORTED'; reason = 'ABORTED';" "ack の待ちの $v の後に要求を ABORTED にする"
    teardown_qdb
  done
}

case_only_instance_stopped_after_registration_fails() {
  setup_qdb
  # 登録の後に唯一の instance が STOPPED になり、必要な instance が 0 になる（D-P2-4 (a)）
  { verdict_line WAIT 1; instance_line "$INSTANCE_A" REQUIRED 1 1 - - 0; } > "$T/qdb/poll-acks.1.out"
  { verdict_line FAIL_NO_INSTANCE 0; instance_line "$INSTANCE_A" IGNORED 1 1 - - 0 STOPPED; } > "$T/qdb/poll-acks.2.out"
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "必要な instance が 0 になれば取得しない"
  assert_contains "$JOB_ERR" "instance がありません" "必要な instance が無いことを示す"
  teardown_qdb
}

case_instance_added_between_precheck_and_insert_must_ack() {
  setup_qdb
  # 事前の確認では 1 つ、INSERT の後の分類し直しで 2 つ目が増えた（その ack を待つ）
  { verdict_line WAIT 2; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1,10.0.0.12=1 0;
    instance_line "$INSTANCE_B" REQUIRED 1 1 - - 0 RUNNING 10.0.0.12; } > "$T/qdb/poll-acks.1.out"
  { verdict_line OK 2; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1,10.0.0.12=1 0;
    instance_line "$INSTANCE_B" REQUIRED 1 1 QUIESCED 10.0.0.11=1,10.0.0.12=1 0 RUNNING 10.0.0.12; } > "$T/qdb/poll-acks.2.out"
  run_job CHECKPOINT acquire release
  assert_eq 0 "$JOB_RC" "増えた instance の ack の後に取得する"
  assert_eq 2 "$(call_count poll-acks)" "ack が揃うまで分類し直して待つ"
  assert_eq 2 "$(jq '.instances | length' "$T/state/quiesce.json")" "増えた instance も quiesce.json に記録する"
  teardown_qdb
}

case_stopped_instance_is_ignored() {
  setup_qdb
  { verdict_line OK 1; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=2 0;
    instance_line "$INSTANCE_B" IGNORED 0 1 - - 0 STOPPED 10.0.0.12; } > "$T/qdb/poll-acks.out"
  run_job CHECKPOINT acquire release
  assert_eq 0 "$JOB_RC" "停止済みの instance は待たずに取得する"
  assert_eq "$INSTANCE_A" "$(jq -r '[.instances[].instance_id] | join(",")' "$T/state/quiesce.json")" \
    "停止済みの instance は quiesce.json の必要な instance に入れない"
  teardown_qdb
}

case_poll_deadline_aborts() {
  setup_qdb
  export QUIESCE_DEADLINE_SECONDS=2
  { verdict_line WAIT 1; instance_line "$INSTANCE_A" REQUIRED 1 1 - - 0; } > "$T/qdb/poll-acks.out"
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "ack が上限までに揃わなければ取得しない"
  assert_contains "$JOB_ERR" "QUIESCE_DEADLINE_SECONDS" "ack の待ちの上限を示す"
  assert_contains "$(call_params release)" "status = 'ABORTED';" "上限を超えたら要求を ABORTED にする"
  teardown_qdb
}

case_foreign_observed_host_fails_acquire() {
  setup_qdb
  { verdict_line OK 1; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=2,10.0.0.99=1 0; } > "$T/qdb/poll-acks.out"
  run_job CHECKPOINT acquire
  assert_eq 11 "$JOB_RC" "ack した instance の接続元以外の接続が見えたら取得しない"
  assert_contains "$JOB_ERR" "10.0.0.99" "外れた接続元を示す"
  teardown_qdb
}

case_short_max_hold_warns() {
  setup_qdb
  { verdict_line OK 1; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1 0; } \
    | sed 's/\t300000\t/\t120000\t/' > "$T/qdb/poll-acks.out"
  run_job CHECKPOINT acquire release
  assert_eq 0 "$JOB_RC" "最大保持時間が短くても取得する"
  assert_contains "$JOB_ERR" "最大保持時間（120 秒）が QUIESCE_LEASE_MAX_SECONDS（300 秒）より短い" "短い最大保持時間を WARN に出す"
  teardown_qdb
}

# ------------------------------------------------------------ verify

case_verify_failures() {
  local v expect
  for v in FAIL_LATE_ACK FAIL_HOLD_UNTIL FAIL_NO_INSTANCE FAIL_DISABLED FAIL_REQUEST_GONE; do
    setup_qdb
    case "$v" in
      FAIL_LATE_ACK) expect="整合時刻の後に ack" ;;
      FAIL_HOLD_UNTIL) expect="hold 期限" ;;
      FAIL_NO_INSTANCE) expect="instance がありません" ;;
      FAIL_DISABLED) expect="停止口" ;;
      FAIL_REQUEST_GONE) expect="既に解かれています" ;;
    esac
    { verdict_line "$v" 1; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1 1; } > "$T/qdb/verify-check.out"
    run_job CHECKPOINT acquire verify release
    assert_contains "$JOB_ERR" "step:verify=1" "verify の $v で失敗する"
    assert_contains "$JOB_ERR" "$expect" "verify の $v の理由"
    assert_contains "$(call_params release)" "status = 'ABORTED'; reason = 'VERIFY_FAILED';" "verify の $v の後は VERIFY_FAILED で解く"
    assert_contains "$(call_params verify-check)" "consistency_epoch_us = 1763769606000000;" \
      "verify は acquire の整合時刻（epoch の μ秒）で評価する"
    teardown_qdb
  done
}

case_verify_waits_for_reobservation_then_fails() {
  setup_qdb
  { verdict_line WAIT 1; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1 0; } > "$T/qdb/verify-check.out"
  run_job CHECKPOINT acquire verify release
  assert_contains "$JOB_ERR" "step:verify=1" "観測し直しが欠ければ verify は失敗する"
  assert_contains "$JOB_ERR" "QUIESCE_VERIFY_OBSERVE_SECONDS" "観測し直しの待ちの上限を示す"
  if (( $(call_count verify-check) >= 2 )); then
    test_assert "観測し直しを待つ間は評価を繰り返す"
  else
    test_fail "観測し直しを待つ間は評価を繰り返す" "verify-check $(call_count verify-check) 回"
  fi
  teardown_qdb
}

case_verify_accepts_instance_registered_after_request() {
  setup_qdb
  # 要求の後に登録した instance（要求の前からではない）が checkpoint の最中に後から ack する
  { verdict_line WAIT 2; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1,10.0.0.12=1 1;
    instance_line "$INSTANCE_B" REQUIRED 1 0 - - 0 STARTING 10.0.0.12; } > "$T/qdb/verify-check.1.out"
  { verdict_line OK 2; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1,10.0.0.12=1 1;
    instance_line "$INSTANCE_B" REQUIRED 1 0 QUIESCED 10.0.0.11=1,10.0.0.12=1 1 STARTING 10.0.0.12; } > "$T/qdb/verify-check.2.out"
  run_job CHECKPOINT acquire verify release
  assert_contains "$JOB_ERR" "step:verify=0" "要求の後に登録した instance の後からの ack は受け入れる"
  assert_eq 0 "$JOB_RC" "verify の後の解除も成功する"
  teardown_qdb
}

case_verify_rejects_foreign_host() {
  setup_qdb
  { verdict_line OK 1; instance_line "$INSTANCE_A" REQUIRED 1 1 QUIESCED 10.0.0.11=1,fe80::99=2 1; } > "$T/qdb/verify-check.out"
  run_job CHECKPOINT acquire verify release
  assert_contains "$JOB_ERR" "step:verify=1" "観測し直した接続元が ack した instance の接続元と違えば失敗する"
  assert_contains "$JOB_ERR" "fe80::99" "外れた接続元（IPv6 の : を切らない）を示す"
  teardown_qdb
}

case_verify_request_already_released_fails() {
  setup_qdb
  printf 'VERIFY_REQUESTED\t0\n' > "$T/qdb/verify-request.out"
  run_job FULL acquire verify release
  assert_contains "$JOB_ERR" "step:verify=1" "要求が既に解かれていれば verify は失敗する"
  assert_eq 0 "$(call_count verify-check)" "verify_requested_at を書けなければ評価しない"
  teardown_qdb
}

# ------------------------------------------------------------ 保持 process: lease の延長

case_hold_keeps_renewing_after_acquire_returns() {
  setup_qdb
  run_job CHECKPOINT acquire sleep:4 release
  assert_eq 0 "$JOB_RC" "取得の後に待って解除する"
  local n poll_ms last_renew_ms
  n=$(call_count renew)
  if (( n >= 2 )); then
    test_assert "acquire の process が終わっても保持 process が lease を延ばし続ける（$n 回）"
  else
    test_fail "acquire の process が終わっても保持 process が lease を延ばし続ける" "renew $n 回"
  fi
  poll_ms=$(awk -F'\t' '$2 == "poll-acks" { print $1 }' "$T/qdb/calls.log" | tail -n 1)
  last_renew_ms=$(awk -F'\t' '$2 == "renew" { print $1 }' "$T/qdb/calls.log" | tail -n 1)
  if [[ -n "$poll_ms" && -n "$last_renew_ms" ]] && (( last_renew_ms > poll_ms )); then
    test_assert "延長は acquire の終わりの後も続く"
  else
    test_fail "延長は acquire の終わりの後も続く" "poll=$poll_ms renew=$last_renew_ms"
  fi
  assert_contains "$(call_params renew)" "lease_max_seconds = 300;" "checkpoint の延長の上限（既定 300 秒）を渡す"
  assert_contains "$(call_params renew)" "request_id = 7;" "延長は登録した要求の番号で行う"
  teardown_qdb
}

case_renew_stopped_fails_verify() {
  setup_qdb
  printf 'RENEW\t0\nLEASE\tABORTED\t0\t0\t300\t2026-11-22T00:01:00.000000Z\n' > "$T/qdb/renew.out"
  run_job CHECKPOINT acquire sleep:3 verify release
  assert_file "$T/state/renew-stopped" "延長できない要求では延長の停止の印を書く"
  assert_contains "$JOB_ERR" "step:verify=1" "延長が止まれば verify は失敗する"
  assert_contains "$JOB_ERR" "延長が止まりました" "延長の停止を理由に示す"
  assert_eq 1 "$(call_count renew)" "延長を止めた後は延ばさない"
  teardown_qdb
}

case_renew_cap_stops_renewal() {
  setup_qdb
  printf 'RENEW\t0\nLEASE\tREQUESTED\t1\t1\t120\t2026-11-22T00:02:00.000000Z\n' > "$T/qdb/renew.out"
  run_job CHECKPOINT acquire sleep:4 verify release
  assert_file "$T/state/renew-capped" "延長の上限に達したら印を書く"
  assert_eq 1 "$(call_count renew)" "延長の上限に達したら延長を止める"
  assert_contains "$(cat "$T/state/hold.log")" "上限" "延長の上限を hold.log に出す"
  assert_contains "$JOB_ERR" "step:verify=0" "上限に達しても lease が有効な間は hold 期限で評価する（verify は SQL の判定による）"
  teardown_qdb
}

case_ddl_session_lost_fails_verify() {
  setup_qdb
  export FAKE_QDB_LOCK_DIE_AFTER=1
  run_job CHECKPOINT acquire sleep:3 verify release
  assert_file "$T/state/ddl-lock.lost" "DDL lock の session を失ったら印を書く"
  assert_contains "$JOB_ERR" "step:verify=1" "DDL lock を失った静止区間は VALID にしない"
  unset FAKE_QDB_LOCK_DIE_AFTER
  teardown_qdb
}

# ------------------------------------------------------------ 保持 process: job の SIGKILL

case_kill_job_during_dump_ends_hold_and_returns_lock() {
  setup_qdb
  export FAKE_DUMP_SLEEP=30 FAKE_DUMP_PID_FILE="$T/dump.pid"
  "$T/job.sh" CHECKPOINT acquire dump > "$T/job.out" 2> "$T/job.err" &
  local job=$!
  if wait_file "$T/dump.pid" 15; then
    test_assert "mysqldump（fake）が動いている"
  else
    test_fail "mysqldump（fake）が動いている" "$(cat "$T/job.err")"
  fi
  local hold
  hold=$(cat "$T/state/hold.pid" 2>/dev/null || true)
  kill -9 "$job" 2>/dev/null
  wait "$job" 2>/dev/null
  if pid_gone_within "$hold" 5; then
    test_assert "job を SIGKILL すると mysqldump の最中でも保持 process が終わる"
  else
    test_fail "job を SIGKILL すると mysqldump の最中でも保持 process が終わる" "hold pid=$hold が残っています"
  fi
  if wait_file "$T/qdb/ddl-lock.released" 5; then
    test_assert "保持 process が終わると DDL lock の session が終わる（lock が返る）"
  else
    test_fail "保持 process が終わると DDL lock の session が終わる" "ddl-lock.released がありません"
  fi
  if kill -0 "$(cat "$T/dump.pid")" 2>/dev/null; then
    test_assert "mysqldump は書き口を閉じて起動したため、生きていても保持 process の終わりを妨げない"
  else
    test_fail "mysqldump は生きている（前提）" "fake の mysqldump が先に終わりました"
  fi
  unset FAKE_DUMP_SLEEP FAKE_DUMP_PID_FILE
  kill "$(cat "$T/dump.pid")" 2>/dev/null
  teardown_qdb
}

case_kill_job_during_ddl_lock_wait_ends_hold() {
  setup_qdb
  export FAKE_QDB_LOCK=hang
  "$T/job.sh" CHECKPOINT acquire > "$T/job.out" 2> "$T/job.err" &
  local job=$!
  if wait_file "$T/qdb/ddl-lock.waiting" 10; then
    test_assert "保持 process が DDL lock を待っている"
  else
    test_fail "保持 process が DDL lock を待っている" "$(cat "$T/job.err")"
  fi
  local hold session
  hold=$(cat "$T/state/hold.pid" 2>/dev/null || true)
  session=$(cat "$T/qdb/ddl-lock.pid" 2>/dev/null || true)
  kill -9 "$job" 2>/dev/null
  wait "$job" 2>/dev/null
  if pid_gone_within "$hold" 5; then
    test_assert "DDL lock の取得の待ちの間に job を SIGKILL しても保持 process が終わる"
  else
    test_fail "DDL lock の取得の待ちの間に job を SIGKILL しても保持 process が終わる" "hold pid=$hold が残っています"
  fi
  if pid_gone_within "$session" 5; then
    test_assert "GET_LOCK を待つ session も止める"
  else
    test_fail "GET_LOCK を待つ session も止める" "session pid=$session が残っています"
  fi
  sleep 1
  assert_eq 0 "$(call_count insert-request)" "DDL lock を取れないまま要求を登録しない"
  unset FAKE_QDB_LOCK
  teardown_qdb
}

# ------------------------------------------------------------ ONLINE の full の最中の新しい instance（U-13）

case_new_instance_during_online_full_aborts_request() {
  setup_qdb
  printf 'ABORT\t1\t1\n' > "$T/qdb/full-new-instance.2.out"
  run_job FULL acquire sleep:4 aborted verify release
  assert_file "$T/state/full-aborted" "ONLINE の full の最中の新しい instance で中止の印を書く"
  assert_contains "$JOB_ERR" "step:aborted=1" "quiesce::check_aborted が中止を返す"
  assert_contains "$JOB_ERR" "step:verify=1" "中止した full の verify は失敗する"
  assert_contains "$(cat "$T/state/hold.log")" "errorCode=BACKUP_QUIESCE_FULL_ABORTED" "中止を WARN に出す"
  local renew_after
  renew_after=$(awk -F'\t' '$2 == "full-new-instance" { n++ } n >= 2 && $2 == "renew" { r++ } END { print r + 0 }' "$T/qdb/calls.log")
  assert_eq 0 "$renew_after" "中止した後は lease を延ばさない"
  teardown_qdb
}

case_new_instance_check_only_for_online_full() {
  setup_qdb
  run_job CHECKPOINT acquire sleep:3 release
  assert_eq 0 "$(call_count full-new-instance)" "checkpoint では新しい instance で中止しない"
  teardown_qdb

  # APP_STOPPED の full（配備の前の backup）の要求では中止の確認をしない（D3-P2-2 の負例）。保持 process だけを動かす
  setup_qdb
  cat > "$T/hold-only.sh" <<'HOLD'
#!/usr/bin/env bash
set -Eeuo pipefail
. "$LIB_UNDER_TEST/common.sh"
. "$LIB_UNDER_TEST/quiesce.sh"
QUIESCE_JOB_KIND=FULL
QUIESCE_PROVIDER=quiesce-db
quiesce::_start_hold
for _ in $(seq 1 50); do [[ -f "$QUIESCE_STATE_DIR/ddl-lock.ok" ]] && break; sleep 0.1; done
printf 'request_id=9\nmode=APP_STOPPED\njob_kind=FULL\ntoken=0123456789abcdef0123456789abcdef\nrequested_at_utc=x\nrequested_epoch_us=1\n' \
  > "$QUIESCE_STATE_DIR/request.env"
sleep 4
quiesce::_stop_hold
HOLD
  chmod +x "$T/hold-only.sh"
  "$T/hold-only.sh" > /dev/null 2>&1
  if (( $(call_count renew) >= 1 )); then
    test_assert "APP_STOPPED の full の保持でも lease は延ばす"
  else
    test_fail "APP_STOPPED の full の保持でも lease は延ばす" "renew 0 回"
  fi
  assert_eq 0 "$(call_count full-new-instance)" "APP_STOPPED の full の最中の新しい instance では要求を中止しない"
  teardown_qdb
}

# ------------------------------------------------------------ release の再開の確認

case_release_waits_only_for_fresh_instances() {
  setup_qdb
  { printf 'VERDICT\tWAIT\t2026-11-22T00:00:31Z\t1763769631000000\t1\twaiting=1,down=1\n';
    printf 'ACK\t%s\tQUIESCED\t1\thost-a\nACK\t%s\tQUIESCED\t0\thost-b\n' "$INSTANCE_A" "$INSTANCE_B"; } > "$T/qdb/resume-check.1.out"
  { printf 'VERDICT\tOK\t2026-11-22T00:00:32Z\t1763769632000000\t0\twaiting=0,down=1\n';
    printf 'ACK\t%s\tRESUMED\t1\thost-a\nACK\t%s\tQUIESCED\t0\thost-b\n' "$INSTANCE_A" "$INSTANCE_B"; } > "$T/qdb/resume-check.2.out"
  run_job CHECKPOINT acquire verify release
  assert_eq 0 "$JOB_RC" "heartbeat の新しい instance が RESUMED になれば解除は成功する"
  assert_contains "$JOB_ERR" "WARN: 区間の中で heartbeat が止まった instance" "落ちた instance は待たずに WARN を出す"
  assert_contains "$JOB_ERR" "$INSTANCE_B" "落ちた instance を示す"
  teardown_qdb
}

case_release_exit3_when_resume_unconfirmed() {
  setup_qdb
  { printf 'VERDICT\tWAIT\t2026-11-22T00:00:31Z\t1763769631000000\t1\twaiting=1,down=0\n';
    printf 'ACK\t%s\tQUIESCED\t1\thost-a\n' "$INSTANCE_A"; } > "$T/qdb/resume-check.out"
  run_job CHECKPOINT acquire verify release
  assert_contains "$JOB_ERR" "step:release=3" "再開を確かめられなければ release は 3（重大）"
  assert_contains "$JOB_ERR" "重大: app の再開を確かめられません" "重大の理由を示す"
  assert_file "$T/state/hold.exited" "再開を確かめられなくても保持 process は終わる"
  teardown_qdb
}

case_release_does_not_wait_new_instance_without_ack() {
  setup_qdb
  # U-13 の中止の時: ack の前に要求が解かれた新しい instance は ack の行が無い（SQL の待つ対象に入らない）
  printf 'VERDICT\tOK\t2026-11-22T00:00:31Z\t1763769631000000\t0\twaiting=0,down=0\n' > "$T/qdb/resume-check.out"
  printf 'RELEASE\t0\nREQUEST_STATE\tABORTED\tNEW_INSTANCE\t2026-11-22T00:00:20Z\n' > "$T/qdb/release.out"
  run_job FULL acquire release
  assert_contains "$JOB_ERR" "step:release=0" "ack の行の無い instance を待たず exit 3 を出さない"
  assert_contains "$JOB_ERR" "既に解かれていました（ABORTED、NEW_INSTANCE）" "既に中止された要求はそのまま"
  assert_eq NEW_INSTANCE "$(jq -r .release_reason "$T/state/quiesce.json")" "quiesce.json に中止の理由を残す"
  teardown_qdb
}

# ------------------------------------------------------------ fd の初期化（D4-P3-1）

case_hold_fd_is_initialized_before_acquire_and_for_local() {
  setup_qdb
  local out rc=0
  out=$(QUIESCE_HOLD_FD=1 QUIESCE_PROVIDER=quiesce-local bash -c '
    set -Eeuo pipefail
    . "$LIB_UNDER_TEST/common.sh"
    . "$LIB_UNDER_TEST/quiesce.sh"
    # acquire の前・quiesce-local でも書き口を閉じる指定で長い子 process を起動できる
    sh -c "echo child-ok" {QUIESCE_HOLD_FD}>&-
    [[ "$QUIESCE_HOLD_FD" != 1 ]] && echo "env-ignored"
    [[ -e /proc/$$/fd/$QUIESCE_HOLD_FD ]] && echo "fd-open"' 2>&1) || rc=$?
  assert_eq 0 "$rc" "source の直後に {QUIESCE_HOLD_FD}>&- を付けた起動が ambiguous redirect で失敗しない"
  assert_contains "$out" "child-ok" "子 process の出力（標準出力は閉じない）"
  assert_contains "$out" "env-ignored" "環境変数の QUIESCE_HOLD_FD は使わない（標準出力を閉じない）"
  assert_contains "$out" "fd-open" "QUIESCE_HOLD_FD は開いた fd（/dev/null）"
  teardown_qdb
}

case_bash_version_is_checked() {
  setup_qdb
  local out rc=0
  out=$(bash -c '
    . "$LIB_UNDER_TEST/common.sh"
    . "$LIB_UNDER_TEST/quiesce.sh"
    quiesce::check_provider && echo "provider-ok"' 2>&1) || rc=$?
  assert_contains "$out" "provider-ok" "この bash（${BASH_VERSION}）は 4.4 以上で quiesce-db を使える"
  if (( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) )); then
    test_assert "試験を動かす bash は 4.4 以上（process substitution の wait。tool image は bookworm の bash 5.2）"
  else
    test_fail "試験を動かす bash は 4.4 以上" "$BASH_VERSION"
  fi
  teardown_qdb
}

case_unknown_provider_rejected() {
  setup_qdb
  export QUIESCE_PROVIDER='quiesce-db; rm -rf /'
  run_job CHECKPOINT acquire
  assert_nonzero "$JOB_RC" "未知の provider は拒否する"
  assert_contains "$JOB_ERR" "未知の provider" "未知の provider を示す"
  assert_eq "" "$(cat "$T/qdb/calls.log" 2>/dev/null)" "未知の provider では DB に触れない"
  teardown_qdb
}

run_case case_acquire_verify_release_success
run_case case_release_without_verify_is_aborted
run_case case_requested_by_default_and_format
run_case case_precheck_failures_do_not_register
run_case case_disabled_row_fails_until_cleanup
run_case case_settings_are_validated
run_case case_ddl_lock_conflict_does_not_register
run_case case_poll_failures_abort_request
run_case case_only_instance_stopped_after_registration_fails
run_case case_instance_added_between_precheck_and_insert_must_ack
run_case case_stopped_instance_is_ignored
run_case case_poll_deadline_aborts
run_case case_foreign_observed_host_fails_acquire
run_case case_short_max_hold_warns
run_case case_verify_failures
run_case case_verify_waits_for_reobservation_then_fails
run_case case_verify_accepts_instance_registered_after_request
run_case case_verify_rejects_foreign_host
run_case case_verify_request_already_released_fails
run_case case_hold_keeps_renewing_after_acquire_returns
run_case case_renew_stopped_fails_verify
run_case case_renew_cap_stops_renewal
run_case case_ddl_session_lost_fails_verify
run_case case_kill_job_during_dump_ends_hold_and_returns_lock
run_case case_kill_job_during_ddl_lock_wait_ends_hold
run_case case_new_instance_during_online_full_aborts_request
run_case case_new_instance_check_only_for_online_full
run_case case_release_waits_only_for_fresh_instances
run_case case_release_exit3_when_resume_unconfirmed
run_case case_release_does_not_wait_new_instance_without_ack
run_case case_hold_fd_is_initialized_before_acquire_and_for_local
run_case case_bash_version_is_checked
run_case case_unknown_provider_rejected

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "RB-QUIESCE BWQ-009 quiesce-db（ONLINE）・保持 process・verify・release"
