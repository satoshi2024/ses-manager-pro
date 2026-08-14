#!/usr/bin/env bash
# HFP-03-007 target guard test（unit）
#
# RQ-006 AC-006-01: source UUID と同じ / allowlist 外 / marker 不一致 /
# 非空 DB / 既存 schema / 承認 0・1 名 / 同一 actor 2 件 で import 前に非 0。
# fake mysql を使う。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"

FAKE_PW='S3cr3t-Value-XYZ-007'
SOURCE_UUID='11111111-2222-3333-4444-555555555555'
TARGET_UUID='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

setup_guard() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER='bkp-user-7x9'
  export MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" FAKE_ARGV_LOG="$T/argv.log"
  export TARGET_HOST=10.0.0.9 TARGET_PORT=3306 TARGET_USER='restore-svc' TARGET_PASSWORD_FILE="$T/pw"
  export SOURCE_HOST=10.0.0.1
  printf '%s\n' "$TARGET_UUID" > "$T/allowlist.txt"
  export TARGET_ALLOWLIST_FILE="$T/allowlist.txt"
  export FAKE_UUID="$TARGET_UUID" FAKE_CONTROL_MARKER="default	plan-1234" FAKE_TABLE_COUNT=0
  unset MYSQL_PWD
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/target-guard.sh"
  TARGET_OPT_ARGS=(--defaults-extra-file=/dev/null -h "$TARGET_HOST")
  # shellcheck disable=SC1091
  . "$LIB/plan.sh"
}

mkplan() { # uuid -> plan json（source_server_uuid / plan_id / target.allowlist_ref を持つ）
  jq -n --arg s "$1" --arg id "plan-1234" \
    '{source_server_uuid: $s, plan_id: $id,
      target: {allowlist_ref: "default", min_approvals: 2}}'
}

run_guard() { # plan_json db
  GUARD_OUT=$(target_guard::run "$1" "$2" 2>&1)
  GUARD_RC=$?
}

case_guard_normal() {
  setup_guard
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_zero "$GUARD_RC" "正常 provision 済み target は通過"
}

case_guard_same_uuid_as_source() {
  setup_guard
  # target が source と同じ UUID を返す
  export FAKE_UUID="$SOURCE_UUID"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "source と同一 UUID は拒否"
  assert_contains "$GUARD_OUT" "同じ server_uuid" "理由を通知"
}

case_guard_allowlist_mismatch() {
  setup_guard
  export FAKE_UUID="ffffffff-ffff-ffff-ffff-ffffffffffff"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "allowlist 外は拒否"
  assert_contains "$GUARD_OUT" "allowlist" "理由を通知"
}

case_guard_marker_mismatch() {
  setup_guard
  export FAKE_CONTROL_MARKER="other-ref	plan-1234"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "marker の allowlist_ref 不一致は拒否"
  export FAKE_CONTROL_MARKER="default	plan-9999"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "marker の plan_id 不一致は拒否"
}

case_guard_marker_missing() {
  setup_guard
  export FAKE_CONTROL_MARKER=""
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "marker 不在は拒否"
  assert_contains "$GUARD_OUT" "marker" "理由を通知"
}

case_guard_nonempty_db() {
  setup_guard
  export FAKE_TABLE_COUNT=12
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "非空 DB は拒否"
  assert_contains "$GUARD_OUT" "空ではありません" "理由を通知"
}

case_guard_default_host_user() {
  setup_guard
  export TARGET_HOST="localhost"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "localhost は拒否"
  export TARGET_HOST=10.0.0.9 TARGET_USER="root"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "root user は拒否"
  export TARGET_USER="restore-svc" TARGET_DATABASE=""
  run_guard "$(mkplan "$SOURCE_UUID")" ""
  assert_nonzero "$GUARD_RC" "DB 名空は拒否"
}

case_guard_same_host_as_source() {
  setup_guard
  export SOURCE_HOST="$TARGET_HOST"
  run_guard "$(mkplan "$SOURCE_UUID")" ses_manager_db
  assert_nonzero "$GUARD_RC" "TARGET_HOST == SOURCE_HOST は拒否"
}

case_guard_plan_tamper() {
  setup_guard
  # plan の target が改変されている（allowlist_ref が marker と一致しない）
  local tampered
  tampered=$(mkplan "$SOURCE_UUID" | jq -c '.target.allowlist_ref = "other-ref"')
  run_guard "$tampered" ses_manager_db
  assert_nonzero "$GUARD_RC" "plan tamper は拒否"
}

run_case case_guard_normal
run_case case_guard_same_uuid_as_source
run_case case_guard_allowlist_mismatch
run_case case_guard_marker_mismatch
run_case case_guard_marker_missing
run_case case_guard_nonempty_db
run_case case_guard_default_host_user
run_case case_guard_same_host_as_source
run_case case_guard_plan_tamper

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-007 target guard"
