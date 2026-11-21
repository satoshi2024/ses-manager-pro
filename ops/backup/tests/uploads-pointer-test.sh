#!/usr/bin/env bash
# uploads の pointer（current symlink）の解決（lib/uploads-pointer.sh。RB-W 追加修正 C。2026-11-18 利用者決定: 方式 A）
#
# - 受け付ける: 根が実 directory、pointer が symlink、指す先が 1 段の名前（^[A-Za-z0-9][A-Za-z0-9._-]*$）の実 directory
# - 拒否する（fail-closed）: 絶対 path・複数段・.. ・. で始まる名前・指す先が無い・指す先が file・指す先が symlink・
#   pointer が無い・pointer が実 directory・根が symlink・根が無い・pointer の名前が不正
# - check_env: 廃止した UPLOADS_DIR が残っていれば失敗、UPLOADS_ROOT が無ければ失敗、UPLOADS_POINTER の既定は current
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"

# shellcheck disable=SC1091
. "$LIB/uploads-pointer.sh"

# 根 $T/uploads に版 v0（file を 1 つ）を作る。pointer は各 case で作る（前の case の一時 directory は消す）
T=""
setup_root() {
  [[ -z "$T" ]] || rm -rf "$T"
  T=$(mktemp -d)
  mkdir -p "$T/uploads/v0"
  printf 'x\n' > "$T/uploads/v0/a.txt"
}

# 前提: この環境で symlink を作れる（作れないと ln -s が複製になり、試験の意味が変わる。Git for Windows では
# MSYS=winsymlinks:sys を設定する）
case_pointer_requires_symlink_capable_environment() {
  setup_root
  ln -s v0 "$T/uploads/current" 2>/dev/null
  if [[ -L "$T/uploads/current" && "$(readlink "$T/uploads/current")" == v0 ]]; then
    test_assert "前提: symlink を作れる環境で実行している"
  else
    test_fail "前提: symlink を作れる環境で実行している" "ln -s が symlink を作りません"
  fi
  rm -rf "$T"
  T=""
}

resolve() { # [pointer（既定 current。空文字はそのまま渡す）] [root]
  RES_OUT=$(uploads_pointer::resolve "${2-$T/uploads}" "${1-current}" 2>"$T/err")
  RES_RC=$?
  RES_ERR=$(cat "$T/err")
}

expect_rejected() { # <期待する文言> <label>
  assert_nonzero "$RES_RC" "拒否する: $2"
  assert_eq "" "$RES_OUT" "版の名前を出さない: $2"
  assert_contains "$RES_ERR" "$1" "理由を通知: $2"
}

case_pointer_accepts_single_segment_names() {
  local name
  for name in v0 v1.2_a-b 20261118T0900Z; do
    setup_root
    [[ "$name" == v0 ]] || mkdir -p "$T/uploads/$name"
    ln -s "$name" "$T/uploads/current"
    resolve
    assert_zero "$RES_RC" "1 段の名前を受け付ける: $name"
    assert_eq "$name" "$RES_OUT" "指す版の名前を出す: $name"
    rm -rf "$T"
  done
  # pointer の名前も既定以外を使える
  setup_root
  ln -s v0 "$T/uploads/live"
  resolve live
  assert_eq "v0" "$RES_OUT" "pointer の名前は引数で指定できる"
  rm -rf "$T"
}

case_pointer_rejects_unsafe_targets() {
  setup_root
  ln -s "$T/uploads/v0" "$T/uploads/current"
  resolve
  expect_rejected "1 段の名前ではありません" "絶対 path（実在する版でも拒否）"

  setup_root
  mkdir -p "$T/uploads/v0/sub"
  ln -s v0/sub "$T/uploads/current"
  resolve
  expect_rejected "1 段の名前ではありません" "複数段"

  setup_root
  ln -s .. "$T/uploads/current"
  resolve
  expect_rejected "1 段の名前ではありません" ".."

  setup_root
  ln -s ../outside "$T/uploads/current"
  resolve
  expect_rejected "1 段の名前ではありません" "../ で外へ出る"

  setup_root
  ln -s .hidden "$T/uploads/current"
  mkdir -p "$T/uploads/.hidden"
  resolve
  expect_rejected "1 段の名前ではありません" ". で始まる名前"

  setup_root
  ln -s v9 "$T/uploads/current"
  resolve
  expect_rejected "実 directory ではありません" "指す先が無い"

  setup_root
  printf 'x\n' > "$T/uploads/vfile"
  ln -s vfile "$T/uploads/current"
  resolve
  expect_rejected "実 directory ではありません" "指す先が file"

  setup_root
  ln -s v0 "$T/uploads/v1"
  ln -s v1 "$T/uploads/current"
  resolve
  expect_rejected "実 directory ではありません" "指す先が symlink（2 段の symlink）"
  rm -rf "$T"
}

case_pointer_rejects_missing_or_replaced_pointer_and_bad_root() {
  setup_root
  resolve
  expect_rejected "current がありません" "pointer が無い"

  setup_root
  mkdir -p "$T/uploads/current"
  resolve
  expect_rejected "symlink ではありません" "pointer が実 directory（app が作ってしまった）"

  setup_root
  printf 'x\n' > "$T/uploads/current"
  resolve
  expect_rejected "symlink ではありません" "pointer が file"

  setup_root
  ln -s v0 "$T/uploads/current"
  ln -s uploads "$T/uploads-link"
  resolve current "$T/uploads-link"
  expect_rejected "uploads の根が実 directory ではありません" "根が symlink"

  setup_root
  resolve current "$T/not-exists"
  expect_rejected "uploads の根が実 directory ではありません" "根が無い"

  setup_root
  ln -s v0 "$T/uploads/current"
  local bad
  for bad in "../current" "/current" "" ".current" "cur rent"; do
    resolve "$bad"
    expect_rejected "pointer の名前が 1 段の名前ではありません" "pointer の名前が不正: [$bad]"
  done
  rm -rf "$T"
}

case_pointer_check_env() {
  local out rc
  out=$(env -u UPLOADS_DIR -u UPLOADS_POINTER UPLOADS_ROOT=/app/uploads bash -c \
    ". '$LIB/uploads-pointer.sh'; uploads_pointer::check_env && printf '%s\n' \"\$UPLOADS_POINTER\"" 2>&1)
  rc=$?
  assert_zero "$rc" "UPLOADS_ROOT だけなら受け付ける"
  assert_eq "current" "$out" "UPLOADS_POINTER の既定は current"

  out=$(env UPLOADS_DIR=/app/uploads UPLOADS_ROOT=/app/uploads bash -c \
    ". '$LIB/uploads-pointer.sh'; uploads_pointer::check_env" 2>&1)
  rc=$?
  assert_nonzero "$rc" "廃止した UPLOADS_DIR が残っていれば失敗する"
  assert_contains "$out" "UPLOADS_DIR は廃止しました" "廃止を通知"

  out=$(env -u UPLOADS_DIR -u UPLOADS_ROOT bash -c ". '$LIB/uploads-pointer.sh'; uploads_pointer::check_env" 2>&1)
  rc=$?
  assert_nonzero "$rc" "UPLOADS_ROOT が無ければ失敗する"
  assert_contains "$out" "UPLOADS_ROOT が未設定です" "未設定を通知"

  out=$(env -u UPLOADS_DIR UPLOADS_ROOT=/app/uploads UPLOADS_POINTER=../current bash -c \
    ". '$LIB/uploads-pointer.sh'; uploads_pointer::check_env" 2>&1)
  rc=$?
  assert_nonzero "$rc" "UPLOADS_POINTER が 1 段の名前でなければ失敗する"
}

run_case case_pointer_requires_symlink_capable_environment
run_case case_pointer_accepts_single_segment_names
run_case case_pointer_rejects_unsafe_targets
run_case case_pointer_rejects_missing_or_replaced_pointer_and_bad_root
run_case case_pointer_check_env

test_summary "uploads の pointer（current symlink）の解決"
