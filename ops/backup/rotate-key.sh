#!/usr/bin/env bash
# ============================================================
# HFP-03-010 repository key rotation（AC-008-03）
#
# 旧・新の両キーで restore verify を実施し、両方成功した場合だけ
# 新しいキーへ切替える。途中で失敗した場合は切替えず終了する
# （旧キーで運用継続し、障害が原因で切替えを誤らない）。
#
# usage: rotate-key.sh --new-key-file <file>
# 環境変数: RESTIC_BIN, RESTIC_PASSWORD_FILE（現在のキー）,
# INDEX_DIR（最新 checkpoint/full の特定に使用）
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
# shellcheck disable=SC1091
. "$LIB_DIR/common.sh"

RESTIC_BIN=${RESTIC_BIN:-restic}

usage() {
  cat <<'EOF'
Usage: rotate-key.sh --new-key-file <file>
環境変数: RESTIC_BIN, RESTIC_PASSWORD_FILE（旧キー）, INDEX_DIR,
BACKUP_WORK_DIR（verify 一時領域）
EOF
}

rotate::fail() { echo "[rotate-key] ERROR: $*" >&2; exit 1; }

rotate::restore_verify() { # password_file snapshot label
  local pwfile=$1 snap=$2 label=$3
  local work="${BACKUP_WORK_DIR:-/var/lib/ses-backup}/key-rotate-verify"
  rm -rf "$work"
  mkdir -p "$work"
  RESTIC_PASSWORD_FILE="$pwfile" "$RESTIC_BIN" restore "$snap" --target "$work" --verify \
    > /dev/null 2>&1
}

main() {
  common::require_env RESTIC_BIN
  common::require_env RESTIC_PASSWORD_FILE
  common::require_env INDEX_DIR
  [[ -f "$NEW_KEY_FILE" ]] || rotate::fail "new key file がありません: $NEW_KEY_FILE"
  [[ -n "${RESTIC_REPOSITORY:-}" ]] || rotate::fail "RESTIC_REPOSITORY が未設定です"

  local target=""
  target=$(find "$INDEX_DIR" -name 'checkpoint-*.json' -o -name 'full-*.json' 2>/dev/null \
    | sort | tail -1)
  [[ -n "$target" ]] || rotate::fail "対象の index がありません"
  local snap
  snap=$(jq -r '.restic_snapshot_id // empty' "$target" 2>/dev/null)
  [[ -n "$snap" ]] || rotate::fail "対象 snapshot を特定できません"

  # 1) 旧キーで restore verify（ベースライン）
  rotate::restore_verify "$RESTIC_PASSWORD_FILE" "$snap" old \
    || rotate::fail "旧キーでの restore verify に失敗しました（中断）"

  # 2) 新キーで restore verify（旧・新の両方が読めることを確認）
  rotate::restore_verify "$NEW_KEY_FILE" "$snap" new \
    || rotate::fail "新キーでの restore verify に失敗しました（切替えません）"

  # 3) R1 P2: repository に新キーを追加（restic key add。旧キーは残したまま）
  local add_log
  add_log="$TMPDIR/key-add.log"
  if ! RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    "$RESTIC_BIN" -r "$RESTIC_REPOSITORY" key add \
      --new-password-file "$NEW_KEY_FILE" > "$add_log" 2>&1; then
    rotate::fail "restic key add に失敗しました（切替えません）: $(common::redact < "$add_log")"
  fi

  # 4) 切替（atomic。新キーを一時 file に書いて rename）
  local tmp
  tmp="$RESTIC_PASSWORD_FILE.tmp-$$"
  cp "$NEW_KEY_FILE" "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$RESTIC_PASSWORD_FILE"

  # 5) 切替後に新キーで再 verify
  rotate::restore_verify "$RESTIC_PASSWORD_FILE" "$snap" post \
    || rotate::fail "切替後の restore verify に失敗しました"

  # 6) 旧キーを repository から除去（best-effort。失敗は alert 対象）
  local keys
  keys=$(RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
    "$RESTIC_BIN" -r "$RESTIC_REPOSITORY" key list --json 2>/dev/null || echo "[]")
  local old_id
  old_id=$(printf '%s' "$keys" | jq -r '.[] | select(.current == false) | .id' 2>/dev/null | head -1)
  if [[ -n "$old_id" ]]; then
    if ! RESTIC_PASSWORD_FILE="$RESTIC_PASSWORD_FILE" \
      "$RESTIC_BIN" -r "$RESTIC_REPOSITORY" key remove "$old_id" > /dev/null 2>&1; then
      echo "[rotate-key] WARNING: 旧キーの除去に失敗しました（id=$old_id）。次回 rotation 時に再試行してください" >&2
    fi
  fi

  jq -n --arg snap "$snap" --arg rotated_at_utc "$(common::now_utc)" \
    --arg removed_key_id "${old_id:-}" \
    '{state:"ROTATED", target_snapshot: $snap, removed_key_id: $removed_key_id, rotated_at_utc: $rotated_at_utc}'
  return 0
}

NEW_KEY_FILE=""
while (($#)); do
  case "$1" in
    --new-key-file) NEW_KEY_FILE=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$NEW_KEY_FILE" ]] || { usage >&2; exit 2; }
main
