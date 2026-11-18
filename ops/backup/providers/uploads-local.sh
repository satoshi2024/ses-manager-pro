#!/usr/bin/env bash
# ============================================================
# uploads snapshot provider（ローカル volume 向け・version 管理された executable）
#
# usage:
#   uploads-local.sh snapshot <src_dir> <staging_parent> <out_json>
#
# - 書込み静止中に、同一 filesystem の staging へ複製する（cp -a）。
# - symlink / hardlink / device / FIFO / socket / path traversal を拒否する
#   （RQ-002: "snapshot 中の symlink、hardlink、device、FIFO、socket、
#   path traversal を拒否する"）。
# - inventory（relpath / size / sha256）と snapshot ID を JSON で出力する。
#   snapshot ID = sha256(inventory JSON)。src は一切変更しない。
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/../lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
. "$LIB_DIR/common.sh"

uploads_local::check_safe() { # src_dir
  local src_dir=$1
  local entry=""
  # special file（symlink / pipe / socket / block / char）を拒否
  while IFS= read -r -d '' entry; do
    local rel=""
    rel=${entry#"$src_dir"/}
    echo "uploads-local: 非 regular file を拒否: $rel" >&2
    return 1
  done < <(find "$src_dir" -mindepth 1 \( -type l -o -type p -o -type s -o -type b -o -type c \) -print0 2>/dev/null)

  # 全 entry の path traversal 検査と hardlink 検査
  local p=""
  while IFS= read -r -d '' p; do
    local rel=""
    rel=${p#"$src_dir"/}
    case "$rel" in
      *'/'../*|../*|*'/..') echo "uploads-local: path traversal を拒否: $rel" >&2; return 1 ;;
    esac
    if [[ -f "$p" ]]; then
      local links=0
      links=$(stat -c %h "$p" 2>/dev/null || echo 1)
      if (( links > 1 )); then
        echo "uploads-local: hardlink を拒否: $rel (nlink=$links)" >&2
        return 1
      fi
    fi
  done < <(find "$src_dir" -mindepth 1 -print0 2>/dev/null)
  return 0
}

uploads_local::snapshot() {
  local src_dir=$1 staging_parent=$2 out_json=$3
  [[ -d "$src_dir" ]] || { echo "uploads-local: src がありません: $src_dir" >&2; return 1; }
  [[ -d "$staging_parent" ]] || { echo "uploads-local: staging_parent がありません: $staging_parent" >&2; return 1; }

  uploads_local::check_safe "$src_dir" || return 1

  local started ended
  started=$(common::now_utc)

  # 同一 filesystem 判定（cp -a は同一 fs の staging で行う）。
  # stat -c %d（st_dev。file が載っている filesystem の device 番号）で比べる。RB-W 以前の stat -f -c %d は、GNU coreutils の
  # --file-system の書式では「空き inode 数」で filesystem の識別子ではなかった（同じ fs でも 2 回の呼び出しの間に値が
  # 変わって拒否し得、別の fs でも値が同じ（0 や固定値を返す network fs・overlay）なら通していた）。stat -f -c %i
  # （statfs の f_fsid）も使わない: f_fsid の意味は fs の実装ごとに違い、0 のままの実装もあって network mount を区別
  # できない。st_dev は mount された filesystem（superblock）ごとに kernel が割り当てる値で、本番の実行環境（Linux の
  # container 内。uploads と staging は bind mount、または EFS（NFS）の mount）でも、同じ fs なら同じ値、別の mount の
  # 別の fs なら別の値になる。symlink は辿る（-L。実際に読み書きする場所を比べる）。btrfs の subvolume は同じ fs でも
  # st_dev が違うため拒否する（fail-closed）。Git Bash（MSYS）の st_dev は Windows の volume から作られる値で、
  # network drive での挙動は未確認（本番は Linux の container 内で動く）。値を取れない・数値でない場合も拒否する。
  local src_fs staging_fs
  src_fs=$(stat -L -c %d "$src_dir" 2>/dev/null) || src_fs=""
  staging_fs=$(stat -L -c %d "$staging_parent" 2>/dev/null) || staging_fs=""
  if [[ ! "$src_fs" =~ ^[0-9]+$ || ! "$staging_fs" =~ ^[0-9]+$ ]]; then
    echo "uploads-local: src / staging の filesystem（st_dev）を取得できません（src=${src_fs:-なし} staging=${staging_fs:-なし}）" >&2
    return 1
  fi
  if [[ "$src_fs" != "$staging_fs" ]]; then
    echo "uploads-local: staging が src と同一 filesystem ではありません（atomic snapshot 不可。st_dev: src=$src_fs staging=$staging_fs）" >&2
    return 1
  fi

  local stamp
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  local staging="$staging_parent/uploads-snap-$stamp-$$"
  mkdir -p "$staging"
  common::trap_add 'rm -rf "$staging"'

  # 通常 file のみ複製（検査済み）
  local entry=""
  local rel=""
  while IFS= read -r -d '' entry; do
    rel=${entry#"$src_dir"/}
    mkdir -p "$staging/$(dirname "$rel")"
    cp -a -- "$entry" "$staging/$rel"
  done < <(find "$src_dir" -type f -print0 2>/dev/null)

  # inventory
  local inv
  inv=$(cd "$staging" && find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do
    local rel size sha
    rel=${f#./}
    size=$(stat -c %s "$f")
    sha=$(sha256sum "$f" | awk '{print $1}')
    printf '%s\t%s\t%s\n' "$rel" "$size" "$sha"
  done)

  local inv_json
  inv_json=$(printf '%s\n' "$inv" | awk -F'\t' '{printf "{\"path\": \"%s\", \"size\": %s, \"sha256\": \"%s\"}\n", $1, $2, $3}' | jq -s .)
  local snapshot_id
  snapshot_id=$(printf '%s' "$inv_json" | sha256sum | awk '{print $1}')

  ended=$(common::now_utc)

  jq -n \
    --arg snapshot_id "$snapshot_id" \
    --arg started_at_utc "$started" \
    --arg ended_at_utc "$ended" \
    --arg src_dir "$src_dir" \
    --arg staging_dir "$staging" \
    --argjson inventory "$inv_json" \
    '{snapshot_id: $snapshot_id, started_at_utc: $started_at_utc, ended_at_utc: $ended_at_utc,
      src_dir: $src_dir, staging_dir: $staging_dir,
      file_count: ($inventory | length),
      inventory: $inventory}' > "$out_json"
  chmod 600 "$out_json"
  trap - EXIT
  return 0
}

cmd=${1:-}
case "$cmd" in
  snapshot)
    [[ $# -eq 4 ]] || { echo "Usage: uploads-local.sh snapshot <src_dir> <staging_parent> <out_json>" >&2; exit 2; }
    uploads_local::snapshot "$2" "$3" "$4"
    ;;
  *)
    echo "Usage: uploads-local.sh snapshot <src_dir> <staging_parent> <out_json>" >&2
    exit 2
    ;;
esac
