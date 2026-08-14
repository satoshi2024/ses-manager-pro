#!/usr/bin/env bash
# ============================================================
# HFP-03-004 閉じた binlog の immutable snapshot（BL-004/008 対応）
# - RAW_DIR（archiver の work area）のうち、source の File_size と一致し
#   checksum 検証済みの closed file だけを IMMUTABLE_DIR へ rename する。
# - active（書込み中）file は repository 対象にしない。
# - 新しい immutable file を restic snapshot 化し binlog-index.json へ
#   記録する（restore plan の依存解決に使う）。
#
# usage: snapshot-binlog.sh [--help]
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
. "$LIB_DIR/common.sh"
. "$LIB_DIR/mysql-options.sh"
. "$LIB_DIR/repository-lock.sh"
. "$LIB_DIR/binlog.sh"

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
MYSQLBINLOG_BIN=${MYSQLBINLOG_BIN:-mysqlbinlog}
RESTIC_BIN=${RESTIC_BIN:-restic}

BINLOG_RAW_DIR=${BINLOG_RAW_DIR:?BINLOG_RAW_DIR is required}
BINLOG_IMMUTABLE_DIR=${BINLOG_IMMUTABLE_DIR:?BINLOG_IMMUTABLE_DIR is required}
BINLOG_INDEX=${BINLOG_INDEX:-$BINLOG_IMMUTABLE_DIR/binlog-index.json}

# source の現在の binlog file と size 一覧（SHOW BINARY LOGS）
binlog_snap::source_logs() { # -> "file<TAB>size" 1行ずつ
  if ! mysql_options::init; then
    echo "snapshot-binlog: mysql option file を作成できませんでした" >&2
    return 1
  fi
  "$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SHOW BINARY LOGS;" 2>/dev/null || {
    echo "snapshot-binlog: SHOW BINARY LOGS に失敗しました" >&2
    return 1
  }
}

main() {
  common::require_env BACKUP_REPOSITORY
  common::require_env RESTIC_PASSWORD_FILE
  common::require_env MYSQL_HOST
  common::require_env MYSQL_USER
  common::require_env MYSQL_PASSWORD_FILE
  MYSQL_PORT=${MYSQL_PORT:-3306}

  export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE
  mkdir -p "$BINLOG_RAW_DIR" "$BINLOG_IMMUTABLE_DIR"
  restic::ensure_repository "$RESTIC_BIN" "$BINLOG_IMMUTABLE_DIR/restic.log" \
    || common::fail "restic repository を準備できません"

  # 既定は exclusive lock。create-checkpoint から呼ばれる場合（lock 保持済み）は
  # --no-lock でスキップする（自己デッドロック防止）
  if [[ "${SKIP_LOCK:-}" != "1" ]]; then
    if ! repository_lock::acquire exclusive "${BACKUP_LOCK_TIMEOUT:-120}" "snapshot-binlog"; then
      common::fail "repository lock を取得できませんでした"
    fi
  fi

  local src_logs=""
  src_logs=$(binlog_snap::source_logs) || common::fail "source の binlog 一覧を取得できません"

  # source の最新 file は active（対象外）。それ以外を closed 候補とする
  local last_file=""
  last_file=$(printf '%s\n' "$src_logs" | awk 'END{print $1}')
  [[ -n "$last_file" ]] || common::fail "source に binlog がありません"

  local moved=0
  local f=""
  local size=0
  local rel=""
  local snap=""
  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)

  # RAW_DIR の file を suffix 順に処理
  local raw_file=""
  while IFS= read -r -d '' raw_file; do
    rel=$(basename "$raw_file")
    [[ "$rel" == binlog.* ]] || continue
    [[ "$rel" != "$last_file" ]] || continue   # active file は除外

    # source の File_size と一致（truncated 拒否）
    size=$(printf '%s\n' "$src_logs" | awk -v f="$rel" '$1 == f {print $2; exit}')
    if [[ -z "$size" ]]; then
      echo "snapshot-binlog: source に無い file（別 lineage?）: $rel" >&2
      continue
    fi
    if ! binlog::size_ok "$raw_file" "$size"; then
      echo "snapshot-binlog: truncated のため保留: $rel" >&2
      continue
    fi
    if ! binlog::verify_checksum "$raw_file"; then
      echo "snapshot-binlog: checksum 検証失敗のため保留: $rel" >&2
      continue
    fi

    # immutable 領域へ rename（同一 fs 前提。既存を上書きしない）
    local dest="$BINLOG_IMMUTABLE_DIR/$rel"
    if [[ -f "$dest" ]]; then
      continue
    fi
    if ! mv -n "$raw_file" "$dest"; then
      echo "snapshot-binlog: rename に失敗しました: $rel" >&2
      continue
    fi
    chmod 400 "$dest"

    # 1 file = 1 snapshot（依存解決を単純化）
    if ! snap=$("$RESTIC_BIN" backup "$dest" --tag "kind=binlog" --tag "file=$rel" \
        --tag "status=pending" --json 2>> "$BINLOG_IMMUTABLE_DIR/restic.log" \
        | jq -rs 'map(select(.message_type == "summary"))[0].snapshot_id // empty'); then
      echo "snapshot-binlog: restic backup 失敗: $rel" >&2
      continue
    fi
    if [[ -z "$snap" ]]; then
      echo "snapshot-binlog: snapshot ID を取得できません: $rel" >&2
      continue
    fi
    "$RESTIC_BIN" tag --add "status=valid" --remove "status=pending" "$snap" \
      >> "$BINLOG_IMMUTABLE_DIR/restic.log" 2>&1 || true

    # binlog-index.json に記録（追記）
    local index="[]"
    [[ -f "$BINLOG_INDEX" ]] && index=$(cat "$BINLOG_INDEX")
    printf '%s' "$index" | jq -c \
      --arg file "$rel" \
      --arg snapshot_id "$snap" \
      --arg size "$size" \
      --arg sha256 "$(common::sha256_file "$dest")" \
      --arg archived_at_utc "$(common::now_utc)" \
      '. + [{file: $file, snapshot_id: $snapshot_id, size: ($size|tonumber), sha256: $sha256, archived_at_utc: $archived_at_utc}]' \
      > "$BINLOG_INDEX.tmp" && mv "$BINLOG_INDEX.tmp" "$BINLOG_INDEX"
    chmod 600 "$BINLOG_INDEX"
    moved=$((moved + 1))
  done < <(find "$BINLOG_RAW_DIR" -maxdepth 1 -type f -name 'binlog.*' -print0 | sort -z)

  jq -n \
    --arg kind "binlog-snapshot" \
    --arg status "OK" \
    --arg moved "$moved" \
    --arg last_closed_file "$(ls "$BINLOG_IMMUTABLE_DIR"/binlog.* 2>/dev/null | xargs -r basename | sort | tail -n1)" \
    --arg source_current_file "$last_file" \
    '{kind: $kind, status: $status, moved_files: ($moved|tonumber),
      last_closed_file: $last_closed_file, source_current_file: $source_current_file}'
  return 0
}

case "${1:---run}" in
  --run) main ;;
  --no-lock) SKIP_LOCK=1; main ;;
  --help|-h) cat <<'EOF'
Usage: snapshot-binlog.sh [--run|--no-lock|--help]
--no-lock: 呼び出し元（create-checkpoint）が exclusive lock を保持している場合に使う。
環境変数: BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE, MYSQL_HOST, MYSQL_USER,
MYSQL_PASSWORD_FILE, BINLOG_RAW_DIR, BINLOG_IMMUTABLE_DIR, BINLOG_INDEX
EOF
    exit 0 ;;
  *) echo "Usage: snapshot-binlog.sh [--run|--no-lock|--help]" >&2; exit 2 ;;
esac
