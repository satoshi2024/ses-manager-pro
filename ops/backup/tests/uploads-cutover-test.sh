#!/usr/bin/env bash
# uploads の版の切替え（uploads-cutover.sh。RB-W 追加修正 C。2026-11-18 利用者決定: 方式 A `current` symlink）
#
# - stage: 復元した uploads を新しい版の directory として複製し、今の版と pointer は変えない。既にある名前・不正な名前・
#   安全でない entry は拒否し、途中で失敗しても新しい版も一時 directory も残さない（作りかけの版へ switch できない）
# - switch: pointer が --expect を指しているときだけ、1 回の rename（mv -T）で切替え先へ置き換える。rename の直前まで
#   pointer は旧版を指したまま（先に消さない）。一時 symlink を版の directory の中へ移さない。rollback は逆向きの switch
# - status: pointer の指す版・版の一覧・残った一時 directory を出し、根の下に何も書かない
# symlink を作れる環境が要る（Linux の tool image。Git for Windows では MSYS=winsymlinks:sys で動く）。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
SCRIPT="$ROOT/ops/backup/uploads-cutover.sh"

# 根 $T/uploads に版 v0（今の版）と current -> v0、複製元 $T/ready（復元した uploads 相当）を作る
setup_tree() {
  T=$(mktemp -d)
  R="$T/uploads"
  mkdir -p "$R/v0/published" "$T/ready/published/sub" "$T/ready/quarantine"
  printf 'old\n' > "$R/v0/published/a.txt"
  ln -s v0 "$R/current"
  printf 'restored-a\n' > "$T/ready/published/a.txt"
  printf '日本語\n' > "$T/ready/published/sub/b.txt"
  printf 'ledger\n' > "$T/ready/published/sub/ledger.properties"
  chmod 600 "$T/ready/published/sub/ledger.properties"
}

teardown_tree() { rm -rf "$T"; }

run_cutover() { # <args...> → CO_OUT / CO_RC
  CO_OUT=$(PATH="${CO_PATH:-$PATH}" "$SCRIPT" "$@" 2>&1)
  CO_RC=$?
}

# 根の下（版の中を含む）に一時の名前が残っていない
assert_no_temp_left() { # <label>
  assert_eq "" "$(find "$R" -name '.stage-*' -o -name '.current.switch-*' 2>/dev/null)" "一時の名前を残さない: $1"
}

# 前提: この環境で symlink を作れる（作れないと ln -s が複製になり、試験の意味が変わる）
case_cutover_requires_symlink_capable_environment() {
  local t
  t=$(mktemp -d)
  mkdir "$t/v0"
  ln -s v0 "$t/link" 2>/dev/null
  if [[ -L "$t/link" && "$(readlink "$t/link")" == v0 ]]; then
    test_assert "前提: symlink を作れる環境で実行している"
  else
    test_fail "前提: symlink を作れる環境で実行している" "ln -s が symlink を作りません（Git for Windows では MSYS=winsymlinks:sys を設定する）"
  fi
  rm -rf "$t"
}

case_stage_creates_new_version_without_touching_current() {
  setup_tree
  run_cutover stage --from "$T/ready" --root "$R" --version v1
  assert_zero "$CO_RC" "stage は成功する"
  assert_contains "$CO_OUT" "version=v1" "新しい版の名前を出力する"
  assert_contains "$CO_OUT" "files=3" "複製した file の数を出力する"
  assert_contains "$CO_OUT" "pointer_target=v0" "今の pointer の指す版を出力する"
  assert_eq "restored-a" "$(cat "$R/v1/published/a.txt" 2>/dev/null)" "復元した内容を新しい版に複製する"
  assert_eq "日本語" "$(cat "$R/v1/published/sub/b.txt" 2>/dev/null)" "階層の下の file も複製する"
  assert_eq "ledger" "$(cat "$R/v1/published/sub/ledger.properties" 2>/dev/null)" "0600 の file も複製する"
  if [[ -d "$R/v1/quarantine" ]]; then test_assert "空の directory も複製する"; else test_fail "空の directory も複製する" "無い"; fi
  assert_eq "v0" "$(readlink "$R/current")" "pointer は変えない"
  assert_eq "old" "$(cat "$R/v0/published/a.txt")" "今の版は変えない"
  assert_no_temp_left "stage の成功"
  teardown_tree
}

case_stage_rejects_and_leaves_nothing() {
  local bad
  setup_tree
  run_cutover stage --from "$T/ready" --root "$R" --version v0
  assert_nonzero "$CO_RC" "今の版の名前は拒否する"
  assert_contains "$CO_OUT" "既にあります" "上書きしないことを通知"
  assert_eq "old" "$(cat "$R/v0/published/a.txt")" "今の版を上書きしない"
  run_cutover stage --from "$T/ready" --root "$R" --version current
  assert_nonzero "$CO_RC" "pointer の名前は版の名前にできない"
  assert_contains "$CO_OUT" "pointer の名前" "理由を通知: pointer の名前"
  for bad in "../v1" "/v1" ".v1" "v1/sub" "v 1"; do
    run_cutover stage --from "$T/ready" --root "$R" --version "$bad"
    assert_nonzero "$CO_RC" "不正な版の名前は拒否する: [$bad]"
    assert_contains "$CO_OUT" "1 段の名前ではありません" "理由を通知: [$bad]"
  done
  assert_eq "v0 current" "$(cd "$R" && ls -A | LC_ALL=C sort -r | tr '\n' ' ' | sed 's/ $//')" "根の下は v0 と current のまま"
  assert_eq "v0" "$(readlink "$R/current")" "拒否しても pointer は変えない"
  teardown_tree

  setup_tree
  ln -s a.txt "$T/ready/published/link.txt"
  run_cutover stage --from "$T/ready" --root "$R" --version v1
  assert_nonzero "$CO_RC" "複製元に symlink があれば拒否する"
  assert_contains "$CO_OUT" "安全でない entry" "理由を通知: symlink"
  assert_no_file "$R/v1" "新しい版を作らない: symlink"
  assert_no_temp_left "symlink の拒否"
  teardown_tree

  setup_tree
  mkfifo "$T/ready/published/pipe"
  run_cutover stage --from "$T/ready" --root "$R" --version v1
  assert_nonzero "$CO_RC" "複製元に FIFO があれば拒否する"
  assert_no_file "$R/v1" "新しい版を作らない: FIFO"
  assert_no_temp_left "FIFO の拒否"
  teardown_tree

  setup_tree
  run_cutover stage --from "$T/not-exists" --root "$R" --version v1
  assert_nonzero "$CO_RC" "複製元が無ければ拒否する"
  ln -s ready "$T/ready-link"
  run_cutover stage --from "$T/ready-link" --root "$R" --version v1
  assert_nonzero "$CO_RC" "複製元が symlink なら拒否する"
  ln -s uploads "$T/uploads-link"
  run_cutover stage --from "$T/ready" --root "$T/uploads-link" --version v1
  assert_nonzero "$CO_RC" "根が symlink なら拒否する"
  assert_contains "$CO_OUT" "uploads の根が実 directory ではありません" "理由を通知: 根が symlink"
  assert_no_file "$R/v1" "新しい版を作らない: 根・複製元の拒否"
  assert_no_temp_left "根・複製元の拒否"
  teardown_tree
}

# 複製の途中・複製後の照合で失敗しても、新しい版も一時 directory も残さない（作りかけの版は現れない）
case_stage_failure_after_copy_starts_removes_temp() {
  setup_tree
  mkdir -p "$T/bin"
  printf '%s\n' '#!/usr/bin/env bash' 'echo "cp: 試験のための失敗" >&2' 'exit 1' > "$T/bin/cp"
  chmod +x "$T/bin/cp"
  CO_PATH="$T/bin:$PATH"
  run_cutover stage --from "$T/ready" --root "$R" --version v1
  CO_PATH=""
  assert_nonzero "$CO_RC" "複製の失敗は非 0"
  assert_contains "$CO_OUT" "複製に失敗しました" "理由を通知: 複製の失敗"
  assert_no_file "$R/v1" "複製に失敗したら新しい版を作らない"
  assert_no_temp_left "複製の失敗"
  teardown_tree

  # 複製した内容の照合（SHA-256）で不一致になったら rename しない。sha256sum を呼ぶたびに違う値を返す stub で起こす
  setup_tree
  mkdir -p "$T/bin"
  cat > "$T/bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
cat > /dev/null
n=$(cat "$SHA_STUB_COUNTER" 2>/dev/null || echo 0)
n=$((n + 1))
echo "$n" > "$SHA_STUB_COUNTER"
printf '%064d  -\n' "$n"
EOF
  chmod +x "$T/bin/sha256sum"
  export SHA_STUB_COUNTER="$T/sha-counter"
  CO_PATH="$T/bin:$PATH"
  run_cutover stage --from "$T/ready" --root "$R" --version v1
  CO_PATH=""
  unset SHA_STUB_COUNTER
  assert_nonzero "$CO_RC" "照合の不一致は非 0"
  assert_contains "$CO_OUT" "複製元と一致しません" "理由を通知: 照合の不一致"
  assert_no_file "$R/v1" "照合が一致しなければ新しい版を作らない"
  assert_no_temp_left "照合の不一致"
  assert_eq "v0" "$(readlink "$R/current")" "照合の不一致でも pointer は変えない"
  teardown_tree
}

case_switch_replaces_pointer_with_one_rename_and_rolls_back() {
  local mvlog rollback
  setup_tree
  run_cutover stage --from "$T/ready" --root "$R" --version v1
  assert_zero "$CO_RC" "前提: 新しい版を stage した"
  # mv を記録する stub（呼ばれた時点の pointer の指す先を書いてから本物の mv を実行する）
  mkdir -p "$T/bin"
  mvlog="$T/mv.log"
  cat > "$T/bin/mv" <<EOF
#!/usr/bin/env bash
printf 'mv %s pointer=%s\n' "\$*" "\$(readlink "$R/current" 2>/dev/null || echo NONE)" >> "$mvlog"
exec "$(command -v mv)" "\$@"
EOF
  chmod +x "$T/bin/mv"
  CO_PATH="$T/bin:$PATH"
  run_cutover switch --root "$R" --to v1 --expect v0
  CO_PATH=""
  assert_zero "$CO_RC" "switch は成功する"
  assert_eq "v1" "$(readlink "$R/current")" "pointer は相対・1 段の名前で新しい版を指す"
  assert_eq "restored-a" "$(cat "$R/current/published/a.txt")" "pointer を通して新しい版を読める"
  assert_contains "$CO_OUT" "previous=v0" "切替え前の版を出力する"
  assert_contains "$CO_OUT" "current=v1" "切替え後の版を出力する"
  assert_contains "$CO_OUT" "rollback=uploads-cutover.sh switch --root" "rollback の command を出力する"
  assert_eq 1 "$(grep -c '^mv ' "$mvlog" 2>/dev/null)" "pointer の置き換えは mv（rename）1 回だけ"
  assert_contains "$(cat "$mvlog" 2>/dev/null)" "pointer=v0" "rename の直前まで pointer は旧版を指している（先に消さない）"
  assert_contains "$(cat "$mvlog" 2>/dev/null)" "mv -T -- " "mv に -T を付ける（pointer の指す directory の中へ移さない）"
  assert_eq "" "$(find "$R/v0" "$R/v1" -name '.current.switch-*' 2>/dev/null)" "一時 symlink を版の directory の中へ移さない"
  assert_no_temp_left "switch の成功"
  assert_eq "old" "$(cat "$R/v0/published/a.txt")" "旧版は消さない"

  rollback=$(sed -n 's/^rollback=//p' <<< "$CO_OUT")
  assert_contains "$rollback" "--to v0 --expect v1" "rollback は逆向きの switch"
  run_cutover switch --root "$R" --to v0 --expect v1
  assert_zero "$CO_RC" "rollback（逆向きの switch）は成功する"
  assert_eq "v0" "$(readlink "$R/current")" "rollback で旧版に戻る"
  assert_eq "restored-a" "$(cat "$R/v1/published/a.txt")" "rollback しても新しい版は残る"
  assert_no_temp_left "rollback"
  teardown_tree
}

case_switch_rejects_unexpected_state() {
  local bad
  setup_tree
  mkdir -p "$R/v1"
  run_cutover switch --root "$R" --to v1 --expect v2
  assert_nonzero "$CO_RC" "pointer が --expect と違えば切り替えない"
  assert_contains "$CO_OUT" "pointer は v0 を指しています" "理由を通知: --expect の不一致"
  run_cutover switch --root "$R" --to v9 --expect v0
  assert_nonzero "$CO_RC" "切替え先が無ければ切り替えない"
  assert_contains "$CO_OUT" "実 directory ではありません" "理由を通知: 切替え先が無い"
  ln -s v1 "$R/v1link"
  run_cutover switch --root "$R" --to v1link --expect v0
  assert_nonzero "$CO_RC" "切替え先が symlink なら切り替えない（2 段の symlink にしない）"
  printf 'x\n' > "$R/vfile"
  run_cutover switch --root "$R" --to vfile --expect v0
  assert_nonzero "$CO_RC" "切替え先が file なら切り替えない"
  run_cutover switch --root "$R" --to v0 --expect v0
  assert_nonzero "$CO_RC" "切替え先が今の版と同じなら切り替えない"
  run_cutover switch --root "$R" --to current --expect v0
  assert_nonzero "$CO_RC" "pointer の名前へは切り替えない"
  for bad in "../v1" "/v1" ".v1"; do
    run_cutover switch --root "$R" --to "$bad" --expect v0
    assert_nonzero "$CO_RC" "不正な版の名前は拒否する: [$bad]"
  done
  run_cutover switch --root "$R" --to v1 --expect v0 --pointer "../current"
  assert_nonzero "$CO_RC" "不正な pointer の名前は拒否する"
  assert_eq "v0" "$(readlink "$R/current")" "拒否しても pointer は変えない"
  assert_no_temp_left "switch の拒否"
  teardown_tree

  # pointer が実 directory に置き換わっている（app が作ってしまった）・無い・絶対 path なら切り替えない
  setup_tree
  mkdir -p "$R/v1"
  rm "$R/current"
  mkdir "$R/current"
  printf 'app\n' > "$R/current/x.txt"
  run_cutover switch --root "$R" --to v1 --expect v0
  assert_nonzero "$CO_RC" "pointer が実 directory なら切り替えない"
  assert_contains "$CO_OUT" "symlink ではありません" "理由を通知: pointer が実 directory"
  assert_eq "app" "$(cat "$R/current/x.txt")" "実 directory の中身は変えない"
  rm -rf "$R/current"
  run_cutover switch --root "$R" --to v1 --expect v0
  assert_nonzero "$CO_RC" "pointer が無ければ切り替えない"
  assert_no_file "$R/current" "無い pointer を作らない"
  ln -s "$R/v0" "$R/current"
  run_cutover switch --root "$R" --to v1 --expect v0
  assert_nonzero "$CO_RC" "pointer が絶対 path なら切り替えない"
  assert_eq "$R/v0" "$(readlink "$R/current")" "絶対 path の pointer も変えない"
  assert_no_temp_left "pointer の不正"
  teardown_tree
}

case_status_reports_and_writes_nothing() {
  local before
  setup_tree
  mkdir -p "$R/v1" "$R/.stage-v2.abc123"
  before=$(cd "$R" && ls -A | LC_ALL=C sort)
  run_cutover status --root "$R"
  assert_zero "$CO_RC" "status は成功する"
  assert_contains "$CO_OUT" "target=v0" "pointer の指す版を出力する"
  assert_contains "$CO_OUT" "versions=v0 v1" "版の directory を出力する（pointer と一時 directory は含めない）"
  assert_contains "$CO_OUT" "leftover=.stage-v2.abc123" "残った一時 directory を出力する"
  assert_eq "$before" "$(cd "$R" && ls -A | LC_ALL=C sort)" "status は根の下に何も書かない"
  rm "$R/current"
  run_cutover status --root "$R"
  assert_nonzero "$CO_RC" "pointer を解決できなければ非 0"
  assert_contains "$CO_OUT" "error=uploads-pointer: current がありません" "理由を出力する"
  teardown_tree

  run_cutover bogus --root /nonexistent
  assert_eq 2 "$CO_RC" "未知の subcommand は 2"
  run_cutover switch --root /nonexistent --to v1
  assert_eq 2 "$CO_RC" "引数の不足は 2"
}

run_case case_cutover_requires_symlink_capable_environment
run_case case_stage_creates_new_version_without_touching_current
run_case case_stage_rejects_and_leaves_nothing
run_case case_stage_failure_after_copy_starts_removes_temp
run_case case_switch_replaces_pointer_with_one_rename_and_rolls_back
run_case case_switch_rejects_unexpected_state
run_case case_status_reports_and_writes_nothing

test_summary "uploads の版の切替え（uploads-cutover.sh）"
