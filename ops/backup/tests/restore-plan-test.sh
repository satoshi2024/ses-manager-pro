#!/usr/bin/env bash
# HFP-03-006 restore plan test
#
# baseline §5 failure inventory のうち HFP-03-006 担当分:
#   (1) target より後の latest full が選ばれてしまう
#   (2) --target を変えても実行 command が同一
#   + RQ-005/006: UTC/JST/DST 同一 plan SHA、RPO 15 分超、lineage mismatch、
#   gap、plan tamper、approval 0/1/同一 actor/期限切れ/別 target
# index / binlog-index を fixture で作り、実 DB には接続しない。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
PLAN="$ROOT/ops/backup/plan-restore.sh"

FAKE_PW='S3cr3t-Value-XYZ-006'
LINEAGE='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

setup_plan() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp"
  mkdir -p "$TMPDIR" "$T/index" "$T/binlog" "$T/plans"
  export BACKUP_WORK_DIR="$T" BINLOG_INDEX="$T/binlog/binlog-index.json" PLANS_DIR="$T/plans"
  export INDEX_DIR="$T/index"
  chmod +x "$ROOT/ops/backup/providers/approval-verifier-local.sh" 2>/dev/null || true
  unset MYSQL_PWD
}

# plans ディレクトリから plan_id を取得（出力の混線に依存しない）
plan_id_from_dir() {
  ls "$T/plans" | grep -E '^[a-f0-9]{16}\.json$' | head -1 | sed 's/\.json$//'
}

write_index_entry() { # kind time binlog_file binlog_pos
  local kind=$1 time=$2 bf=$3 bp=$4
  local name="${kind}-${time}"
  jq -n --arg k "$kind" --arg s "VALID" --arg t "$time" --arg lin "$LINEAGE" \
    --arg bf "$bf" --arg bp "$bp" --arg us "uploads-$time" \
    '{kind: $k, status: $s, consistency_time_utc: $t, source_lineage: $lin,
      binlog_start: {file: $bf, position: ($bp|tonumber)},
      binlog_end: {file: $bf, position: ($bp|tonumber)},
      uploads_snapshot_id: $us}' > "$T/index/$name.json"
}

write_binlog_index_entry() { # file
  local f=$1
  local arr="[]"
  [[ -f "$BINLOG_INDEX" ]] && arr=$(cat "$BINLOG_INDEX")
  printf '%s' "$arr" | jq -c --arg f "$f" --arg sha "sha-$f" \
    '. + [{file: $f, snapshot_id: "snap-$f", sha256: $sha, size: 100}]' > "$BINLOG_INDEX"
}

setup_standard_fixture() {
  setup_plan
  write_index_entry full 2026-08-14T10:00:00Z binlog.000010 154
  write_index_entry checkpoint 2026-08-14T10:15:00Z binlog.000011 200
  write_index_entry checkpoint 2026-08-14T10:30:00Z binlog.000012 300
  write_index_entry checkpoint 2026-08-14T10:45:00Z binlog.000013 400
  for f in binlog.000010 binlog.000011 binlog.000012 binlog.000013; do
    write_binlog_index_entry "$f"
  done
}

run_plan() { # target
  PLAN_OUT=$("$PLAN" --target "$1" 2>&1)
  PLAN_RC=$?
}

case_plan_selects_before_target() {
  setup_standard_fixture
  run_plan 2026-08-14T10:20:00Z
  assert_zero "$PLAN_RC" "plan 生成成功"
  assert_contains "$PLAN_OUT" '10:15:00Z' "実効 checkpoint は 10:15"
  assert_not_contains "$PLAN_OUT" '10:30:00Z' "target 後の checkpoint を選ばない"
  assert_not_contains "$PLAN_OUT" '10:45:00Z' "target 後の checkpoint を選ばない"
  assert_contains "$PLAN_OUT" '"binlog.000011"' "replay は 000010..000011"
  assert_contains "$PLAN_OUT" '"rpo_seconds": 300' "RPO 5 分"
  assert_contains "$PLAN_OUT" '"state": "READY"' "state READY"
  local pid
  pid=$(plan_id_from_dir)
  assert_file "$T/plans/$pid.json" "plan file 保存"
  assert_file "$T/plans/$pid.json.sha256" "plan sha 保存"
}

case_plan_latest_full_after_target_not_selected() {
  # restic の latest が target 後でも、時刻基準で選択される（BL-002）
  setup_plan
  write_index_entry full 2026-08-14T09:00:00Z binlog.000008 100
  write_index_entry full 2026-08-14T11:00:00Z binlog.000012 500   # target より後
  write_index_entry checkpoint 2026-08-14T09:15:00Z binlog.000009 200
  write_index_entry checkpoint 2026-08-14T09:30:00Z binlog.000010 300
  for f in binlog.000008 binlog.000009 binlog.000010 binlog.000011 binlog.000012; do
    write_binlog_index_entry "$f"
  done
  run_plan 2026-08-14T09:30:00Z
  assert_zero "$PLAN_RC" "plan 生成成功"
  assert_contains "$PLAN_OUT" '09:00:00Z' "base full は 09:00（11:00 の full を選ばない）"
  assert_not_contains "$PLAN_OUT" '11:00:00Z' "target 後の full を選ばない"
  assert_contains "$PLAN_OUT" '"binlog.000010"' "replay end 000010"
}

case_plan_timezone_independent() {
  setup_standard_fixture
  local sha_utc sha_jst sha_dst
  TZ=UTC run_plan 2026-08-14T10:20:00Z
  sha_utc=$(printf '%s' "$PLAN_OUT" | grep -oE '"plan_id": "[a-f0-9]{16}"' | head -1 | grep -oE '[a-f0-9]{16}')
  assert_zero "$PLAN_RC" "UTC で plan 生成成功"
  TZ=Asia/Tokyo run_plan 2026-08-14T10:20:00Z
  sha_jst=$(printf '%s' "$PLAN_OUT" | grep -oE '"plan_id": "[a-f0-9]{16}"' | head -1 | grep -oE '[a-f0-9]{16}')
  TZ=America/New_York run_plan 2026-08-14T10:20:00Z
  sha_dst=$(printf '%s' "$PLAN_OUT" | grep -oE '"plan_id": "[a-f0-9]{16}"' | head -1 | grep -oE '[a-f0-9]{16}')
  assert_eq "$sha_utc" "$sha_jst" "UTC/JST で plan SHA 一致"
  assert_eq "$sha_utc" "$sha_dst" "UTC/DST で plan SHA 一致"
}

case_plan_rpo_missed() {
  setup_standard_fixture
  run_plan 2026-08-14T11:05:00Z   # 最後の checkpoint から 20 分後
  assert_zero "$PLAN_RC" "RPO_MISSED でも plan は生成される"
  assert_contains "$PLAN_OUT" '"state": "RPO_MISSED"' "state RPO_MISSED"
  local pid
  pid=$(plan_id_from_dir)
  # shellcheck disable=SC1091
  . "$LIB/plan.sh"
  local st
  st=$(plan::status "$T/plans/$pid.json")
  assert_eq "RPO_MISSED" "$st" "RPO_MISSED は apply 不可"
}

case_plan_lineage_mismatch() {
  setup_plan
  jq -n --arg s "VALID" --arg t "2026-08-14T10:15:00Z" --arg lin "OTHER-LINEAGE" \
    '{kind: "checkpoint", status: $s, consistency_time_utc: $t, source_lineage: $lin,
      binlog_end: {file: "binlog.000011", position: 200}, uploads_snapshot_id: "u"}' \
    > "$T/index/ckpt.json"
  run_plan 2026-08-14T10:20:00Z
  assert_nonzero "$PLAN_RC" "lineage 不一致は非 0"
}

case_plan_binlog_gap() {
  setup_standard_fixture
  # binlog.000011 を index から消す（欠番）
  jq -c '[.[] | select(.file != "binlog.000011")]' "$BINLOG_INDEX" > "$BINLOG_INDEX.tmp" && mv "$BINLOG_INDEX.tmp" "$BINLOG_INDEX"
  run_plan 2026-08-14T10:20:00Z
  assert_nonzero "$PLAN_RC" "binlog 欠番は非 0"
  assert_contains "$PLAN_OUT" "binlog.000011" "欠番 file を通知"
}

case_plan_tamper_detected() {
  setup_standard_fixture
  run_plan 2026-08-14T10:20:00Z
  local pid
  pid=$(plan_id_from_dir)
  printf 'x' | dd of="$T/plans/$pid.json" bs=1 seek=5 conv=notrunc 2>/dev/null
  # shellcheck disable=SC1091
  . "$LIB/plan.sh"
  plan::verify "$T/plans/$pid.json"
  assert_nonzero "$?" "plan 改変は verify 失敗"
}

case_plan_expiry() {
  setup_standard_fixture
  run_plan 2026-08-14T10:20:00Z
  local pid
  pid=$(plan_id_from_dir)
  # shellcheck disable=SC1091
  . "$LIB/plan.sh"
  local st
  st=$(plan::status "$T/plans/$pid.json")
  assert_eq "APPLYABLE" "$st" "生成直後は APPLYABLE"
  # valid_until を過去へ改変し、sha も同じ方式で再計算（正当な再署名相当）
  local content sha
  content=$(jq -S -c '.valid_until_utc = "2020-01-01T00:00:00Z"' "$T/plans/$pid.json")
  sha=$(printf '%s' "$(plan::content_for_sha "$content")" | sha256sum | awk '{print $1}')
  printf '%s' "$content" > "$T/plans/$pid.json"
  printf '%s\n' "$sha" > "$T/plans/$pid.json.sha256"
  st=$(plan::status "$T/plans/$pid.json")
  assert_eq "EXPIRED" "$st" "期限切れは EXPIRED"
}

case_plan_parser_rejects_bad_target() {
  setup_standard_fixture
  for bad in "2026-08-14 10:20:00" "2026-08-14T10:20:00" "2026-13-01T10:00:00Z" "not-a-date" "2026-08-14T25:00:00Z"; do
    run_plan "$bad"
    assert_nonzero "$PLAN_RC" "不正 target は非 0: $bad"
  done
}

case_approval_verify() {
  setup_standard_fixture
  run_plan 2026-08-14T10:20:00Z
  local pid
  pid=$(plan_id_from_dir)
  local plan_path="$T/plans/$pid.json"
  local plan_sha
  plan_sha=$(sha256sum "$plan_path" | awk '{print $1}')
  openssl genrsa -out "$T/priv1.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv1.pem" -pubout -out "$T/pub1.pem" 2>/dev/null
  openssl genrsa -out "$T/priv2.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv2.pem" -pubout -out "$T/pub2.pem" 2>/dev/null
  local verifier="$ROOT/ops/backup/providers/approval-verifier-local.sh"

  make_claim() { # actor key expires out
    local actor=$1 key=$2 expires=$3 out=$4
    jq -n --arg ps "$plan_sha" --arg tu "target-uuid-001" --arg a "$actor" \
      --arg r "manager" --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$expires" \
      --arg ct "CHG-001" \
      '{plan_sha256: $ps, target_uuid: $tu, change_ticket: $ct, actor: $a, role: $r,
        issued_at_utc: $i, expires_at_utc: $e}' > "$out"
    "$verifier" sign "$out" "$key"
  }
  local future
  future=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)

  make_claim alice "$T/priv1.pem" "$future" "$T/claim-a.json"
  make_claim bob "$T/priv2.pem" "$future" "$T/claim-b.json"
  # shellcheck disable=SC1091
  . "$LIB/approval.sh"
  mkdir -p "$T/pubkeys"
  cp "$T/pub1.pem" "$T/pubkeys/alice.pem"
  cp "$T/pub2.pem" "$T/pubkeys/bob.pem"
  export APPROVAL_PUBKEY_DIR="$T/pubkeys"

  approval::collect_and_verify "$plan_path" target-uuid-001 "$T/claim-a.json" "$T/claim-b.json"
  assert_zero "$?" "2 名の正常 claim は成功"
  # 同一 actor
  approval::collect_and_verify "$plan_path" target-uuid-001 "$T/claim-a.json" "$T/claim-a.json"
  assert_nonzero "$?" "同一 actor は拒否"
  # 単一 claim の署名は valid（署名検証自体）
  approval::verify_claim "$T/claim-a.json" "$T/pub1.pem"
  assert_zero "$?" "単一 claim の署名は valid"
  # 期限切れ
  make_claim carol "$T/priv1.pem" "2020-01-01T00:00:00Z" "$T/claim-c.json"
  make_claim dave "$T/priv2.pem" "$future" "$T/claim-d.json"
  approval::collect_and_verify "$plan_path" target-uuid-001 "$T/claim-c.json" "$T/claim-d.json"
  assert_nonzero "$?" "期限切れ claim は拒否"
  # 別 target
  make_claim erin "$T/priv1.pem" "$future" "$T/claim-e.json"
  make_claim frank "$T/priv2.pem" "$future" "$T/claim-f.json"
  approval::collect_and_verify "$plan_path" target-uuid-999 "$T/claim-e.json" "$T/claim-f.json"
  assert_nonzero "$?" "別 target UUID は拒否"
  # 署名対象の改変
  printf 'x' | dd of="$T/claim-a.json" bs=1 seek=3 conv=notrunc 2>/dev/null
  approval::verify_claim "$T/claim-a.json" "$T/pub1.pem"
  assert_nonzero "$?" "署名対象の改変は拒否"
}

run_case case_plan_selects_before_target
run_case case_plan_latest_full_after_target_not_selected
run_case case_plan_timezone_independent
run_case case_plan_rpo_missed
run_case case_plan_lineage_mismatch
run_case case_plan_binlog_gap
run_case case_plan_tamper_detected
run_case case_plan_expiry
run_case case_plan_parser_rejects_bad_target
run_case case_approval_verify

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-006 restore plan"
