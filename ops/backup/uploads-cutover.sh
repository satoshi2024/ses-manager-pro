#!/usr/bin/env bash
# ============================================================
# uploads の版の切替え（RB-W 追加修正 C。2026-11-18 利用者決定: 方式 A `current` symlink、backup と app は同じ uid）
#
# production の uploads は、uploads の根（app の container では /app/uploads）の下に版の directory（v0, v1, …）と
# 相対・1 段の symlink current -> v<n> を置き、app は UPLOAD_BASE_PATH=/app/uploads/current を使う
# （lib/uploads-pointer.sh、README「uploads の配置と mount」）。復元した uploads はこの script で本番へ出す。
#
# usage:
#   uploads-cutover.sh stage  --from <dir> --root <uploads の根> --version <新しい版> [--pointer <名前>]
#   uploads-cutover.sh switch --root <uploads の根> --to <切替え先の版> --expect <今の版> [--pointer <名前>]
#   uploads-cutover.sh status --root <uploads の根> [--pointer <名前>]
#   （--pointer の既定は current）
#
# stage（restore.sh・validate-restore.sh の後。今の版を使う app は動いていてよい）:
#   - <dir>（restore.sh の uploads_ready。validate-restore.sh が hash と参照を照合したもの）を、新しい版の directory
#     <根>/<新しい版> として複製する。今の版と pointer は変えない。既にある名前（今の版を含む）は拒否する（上書きしない）。
#   - 複製は lib/safe-extract.sh の規則（symlink・hardlink・device・FIFO・socket・path traversal を拒否）で、<根> の下の
#     一時 directory（.stage-<新しい版>.XXXXXX。. で始まるため版の名前にも pointer の名前にもならない）へ行う。
#     全 entry の種類・相対 path と file の SHA-256 が複製元と一致してから、1 回の rename（mv -T）で <根>/<新しい版> にする。
#     途中で失敗したら一時 directory を消し、<根>/<新しい版> は現れない（作りかけの版へ switch できない）。
# switch（app の停止中。DB の cutover と同じ change の中で行う）:
#   - pointer が --expect の版を指していること（lib/uploads-pointer.sh の規則で解決できること）と、切替え先が実 directory で
#     あることを確かめ、切替え先を指す相対・1 段の一時 symlink（.<pointer>.switch-<pid>）を ln -sT で作り（既にあれば失敗。
#     上書きしない）、mv -T（rename(2) 1 回）で pointer と置き換える。pointer は切替えの前も後も必ずどちらかの版を指し、
#     無い瞬間・壊れた瞬間は無い。-T を付けないと、mv は directory を指す pointer の中（今の版の中）へ一時 symlink を
#     移してしまうため、必ず付ける。
#   - rollback は同じ switch を逆向きに実行する（--to <旧版> --expect <新版>）。旧版の directory は消さない。
#     切替えが成功すると、そのまま使える rollback の command を出力する。
# status: pointer が指す版と、根の下の版の directory、残った一時 directory を出力する（切替えの前後の確認用）。
#
# 出力は key=value の行（標準出力）。失敗は理由を標準エラーに出して 1、引数の誤りは 2。
# 前提: GNU coreutils の ln -T・mv -T（tool image）。<根> は app と同じ uid で書ける（restore 主体が uploads の access point を
# 読み書きで mount する）。umask 077 のため新しい directory は 0700 になるが、app と同じ uid のため app は読み書きできる
# （README「uid の模型」）。uid を分ける場合は mode の扱いを見直す。
# 残る窓: stage の直前の確認と rename の間に、同じ名前の「空の directory」か symlink を作られると rename がそれを置き換える
# （中身のある directory・file なら rename は失敗する）。版の名前を使うのは復元の operator だけのため受け入れる。
# switch の --expect の確認と rename の間に別の operator が pointer を変えた場合も、後の rename が勝つ（同じ change の中で
# 1 名が実行する。切替え後に出力の current と status で確かめる）。
# ============================================================
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB_DIR="$SCRIPT_DIR/lib"
[[ -d "$LIB_DIR" ]] || LIB_DIR=/usr/local/lib/ses-backup
# shellcheck disable=SC1091
. "$LIB_DIR/common.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/safe-extract.sh"
# shellcheck disable=SC1091
. "$LIB_DIR/uploads-pointer.sh"

uploads_cutover::fail() { echo "[uploads-cutover] ERROR: $*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage:
  uploads-cutover.sh stage  --from <dir> --root <uploads の根> --version <新しい版> [--pointer current]
  uploads-cutover.sh switch --root <uploads の根> --to <切替え先の版> --expect <今の版> [--pointer current]
  uploads-cutover.sh status --root <uploads の根> [--pointer current]
stage は復元した uploads を新しい版の directory として複製する（今の版と pointer は変えない）。
switch は pointer を 1 回の rename で切り替える（app の停止中）。rollback は --to と --expect を入れ替えた switch。
EOF
}

# <根> の検査（実 directory。symlink は拒否）
uploads_cutover::require_root() { # <root>
  local root=$1
  if [[ -z "$root" || -L "$root" || ! -d "$root" ]]; then
    uploads_cutover::fail "uploads の根が実 directory ではありません（無い・symlink・directory でない）: $root"
  fi
}

# 版の名前の検査（lib/uploads-pointer.sh と同じ 1 段の名前。pointer の名前とは別）
uploads_cutover::require_version_name() { # <label> <name> <pointer>
  uploads_pointer::valid_name "$2" \
    || uploads_cutover::fail "$1 が 1 段の名前ではありません（^[A-Za-z0-9][A-Za-z0-9._-]*\$）: $2"
  [[ "$2" != "$3" ]] || uploads_cutover::fail "$1 に pointer の名前（$3）は使えません"
}

uploads_cutover::require_pointer_name() { # <pointer>
  uploads_pointer::valid_name "$1" || uploads_cutover::fail "pointer の名前が 1 段の名前ではありません: $1"
}

# directory の中身の一覧（種類・相対 path・file の SHA-256。C locale の byte 順）。複製の一致の確認に使う。
# 相対 path は printf %q で 1 行にする（改行を含む名前でも行がずれない）。
uploads_cutover::inventory() { # <dir>
  local dir=$1 p rel sum
  while IFS= read -r -d '' p; do
    rel=${p#"$dir"/}
    if [[ -d "$p" && ! -L "$p" ]]; then
      printf 'd\t%q\n' "$rel"
    else
      sum=$(sha256sum < "$p") || return 1
      printf 'f\t%q\t%s\n' "$rel" "${sum%% *}"
    fi
  done < <(find "$dir" -mindepth 1 -print0 | LC_ALL=C sort -z)
}

uploads_cutover::stage() { # <from> <root> <version> <pointer>
  local from=$1 root=$2 version=$3 pointer=$4 tmp handler want got files current
  if [[ -z "$from" || -L "$from" || ! -d "$from" ]]; then
    uploads_cutover::fail "複製元が実 directory ではありません（無い・symlink・directory でない）: $from"
  fi
  uploads_cutover::require_root "$root"
  uploads_cutover::require_pointer_name "$pointer"
  uploads_cutover::require_version_name "新しい版の名前（--version）" "$version" "$pointer"
  if [[ -e "$root/$version" || -L "$root/$version" ]]; then
    uploads_cutover::fail "版 $version は既にあります（上書きしない。今の版を含め既存の版は変えない）: $root/$version"
  fi
  safe_extract::check_entries "$from" \
    || uploads_cutover::fail "複製元に安全でない entry があります（symlink・hardlink・特殊 file・path traversal）: $from"

  tmp=$(mktemp -d "$root/.stage-$version.XXXXXX") || uploads_cutover::fail "一時 directory を作れません: $root"
  # 失敗したら一時 directory を消す（登録時に path を展開する。成功したら common::trap_remove で外す）
  handler="rm -rf -- $(printf '%q' "$tmp")"
  common::trap_add "$handler"
  safe_extract::copy "$from" "$tmp" || uploads_cutover::fail "複製に失敗しました（$root/$version は作っていない）"
  want=$(uploads_cutover::inventory "$from") || uploads_cutover::fail "複製元の一覧を作れません"
  got=$(uploads_cutover::inventory "$tmp") || uploads_cutover::fail "複製した内容の一覧を作れません"
  [[ "$want" == "$got" ]] \
    || uploads_cutover::fail "複製した内容が複製元と一致しません（$root/$version は作っていない）"
  if [[ -e "$root/$version" || -L "$root/$version" ]]; then
    uploads_cutover::fail "版 $version が複製の間に作られました（上書きしない）: $root/$version"
  fi
  mv -T -- "$tmp" "$root/$version" || uploads_cutover::fail "一時 directory を版 $version にできません"
  common::trap_remove "$handler"

  files=$(awk -F'\t' '$1 == "f" { n++ } END { print n + 0 }' <<< "$got")
  current=$(uploads_pointer::resolve "$root" "$pointer" 2> /dev/null) || current=""
  printf 'stage=ok\nroot=%s\nversion=%s\nfiles=%s\npointer=%s\npointer_target=%s\n' \
    "$root" "$version" "$files" "$pointer" "$current"
}

uploads_cutover::switch() { # <root> <to> <expect> <pointer>
  local root=$1 to=$2 expect=$3 pointer=$4 current tmp handler after
  uploads_cutover::require_root "$root"
  uploads_cutover::require_pointer_name "$pointer"
  uploads_cutover::require_version_name "切替え先の版（--to）" "$to" "$pointer"
  uploads_cutover::require_version_name "今の版（--expect）" "$expect" "$pointer"
  [[ "$to" != "$expect" ]] || uploads_cutover::fail "切替え先が今の版と同じです: $to"
  current=$(uploads_pointer::resolve "$root" "$pointer") \
    || uploads_cutover::fail "pointer を解決できないため切り替えません（理由は上）"
  [[ "$current" == "$expect" ]] \
    || uploads_cutover::fail "pointer は $current を指しています（--expect $expect と違うため切り替えない）"
  if [[ -L "$root/$to" || ! -d "$root/$to" ]]; then
    uploads_cutover::fail "切替え先の版 $to が実 directory ではありません（無い・symlink・file）"
  fi

  tmp="$root/.$pointer.switch-$$"
  # ln -T: 既に同じ名前があれば失敗する（directory の中に作らない・上書きしない）。後始末の handler は作れた後に登録する
  # （既にあった他人の file を消さない）
  ln -sT -- "$to" "$tmp" || uploads_cutover::fail "一時の symlink を作れません（既にある名前は上書きしない）: $tmp"
  handler="rm -f -- $(printf '%q' "$tmp")"
  common::trap_add "$handler"
  if [[ ! -L "$tmp" || "$(readlink -- "$tmp")" != "$to" ]]; then
    uploads_cutover::fail "一時の symlink が $to を指していません: $tmp"
  fi
  # mv -T: rename(2) 1 回で pointer を置き換える（-T が無いと pointer の指す directory の中へ移してしまう）
  mv -T -- "$tmp" "$root/$pointer" || uploads_cutover::fail "pointer を置き換えられません（pointer は $expect を指したまま）"
  common::trap_remove "$handler"

  after=$(uploads_pointer::resolve "$root" "$pointer") \
    || uploads_cutover::fail "切替え後の pointer を解決できません。直ちに status で確かめ、必要なら rollback する"
  [[ "$after" == "$to" ]] || uploads_cutover::fail "切替え後の pointer が $after を指しています（期待 $to）"
  printf 'switch=ok\nroot=%s\npointer=%s\nprevious=%s\ncurrent=%s\n' "$root" "$pointer" "$expect" "$after"
  printf 'rollback=uploads-cutover.sh switch --root %q --to %q --expect %q --pointer %q\n' "$root" "$expect" "$to" "$pointer"
}

uploads_cutover::status() { # <root> <pointer>（読むだけ。根の下に何も書かない）
  local root=$1 pointer=$2 target="" err="" out rc=0 d name
  local -a versions=() leftovers=()
  uploads_cutover::require_root "$root"
  uploads_cutover::require_pointer_name "$pointer"
  # resolve は成功時に版の名前だけを、失敗時に理由だけを出す
  if out=$(uploads_pointer::resolve "$root" "$pointer" 2>&1); then
    target=$out
  else
    err=$out
    rc=1
  fi
  for d in "$root"/*; do
    name=${d##*/}
    if [[ "$name" != "$pointer" && ! -L "$d" && -d "$d" ]] && uploads_pointer::valid_name "$name"; then
      versions+=("$name")
    fi
  done
  for d in "$root"/.stage-* "$root"/."$pointer".switch-*; do
    if [[ -e "$d" || -L "$d" ]]; then
      leftovers+=("${d##*/}")
    fi
  done
  printf 'root=%s\npointer=%s\ntarget=%s\nversions=%s\n' "$root" "$pointer" "$target" "${versions[*]}"
  (( ${#leftovers[@]} == 0 )) || printf 'leftover=%s\n' "${leftovers[*]}"
  [[ -z "$err" ]] || printf 'error=%s\n' "${err//$'\n'/ }"
  return "$rc"
}

main() {
  local cmd=${1:-} from="" root="" version="" to="" expect="" pointer=current
  [[ -n "$cmd" ]] || { usage >&2; exit 2; }
  shift
  while (($#)); do
    case "$1" in
      --from) from=${2-}; shift 2 || { usage >&2; exit 2; } ;;
      --root) root=${2-}; shift 2 || { usage >&2; exit 2; } ;;
      --version) version=${2-}; shift 2 || { usage >&2; exit 2; } ;;
      --to) to=${2-}; shift 2 || { usage >&2; exit 2; } ;;
      --expect) expect=${2-}; shift 2 || { usage >&2; exit 2; } ;;
      --pointer) pointer=${2-}; shift 2 || { usage >&2; exit 2; } ;;
      --help|-h) usage; exit 0 ;;
      *) echo "未知の引数: $1" >&2; usage >&2; exit 2 ;;
    esac
  done
  case "$cmd" in
    stage)
      [[ -n "$from" && -n "$root" && -n "$version" ]] || { usage >&2; exit 2; }
      uploads_cutover::stage "$from" "$root" "$version" "$pointer"
      ;;
    switch)
      [[ -n "$root" && -n "$to" && -n "$expect" ]] || { usage >&2; exit 2; }
      uploads_cutover::switch "$root" "$to" "$expect" "$pointer"
      ;;
    status)
      [[ -n "$root" ]] || { usage >&2; exit 2; }
      uploads_cutover::status "$root" "$pointer"
      ;;
    --help|-h) usage ;;
    *) echo "未知の subcommand: $cmd" >&2; usage >&2; exit 2 ;;
  esac
}

main "$@"
