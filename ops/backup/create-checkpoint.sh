#!/usr/bin/env bash
# ============================================================
# HFP-03-004 15 分間隔整合 checkpoint
#
# DB/uploads を同じ整合点で固定する:
#   1. repository exclusive lock
#   2. 書込み静止（replicas / scheduler / DDL lock）
#   3. FLUSH BINARY LOGS → rotation 前 file を closed coordinate とする
#   4. archiver が closed file を source size まで追従し checksum 検証
#   5. uploads snapshot（同一静止区間）
#   6. 静止解除
#   7. snapshot-binlog（immutable 化 + restic）→ checkpoint metadata +
#      manifest + restic backup + restore verify → status=valid 昇格
#   8. checkpoint-index.json に VALID 登録
# active raw file は repository 対象にしない（snapshot-binlog が除外する）。
#
# usage: create-checkpoint.sh [--help]
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"
. "$LIB_DIR/repository-lock.sh"
. "$LIB_DIR/quiesce.sh"
. "$LIB_DIR/manifest.sh"
. "$LIB_DIR/metadata.sh"
. "$LIB_DIR/binlog.sh"

PROVIDERS_DIR="$LIB_DIR/providers"
[[ -d "$PROVIDERS_DIR" ]] || PROVIDERS_DIR=$(cd "$LIB_DIR/../providers" && pwd)

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}
RESTIC_BIN=${RESTIC_BIN:-restic}
SNAPSHOT_BINLOG_BIN=${SNAPSHOT_BINLOG_BIN:-snapshot-binlog.sh}

QUIESCED=0
work=""

checkpoint::cleanup() {
  if (( QUIESCED )); then
    if ! quiesce::release > /dev/null 2>&1; then
      echo "[create-checkpoint] 重大: 静止解除に失敗しました" >&2
    fi
    QUIESCED=0
  fi
  repository_lock::release
}

# source の binlog 一覧
checkpoint::source_logs() {
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SHOW BINARY LOGS;" "$MYSQL_DATABASE" 2>/dev/null
}

# 指定 file の source 側 size（SHOW BINARY LOGS から）
checkpoint::source_size() { # file
  checkpoint::source_logs | awk -v f="$1" '$1 == f {print $2; exit}'
}

# archiver の追従を bounded wait（closed file が source size に達するまで）
checkpoint::wait_archiver() { # file expected_size timeout_seconds
  local file=$1 expected=$2 timeout=$3
  local i
  for ((i = 0; i < timeout; i++)); do
    if [[ -f "$BINLOG_RAW_DIR/$file" ]]; then
      local have
      have=$(stat -c %s "$BINLOG_RAW_DIR/$file" 2>/dev/null || echo 0)
      if (( have >= expected )); then
        return 0
      fi
    fi
    sleep 1
  done
  echo "checkpoint: archiver が追従しません: $file (expected=$expected)" >&2
  return 1
}

main() {
  common::require_env BACKUP_REPOSITORY
  common::require_env RESTIC_PASSWORD_FILE
  common::require_env MYSQL_HOST
  common::require_env MYSQL_USER
  common::require_env MYSQL_PASSWORD_FILE
  common::require_env MYSQL_DATABASE
  common::require_env BACKUP_WORK_DIR
  common::require_env UPLOADS_DIR
  common::require_env UPLOADS_STAGING_PARENT
  common::require_env REPLICA_HEARTBEAT_DIR
  common::require_env SCHEDULER_ACK_DIR
  common::require_env BINLOG_RAW_DIR
  common::require_env BINLOG_IMMUTABLE_DIR
  MYSQL_PORT=${MYSQL_PORT:-3306}

  export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE

  if ! mysql_options::init; then
    common::fail "mysql option file を作成できません（接続設定を確認してください）"
  fi

  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  work="$BACKUP_WORK_DIR/checkpoint-$stamp"
  mkdir -p "$work"
  common::trap_add 'rm -rf "$work"; checkpoint::cleanup'
  restic::ensure_repository "$RESTIC_BIN" "$work/restic.log" \
    || common::fail "restic repository を準備できません"

  if ! repository_lock::acquire exclusive "${BACKUP_LOCK_TIMEOUT:-120}" "create-checkpoint"; then
    common::fail "repository lock を取得できませんでした"
  fi

  # source UUID（lineage 記録用）
  local uuid=""
  uuid=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SELECT @@server_uuid;" 2>/dev/null) || common::fail "server_uuid を取得できません"
  SOURCE_SERVER_UUID=$uuid

  # 静止
  quiesce::acquire || common::fail "書込み静止に失敗したため checkpoint を発行しません"
  QUIESCED=1
  CONSISTENCY_TIME_UTC=$(quiesce::status_json | jq -r '.started_at_utc // empty')

  # rotation: 直前の file が closed になる
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
    --execute "FLUSH BINARY LOGS;" 2>/dev/null || common::fail "FLUSH BINARY LOGS に失敗しました"

  local logs=""
  logs=$(checkpoint::source_logs) || common::fail "SHOW BINARY LOGS に失敗しました"
  # closed = rotation 前の最新（最後から 2 番目）
  local closed_file=""
  closed_file=$(printf '%s\n' "$logs" | awk '{if (NF>0) lines[NR]=$1} END{if (NR>=2) print lines[NR-1]; else print ""}')
  if [[ -z "$closed_file" ]]; then
    echo "checkpoint: rotation 後の closed file を特定できません" >&2
    common::fail "closed file がありません"
  fi
  local closed_size=""
  closed_size=$(checkpoint::source_size "$closed_file")
  [[ -n "$closed_size" ]] || common::fail "closed file の size を取得できません"

  # archiver 追従 + checksum
  checkpoint::wait_archiver "$closed_file" "$closed_size" "${CHECKPOINT_ARCHIVER_WAIT:-120}" \
    || common::fail "archiver 追従待ちで失敗"
  local raw_file="$BINLOG_RAW_DIR/$closed_file"
  binlog::size_ok "$raw_file" "$closed_size" || common::fail "closed file が truncated"
  binlog::verify_checksum "$raw_file" || common::fail "closed file の checksum 検証に失敗"

  # uploads snapshot（同一静止区間）
  local uploads_snap="$work/uploads-snapshot.json"
  if ! "$PROVIDERS_DIR/uploads-local.sh" snapshot "$UPLOADS_DIR" "$UPLOADS_STAGING_PARENT" "$uploads_snap"; then
    common::fail "uploads snapshot に失敗しました"
  fi
  UPLOADS_SNAPSHOT_ID=$(jq -r '.snapshot_id // empty' "$uploads_snap")
  UPLOADS_STAGING_DIR=$(jq -r '.staging_dir // empty' "$uploads_snap")
  [[ -n "$UPLOADS_SNAPSHOT_ID" ]] || common::fail "uploads snapshot ID を取得できません"

  # 静止解除
  if ! quiesce::release; then
    QUIESCED=0
    common::fail "静止解除失敗（incident 扱い）"
  fi
  QUIESCED=0

  # snapshot-binlog（immutable 化 + restic。lock は本 script が保持済みのため --no-lock）
  local snap_out=""
  snap_out=$("$SNAPSHOT_BINLOG_BIN" --no-lock 2>&1) || common::fail "snapshot-binlog に失敗しました: $snap_out"

  # checkpoint payload（uploads + metadata + manifest）
  local payload="$work/payload"
  mkdir -p "$payload/uploads"
  cp -a "$UPLOADS_STAGING_DIR"/. "$payload/uploads/"
  cp "$uploads_snap" "$payload/uploads-snapshot.json"

  BINLOG_START_FILE=$closed_file
  BINLOG_START_POSITION=$closed_size
  GTID_EXECUTED=""
  DATABASE_FINGERPRINT=$(metadata::db_fingerprint "${ENVIRONMENT:-unknown}" "$MYSQL_DATABASE")
  SOURCE_LINEAGE=$(metadata::lineage "$SOURCE_SERVER_UUID" "$DATABASE_FINGERPRINT")
  KIND=checkpoint
  STATUS=PENDING
  metadata::build "$work" "$payload/metadata.json"

  manifest::build "$payload" || common::fail "manifest 生成に失敗しました"
  manifest::finalize "$payload" || common::fail "manifest 固定に失敗しました"

  local snap=""
  if ! snap=$("$RESTIC_BIN" backup "$payload" --tag "kind=checkpoint" --tag "date=$stamp" \
      --tag "status=pending" --json 2>> "$work/restic.log" \
      | jq -rs 'map(select(.message_type == "summary"))[0].snapshot_id // empty'); then
    common::fail "restic backup に失敗しました: $(common::redact < "$work/restic.log")"
  fi
  [[ -n "$snap" ]] || common::fail "restic snapshot ID を取得できません"

  # restore verify + manifest 照合
  local verify_dir="$work/verify"
  mkdir -p "$verify_dir"
  "$RESTIC_BIN" restore "$snap" --target "$verify_dir" --verify >> "$work/restic.log" 2>&1 \
    || common::fail "restic restore verify に失敗しました"
  local restored_root=""
  restored_root=$(find "$verify_dir" -name manifest.json -print -quit 2>/dev/null | xargs -r dirname)
  [[ -n "$restored_root" ]] || common::fail "restore 結果に manifest.json が見つかりません"
  manifest::verify "$restored_root" "$restored_root/manifest.json" \
    || common::fail "restore 後の manifest 検証に失敗しました"

  "$RESTIC_BIN" tag --add "status=valid" --remove "status=pending" "$snap" >> "$work/restic.log" 2>&1 \
    || common::fail "snapshot の status タグ更新に失敗しました"
  # restic 0.17 の tag は新 id の snapshot を作るため、id を再解決する
  snap=$(restic::resolve_snapshot_by_tag "$RESTIC_BIN" "date=$stamp")
  [[ -n "$snap" ]] || common::fail "tag 更新後の snapshot ID を解決できません"

  # checkpoint-index 登録
  local index_dir="$BACKUP_WORK_DIR/index"
  mkdir -p "$index_dir"
  jq --arg snap "$snap" --arg status "VALID" --arg uploaded_at_utc "$(common::now_utc)" \
    --arg closed_file "$closed_file" --arg closed_size "$closed_size" \
    '.status = $status | .restic_snapshot_id = $snap | .uploaded_at_utc = $uploaded_at_utc |
     .binlog_end = {file: $closed_file, position: ($closed_size|tonumber)}' \
    "$payload/metadata.json" > "$index_dir/checkpoint-$stamp.json"

  jq -n \
    --arg kind "checkpoint" \
    --arg status "VALID" \
    --arg consistency_time_utc "$CONSISTENCY_TIME_UTC" \
    --arg restic_snapshot_id "$snap" \
    --arg binlog_file "$closed_file" \
    --arg binlog_position "$closed_size" \
    --arg manifest_sha256 "$(cat "$payload/manifest.sha256")" \
    --arg uploads_snapshot_id "$UPLOADS_SNAPSHOT_ID" \
    '{kind: $kind, status: $status, consistency_time_utc: $consistency_time_utc,
      restic_snapshot_id: $restic_snapshot_id,
      binlog_end: {file: $binlog_file, position: ($binlog_position|tonumber)},
      manifest_sha256: $manifest_sha256, uploads_snapshot_id: $uploads_snapshot_id}'
  return 0
}

case "${1:---run}" in
  --run) main ;;
  --help|-h) cat <<'EOF'
Usage: create-checkpoint.sh [--help]
環境変数: BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, MYSQL_HOST, MYSQL_USER,
MYSQL_PASSWORD_FILE, MYSQL_DATABASE, BACKUP_WORK_DIR, UPLOADS_DIR,
UPLOADS_STAGING_PARENT, REPLICA_HEARTBEAT_DIR, SCHEDULER_ACK_DIR,
BINLOG_RAW_DIR, BINLOG_IMMUTABLE_DIR, APP_COMMIT, FLYWAY_VERSION
EOF
    exit 0 ;;
  *) echo "Usage: create-checkpoint.sh [--help]" >&2; exit 2 ;;
esac
