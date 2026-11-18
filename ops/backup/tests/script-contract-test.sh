#!/usr/bin/env bash
# 本番 script（ops/backup の *.sh・lib・providers・image）に横断的にかかる契約（RB-W 追加修正）
#
# - pipefail と SIGPIPE（RB-W P1-1）: 本番 script は set -Eeuo pipefail で動く。パイプの後段に途中で読むのを
#   やめ得る command（grep -q / -m / -l・head・exit する awk・sed の q・read・cmp）を置くと、入力が pipe の容量
#   （Linux 64 KiB）を超えたとき前段が SIGPIPE（141）で終わり、パイプ全体が失敗に見える（table 0 件の誤判定、
#   archive / snapshot / checkpoint の停止）。入力は here-string で渡す。静的な走査と、1 MiB を超える入力での
#   挙動の両方で確かめる（走査器そのものも、検出すべき書き方と検出しない書き方の見本で確かめる）。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
BACKUP_ROOT="$ROOT/ops/backup"
LIB="$BACKUP_ROOT/lib"

# 本番の script（image に入る *.sh と lib・providers、build の image/*.sh）。試験・fixture は含めない。
production_scripts() {
  local f
  for f in "$BACKUP_ROOT"/*.sh "$LIB"/*.sh "$BACKUP_ROOT"/providers/*.sh "$BACKUP_ROOT"/image/*.sh; do
    [[ -f "$f" ]] && printf '%s\n' "$f"
  done
  return 0
}

# パイプの後段に置かれた「途中で読むのをやめ得る command」を出力する（<file>:<行番号>: <理由>）。
# 行全体のコメントは見ない。awk の program が複数行にわたるときは、閉じる ' までを 1 つの program として exit を探す
# （END の exit も含めて禁止する。パイプの後段の awk には exit を書かず、必要なら here-string で渡す）。
early_exit_readers_after_pipe() { # <file>...
  awk -v q="'" '
    function nquote(s,   t) { t = s; return gsub(q, "", t) }
    function has_exit(s) { return s ~ /(^|[^A-Za-z_])exit([^A-Za-z_]|$)/ }
    BEGIN {
      pipe = "(^|[^|])[|][[:space:]]*"
      sedq = pipe "sed[[:space:]][^|]*[0-9;/" q "[:space:]]q([;" q "[:space:]}]|$)"
    }
    FNR == 1 { inawk = 0 }
    inawk {
      prog = prog "\n" $0
      if (nquote($0) % 2 == 1) {
        inawk = 0
        if (has_exit(prog)) print FILENAME ":" start ": パイプの後段の awk の program に exit がある"
      }
      next
    }
    /^[[:space:]]*#/ { next }
    $0 ~ (pipe "grep[[:space:]]([^|]*[[:space:]])?-[A-Za-z]*[qlLm]") { print FILENAME ":" FNR ": パイプの後段の grep -q / -m / -l" }
    $0 ~ (pipe "grep[[:space:]][^|]*--(quiet|silent|max-count|files-with)") { print FILENAME ":" FNR ": パイプの後段の grep --quiet 等" }
    $0 ~ (pipe "head([[:space:]]|$|[)])") { print FILENAME ":" FNR ": パイプの後段の head" }
    $0 ~ sedq { print FILENAME ":" FNR ": パイプの後段の sed の q" }
    $0 ~ (pipe "(IFS=[^[:space:]]*[[:space:]]+)?read([[:space:]]|$)") { print FILENAME ":" FNR ": パイプの後段の read" }
    $0 ~ (pipe "cmp([[:space:]]|$)") { print FILENAME ":" FNR ": パイプの後段の cmp" }
    match($0, /(^|[^|])[|][[:space:]]*awk([[:space:]]|$)/) {
      rest = substr($0, RSTART)
      if (nquote(rest) % 2 == 1) {
        inawk = 1; prog = rest; start = FNR
      } else if (has_exit(rest)) {
        print FILENAME ":" FNR ": パイプの後段の awk の program に exit がある"
      }
    }' "$@"
}

# 走査器そのものの確認: 検出すべき書き方はすべて検出し、here-string・読み切る command・コメント・|| は検出しない
case_pipefail_scanner_detects_each_form() {
  local t out expected
  t=$(mktemp -d)
  cat > "$t/sample.sh" <<'EOF'
x=$(printf '%s\n' "$a" | grep -q foo)
if printf '%s' "$a" | grep -qiE 'ok$'; then :; fi
y=$(ls "$d" | head -n 1)
z=$(cmd | awk -v f="$f" '$1 == f {print $2; exit}')
w=$(cmd | awk '
  { bad = 1; exit }
')
v=$(cmd | sed -n '1p;q')
u=$(cmd | grep -m1 x)
cmd | IFS= read -r line
cmd | cmp -s - "$file"
s=$(cmd | grep --quiet x)
# コメントの | head -n 1 は見ない
a=$(grep -q foo <<< "$x")
b=$(awk '{ print; exit }' <<< "$x")
c=$(cmd | awk '{ print $1 }')
e=$(cmd | grep -c x)
f=$(cmd || head -c 1 "$file")
g=$(cmd | tail -n 1)
h=$(cmd | awk '
  { n++ }
  END { print n }
')
i=$(cmd | sed 's/q//')
EOF
  out=$(early_exit_readers_after_pipe "$t/sample.sh")
  for expected in 1 2 3 4 5 8 9 10 11 12; do
    assert_contains "$out" "sample.sh:$expected: " "検出すべき書き方を検出する（見本の $expected 行目）"
  done
  assert_eq 10 "$(grep -c . <<< "$out")" "検出すべき書き方だけを検出する（here-string・読み切る command・コメント・|| は除く）"
  rm -rf "$t"
}

# 本番 script に「パイプの後段で途中で読むのをやめ得る command」が無い
case_pipefail_production_scripts_have_no_early_exit_reader_after_pipe() {
  local -a files=()
  local f out
  while IFS= read -r f; do files+=("$f"); done < <(production_scripts)
  if (( ${#files[@]} > 20 )); then
    test_assert "本番 script を走査する（${#files[@]} 個）"
  else
    test_fail "本番 script を走査する" "走査対象が少なすぎます（${#files[@]} 個）"
  fi
  out=$(early_exit_readers_after_pipe "${files[@]}")
  assert_eq "" "$out" "パイプの後段で途中で読むのをやめ得る command を置かない（here-string で渡す）"
}

# lib/binlog.sh の binlog::size_from_logs: 一覧が 1 MiB を超え、該当行が先頭近くにあっても pipefail 下で size を返す
# （archive / snapshot / checkpoint の旧い書き方 printf | awk '... exit' は SIGPIPE で失敗していた）
case_pipefail_binlog_size_from_logs_with_large_list() {
  local logs n out rc
  # shellcheck disable=SC1091
  . "$LIB/binlog.sh"
  set -o pipefail
  logs=$(seq -f $'binlog.%06g\t1073741824\tNo' 1 40000)
  n=${#logs}
  if (( n > 1048576 )); then
    test_assert "1 MiB を超える SHOW BINARY LOGS の一覧を用意した（前提: ${n} bytes）"
  else
    test_fail "1 MiB を超える SHOW BINARY LOGS の一覧を用意した（前提）" "${n} bytes"
  fi
  # 前提: 修正前の書き方はこの入力で失敗する（この環境で SIGPIPE が起きることの確認）
  out=$(printf '%s\n' "$logs" | awk -v f=binlog.000002 '$1 == f {print $2; exit}')
  rc=$?
  assert_nonzero "$rc" "前提: パイプで渡して途中で exit する awk は pipefail 下で失敗する（修正前の書き方）"
  out=$(binlog::size_from_logs "$logs" binlog.000002)
  rc=$?
  assert_zero "$rc" "先頭近くの file でも pipefail 下で失敗しない"
  assert_eq 1073741824 "$out" "先頭近くの file の size を返す"
  out=$(binlog::size_from_logs "$logs" binlog.040000)
  assert_eq 1073741824 "$out" "最後の file の size を返す"
  out=$(binlog::size_from_logs "$logs" binlog.040001)
  rc=$?
  assert_zero "$rc" "一覧に無い file は空で返す（終了コードは 0）"
  assert_eq "" "$out" "一覧に無い file は何も出さない"
}

# lib/validate-db.sh の validate_db::check_tables: CHECK TABLE の出力が 1 MiB を超え、OK が先頭にあっても pipefail 下で
# 「OK が無い」と誤判定しない。OK が無い出力は従来どおり失敗する。
case_pipefail_validate_db_check_tables_with_large_output() {
  local t rc out
  t=$(mktemp -d)
  # shellcheck disable=SC1091
  . "$LIB/validate-db.sh"
  set -o pipefail
  cat > "$t/mysql-large" <<'EOF'
#!/usr/bin/env bash
if [[ "${FAKE_CHECK_OK:-1}" == 1 ]]; then
  printf 'ses_manager_db.t_a\tcheck\tstatus\tOK\n'
fi
seq -f $'ses_manager_db.t_a\tcheck\tinfo\tfiller-%06g' 1 40000
EOF
  chmod +x "$t/mysql-large"
  MYSQL_CLIENT_BIN="$t/mysql-large"
  TARGET_OPT_ARGS=(--defaults-extra-file=/dev/null)
  export FAKE_CHECK_OK=1
  if (( $("$t/mysql-large" | wc -c) > 1048576 )); then
    test_assert "1 MiB を超える CHECK TABLE の出力を用意した（前提）"
  else
    test_fail "1 MiB を超える CHECK TABLE の出力を用意した（前提）" "1 MiB 以下です"
  fi
  out=$(validate_db::check_tables "t_a" 2>&1)
  rc=$?
  assert_zero "$rc" "OK の後に 1 MiB を超える行があっても pipefail 下で OK と判定する"
  export FAKE_CHECK_OK=0
  out=$(validate_db::check_tables "t_a" 2>&1)
  rc=$?
  assert_nonzero "$rc" "OK が無い CHECK TABLE の出力は失敗する"
  assert_contains "$out" "CHECK TABLE 失敗: t_a" "失敗した table を通知"
  unset FAKE_CHECK_OK
  rm -rf "$t"
}

run_case case_pipefail_scanner_detects_each_form
run_case case_pipefail_production_scripts_have_no_early_exit_reader_after_pipe
run_case case_pipefail_binlog_size_from_logs_with_large_list
run_case case_pipefail_validate_db_check_tables_with_large_output

test_summary "本番 script の横断的な契約（pipefail と SIGPIPE）"
