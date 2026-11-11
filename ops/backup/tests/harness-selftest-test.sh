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
# 各ケースは fresh な bash -c 子プロセスで実行する。source 済み関数は子 bash へ
# 継承されないため、common.sh をロードした/しない状態を確実に切り分けられる。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091  # HERE 経由の source は静的解決できない
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"

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

# 6) 失敗証跡フローの契約を固定する。Docker自体は個別シナリオでモックし、
#    部分起動時も所有確認した最大5サービスだけを診断し、target を先に収集する。
case_integration_failure_capture_contract() {
  local runner
  runner=$(cat "$ROOT/ops/backup/tests/run-integration.sh")
  assert_contains "$runner" "upAttempted=1" "compose up 前に所有状態を記録"
  assert_contains "$runner" "count <= 5" "部分起動の診断対象を5サービス以下に制限"
  assert_contains "$runner" 'for service in target source tool scheduler replica' "targetを先に診断"
  assert_contains "$runner" 'timeout -k 1s' "Docker診断に終了猶予付きtimeoutを設定"
  assert_contains "$runner" 'DIAG_DEADLINE_MS=$((diag_start_ms + 15000))' "診断全体の15秒期限を設定"
  assert_contains "$runner" 'FAILURE_NATIVE_RC=$PITR_RC' "PITRのnative終了コードを保存"
  assert_contains "$runner" 'FAILURE_NATIVE_RC=$DRILL_RC' "drillのnative終了コードを保存"
  assert_contains "$runner" 'command_status=("${PIPESTATUS[@]}")' "診断パイプ直後に両終了コードを取得"
  assert_not_contains "$runner" 'ses-backup-integration_ses-bkup' "固定network名へのfallbackを残さない"
  assert_contains "$runner" 'PROJECT_OWNED && upAttempted' "未所有projectをcleanupしない"
}

# 7) ストリーミング redactor は通常secret・複数/未閉鎖PEM・巨大行を漏らさず、
#    出力上限とredactor失敗コードを保つ。
case_integration_redactor_bounds_and_fails_closed() {
  local temp runner function_file work awk_bin result rc bytes
  temp=$(mktemp -d)
  runner="$ROOT/ops/backup/tests/run-integration.sh"
  function_file="$temp/redactor-functions.sh"
  work="$temp/work"
  mkdir -p "$work/keys" "$temp/fake-bin"
  sed -n '/^redact_stream_to_file() {/,/^append_diagnostic_line() {/p' "$runner" | sed '$d' > "$function_file"
  WORK_AWK="$work"
  printf 'src-secret-unique\n' > "$work/src-password"
  printf 'tgt-secret-unique\n' > "$work/tgt-password"
  printf 'repo-secret-unique\n' > "$work/repo-password"
  printf 'restore-secret-unique\n' > "$work/restore-svc-pw"
  printf '%s\n' '-----BEGIN RSA PRIVATE KEY-----' 'key-material-unique' '-----END RSA PRIVATE KEY-----' > "$work/keys/priv1.pem"
  : > "$work/keys/priv2.pem"
  # shellcheck disable=SC1090
  . "$function_file"
  printf '%s\n' 'src-secret-unique' 'before -----BEGIN RSA PRIVATE KEY-----one-----END RSA PRIVATE KEY----- middle -----BEGIN EC PRIVATE KEY-----two-----END EC PRIVATE KEY----- after' '-----BEGIN RSA PRIVATE KEY-----' 'multi-line-key-unique' '-----END RSA PRIVATE KEY-----' '-----BEGIN OPENSSH PRIVATE KEY-----' 'unclosed-key-unique' \
    | redact_stream_to_file "$temp/redacted.txt" 65536 > "$temp/stream.txt" 2> "$temp/redactor.err"
  rc=$?
  result=$(cat "$temp/redacted.txt")
  assert_zero "$rc" "redactor はPEM複数形式を処理"
  assert_not_contains "$result" "src-secret-unique" "password literal をファイルへ出さない"
  assert_not_contains "$result" "key-material-unique" "秘密鍵本体を出さない"
  assert_not_contains "$result" "multi-line-key-unique" "複数行秘密鍵を出さない"
  assert_not_contains "$result" "unclosed-key-unique" "未閉鎖秘密鍵の後続を出さない"

  head -c 70000 /dev/zero | tr '\000' A | redact_stream_to_file "$temp/large.txt" 65536 > /dev/null 2> "$temp/large.err"
  rc=$?
  bytes=$(wc -c < "$temp/large.txt")
  assert_zero "$rc" "巨大な単一行でもredactorが成功"
  assert_eq 65536 "$bytes" "1 section の出力を64KiBに制限"

  cat > "$temp/fake-bin/awk" <<'MOCK'
#!/usr/bin/env bash
exit 77
MOCK
  chmod +x "$temp/fake-bin/awk"
  PATH="$temp/fake-bin:$PATH" redact_stream_to_file "$temp/failed.txt" 65536 </dev/null 2>/dev/null
  rc=$?
  assert_nonzero "$rc" "redactor失敗を成功扱いしない"
  rm -rf "$temp"
}

run_case case_dependency_skip_is_nonzero
run_case case_setup_error_is_nonzero
run_case case_failing_backup_command_is_nonzero
run_case case_integration_runner_keeps_work_private
run_case case_integration_foreign_work_preflight_is_read_only
run_case case_integration_failure_capture_contract
run_case case_integration_redactor_bounds_and_fails_closed

test_summary "ACC-OPS-P2 harness self-tests"
