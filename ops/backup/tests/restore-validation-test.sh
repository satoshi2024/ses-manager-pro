#!/usr/bin/env bash
# HFP-03-008 restore validation test
#
# - 正常: Flyway / CHECK TABLE / counts / markers / uploads hash / references /
#   smoke 全 PASS → READY_FOR_CUTOVER
# - 負: Flyway failed、version 不一致、count 不一致、missing reference、
#   hash mismatch、after marker 存在、smoke failure → FAILED_VALIDATION
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"
VALIDATE="$ROOT/ops/backup/validate-restore.sh"
PLAN="$ROOT/ops/backup/plan-restore.sh"

FAKE_PW='S3cr3t-Value-XYZ-008'
LINEAGE='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'

setup_validate() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath" "$T/index" "$T/binlog" "$T/plans" "$T/work"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql"
  export TARGET_HOST=10.0.0.9 TARGET_PORT=3306 TARGET_USER='restore-svc' TARGET_PASSWORD_FILE="$T/pw"
  export TARGET_SSL_CAPATH="$T/capath" TARGET_TLS_MODE=VERIFY_CA
  export TARGET_DATABASE=ses_manager_db PLANS_DIR="$T/plans" BACKUP_WORK_DIR="$T/work" INDEX_DIR="$T/index"
  export BINLOG_INDEX="$T/binlog/binlog-index.json"
  export FAKE_COUNT=2 FAKE_FLYWAY_MAX=42 FAKE_CHECK_TABLE=OK FAKE_MARKER_BEFORE=1 FAKE_MARKER_AFTER=0
  export FAKE_TABLE_COUNT=0 FAKE_MYSQL_STATUS_ROW=$'8.0.36\t11111111-2222-3333-4444-555555555555\t1\tROW\tCRC32\t0\tON\tYES\t/var/lib/mysql/binlog.000001\t2592000'
  unset MYSQL_PWD FAKE_FLYWAY_FAILED FAKE_FLYWAY_MAX FAKE_CHECK_TABLE \
    FAKE_MARKER_BEFORE FAKE_MARKER_AFTER FAKE_REF_KEYS FAKE_COUNT FAKE_TABLE_COUNT
  export FAKE_COUNT=2 FAKE_FLYWAY_MAX=42 FAKE_CHECK_TABLE=OK FAKE_MARKER_BEFORE=1 FAKE_MARKER_AFTER=0
  chmod +x "$ROOT/ops/backup/providers/approval-verifier-local.sh" 2>/dev/null || true
}

# checkpoint index（flyway / counts / uploads inventory の出所）
make_ckpt_index() { # flyway counts_json uploads_inv_json
  mkdir -p "$T/work/index"
  jq -n --arg f "$1" --argjson c "$2" --argjson u "$3" \
    '{kind:"checkpoint", status:"VALID", flyway_max_success:$f,
      critical_table_counts:$c, uploads:{inventory:$u},
      consistency_time_utc:"2026-08-14T09:15:00Z",
      source_lineage:"'"$LINEAGE"'", binlog_end:{file:"binlog.000012", position:400},
      uploads_snapshot_id:"u1", restic_snapshot_id:"s1"}' > "$T/work/index/checkpoint-fixture.json"
}

# plan（検証対象として)
make_plan() {
  jq -n --arg id "plan-008" \
    '{schema_version:1, plan_id:$id, kind:"restore-plan", state:"READY",
      requested_target:"2026-08-14T09:15:00Z", rpo_seconds:0,
      source_server_uuid:"11111111-2222-3333-4444-555555555555",
      effective_checkpoint:{time:"2026-08-14T09:15:00Z", index:"checkpoint-fixture"},
      target:{allowlist_ref:"default", min_approvals:2},
      valid_until_utc:"2099-01-01T00:00:00Z"}' > "$T/plans/plan-008.json"
  local sha
  # shellcheck disable=SC1091
  . "$LIB/plan.sh"
  sha=$(printf '%s' "$(plan::content_for_sha "$(cat "$T/plans/plan-008.json")")" | sha256sum | awk '{print $1}')
  printf '%s\n' "$sha" > "$T/plans/plan-008.json.sha256"
}

# uploads staging fixture
make_uploads() { # marker あり / inventory 一致
  mkdir -p "$T/uploads/published"
  printf 'marker-before-checkpoint\n' > "$T/uploads/published/marker-before.txt"
  INVENTORY=$(cd "$T/uploads" && find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do
    rel=${f#./}
    printf '{"path":"%s","sha256":"%s"}\n' "$rel" "$(sha256sum "$f" | awk '{print $1}')"
  done | jq -s -c .)
}

run_validate() {
  VAL_OUT=$("$VALIDATE" --plan plan-008 --uploads-dir "$T/uploads" --smoke "$SMOKE" 2>&1)
  VAL_RC=$?
}

setup_common_fixture() {
  setup_validate
  make_uploads
  make_ckpt_index 42 '{"marker_test":2}' "$INVENTORY"
  make_plan
  export VALIDATE_TABLES="marker_test"
  export VALIDATE_MARKERS_JSON='{"table":"marker_test","before":"marker-before-checkpoint","after":"marker-after-checkpoint"}'
  export VALIDATE_DB_REFERENCE_QUERIES='{"published":{"query":"SELECT storage_key FROM t_document_version WHERE storage_key IS NOT NULL"}}'
  export FAKE_REF_KEYS="published/marker-before.txt"
  cat > "$T/smoke.sh" <<'EOF'
#!/usr/bin/env bash
exit "${SMOKE_RC:-0}"
EOF
  chmod +x "$T/smoke.sh"
  export SMOKE="$T/smoke.sh"
}

case_validate_normal() {
  setup_common_fixture
  export SMOKE_RC=0
  run_validate
  assert_zero "$VAL_RC" "正常系は exit 0"
  assert_contains "$VAL_OUT" '"state": "READY_FOR_CUTOVER"' "READY_FOR_CUTOVER"
  assert_contains "$VAL_OUT" '"app_smoke": "PASS"' "smoke PASS"
  assert_contains "$VAL_OUT" '"markers": "PASS"' "markers PASS"
  assert_contains "$VAL_OUT" '"uploads_hash": "PASS"' "uploads hash PASS"
  assert_contains "$VAL_OUT" '"uploads_references": "PASS"' "references PASS"
}

case_validate_decoy_checkpoint_ignored() {
  setup_common_fixture
  export SMOKE_RC=0
  # R1 P1-04: plan が参照しない checkpoint index（flyway failed のデコイ）が
  # あっても、検証は plan の effective_checkpoint.index のみを読む
  jq -n '{kind:"checkpoint", status:"VALID", flyway_max_success:"99",
    critical_table_counts:{marker_test:999}, uploads:{inventory:[]},
    consistency_time_utc:"2026-08-14T09:15:00Z"}' \
    > "$T/work/index/checkpoint-aaa.json"
  run_validate
  assert_zero "$VAL_RC" "デコイ checkpoint は検証に影響しない（exit 0）"
  assert_contains "$VAL_OUT" '"state": "READY_FOR_CUTOVER"' "READY_FOR_CUTOVER"
  assert_contains "$VAL_OUT" '"flyway": "PASS"' "flyway PASS（plan の checkpoint 由来）"
  assert_contains "$VAL_OUT" '"counts": "PASS"' "counts PASS（plan の checkpoint 由来）"
}

case_validate_flyway_failed() {
  setup_common_fixture
  export FAKE_FLYWAY_FAILED=1
  run_validate
  assert_nonzero "$VAL_RC" "flyway failed は非 0"
  assert_contains "$VAL_OUT" '"state": "FAILED_VALIDATION"' "FAILED_VALIDATION"
  assert_contains "$VAL_OUT" '"flyway": "FAIL"' "flyway FAIL"
}

case_validate_flyway_version_mismatch() {
  setup_common_fixture
  export FAKE_FLYWAY_MAX=99
  run_validate
  assert_nonzero "$VAL_RC" "flyway version 不一致は非 0"
  assert_contains "$VAL_OUT" '"flyway": "FAIL"' "flyway FAIL"
}

case_validate_count_mismatch() {
  setup_common_fixture
  export FAKE_COUNT=999
  run_validate
  assert_nonzero "$VAL_RC" "count 不一致は非 0"
  assert_contains "$VAL_OUT" '"counts": "FAIL"' "counts FAIL"
}

case_validate_marker_after_present() {
  setup_common_fixture
  export FAKE_MARKER_AFTER=1
  run_validate
  assert_nonzero "$VAL_RC" "after marker 存在は非 0"
  assert_contains "$VAL_OUT" '"markers": "FAIL"' "markers FAIL"
}

case_validate_missing_reference() {
  setup_common_fixture
  export FAKE_REF_KEYS="published/missing-file.txt"
  run_validate
  assert_nonzero "$VAL_RC" "missing reference は非 0"
  assert_contains "$VAL_OUT" '"uploads_references": "FAIL"' "references FAIL"
  assert_contains "$VAL_OUT" "missing-file" "missing 名を通知"
}

case_validate_hash_mismatch() {
  setup_common_fixture
  # inventory を 1 byte 改変して期待値とずらす
  export INVENTORY
  INVENTORY=$(printf '%s' "$INVENTORY" | jq -c '.[0].sha256 = "0000000000000000000000000000000000000000000000000000000000000000"')
  make_ckpt_index 42 '{"marker_test":2}' "$INVENTORY"
  run_validate
  assert_nonzero "$VAL_RC" "hash mismatch は非 0"
  assert_contains "$VAL_OUT" '"uploads_hash": "FAIL"' "uploads hash FAIL"
}

case_validate_orphan_reported_not_fatal() {
  setup_common_fixture
  # 未参照 extra file（orphan report のみで fatal にしない）
  printf 'orphan\n' > "$T/uploads/published/orphan-extra.txt"
  run_validate
  assert_zero "$VAL_RC" "orphan は fatal にしない"
  assert_contains "$VAL_OUT" 'orphan-extra.txt' "orphan report に載る"
}

case_validate_smoke_failure() {
  setup_common_fixture
  export SMOKE_RC=7
  run_validate
  assert_nonzero "$VAL_RC" "smoke failure は非 0"
  assert_contains "$VAL_OUT" '"app_smoke": "FAIL' "smoke FAIL"
  assert_contains "$VAL_OUT" '"state": "FAILED_VALIDATION"' "FAILED_VALIDATION"
}

case_validate_no_smoke_script() {
  setup_common_fixture
  run_validate() {
    VAL_OUT=$("$VALIDATE" --plan plan-008 --uploads-dir "$T/uploads" 2>&1)
    VAL_RC=$?
  }
  run_validate
  assert_nonzero "$VAL_RC" "smoke script なしは非 0（検証未完了扱い）"
  assert_contains "$VAL_OUT" '"app_smoke": "SKIP' "smoke SKIP"
}

run_case case_validate_normal
run_case case_validate_decoy_checkpoint_ignored
run_case case_validate_flyway_failed
run_case case_validate_flyway_version_mismatch
run_case case_validate_count_mismatch
run_case case_validate_marker_after_present
run_case case_validate_missing_reference
run_case case_validate_hash_mismatch
run_case case_validate_orphan_reported_not_fatal
run_case case_validate_smoke_failure
run_case case_validate_no_smoke_script

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-008 restore validation"
