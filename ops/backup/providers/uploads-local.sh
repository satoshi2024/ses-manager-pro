#!/usr/bin/env bash
# ============================================================
# uploads snapshot provider（ローカル volume 向け・version 管理された executable）
#
# usage:
#   uploads-local.sh snapshot <uploads_root> <pointer> <staging_parent> <out_json>
#
# - uploads の根（<uploads_root>。production では uploads を :ro で bind した /app/uploads）の pointer（既定 current）を
#   lib/uploads-pointer.sh の規則で解決し、指す版の directory（<uploads_root>/<版>）を src にする（RB-W 追加修正 C。
#   2026-11-18 利用者決定: 方式 A）。pointer そのものを find に渡さない（起点の symlink は辿られず空になる）。
#   pointer の名前と指す版を JSON（pointer・pointer_target）に記録する。
# - src は実 directory であること（symlink・directory でないものは拒否。RB-W X-1）。file が 1 つも無い版は
#   inventory を [] として成功する（初回 deploy 直後の空の uploads。RB-W X-1）。
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
. "$LIB_DIR/uploads-pointer.sh"

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
      # 改行・tab を含む名前は inventory（1 行 = 1 file、tab 区切り）を壊すため拒否する
      *$'\n'*|*$'\t'*) echo "uploads-local: 改行・tab を含む名前を拒否" >&2; return 1 ;;
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
  local uploads_root=$1 pointer=$2 staging_parent=$3 out_json=$4
  [[ -d "$staging_parent" ]] || { echo "uploads-local: staging_parent がありません: $staging_parent" >&2; return 1; }

  # pointer（current -> v<n>）を解決して版の directory を src にする（lib/uploads-pointer.sh）
  local version src_dir
  version=$(uploads_pointer::resolve "$uploads_root" "$pointer") || return 1
  src_dir="$uploads_root/$version"
  # RB-W X-1: src は実 directory に限る。find は起点の symlink を辿らないため、symlink を src にすると file が 0 件の
  # snapshot になり、空の inventory を受け付けるようにした今は黙って空の backup になる（resolve でも確かめている）。
  if [[ -L "$src_dir" || ! -d "$src_dir" ]]; then
    echo "uploads-local: src が実 directory ではありません（symlink・directory でない）: $src_dir" >&2
    return 1
  fi

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
  # したがって「同一 filesystem」は「同じ mount（superblock）」の意味になる。production は backup host が 1 つの access point を
  # 1 回だけ mount し、その下の uploads/ と backup-staging/ を別々に bind する（README「uploads の配置と mount」）。同じ EFS でも
  # access point ごとに別々に mount すると st_dev が違い、ここで拒否される（backup が止まる。fail-closed）。
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
  # 失敗したら staging を消す（成功時は末尾の common::trap_remove で外す）。EXIT trap は関数を抜けた後に評価されるため、
  # 関数の local 変数（$staging）を handler に書くと set -u で unbound variable になり、staging が残っていた（RB-W）。
  # 登録時に path を展開し、printf %q で空白・引用符・改行を含む path も 1 語のまま渡す。
  local staging_handler
  staging_handler="rm -rf -- $(printf '%q' "$staging")"
  common::trap_add "$staging_handler"

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

  # RB-W X-1: file が 0 件なら inventory は []（以前は空行から不正な JSON を作り jq が失敗し、空の uploads の初回 backup が
  # 必ず失敗していた）。JSON は jq で組み立てる（path の引用符なども正しく escape する。並びと形は以前と同じ）。
  local inv_json
  inv_json=$(jq -R -s 'split("\n") | map(select(length > 0) | split("\t")
      | {path: .[0], size: (.[1] | tonumber), sha256: .[2]})' <<< "$inv")
  local snapshot_id
  snapshot_id=$(printf '%s' "$inv_json" | sha256sum | awk '{print $1}')

  ended=$(common::now_utc)

  jq -n \
    --arg snapshot_id "$snapshot_id" \
    --arg started_at_utc "$started" \
    --arg ended_at_utc "$ended" \
    --arg uploads_root "$uploads_root" \
    --arg pointer "$pointer" \
    --arg pointer_target "$version" \
    --arg src_dir "$src_dir" \
    --arg staging_dir "$staging" \
    --argjson inventory "$inv_json" \
    '{snapshot_id: $snapshot_id, started_at_utc: $started_at_utc, ended_at_utc: $ended_at_utc,
      uploads_root: $uploads_root, pointer: $pointer, pointer_target: $pointer_target,
      src_dir: $src_dir, staging_dir: $staging_dir,
      file_count: ($inventory | length),
      inventory: $inventory}' > "$out_json"
  chmod 600 "$out_json"
  # 成功したので staging を消す handler だけを外す（RB-W P3-2。trap - EXIT は dispatcher だけを外し、配列に残った
  # handler が後の common::trap_add で戻ると成功した staging を消すため使わない）
  common::trap_remove "$staging_handler"
  return 0
}

cmd=${1:-}
case "$cmd" in
  snapshot)
    [[ $# -eq 5 ]] || { echo "Usage: uploads-local.sh snapshot <uploads_root> <pointer> <staging_parent> <out_json>" >&2; exit 2; }
    uploads_local::snapshot "$2" "$3" "$4" "$5"
    ;;
  *)
    echo "Usage: uploads-local.sh snapshot <uploads_root> <pointer> <staging_parent> <out_json>" >&2
    exit 2
    ;;
esac
