#!/usr/bin/env bash
# HFP-03-012 restore drill test
#
# 実 script（plan-restore / restore / validate / cutover）を fake CLI に対して
# 実行する drill の検証:
#   正常 SUCCESS / plan 失敗 / validate 未 READY / evidence 欠如 /
#   ping のみの代替拒否 / RTO 超過 / ドリルでの write-enable 拒否
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
DRILL="$ROOT/ops/backup/restore-drill.sh"
VERIFIER="$ROOT/ops/backup/providers/approval-verifier-local.sh"

FAKE_PW='S3cr3t-Value-XYZ-012'

# 合成 metadata + fake ツール一式（restore-flow-test / restore-validation-test の pattern）
setup_drill() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/index" "$T/plans" "$T/pubkeys" "$T/binlog" "$T/repo"
  export INDEX_DIR="$T/index" PLANS_DIR="$T/plans" BACKUP_WORK_DIR="$T/work"
  export BINLOG_INDEX="$T/binlog/binlog-index.json"
  mkdir -p "$BACKUP_WORK_DIR" "$T/uploads/published"
  printf 'marker-before-checkpoint\n' > "$T/uploads/published/marker-before.txt"

  # 実 restic repo + payload（restore-flow-test と同 pattern）
  export RESTIC_REPOSITORY="$T/repo" RESTIC_PASSWORD_FILE="$T/pw"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  /usr/local/bin/restic init > /dev/null 2>&1
  local full_payload="$T/full-payload" ckpt_payload="$T/ckpt-payload"
  mkdir -p "$full_payload/db" "$full_payload/uploads/published" "$ckpt_payload/uploads/published"
  printf '%s\n' "-- MySQL dump (source-data=2)" \
    "-- CHANGE MASTER TO MASTER_LOG_FILE='binlog.000001', MASTER_LOG_POS=4;" \
    "CREATE TABLE t (id INT); INSERT INTO t VALUES (1);" > "$full_payload/db/database.sql"
printf 'marker-before-full\n' > "$full_payload/uploads/published/marker.txt"
printf 'marker-before-checkpoint\n' > "$ckpt_payload/uploads/published/marker-before.txt"
  make_manifest "$full_payload"
  make_manifest "$ckpt_payload"
  FULL_SNAP=$(/usr/local/bin/restic backup "$full_payload" --tag "kind=full" --json 2>/dev/null \
    | jq -rs 'map(select(.message_type=="summary"))[0].snapshot_id')
  CKPT_SNAP=$(/usr/local/bin/restic backup "$ckpt_payload" --tag "kind=checkpoint" --json 2>/dev/null \
    | jq -rs 'map(select(.message_type=="summary"))[0].snapshot_id')
  local n bins="[]"
  for n in 1 2; do
    local bf="binlog.00000$n"
    printf 'binlog-event-%s\n' "$n" > "$T/$bf"
    local bs
    bs=$(/usr/local/bin/restic backup "$T/$bf" --tag "kind=binlog" --json 2>/dev/null \
      | jq -rs 'map(select(.message_type=="summary"))[0].snapshot_id')
    bins=$(printf '%s' "$bins" | jq -c --arg f "$bf" --arg s "$bs" \
      --arg sha "$(sha256sum "$T/$bf" | awk '{print $1}')" \
      '. + [{file: $f, snapshot_id: $s, sha256: $sha, size: 1}]')
  done
  printf '%s\n' "$bins" > "$BINLOG_INDEX"

  local now full_ts cp_ts
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  full_ts=$(date -u -d "$now - 1200 seconds" +%Y-%m-%dT%H:%M:%SZ)
  cp_ts=$(date -u -d "$now - 600 seconds" +%Y-%m-%dT%H:%M:%SZ)
  jq -n --arg t "$full_ts" --arg snap "$FULL_SNAP" \
    '{kind:"full", status:"VALID", source_server_uuid:"11111111-2222-3333-4444-555555555555",
      source_lineage:"lin-012", consistency_time_utc: $t, restic_snapshot_id: $snap,
      binlog_start:{file:"binlog.000001", position:4}, uploads_snapshot_id:"up-full",
      archive:"none", full_only:false}' > "$T/index/full-012.json"
  jq -n --arg t "$cp_ts" --arg snap "$CKPT_SNAP" \
    '{kind:"checkpoint", status:"VALID", source_server_uuid:"11111111-2222-3333-4444-555555555555",
      source_lineage:"lin-012", consistency_time_utc: $t, restic_snapshot_id: $snap,
      binlog_end:{file:"binlog.000002", position:154}, uploads_snapshot_id:"up-cp",
      manifest_sha256:"m"}' > "$T/index/checkpoint-012.json"
  # validate が読む checkpoint index（flyway/counts/uploads inventory 付き）
  mkdir -p "$BACKUP_WORK_DIR/index"
  local mb_sha mb_size
  mb_sha=$(sha256sum "$T/uploads/published/marker-before.txt" | awk '{print $1}')
  mb_size=$(stat -c %s "$T/uploads/published/marker-before.txt")
  jq -n --arg t "$cp_ts" --arg sha "$mb_sha" --argjson sz "$mb_size" \
    '{kind:"checkpoint", status:"VALID", consistency_time_utc: $t,
      flyway_max_success:"42", critical_table_counts:{marker_test:1},
      uploads:{inventory:[{path:"published/marker-before.txt",
        sha256:$sha, size:$sz}]}}' \
    > "$BACKUP_WORK_DIR/index/checkpoint-012.json"

  # fake CLI（PATH の fixtures を利用）+ validate 用 env
  export MYSQL_CLIENT_BIN="$HERE/fixtures/bin/mysql"
  export RESTIC_BIN="$HERE/fixtures/bin/restic"
  export MYSQLBINLOG_BIN="$HERE/fixtures/bin/mysqlbinlog"
  export FAKE_COUNT=1 FAKE_FLYWAY_MAX=42 FAKE_CHECK_TABLE=OK \
    FAKE_MARKER_BEFORE=1 FAKE_MARKER_AFTER=0
  export VALIDATE_TABLES="marker_test"
  export VALIDATE_MARKERS_JSON='{"table":"marker_test","before":"marker-before-checkpoint","after":"marker-after-checkpoint"}'
  export TARGET_HOST=10.0.0.9 TARGET_USER=restore-svc TARGET_PORT=3306
  mkdir -p "$T/capath"
  export TARGET_SSL_CAPATH="$T/capath" TARGET_TLS_MODE=VERIFY_CA
  export TARGET_PASSWORD_FILE="$T/pw" TARGET_DATABASE=ses_manager_db
  export TARGET_ALLOWLIST_FILE="$T/allowlist.txt"
  printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n' > "$T/allowlist.txt"
  export FAKE_UUID='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
  export SOURCE_HOST=10.0.0.1
  export FAKE_TABLE_COUNT=0
  export REPOSITORY_LOCK_DIR="$T/locks"
  mkdir -p "$REPOSITORY_LOCK_DIR"
  export BACKUP_REPOSITORY="$T/repo" RESTIC_REPOSITORY="$T/repo"
  export APP_SMOKE_SCRIPT="$T/cutover-smoke.sh"
  cat > "$T/cutover-smoke.sh" <<'EOF'
#!/usr/bin/env bash
# cutover リハーサルは write-enable 前に止めるため smoke を失敗させる
exit 1
EOF
  chmod +x "$T/cutover-smoke.sh"
  cat > "$T/validate-smoke.sh" <<'EOF'
#!/usr/bin/env bash
# validate 用 read-only smoke（PASS）
exit 0
EOF
  chmod +x "$T/validate-smoke.sh"
  export DRILL_SMOKE_SCRIPT="$T/validate-smoke.sh"
  cat > "$T/write-enable.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$T/write-enable.sh"
  export WRITE_ENABLE_COMMAND="$T/write-enable.sh"
  # 承認鍵
  openssl genrsa -out "$T/priv1.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv1.pem" -pubout -out "$T/pub1.pem" 2>/dev/null
  openssl genrsa -out "$T/priv2.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv2.pem" -pubout -out "$T/pub2.pem" 2>/dev/null
  cp "$T/pub1.pem" "$T/pubkeys/alice.pem"
  cp "$T/pub2.pem" "$T/pubkeys/bob.pem"
  export APPROVAL_PUBKEY_DIR="$T/pubkeys"
  chmod +x "$VERIFIER" 2>/dev/null || true
}

# 簡易 manifest（tmp へ書いてから mv する。直書きすると find が空 manifest を見る）
make_manifest() { # dir
  local dir=$1
  jq -s '{schema_version:1, files: .}' \
    < <(cd "$dir" && find . -type f -not -name 'manifest.json*' | while read -r f; do
      printf '{"path":"%s","type":"file","size":%s,"sha256":"%s"}\n' "${f#./}" \
        "$(stat -c %s "$f")" "$(sha256sum "$f" | awk '{print $1}')"
    done) > "$dir/manifest.json.tmp"
  mv "$dir/manifest.json.tmp" "$dir/manifest.json"
  sha256sum "$dir/manifest.json" | awk '{print $1}' > "$dir/manifest.sha256"
}

make_claims() { # plan_path
  local plan_path=$1
  local sha
  sha=$(sha256sum "$plan_path" | awk '{print $1}')
  local future
  future=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)
  local a
  for a in alice bob; do
    local key
    [[ "$a" == "alice" ]] && key="$T/priv1.pem" || key="$T/priv2.pem"
    jq -n --arg ps "$sha" --arg tu "$FAKE_UUID" --arg a "$a" --arg r manager \
      --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$future" --arg ct "CHG-012" \
      '{plan_sha256:$ps,target_uuid:$tu,change_ticket:$ct,actor:$a,role:$r,
        issued_at_utc:$i,expires_at_utc:$e}' > "$T/claim-$a.json"
    "$VERIFIER" sign "$T/claim-$a.json" "$key"
  done
}

# 事前に plan を生成しておく（drill の plan step は検証のみ行う）
prebuild_plan() {
  local ts
  ts=$(date -u -d "now - 500 seconds" +%Y-%m-%dT%H:%M:%SZ)
  local out rc
  out=$(INDEX_DIR="$INDEX_DIR" BINLOG_INDEX="$BINLOG_INDEX" \
    PLANS_DIR="$PLANS_DIR" BACKUP_WORK_DIR="$BACKUP_WORK_DIR" ALLOWLIST_REF=default \
    "$ROOT/ops/backup/plan-restore.sh" --target "$ts" 2>&1)
  rc=$?
  if [[ "$rc" -eq 0 ]]; then
    local plan_path
    plan_path=$(ls "$PLANS_DIR"/*.json | head -1)
    export FAKE_CONTROL_MARKER="default $(basename "$plan_path" .json)"
    make_claims "$plan_path"
  fi
  return "$rc"
}

run_drill() { # plan_id
  OUT=$("$DRILL" --plan "$1" --report-dir "$T/drill-report" \
    --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  RC=$?
}

case_drill_success() {
  setup_drill
  prebuild_plan > /dev/null
  echo '' > /dev/null # (smoke は固定)
  run_drill "$(ls "$PLANS_DIR"/*.json | head -1 | xargs -r basename | sed "s/.json$//")"
  assert_zero "$RC" "drill 成功"
  assert_contains "$OUT" '"state": "SUCCESS"' "SUCCESS"
  assert_contains "$OUT" '"rto_ok": true' "RTO OK"
  assert_file "$T/drill-report/drill-report.json" "report"
  local seg
  seg=$(jq -r '.segments | length' "$T/drill-report/drill-report.json")
  assert_eq "5" "$seg" "5 segments（plan/integrity/restore/validate/cutover）"
}

case_drill_plan_fail() {
  setup_drill
  # checkpoint を削除 → plan 生成失敗
  rm -f "$T/index"/checkpoint-*.json
  prebuild_plan > /dev/null 2>&1
  assert_nonzero "$?" "plan 失敗は非 0"
}

case_drill_validate_not_ready() {
  setup_drill
  prebuild_plan > /dev/null
  # smoke は fixture 固定（validate=PASS / cutover=FAIL）
  # validate を FAIL させる（count 不一致）
  export FAKE_COUNT=999
  run_drill "$(ls "$PLANS_DIR"/*.json | head -1 | xargs -r basename | sed "s/.json$//")"
  assert_nonzero "$RC" "validate 未 READY は非 0"
  assert_contains "$OUT" "READY_FOR_CUTOVER" "理由"
}

case_drill_evidence_missing() {
  setup_drill
  prebuild_plan > /dev/null
  # smoke は fixture 固定（validate=PASS / cutover=FAIL）
  # replay 失敗 → restore が uploads_ready を出せない（evidence 欠如）
  export FAKE_REPLAY_RC=1
  run_drill "$(ls "$PLANS_DIR"/*.json | head -1 | xargs -r basename | sed "s/.json$//")"
  assert_nonzero "$RC" "evidence 欠如は非 0"
  unset FAKE_REPLAY_RC
}

case_drill_ping_only_rejected() {
  setup_drill
  prebuild_plan > /dev/null
  # smoke は fixture 固定（validate=PASS / cutover=FAIL）
  # validate が READY を返せない（ping 相当の代替では drill は通らない）
  export FAKE_COUNT=999
  run_drill "$(ls "$PLANS_DIR"/*.json | head -1 | xargs -r basename | sed "s/.json$//")"
  assert_nonzero "$RC" "ping のみの代替は拒否"
  assert_contains "$OUT" "mysqladmin ping" "理由"
  unset FAKE_COUNT
}

case_drill_rto_exceeded() {
  setup_drill
  prebuild_plan > /dev/null
  # smoke は fixture 固定（validate=PASS / cutover=FAIL）
  export RTO_SECONDS=1
  run_drill "$(ls "$PLANS_DIR"/*.json | head -1 | xargs -r basename | sed "s/.json$//")"
  assert_nonzero "$RC" "RTO 超過は非 0"
  assert_contains "$OUT" "RTO" "理由"
  unset RTO_SECONDS
}

case_drill_write_enable_refused() {
  setup_drill
  prebuild_plan > /dev/null
  # write-enable を動かす: cutover smoke を PASS に差し替え
  cat > "$T/cutover-smoke.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$T/cutover-smoke.sh"
  run_drill "$(ls "$PLANS_DIR"/*.json | head -1 | xargs -r basename | sed "s/.json$//")"
  assert_nonzero "$RC" "ドリルで write-enable されたら拒否"
  assert_contains "$OUT" "write-enable" "理由"
}

run_case case_drill_success
run_case case_drill_plan_fail
run_case case_drill_validate_not_ready
run_case case_drill_evidence_missing
run_case case_drill_ping_only_rejected
run_case case_drill_rto_exceeded
run_case case_drill_write_enable_refused

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-012 restore drill"
