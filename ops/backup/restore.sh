#!/usr/bin/env bash
# ============================================================
# HFP-03-007 recovery target への staging restore
#
# 前提: plan-restore.sh で生成された APPLYABLE な plan。
# 流れ:
#   1. plan 検証（tamper / EXPIRED / RPO_MISSED）
#   2. target guard（UUID / allowlist / marker / 空 DB / default 拒否）
#   3. 二者承認（plan SHA・target UUID・期限に bind、異なる actor）
#   4. restic restore + manifest verify（full / binlog / uploads）
#   5. dump import → 全 binlog を単一 mysqlbinlog → 単一 mysql connection で
#      start/stop position を指定して replay
#   6. uploads は versioned staging へ安全展開（production path は触らない）
#   7. 失敗時は FAILED_RESTORE として隔離し、公開しない
#
# usage: restore.sh --plan <plan-id> --approval <claim1> --approval <claim2>
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/plan.sh"
. "$LIB_DIR/manifest.sh"
. "$LIB_DIR/target-guard.sh"
. "$LIB_DIR/approval.sh"
. "$LIB_DIR/safe-extract.sh"

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}
RESTIC_BIN=${RESTIC_BIN:-restic}

STAGING_ROOT=${STAGING_ROOT:-${TMPDIR:-/tmp}/ses-restore}
RESTORE_STATE="RESTORING"
STAGE_DIR=""

restore::cleanup() {
  # RESTORED 以外は staging を隔離（削除しない。調査用 read-only）
  if [[ "$RESTORE_STATE" != "RESTORED" && -n "$STAGE_DIR" && -d "$STAGE_DIR" ]]; then
    chmod -R a-w "$STAGE_DIR" 2>/dev/null || true
  fi
}

restore::fail() { # message
  echo "[restore] ERROR: $*" >&2
  RESTORE_STATE="FAILED_RESTORE"
  exit 1
}

usage() {
  cat <<'EOF'
Usage: restore.sh --plan <plan-id> --approval <claim1.json> --approval <claim2.json>
環境変数: TARGET_HOST, TARGET_PORT, TARGET_USER, TARGET_PASSWORD_FILE,
TARGET_DATABASE, SOURCE_HOST, TARGET_ALLOWLIST_FILE, APPROVAL_PUBKEY_DIR,
BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, PLANS_DIR, STAGING_ROOT
EOF
}

main() {
  common::require_env BACKUP_REPOSITORY
  common::require_env RESTIC_PASSWORD_FILE
  common::require_env PLANS_DIR
  common::require_env TARGET_HOST
  common::require_env TARGET_PORT
  common::require_env TARGET_USER
  common::require_env TARGET_PASSWORD_FILE
  common::require_env TARGET_DATABASE
  common::require_env SOURCE_HOST
  TARGET_PORT=${TARGET_PORT:-3306}

  # R1 P1-05: 復元先への接続は VERIFY 系 TLS のみ許可（DISABLED/平文は拒否）
  case "${TARGET_TLS_MODE:-VERIFY_CA}" in
    VERIFY_IDENTITY|VERIFY_CA) TARGET_TLS_MODE_SAFE=${TARGET_TLS_MODE:-VERIFY_CA} ;;
    *)
      restore::fail "TARGET_TLS_MODE は VERIFY_CA / VERIFY_IDENTITY のみ許可されます（受信: ${TARGET_TLS_MODE:-未設定}）"
      ;;
  esac
  [[ -n "${TARGET_SSL_CAPATH:-}" ]] || restore::fail "TARGET_SSL_CAPATH が未設定です（VERIFY 系 TLS には CA 証明書が必要）"

  export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE

  local plan_path="$PLANS_DIR/$PLAN_ID.json"
  plan::verify "$plan_path" || restore::fail "plan の検証に失敗しました"
  local state
  state=$(plan::status "$plan_path")
  [[ "$state" == "APPLYABLE" ]] || restore::fail "plan は apply 不可です（state=$state）"
  local plan_json
  plan_json=$(cat "$plan_path")

  # target credential 用 option file（source の credential は mount しない）
  target_optfile=$(mktemp)
  {
    echo '[client]'
    echo "user=$TARGET_USER"
    echo "port=$TARGET_PORT"
    echo "password=$(head -n1 "$TARGET_PASSWORD_FILE")"
    echo "ssl-mode=$TARGET_TLS_MODE_SAFE"
    [[ -n "${TARGET_SSL_CAPATH:-}" ]] && echo "ssl-capath=$TARGET_SSL_CAPATH"
  } > "$target_optfile"
  chmod 600 "$target_optfile"
  TARGET_OPT_ARGS=(--defaults-extra-file="$target_optfile" -h "$TARGET_HOST")
  common::trap_add 'rm -f "$target_optfile"; restore::cleanup'

  # target guard（plan・allowlist・marker・空 DB・default 拒否）
  target_guard::run "$plan_json" "$TARGET_DATABASE" || restore::fail "target guard に失敗しました"

  # 二者承認（target UUID を claim に bind）
  local target_uuid
  target_uuid=$(target_guard::target_uuid) || restore::fail "target UUID を取得できません"
  approval::collect_and_verify "$plan_path" "$target_uuid" "$CLAIM1" "$CLAIM2" \
    || restore::fail "承認が不足または不正です"

  # R1 P0-01: 同一 target への再 restore でも replay が沈黙スキップされないよう、
  # import 前に gtid_executed / binlog をリセットする（専用 recovery target 限定。
  # target guard が source と同一 UUID を拒否済み）。
  # R2 P2-01: 承認検証の後に実行する（未承認 plan で target を変更しない）。
  if ! "$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "RESET MASTER;" 2>/dev/null; then
    restore::fail "target の GTID 状態をリセットできません（RESET MASTER）。再 restore の正しさを保証できません"
  fi

  # staging（失敗時は read-only に隔離）
  STAGE_DIR="$STAGING_ROOT/$(basename "$plan_path" .json)-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$STAGE_DIR"

  local full_snap ckpt_snap
  full_snap=$(printf '%s' "$plan_json" | jq -r '.base_full.restic_snapshot_id // empty')
  ckpt_snap=$(printf '%s' "$plan_json" | jq -r '.effective_checkpoint.restic_snapshot_id // empty')
  [[ -n "$full_snap" && -n "$ckpt_snap" ]] || restore::fail "plan に snapshot ID がありません"

  # 1) full restore + manifest verify
  mkdir -p "$STAGE_DIR/full"
  "$RESTIC_BIN" restore "$full_snap" --target "$STAGE_DIR/full" --verify >> "$STAGE_DIR/restic.log" 2>&1 \
    || restore::fail "full snapshot の restore に失敗しました"
  local full_root
  full_root=$(find "$STAGE_DIR/full" -name manifest.json -print -quit 2>/dev/null | xargs -r dirname)
  [[ -n "$full_root" ]] || restore::fail "full の manifest が見つかりません"
  manifest::verify "$full_root" "$full_root/manifest.json" || restore::fail "full の manifest 検証に失敗"

  # 2) uploads restore（checkpoint snapshot 内） + manifest verify
  mkdir -p "$STAGE_DIR/uploads"
  "$RESTIC_BIN" restore "$ckpt_snap" --target "$STAGE_DIR/uploads" --verify >> "$STAGE_DIR/restic.log" 2>&1 \
    || restore::fail "checkpoint snapshot の restore に失敗しました"
  local ckpt_root
  ckpt_root=$(find "$STAGE_DIR/uploads" -name manifest.json -print -quit 2>/dev/null | xargs -r dirname)
  [[ -n "$ckpt_root" ]] || restore::fail "checkpoint の manifest が見つかりません"
  manifest::verify "$ckpt_root" "$ckpt_root/manifest.json" || restore::fail "checkpoint の manifest 検証に失敗"

  # 3) binlog restore
  local binlog_count
  binlog_count=$(printf '%s' "$plan_json" | jq '.binlog_replay.files | length')
  local binlog_dir="$STAGE_DIR/binlogs"
  mkdir -p "$binlog_dir"
  local i
  for ((i = 0; i < binlog_count; i++)); do
    local bsnap bfile
    bsnap=$(printf '%s' "$plan_json" | jq -r ".binlog_replay.files[$i].snapshot_id")
    bfile=$(printf '%s' "$plan_json" | jq -r ".binlog_replay.files[$i].file")
    mkdir -p "$STAGE_DIR/binlog-$i"
    "$RESTIC_BIN" restore "$bsnap" --target "$STAGE_DIR/binlog-$i" --verify >> "$STAGE_DIR/restic.log" 2>&1 \
      || restore::fail "binlog snapshot の restore に失敗しました: $bsnap"
    local found
    found=$(find "$STAGE_DIR/binlog-$i" -name "$bfile" -type f -print -quit 2>/dev/null)
    [[ -n "$found" ]] || restore::fail "binlog file が見つかりません: $bfile"
    # R1 P2: replay 前に binlog file の SHA を plan（binlog index 由来）と照合する
    local want_sha got_sha
    want_sha=$(printf '%s' "$plan_json" | jq -r ".binlog_replay.files[$i].sha256 // empty")
    if [[ -n "$want_sha" ]]; then
      got_sha=$(sha256sum "$found" | awk '{print $1}')
      [[ "$got_sha" == "$want_sha" ]] || restore::fail "binlog file の SHA が一致しません: $bfile"
    fi
    cp -a -- "$found" "$binlog_dir/$bfile"
    rm -rf "$STAGE_DIR/binlog-$i"
  done

  # 4) dump import（新規 DB へ。target guard で空を確認済み）
  local dump_file
  dump_file=$(find "$full_root" -name '*.sql' -type f -print -quit 2>/dev/null)
  [[ -n "$dump_file" ]] || restore::fail "dump が見つかりません"
  "$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" --binary-mode \
    --execute "CREATE DATABASE IF NOT EXISTS \`$TARGET_DATABASE\` CHARACTER SET utf8mb4;" 2>/dev/null \
    || restore::fail "target DB を作成できません"
  "$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" --binary-mode "$TARGET_DATABASE" < "$dump_file" \
    || restore::fail "dump import に失敗しました"

  # 5) binlog replay: 単一 mysqlbinlog → 単一 mysql connection（--binary-mode）
  local start_pos stop_pos
  start_pos=$(printf '%s' "$plan_json" | jq -r '.binlog_replay.start_position // empty')
  stop_pos=$(printf '%s' "$plan_json" | jq -r '.binlog_replay.end_position // empty')
  [[ -n "$start_pos" && -n "$stop_pos" ]] || restore::fail "plan に replay position がありません"

  local binlog_args=()
  local j
  for ((j = 0; j < binlog_count; j++)); do
    local bf
    bf=$(printf '%s' "$plan_json" | jq -r ".binlog_replay.files[$j].file")
    binlog_args+=("$binlog_dir/$bf")
  done

  local mb_rc=0 m_rc=0
  set +e
  "$MYSQLBINLOG_BIN" --start-position="$start_pos" --stop-position="$stop_pos" \
    "${binlog_args[@]}" 2>> "$STAGE_DIR/replay.log" \
    | "$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" --binary-mode "$TARGET_DATABASE" \
        2>> "$STAGE_DIR/replay.log"
  pstatus=("${PIPESTATUS[@]}")
  set -e
  mb_rc=${pstatus[0]}
  m_rc=${pstatus[1]}
  if (( mb_rc != 0 || m_rc != 0 )); then
    restore::fail "binlog replay に失敗しました（mysqlbinlog=$mb_rc mysql=$m_rc）"
  fi

  # 6) uploads staging を安全展開（production path は変更しない）
  local uploads_src="$ckpt_root/uploads"
  local uploads_stage="$STAGE_DIR/uploads-ready"
  safe_extract::copy "$uploads_src" "$uploads_stage" || restore::fail "uploads の安全展開に失敗しました"

  RESTORE_STATE="RESTORED"
  jq -n \
    --arg state "$RESTORE_STATE" \
    --arg plan_id "$PLAN_ID" \
    --arg target_uuid "$target_uuid" \
    --arg target_database "$TARGET_DATABASE" \
    --arg staging_dir "$STAGE_DIR" \
    --arg uploads_ready "$uploads_stage" \
    --arg start_position "$start_pos" \
    --arg stop_position "$stop_pos" \
    --arg binlog_count "$binlog_count" \
    --arg replay_log "$STAGE_DIR/replay.log" \
    '{state: $state, plan_id: $plan_id, target_uuid: $target_uuid,
      target_database: $target_database, staging_dir: $staging_dir,
      uploads_ready: $uploads_ready,
      replay: {start_position: ($start_position|tonumber), stop_position: ($stop_position|tonumber),
               binlog_count: ($binlog_count|tonumber), log: $replay_log}}'
  return 0
}

PLAN_ID=""
CLAIM1=""
CLAIM2=""
while (($#)); do
  case "$1" in
    --plan) PLAN_ID=$2; shift 2 ;;
    --plan=*) PLAN_ID=${1#--plan=}; shift ;;
    --approval) [[ -z "$CLAIM1" ]] && CLAIM1=$2 || CLAIM2=$2; shift 2 ;;
    --approval=*) [[ -z "$CLAIM1" ]] && CLAIM1=${1#--approval=} || CLAIM2=${1#--approval=}; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$PLAN_ID" && -n "$CLAIM1" && -n "$CLAIM2" ]] || { usage >&2; exit 2; }
main
