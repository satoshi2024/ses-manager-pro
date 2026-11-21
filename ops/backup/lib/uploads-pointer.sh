#!/usr/bin/env bash
# ============================================================
# uploads の pointer（current symlink）の解決（RB-W 追加修正 C。2026-11-18 利用者決定: 方式 A）
#
# production の uploads は、uploads の根（app の container では /app/uploads）の下に版の directory（v0, v1, …）と、
# 相対・1 段の symlink current -> v<n> を置く。app は UPLOAD_BASE_PATH=/app/uploads/current を使う。
# backup は pointer を find や host の mount に辿らせない。uploads の根を :ro で bind した container の中で、
# readlink（-f を付けない）で pointer の値だけを読み、1 段の名前だけを受け付けて <根>/<名前> を snapshot の src にする
# （find は起点の symlink を辿らないため、pointer そのものを src にすると空の snapshot になる）。
#
# 受け付ける条件（どれか 1 つでも満たさなければ失敗。fail-closed）:
#   - 根: 存在する実 directory（symlink は拒否）
#   - pointer の名前と、pointer が指す名前: ^[A-Za-z0-9][A-Za-z0-9._-]*$（絶対 path・/ を含むもの・. で始まるもの
#     （. と .. を含む）・空は拒否）
#   - pointer: symlink であること（実 directory に置き換わると版の切替えが効かなくなるため、実 directory・file・無いは拒否）
#   - 指す先: <根>/<名前> が存在する実 directory（symlink・file・無い（指す先の無い pointer）は拒否）
# ============================================================

UPLOADS_POINTER_NAME_RE='^[A-Za-z0-9][A-Za-z0-9._-]*$'

# 1 段の名前か
uploads_pointer::valid_name() { # <name>
  [[ "$1" =~ $UPLOADS_POINTER_NAME_RE ]]
}

# pointer を解決し、指す版の名前を標準出力に 1 行で出す。失敗は理由を標準エラーに出して 1
uploads_pointer::resolve() { # <uploads の根> <pointer の名前>
  local root=$1 pointer=$2 link target
  if [[ -z "$root" || -L "$root" || ! -d "$root" ]]; then
    echo "uploads-pointer: uploads の根が実 directory ではありません（無い・symlink・directory でない）: $root" >&2
    return 1
  fi
  if ! uploads_pointer::valid_name "$pointer"; then
    echo "uploads-pointer: pointer の名前が 1 段の名前ではありません: $pointer" >&2
    return 1
  fi
  link="$root/$pointer"
  if [[ ! -L "$link" ]]; then
    if [[ -e "$link" ]]; then
      echo "uploads-pointer: $pointer が symlink ではありません（実 directory 等に置き換わっていると版の切替えが効かない）: $link" >&2
    else
      echo "uploads-pointer: $pointer がありません（app の初回の起動前に v0 と $pointer -> v0 を作る）: $link" >&2
    fi
    return 1
  fi
  if ! target=$(readlink -- "$link"); then
    echo "uploads-pointer: $pointer を読めません: $link" >&2
    return 1
  fi
  if ! uploads_pointer::valid_name "$target"; then
    echo "uploads-pointer: $pointer の指す先が 1 段の名前ではありません（絶対 path・/・. で始まる名前は受け付けない）: $target" >&2
    return 1
  fi
  if [[ -L "$root/$target" || ! -d "$root/$target" ]]; then
    echo "uploads-pointer: $pointer の指す先 $target が実 directory ではありません（無い・symlink・file）" >&2
    return 1
  fi
  printf '%s\n' "$target"
}

# backup-full / create-checkpoint の uploads の設定を検査する。廃止した UPLOADS_DIR が残っていれば失敗する
# （旧い設定のまま pointer を介さずに版の directory や根を直接 backup しないため）。UPLOADS_POINTER の既定は current。
uploads_pointer::check_env() {
  if [[ -n "${UPLOADS_DIR:-}" ]]; then
    echo "uploads-pointer: UPLOADS_DIR は廃止しました。UPLOADS_ROOT（uploads の根）と UPLOADS_POINTER（既定 current）を設定してください（README「設定」）" >&2
    return 1
  fi
  if [[ -z "${UPLOADS_ROOT:-}" ]]; then
    echo "uploads-pointer: UPLOADS_ROOT が未設定です" >&2
    return 1
  fi
  UPLOADS_POINTER=${UPLOADS_POINTER:-current}
  if ! uploads_pointer::valid_name "$UPLOADS_POINTER"; then
    echo "uploads-pointer: UPLOADS_POINTER が 1 段の名前ではありません: $UPLOADS_POINTER" >&2
    return 1
  fi
  return 0
}
