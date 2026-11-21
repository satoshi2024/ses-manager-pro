#!/usr/bin/env bash
# ============================================================
# HFP-03-004 15 分間隔整合 checkpoint
#
# DB/uploads を同じ整合点で固定する:
#   1. repository exclusive lock
#   2. 書込み静止（QUIESCE_PROVIDER。本番は quiesce-db: 保持 process・DDL lock・DB の信号表。
#      隔離環境の quiesce-local: replicas / scheduler / DDL lock）
#   3. FLUSH BINARY LOGS → rotation 前 file を closed coordinate とする
#   4. archiver が closed file を source size まで追従し checksum 検証
#   5. uploads snapshot（同一静止区間）
#   6. verify（静止が区間の終わりまで途切れなかったこと。失敗したら VALID にしない）→ 静止解除
#   7. snapshot-binlog（immutable 化 + restic）→ checkpoint metadata +
#      manifest + restic backup + restore verify → status=valid 昇格
#   8. checkpoint-index.json に VALID 登録
# active raw file は repository 対象にしない（snapshot-binlog が除外する）。
# 長い子 process は保持 process の pipe の書き口を閉じて起動する（{QUIESCE_HOLD_FD}>&-。lib/quiesce.sh）。
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
. "$LIB_DIR/content-hash.sh"
. "$LIB_DIR/schema-fingerprint.sh"
. "$LIB_DIR/uploads-pointer.sh"

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
    # 解除の診断（保持 process の log の要約等）は標準エラーに出す（結果の JSON の標準出力には出さない）
    if ! quiesce::release > /dev/null; then
      echo "[create-checkpoint] 重大: 静止解除に失敗しました" >&2
    fi
    QUIESCED=0
  fi
  repository_lock::release
}

# source の binlog 一覧
checkpoint::source_logs() {
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SHOW BINARY LOGS;" "$MYSQL_DATABASE" 2>/dev/null {QUIESCE_HOLD_FD}>&-
}

# 指定 file の source 側 size（SHOW BINARY LOGS から。一覧はパイプで渡さない。RB-W P1-1、lib/binlog.sh 参照）
checkpoint::source_size() { # file
  local logs
  logs=$(checkpoint::source_logs) || return 1
  binlog::size_from_logs "$logs" "$1"
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
  # RB-W 追加修正 C: uploads は根（UPLOADS_ROOT）と pointer（UPLOADS_POINTER）で指定する（廃止した UPLOADS_DIR が残っていれば失敗）
  uploads_pointer::check_env || common::fail "uploads の設定が不正です（UPLOADS_ROOT・UPLOADS_POINTER）"
  common::require_env UPLOADS_STAGING_PARENT
  # 書込み静止の provider と、その provider に要る設定（heartbeat の directory は quiesce-local の時だけ必須）
  quiesce::check_provider || common::fail "書込み静止の provider の設定が不正です（QUIESCE_PROVIDER）"
  QUIESCE_JOB_KIND=CHECKPOINT
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
  restic::ensure_repository "$RESTIC_BIN" "$work/restic.log" {QUIESCE_HOLD_FD}>&- \
    || common::fail "restic repository を準備できません"

  if ! repository_lock::acquire exclusive "${BACKUP_LOCK_TIMEOUT:-120}" "create-checkpoint"; then
    common::fail "repository lock を取得できませんでした"
  fi

  # source UUID（lineage 記録用）
  local uuid=""
  uuid=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SELECT @@server_uuid;" 2>/dev/null {QUIESCE_HOLD_FD}>&-) || common::fail "server_uuid を取得できません"
  SOURCE_SERVER_UUID=$uuid

  # 静止
  quiesce::acquire || common::fail "書込み静止に失敗したため checkpoint を発行しません"
  QUIESCED=1
  CONSISTENCY_TIME_UTC=$(quiesce::status_json | jq -r '.started_at_utc // empty')

  # rotation: 直前の file が closed になる
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
    --execute "FLUSH BINARY LOGS;" 2>/dev/null {QUIESCE_HOLD_FD}>&- || common::fail "FLUSH BINARY LOGS に失敗しました"

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
  quiesce::step flush_binary_logs

  # A-NF09-5: 主要 table の順序付き内容ハッシュ（静止区間内 = closed coordinate 時点の内容）。
  # PITR はこの checkpoint の binlog_end まで replay するため、復元検証は同じ値になることを照合する。
  # 指定した table を 1 つでも計算できなければ checkpoint を VALID にしない。
  METADATA_CONTENT_HASHES_JSON='{}'
  if [[ -n "${CONTENT_HASH_TABLES:-}" ]]; then
    METADATA_CONTENT_HASHES_JSON=$(content_hash::collect "$CONTENT_HASH_TABLES" \
        "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" "$MYSQL_DATABASE" {QUIESCE_HOLD_FD}>&-) \
      || common::fail "主要 table の内容ハッシュを計算できません（CONTENT_HASH_TABLES）"
    quiesce::step content_hash
  fi
  # G-04（RB-W W-1）: schema fingerprint（同じ静止区間 = closed coordinate 時点の schema）。設定によらず常に
  # 記録し、計算できなければ checkpoint を VALID にしない。
  METADATA_SCHEMA_FINGERPRINT_JSON=$(schema_fingerprint::compute \
      "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" "$MYSQL_DATABASE" {QUIESCE_HOLD_FD}>&-) \
    || common::fail "schema fingerprint を計算できません（lib/schema-fingerprint.sh）"
  quiesce::step schema_fingerprint

  # archiver 追従 + checksum
  checkpoint::wait_archiver "$closed_file" "$closed_size" "${CHECKPOINT_ARCHIVER_WAIT:-120}" \
    || common::fail "archiver 追従待ちで失敗"
  local raw_file="$BINLOG_RAW_DIR/$closed_file"
  binlog::size_ok "$raw_file" "$closed_size" || common::fail "closed file が truncated"
  binlog::verify_checksum "$raw_file" {QUIESCE_HOLD_FD}>&- || common::fail "closed file の checksum 検証に失敗"
  quiesce::step archiver

  # uploads snapshot（同一静止区間。pointer が指す版の directory）
  local uploads_snap="$work/uploads-snapshot.json"
  if ! "$PROVIDERS_DIR/uploads-local.sh" snapshot "$UPLOADS_ROOT" "$UPLOADS_POINTER" "$UPLOADS_STAGING_PARENT" "$uploads_snap" \
      {QUIESCE_HOLD_FD}>&-; then
    common::fail "uploads snapshot に失敗しました"
  fi
  UPLOADS_SNAPSHOT_ID=$(jq -r '.snapshot_id // empty' "$uploads_snap")
  UPLOADS_STAGING_DIR=$(jq -r '.staging_dir // empty' "$uploads_snap")
  # snapshot した pointer の名前と版（metadata.json と checkpoint index の uploads_pointer に記録する。RB-W 追加修正 C）
  UPLOADS_POINTER_NAME=$(jq -r '.pointer // empty' "$uploads_snap")
  UPLOADS_POINTER_TARGET=$(jq -r '.pointer_target // empty' "$uploads_snap")
  [[ -n "$UPLOADS_SNAPSHOT_ID" ]] || common::fail "uploads snapshot ID を取得できません"
  [[ -n "$UPLOADS_POINTER_NAME" && -n "$UPLOADS_POINTER_TARGET" ]] || common::fail "uploads の pointer と版を取得できません"
  quiesce::step uploads

  # 静止が区間の終わりまで途切れなかったことを確かめる（RB-QUIESCE §7.2。失敗は cleanup が VERIFY_FAILED で解除する）
  quiesce::verify || common::fail "静止が途中で途切れたため VALID にしません"

  # 静止解除
  if ! quiesce::release; then
    QUIESCED=0
    common::fail "静止解除失敗（incident 扱い）"
  fi
  QUIESCED=0

  # snapshot-binlog（immutable 化 + restic。lock は本 script が保持済みのため --no-lock）
  local snap_out=""
  snap_out=$("$SNAPSHOT_BINLOG_BIN" --no-lock 2>&1 {QUIESCE_HOLD_FD}>&-) || common::fail "snapshot-binlog に失敗しました: $snap_out"

  # checkpoint payload（uploads + metadata + manifest）
  local payload="$work/payload"
  mkdir -p "$payload/uploads"
  cp -a "$UPLOADS_STAGING_DIR"/. "$payload/uploads/" {QUIESCE_HOLD_FD}>&-
  cp "$uploads_snap" "$payload/uploads-snapshot.json"

  BINLOG_START_FILE=$closed_file
  BINLOG_START_POSITION=$closed_size
  GTID_EXECUTED=""
  DATABASE_FINGERPRINT=$(metadata::db_fingerprint "${ENVIRONMENT:-unknown}" "$MYSQL_DATABASE")
  SOURCE_LINEAGE=$(metadata::lineage "$SOURCE_SERVER_UUID" "$DATABASE_FINGERPRINT")
  KIND=checkpoint
  STATUS=PENDING
  metadata::build "$work" "$payload/metadata.json"

  manifest::build "$payload" {QUIESCE_HOLD_FD}>&- || common::fail "manifest 生成に失敗しました"
  manifest::finalize "$payload" {QUIESCE_HOLD_FD}>&- || common::fail "manifest 固定に失敗しました"

  local snap=""
  if ! snap=$("$RESTIC_BIN" backup "$payload" --tag "kind=checkpoint" --tag "date=$stamp" \
      --tag "status=pending" --json 2>> "$work/restic.log" {QUIESCE_HOLD_FD}>&- \
      | jq -rs 'map(select(.message_type == "summary"))[0].snapshot_id // empty'); then
    common::fail "restic backup に失敗しました: $(common::redact < "$work/restic.log")"
  fi
  [[ -n "$snap" ]] || common::fail "restic snapshot ID を取得できません"

  # restore verify + manifest 照合
  local verify_dir="$work/verify"
  mkdir -p "$verify_dir"
  "$RESTIC_BIN" restore "$snap" --target "$verify_dir" --verify >> "$work/restic.log" 2>&1 {QUIESCE_HOLD_FD}>&- \
    || common::fail "restic restore verify に失敗しました"
  local restored_root=""
  restored_root=$(find "$verify_dir" -name manifest.json -print -quit 2>/dev/null | xargs -r dirname)
  [[ -n "$restored_root" ]] || common::fail "restore 結果に manifest.json が見つかりません"
  manifest::verify "$restored_root" "$restored_root/manifest.json" {QUIESCE_HOLD_FD}>&- \
    || common::fail "restore 後の manifest 検証に失敗しました"

  "$RESTIC_BIN" tag --add "status=valid" --remove "status=pending" "$snap" >> "$work/restic.log" 2>&1 {QUIESCE_HOLD_FD}>&- \
    || common::fail "snapshot の status タグ更新に失敗しました"
  # restic 0.17 の tag は新 id の snapshot を作るため、id を再解決する
  snap=$(restic::resolve_snapshot_by_tag "$RESTIC_BIN" "date=$stamp" {QUIESCE_HOLD_FD}>&-)
  [[ -n "$snap" ]] || common::fail "tag 更新後の snapshot ID を解決できません"

  # checkpoint-index 登録
  local index_dir="$BACKUP_WORK_DIR/index"
  mkdir -p "$index_dir"
  jq --arg snap "$snap" --arg status "VALID" --arg uploaded_at_utc "$(common::now_utc)" \
    --arg closed_file "$closed_file" --arg closed_size "$closed_size" \
    '.status = $status | .restic_snapshot_id = $snap | .uploaded_at_utc = $uploaded_at_utc |
     .binlog_end = {file: $closed_file, position: ($closed_size|tonumber)}' \
    "$payload/metadata.json" > "$index_dir/checkpoint-$stamp.json"

  local quiesce_json
  quiesce_json=$(quiesce::status_json)
  jq -n \
    --arg kind "checkpoint" \
    --arg status "VALID" \
    --arg consistency_time_utc "$CONSISTENCY_TIME_UTC" \
    --arg restic_snapshot_id "$snap" \
    --arg binlog_file "$closed_file" \
    --arg binlog_position "$closed_size" \
    --arg manifest_sha256 "$(cat "$payload/manifest.sha256")" \
    --arg uploads_snapshot_id "$UPLOADS_SNAPSHOT_ID" \
    --arg uploads_pointer "$UPLOADS_POINTER_NAME" \
    --arg uploads_version "$UPLOADS_POINTER_TARGET" \
    --argjson quiesce "$quiesce_json" \
    '{kind: $kind, status: $status, consistency_time_utc: $consistency_time_utc,
      restic_snapshot_id: $restic_snapshot_id,
      binlog_end: {file: $binlog_file, position: ($binlog_position|tonumber)},
      manifest_sha256: $manifest_sha256, uploads_snapshot_id: $uploads_snapshot_id,
      uploads_pointer: {name: $uploads_pointer, target: $uploads_version},
      quiesce: {provider: ($quiesce.provider // null), mode: ($quiesce.mode // null),
                request_id: ($quiesce.request_id // null),
                started_at_utc: ($quiesce.started_at_utc // ""), verified_at_utc: ($quiesce.verified_at_utc // null),
                released_at_utc: ($quiesce.released_at_utc // ""), window_seconds: ($quiesce.window_seconds // null)}}'
  return 0
}

case "${1:---run}" in
  --run) main ;;
  # 停止確認は明示の引数でだけ選ぶ（design.md §9.3。RB-QUIESCE BWQ-010）
  --app-stopped) quiesce::select_app_stopped; main ;;
  --help|-h) cat <<'EOF'
Usage: create-checkpoint.sh [--app-stopped | --help]
  --app-stopped  app が動いていない時の checkpoint（app の停止を DB の信号表で確かめる provider quiesce-app-stopped を使う。
                 QUIESCE_PROVIDER の環境変数では選べない）
環境変数: BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, MYSQL_HOST, MYSQL_USER,
MYSQL_PASSWORD_FILE, MYSQL_DATABASE, BACKUP_WORK_DIR, UPLOADS_ROOT, UPLOADS_POINTER（既定 current）,
UPLOADS_STAGING_PARENT, QUIESCE_PROVIDER（quiesce-db / quiesce-local。quiesce-local の時だけ REPLICA_HEARTBEAT_DIR,
SCHEDULER_ACK_DIR）, QUIESCE_*（README「設定」）, BINLOG_RAW_DIR, BINLOG_IMMUTABLE_DIR, APP_COMMIT, FLYWAY_VERSION,
CONTENT_HASH_TABLES
EOF
    exit 0 ;;
  *) echo "Usage: create-checkpoint.sh [--app-stopped | --help]" >&2; exit 2 ;;
esac
