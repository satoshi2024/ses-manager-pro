#!/usr/bin/env bash
# ============================================================
# HFP-03-003 日次 full backup（DB + uploads 同一整合点）
#
# 流れ（BL-005/006/011 対応）:
#   1. repository exclusive lock（bounded timeout）
#   2. preflight（read-only contract）再確認
#   3. 書込み静止（replicas / scheduler / DDL lock）
#   4. mysqldump --single-transaction --source-data=2 + uploads snapshot
#   5. 静止解除（bounded cleanup。解除失敗は重大 alert）
#   6. metadata → manifest.json → manifest.sha256 → staging read-only
#   7. restic backup → restore verify → manifest hash 照合
#   8. VALID な metadata を index へ登録（forget --prune は呼ばない）
#
# 対象外: forget --prune（HFP-03-010 の retention 専用）
# 秘密: MYSQL_PWD 不使用。0600 option file 経由。
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
. "$LIB_DIR/content-hash.sh"
. "$LIB_DIR/schema-fingerprint.sh"
. "$LIB_DIR/binlog.sh"
. "$LIB_DIR/uploads-pointer.sh"

PROVIDERS_DIR="$LIB_DIR/providers"
[[ -d "$PROVIDERS_DIR" ]] || PROVIDERS_DIR=$(cd "$LIB_DIR/../providers" && pwd)

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}
MYSQLDUMP_BIN=${MYSQLDUMP_BIN:-mysqldump}
RESTIC_BIN=${RESTIC_BIN:-restic}
PREFLIGHT_BIN=${PREFLIGHT_BIN:-preflight.sh}

QUIESCED=0
STAGING=""

backup_full::cleanup() {
  if (( QUIESCED )); then
    if ! quiesce::release > /dev/null 2>&1; then
      echo "[backup-full] 重大: 静止解除に失敗しました。service safety を確認してください" >&2
    fi
    QUIESCED=0
  fi
  repository_lock::release
}

# dump の起点（--source-data=2 の coordinate）と GTID の読み取りは lib/binlog.sh の binlog::parse_dump_coords /
# binlog::parse_dump_gtid に 1 つにまとめた（RB-W P3-7。以前は同じ実装がこの script と lib の 2 箇所にあった）。

# repository の存在確認（無ければ初期化。中身があるのに読めない場合は fail-closed）
backup_full::ensure_repository() {
  restic::ensure_repository "$RESTIC_BIN" "$work/restic.log"
}

usage() {
  cat <<'EOF'
Usage: backup-full.sh [--help]

環境変数: BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, MYSQL_HOST, MYSQL_PORT,
MYSQL_USER, MYSQL_PASSWORD_FILE, MYSQL_DATABASE, BACKUP_WORK_DIR,
UPLOADS_ROOT（uploads の根）, UPLOADS_POINTER（既定 current）,
UPLOADS_STAGING_PARENT（uploads と同一 filesystem = 同じ mount 必須）,
REPLICA_HEARTBEAT_DIR, SCHEDULER_ACK_DIR, APP_COMMIT, FLYWAY_VERSION,
CRITICAL_TABLES, CONTENT_HASH_TABLES, BACKUP_TOOL_IMAGE_DIGEST
EOF
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
  common::require_env REPLICA_HEARTBEAT_DIR
  common::require_env SCHEDULER_ACK_DIR
  MYSQL_PORT=${MYSQL_PORT:-3306}

  export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE
  # preflight（子 process）にも同じ uploads の設定を渡す
  export UPLOADS_ROOT UPLOADS_POINTER

  if ! mysql_options::init; then
    common::fail "mysql option file を作成できません（接続設定を確認してください）"
  fi

  local started
  started=$(common::now_utc)
  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)

  mkdir -p "$BACKUP_WORK_DIR"
  work="$BACKUP_WORK_DIR/full-$stamp"
  mkdir -p "$work/db"
  # R1 P1-03: 失敗時も静止解除（quiesce::release + DDL lock session 終了）を必ず実行する。
  # R2 P2-04: trap は連結（mysql-options の option file cleanup を失わない）。
  common::trap_add 'backup_full::cleanup; rm -rf "$work"'
  backup_full::ensure_repository || common::fail "restic repository を準備できません"

  if ! repository_lock::acquire exclusive "${BACKUP_LOCK_TIMEOUT:-120}" "backup-full"; then
    common::fail "repository lock を取得できませんでした"
  fi

  # preflight（read-only）
  local preflight_out=""
  if ! preflight_out=$("$PREFLIGHT_BIN" --json 2>&1); then
    echo "$preflight_out" >&2
    common::fail "preflight 不合格のため full を開始しません"
  fi
  echo "$preflight_out" > "$work/preflight.json"
  SOURCE_SERVER_UUID=$(printf '%s' "$preflight_out" | jq -r '.checks.server_uuid // empty')
  MYSQL_SERVER_VERSION=$(printf '%s' "$preflight_out" | jq -r '.checks.server_version // empty')
  MYSQL_CLIENT_VERSION=$(printf '%s' "$preflight_out" | jq -r '.checks.mysql_client_version // empty')
  [[ -n "$SOURCE_SERVER_UUID" ]] || common::fail "preflight から server_uuid を取得できません"

  # 書込み静止
  if ! quiesce::acquire; then
    common::fail "書込み静止に失敗したため snapshot を発行しません"
  fi
  QUIESCED=1
  QUIESCE_STATE_DIR="$QUIESCE_STATE_DIR"
  CONSISTENCY_TIME_UTC=$(quiesce::status_json | jq -r '.started_at_utc // empty')

  # mysqldump（--source-data=2 の coordinate を後で機械抽出）
  # --no-tablespaces: tablespace の定義（CREATE TABLESPACE 等）を書かない。これにより dump account に PROCESS 権限が要らない
  # （mysqldump 8.0.21 以降は tablespace の情報を読むのに PROCESS が要る。2026-11-18 利用者決定で上線前に外した）。
  # 前提: migration（db/migration・db/migration-prod・db/migration の Java）は general tablespace を使わない
  # （tests/script-contract-test.sh が TABLESPACE の不在を確かめる）。使い始めるときは見直す。
  local dump_file="$work/db/database.sql"
  if ! "$MYSQLDUMP_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
      --single-transaction --quick --routines --events --triggers --hex-blob --no-tablespaces \
      --source-data=2 --set-gtid-purged=OFF "$MYSQL_DATABASE" > "$dump_file" 2> "$work/db/mysqldump.err"; then
    common::fail "mysqldump が失敗しました: $(common::redact < "$work/db/mysqldump.err")"
  fi

  if ! binlog::parse_dump_coords "$dump_file"; then
    common::fail "dump から binlog coordinate を抽出できません（--source-data=2 が無効?）"
  fi
  binlog::parse_dump_gtid "$dump_file"

  # uploads snapshot（pointer が指す版の directory を同一 fs の staging へ。特殊 file は provider が拒否）
  local uploads_snap="$work/uploads-snapshot.json"
  if ! "$PROVIDERS_DIR/uploads-local.sh" snapshot "$UPLOADS_ROOT" "$UPLOADS_POINTER" "$UPLOADS_STAGING_PARENT" "$uploads_snap"; then
    common::fail "uploads snapshot に失敗しました"
  fi
  UPLOADS_SNAPSHOT_ID=$(jq -r '.snapshot_id // empty' "$uploads_snap")
  UPLOADS_STAGING_DIR=$(jq -r '.staging_dir // empty' "$uploads_snap")
  # snapshot した pointer の名前と版（metadata.json と index の uploads_pointer に記録する。RB-W 追加修正 C）
  UPLOADS_POINTER_NAME=$(jq -r '.pointer // empty' "$uploads_snap")
  UPLOADS_POINTER_TARGET=$(jq -r '.pointer_target // empty' "$uploads_snap")
  [[ -n "$UPLOADS_SNAPSHOT_ID" ]] || common::fail "uploads snapshot ID を取得できません"
  [[ -n "$UPLOADS_POINTER_NAME" && -n "$UPLOADS_POINTER_TARGET" ]] || common::fail "uploads の pointer と版を取得できません"

  # R2 P2-06: 主要 table count は静止区間内（解除前）に採取する
  # （dump と同じ静止区間の値として metadata に載せる。失敗しても metadata は書ける）
  if [[ -n "${CRITICAL_TABLES:-}" ]]; then
    local counts='{}'
    local t=""
    local -a tarr=()
    IFS=',' read -ra tarr <<< "$CRITICAL_TABLES"
    for t in "${tarr[@]}"; do
      t=$(echo "$t" | xargs)
      [[ -n "$t" ]] || continue
      local esc=${t//\`/}
      local c=""
      if c=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
          --execute "SELECT COUNT(*) FROM \`$esc\`;" "$MYSQL_DATABASE" 2>/dev/null); then
        c=$(echo "$c" | tail -n1)
        if common::is_int "$c"; then
          counts=$(printf '%s' "$counts" | jq -c --arg t "$t" --argjson c "$c" '.[$t] = $c')
        fi
      fi
    done
    METADATA_TABLE_COUNTS_JSON=$counts
  fi

  # A-NF09-5: 主要 table の順序付き内容ハッシュも静止区間内に採取する。count と違い、指定した table を
  # 1 つでも計算できなければ full を VALID にしない（内容照合の根拠を黙って欠落させない）。
  METADATA_CONTENT_HASHES_JSON='{}'
  if [[ -n "${CONTENT_HASH_TABLES:-}" ]]; then
    METADATA_CONTENT_HASHES_JSON=$(content_hash::collect "$CONTENT_HASH_TABLES" \
        "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" "$MYSQL_DATABASE") \
      || common::fail "主要 table の内容ハッシュを計算できません（CONTENT_HASH_TABLES）"
  fi
  # G-04（RB-W W-1）: schema fingerprint も同じ静止区間内に採取する。設定によらず常に記録し、計算できなければ
  # full を VALID にしない（復元検証は記録の無い backup を FAIL にする）。
  METADATA_SCHEMA_FINGERPRINT_JSON=$(schema_fingerprint::compute \
      "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" "$MYSQL_DATABASE") \
    || common::fail "schema fingerprint を計算できません（lib/schema-fingerprint.sh）"

  # 静止解除
  if ! quiesce::release; then
    echo "[backup-full] 重大: 静止解除に失敗しました" >&2
    QUIESCED=0
    common::fail "静止解除失敗（incident 扱い）"
  fi
  QUIESCED=0

  # staging: uploads staging を work 配下の payload へ移す
  local payload="$work/payload"
  mkdir -p "$payload/uploads" "$payload/db"
  cp -a "$UPLOADS_STAGING_DIR"/. "$payload/uploads/"
  cp "$uploads_snap" "$payload/uploads-snapshot.json"
  cp "$dump_file" "$payload/db/database.sql"

  # metadata（dump と同じ静止区間の整合時刻。counts は静止区間内に採取済み）
  DATABASE_FINGERPRINT=$(metadata::db_fingerprint "${ENVIRONMENT:-unknown}" "$MYSQL_DATABASE")
  SOURCE_LINEAGE=$(metadata::lineage "$SOURCE_SERVER_UUID" "$DATABASE_FINGERPRINT")
  KIND=full
  STATUS=PENDING
  UPLOADS_SNAPSHOT_ID="$UPLOADS_SNAPSHOT_ID"
  metadata::build "$work" "$payload/metadata.json"
  # metadata を table counts 込みで再生成
  if [[ -n "${METADATA_TABLE_COUNTS_JSON:-}" ]]; then
    metadata::build "$work" "$payload/metadata.json"
  fi

  # manifest（metadata→payload close→manifest→manifest.sha256→read-only）
  manifest::build "$payload" || common::fail "manifest 生成に失敗しました"
  manifest::finalize "$payload" || common::fail "manifest 固定に失敗しました"

  # restic backup（status は verify 前なので pending で登録）
  local snap=""
  if [[ -n "${DEBUG_RESTIC_RAW:-}" ]]; then
    snap=$("$RESTIC_BIN" backup "$payload" --tag "kind=full" --tag "date=$stamp" \
        --tag "status=pending" --json 2>> "$work/restic.log" | tee "$BACKUP_WORK_DIR/restic-raw-debug.log" \
        | jq -rs 'map(select(.message_type == "summary"))[0].snapshot_id // empty')
    echo "DEBUG: extracted snap=$snap" >&2
    echo "DEBUG: raw tail: $(tail -n1 "$BACKUP_WORK_DIR/restic-raw-debug.log" 2>/dev/null)" >&2
  else
    if ! snap=$("$RESTIC_BIN" backup "$payload" --tag "kind=full" --tag "date=$stamp" \
        --tag "status=pending" --json 2>> "$work/restic.log" \
        | jq -rs 'map(select(.message_type == "summary"))[0].snapshot_id // empty'); then
      common::fail "restic backup に失敗しました: $(common::redact < "$work/restic.log")"
    fi
    [[ -n "$snap" ]] || common::fail "restic snapshot ID を取得できません"
  fi

  # restore verify + manifest hash 照合（verify 済みだけ status=valid に昇格）
  local verify_dir="$work/verify"
  mkdir -p "$verify_dir"
  if ! "$RESTIC_BIN" restore "$snap" --target "$verify_dir" --verify >> "$work/restic.log" 2>&1; then
    common::fail "restic restore verify に失敗しました"
  fi
  # restore された payload の root を manifest.json の位置から特定する
  # （restic は絶対 path を preserve して復元するため）
  local restored_root=""
  restored_root=$(find "$verify_dir" -name manifest.json -print -quit 2>/dev/null | xargs -r dirname)
  [[ -n "$restored_root" && -f "$restored_root/manifest.json" ]] \
    || common::fail "restore 結果に manifest.json が見つかりません"
  manifest::verify "$restored_root" "$restored_root/manifest.json" \
    || common::fail "restore 後の manifest 検証に失敗しました"

  if ! "$RESTIC_BIN" tag --add "status=valid" --remove "status=pending" "$snap" >> "$work/restic.log" 2>&1; then
    common::fail "snapshot の status タグ更新に失敗しました"
  fi
  # restic 0.17 の tag は新 id の snapshot を作るため、id を再解決する
  snap=$(restic::resolve_snapshot_by_tag "$RESTIC_BIN" "date=$stamp")
  [[ -n "$snap" ]] || common::fail "tag 更新後の snapshot ID を解決できません"

  # index 登録（補助。正は restic snapshot の status=valid タグ + 内部 metadata）
  local index_dir="$BACKUP_WORK_DIR/index"
  mkdir -p "$index_dir"
  jq --arg snap "$snap" --arg status "VALID" --arg uploaded_at_utc "$(common::now_utc)" \
    '.status = $status | .restic_snapshot_id = $snap | .uploaded_at_utc = $uploaded_at_utc' \
    "$payload/metadata.json" > "$index_dir/full-$stamp.json"

  # R1 P1-06: archiver の初回起点となる full coordinate を書き出す
  if [[ -z "${FULL_COORDINATE_FILE:-}" ]]; then
    common::fail "FULL_COORDINATE_FILE が未設定です（archiver の初回起点を失います）"
  fi
  if [[ -n "$BINLOG_START_FILE" ]]; then
    printf '%s\n' "$BINLOG_START_FILE" > "$FULL_COORDINATE_FILE.tmp"
    mv "$FULL_COORDINATE_FILE.tmp" "$FULL_COORDINATE_FILE"
    chmod 600 "$FULL_COORDINATE_FILE"
  else
    common::fail "full の binlog start coordinate を取得できません"
  fi

  local result
  result=$(jq -n \
    --arg kind "full" \
    --arg status "VALID" \
    --arg consistency_time_utc "$CONSISTENCY_TIME_UTC" \
    --arg restic_snapshot_id "$snap" \
    --arg binlog_file "$BINLOG_START_FILE" \
    --arg binlog_position "$BINLOG_START_POSITION" \
    --arg manifest_sha "$(cat "$payload/manifest.sha256")" \
    --arg uploads_snapshot_id "$UPLOADS_SNAPSHOT_ID" \
    --arg uploads_pointer "$UPLOADS_POINTER_NAME" \
    --arg uploads_version "$UPLOADS_POINTER_TARGET" \
    --arg quiesce_started "$(quiesce::status_json | jq -r '.started_at_utc // ""')" \
    --arg quiesce_released "$(quiesce::status_json | jq -r '.released_at_utc // ""')" \
    '{kind: $kind, status: $status, consistency_time_utc: $consistency_time_utc,
      restic_snapshot_id: $restic_snapshot_id, binlog_start: {file: $binlog_file, position: ($binlog_position | tonumber?)},
      manifest_sha256: $manifest_sha, uploads_snapshot_id: $uploads_snapshot_id,
      uploads_pointer: {name: $uploads_pointer, target: $uploads_version},
      quiesce: {started_at_utc: $quiesce_started, released_at_utc: $quiesce_released}}')
  printf '%s\n' "$result"
  return 0
}

case "${1:---run}" in
  --run) main ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
