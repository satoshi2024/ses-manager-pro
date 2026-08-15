#!/usr/bin/env bash
# HFP-03-010 retention / dependency graph / key rotation test
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
RETENTION="$ROOT/ops/backup/retention.sh"
ROTATE="$ROOT/ops/backup/rotate-key.sh"
VERIFIER="$ROOT/ops/backup/providers/approval-verifier-local.sh"

FAKE_PW='S3cr3t-Value-XYZ-010'

# 合成 metadata（DAYS 日分の checkpoint + 10 日毎の full + binlog index）
setup_metadata() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp" INDEX_DIR="$T/index"
  mkdir -p "$INDEX_DIR" "$TMPDIR"
  export BINLOG_INDEX="$T/binlog-index.json"
  local days=${FIXTURE_DAYS:-40}
  local now
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  # binlog: 000001 .. 0000(days+5)。file 番号は新しい時刻ほど大きい（F(age)=days+5-age）
  local bins="[]"
  local n total
  total=$((days + 5))
  for ((n = 1; n <= total; n++)); do
    local file
    file=$(printf 'binlog.%06d' "$n")
    bins=$(printf '%s' "$bins" | jq -c --arg f "$file" --arg s "bl-$file" '. + [{file: $f, snapshot_id: $s}]')
  done
  printf '%s\n' "$bins" > "$BINLOG_INDEX"
  # full: age 1,6,11,16,21,26,31,36,41 日（binlog_start = F(age)）。days+1 までの full を作る
  local d
  for d in 1 6 11 16 21 26 31 36 41; do
    [[ $d -le $((days + 1)) ]] || break
    local ts file start
    ts=$(date -u -d "$now - $d days" +%Y-%m-%dT%H:%M:%SZ)
    start=$((total - d))
    file=$(printf 'binlog.%06d' "$start")
    local stamp
    stamp=$(date -u -d "$now - $d days" +%Y%m%dT%H%M%SZ)
    jq -n --arg t "$ts" --arg id "full-$d" --arg f "$file" --arg u "up-full-$d" \
      --arg a "${FULL_ARCHIVE:-none}" --argjson fo "${FULL_ONLY:-false}" \
      '{kind:"full", status:"VALID", consistency_time_utc: $t, restic_snapshot_id: $id,
        binlog_start: {file: $f, position: 0}, uploads_snapshot_id: $u,
        archive: $a, full_only: $fo}' > "$INDEX_DIR/full-$stamp.json"
  done
  # checkpoint: day 0..days（binlog_end = F(day) = days+5-day）
  for ((d = 0; d <= days; d++)); do
    local ts file
    ts=$(date -u -d "$now - $d days" +%Y-%m-%dT%H:%M:%SZ)
    local e=$((days + 5 - d))
    file=$(printf 'binlog.%06d' "$e")
    local stamp
    stamp=$(date -u -d "$now - $d days" +%Y%m%dT%H%M%SZ)
    jq -n --arg t "$ts" --arg id "cp-$d" --arg f "$file" --arg u "up-cp-$d" \
      '{kind:"checkpoint", status:"VALID", consistency_time_utc: $t, restic_snapshot_id: $id,
        binlog_end: {file: $f, position: 0}, uploads_snapshot_id: $u, manifest_sha256: "m"}' \
      > "$INDEX_DIR/checkpoint-$stamp.json"
  done
}

# script-local fake restic
setup_fake_restic() {
  mkdir -p "$T/fakebin"
  cat > "$T/fakebin/restic" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${FAKE_RESTIC_ARGV_LOG:-/dev/null}"
[[ "${1:-}" == "-r" ]] && shift 2 || true
case "${1:-}" in
  cat) printf '{"id": "repo-001"}\n'; exit 0 ;;
  snapshots) printf '%s\n' "${FAKE_REPO_SNAPSHOTS:-[]}"; exit 0 ;;
  forget) exit "${FAKE_RESTIC_FORGET_RC:-0}" ;;
  restore) exit "${FAKE_RESTIC_RESTORE_RC:-0}" ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$T/fakebin/restic"
  export RESTIC_BIN="$T/fakebin/restic"
  export RESTIC_REPOSITORY="$T/repo"
  export FAKE_RESTIC_ARGV_LOG="$T/restic-argv.log"
  : > "$FAKE_RESTIC_ARGV_LOG"
  export REPOSITORY_LOCK_DIR="$T/locks"
  mkdir -p "$REPOSITORY_LOCK_DIR"
}

# claims（report file SHA + repository id に bind）
make_claims() { # report_file
  local report=$1
  local sha
  sha=$(sha256sum "$report" | awk '{print $1}')
  openssl genrsa -out "$T/priv1.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv1.pem" -pubout -out "$T/pub1.pem" 2>/dev/null
  openssl genrsa -out "$T/priv2.pem" 2048 2>/dev/null
  openssl rsa -in "$T/priv2.pem" -pubout -out "$T/pub2.pem" 2>/dev/null
  mkdir -p "$T/pubkeys"
  cp "$T/pub1.pem" "$T/pubkeys/alice.pem"
  cp "$T/pub2.pem" "$T/pubkeys/bob.pem"
  export APPROVAL_PUBKEY_DIR="$T/pubkeys"
  local future
  future=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)
  local a
  for a in alice bob; do
    local key
    [[ "$a" == "alice" ]] && key="$T/priv1.pem" || key="$T/priv2.pem"
    jq -n --arg ps "$sha" --arg tu "repo-001" --arg a "$a" --arg r manager \
      --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$future" --arg ct "CHG-010" \
      '{plan_sha256:$ps,target_uuid:$tu,change_ticket:$ct,actor:$a,role:$r,
        issued_at_utc:$i,expires_at_utc:$e}' > "$T/claim-$a.json"
    "$VERIFIER" sign "$T/claim-$a.json" "$key"
  done
}

export RETENTION_ROLE=retention
export RETENTION_PITR_DAYS=7 RETENTION_DAILY_DAYS=3 RETENTION_WEEKLY_COUNT=2 RETENTION_MONTHLY_COUNT=1
export RETENTION_LOCK_TIMEOUT=5

run_dry() {
  OUT=$("$RETENTION" --dry-run 2>&1)
  RC=$?
}

case_retention_window_all_kept() {
  setup_metadata
  run_dry
  assert_zero "$RC" "dry-run 成功"
  # window 内（day 0-7）の checkpoint は全保持
  for d in 0 3 7; do
    assert_contains "$OUT" "\"cp-$d\"" "window 内 cp-$d を保持"
  done
  # 古い cp は保持されない（day 40 は代表にも入らない）
  assert_contains "$OUT" '"deletable_snapshots"' "deletable セクション"
  local del
  del=$(printf '%s' "$OUT" | jq -r '.deletable_snapshots | index("cp-40") != null')
  assert_eq "true" "$del" "cp-40 は削除候補"
}

case_retention_daily_weekly_rep() {
  setup_metadata
  run_dry
  assert_zero "$RC" "dry-run 成功"
  # daily 代表（day 8-10）
  for d in 8 9 10; do
    local k
    k=$(printf '%s' "$OUT" | jq -r ".kept_snapshots | index(\"cp-$d\") != null")
    assert_eq "true" "$k" "daily 代表 cp-$d を保持"
  done
  # weekly 代表: day 14-40 を 7 日バケットに分け、最新 2 バケットの代表を保持
  local k38
  k38=$(printf '%s' "$OUT" | jq -r '.kept_snapshots | index("cp-35") != null')
  assert_eq "true" "$k38" "weekly 代表 cp-35 を保持"
  local k24
  k24=$(printf '%s' "$OUT" | jq -r '.kept_snapshots | index("cp-24") != null')
  assert_eq "false" "$k24" "古いバケットの cp-24 は保持しない"
}

case_retention_old_chain_deletable() {
  setup_metadata
  run_dry
  # 旧 full（full-41）はどの保持 checkpoint からも参照されず、最新でもない → 削除候補
  local del0
  del0=$(printf '%s' "$OUT" | jq -r '.deletable_snapshots | index("full-41") != null')
  assert_eq "true" "$del0" "full-41 は削除候補"
  # 最新 full（full-1）は常に保持
  local keep40
  keep40=$(printf '%s' "$OUT" | jq -r '.kept_snapshots | index("full-1") != null')
  assert_eq "true" "$keep40" "最新 full は常に保持"
  # 参照済み binlog は保持、未参照は削除候補
  local bl1
  bl1=$(printf '%s' "$OUT" | jq -r '.deletable_binlog_snapshots | index("bl-binlog.000001") != null')
  assert_eq "true" "$bl1" "未参照 binlog は削除候補"
}

case_retention_full_only_archive() {
  setup_metadata
  FULL_ARCHIVE=weekly
  setup_metadata
  run_dry
  # weekly full-only は 2 つ保持（WEEKLY_COUNT=2）
  local cnt
  cnt=$(printf '%s' "$OUT" | jq -r '[.kept_snapshots[] | select(startswith("full-"))] | length')
  assert_eq "5" "$cnt" "保持 full は最新 + full-only 2 + 保持チェーンの base"
  local k6
  k6=$(printf '%s' "$OUT" | jq -r '.kept_snapshots | index("full-6") != null')
  assert_eq "true" "$k6" "weekly full-only を保持"
  unset FULL_ARCHIVE
}

case_retention_orphan_snapshot() {
  setup_metadata
  # 孤立 full（未来時刻スタンプ = どの checkpoint にも参照されず、最新対象外）
  local now
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  local ts stamp
  ts=$(date -u -d "$now + 1 hours" +%Y-%m-%dT%H:%M:%SZ)
  stamp=$(date -u -d "$now + 1 hours" +%Y%m%dT%H%M%SZ)
  jq -n --arg t "$ts" --arg id "full-orphan" --arg f "binlog.000046" --arg u "up-orphan" \
    '{kind:"full", status:"VALID", consistency_time_utc: $t, restic_snapshot_id: $id,
      binlog_start: {file: $f, position: 0}, uploads_snapshot_id: $u, archive: "none", full_only: false}' \
    > "$INDEX_DIR/full-$stamp-orphan.json"
  run_dry
  local k
  k=$(printf '%s' "$OUT" | jq -r '.deletable_snapshots | index("full-orphan") != null')
  assert_eq "true" "$k" "孤立 full は削除候補"
}

case_retention_missing_dependency() {
  setup_metadata
  # cp-3 の binlog_end を未存在 file に改変（チェーン不完全）
  local stamp
  stamp=$(date -u -d "$(date -u +%Y-%m-%dT%H:%M:%SZ) - 3 days" +%Y%m%dT%H%M%SZ)
  local cp
  cp=$(ls "$INDEX_DIR"/checkpoint-*.json | head -1)
  for f in "$INDEX_DIR"/checkpoint-*.json; do
    if jq -e '.restic_snapshot_id == "cp-3"' "$f" > /dev/null; then cp=$f; fi
  done
  jq '.binlog_end.file = "binlog.000001"' "$cp" > "$cp.tmp" && mv "$cp.tmp" "$cp"
  run_dry
  assert_zero "$RC" "dry-run は成功（不完全でも report 出力）"
  local av
  av=$(printf '%s' "$OUT" | jq -r '.pitr_available["cp-3"]')
  assert_eq "false" "$av" "cp-3 は PITR_AVAILABLE=false"
  # 不完全でも window 内 checkpoint 自身は削除しない（手動調査の余地）
  local delcp3
  delcp3=$(printf '%s' "$OUT" | jq -r '.deletable_snapshots | index("cp-3") == null')
  assert_eq "true" "$delcp3" "cp-3 自身は削除候補にしない"
}

case_retention_writer_denied() {
  setup_metadata
  export RETENTION_ROLE=backup
  run_dry
  assert_nonzero "$RC" "writer role は削除不可"
  assert_contains "$OUT" "retention/admin" "role 理由"
  unset RETENTION_ROLE
}

case_retention_role_absent() {
  setup_metadata
  unset RETENTION_ROLE
  run_dry
  assert_nonzero "$RC" "role 未設定は拒否"
  assert_contains "$OUT" "RETENTION_ROLE" "理由"
  export RETENTION_ROLE=retention
}

case_retention_dry_run_no_restic() {
  setup_metadata
  export RESTIC_BIN=/nonexistent/restic
  run_dry
  assert_zero "$RC" "dry-run は restic に触れない"
  export RESTIC_BIN=""
}

case_retention_apply_noop() {
  setup_metadata
  export FIXTURE_DAYS=5   # 全て window 内
  setup_metadata
  # 参照済み binlog（4..10）のみの index に差し替え → 削除候補ゼロにする
  local bins="[]" n
  for n in 4 5 6 7 8 9 10; do
    local file
    file=$(printf 'binlog.%06d' "$n")
    bins=$(printf '%s' "$bins" | jq -c --arg f "$file" --arg s "bl-$file" '. + [{file: $f, snapshot_id: $s}]')
  done
  printf '%s\n' "$bins" > "$BINLOG_INDEX"
  run_dry
  local report="$T/report.json"
  printf '%s\n' "$OUT" > "$report"
  make_claims "$report"
  setup_fake_restic
  OUT2=$("$RETENTION" --apply --report "$report" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  assert_zero "$?" "NO_OP apply 成功"
  assert_contains "$OUT2" '"state": "NO_OP"' "NO_OP を返す"
  if grep -q "forget" "$FAKE_RESTIC_ARGV_LOG"; then
    test_fail "restic forget を呼ばない" "argv log に forget がある"
  else
    test_assert "restic forget を呼ばない"
  fi
  unset FIXTURE_DAYS
}

case_retention_apply_executes() {
  setup_metadata
  run_dry
  local report="$T/report.json"
  printf '%s\n' "$OUT" > "$report"
  make_claims "$report"
  setup_fake_restic
  # repository には保持・削除候補が全て実在する想定（restic 形式: {id: ...} の配列）
  local snaps
  snaps=$(printf '%s' "$OUT" | jq -c '(.kept_snapshots + .deletable_snapshots + .deletable_binlog_snapshots) | [.[] | {id: .}]')
  export FAKE_REPO_SNAPSHOTS="$snaps"
  OUT2=$("$RETENTION" --apply --report "$report" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  assert_zero "$?" "apply 成功"
  assert_contains "$OUT2" '"state": "APPLIED"' "APPLIED を返す"
  if grep -q "forget" "$FAKE_RESTIC_ARGV_LOG"; then
    test_assert "restic forget を実行"
  else
    test_fail "restic forget を実行" "argv log に forget がない"
  fi
  if grep -q "full-41" "$FAKE_RESTIC_ARGV_LOG"; then
    test_assert "削除候補 full-41 を forget に渡す"
  else
    test_fail "削除候補 full-41 を forget に渡す" "argv log に full-41 がない（log=$(cat "$FAKE_RESTIC_ARGV_LOG")）"
  fi
  if grep -q " full-1 \| full-1$" "$FAKE_RESTIC_ARGV_LOG"; then
    test_fail "保持 full を forget に渡さない" "argv log に full-1 がある（log=$(cat "$FAKE_RESTIC_ARGV_LOG")）"
  else
    test_assert "保持 full を forget に渡さない"
  fi
}

case_retention_apply_report_mismatch() {
  setup_metadata
  run_dry
  local report="$T/report.json"
  printf '%s\n' "$OUT" > "$report"
  make_claims "$report"
  setup_fake_restic
  # report 生成後に metadata を変更（新しい checkpoint 追加）
  local now
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  local stamp
  stamp=$(date -u -d "$now" +%Y%m%dT%H%M%SZ)
  jq -n --arg t "$now" --arg id "cp-new" --arg f "binlog.000045" --arg u "up-new" \
    '{kind:"checkpoint", status:"VALID", consistency_time_utc: $t, restic_snapshot_id: $id,
      binlog_end: {file: $f, position: 0}, uploads_snapshot_id: $u, manifest_sha256: "m"}' \
    > "$INDEX_DIR/checkpoint-$stamp.json"
  OUT2=$("$RETENTION" --apply --report "$report" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  assert_nonzero "$?" "report 不一致は拒否"
  assert_contains "$OUT2" "一致しません" "理由"
  if grep -q "forget" "$FAKE_RESTIC_ARGV_LOG"; then
    test_fail "forget を実行しない" "argv log に forget がある"
  else
    test_assert "forget を実行しない"
  fi
}

case_retention_apply_approval_missing() {
  setup_metadata
  run_dry
  local report="$T/report.json"
  printf '%s\n' "$OUT" > "$report"
  make_claims "$report"
  setup_fake_restic
  OUT2=$("$RETENTION" --apply --report "$report" --approval "$T/claim-alice.json" --approval "$T/claim-alice.json" 2>&1)
  assert_nonzero "$?" "同一 actor は拒否"
}

case_retention_prune_race() {
  setup_metadata
  run_dry
  local report="$T/report.json"
  printf '%s\n' "$OUT" > "$report"
  make_claims "$report"
  setup_fake_restic
  # maintenance lock を先に取得（競合を作る）
  mkdir -p "$REPOSITORY_LOCK_DIR"
  exec 9>"$REPOSITORY_LOCK_DIR/repository.lock"
  flock 9
  OUT2=$("$RETENTION" --apply --report "$report" --approval "$T/claim-alice.json" --approval "$T/claim-bob.json" 2>&1)
  local rc=$?
  exec 9>&-
  assert_nonzero "$rc" "prune 競合は非 0"
  assert_contains "$OUT2" "prune 競合" "alert 文言"
  if grep -q "forget" "$FAKE_RESTIC_ARGV_LOG"; then
    test_fail "競合時は forget しない" "argv log に forget がある"
  else
    test_assert "競合時は forget しない"
  fi
}

# key rotation（script-local fake restic: 新キー時に env で fail 可能）
case_key_rotation() {
  setup_metadata
  mkdir -p "$T/kr-bin"
  cat > "$T/kr-bin/restic" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${FAKE_RESTIC_ARGV_LOG:-/dev/null}"
case "${1:-}" in
  restore)
    local pw=""
    [[ -f "${RESTIC_PASSWORD_FILE:-}" ]] && pw=$(cat "$RESTIC_PASSWORD_FILE")
    if [[ -n "${FAKE_NEW_KEY_FAIL:-}" && "$pw" == new-* ]]; then
      exit 1
    fi
    exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$T/kr-bin/restic"
  export RESTIC_BIN="$T/kr-bin/restic"
  printf 'old-key-value\n' > "$T/pw-file"
  printf 'new-key-value\n' > "$T/new-key"
  export RESTIC_PASSWORD_FILE="$T/pw-file"
  export FAKE_RESTIC_ARGV_LOG="$T/kr-argv.log"
  : > "$FAKE_RESTIC_ARGV_LOG"

  # 新キーが失敗する場合 → 切替えない
  export FAKE_NEW_KEY_FAIL=1
  OUT=$("$ROTATE" --new-key-file "$T/new-key" 2>&1)
  assert_nonzero "$?" "新キー restore 失敗は非 0"
  assert_eq "old-key-value" "$(cat "$T/pw-file")" "キーは切替わらない"
  assert_contains "$OUT" "切替えません" "理由"

  # 両方成功 → 切替える
  unset FAKE_NEW_KEY_FAIL
  OUT=$("$ROTATE" --new-key-file "$T/new-key" 2>&1)
  assert_zero "$?" "rotation 成功"
  assert_eq "new-key-value" "$(cat "$T/pw-file")" "キーが切替わる"
  assert_contains "$OUT" '"state": "ROTATED"' "ROTATED を返す"
}

run_case case_retention_window_all_kept
run_case case_retention_daily_weekly_rep
run_case case_retention_old_chain_deletable
run_case case_retention_full_only_archive
run_case case_retention_orphan_snapshot
run_case case_retention_missing_dependency
run_case case_retention_writer_denied
run_case case_retention_role_absent
run_case case_retention_dry_run_no_restic
run_case case_retention_apply_noop
run_case case_retention_apply_executes
run_case case_retention_apply_report_mismatch
run_case case_retention_apply_approval_missing
run_case case_retention_prune_race
run_case case_key_rotation

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-010 retention"
