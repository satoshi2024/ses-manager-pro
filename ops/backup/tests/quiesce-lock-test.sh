#!/usr/bin/env bash
# HFP-03-002 lock / quiesce / uploads snapshot provider test
#
# baseline §5 failure inventory のうち HFP-03-002 担当分 + RQ-002/006/008/009:
# lock race、timeout、provider partial failure、replica 未静止、scheduler active、
# DDL lock conflict、解除 failure、special file 拒否、uploads snapshot の同一 filesystem 判定（st_dev）と失敗時の staging の後始末（RB-W）。
# fake mysql / fake replica・scheduler fixture を使い、実 DB には接続しない。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
PROVIDERS="$ROOT/ops/backup/providers"
FIXTURES="$HERE/fixtures/bin"

FAKE_PW='S3cr3t-Value-XYZ-002'

setup_common() {
  T=$(mktemp -d)
  export TMPDIR="$T/tmp" REPOSITORY_LOCK_DIR="$T/locks"
  mkdir -p "$TMPDIR" "$REPOSITORY_LOCK_DIR" "$T/capath"
  printf '%s\n' "$FAKE_PW" > "$T/pw"
  printf '%s\n' '-----BEGIN CERTIFICATE-----' 'FAKECA' '-----END CERTIFICATE-----' > "$T/capath/fd95d540.0"
  export MYSQL_PASSWORD_FILE="$T/pw" MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER='bkp-user-7x9'
  export MYSQL_SSL_CAPATH="$T/capath"
  export MYSQL_CLIENT_BIN="$FIXTURES/mysql" MYSQLBINLOG_BIN="$FIXTURES/mysqlbinlog" MYSQLDUMP_BIN="$FIXTURES/mysqldump"
  export FAKE_ARGV_LOG="$T/argv.log"
  unset MYSQL_PWD FAKE_GET_LOCK FAKE_RELEASE_LOCK FAKE_GET_LOCK_HOLD
}

setup_quiesce() {
  setup_common
  export REPLICA_HEARTBEAT_DIR="$T/replicas" SCHEDULER_ACK_DIR="$T/scheduler"
  mkdir -p "$REPLICA_HEARTBEAT_DIR" "$SCHEDULER_ACK_DIR"
  # 既定: 2 replica（fresh heartbeat）
  touch "$REPLICA_HEARTBEAT_DIR/app1.heartbeat" "$REPLICA_HEARTBEAT_DIR/app2.heartbeat"
  # scheduler fixture: stop-request を見たら ack する
  (
    while :; do
      [[ -f "$SCHEDULER_ACK_DIR/scheduler.stop-request" ]] && touch "$SCHEDULER_ACK_DIR/scheduler.stopped" && break
      sleep 0.1
    done
  ) &
  SCHEDULER_PID=$!
  # replica fixture: quiesce-requested を見たら ack する
  (
    while :; do
      for req in "$REPLICA_HEARTBEAT_DIR"/*.quiesce-requested; do
        [[ -e "$req" ]] || continue
        touch "${req%.quiesce-requested}.quiesced"
        rm -f "$req"
      done
      sleep 0.1
    done
  ) &
  REPLICA_ACK_PID=$!
  export QUIESCE_DEADLINE_SECONDS=20 QUIESCE_STALE_SECONDS=60 FAKE_GET_LOCK_HOLD=1
}

teardown_quiesce() {
  kill "${SCHEDULER_PID:-}" "${REPLICA_ACK_PID:-}" 2>/dev/null || true
}

case_lock_shared_and_exclusive() {
  setup_common
  # shellcheck disable=SC1091
  . "$LIB/repository-lock.sh"
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  repository_lock::acquire shared 5 "test-a"
  assert_zero "$?" "shared lock 取得"
  assert_file "$REPOSITORY_LOCK_DIR/owner.json" "owner metadata あり"
  assert_contains "$(cat "$REPOSITORY_LOCK_DIR/owner.json")" '"mode": "shared"' "owner mode"
  assert_contains "$(cat "$REPOSITORY_LOCK_DIR/owner.json")" '"owner": "test-a"' "owner 名"
  # 別 process から exclusive は timeout で失敗する
  capture_exit code bash -c "cd '$ROOT' && . '$LIB/common.sh' && . '$LIB/repository-lock.sh' && REPOSITORY_LOCK_DIR='$REPOSITORY_LOCK_DIR' repository_lock::acquire exclusive 2 'other'"
  assert_nonzero "$code" "排他要求は 2s timeout で失敗"
  # shared は並行取得できる
  capture_exit code2 bash -c "cd '$ROOT' && . '$LIB/common.sh' && . '$LIB/repository-lock.sh' && REPOSITORY_LOCK_DIR='$REPOSITORY_LOCK_DIR' repository_lock::acquire shared 2 'other-shared'"
  assert_zero "$code2" "shared は並行取得できる"
  repository_lock::release
}

case_lock_bad_mode() {
  setup_common
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/repository-lock.sh"
  capture_exit code repository_lock::acquire bogus 5 "t"
  assert_nonzero "$code" "不正 mode は非 0"
  capture_exit code2 repository_lock::acquire shared abc "t"
  assert_nonzero "$code2" "不正 timeout は非 0"
}

case_quiesce_all_fresh() {
  setup_quiesce
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  quiesce::acquire
  assert_zero "$?" "全 replica fresh で静止取得成功"
  local st
  st=$(quiesce::status_json)
  assert_contains "$st" '"provider": "quiesce-local"' "provider 名"
  assert_contains "$st" '"started_at_utc"' "開始時刻あり"
  assert_contains "$st" '"app1"' "replica app1 記載"
  assert_contains "$st" '"app2"' "replica app2 記載"
  assert_contains "$st" '"ddl_lock": "ses_backup_ddl_freeze"' "DDL lock 名"
  quiesce::release
  assert_zero "$?" "静止解除成功"
  st=$(quiesce::status_json)
  assert_contains "$st" '"released_at_utc": "20' "解除時刻が記載される"
  teardown_quiesce
}

case_quiesce_one_replica_stale() {
  setup_quiesce
  touch -d '5 minutes ago' "$REPLICA_HEARTBEAT_DIR/app2.heartbeat"
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "1 replica 未静止なら非 0"
  assert_contains "$out" "app2" "未静止 replica 名を通知"
  teardown_quiesce
}

case_quiesce_scheduler_no_ack() {
  setup_quiesce
  kill "$SCHEDULER_PID" 2>/dev/null || true
  export QUIESCE_DEADLINE_SECONDS=3
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "scheduler ack なしは deadline 超過で非 0"
  assert_contains "$out" "scheduler" "scheduler 関連の通知"
  teardown_quiesce
}

case_quiesce_scheduler_dir_missing() {
  setup_quiesce
  unset SCHEDULER_ACK_DIR
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "SCHEDULER_ACK_DIR 未設定は非 0（fail-closed）"
  assert_contains "$out" "SCHEDULER_ACK_DIR" "理由を通知"
  teardown_quiesce
}

case_quiesce_ddl_lock_conflict() {
  setup_quiesce
  export FAKE_GET_LOCK=0
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  local out=""
  out=$(quiesce::acquire 2>&1)
  local code=$?
  assert_nonzero "$code" "DDL lock 取得失敗は非 0"
  assert_contains "$out" "GET_LOCK" "GET_LOCK 失敗を通知"
  teardown_quiesce
}

case_quiesce_release_after_ddl_conflict() {
  # acquire 失敗後に release しても安全（DDL lock 未取得のままエラーにならない）
  setup_quiesce
  export FAKE_GET_LOCK=0
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  quiesce::acquire > /dev/null 2>&1
  capture_exit code quiesce::release
  assert_zero "$code" "acquire 失敗後の release は exit 0"
  teardown_quiesce
}

# RB-W 追加修正 C: uploads の根（$T/uploads）の下に版 v0 と相対・1 段の symlink current -> v0 を作る（production の配置）。
# 版の中身は UP_SRC（$T/uploads/v0）に置く。provider は current を解決して v0 を src にする。
setup_uploads_tree() {
  setup_common
  mkdir -p "$T/uploads/v0" "$T/staging"
  ln -s v0 "$T/uploads/current"
  UP_SRC="$T/uploads/v0"
}

# provider を起動する（$T/bin を PATH の先頭に置く。stub が無ければ影響しない）
run_uploads_snapshot() { # [staging_parent] [pointer]
  UP_OUT=$(PATH="$T/bin:$PATH" "$PROVIDERS/uploads-local.sh" snapshot "$T/uploads" "${2:-current}" "${1:-$T/staging}" \
    "$T/snap.json" 2>&1)
  UP_RC=$?
}

# 拒否したときは staging に何も作らず snapshot JSON も書かない
assert_uploads_rejected() { # <期待する文言> <label>
  assert_nonzero "$UP_RC" "拒否する: $2"
  assert_contains "$UP_OUT" "$1" "理由を通知: $2"
  assert_eq "" "$(find "$T/staging" -mindepth 1 2>/dev/null)" "staging に何も作らない: $2"
  assert_no_file "$T/snap.json" "snapshot JSON を書かない: $2"
}

case_uploads_snapshot_normal() {
  setup_uploads_tree
  mkdir -p "$UP_SRC/sub" "$T/uploads/v1"
  printf 'hello world\n' > "$UP_SRC/a.txt"
  printf '日本語\n' > "$UP_SRC/sub/b.txt"
  # app は取込センターの台帳などを 0600 で作る。backup は app と同じ uid で読む（2026-11-18 利用者決定）
  printf 'ledger\n' > "$UP_SRC/sub/ledger.properties"
  chmod 600 "$UP_SRC/sub/ledger.properties"
  # current が指さない版（v1）は backup しない
  printf 'other\n' > "$T/uploads/v1/other.txt"
  run_uploads_snapshot
  assert_zero "$UP_RC" "snapshot 成功"
  assert_file "$T/snap.json" "snapshot JSON あり"
  local sj
  sj=$(cat "$T/snap.json")
  assert_contains "$sj" '"snapshot_id"' "snapshot ID あり"
  assert_contains "$sj" '"a.txt"' "inventory に a.txt"
  assert_contains "$sj" '"sub/b.txt"' "inventory に sub/b.txt"
  assert_contains "$sj" '"sub/ledger.properties"' "inventory に 0600 の file"
  assert_contains "$sj" '"file_count": 3' "file_count 3"
  assert_not_contains "$sj" 'other.txt' "current が指さない版は含めない"
  # RB-W 追加修正 C: pointer の名前と版を記録する
  assert_contains "$sj" '"pointer": "current"' "pointer の名前を記録"
  assert_contains "$sj" '"pointer_target": "v0"' "pointer が指す版を記録"
  assert_contains "$sj" "\"src_dir\": \"$UP_SRC\"" "src は版の directory"
  # source 不変
  assert_eq "$(find "$UP_SRC" -type f | wc -l | tr -d ' ')" "3" "source file 数不変"
  assert_eq "v0" "$(readlink "$T/uploads/current")" "pointer は変えない"
  # staging の内容が source と一致
  local s1 s2
  s1=$(sha256sum "$UP_SRC/a.txt" | awk '{print $1}')
  s2=$(find "$T/staging" -name a.txt -exec sha256sum {} \; | awk '{print $1}')
  assert_eq "$s1" "$s2" "staging の a.txt が source と一致"
  assert_eq "ledger" "$(cat "$(find "$T/staging" -name ledger.properties)" 2>/dev/null)" "0600 の file も複製する"
}

# RB-W X-1: file が 1 つも無い版（初回 deploy 直後の空の uploads）は inventory [] で成功する
# （以前は空行から不正な JSON を作り、jq が失敗して初回の backup が必ず失敗していた）
case_uploads_snapshot_empty_version() {
  setup_uploads_tree
  run_uploads_snapshot
  assert_zero "$UP_RC" "空の版の snapshot は成功する"
  local sj
  sj=$(cat "$T/snap.json" 2>/dev/null)
  assert_contains "$sj" '"file_count": 0' "file_count 0"
  assert_eq "0" "$(jq '.inventory | length' <<< "$sj" 2>/dev/null)" "inventory は空の配列"
  assert_eq "array" "$(jq -r '.inventory | type' <<< "$sj" 2>/dev/null)" "inventory は配列（[]）"
  assert_contains "$sj" '"pointer_target": "v0"' "空でも版を記録"
}

# RB-W X-1 / 追加修正 C: pointer を安全に解決できなければ、staging を作る前に拒否する（代表的な形。規則の網羅は
# uploads-pointer-test.sh）。find は起点の symlink を辿らないため、symlink を src にすると黙って空の snapshot になる。
case_uploads_snapshot_rejects_unsafe_pointer() {
  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/a.txt"
  rm "$T/uploads/current"
  ln -s "$UP_SRC" "$T/uploads/current"
  run_uploads_snapshot
  assert_uploads_rejected "1 段の名前ではありません" "絶対 path の pointer"

  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/a.txt"
  ln -s v0 "$T/uploads/v1"
  rm "$T/uploads/current"
  ln -s v1 "$T/uploads/current"
  run_uploads_snapshot
  assert_uploads_rejected "実 directory ではありません" "symlink を指す pointer（起点が symlink）"

  setup_uploads_tree
  rm "$T/uploads/current"
  ln -s v9 "$T/uploads/current"
  run_uploads_snapshot
  assert_uploads_rejected "実 directory ではありません" "指す先の無い pointer"

  setup_uploads_tree
  rm "$T/uploads/current"
  run_uploads_snapshot
  assert_uploads_rejected "current がありません" "pointer が無い"

  setup_uploads_tree
  rm "$T/uploads/current"
  mkdir "$T/uploads/current"
  printf 'x\n' > "$T/uploads/current/a.txt"
  run_uploads_snapshot
  assert_uploads_rejected "symlink ではありません" "pointer が実 directory に置き換わった"

  setup_uploads_tree
  run_uploads_snapshot "$T/staging" "../current"
  assert_uploads_rejected "1 段の名前ではありません" "pointer の名前に / と .."
}

case_uploads_snapshot_symlink() {
  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/a.txt"
  ln -s a.txt "$UP_SRC/link.txt"
  run_uploads_snapshot
  assert_nonzero "$UP_RC" "symlink は非 0"
  assert_contains "$UP_OUT" "拒否" "symlink 拒否を通知"
}

case_uploads_snapshot_fifo() {
  setup_uploads_tree
  mkfifo "$UP_SRC/pipe.fifo"
  run_uploads_snapshot
  assert_nonzero "$UP_RC" "FIFO は非 0"
  assert_contains "$UP_OUT" "拒否" "FIFO 拒否を通知"
}

case_uploads_snapshot_hardlink() {
  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/a.txt"
  ln "$UP_SRC/a.txt" "$UP_SRC/b.txt"
  run_uploads_snapshot
  assert_nonzero "$UP_RC" "hardlink は非 0"
  assert_contains "$UP_OUT" "拒否" "hardlink 拒否を通知"
}

case_uploads_snapshot_encoded_name() {
  # ..%2f のようなエンコード名は実 FS 上は単なる file 名であり、
  # literal '..' 成分の拒否対象ではない（防御は復元側 safe-extract でも行う）
  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/normal.txt"
  printf 'y\n' > "$UP_SRC/..%2fetc"
  run_uploads_snapshot
  assert_zero "$UP_RC" "エンコード名は file として正常処理"
  assert_contains "$(cat "$T/snap.json" 2>/dev/null)" '..%2fetc' "エンコード名が inventory に残る"
}

# RB-W: uploads-local.sh の同一 filesystem 判定用の stat の stub（$T/bin/stat。PATH の先頭に置いて uploads-local.sh を起動する）。
# - stat [-L] -c %d <path>（st_dev）: path が FAKE_STAT_SRC / FAKE_STAT_STAGING なら FAKE_STAT_SRC_DEV / FAKE_STAT_STAGING_DEV を
#   返す（ERR なら失敗）。
# - stat -f -c %d（GNU の --file-system の書式では空き inode 数）: FAKE_STAT_FREE_MODE=counter なら呼ぶたびに違う値、
#   same なら常に 0（0 を返す network fs の再現）。修正前の判定はこの値を比べていた。
# - それ以外（-c %h・-c %s 等）は実際の stat に任せる。
setup_fake_stat() { # <src_dev> <staging_dev> <free_mode>
  mkdir -p "$T/bin"
  export REAL_STAT_BIN
  REAL_STAT_BIN=$(command -v stat)
  export FAKE_STAT_SRC="$UP_SRC" FAKE_STAT_STAGING="$T/staging"
  export FAKE_STAT_SRC_DEV=$1 FAKE_STAT_STAGING_DEV=$2 FAKE_STAT_FREE_MODE=$3 FAKE_STAT_COUNTER="$T/stat-free-counter"
  cat > "$T/bin/stat" <<'EOF'
#!/usr/bin/env bash
fsmode=0 fmt="" path=""
args=("$@")
while (( $# > 0 )); do
  case "$1" in
    -f|--file-system) fsmode=1 ;;
    -L|--dereference) : ;;
    -c) fmt=$2; shift ;;
    *) path=$1 ;;
  esac
  shift
done
if [[ "$fmt" == %d ]]; then
  if (( fsmode )); then
    if [[ "$FAKE_STAT_FREE_MODE" == counter ]]; then
      n=$(cat "$FAKE_STAT_COUNTER" 2>/dev/null || echo 1000)
      printf '%s\n' "$((n + 1))" > "$FAKE_STAT_COUNTER"
      printf '%s\n' "$n"
    else
      printf '0\n'
    fi
    exit 0
  fi
  dev=""
  [[ "$path" == "$FAKE_STAT_SRC" ]] && dev=$FAKE_STAT_SRC_DEV
  [[ "$path" == "$FAKE_STAT_STAGING" ]] && dev=$FAKE_STAT_STAGING_DEV
  if [[ -n "$dev" ]]; then
    [[ "$dev" == ERR ]] && { echo "stat: cannot stat '$path'" >&2; exit 1; }
    printf '%s\n' "$dev"
    exit 0
  fi
fi
exec "$REAL_STAT_BIN" "${args[@]}"
EOF
  chmod +x "$T/bin/stat"
}

run_uploads_snapshot_with_fake_stat() {
  run_uploads_snapshot
}

# 同じ filesystem（st_dev が同じ）なら、空き inode 数が呼び出しの間に変わっても受け付ける（修正前は stat -f -c %d の
# 空き inode 数を比べており、この場合に拒否していた）
case_uploads_snapshot_same_filesystem_by_device() {
  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/a.txt"
  setup_fake_stat 2049 2049 counter
  run_uploads_snapshot_with_fake_stat
  assert_not_contains "$UP_OUT" "同一 filesystem ではありません" "st_dev が同じなら同一 filesystem と判定する"
  assert_not_contains "$UP_OUT" "filesystem（st_dev）を取得できません" "st_dev を取得できる"
  assert_zero "$UP_RC" "同一 filesystem の snapshot は成功する"
  assert_file "$T/snap.json" "snapshot JSON あり"
}

# 別の filesystem（st_dev が違う）なら、空き inode 数がたまたま同じ（0 を返す network fs 等）でも拒否する（修正前は通していた）。
# 拒否は staging を作る前に行う。
case_uploads_snapshot_different_filesystem_rejected() {
  setup_uploads_tree
  printf 'x\n' > "$UP_SRC/a.txt"
  setup_fake_stat 2049 53 same
  run_uploads_snapshot_with_fake_stat
  assert_nonzero "$UP_RC" "st_dev が違えば拒否する"
  assert_contains "$UP_OUT" "staging が src と同一 filesystem ではありません" "別 filesystem を通知"
  assert_contains "$UP_OUT" "src=2049 staging=53" "比べた st_dev を通知"
  assert_eq "" "$(find "$T/staging" -mindepth 1 2>/dev/null)" "拒否したときは staging に何も作らない"
  assert_no_file "$T/snap.json" "拒否したときは snapshot JSON を書かない"
  # 静的: コメント以外で stat -f（--file-system の書式。%d は空き inode 数）を使わない
  assert_eq "" "$(grep -vE '^[[:space:]]*#' "$PROVIDERS/uploads-local.sh" | grep -E 'stat[[:space:]]+(-[A-Za-z]*f|--file-system)' || true)" \
    "空き inode 数（stat -f -c %d）で filesystem を判定しない"
}

# st_dev を取得できない・数値でない場合は拒否する（fail-closed）
case_uploads_snapshot_filesystem_unknown_rejected() {
  local dev
  for dev in ERR abc; do
    setup_uploads_tree
    printf 'x\n' > "$UP_SRC/a.txt"
    setup_fake_stat "$dev" 2049 same
    run_uploads_snapshot_with_fake_stat
    assert_nonzero "$UP_RC" "st_dev を取得できなければ拒否する: $dev"
    assert_contains "$UP_OUT" "filesystem（st_dev）を取得できません" "取得できないことを通知: $dev"
    assert_eq "" "$(find "$T/staging" -mindepth 1 2>/dev/null)" "staging に何も作らない: $dev"
  done
}

# RB-W: staging を作った後に失敗したら、EXIT trap が staging を消す（修正前は trap の handler が関数の local 変数を参照し、
# set -u で unbound variable になって staging が残っていた）。空白と引用符を含む staging の親 path でも消す。
# 失敗は jq の stub（$T/bin/jq。inventory の JSON 化で失敗する）で起こす（本物の jq が無い環境でも同じ経路を通る）。
case_uploads_snapshot_failure_removes_staging() {
  setup_uploads_tree
  local parent="$T/stg dir's"
  mkdir -p "$UP_SRC/sub" "$parent" "$T/bin"
  printf 'x\n' > "$UP_SRC/a.txt"
  printf 'y\n' > "$UP_SRC/sub/b.txt"
  printf '%s\n' '#!/usr/bin/env bash' 'echo "jq: 試験のための失敗" >&2' 'exit 5' > "$T/bin/jq"
  chmod +x "$T/bin/jq"
  run_uploads_snapshot "$parent"
  assert_nonzero "$UP_RC" "staging を作った後の失敗は非 0"
  assert_contains "$UP_OUT" "jq: 試験のための失敗" "staging を作った後の段階で失敗した（前提）"
  assert_not_contains "$UP_OUT" "unbound variable" "EXIT trap が unbound variable にならない"
  assert_eq "" "$(find "$parent" -mindepth 1 2>/dev/null)" "失敗したら staging（uploads-snap-*）を消す"
  assert_no_file "$T/snap.json" "失敗したら snapshot JSON を書かない"
  assert_eq "2" "$(find "$UP_SRC" -type f | wc -l | tr -d ' ')" "source は変えない"
}

case_quiesce_no_secret_leak() {
  setup_quiesce
  # shellcheck disable=SC1091
  . "$LIB/common.sh"
  # shellcheck disable=SC1091
  . "$LIB/quiesce.sh"
  quiesce::acquire > /dev/null 2>&1
  quiesce::release > /dev/null 2>&1
  teardown_quiesce
  if [[ -f "$FAKE_ARGV_LOG" ]]; then
    assert_not_contains "$(cat "$FAKE_ARGV_LOG")" "$FAKE_PW" "argv に秘密なし"
  fi
  assert_not_contains "$(cat "$T/quiesce.json" 2>/dev/null || true)" "$FAKE_PW" "quiesce.json に秘密なし"
  assert_not_contains "$(grep -r . "$REPOSITORY_LOCK_DIR" 2>/dev/null | head -c 2000)" "$FAKE_PW" "lock metadata に秘密なし"
}

run_case case_lock_shared_and_exclusive
run_case case_lock_bad_mode
run_case case_quiesce_all_fresh
run_case case_quiesce_one_replica_stale
run_case case_quiesce_scheduler_no_ack
run_case case_quiesce_scheduler_dir_missing
run_case case_quiesce_ddl_lock_conflict
run_case case_quiesce_release_after_ddl_conflict
run_case case_uploads_snapshot_normal
run_case case_uploads_snapshot_empty_version
run_case case_uploads_snapshot_rejects_unsafe_pointer
run_case case_uploads_snapshot_symlink
run_case case_uploads_snapshot_fifo
run_case case_uploads_snapshot_hardlink
run_case case_uploads_snapshot_encoded_name
run_case case_uploads_snapshot_same_filesystem_by_device
run_case case_uploads_snapshot_different_filesystem_rejected
run_case case_uploads_snapshot_filesystem_unknown_rejected
run_case case_uploads_snapshot_failure_removes_staging
run_case case_quiesce_no_secret_leak

if grep -r "$FAKE_PW" "$TEST_LOG" > /dev/null 2>&1; then
  test_fail "global-secret-scan" "TEST_LOG に秘密が残った"
else
  test_assert "global-secret-scan"
fi

test_summary "HFP-03-002 quiesce/lock/uploads"
