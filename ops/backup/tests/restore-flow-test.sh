#!/usr/bin/env bash
# HFP-03-007 restore flow test（fake mysql/mysqlbinlog + 実 restic）
#
# - 正常系: plan → guard → approval → restic restore → dump import →
#   binlog replay 単一 connection → uploads staging
# - 負: 途中 binlog failure で FAILED_RESTORE 隔離、source 不変
# - mysql connection 数を argv log で検証（replay は 1 回）
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"
RESTORE="$ROOT/ops/backup/restore.sh"
PLAN="$ROOT/ops/backup/plan-restore.sh"

FAKE_PW='S3cr3t-Value-XYZ-007'
SOURCE_UUID='11111111-2222-3333-4444-555555555555'
TARGET_UUID='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
LINEAGE='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'

setup_restore() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/capath" "$T/index" "$T/binlog" "$T/plans" "$T/repo" "$T/work"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_SSL_CAPATH="$T/capath" MYSQL_TLS_MODE=VERIFY_CA
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export RESTIC_BIN="$FIXTURES/restic" RESTIC_REAL_BIN=/usr/local/bin/restic
  export RESTIC_ARGV_LOG="$T/restic-argv.log" FAKE_ARGV_LOG="$T/argv.log"
  export RESTIC_PASSWORD_FILE="$T/pw" BACKUP_REPOSITORY="$T/repo"
  export PLANS_DIR="$T/plans" INDEX_DIR="$T/index" BINLOG_INDEX="$T/binlog/binlog-index.json"
  export BACKUP_WORK_DIR="$T/work"
  export TARGET_HOST=10.0.0.9 TARGET_PORT=3306 TARGET_USER='restore-svc' TARGET_PASSWORD_FILE="$T/pw"
  export TARGET_DATABASE=ses_manager_db SOURCE_HOST=10.0.0.1
  export TARGET_ALLOWLIST_FILE="$T/allowlist.txt" STAGING_ROOT="$T/staging"
  printf '%s\n' "$TARGET_UUID" > "$T/allowlist.txt"
  export FAKE_UUID="$TARGET_UUID" FAKE_CONTROL_MARKER="default	plan-1234" FAKE_TABLE_COUNT=0
  unset MYSQL_PWD
  chmod +x "$ROOT/ops/backup/providers/approval-verifier-local.sh" 2>/dev/null || true
}

# restic に full / checkpoint / binlog の snapshot を直接作成し、index と plan を生成する
build_backup_fixture() {
# 簡易 manifest（tmp へ書いてから mv する。直接 > すると find が空 manifest を見る）
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

# full payload
local full_payload="$T/full-payload"
mkdir -p "$full_payload/db" "$full_payload/uploads/published"
printf '%s\n' "-- MySQL dump (source-data=2)" \
  "-- CHANGE MASTER TO MASTER_LOG_FILE='binlog.000010', MASTER_LOG_POS=154;" \
  "CREATE TABLE t (id INT); INSERT INTO t VALUES (1);" > "$full_payload/db/database.sql"
printf 'marker-before-full\n' > "$full_payload/uploads/published/marker.txt"
make_manifest "$full_payload"

# checkpoint payload（uploads のみ）
local ckpt_payload="$T/ckpt-payload"
mkdir -p "$ckpt_payload/uploads/published"
printf 'marker-before-checkpoint\n' > "$ckpt_payload/uploads/published/marker.txt"
make_manifest "$ckpt_payload"

  export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE"
  /usr/local/bin/restic init > /dev/null 2>&1
  FULL_SNAP=$(/usr/local/bin/restic backup "$full_payload" --tag "kind=full" --json 2>/dev/null | jq -rs 'map(select(.message_type=="summary"))[0].snapshot_id')
  CKPT_SNAP=$(/usr/local/bin/restic backup "$ckpt_payload" --tag "kind=checkpoint" --json 2>/dev/null | jq -rs 'map(select(.message_type=="summary"))[0].snapshot_id')
  # binlog snapshots（3 本）
  for n in 10 11 12; do
    local bf="binlog.0000$n"
    printf 'binlog-event-%s\n' "$n" > "$T/$bf"
    local bs
    bs=$(/usr/local/bin/restic backup "$T/$bf" --tag "kind=binlog" --json 2>/dev/null | jq -rs 'map(select(.message_type=="summary"))[0].snapshot_id')
    local prev="[]"
    [[ -f "$BINLOG_INDEX" ]] && prev=$(cat "$BINLOG_INDEX")
    printf '%s' "$prev" | jq -c --arg f "$bf" --arg s "$bs" --arg sha "$(sha256sum "$T/$bf" | awk '{print $1}')" \
      '. + [{file: $f, snapshot_id: $s, sha256: $sha, size: 1}]' > "$BINLOG_INDEX"
  done
  # index
  jq -n --arg s "VALID" --arg t "2026-08-14T09:00:00Z" --arg lin "$LINEAGE" --arg su "$SOURCE_UUID" \
    --arg bf "binlog.000010" --arg bp "154" --arg snap "$FULL_SNAP" \
    '{kind:"full",status:$s,consistency_time_utc:$t,source_lineage:$lin,source_server_uuid:$su,
      binlog_start:{file:$bf,position:($bp|tonumber)},restic_snapshot_id:$snap}' > "$T/index/full.json"
  jq -n --arg s "VALID" --arg t "2026-08-14T09:15:00Z" --arg lin "$LINEAGE" --arg su "$SOURCE_UUID" \
    --arg bf "binlog.000012" --arg bp "400" --arg snap "$CKPT_SNAP" --arg us "uploads-ckpt" \
    '{kind:"checkpoint",status:$s,consistency_time_utc:$t,source_lineage:$lin,source_server_uuid:$su,
      binlog_end:{file:$bf,position:($bp|tonumber)},uploads_snapshot_id:$us,restic_snapshot_id:$snap}' > "$T/index/ckpt.json"
  # plan
  PLAN_OUT=$("$PLAN" --target 2026-08-14T09:15:00Z 2>&1)
  PLAN_RC=$?
  PLAN_ID=$(ls "$T/plans" | grep -E '^[a-f0-9]{16}\.json$' | head -1 | sed 's/\.json$//')
  # recovery control marker を実際の plan_id で provision した状態にする
  export FAKE_CONTROL_MARKER="default	$PLAN_ID"
}

make_claims() {
  local verifier="$ROOT/ops/backup/providers/approval-verifier-local.sh"
  openssl genrsa -out "$T/priv1.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv1.pem" -pubout -out "$T/pub1.pem" 2>/dev/null
  openssl genrsa -out "$T/priv2.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv2.pem" -pubout -out "$T/pub2.pem" 2>/dev/null
  local plan_path="$T/plans/$PLAN_ID.json"
  local plan_sha
  plan_sha=$(sha256sum "$plan_path" | awk '{print $1}')
  local future
  future=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)
  local i
  for a in alice bob; do
    jq -n --arg ps "$plan_sha" --arg tu "$TARGET_UUID" --arg a "$a" \
      --arg r "manager" --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$future" --arg ct "CHG-007" \
      '{plan_sha256:$ps,target_uuid:$tu,change_ticket:$ct,actor:$a,role:$r,
        issued_at_utc:$i,expires_at_utc:$e}' > "$T/claim-$a.json"
    if [[ "$a" == "alice" ]]; then
      "$verifier" sign "$T/claim-alice.json" "$T/priv1.pem"
    else
      "$verifier" sign "$T/claim-bob.json" "$T/priv2.pem"
    fi
  done
  mkdir -p "$T/pubkeys"
  cp "$T/pub1.pem" "$T/pubkeys/alice.pem"
  cp "$T/pub2.pem" "$T/pubkeys/bob.pem"
  export APPROVAL_PUBKEY_DIR="$T/pubkeys"
}

case_restore_normal_flow() {
  setup_restore
  build_backup_fixture
  assert_zero "$PLAN_RC" "plan 生成成功"
  make_claims
  local out=""
  out=$("$RESTORE" --plan "$PLAN_ID" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  local rc=$?
  assert_zero "$rc" "restore 成功"
  assert_contains "$out" '"state": "RESTORED"' "state RESTORED"
  assert_contains "$out" '"uploads_ready"' "uploads staging あり"
  # uploads staging に marker が展開されている
  local up
  up=$(echo "$out" | jq -r '.uploads_ready')
  assert_file "$up/published/marker.txt" "uploads staging に marker"
  assert_contains "$(cat "$up/published/marker.txt")" "marker-before-checkpoint" "marker 内容"
  # replay の mysql connection は 1 回（CREATE 1 + import 1 + replay 1 = --binary-mode 3 回）
  local binmode_count
  binmode_count=$(grep -c -- '--binary-mode' "$FAKE_ARGV_LOG" || true)
  assert_eq "3" "$binmode_count" "CREATE/import/replay の合計 3 connection（replay は 1 回のみ）"
  # mysqlbinlog に start/stop position
  assert_contains "$(cat "$FAKE_ARGV_LOG")" "--start-position=154" "start position"
  assert_contains "$(cat "$FAKE_ARGV_LOG")" "--stop-position=400" "stop position"
  # 3 本の binlog が 1 回の mysqlbinlog に渡る
  local mb_line
  mb_line=$(grep -m1 -- '--start-position' "$FAKE_ARGV_LOG")
  assert_contains "$mb_line" "binlog.000010" "binlog 1"
  assert_contains "$mb_line" "binlog.000011" "binlog 2"
  assert_contains "$mb_line" "binlog.000012" "binlog 3"
}

case_restore_guard_reject() {
  setup_restore
  build_backup_fixture
  make_claims
  # target を source と同じ UUID にする → guard で拒否
  export FAKE_UUID="$SOURCE_UUID"
  local out=""
  out=$("$RESTORE" --plan "$PLAN_ID" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  local rc=$?
  assert_nonzero "$rc" "guard 拒否で非 0"
  assert_contains "$out" "target-guard" "guard 理由"
}

case_restore_approval_missing() {
  setup_restore
  build_backup_fixture
  make_claims
  # claim を 1 つだけにする（同一 actor 検出）
  local out=""
  out=$("$RESTORE" --plan "$PLAN_ID" --approval "$T/claim-alice.json" --approval "$T/claim-alice.json" 2>&1)
  local rc=$?
  assert_nonzero "$rc" "同一 actor 2 件は拒否"
  assert_contains "$out" "approval" "承認理由"
}

case_restore_mid_binlog_failure() {
  setup_restore
  build_backup_fixture
  make_claims
  # mysqlbinlog（replay 側）を失敗させる: FAKE_BINLOG_VERIFY_RC は verify 用のため、
  # 別フラグ FAKE_REPLAY_RC を追加して使う
  export FAKE_REPLAY_RC=1
  local out=""
  out=$("$RESTORE" --plan "$PLAN_ID" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  local rc=$?
  assert_nonzero "$rc" "replay 失敗は非 0"
  assert_contains "$out" "replay に失敗しました" "replay 失敗を通知（FAILED_RESTORE 隔離）"
  # staging は read-only に隔離されている
  local stage
  stage=$(ls -d "$T/staging"/*-* 2>/dev/null | head -1)
  if [[ -n "$stage" ]]; then
    local mode
    mode=$(stat -c %a "$stage")
    assert_eq "500" "$mode" "FAILED staging は read-only"
  fi
}

run_case case_restore_normal_flow
run_case case_restore_guard_reject
run_case case_restore_approval_missing
run_case case_restore_mid_binlog_failure

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-007 restore flow"
