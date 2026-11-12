#!/usr/bin/env bash
# ACC-OPS-P2-001 / ACC-OPS-P2-003 harness 自己検証（negative self-tests）
#
# 目的: false-green の恒久固定。過去、mysql-options を common.sh 無しで source すると
#   common::trap_add が "command not found" になりつつ init が末尾の return 0 で成功扱いになり、
#   backup unit gate が「エラーを出しつつ SUCCESS」で緑になっていた（ACC-OPS-P2-001）。
# ここでは以下の3系統が必ず非 0 になることを固定する:
#   1. 依存 lib（common.sh）を読み込まずに mysql-options を使う → 非 0
#   2. parse/setup エラー（必須 env 欠落）→ 非 0
#   3. backup コマンド失敗（必須 env 欠落で backup-full 実行）→ 非 0（VALID/SUCCESS を出さない）
#
# 加えて run-integration.sh（PITR integration runner）の失敗前証跡収集を、実 Docker を使わずに
# fixtures/integration-runner/mock-docker.sh で検証する:
#   - Go template の network ID 解析（P1 回帰）、失敗時の runner 終了コード 1 と native 終了コードの診断記録
#   - 全段成功（診断を生成しない）、後半段失敗の stage 記録、コマンド成功・redactor 失敗
#   - Compose partial-up / healthcheck 失敗 / foreign network / foreign container での診断と cleanup 順序
#   - redactor の PEM 種別ごとの END 判定と後続ログ保持、複数・重なり secret、行長/入力上限、先頭+末尾保持
#   - 診断全体 512KiB 上限、診断予算と per-call timeout、awk timeout、secret scan の fail-closed
#   - WORK の許可ルート・祖先 symlink・所有マーカー照合（値比較）、既定 WORK の再実行時退避と保持数、
#     verify-like-ci.ps1 の -CustomPitrWorkDir（一時ディレクトリ外、PS 5.1 / PS 7・CRLF 形式の marker）、
#     所有を失った WORK への chmod 禁止、固定名・project の事前確認で止まる run は退避しないこと、
#     退避分の剪除方針（削除を表示・失敗診断と本 harness 以外のディレクトリは残す）、PID 再利用の扱い
#   - 固定名の事前確認を WORK 判定より前に行うこと（P3-N1）、別の PID 名前空間の前回 run は終了記録が
#     無ければ退避しないこと（P3-N2）、所有マーカーの排他作成（P3-S1）
#   - timeout 不在・GNU 以外の timeout・container 一覧取得失敗・secret ファイル欠落などの欠落入力
#
# 実行方式: case 1〜3 は fresh な bash -c 子プロセスで実行する（source 済み関数は子 bash へ継承されないため、
# common.sh をロードした/しない状態を切り分けられる）。runner の挙動検証は runner を別の bash プロセスとして
# 起動する。redactor / 診断ヘルパーの単体検証は、runner から区間マーカーで切り出した関数をこのシェルへ
# source し、各 case の冒頭で毎回 source し直して大域変数を初期化する。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091  # HERE 経由の source は静的解決できない
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"

RUNNER="$ROOT/ops/backup/tests/run-integration.sh"
MOCK_DOCKER_SRC="$HERE/fixtures/integration-runner/mock-docker.sh"
REAL_CHMOD=$(command -v chmod)
REAL_AWK=$(command -v awk)
REAL_TIMEOUT=$(command -v timeout)
mock_constant() { sed -n "s/^$1=//p" "$MOCK_DOCKER_SRC" | head -n 1; }
MOCK_SRC_ID=$(mock_constant SRC_ID)
MOCK_TGT_ID=$(mock_constant TGT_ID)
MOCK_NET_ID=$(mock_constant NET_ID)
SELFTEST_SRC_PW="selftest-src-secret-7c1d"
SELFTEST_TGT_PW="selftest-tgt-secret-9e4a"
SELFTEST_RUN_TOKEN="0123456789abcdef0123456789abcdef"
RUNNER_RC=0
RUNNER_OUT=""
RUNNER_DOCKER_LOG=""
RUNNER_PATH=""
RUNNER_UNDER_TEST=""
RUNNER_SELF_PID_MARKER_DIR=""
RUNNER_SELF_PID_MARKER_NS=""

# 1) 依存未ロード（common.sh を source しない）で mysql_options::init が非 0 になる
case_dependency_skip_is_nonzero() {
  local snippet='
    set -uo pipefail
    # 意図的に common.sh を source しない（trap_add 未定義）
    . "'"$LIB"'/mysql-options.sh"
    T=$(mktemp -d)
    mkdir -p "$T/capath" "$T/tmp"
    printf "pw\n" > "$T/pw"
    printf "ca\n" > "$T/capath/00000000.0"
    export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_USER=u MYSQL_SSL_CAPATH="$T/capath" TMPDIR="$T/tmp"
    unset MYSQL_PWD
    mysql_options::init
    rc=$?
    # option file を残していないこと（fail-closed は optfile 作成前に返す）
    if ls "$T/tmp"/ses-backup-mysql.* > /dev/null 2>&1; then
      echo "LEFTOVER_OPTFILE" >&2
    fi
    exit "$rc"
  '
  local out code
  out=$(bash -c "$snippet" 2>&1)
  code=$?
  assert_nonzero "$code" "common.sh 未ロードで mysql_options::init が非 0"
  assert_contains "$out" "common.sh" "未ロード理由を通知する"
  assert_not_contains "$out" "LEFTOVER_OPTFILE" "fail-closed 時に option file を残さない"
}

# 2) parse/setup エラー（必須 env 欠落）で mysql_options::init が非 0 になる
case_setup_error_is_nonzero() {
  local snippet='
    set -uo pipefail
    . "'"$LIB"'/common.sh"
    . "'"$LIB"'/mysql-options.sh"
    unset MYSQL_PWD MYSQL_PASSWORD_FILE
    export MYSQL_USER=u
    mysql_options::init
  '
  local out code
  out=$(bash -c "$snippet" 2>&1)
  code=$?
  assert_nonzero "$code" "必須 env 欠落で mysql_options::init が非 0"
  assert_contains "$out" "MYSQL_PASSWORD_FILE" "未設定理由を通知する"
}

# 3) backup コマンド失敗（必須 env 欠落で backup-full）で非 0・成功文字列を出さない
case_failing_backup_command_is_nonzero() {
  local out code
  # 必須 env を与えず実行する。common::require_env → common::fail で早期に exit 1。
  out=$(env -u BACKUP_REPOSITORY -u MYSQL_HOST -u MYSQL_USER -u MYSQL_PASSWORD_FILE \
    -u MYSQL_DATABASE -u BACKUP_WORK_DIR -u UPLOADS_DIR \
    bash "$ROOT/ops/backup/backup-full.sh" 2>&1)
  code=$?
  assert_nonzero "$code" "必須 env 欠落で backup-full が非 0"
  assert_not_contains "$out" '"status": "VALID"' "失敗時に VALID を出さない"
  assert_not_contains "$out" "SUCCESS" "失敗時に SUCCESS を出さない"
}

# 4) integration evidence はホスト取得に必要な範囲だけを実行ユーザーへ開き、
#    作業ディレクトリ全体や evidence を全員書き込みにはしない。
case_integration_runner_keeps_work_private() {
  local runner content
  runner="$ROOT/ops/backup/tests/run-integration.sh"
  content=$(cat "$runner")
  assert_contains "$content" "umask 077" "integration runner が機密ファイルを初期化時から非公開にする"
  assert_not_contains "$content" 'chmod -R 777 "$WORK"' "作業ディレクトリ全体を 777 にしない"
  assert_not_contains "$content" "chmod -R a+rwX" "全員書き込みの evidence 権限を使わない"
  assert_contains "$content" "normalize_work_permissions" "終了時に root 作成ファイルを安全に掃除できる"
}

# 5) 既存の work path は foreign として扱い、Docker cleanup / 権限変更をしない。
case_integration_foreign_work_preflight_is_read_only() {
  local temp fake_bin work docker_log chmod_log runner_rc runner_out
  temp=$(mktemp -d)
  fake_bin="$temp/bin"
  work="$temp/foreign-work"
  mkdir -p "$fake_bin" "$work"
  printf 'preserve me\n' > "$work/owner-data"
  docker_log="$temp/docker.log"
  chmod_log="$temp/chmod.log"
  cat > "$fake_bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
case "$*" in
  info|"compose version") exit 0 ;;
  # 固定名の事前確認（読み取り専用の docker ps）は WORK 判定より前に行われる。衝突なしとして応答する。
  "ps -aq --filter "*) exit 0 ;;
  *) exit 91 ;;
esac
MOCK
  cat > "$fake_bin/cygpath" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "${*: -1}"
MOCK
  cat > "$fake_bin/chmod" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_CHMOD_LOG"
exit 0
MOCK
  chmod +x "$fake_bin/docker" "$fake_bin/cygpath" "$fake_bin/chmod"
  MOCK_DOCKER_LOG="$docker_log" MOCK_CHMOD_LOG="$chmod_log" \
    PATH="$fake_bin:$PATH" INTEGRATION_WORK_DIR="$work" \
    bash "$ROOT/ops/backup/tests/run-integration.sh" > "$temp/out" 2>&1
  runner_rc=$?
  runner_out=$(cat "$temp/out")
  assert_nonzero "$runner_rc" "既存 work path は preflight で拒否"
  assert_contains "$runner_out" "existing contents are preserved" "既存データ保持を通知"
  assert_not_contains "$(cat "$docker_log")" "down" "foreign work preflight で compose down を呼ばない"
  assert_no_file "$chmod_log" "foreign work path に chmod を行わない"
  assert_contains "$(cat "$work/owner-data")" "preserve me" "foreign work の既存ファイルを保持"
  rm -rf "$temp"
}

# ------------------------------------------------------------
# run-integration.sh を mock docker で実行するための共通ヘルパー
# ------------------------------------------------------------
runner_mock_init() { # <temp>
  local temp=$1
  mkdir -p "$temp/bin" "$temp/state" "$temp/allowed"
  cp "$MOCK_DOCKER_SRC" "$temp/bin/docker"
  cat > "$temp/bin/cygpath" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "${*: -1}"
MOCK
  cat > "$temp/bin/chmod" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_CHMOD_LOG"
if [[ -n "${MOCK_REAL_CHMOD:-}" ]]; then exec "$MOCK_REAL_CHMOD" "$@"; fi
exit 0
MOCK
  "$REAL_CHMOD" +x "$temp/bin/docker" "$temp/bin/cygpath" "$temp/bin/chmod"
}

# usage: runner_mock_run <temp> <scenario> <work|-> [VAR=value ...]
#   work に "-" を渡すと INTEGRATION_WORK_DIR を未設定にする（既定パスの検証用）。
#   許可ルートは既定で <temp>/allowed（TMPDIR/TMP/TEMP）。追加の VAR=value で上書きできる。
#   RUNNER_SELF_PID_MARKER_DIR を設定すると、runner 自身の PID（exec で引き継ぐ）を run= に持つ
#   所有マーカーをそのディレクトリへ書いてから起動する（PID 再利用＝旧 PID が自分自身の検証用）。
#   RUNNER_SELF_PID_MARKER_NS を併せて設定すると、そのマーカーに pidns= 行を加える。
runner_mock_run() {
  local temp=$1 scenario=$2 work=$3 runner=${RUNNER_UNDER_TEST:-$RUNNER}
  local -a work_env env_args
  shift 3
  rm -rf "$temp/state"
  mkdir -p "$temp/state"
  : > "$temp/docker.log"
  rm -f "$temp/chmod.log"
  if [[ "$work" == "-" ]]; then
    work_env=(-u INTEGRATION_WORK_DIR)
  else
    work_env=(INTEGRATION_WORK_DIR="$work")
  fi
  env_args=("${work_env[@]}"
    MOCK_DOCKER_LOG="$temp/docker.log" MOCK_STATE_DIR="$temp/state" MOCK_SCENARIO="$scenario"
    MOCK_CHMOD_LOG="$temp/chmod.log" MOCK_REAL_CHMOD="$REAL_CHMOD" MOCK_REAL_AWK="$REAL_AWK"
    INTEGRATION_SRC_PW="$SELFTEST_SRC_PW" INTEGRATION_TGT_PW="$SELFTEST_TGT_PW"
    TMPDIR="$temp/allowed" TMP="$temp/allowed" TEMP="$temp/allowed"
    PATH="${RUNNER_PATH:-$temp/bin:$PATH}"
    "$@")
  if [[ -n "${RUNNER_SELF_PID_MARKER_DIR:-}" ]]; then
    (
      # この subshell の PID は exec 後の env → bash（runner）にそのまま引き継がれる
      printf 'run=1700000000-%s-1\nwork=%s\nproject=selftest\n' "$BASHPID" "$RUNNER_SELF_PID_MARKER_DIR" \
        > "$RUNNER_SELF_PID_MARKER_DIR/.ses-pitr-run-owner"
      if [[ -n "${RUNNER_SELF_PID_MARKER_NS:-}" ]]; then
        printf 'pidns=%s\n' "$RUNNER_SELF_PID_MARKER_NS" >> "$RUNNER_SELF_PID_MARKER_DIR/.ses-pitr-run-owner"
      fi
      printf '%s\n' "$BASHPID" > "$temp/self-pid"
      exec env "${env_args[@]}" bash "$runner"
    ) > "$temp/out" 2>&1
  else
    env "${env_args[@]}" bash "$runner" > "$temp/out" 2>&1
  fi
  RUNNER_RC=$?
  RUNNER_OUT=$(cat "$temp/out")
  RUNNER_DOCKER_LOG=$(cat "$temp/docker.log")
}

first_line_no() { # <file> <fixed-string>
  grep -n -F -m 1 -- "$2" "$1" 2>/dev/null | cut -d: -f1
}

last_line_no() { # <file> <fixed-string>
  grep -n -F -- "$2" "$1" 2>/dev/null | tail -n 1 | cut -d: -f1
}

assert_before() { # <earlier_line> <later_line> <label>
  local earlier=$1 later=$2 label=$3
  if [[ "$earlier" =~ ^[0-9]+$ && "$later" =~ ^[0-9]+$ ]] && (( earlier < later )); then
    test_assert "$label"
  else
    test_fail "$label" "順序が不正です earlier=[$earlier] later=[$later]"
  fi
}

assert_le() { # <actual> <max> <label>
  local actual=$1 max=$2 label=$3
  if [[ "$actual" =~ ^[0-9]+$ ]] && (( actual <= max )); then
    test_assert "$label"
  else
    test_fail "$label" "actual=[$actual] max=[$max]"
  fi
}

assert_no_secret_leak() { # <text> <label>
  local text=$1 label=$2
  assert_not_contains "$text" "$SELFTEST_SRC_PW" "$label: source password を出さない"
  assert_not_contains "$text" "$SELFTEST_TGT_PW" "$label: target password を出さない"
  assert_not_contains "$text" "restore-svc-pw" "$label: restore-svc password を出さない"
  assert_not_contains "$text" "-----BEGIN" "$label: PEM BEGIN marker を出さない"
}

file_bytes() { # <file>
  local bytes
  bytes=$(wc -c < "$1" 2>/dev/null) || bytes=""
  printf '%s\n' "${bytes//[[:space:]]/}"
}

# verify-like-ci.ps1（New-ControlledPitrWorkDir → Write-JsonStateFile → ConvertTo-Json）と同じ形の
# .ses-pitr-owner.json を書く。値はそのまま（JSON エスケープ済みの文字列として）埋め込む。
write_controller_marker() { # <parent> <workDir 値> <parentDir 値> [runToken]
  local parent=$1 work_value=$2 parent_value=$3 token=${4:-$SELFTEST_RUN_TOKEN}
  printf '{\n    "pid":  4242,\n    "createdAt":  "2026-11-12T00:00:00.0000000+09:00",\n    "runToken":  "%s",\n    "repoRoot":  "%s",\n    "parentDir":  "%s",\n    "workDir":  "%s"\n}\n' \
    "$token" "$ROOT" "$parent_value" "$work_value" > "$parent/.ses-pitr-owner.json"
}

# PowerShell 7 の ConvertTo-Json（2 空白インデント・"k": "v"）を Windows の CRLF で書いた形。
write_controller_marker_ps7_crlf() { # <parent> <workDir 値> <parentDir 値>
  local parent=$1 work_value=$2 parent_value=$3
  printf '{\r\n  "pid": 4242,\r\n  "createdAt": "2026-11-12T00:00:00.0000000+09:00",\r\n  "runToken": "%s",\r\n  "repoRoot": "%s",\r\n  "parentDir": "%s",\r\n  "workDir": "%s"\r\n}\r\n' \
    "$SELFTEST_RUN_TOKEN" "$ROOT" "$parent_value" "$work_value" > "$parent/.ses-pitr-owner.json"
}

# 既定 WORK を使う runner の複製を <temp>/<name>/ops/backup/tests に置き、RUNNER_UNDER_TEST に設定する。
setup_copied_runner() { # <temp> <name> → stdout: tests ディレクトリ
  local tests_dir="$1/$2/ops/backup/tests"
  mkdir -p "$tests_dir"
  cp "$RUNNER" "$tests_dir/run-integration.sh"
  printf '%s\n' "$tests_dir"
}

marker_run_id() { # <dir> → stdout: 所有マーカーの run=
  sed -n 's/^run=//p' "$1/.ses-pitr-run-owner" 2>/dev/null
}

marker_pidns() { # <dir> → stdout: 所有マーカーの pidns=
  sed -n 's/^pidns=//p' "$1/.ses-pitr-run-owner" 2>/dev/null
}

# runner 自身が所有マーカーへ書く pidns=（この環境の PID 名前空間の識別子）を、temp モードの実 run から得る。
runner_runtime_ns() { # <temp> → stdout: pidns=
  local temp=$1 probe="$1/allowed/ns-probe-$RANDOM" saved_runner=${RUNNER_UNDER_TEST:-}
  RUNNER_UNDER_TEST=""
  runner_mock_run "$temp" up-fail "$probe"
  RUNNER_UNDER_TEST=$saved_runner
  marker_pidns "$probe"
}

# 6) 失敗証跡フローの静的契約（挙動は以降の mock シナリオで検証する）。
case_integration_failure_capture_contract() {
  local runner
  runner=$(cat "$ROOT/ops/backup/tests/run-integration.sh")
  assert_contains "$runner" "upAttempted=1" "compose up 前に所有状態を記録"
  assert_contains "$runner" "count <= 5" "部分起動の診断対象を5サービス以下に制限"
  assert_contains "$runner" 'for service in target source tool scheduler replica' "targetを先に診断"
  assert_contains "$runner" '-k 1s' "Docker診断に終了猶予付きtimeoutを設定"
  assert_contains "$runner" 'GNU coreutils' "GNU coreutils の timeout だけを採用"
  assert_not_contains "$runner" 'EPOCHREALTIME' "壁時計EPOCHREALTIMEで硬い期限を主張しない"
  assert_contains "$runner" '{{println}}' "Go templateの改行は{{println}}で出力"
  assert_not_contains "$runner" '\\n' "Go template等に二重バックスラッシュの改行エスケープを残さない（P1）"
  assert_contains "$runner" 'fflush()' "redactorの端末出力を逐次flush"
  assert_contains "$runner" 'FAILURE_NATIVE_RC=$PITR_RC' "PITRのnative終了コードを診断用に保存"
  assert_contains "$runner" 'FAILURE_NATIVE_RC=$DRILL_RC' "drillのnative終了コードを診断用に保存"
  assert_not_contains "$runner" 'exit "$UP_RC"' "compose upのnative終了コードを透過しない（失敗は1）"
  assert_not_contains "$runner" 'exit "$WAIT_RC"' "waitのnative終了コードを透過しない（失敗は1）"
  assert_not_contains "$runner" 'exit "$PITR_RC"' "PITRのnative終了コードを透過しない（失敗は1）"
  assert_not_contains "$runner" 'exit "$DRILL_RC"' "drillのnative終了コードを透過しない（BLOCKED=2との衝突回避）"
  assert_contains "$runner" 'command_status=("${PIPESTATUS[@]}")' "診断パイプ直後に両終了コードを取得"
  assert_not_contains "$runner" 'ses-backup-integration_ses-bkup' "固定network名へのfallbackを残さない"
  assert_contains "$runner" 'PROJECT_OWNED && upAttempted' "未所有projectをcleanupしない"
  assert_not_contains "$runner" 'mkdir -p "$WORK"' "WORKの祖先をmkdir -pで作らない"
  assert_not_contains "$runner" 'service_container_id' "未使用関数を残さない"
  assert_not_contains "$runner" '.bytes' "旧設計の .bytes 後始末を残さない"
  assert_contains "$runner" 'mv -T --' "既定WORKの退避は退避先へ入り込まない mv -T で行う"
  assert_contains "$runner" 'uutils coreutils' "uutils coreutils の timeout も採用する"
  assert_contains "$runner" 'ringStart = 0' "リングバッファの添字を明示的に初期化する"
  assert_contains "$runner" 'BASH_VERSINFO' "bash 4.4 未満を BLOCKED にする前提検査を持つ"
  assert_contains "$runner" 'WORK_PLAN_EXISTED' "判定時に WORK が存在したかを記録して準備時の排他作成に使う"
  assert_before "$(first_line_no "$RUNNER" 'if ! check_fixed_container_names; then')" \
    "$(first_line_no "$RUNNER" 'if ! prepare_work_dir; then')" "固定名の事前確認をWORKの作成・退避より前に行う"
  assert_before "$(first_line_no "$RUNNER" 'if ! check_fixed_container_names; then')" \
    "$(first_line_no "$RUNNER" 'if ! validate_work_path; then')" "固定名の事前確認をWORKの判定（前回runの生存判定）より前に行う（P3-N1）"
  assert_contains "$runner" 'set -o noclobber' "所有マーカーと合成secretはnoclobber（O_EXCL）で排他作成する（P3-S1）"
  assert_not_contains "$runner" '> "$WORK/.ses-pitr-run-owner"' "所有マーカーを追従・上書きするリダイレクトで書かない（P3-S1）"
  assert_contains "$runner" 'pidns=%s' "所有マーカーにPID名前空間の識別子を記録する（P3-N2）"
  assert_contains "$runner" '.ses-pitr-run-ended' "終了処理の最後に終了記録を残す（P3-N2）"
}

# ------------------------------------------------------------
# redactor / 診断ヘルパーの単体検証（runner から区間マーカーで切り出して source する）
# ------------------------------------------------------------
load_runner_diag_helpers() { # <temp>
  local helper_file="$1/runner-diag-helpers.sh"
  sed -n '/^# >>> harness-selftest: diagnostics helpers >>>$/,/^# <<< harness-selftest: diagnostics helpers <<<$/p' \
    "$RUNNER" > "$helper_file"
  if ! grep -q '^redact_stream_to_file() {' "$helper_file" || ! grep -q '^scan_failure_diagnostics() {' "$helper_file"; then
    test_fail "runner診断ヘルパーの切り出し" "区間マーカーまたは関数が見つかりません"
    return 1
  fi
  # shellcheck disable=SC1090
  . "$helper_file"
  init_diag_limits
}

write_redactor_secrets() { # <work>
  local work=$1
  mkdir -p "$work/keys"
  printf 'src-secret-unique\n' > "$work/src-password"
  printf 'tgt-secret-unique\n' > "$work/tgt-password"
  printf 'repo-secret-unique\n' > "$work/repo-password"
  printf 'restore-secret-unique\n' > "$work/restore-svc-pw"
  printf '%s\n' '-----BEGIN RSA PRIVATE KEY-----' 'key-material-unique' '-----END RSA PRIVATE KEY-----' > "$work/keys/priv1.pem"
  : > "$work/keys/priv2.pem"
}

# 7) PEM の END は BEGIN と同じ種別で判定し、END 後の通常ログ（同一行・後続行）を失わない。
case_integration_redactor_pem_markers_keep_suffix_logs() {
  local temp work result rc tail body
  temp=$(mktemp -d)
  work="$temp/work"
  load_runner_diag_helpers "$temp" || { rm -rf "$temp"; return 0; }
  write_redactor_secrets "$work"
  WORK_AWK="$work"
  printf '%s\n' \
    'before -----BEGIN RSA PRIVATE KEY-----rsa-inline-body-unique-----END RSA PRIVATE KEY----- rsa-inline-tail' \
    '-----BEGIN EC PRIVATE KEY-----' 'ec-key-body-unique' '-----END EC PRIVATE KEY----- ec-same-line-tail' \
    'normal-log-after-ec-block' \
    'x -----BEGIN ENCRYPTED PRIVATE KEY-----enc-body-unique-----END ENCRYPTED PRIVATE KEY----- enc-tail' \
    '-----BEGIN OPENSSH PRIVATE KEY-----' 'openssh-body-unique' '-----END OPENSSH PRIVATE KEY-----' \
    'normal-log-after-openssh' \
    '-----BEGIN DSA PRIVATE KEY-----dsa-body-unique-----END DSA PRIVATE KEY-----dsa-tail' \
    '-----BEGIN PRIVATE KEY-----pkcs8-body-unique-----END PRIVATE KEY----- pkcs8-tail' \
    '-----BEGIN PGP PRIVATE KEY BLOCK-----' 'pgp-body-unique' '-----END PGP PRIVATE KEY BLOCK----- pgp-tail' \
    '-----BEGIN RSA PRIVATE KEY-----' 'mismatch-body-1-unique' '-----END EC PRIVATE KEY-----' 'mismatch-body-2-unique' \
    '-----END RSA PRIVATE KEY----- after-mismatch-close' \
    'final-normal-line' \
    '-----BEGIN OPENSSH PRIVATE KEY-----' 'unclosed-key-unique' 'unclosed-follow-unique' \
    | redact_stream_to_file "$temp/pem.txt" 65536 > "$temp/pem.stdout" 2> "$temp/pem.err"
  rc=$?
  result=$(cat "$temp/pem.txt")
  assert_zero "$rc" "redactor はPEM複数形式を処理"
  assert_contains "$result" "before [PRIVATE KEY BLOCK REDACTED] rsa-inline-tail" "END RSA PRIVATE KEY を END RSA に削らず同一行の後続を保持"
  for tail in ec-same-line-tail normal-log-after-ec-block enc-tail normal-log-after-openssh dsa-tail pkcs8-tail pgp-tail after-mismatch-close final-normal-line; do
    assert_contains "$result" "$tail" "PEMブロック後の通常ログを保持: $tail"
  done
  for body in rsa-inline-body-unique ec-key-body-unique enc-body-unique openssh-body-unique dsa-body-unique \
      pkcs8-body-unique pgp-body-unique mismatch-body-1-unique mismatch-body-2-unique unclosed-key-unique unclosed-follow-unique; do
    assert_not_contains "$result" "$body" "秘密鍵本文・未閉鎖ブロック後続を出さない: $body"
  done
  assert_not_contains "$result" "-----BEGIN" "PEM BEGIN marker を残さない"
  assert_eq "$result" "$(cat "$temp/pem.stdout")" "コンソール出力もファイルと同じ伏字化済み内容"
  rm -rf "$temp"
}

# 8) 1 行内の複数 secret（隣接・反復・重なり）をすべて伏字化し、残片を出さない。
case_integration_redactor_multi_substring_and_overlap() {
  local temp work result rc
  temp=$(mktemp -d)
  work="$temp/work"
  load_runner_diag_helpers "$temp" || { rm -rf "$temp"; return 0; }
  write_redactor_secrets "$work"
  WORK_AWK="$work"
  printf '%s\n' 'A=src-secret-unique B=tgt-secret-unique C=src-secret-uniquesrc-secret-unique D=restore-secret-unique E=key-material-unique F=repo-secret-unique end' \
    | redact_stream_to_file "$temp/multi.txt" 65536 > /dev/null 2>&1
  rc=$?
  result=$(cat "$temp/multi.txt")
  assert_zero "$rc" "複数secretの行を処理"
  assert_eq 'A=[REDACTED] B=[REDACTED] C=[REDACTED] D=[REDACTED] E=[REDACTED] F=[REDACTED] end' "$result" \
    "1行内の複数secret（隣接・反復を含む）をすべて伏字化"

  printf 'overlap-alpha-beta\n' > "$work/src-password"
  printf 'beta-gamma-unique\n' > "$work/tgt-password"
  printf 'abab-abab\n' > "$work/restore-svc-pw"
  printf '%s\n' 'xx overlap-alpha-beta-gamma-unique yy' 'zz abab-abab-abab ww' \
    | redact_stream_to_file "$temp/overlap.txt" 65536 > /dev/null 2>&1
  rc=$?
  result=$(cat "$temp/overlap.txt")
  assert_zero "$rc" "重なりsecretの行を処理"
  assert_eq $'xx [REDACTED] yy\nzz [REDACTED] ww' "$result" "重なり合うsecretを区間併合して伏字化"
  assert_not_contains "$result" "gamma" "重なりの後半secret残片を出さない"
  assert_not_contains "$result" "abab" "同一secretの重なり出現の残片を出さない"
  rm -rf "$temp"
}

# 9) 行長上限・先頭+末尾保持・端末への逐次出力・入力上限・上流終了コード保持・欠落入力・awk 失敗/timeout。
case_integration_redactor_bounds_and_fails_closed() {
  local temp work result stdout_text rc long start elapsed saved_timeout
  local -a status
  temp=$(mktemp -d)
  work="$temp/work"
  load_runner_diag_helpers "$temp" || { rm -rf "$temp"; return 0; }
  write_redactor_secrets "$work"
  WORK_AWK="$work"
  mkdir -p "$temp/fake-bin" "$temp/hang-bin"

  head -c 70000 /dev/zero | tr '\000' A | redact_stream_to_file "$temp/large.txt" 65536 > /dev/null 2> "$temp/large.err"
  rc=$?
  result=$(cat "$temp/large.txt")
  assert_zero "$rc" "巨大な単一行でもredactorが成功"
  assert_le "$(file_bytes "$temp/large.txt")" 65536 "1 section の保存を64KiB以内に制限"
  assert_contains "$result" "LINE LENGTH LIMIT REACHED" "行長上限で止めた位置に理由付きmarkerを残す"
  assert_contains "$result" "[OUTPUT TRUNCATED:" "先頭と末尾の間の省略をmarkerで明示"
  assert_contains "$(cat "$temp/large.err")" "LINE LENGTH LIMIT" "行長上限到達をstderrにも通知"

  long=$(head -c 4090 /dev/zero | tr '\000' B)
  { printf '%s%s%s\n' "$long" 'src-secret-unique' 'CCCC'; printf '%s\n' 'after-long-line-unique'; } \
    | redact_stream_to_file "$temp/linecap.txt" 4096 > "$temp/linecap.stdout" 2> "$temp/linecap.err"
  rc=$?
  result=$(cat "$temp/linecap.txt")
  stdout_text=$(cat "$temp/linecap.stdout")
  assert_zero "$rc" "行長上限を超える行を処理"
  assert_not_contains "$result" "src-se" "行長上限で切れたsecret接頭辞をファイルへ出さない"
  assert_not_contains "$stdout_text" "src-se" "行長上限で切れたsecret接頭辞を端末へ出さない"
  assert_not_contains "$result$stdout_text" "after-long-line-unique" "行長上限超過後は以降を出さない（切捨部のPEM状態不明のためfail-closed）"
  assert_contains "$result" "LINE LENGTH LIMIT REACHED" "行長上限の停止理由をファイルに残す"
  assert_le "$(file_bytes "$temp/linecap.txt")" 4096 "行長上限ケースでも保存上限を守る"

  { printf 'BBB%s%s\n' 'src-secret-unique' "$(head -c 5000 /dev/zero | tr '\000' C)"; } \
    | redact_stream_to_file "$temp/linecap2.txt" 4096 > /dev/null 2>&1
  result=$(cat "$temp/linecap2.txt")
  assert_contains "$result" "BBB[REDACTED]CCC" "行長上限内のsecretは全体を伏字化"
  assert_not_contains "$result" "src-secret-unique" "長い行のsecretを出さない"

  {
    for ((i = 1; i <= 400; i++)); do printf 'line-%04d-padding-padding-padding\n' "$i"; done
    printf '%s\n' 'FINAL-FAILURE-LINE-unique src=src-secret-unique'
  } | redact_stream_to_file "$temp/tail.txt" 4096 > "$temp/tail.stdout" 2> "$temp/tail.err"
  rc=$?
  result=$(cat "$temp/tail.txt")
  stdout_text=$(cat "$temp/tail.stdout")
  assert_zero "$rc" "保存上限を超える出力でもredactorは成功"
  assert_contains "$result" "line-0001-" "保存ファイルに先頭を残す"
  assert_contains "$result" "[OUTPUT TRUNCATED:" "保存ファイルで省略位置を明示"
  assert_contains "$result" "FINAL-FAILURE-LINE-unique src=[REDACTED]" "保存ファイルに末尾（失敗行）を伏字化して残す"
  assert_not_contains "$result" "line-0200-" "保存ファイルは中間を省略する"
  assert_le "$(file_bytes "$temp/tail.txt")" 4096 "先頭+末尾の合計を保存上限以内にする"
  assert_contains "$stdout_text" "line-0200-" "端末へは上限なく全行を流す"
  assert_contains "$stdout_text" "FINAL-FAILURE-LINE-unique src=[REDACTED]" "端末出力も伏字化済み"
  assert_not_contains "$stdout_text" "src-secret-unique" "端末出力にsecretを出さない"
  assert_contains "$(cat "$temp/tail.err")" "SECTION TRUNCATED" "保存の省略をstderrで通知"

  # 先頭容量（1024B）を超えるが先頭+末尾容量（1024+2816B）に収まる出力: 34 行目が先頭と末尾に分かれ、
  # 末尾リングの最初の要素になる。省略が無いので保存内容は端末出力と完全に一致しなければならない。
  for ((i = 1; i <= 70; i++)); do printf 'ring-line-%04d-padding-padding\n' "$i"; done \
    | redact_stream_to_file "$temp/ring.txt" 4096 > "$temp/ring.stdout" 2> "$temp/ring.err"
  rc=$?
  result=$(cat "$temp/ring.txt")
  assert_zero "$rc" "先頭+末尾容量内の出力でもredactorは成功"
  assert_eq 2170 "$(file_bytes "$temp/ring.stdout")" "端末へ70行（2170B）を出力（前提）"
  assert_eq "$(cat "$temp/ring.stdout")" "$result" "省略なしのときは先頭+末尾の保存内容が全出力と一致（リング先頭要素を失わない）"
  assert_contains "$result" $'ring-line-0033-padding-padding\nring-line-0034-padding-padding\nring-line-0035-' "先頭と末尾の境界で分割された行を復元"
  assert_not_contains "$result" "[OUTPUT TRUNCATED" "省略が無いときは省略markerを書かない"

  diag_budget_start
  {
    printf '%s\n' '-----BEGIN RSA PRIVATE KEY-----'
    for ((i = 0; i < 600; i++)); do printf 'kkkkkkkkkk\n'; done
    printf '%s\n' '-----END RSA PRIVATE KEY-----' 'after-input-cap-unique'
    head -c 2000000 /dev/zero | tr '\000' y
    printf '\nlast\n'
    exit 5
  } | redact_stream_to_file "$temp/inputcap.txt" 1024 bounded > "$temp/inputcap.stdout" 2> "$temp/inputcap.err"
  status=("${PIPESTATUS[@]}")
  assert_eq 5 "${status[0]:-}" "入力上限後も読み捨てて上流の終了コードを保つ（SIGPIPEにしない）"
  assert_zero "${status[1]:-1}" "入力上限到達でもredactor自体は成功"
  assert_not_contains "$(cat "$temp/inputcap.txt")" "after-input-cap-unique" "入力上限後の内容を処理しない"
  assert_contains "$(cat "$temp/inputcap.txt")" "INPUT LIMIT REACHED" "入力上限の停止位置をファイルに残す"
  assert_contains "$(cat "$temp/inputcap.err")" "INPUT LIMIT" "入力上限到達を通知"
  assert_eq "" "$(cat "$temp/inputcap.stdout")" "診断用（bounded）は端末へ出さない"

  saved_timeout=$DIAG_TIMEOUT_BIN
  DIAG_TIMEOUT_BIN=""
  { head -c 200000 /dev/zero | tr '\000' q; printf '\n'; exit 8; } \
    | redact_stream_to_file "$temp/no-timeout.txt" 1024 bounded > /dev/null 2>&1
  status=("${PIPESTATUS[@]}")
  DIAG_TIMEOUT_BIN=$saved_timeout
  assert_eq 8 "${status[0]:-}" "timeout不在で早期returnしても標準入力を読み捨てる"
  assert_nonzero "${status[1]:-0}" "timeout不在のbounded redactorは失敗扱い"

  rm -f "$work/src-password"
  printf 'visible src-secret-unique\n' | redact_stream_to_file "$temp/missing.txt" 1024 > "$temp/missing.stdout" 2> "$temp/missing.err"
  rc=$?
  assert_nonzero "$rc" "パスワードsecretファイル欠落時は失敗扱い"
  assert_eq "" "$(cat "$temp/missing.txt")" "secretファイル欠落時はファイルへ何も出さない"
  assert_eq "" "$(cat "$temp/missing.stdout")" "secretファイル欠落時はコンソールへ何も出さない"
  assert_contains "$(cat "$temp/missing.err")" "FAIL-CLOSED" "secretファイル欠落のfail-closedを通知"
  printf 'src-secret-unique\n' > "$work/src-password"
  : > "$work/tgt-password"
  printf 'tgt-secret-unique\n' | redact_stream_to_file "$temp/empty-secret.txt" 1024 > /dev/null 2>&1
  rc=$?
  assert_nonzero "$rc" "空のパスワードsecretファイルは失敗扱い"
  assert_eq "" "$(cat "$temp/empty-secret.txt")" "空secretファイル時は何も出さない"
  printf 'tgt-secret-unique\n' > "$work/tgt-password"
  rm -f "$work/keys/priv1.pem" "$work/keys/priv2.pem"
  printf 'key files removed tgt-secret-unique\n' | redact_stream_to_file "$temp/nokeys.txt" 1024 > /dev/null 2>&1
  rc=$?
  assert_zero "$rc" "署名鍵ファイル（PITR後に削除される）の欠落は許容"
  assert_eq "key files removed [REDACTED]" "$(cat "$temp/nokeys.txt")" "鍵ファイル欠落時もパスワードは伏字化"
  write_redactor_secrets "$work"

  redact_stream_to_file "$temp/empty-input.txt" 1024 < /dev/null > /dev/null 2>&1
  rc=$?
  assert_zero "$rc" "空入力でも成功"
  assert_eq "" "$(cat "$temp/empty-input.txt")" "空入力では何も出さない"
  { head -c 200000 /dev/zero | tr '\000' z; printf '\n'; exit 6; } \
    | redact_stream_to_file "$temp/bad-limit.txt" abc > /dev/null 2>&1
  status=("${PIPESTATUS[@]}")
  assert_eq 6 "${status[0]:-}" "不正な上限値で早期returnしても上流を壊さない"
  assert_eq 2 "${status[1]:-}" "不正な上限値を拒否"
  redact_stream_to_file "$temp/small-limit.txt" 100 < /dev/null > /dev/null 2>&1
  rc=$?
  assert_eq 2 "$rc" "1024未満の上限値を拒否"

  cat > "$temp/fake-bin/awk" <<'MOCK'
#!/usr/bin/env bash
exit 77
MOCK
  "$REAL_CHMOD" +x "$temp/fake-bin/awk"
  { head -c 200000 /dev/zero | tr '\000' w; printf '\n'; exit 7; } \
    | PATH="$temp/fake-bin:$PATH" redact_stream_to_file "$temp/failed.txt" 65536 > /dev/null 2>&1
  status=("${PIPESTATUS[@]}")
  assert_eq 77 "${status[1]:-}" "redactor失敗を成功扱いしない"
  assert_eq 7 "${status[0]:-}" "awkが即時終了しても上流をSIGPIPEで落とさない"

  cat > "$temp/hang-bin/awk" <<'MOCK'
#!/usr/bin/env bash
exec sleep 30
MOCK
  "$REAL_CHMOD" +x "$temp/hang-bin/awk"
  INTEGRATION_DIAG_BUDGET_SECONDS=5 INTEGRATION_DIAG_CALL_TIMEOUT_SECONDS=1 init_diag_limits
  diag_budget_start
  start=$SECONDS
  printf 'x\n' | PATH="$temp/hang-bin:$PATH" redact_stream_to_file "$temp/hang.txt" 1024 bounded > /dev/null 2>&1
  rc=$?
  elapsed=$((SECONDS - start))
  assert_nonzero "$rc" "診断用redactorのawkが期限内に終わらなければ失敗扱い（timeoutがawkも覆う）"
  assert_le "$elapsed" 10 "awkのハングを診断予算付きtimeoutで打ち切る"
  rm -rf "$temp"
}

# 10) 診断ファイル全体を 512KiB に制限し、上限後のセクション・行を追記しない。
case_integration_diag_total_cap_512kib() {
  local temp content
  temp=$(mktemp -d)
  load_runner_diag_helpers "$temp" || { rm -rf "$temp"; return 0; }
  DIAG_FILE="$temp/diag.txt"
  : > "$DIAG_FILE"
  head -c 70000 /dev/zero | tr '\000' S > "$temp/section.txt"
  for ((i = 1; i <= 10; i++)); do
    append_diagnostic_section "section-$i" "$temp/section.txt"
  done
  append_diagnostic_line "line-after-cap-unique"
  content=$(cat "$DIAG_FILE")
  assert_eq 524288 "$(file_bytes "$DIAG_FILE")" "診断全体を512KiBちょうどで打ち切る"
  assert_contains "$content" "[section-1]" "上限内の先頭セクションを保持"
  assert_contains "$content" "[section-8]" "上限に掛かるセクションは残り容量分だけ保持"
  assert_not_contains "$content" "[section-9]" "上限後のセクションを追記しない"
  assert_not_contains "$content" "line-after-cap-unique" "上限後の行を追記しない"
  rm -rf "$temp"
}

# 11) 最終 secret scan は漏えい・PEM（PGP 形式を含む）・サイズ超過・欠落入力をすべて失敗として扱う。
case_integration_secret_scan_fails_closed() {
  local temp work rc
  temp=$(mktemp -d)
  work="$temp/work"
  load_runner_diag_helpers "$temp" || { rm -rf "$temp"; return 0; }
  write_redactor_secrets "$work"
  WORK_AWK="$work"
  DIAG_FILE="$temp/scan.txt"
  printf 'clean diagnostic line [REDACTED]\n' > "$DIAG_FILE"
  scan_failure_diagnostics
  assert_zero "$?" "secretを含まない診断はscanを通過"
  printf 'leak src-secret-unique\n' > "$DIAG_FILE"
  scan_failure_diagnostics
  rc=$?
  assert_nonzero "$rc" "secret literalが残った診断を拒否"
  printf '%s\n' 'leak -----BEGIN PRIVATE KEY-----' > "$DIAG_FILE"
  scan_failure_diagnostics
  rc=$?
  assert_nonzero "$rc" "PEM BEGINが残った診断を拒否"
  printf '%s\n' 'leak -----BEGIN PGP PRIVATE KEY BLOCK-----' > "$DIAG_FILE"
  scan_failure_diagnostics
  rc=$?
  assert_nonzero "$rc" "PGP形式のPEM BEGINが残った診断を拒否"
  head -c 524289 /dev/zero | tr '\000' Z > "$DIAG_FILE"
  scan_failure_diagnostics
  rc=$?
  assert_nonzero "$rc" "512KiB超の診断を拒否"
  rm -f "$DIAG_FILE"
  scan_failure_diagnostics
  rc=$?
  assert_nonzero "$rc" "診断ファイル欠落を拒否"
  printf 'clean\n' > "$DIAG_FILE"
  rm -f "$work/src-password"
  scan_failure_diagnostics
  rc=$?
  assert_nonzero "$rc" "secretファイル欠落時はscan失敗（fail-closed）"
  rm -rf "$temp"
}

# ------------------------------------------------------------
# mock docker による run-integration.sh の挙動検証
# ------------------------------------------------------------

# 12) P1 回帰: network ID を正しく解析し、失敗は runner_exit=1・native 終了コードは診断に記録する。
case_integration_p1_network_id_and_pitr_native_rc() {
  local temp work diag diag_content svc
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-pitr"
  runner_mock_run "$temp" pitr-native "$work"
  diag="$work/evidence/failure-diagnostics.txt"
  diag_content=$(cat "$diag" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "PITR失敗時のrunner終了コードは1（73aa互換）"
  assert_contains "$RUNNER_OUT" "network: $MOCK_NET_ID" "Go templateの改行区切りでnetwork IDを正しく解析（P1回帰）"
  assert_not_contains "$RUNNER_OUT" "network: $MOCK_NET_ID"'\n' "network IDに字面の改行エスケープを含めない（P1回帰）"
  assert_contains "$RUNNER_DOCKER_LOG" "network inspect --format" "network所有確認を実施"
  assert_contains "$RUNNER_DOCKER_LOG" "run --rm --network $MOCK_NET_ID " "検証済みnetwork IDでPITRコンテナを起動"
  assert_contains "$RUNNER_DOCKER_LOG" "cp $MOCK_SRC_ID:/var/lib/mysql/ca.pem" "検証済みsource container IDでCAを取得"
  assert_contains "$diag_content" "stage=integration-pitr native_exit=42 runner_exit=1" "診断にPITR段階とnative終了コード(42)を記録"
  assert_contains "$diag_content" "[failing-command-output]" "失敗コマンド出力を診断へ収録"
  assert_contains "$diag_content" "pitr-normal-tail-after-pem" "PEMブロック後のPITR通常ログを保持"
  assert_contains "$diag_content" "mock-after-pem-tail" "END RSA PRIVATE KEY同一行の後続ログを保持"
  assert_not_contains "$diag_content" "mock-key-body-unique" "PITR出力の秘密鍵本文を診断に出さない"
  for svc in target source tool scheduler replica; do
    assert_contains "$diag_content" "[$svc-state-health-image-mounts]" "所有確認済み${svc}のstateを収集"
    assert_contains "$diag_content" "[$svc-logs tail=200]" "所有確認済み${svc}のlogsを収集"
  done
  assert_contains "$diag_content" "target-normal-error-after-pem" "コンテナログのEC PEM後の通常ログを保持"
  assert_before "$(first_line_no "$diag" '[target-state-health-image-mounts]')" \
    "$(first_line_no "$diag" '[source-state-health-image-mounts]')" "targetを最初に診断"
  assert_before "$(last_line_no "$temp/docker.log" 'logs --timestamps')" \
    "$(first_line_no "$temp/docker.log" 'down -v')" "全診断の収集後にcompose down（capture-before-down）"
  assert_no_secret_leak "$diag_content" "PITR失敗診断"
  assert_no_secret_leak "$RUNNER_OUT" "PITR失敗時のコンソール出力"
  rm -rf "$temp"
}

# 13) restore-drill 失敗: runner_exit=1（drill の exit 2 と BLOCKED=2 を混同しない）、検証済み target ID を使う。
case_integration_drill_native_rc() {
  local temp work diag diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-drill"
  runner_mock_run "$temp" drill-native "$work"
  diag="$work/evidence/failure-diagnostics.txt"
  diag_content=$(cat "$diag" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "drill失敗時のrunner終了コードは1"
  assert_contains "$diag_content" "stage=restore-drill native_exit=37 runner_exit=1" "診断にdrill段階とnative終了コード(37)を記録"
  assert_contains "$diag_content" "drill-normal-tail" "drill出力を診断へ収録"
  assert_contains "$RUNNER_DOCKER_LOG" "exec $MOCK_TGT_ID mysql" "検証済みtarget container IDでdocker exec"
  assert_contains "$RUNNER_DOCKER_LOG" "--target 2026-11-12T00:00:00Z --plan plan-mock-1" "planとrequested_targetをdrillへ渡す"
  assert_before "$(last_line_no "$temp/docker.log" 'logs --timestamps')" \
    "$(first_line_no "$temp/docker.log" 'down -v')" "drill失敗時も診断収集後にcompose down"
  assert_no_secret_leak "$diag_content" "drill失敗診断"
  rm -rf "$temp"
}

# 14) Compose partial-up: 起動済みの所有コンテナだけを target 優先で診断し、その後に cleanup する。
case_integration_compose_partial_up_capture() {
  local temp work diag diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-partial"
  runner_mock_run "$temp" partial-up "$work"
  diag="$work/evidence/failure-diagnostics.txt"
  diag_content=$(cat "$diag" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "compose up失敗時のrunner終了コードは1"
  assert_contains "$diag_content" "stage=compose-up native_exit=17 runner_exit=1" "診断にcompose-up段階とnative終了コード(17)を記録"
  assert_contains "$diag_content" "mock-partial-start-failure" "compose up出力を診断へ収録"
  assert_contains "$diag_content" "[target-state-health-image-mounts]" "起動済みtargetを診断"
  assert_contains "$diag_content" "[source-state-health-image-mounts]" "起動済みsourceを診断"
  assert_not_contains "$diag_content" "[tool-state" "未作成toolを診断しない"
  assert_not_contains "$diag_content" "[scheduler-state" "未作成schedulerを診断しない"
  assert_not_contains "$diag_content" "[replica-state" "未作成replicaを診断しない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "up --wait" "compose up失敗後にwaitへ進まない"
  assert_contains "$diag_content" "target-same-line-tail" "EC PEM END同一行の後続を保持"
  assert_contains "$diag_content" "target-normal-error-after-pem" "EC PEM後の通常エラーログを保持"
  assert_not_contains "$diag_content" "mock-target-key-body-unique" "ログ内の秘密鍵本文を出さない"
  assert_before "$(first_line_no "$diag" '[target-state-health-image-mounts]')" \
    "$(first_line_no "$diag" '[source-state-health-image-mounts]')" "partial-upでもtargetを最初に診断"
  assert_before "$(last_line_no "$temp/docker.log" 'logs --timestamps')" \
    "$(first_line_no "$temp/docker.log" 'down -v')" "partial-upでも診断収集後にcompose down"
  assert_no_secret_leak "$diag_content" "partial-up診断"
  rm -rf "$temp"
}

# 15) healthcheck 失敗（73aa の失敗段階相当）: health log と mount を行単位で残す。
case_integration_compose_wait_unhealthy_capture() {
  local temp work diag diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-wait"
  runner_mock_run "$temp" wait-unhealthy "$work"
  diag="$work/evidence/failure-diagnostics.txt"
  diag_content=$(cat "$diag" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "compose up --wait失敗時のrunner終了コードは1"
  assert_contains "$diag_content" "stage=compose-wait native_exit=1 runner_exit=1" "診断にcompose-wait段階を記録"
  assert_contains "$diag_content" "is unhealthy" "wait出力を診断へ収録"
  assert_contains "$diag_content" $'\nhealth_start=2026-11-12T00:00:10Z health_exit=1' "health logを独立した行で記録（{{println}}）"
  assert_contains "$diag_content" "health_output=target-health-second" "複数のhealth logを記録"
  assert_contains "$diag_content" $'\nmount_type=volume source=/var/lib/docker/volumes/target' "mountを独立した行で記録"
  assert_before "$(first_line_no "$diag" '[target-state-health-image-mounts]')" \
    "$(first_line_no "$diag" '[source-state-health-image-mounts]')" "unhealthy targetを最初に診断"
  assert_before "$(last_line_no "$temp/docker.log" 'logs --timestamps')" \
    "$(first_line_no "$temp/docker.log" 'down -v')" "wait失敗でも診断収集後にcompose down"
  assert_no_secret_leak "$diag_content" "healthcheck失敗診断"
  rm -rf "$temp"
}

# 16) foreign / 分離 / 複数 network は PITR へ進まず拒否する（自 project の container は cleanup する）。
case_integration_foreign_network_rejected() {
  local temp work scenario diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  for scenario in foreign-network split-network multi-network; do
    work="$temp/allowed/work-$scenario"
    runner_mock_run "$temp" "$scenario" "$work"
    diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
    assert_eq 1 "$RUNNER_RC" "$scenario: network検証失敗で非0"
    assert_contains "$diag_content" "stage=project-network-verification native_exit=n/a" "$scenario: native段ではないためnative_exitを0と書かない"
    assert_not_contains "$RUNNER_DOCKER_LOG" "run --rm --network" "$scenario: 未検証networkでPITRコンテナを起動しない"
    assert_not_contains "$RUNNER_DOCKER_LOG" "cp $MOCK_SRC_ID" "$scenario: CA取得へ進まない"
    assert_contains "$RUNNER_DOCKER_LOG" "down -v" "$scenario: 所有確認済みprojectはcleanupする"
  done
  rm -rf "$temp"
}

# 17) foreign container が混在したら診断から除外し、所有を確認できない project を down しない。
case_integration_foreign_container_not_cleaned() {
  local temp work diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-foreign-container"
  runner_mock_run "$temp" foreign-container "$work"
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "foreign container混在で非0"
  assert_contains "$diag_content" "stage=project-network-verification" "所有確認失敗段階を記録"
  assert_contains "$diag_content" "ownership=unverified omitted" "foreign containerを診断から除外"
  assert_not_contains "$diag_content" "[source-state" "foreign containerのstateを収集しない"
  assert_contains "$diag_content" "[target-state-health-image-mounts]" "所有確認済みtargetは診断"
  assert_not_contains "$RUNNER_DOCKER_LOG" "down -v" "所有を確認できないprojectをdownしない"
  assert_contains "$RUNNER_OUT" "leaving unknown resources untouched" "未確認資源を残したことを通知"
  rm -rf "$temp"
}

# 18) 診断予算と per-call timeout: Docker がハングしても有界時間で終わり、予算切れを記録する。
case_integration_diag_deadline_bounded() {
  local temp work diag_content start elapsed
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-slow"
  start=$SECONDS
  runner_mock_run "$temp" slow-diag "$work" INTEGRATION_DIAG_BUDGET_SECONDS=4 INTEGRATION_DIAG_CALL_TIMEOUT_SECONDS=1
  elapsed=$((SECONDS - start))
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "診断がハングしてもrunner終了コードは1のまま"
  assert_contains "$diag_content" "advisory_budget_seconds=4 per_call_timeout_seconds=1" "目安予算とper-call上限を記録"
  assert_contains "$diag_content" "target-state-collector_rc=124" "ハングしたDocker呼び出しをtimeoutで打ち切る"
  assert_contains "$diag_content" "diagnostic budget exhausted" "予算切れ後の呼び出しを省略して記録"
  assert_le "$elapsed" 30 "ハングするmock（各30秒×最大10回）でも有界時間で終了"
  assert_contains "$RUNNER_DOCKER_LOG" "down -v" "予算切れ後もcleanupを実施"
  rm -rf "$temp"
}

make_tool_wrappers() { # <dir> <tool...>
  local dir=$1 tool real bash_bin
  shift
  bash_bin=$(command -v bash)
  for tool in "$@"; do
    real=$(command -v "$tool") || continue
    [[ "$real" == /* ]] || continue
    printf '#!%s\nexec %q "$@"\n' "$bash_bin" "$real" > "$dir/$tool"
    "$REAL_CHMOD" +x "$dir/$tool"
  done
}

# 19) 欠落入力: timeout 不在・GNU 以外の timeout・container 一覧取得失敗でも fail-safe に診断する。
case_integration_missing_inputs_fail_safe() {
  local temp work curated nongnu diag_content timeout_found fake_calls
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  curated="$temp/curated-bin"
  mkdir -p "$curated"
  cp "$temp/bin/docker" "$temp/bin/cygpath" "$temp/bin/chmod" "$curated/"
  make_tool_wrappers "$curated" bash env dirname basename date id mkdir find grep cat wc head tail cut awk \
    rm cp mv tee sha256sum sleep tr ls touch sed sort kill
  timeout_found=$(PATH="$curated"; command -v timeout 2>/dev/null || true)
  assert_eq "" "$timeout_found" "検証用PATHにtimeoutが存在しない（前提）"
  work="$temp/allowed/work-no-timeout"
  RUNNER_PATH="$curated"
  runner_mock_run "$temp" no-timeout "$work"
  RUNNER_PATH=""
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "timeout不在でもrunner終了コードは1"
  assert_contains "$diag_content" "native_exit=17" "timeout不在でもnative終了コードを記録"
  assert_contains "$diag_content" "collector=bounded timeout command unavailable; Docker diagnostics omitted" "timeout不在ではDocker診断を省略"
  assert_contains "$diag_content" "[failing-command-output]" "timeout不在でも失敗コマンド出力は収録"
  assert_not_contains "$diag_content" "[target-state" "timeout不在では上限の無いDocker診断をしない"
  assert_contains "$RUNNER_DOCKER_LOG" "down -v" "所有確認できたprojectはcleanup"

  nongnu="$temp/nongnu-bin"
  mkdir -p "$nongnu"
  cp "$curated"/* "$nongnu/"
  cat > "$nongnu/timeout" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_FAKE_TIMEOUT_LOG"
echo "ERROR: Invalid syntax. Default option is not allowed more than '1' time(s)." >&2
exit 1
MOCK
  "$REAL_CHMOD" +x "$nongnu/timeout"
  work="$temp/allowed/work-nongnu-timeout"
  RUNNER_PATH="$nongnu"
  runner_mock_run "$temp" no-timeout "$work" MOCK_FAKE_TIMEOUT_LOG="$temp/fake-timeout.log"
  RUNNER_PATH=""
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  fake_calls=$(grep -v -x -F -- '--version' "$temp/fake-timeout.log" 2>/dev/null || true)
  assert_contains "$diag_content" "collector=bounded timeout command unavailable" "GNU以外のtimeout（System32のtimeout.exe相当）は採用しない"
  assert_eq "" "$fake_calls" "GNU以外のtimeoutで診断コマンドを包まない（--version確認だけ）"
  assert_not_contains "$diag_content" "redaction/secret scan failed" "GNU以外のtimeoutでも最終scanは直接実行で完了"

  mkdir -p "$temp/uutils-bin"
  cat > "$temp/uutils-bin/timeout" <<'MOCK'
#!/usr/bin/env bash
if [[ "${1:-}" == --version ]]; then
  echo "timeout (uutils coreutils) 0.1.0"
  exit 0
fi
printf '%s\n' "$*" >> "$MOCK_FAKE_TIMEOUT_LOG"
exec "$MOCK_REAL_TIMEOUT" "$@"
MOCK
  "$REAL_CHMOD" +x "$temp/uutils-bin/timeout"
  work="$temp/allowed/work-uutils-timeout"
  RUNNER_PATH="$temp/uutils-bin:$temp/bin:$PATH"
  runner_mock_run "$temp" partial-up "$work" MOCK_FAKE_TIMEOUT_LOG="$temp/uutils-timeout.log" MOCK_REAL_TIMEOUT="$REAL_TIMEOUT"
  RUNNER_PATH=""
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_contains "$diag_content" "[target-state-health-image-mounts]" "uutils coreutils の timeout ではDocker診断を実施"
  assert_contains "$(cat "$temp/uutils-timeout.log" 2>/dev/null)" "-k 1s" "uutils coreutils の timeout で診断コマンドを包む"

  work="$temp/allowed/work-ps-fail"
  runner_mock_run "$temp" diag-ps-fail "$work"
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "container一覧取得失敗でもrunner終了コードは1"
  assert_contains "$diag_content" "container-list=unavailable rc=1" "container一覧の取得失敗を記録"
  assert_not_contains "$RUNNER_DOCKER_LOG" "down -v" "所有を確認できないprojectをdownしない"
  assert_contains "$RUNNER_OUT" "leaving unknown resources untouched" "未確認資源を残したことを通知"
  rm -rf "$temp"
}

expect_work_rejected() { # <temp> <work> <message-needle> <label> [VAR=value ...]
  local temp=$1 work=$2 needle=$3 label=$4
  shift 4
  runner_mock_run "$temp" pitr-native "$work" "$@"
  assert_nonzero "$RUNNER_RC" "$label: preflightで拒否"
  assert_contains "$RUNNER_OUT" "$needle" "$label: 拒否理由を通知"
  assert_no_file "$temp/chmod.log" "$label: chmodを一切行わない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "compose -f" "$label: Compose projectを操作しない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "--user 0:0" "$label: root chownコンテナを起動しない"
}

# 20) WORK は許可ルート配下・symlink 無し・所有確認済みに限り、満たさなければ chmod/chown しない。
case_integration_work_path_boundary_fails_closed() {
  local temp allowed outside repo
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  allowed="$temp/allowed"
  outside="$temp/outside"
  mkdir -p "$outside" "$allowed/real" "$allowed/empty-foreign" "$allowed/target-dir"
  MSYS=winsymlinks:nativestrict ln -s "$allowed/real" "$allowed/link"
  MSYS=winsymlinks:nativestrict ln -s "$outside" "$allowed/escape"
  MSYS=winsymlinks:nativestrict ln -s "$allowed/target-dir" "$allowed/work-link"
  if [[ -L "$allowed/link" && -L "$allowed/escape" && -L "$allowed/work-link" ]]; then
    test_assert "symlink fixtureを作成（前提）"
  else
    test_fail "symlink fixtureを作成（前提）" "この環境では symlink を作成できません"
  fi

  expect_work_rejected "$temp" "$outside/work" "outside the permitted fresh-work boundary" "許可ルート外"
  assert_no_file "$outside/work" "許可ルート外にディレクトリを作らない"
  expect_work_rejected "$temp" "$allowed/link/work" "symlink を含むパス要素" "許可ルート内を指す祖先symlink"
  assert_no_file "$allowed/real/work" "symlink先にディレクトリを作らない"
  expect_work_rejected "$temp" "$allowed/escape/work" "symlink を含むパス要素" "許可ルート外を指す祖先symlink"
  assert_no_file "$outside/work" "祖先symlink経由で外部にディレクトリを作らない"
  expect_work_rejected "$temp" "$allowed/work-link" "symlink を含むパス要素" "WORK自身のsymlink"
  assert_eq "" "$(ls -A "$allowed/target-dir")" "symlink先ディレクトリを変更しない"
  expect_work_rejected "$temp" "$allowed/empty-foreign" "所有マーカー" "所有マーカーの無い既存の空ディレクトリ"
  assert_eq "" "$(ls -A "$allowed/empty-foreign")" "所有マーカーの無い空ディレクトリへ書き込まない"
  expect_work_rejected "$temp" "$allowed/real/../work" "絶対パスが必要" "'..'を含むパス"
  expect_work_rejected "$temp" "relative/work" "絶対パスが必要" "相対パス"
  expect_work_rejected "$temp" "$allowed/no-parent/work" "親ディレクトリが存在しません" "存在しない祖先"
  assert_no_file "$allowed/no-parent" "祖先をmkdir -pで作らない"
  expect_work_rejected "$temp" "$allowed" "outside the permitted fresh-work boundary" "許可ルートそのもの"

  repo="$temp/repo"
  mkdir -p "$repo/ops/backup/tests"
  cp "$RUNNER" "$repo/ops/backup/tests/run-integration.sh"
  RUNNER_UNDER_TEST="$repo/ops/backup/tests/run-integration.sh"
  expect_work_rejected "$temp" "$repo/ops/inside-work" "リポジトリ配下" "許可ルート内でもリポジトリ配下" \
    TMPDIR="$temp" TMP="$temp" TEMP="$temp"
  assert_no_file "$repo/ops/inside-work" "リポジトリ配下にディレクトリを作らない"
  expect_work_rejected "$temp" "$temp" "リポジトリの祖先" "リポジトリの祖先ディレクトリ" \
    TMPDIR="${temp%/*}" TMP="${temp%/*}" TEMP="${temp%/*}"
  RUNNER_UNDER_TEST=""
  rm -rf "$temp"
}

# 21) 新規作成・verify-like-ci.ps1 の所有マーカー付き・既定パスの WORK は受理し、検証済みパスだけを正規化する。
#     固定名の事前確認が WORK 作成より前になったため、WORK 所有後の最初の失敗点（compose up 失敗）で止める。
case_integration_work_path_accepts_owned_dirs() {
  local temp work parent repo diag_content foreign_chmod
  temp=$(mktemp -d)
  runner_mock_init "$temp"

  work="$temp/allowed/fresh-work"
  runner_mock_run "$temp" up-fail "$work"
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "WORK所有後のcompose up失敗で1"
  assert_file "$work/.ses-pitr-run-owner" "許可ルート配下の新規WORKを所有"
  assert_contains "$(cat "$work/.ses-pitr-run-owner" 2>/dev/null)" "work=$work" "所有マーカーに正規化済みWORKを記録"
  assert_contains "$diag_content" "stage=compose-up native_exit=17 runner_exit=1" "所有したWORKへ失敗診断を記録"
  assert_file "$temp/chmod.log" "所有WORKは終了時に権限を正規化"
  foreign_chmod=$(grep -v -F -- "$work" "$temp/chmod.log" 2>/dev/null || true)
  assert_eq "" "$foreign_chmod" "chmodは検証済みWORKだけを対象にする"

  parent="$temp/allowed/ses-pitr-run-selftest"
  mkdir -p "$parent/integration-work"
  write_controller_marker "$parent" "$parent/integration-work" "$parent"
  runner_mock_run "$temp" up-fail "$parent/integration-work"
  assert_eq 1 "$RUNNER_RC" "所有マーカー付き既存WORKでcompose upまで進む"
  assert_file "$parent/integration-work/.ses-pitr-run-owner" "verify-like-ci.ps1形式の空integration-workを所有"

  repo="$temp/repo"
  mkdir -p "$repo/ops/backup/tests"
  cp "$RUNNER" "$repo/ops/backup/tests/run-integration.sh"
  RUNNER_UNDER_TEST="$repo/ops/backup/tests/run-integration.sh"
  runner_mock_run "$temp" up-fail "-"
  RUNNER_UNDER_TEST=""
  assert_eq 1 "$RUNNER_RC" "既定WORKでcompose upまで進む"
  assert_file "$repo/ops/backup/tests/.integration-work/.ses-pitr-run-owner" "既定パス \$HERE/.integration-work を所有"
  rm -rf "$temp"
}

# 22) 全段成功: runner_exit=0、失敗診断を生成せず、cleanup と権限正規化は検証済み WORK に対して行う。
case_integration_full_success_no_diagnostics() {
  local temp work foreign_chmod
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-success"
  runner_mock_run "$temp" success "$work"
  assert_eq 0 "$RUNNER_RC" "全段成功のmockでrunnerは0"
  assert_contains "$RUNNER_OUT" "integration suite SUCCESS" "成功を通知"
  assert_contains "$RUNNER_OUT" "secret scan: 0 matches" "evidence secret scanを通過"
  assert_contains "$RUNNER_OUT" "network: $MOCK_NET_ID" "成功経路でもnetwork IDを正しく解析"
  assert_no_file "$work/evidence/failure-diagnostics.txt" "成功時は失敗診断を生成しない"
  assert_file "$work/evidence/evidence-sha.txt" "evidence SHAを記録"
  assert_contains "$RUNNER_DOCKER_LOG" "down -v" "成功時もcompose downでcleanup"
  assert_file "$temp/chmod.log" "成功時も権限を正規化"
  foreign_chmod=$(grep -v -F -- "$work" "$temp/chmod.log" 2>/dev/null || true)
  assert_eq "" "$foreign_chmod" "成功時のchmodも検証済みWORKだけを対象にする"
  assert_no_secret_leak "$RUNNER_OUT" "成功時のコンソール出力"
  rm -rf "$temp"
}

# 23) 後半段（結果検証）の失敗にも stage を設定し、native 段でないため native_exit=0 と書かない。
case_integration_late_stage_failure_records_stage() {
  local temp work diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-state-fail"
  runner_mock_run "$temp" state-fail "$work"
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "結果stateがSUCCESSでなければ1"
  assert_contains "$RUNNER_OUT" "FAIL: state=FAILED" "結果state不一致を通知"
  assert_contains "$diag_content" "stage=result-state native_exit=n/a runner_exit=1" "後半段の失敗stageを記録"
  assert_not_contains "$diag_content" "native_exit=0" "native段でない失敗にnative_exit=0を書かない"
  assert_not_contains "$diag_content" "stage=unknown" "後半段の失敗をunknownにしない"
  rm -rf "$temp"
}

# 24) コマンドは成功したが redactor が失敗した場合も失敗（1）とし、上流を SIGPIPE で壊さない。
case_integration_redactor_failure_after_command_success() {
  local temp work diag_content
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  mkdir -p "$temp/awkfail-bin"
  cat > "$temp/awkfail-bin/awk" <<'MOCK'
#!/usr/bin/env bash
case "$*" in
  *maxBytes=*) exit 77 ;;
esac
exec "$MOCK_REAL_AWK" "$@"
MOCK
  "$REAL_CHMOD" +x "$temp/awkfail-bin/awk"
  work="$temp/allowed/work-redactor-fail"
  RUNNER_PATH="$temp/awkfail-bin:$temp/bin:$PATH"
  runner_mock_run "$temp" redactor-fail "$work"
  RUNNER_PATH=""
  diag_content=$(cat "$work/evidence/failure-diagnostics.txt" 2>/dev/null)
  assert_eq 1 "$RUNNER_RC" "コマンド成功でもredactor失敗はrunner失敗(1)"
  assert_contains "$RUNNER_OUT" "rc=0 redactor_rc=77" "コマンドrcとredactor rcを区別して通知"
  assert_contains "$diag_content" "stage=compose-up native_exit=0 runner_exit=1" "上流はSIGPIPEで壊れずnative rc 0を保持"
  assert_contains "$diag_content" "[redactor failed; output omitted]" "redactor失敗時は内容の代わりにplaceholderを残す"
  assert_contains "$diag_content" "redaction failed or timed out; content omitted" "診断sectionもredactor失敗時はplaceholder"
  assert_not_contains "$RUNNER_DOCKER_LOG" "up --wait" "redactor失敗後にwaitへ進まない"
  rm -rf "$temp"
}

# 25) 実行中に所有マーカーが改ざんされた WORK には診断を書かず、chmod/chown もしない。
case_integration_tampered_work_is_not_normalized() {
  local temp work
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  work="$temp/allowed/work-tamper"
  runner_mock_run "$temp" tamper-owner "$work"
  assert_eq 1 "$RUNNER_RC" "所有マーカー改ざんのrunは1"
  assert_contains "$RUNNER_OUT" "所有を再確認できない" "所有を失ったことを通知"
  assert_no_file "$temp/chmod.log" "所有を失ったWORKへchmodしない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "--user 0:0" "所有を失ったWORKへroot chownコンテナを起動しない"
  assert_no_file "$work/evidence/failure-diagnostics.txt" "所有を失ったWORKへ診断を書かない"
  assert_contains "$RUNNER_DOCKER_LOG" "down -v" "所有確認済みのCompose projectはcleanupする"
  rm -rf "$temp"
}

# 26) 既定 WORK の 2 回目以降の run は前回分を .integration-work.prev-<RUN_ID> に退避して成功し、直近 3 件だけ保持する。
case_integration_default_work_rerun_rotates_previous_run() {
  local temp repo tests_dir default first_run second_run run4 prev_count n
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  repo="$temp/repo"
  tests_dir="$repo/ops/backup/tests"
  default="$tests_dir/.integration-work"
  mkdir -p "$tests_dir"
  cp "$RUNNER" "$tests_dir/run-integration.sh"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"

  runner_mock_run "$temp" success "-"
  assert_eq 0 "$RUNNER_RC" "既定WORKの初回runが成功"
  first_run=$(sed -n 's/^run=//p' "$default/.ses-pitr-run-owner" 2>/dev/null)
  # run ID の epoch を進め、後の剪除順が PID の大小に依存しないようにする
  sleep 1
  runner_mock_run "$temp" success "-"
  assert_eq 0 "$RUNNER_RC" "既定WORKの2回目のrunも成功（再実行回帰の防止）"
  assert_contains "$RUNNER_OUT" "前回の既定作業ディレクトリを退避しました" "退避を通知"
  assert_file "$tests_dir/.integration-work.prev-$first_run/evidence/integration-summary.json" "前回runのevidenceを退避先に保全"
  second_run=$(sed -n 's/^run=//p' "$default/.ses-pitr-run-owner" 2>/dev/null)
  if [[ -n "$first_run" && -n "$second_run" && "$first_run" != "$second_run" ]]; then
    test_assert "2回目のrunは新しい所有マーカーで既定WORKを作り直す"
  else
    test_fail "2回目のrunは新しい所有マーカーで既定WORKを作り直す" "first=[$first_run] second=[$second_run]"
  fi
  assert_contains "$(cat "$default/.ses-pitr-run-owner" 2>/dev/null)" "work=$default" "作り直した既定WORKの所有マーカー"

  run4=""
  for n in 3 4 5; do
    # run ID の epoch を進め、剪除順が PID の大小に依存しないようにする
    sleep 1
    if (( n == 5 )); then run4=$(marker_run_id "$default"); fi
    runner_mock_run "$temp" success "-"
    assert_eq 0 "$RUNNER_RC" "既定WORKの${n}回目のrunも成功"
  done
  prev_count=$(find "$tests_dir" -mindepth 1 -maxdepth 1 -type d -name '.integration-work.prev-*' | wc -l)
  assert_eq 3 "${prev_count//[[:space:]]/}" "退避ディレクトリは直近3件だけ保持（無限に増やさない）"
  assert_file "$tests_dir/.integration-work.prev-$run4/.ses-pitr-run-owner" "直前runの退避分は必ず保持"
  assert_no_file "$tests_dir/.integration-work.prev-$first_run" "最も古い退避分から削除"
  assert_contains "$RUNNER_OUT" "退避ディレクトリを削除しました: $tests_dir/.integration-work.prev-$first_run" "剪除した退避ディレクトリ名を表示"
  RUNNER_UNDER_TEST=""
  rm -rf "$temp"
}

# 29) 固定名・project の事前確認で止まる run は既定 WORK を退避しない。剪除は失敗診断を含む退避分と
#     本 harness 以外のディレクトリを削除せず、削除したものを表示する。
case_integration_default_work_preflight_and_prune_policy() {
  local temp tests_dir default run1 run2 n
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  tests_dir=$(setup_copied_runner "$temp" repo)
  default="$tests_dir/.integration-work"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"

  runner_mock_run "$temp" up-fail "-"
  run1=$(marker_run_id "$default")
  assert_file "$default/evidence/failure-diagnostics.txt" "初回runの失敗診断を既定WORKに保存（前提）"

  runner_mock_run "$temp" fixed-name-occupied "-"
  assert_eq 1 "$RUNNER_RC" "固定名衝突のrunは1"
  assert_contains "$RUNNER_OUT" "fixed container name is occupied" "固定名衝突を通知"
  assert_eq "$run1" "$(marker_run_id "$default")" "固定名衝突のrunは既定WORKを退避・作り直さない"
  assert_file "$default/evidence/failure-diagnostics.txt" "固定名衝突のrunは前回の失敗診断を残す"
  assert_not_contains "$RUNNER_OUT" "退避しました" "固定名衝突のrunは退避を行わない"
  assert_no_file "$temp/chmod.log" "固定名衝突のrunは前回WORKへchmodしない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "up -d --build" "固定名衝突のrunはcompose upしない"

  runner_mock_run "$temp" project-not-fresh "-"
  assert_eq 1 "$RUNNER_RC" "既存コンテナのあるprojectのrunは1"
  assert_contains "$RUNNER_OUT" "generated Compose project is not fresh" "project preflight失敗を通知"
  assert_eq "$run1" "$(marker_run_id "$default")" "project preflight失敗のrunも既定WORKを退避しない"
  if compgen -G "$tests_dir/.integration-work.prev-*" > /dev/null; then
    test_fail "事前確認で止まったrunは退避ディレクトリを作らない" "退避ディレクトリが存在します"
  else
    test_assert "事前確認で止まったrunは退避ディレクトリを作らない"
  fi

  # 本 harness 以外のディレクトリ（剪除対象外）
  mkdir -p "$tests_dir/.integration-work.prev-1600000000-1-1" "$tests_dir/.integration-work.prev-1600000000-2-2" \
    "$tests_dir/.integration-work.manual-20261112000000"
  printf 'run=1600000000-1-1\nwork=/somewhere/else/.integration-work\n' > "$tests_dir/.integration-work.prev-1600000000-1-1/.ses-pitr-run-owner"

  run2=""
  for n in 2 3 4 5 6; do
    sleep 1
    if (( n == 3 )); then run2=$(marker_run_id "$default"); fi
    runner_mock_run "$temp" success "-"
    assert_eq 0 "$RUNNER_RC" "既定WORKの${n}回目のrunが成功"
  done
  assert_file "$tests_dir/.integration-work.prev-$run1/evidence/failure-diagnostics.txt" "失敗診断を含む最古の退避分は自動削除しない"
  assert_contains "$RUNNER_OUT" "失敗診断を含む退避ディレクトリは自動削除しません" "失敗診断を含む退避分を保持したことを通知"
  assert_no_file "$tests_dir/.integration-work.prev-$run2" "失敗診断を含まない退避分は保持数を超えたものから削除"
  assert_contains "$RUNNER_OUT" "退避ディレクトリを削除しました: $tests_dir/.integration-work.prev-$run2" "削除した退避ディレクトリ名を表示"
  assert_file "$tests_dir/.integration-work.prev-1600000000-1-1/.ses-pitr-run-owner" "work=不一致の退避ディレクトリは削除しない"
  assert_file "$tests_dir/.integration-work.prev-1600000000-2-2" "所有マーカーの無い退避ディレクトリは削除しない"
  assert_file "$tests_dir/.integration-work.manual-20261112000000" "手動退避ディレクトリは削除しない"
  RUNNER_UNDER_TEST=""
  rm -rf "$temp"
}

# 30) 前回 run の PID が本 runner 自身の PID と一致する（PID 再利用）場合は終了済みとして退避する。
case_integration_default_work_pid_reuse_is_not_alive() {
  local temp tests_dir default self_pid new_run ns
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  # 旧マーカーは本 run と同じ PID 名前空間で書かれたものとする（kill -0 による判定の対象）
  ns=$(runner_runtime_ns "$temp")
  if [[ -n "$ns" && "$ns" != unknown ]]; then
    test_assert "この環境のPID名前空間を識別できる（前提）"
  else
    test_fail "この環境のPID名前空間を識別できる（前提）" "pidns=[$ns]"
  fi
  tests_dir=$(setup_copied_runner "$temp" repo)
  default="$tests_dir/.integration-work"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"
  mkdir -p "$default"
  printf 'previous evidence\n' > "$default/previous.txt"
  RUNNER_SELF_PID_MARKER_DIR="$default"
  RUNNER_SELF_PID_MARKER_NS="$ns"
  runner_mock_run "$temp" up-fail "-"
  RUNNER_SELF_PID_MARKER_DIR=""
  RUNNER_SELF_PID_MARKER_NS=""
  self_pid=$(cat "$temp/self-pid" 2>/dev/null)
  new_run=$(marker_run_id "$default")
  new_run=${new_run#*-}
  assert_eq "$self_pid" "${new_run%%-*}" "旧マーカーのPIDがrunner自身のPIDと一致する状況を作れた（前提）"
  assert_contains "$RUNNER_OUT" "前回の既定作業ディレクトリを退避しました" "自分自身のPIDは生存中の前回runとみなさない"
  assert_not_contains "$RUNNER_OUT" "実行中の可能性があります" "自分自身のPIDで退避を拒否しない"
  assert_file "$tests_dir/.integration-work.prev-1700000000-$self_pid-1/previous.txt" "前回の内容を退避先に保全"
  RUNNER_UNDER_TEST=""
  rm -rf "$temp"
}

# 27) 既定 WORK の所有マーカーが無い・一致しない・前回 run が生存中なら退避せず fail-closed（日本語の復旧手順を表示）。
case_integration_default_work_marker_mismatch_rejected() {
  local temp repo tests_dir default ns
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  ns=$(runner_runtime_ns "$temp")
  repo="$temp/repo"
  tests_dir="$repo/ops/backup/tests"
  default="$tests_dir/.integration-work"
  mkdir -p "$default"
  cp "$RUNNER" "$tests_dir/run-integration.sh"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"
  printf 'keep me\n' > "$default/legacy-evidence.txt"

  printf 'run=1700000000-12345-1\nwork=/somewhere/else/.integration-work\n' > "$default/.ses-pitr-run-owner"
  expect_work_rejected "$temp" "-" "work= が既定パスと一致しません" "所有マーカーのwork=不一致"
  assert_contains "$RUNNER_OUT" "復旧手順" "復旧手順を表示"
  assert_contains "$(cat "$default/legacy-evidence.txt" 2>/dev/null)" "keep me" "不一致時は既存内容を変更しない"

  rm -f "$default/.ses-pitr-run-owner"
  expect_work_rejected "$temp" "-" "所有マーカー .ses-pitr-run-owner がありません" "所有マーカー無し（73aa時代の残り）"
  assert_contains "$(cat "$default/legacy-evidence.txt" 2>/dev/null)" "keep me" "マーカー無し時は既存内容を変更しない"

  # 同じ PID 名前空間で書かれ、終了記録の無い前回 run の PID（この selftest 自身）が生存している
  printf 'run=1700000000-%s-1\nwork=%s\npidns=%s\n' "$$" "$default" "$ns" > "$default/.ses-pitr-run-owner"
  expect_work_rejected "$temp" "-" "実行中の可能性があります" "前回runのPIDが生存"
  assert_contains "$(cat "$default/legacy-evidence.txt" 2>/dev/null)" "keep me" "前回run生存時は退避しない"

  # pidns= の無い旧形式のマーカー（終了記録も無い）は、どの PID 名前空間の run か分からないため判定不能
  printf 'run=1700000000-%s-1\nwork=%s\n' "$$" "$default" > "$default/.ses-pitr-run-owner"
  expect_work_rejected "$temp" "-" "生存を確認できません" "pidns=の無い旧形式マーカー"
  assert_contains "$(cat "$default/legacy-evidence.txt" 2>/dev/null)" "keep me" "旧形式マーカーでは退避しない"
  if compgen -G "$tests_dir/.integration-work.prev-*" > /dev/null; then
    test_fail "拒否時は退避ディレクトリを作らない" "退避ディレクトリが存在します"
  else
    test_assert "拒否時は退避ディレクトリを作らない"
  fi
  RUNNER_UNDER_TEST=""
  rm -rf "$temp"
}

# 28) -CustomPitrWorkDir（一時ディレクトリ外）: 値の一致する所有マーカーと symlink 無しの祖先なら受理し、それ以外は拒否する。
case_integration_controller_workdir_outside_temp() {
  local temp custom value marker_text bs='\'
  temp=$(mktemp -d)
  runner_mock_init "$temp"

  custom="$temp/custom/run1"
  mkdir -p "$custom/integration-work"
  write_controller_marker "$custom" "$custom/integration-work" "$custom"
  runner_mock_run "$temp" up-fail "$custom/integration-work"
  assert_eq 1 "$RUNNER_RC" "一時ディレクトリ外の制御側WORKでcompose upまで進む"
  assert_contains "$RUNNER_OUT" "mock-partial-start-failure" "WORK検証と事前確認を通過してcompose upへ進む"
  assert_file "$custom/integration-work/.ses-pitr-run-owner" "値の一致する所有マーカー付きWORKを所有"

  # workDir は「/」を全て JSON のスラッシュエスケープ（バックスラッシュ+スラッシュ）に、integration の 'a' を
  # JSON の u0061 エスケープ（バックスラッシュ+u0061）にした文字列として書く。
  # （バックスラッシュは bs 変数から組み立て、fixture と前提の期待値を同じ規則で作る）
  custom="$temp/custom/run2"
  mkdir -p "$custom/integration-work"
  write_controller_marker "$custom" \
    "$(printf '%s' "$custom/integration-work" | sed -e "s#/#${bs}${bs}/#g" -e "s#integration-work#integr${bs}${bs}u0061tion-work#")" \
    "$(printf '%s' "$custom" | sed "s#/#${bs}${bs}/#g")"
  marker_text=$(cat "$custom/.ses-pitr-owner.json")
  assert_contains "$marker_text" "\"workDir\":  \"${bs}/" "workDirの値がJSONの${bs}/エスケープで始まる（前提）"
  assert_contains "$marker_text" "${bs}/integr${bs}u0061tion-work\"" "workDirの値が${bs}/と${bs}u0061の両方のエスケープを含む（前提）"
  assert_not_contains "$marker_text" "\"workDir\":  \"/" "workDirの値に未エスケープの/が無い（前提）"
  assert_not_contains "$marker_text" "integration-work" "workDirの値に未エスケープのintegration-workが無い（前提）"
  runner_mock_run "$temp" up-fail "$custom/integration-work"
  assert_file "$custom/integration-work/.ses-pitr-run-owner" "JSONの${bs}/と印字可能ASCIIの${bs}u00XXエスケープを解釈して値を照合"

  custom="$temp/custom/run3"
  mkdir -p "$custom/integration-work"
  write_controller_marker "$custom" "$temp/custom/other/integration-work" "$custom"
  expect_work_rejected "$temp" "$custom/integration-work" "所有マーカー（.ses-pitr-owner.json）の runToken/workDir/parentDir" "workDirの値が不一致"
  assert_eq "" "$(ls -A "$custom/integration-work")" "workDir不一致のWORKへ書き込まない"

  custom="$temp/custom/run4"
  mkdir -p "$custom/integration-work"
  write_controller_marker "$custom" "$custom/integration-work" "$temp/custom/other"
  expect_work_rejected "$temp" "$custom/integration-work" "所有マーカー（.ses-pitr-owner.json）の runToken/workDir/parentDir" "parentDirの値が不一致"

  custom="$temp/custom/run5"
  mkdir -p "$custom/integration-work"
  write_controller_marker "$custom" "$custom/integration-work" "$custom" "selftest"
  expect_work_rejected "$temp" "$custom/integration-work" "所有マーカー（.ses-pitr-owner.json）の runToken/workDir/parentDir" "runTokenの形式が不正"

  custom="$temp/custom/run6"
  mkdir -p "$custom/integration-work"
  value="$custom/integr\\u3042tion-work"
  write_controller_marker "$custom" "$value" "$custom"
  expect_work_rejected "$temp" "$custom/integration-work" "所有マーカー（.ses-pitr-owner.json）の runToken/workDir/parentDir" "非ASCIIの\\uエスケープはfail-closed"

  custom="$temp/custom/run7"
  mkdir -p "$custom/integration-work"
  printf 'keep\n' > "$custom/integration-work/existing.txt"
  write_controller_marker "$custom" "$custom/integration-work" "$custom"
  expect_work_rejected "$temp" "$custom/integration-work" "existing contents are preserved" "制御側WORKが空でない"

  mkdir -p "$temp/custom/run8/integration-work"
  MSYS=winsymlinks:nativestrict ln -s "$temp/custom" "$temp/custom-link"
  if [[ -L "$temp/custom-link" ]]; then
    test_assert "祖先symlink fixtureを作成（前提）"
  else
    test_fail "祖先symlink fixtureを作成（前提）" "この環境では symlink を作成できません"
  fi
  write_controller_marker "$temp/custom/run8" "$temp/custom-link/run8/integration-work" "$temp/custom-link/run8"
  expect_work_rejected "$temp" "$temp/custom-link/run8/integration-work" "symlink を含むパス要素" "一時ディレクトリ外で祖先にsymlink"
  assert_eq "" "$(ls -A "$temp/custom/run8/integration-work")" "祖先symlink経由のWORKへ書き込まない"

  custom="$temp/custom/run9"
  mkdir -p "$custom/integration-work"
  write_controller_marker_ps7_crlf "$custom" "$custom/integration-work" "$custom"
  marker_text=$(cat "$custom/.ses-pitr-owner.json")
  assert_contains "$marker_text" $'\r' "PowerShell 7 形式のmarkerをCRLFで用意（前提）"
  assert_contains "$marker_text" "\"workDir\": \"$custom/integration-work\"" "PowerShell 7 形式（\"k\": \"v\"）のmarkerを用意（前提）"
  runner_mock_run "$temp" up-fail "$custom/integration-work"
  assert_file "$custom/integration-work/.ses-pitr-run-owner" "PowerShell 7・CRLF形式の所有マーカーも値で照合して受理"
  rm -rf "$temp"
}

# 31) 判定後・準備前（project の事前確認中）に第三者が作成した作業ディレクトリは引き継がない
#     （temp モード、および判定時に存在しなかった既定 WORK は本プロセスの排他 mkdir だけを許す）。
case_integration_work_created_during_preflight_is_rejected() {
  local temp work tests_dir default
  temp=$(mktemp -d)
  runner_mock_init "$temp"

  work="$temp/allowed/work-raced"
  runner_mock_run "$temp" race-mkdir "$work"
  assert_contains "$(cat "$temp/state/raced-dirs" 2>/dev/null)" "$work" "project の事前確認中に第三者がtemp WORKを作成した（前提）"
  assert_eq 1 "$RUNNER_RC" "事前確認中に作られたtemp WORKを拒否"
  assert_contains "$RUNNER_OUT" "排他的に作成できません" "temp WORKを排他作成できない理由を通知"
  assert_eq "" "$(ls -A "$work" 2>/dev/null)" "他者が作成したtemp WORKへ所有マーカー等を書かない"
  assert_no_file "$temp/chmod.log" "他者が作成したtemp WORKへchmodしない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "--user 0:0" "他者が作成したtemp WORKへroot chownコンテナを起動しない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "up -d --build" "temp WORKを用意できなければcompose upしない"

  tests_dir=$(setup_copied_runner "$temp" repo)
  default="$tests_dir/.integration-work"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"
  runner_mock_run "$temp" race-mkdir "-"
  RUNNER_UNDER_TEST=""
  assert_contains "$(cat "$temp/state/raced-dirs" 2>/dev/null)" "$default" "判定時に無かった既定WORKを事前確認中に第三者が作成した（前提）"
  assert_eq 1 "$RUNNER_RC" "判定時に無かった既定WORKが事前確認中に作られたら拒否"
  assert_contains "$RUNNER_OUT" "排他的に作成できません" "既定WORKを排他作成できない理由を通知"
  assert_eq "" "$(ls -A "$default" 2>/dev/null)" "他者が作成した既定WORKへ所有マーカー等を書かない"
  assert_no_file "$temp/chmod.log" "他者が作成した既定WORKへchmodしない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "up -d --build" "既定WORKを用意できなければcompose upしない"
  rm -rf "$temp"
}

# 32) 実行環境の前提（coreutils の mv）を満たさなければ、Docker にも WORK にも触れず BLOCKED（2）で終了する。
case_integration_runner_blocks_without_coreutils_mv() {
  local temp work
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  cat > "$temp/bin/mv" <<'MOCK'
#!/usr/bin/env bash
echo "usage: mv [-f | -i | -n] [-hv] source target" >&2
exit 64
MOCK
  "$REAL_CHMOD" +x "$temp/bin/mv"
  work="$temp/allowed/work-bsd-mv"
  runner_mock_run "$temp" success "$work"
  assert_eq 2 "$RUNNER_RC" "coreutils以外のmv（mv -T非対応）ではBLOCKED(2)"
  assert_contains "$RUNNER_OUT" "BLOCKED: GNU coreutils（または uutils coreutils）の mv が必要です" "前提不足の理由を通知"
  assert_eq "" "$RUNNER_DOCKER_LOG" "BLOCKED時はDockerに一切触れない"
  assert_no_file "$work" "BLOCKED時はWORKを作らない"
  rm -rf "$temp"
}

# 33) 固定名の事前確認は WORK の判定（前回 run の生存判定を含む）より前に行う（P3-N1）。他の run の
#     コンテナが残っていれば、既定 WORK の状態にかかわらず固定名衝突として止め、WORK は変更しない。
case_integration_fixed_name_checked_before_work_judgement() {
  local temp tests_dir default first_cmd
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  tests_dir=$(setup_copied_runner "$temp" repo)
  default="$tests_dir/.integration-work"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"
  mkdir -p "$default"
  printf 'keep me\n' > "$default/previous.txt"
  # 生存中の前回 run（この selftest 自身の PID）を示す旧形式マーカー。WORK の判定が先なら判定で止まる。
  printf 'run=1700000000-%s-1\nwork=%s\n' "$$" "$default" > "$default/.ses-pitr-run-owner"
  runner_mock_run "$temp" fixed-name-occupied "-"
  RUNNER_UNDER_TEST=""
  first_cmd=$(grep -v -x -e info -e 'compose version' "$temp/docker.log" 2>/dev/null | head -n 1)
  assert_eq 1 "$RUNNER_RC" "固定名衝突のrunは1"
  assert_contains "$RUNNER_OUT" "fixed container name is occupied" "WORKの状態より先に固定名衝突を通知"
  assert_not_contains "$RUNNER_OUT" "既定の作業ディレクトリを自動退避できません" "固定名衝突時はWORKの判定まで進まない"
  assert_contains "$first_cmd" 'ps -aq --filter name=^/ses-bkup-int-source$' "Docker前提確認の直後（WORK判定より前）に固定名を確認する"
  assert_contains "$(cat "$default/previous.txt" 2>/dev/null)" "keep me" "固定名衝突時は既定WORKを変更しない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "compose -f" "固定名衝突時はCompose projectを操作しない"
  assert_no_file "$temp/chmod.log" "固定名衝突時はchmodしない"
  if compgen -G "$tests_dir/.integration-work.prev-*" > /dev/null; then
    test_fail "固定名衝突時は退避ディレクトリを作らない" "退避ディレクトリが存在します"
  else
    test_assert "固定名衝突時は退避ディレクトリを作らない"
  fi
  rm -rf "$temp"
}

# 34) 終了記録の無い前回 run が別の PID 名前空間（コンテナ・WSL・別の MSYS 等）で作られていれば、kill -0 が
#     失敗しても生存を判定できないため退避しない（P3-N2）。終了記録があれば名前空間に関係なく退避する。
case_integration_default_work_foreign_namespace_requires_end_record() {
  local temp tests_dir default dead_pid old_run
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  tests_dir=$(setup_copied_runner "$temp" repo)
  default="$tests_dir/.integration-work"
  RUNNER_UNDER_TEST="$tests_dir/run-integration.sh"
  ( exit 0 ) &
  dead_pid=$!
  wait "$dead_pid" 2>/dev/null
  if kill -0 "$dead_pid" 2>/dev/null; then
    test_fail "この名前空間では終了済みのPIDを用意できた（前提）" "pid=$dead_pid が生存しています"
  else
    test_assert "この名前空間では終了済みのPIDを用意できた（前提）"
  fi
  old_run="1700000000-$dead_pid-1"
  mkdir -p "$default"
  printf 'previous evidence\n' > "$default/previous.txt"
  printf 'run=%s\nwork=%s\nproject=selftest\npidns=%s\n' "$old_run" "$default" 'linux:selftest-foreign-boot:pid:[1]' \
    > "$default/.ses-pitr-run-owner"
  expect_work_rejected "$temp" "-" "生存を確認できません" "別PID名前空間で作られ終了記録の無い前回run"
  assert_contains "$(cat "$default/previous.txt" 2>/dev/null)" "previous evidence" "判定できない前回runの既定WORKを変更しない"
  assert_no_file "$tests_dir/.integration-work.prev-$old_run" "判定できない前回runの既定WORKを退避しない"

  printf 'run=%s\n' "$old_run" > "$default/.ses-pitr-run-ended"
  runner_mock_run "$temp" up-fail "-"
  RUNNER_UNDER_TEST=""
  assert_contains "$RUNNER_OUT" "前回の既定作業ディレクトリを退避しました" "終了記録があれば別PID名前空間の前回runも退避する"
  assert_file "$tests_dir/.integration-work.prev-$old_run/previous.txt" "前回の内容を退避先に保全"
  assert_file "$default/.ses-pitr-run-ended" "本runも終了処理の最後に終了記録を残す"
  assert_eq "run=$(marker_run_id "$default")" "$(cat "$default/.ses-pitr-run-ended" 2>/dev/null)" "終了記録のrun=は本runの所有マーカーと一致"
  rm -rf "$temp"
}

# 35) 所有マーカーは排他作成する（P3-S1）。既存ディレクトリを引き継ぐ場合（controller）に、準備段階の
#     空確認の後で .ses-pitr-run-owner の名前へ symlink が置かれても、追従して上書きせずに拒否する。
case_integration_owner_marker_exclusive_create() {
  local temp parent work victim real_find
  temp=$(mktemp -d)
  runner_mock_init "$temp"
  real_find=$(command -v find)
  mkdir -p "$temp/plant-bin"
  cat > "$temp/plant-bin/find" <<'MOCK'
#!/usr/bin/env bash
# 同じ WORK に対する 2 回目の find（準備段階の空確認）の直後に、所有マーカー名へ symlink を置く第三者を再現する
out=$("$MOCK_REAL_FIND" "$@")
rc=$?
if [[ "${1:-}" == "$MOCK_PLANT_WORK" ]]; then
  n=$(( $(cat "$MOCK_PLANT_COUNT" 2>/dev/null || echo 0) + 1 ))
  printf '%s\n' "$n" > "$MOCK_PLANT_COUNT"
  if (( n == 2 )); then
    ln -s "$MOCK_PLANT_TARGET" "$MOCK_PLANT_WORK/.ses-pitr-run-owner"
  fi
fi
if [[ -n "$out" ]]; then printf '%s\n' "$out"; fi
exit "$rc"
MOCK
  "$REAL_CHMOD" +x "$temp/plant-bin/find"
  parent="$temp/allowed/ses-pitr-run-s1"
  work="$parent/integration-work"
  mkdir -p "$work"
  write_controller_marker "$parent" "$work" "$parent"
  victim="$temp/victim.txt"
  printf 'victim original\n' > "$victim"
  RUNNER_PATH="$temp/plant-bin:$temp/bin:$PATH"
  runner_mock_run "$temp" up-fail "$work" MOCK_REAL_FIND="$real_find" MOCK_PLANT_WORK="$work" \
    MOCK_PLANT_COUNT="$temp/plant-count" MOCK_PLANT_TARGET="$victim"
  RUNNER_PATH=""
  if [[ -L "$work/.ses-pitr-run-owner" ]]; then
    test_assert "準備段階の空確認の後に所有マーカー名へsymlinkが置かれた（前提）"
  else
    test_fail "準備段階の空確認の後に所有マーカー名へsymlinkが置かれた（前提）" "symlink がありません"
  fi
  assert_eq 1 "$RUNNER_RC" "所有マーカーを排他作成できなければ1"
  assert_contains "$RUNNER_OUT" "所有マーカー（.ses-pitr-run-owner）を排他的に作成できません" "排他作成できない理由を通知"
  assert_eq "victim original" "$(cat "$victim" 2>/dev/null)" "symlink先のファイルを上書きしない"
  assert_no_file "$work/src-password" "所有できないWORKへ合成secretを書かない"
  assert_no_file "$temp/chmod.log" "所有できないWORKへchmodしない"
  assert_not_contains "$RUNNER_DOCKER_LOG" "up -d --build" "所有マーカーを作れなければcompose upしない"
  rm -rf "$temp"
}

run_case case_dependency_skip_is_nonzero
run_case case_setup_error_is_nonzero
run_case case_failing_backup_command_is_nonzero
run_case case_integration_runner_keeps_work_private
run_case case_integration_foreign_work_preflight_is_read_only
run_case case_integration_failure_capture_contract
run_case case_integration_redactor_pem_markers_keep_suffix_logs
run_case case_integration_redactor_multi_substring_and_overlap
run_case case_integration_redactor_bounds_and_fails_closed
run_case case_integration_diag_total_cap_512kib
run_case case_integration_secret_scan_fails_closed
run_case case_integration_p1_network_id_and_pitr_native_rc
run_case case_integration_drill_native_rc
run_case case_integration_compose_partial_up_capture
run_case case_integration_compose_wait_unhealthy_capture
run_case case_integration_foreign_network_rejected
run_case case_integration_foreign_container_not_cleaned
run_case case_integration_diag_deadline_bounded
run_case case_integration_missing_inputs_fail_safe
run_case case_integration_work_path_boundary_fails_closed
run_case case_integration_work_path_accepts_owned_dirs
run_case case_integration_full_success_no_diagnostics
run_case case_integration_late_stage_failure_records_stage
run_case case_integration_redactor_failure_after_command_success
run_case case_integration_tampered_work_is_not_normalized
run_case case_integration_default_work_rerun_rotates_previous_run
run_case case_integration_default_work_marker_mismatch_rejected
run_case case_integration_controller_workdir_outside_temp
run_case case_integration_default_work_preflight_and_prune_policy
run_case case_integration_default_work_pid_reuse_is_not_alive
run_case case_integration_work_created_during_preflight_is_rejected
run_case case_integration_runner_blocks_without_coreutils_mv
run_case case_integration_fixed_name_checked_before_work_judgement
run_case case_integration_default_work_foreign_namespace_requires_end_record
run_case case_integration_owner_marker_exclusive_create

test_summary "ACC-OPS-P2 harness self-tests"
