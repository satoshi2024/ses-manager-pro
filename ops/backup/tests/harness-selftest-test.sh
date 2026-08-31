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

run_case case_dependency_skip_is_nonzero
run_case case_setup_error_is_nonzero
run_case case_failing_backup_command_is_nonzero
run_case case_integration_runner_keeps_work_private

test_summary "ACC-OPS-P2 harness self-tests"
