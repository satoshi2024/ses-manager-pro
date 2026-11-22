#!/usr/bin/env bash
# 本番 script（ops/backup の *.sh・lib・providers・image）に横断的にかかる契約（RB-W 追加修正）
#
# - pipefail と SIGPIPE（RB-W P1-1）: 本番 script は set -Eeuo pipefail で動く。パイプの後段に途中で読むのを
#   やめ得る command（grep -q / -m / -l・head・exit する awk・sed の q・read・cmp）を置くと、入力が pipe の容量
#   （Linux 64 KiB）を超えたとき前段が SIGPIPE（141）で終わり、パイプ全体が失敗に見える（table 0 件の誤判定、
#   archive / snapshot / checkpoint の停止）。入力は here-string で渡す。静的な走査と、1 MiB を超える入力での
#   挙動の両方で確かめる（走査器そのものも、検出すべき書き方と検出しない書き方の見本で確かめる）。
# - MySQL 8.4（RB-W 8.4 対応）: 本番 script と、実 MySQL 8.4 で動く integration の script・compose は、8.4 で削除された
#   構文（RESET MASTER・SHOW MASTER STATUS・CHANGE MASTER TO 等）と、使わないと決めたもの（@@have_ssl・--binlog-format・
#   SET_USER_ID）を書かない（行全体のコメントは除く）。
# - EXIT trap（RB-W）: handler は関数を抜けた後に評価されるため、単一引用符で登録する handler に関数の local 変数を
#   書かない（set -u で unbound variable になり後始末が動かない）。登録時に printf %q で展開するか global 変数を使う。
#   登録した handler は common::trap_remove で外し、trap - EXIT は書かない（RB-W P3-2）。
# - mysqldump の option（2026-11-18 利用者決定）: 本番 script の mysqldump はすべて --no-tablespaces を付ける（source 側の account に
#   PROCESS を与えない）。その前提として、production に入る migration は TABLESPACE を使わない（repository の migration を読む）。
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
# RB-W P3-1 で次の書き方も見る（以前は見落としていた）:
#   - 行末の | の次の行に読み手を書く（cmd | の次の行に grep -q x）: 次の行の先頭にパイプがあるものとして見る
#   - | while / until … do … done の中の break・return・exit（ループを途中で抜けると残りを読まない）。done までを 1 つと見る
#   - 読み手の前の環境変数の代入・command・env・exec（| LC_ALL=C awk '…exit'、| env grep -q x）
#   - |&（標準エラーもつなぐパイプ）、読む量を固定する dd の count= と xxd -l
early_exit_readers_after_pipe() { # <file>...
  awk -v q="'" '
    function nquote(s,   t) { t = s; return gsub(q, "", t) }
    function has_exit(s) { return s ~ /(^|[^A-Za-z_])exit([^A-Za-z_]|$)/ }
    # 語 w の出現数（語でない文字を 2 つの空白にしてから " w " を数える。隣り合う語も数え落とさない）
    function nword(s, w,   t) { t = " " s " "; gsub(/[^A-Za-z0-9_]/, "  ", t); return gsub(" " w " ", "", t) }
    function close_while() {
      if (nword(wprog, "do") > 0 && nword(wprog, "done") >= nword(wprog, "do")) {
        inwhile = 0
        if (nword(wprog, "break") + nword(wprog, "return") + nword(wprog, "exit") > 0)
          print FILENAME ":" wstart ": パイプの後段の while / until の中に break / return / exit がある"
      }
    }
    BEGIN {
      pipe = "(^|[^|])[|]&?[[:space:]]*"
      # 読み手の前に置ける語（環境変数の代入・command・env・exec。env の option は - で始まる語として許す）
      pfx = "(([A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*|command|env|exec|-[A-Za-z]+)[[:space:]]+)*"
      p = pipe pfx
      sedq = p "sed[[:space:]][^|]*[0-9;/" q "[:space:]]q([;" q "[:space:]}]|$)"
    }
    FNR == 1 { inawk = 0; inwhile = 0; pend = 0 }
    { trail = ($0 ~ /(^|[^|])[|][[:space:]]*$/) }
    inawk {
      prog = prog "\n" $0
      if (nquote($0) % 2 == 1) {
        inawk = 0
        if (has_exit(prog)) print FILENAME ":" start ": パイプの後段の awk の program に exit がある"
      }
      pend = trail
      next
    }
    # ループの本体の行も、ほかの書き方の検査は続ける（next しない）
    inwhile && $0 !~ /^[[:space:]]*#/ { wprog = wprog "\n" $0; close_while() }
    /^[[:space:]]*#/ { next }
    {
      ln = (pend ? "| " $0 : $0)
      pend = trail
    }
    ln ~ (p "grep[[:space:]]([^|]*[[:space:]])?-[A-Za-z]*[qlLm]") { print FILENAME ":" FNR ": パイプの後段の grep -q / -m / -l" }
    ln ~ (p "grep[[:space:]][^|]*--(quiet|silent|max-count|files-with)") { print FILENAME ":" FNR ": パイプの後段の grep --quiet 等" }
    ln ~ (p "head([[:space:]]|$|[)])") { print FILENAME ":" FNR ": パイプの後段の head" }
    ln ~ sedq { print FILENAME ":" FNR ": パイプの後段の sed の q" }
    ln ~ (p "read([[:space:]]|$)") { print FILENAME ":" FNR ": パイプの後段の read" }
    ln ~ (p "cmp([[:space:]]|$)") { print FILENAME ":" FNR ": パイプの後段の cmp" }
    ln ~ (p "dd[[:space:]][^|]*count=") { print FILENAME ":" FNR ": パイプの後段の dd の count=" }
    ln ~ (p "xxd[[:space:]][^|]*-l") { print FILENAME ":" FNR ": パイプの後段の xxd -l" }
    match(ln, p "awk([[:space:]]|$)") {
      rest = substr(ln, RSTART)
      if (nquote(rest) % 2 == 1) {
        inawk = 1; prog = rest; start = FNR
      } else if (has_exit(rest)) {
        print FILENAME ":" FNR ": パイプの後段の awk の program に exit がある"
      }
    }
    match(ln, p "(while|until)([[:space:]]|$)") {
      wprog = substr(ln, RSTART); wstart = FNR; inwhile = 1
      close_while()
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
j=$(cmd |
  grep -q foo)
cmd | while IFS= read -r l; do [[ "$l" == x ]] && break; done
cmd | while read -r l; do
  [[ -n "$l" ]] || return 1
done
k=$(cmd | LC_ALL=C awk '$1 == f { print; exit }')
cmd | env grep -q x
l=$(cmd | command head -n 1)
cmd |& grep -q x
m=$(cmd | dd bs=1 count=4 status=none)
cmd | while IFS= read -r l; do printf '%s\n' "$l"; done
n=$(cmd |
  sort -u)
cmd ||
  grep -q x "$file"
o=$(LC_ALL=C sort <<< "$x")
cmd | while read -r l; do
  # コメントの break は見ない
  printf '%s\n' "$l"
done
EOF
  out=$(early_exit_readers_after_pipe "$t/sample.sh")
  # 25 行目以降は RB-W P3-1 で足した書き方（行末の |・while の break / return・前置きの代入や env・|&・dd の count=）
  for expected in 1 2 3 4 5 8 9 10 11 12 26 27 28 31 32 33 34 35; do
    assert_contains "$out" "sample.sh:$expected: " "検出すべき書き方を検出する（見本の $expected 行目）"
  done
  assert_eq 18 "$(grep -c . <<< "$out")" "検出すべき書き方だけを検出する（here-string・読み切る command・読み切る while・コメント・|| は除く）"
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

# MySQL 8.4 で削除された構文（MySQL 8.4.0 release notes。MASTER / SLAVE を含む構文は 8.2.0 から非推奨）と、8.4 対応で
# 使わないと決めたもの（@@have_ssl・--binlog-format の指定・SET_USER_ID）。大文字・小文字を区別しない。
MYSQL84_REMOVED_PATTERN='RESET MASTER|SHOW MASTER (STATUS|LOGS)|PURGE MASTER LOGS|CHANGE MASTER TO|MASTER_LOG_(FILE|POS)|(START|STOP|RESET) SLAVE|SHOW SLAVE (STATUS|HOSTS)|--master-data|--delete-master-logs|--default[-_]authentication[-_]plugin|have_ssl|--binlog[-_]format|SET_USER_ID'

# 8.4 の server・client に対して動く file（本番 script と、実 MySQL 8.4 で動く integration の script・compose・responder）
mysql84_target_files() {
  production_scripts
  printf '%s\n' "$BACKUP_ROOT/docker-compose.yml" "$HERE/docker-compose.integration.yml" "$HERE/integration-pitr.sh" \
    "$HERE/integration-responder.sh"
}

# 上の構文を含む行を出力する（<file>:<行番号>: <行>）。行全体のコメントは見ない（経緯の説明は書いてよい）
mysql84_removed_syntax() { # <file>...
  awk -v pat="$MYSQL84_REMOVED_PATTERN" '
    /^[[:space:]]*#/ { next }
    toupper($0) ~ toupper(pat) { print FILENAME ":" FNR ": " $0 }' "$@"
}

# RB-W 8.4 対応: 走査器の確認と、本番 script・integration が 8.4 で削除された構文を使わないこと
case_mysql84_no_removed_syntax() {
  local t out expected f
  local -a files=()
  t=$(mktemp -d)
  cat > "$t/sample.sh" <<'EOF'
mysql --execute "RESET MASTER;"
mysql -e "reset master"
mysql -e "SHOW MASTER STATUS"
grep "CHANGE MASTER TO MASTER_LOG_FILE='binlog.000001'" dump.sql
      - --binlog-format=ROW
mysql -e "SELECT @@have_ssl"
GRANT SET_USER_ID ON *.* TO 'restore-svc'@'%';
mysql -e "START SLAVE"
mysqldump --master-data=2 db
# RESET MASTER は 8.4 で削除された（コメントは見ない）
mysql --execute "RESET BINARY LOGS AND GTIDS;"
re="CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='([^']+)', *SOURCE_LOG_POS=([0-9]+)"
mysql -e "SHOW BINARY LOG STATUS"
mysqldump --source-data=2 db
mysql -e "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('binlog_format')"
GRANT SET_ANY_DEFINER ON *.* TO 'restore-svc'@'%';
EOF
  out=$(mysql84_removed_syntax "$t/sample.sh")
  for expected in 1 2 3 4 5 6 7 8 9; do
    assert_contains "$out" "sample.sh:$expected: " "8.4 で削除・不使用の構文を検出する（見本の $expected 行目）"
  done
  assert_eq 9 "$(grep -c . <<< "$out")" "8.4 の構文・コメントは検出しない"
  rm -rf "$t"

  while IFS= read -r f; do files+=("$f"); done < <(mysql84_target_files)
  for f in "${files[@]}"; do
    [[ -f "$f" ]] || test_fail "走査する file がある" "$f がありません"
  done
  out=$(mysql84_removed_syntax "${files[@]}")
  assert_eq "" "$out" "本番 script と integration は MySQL 8.4 で削除された構文（MASTER / SLAVE 等）と不使用と決めたものを使わない"
}

# EXIT trap の handler（common::trap_add '…' と trap '…' EXIT の、単一引用符で登録時に展開しないもの）が、同じ file の
# local 変数を参照している行を出力する（<file>:<行番号>: <理由>）。handler は関数を抜けた後に評価されるため、local 変数は
# set -u で unbound variable になり、後始末が動かない（uploads-local.sh の staging が残っていた。RB-W）。
# handler から呼ぶ関数の中身までは追わない（後始末の関数は global 変数だけを使う）。
trap_handlers_referencing_locals() { # <file>...
  local f
  for f in "$@"; do
    awk -v q="'" '
      BEGIN {
        add_re = "common::trap_add[[:space:]]+" q "[^" q "]*" q
        trap_re = "trap[[:space:]]+" q "[^" q "]*" q "[[:space:]]+EXIT"
      }
      FNR == NR {
        if ($0 ~ /^[[:space:]]*local[[:space:]]/) {
          n = split($0, tok, /[[:space:]]+/)
          for (i = 1; i <= n; i++) {
            t = tok[i]
            if (t == "" || t == "local" || t ~ /^-/) continue
            sub(/=.*/, "", t)
            if (t ~ /^[A-Za-z_][A-Za-z0-9_]*$/) locals[t] = 1
          }
        }
        next
      }
      /^[[:space:]]*#/ { next }
      {
        h = ""
        if (match($0, add_re)) h = substr($0, RSTART, RLENGTH)
        else if (match($0, trap_re)) h = substr($0, RSTART, RLENGTH)
        while (h != "" && match(h, /[$][{]?[A-Za-z_][A-Za-z0-9_]*/)) {
          v = substr(h, RSTART, RLENGTH)
          sub(/^[$][{]?/, "", v)
          if (v in locals) print FILENAME ":" FNR ": EXIT trap の handler が local 変数 " v " を参照している"
          h = substr(h, RSTART + RLENGTH)
        }
      }' "$f" "$f"
  done
}

# RB-W: 走査器の確認と、本番 script の EXIT trap の handler が local 変数を参照しないこと
case_trap_handlers_do_not_reference_function_locals() {
  local t out f
  local -a files=()
  t=$(mktemp -d)
  cat > "$t/sample.sh" <<'EOF'
f() {
  local staging="$1"
  common::trap_add 'rm -rf "$staging"'
}
g() {
  local -a items=()
  trap 'echo "${items[0]}"' EXIT
}
WORK=""
h() {
  WORK=/tmp/x
  common::trap_add 'rm -rf "$WORK"'
  local path=/tmp/y
  common::trap_add "rm -rf -- $(printf '%q' "$path")"
  common::trap_add 'restore::cleanup'
}
# common::trap_add 'rm -rf "$staging"'
EOF
  out=$(trap_handlers_referencing_locals "$t/sample.sh")
  assert_contains "$out" "sample.sh:3: " "local 変数を参照する trap_add の handler を検出する"
  assert_contains "$out" "sample.sh:7: " "local 変数を参照する trap '…' EXIT を検出する"
  assert_eq 2 "$(grep -c . <<< "$out")" "global 変数・登録時に展開した handler・関数呼び出し・コメントは検出しない"
  rm -rf "$t"

  while IFS= read -r f; do files+=("$f"); done < <(production_scripts)
  out=$(trap_handlers_referencing_locals "${files[@]}")
  assert_eq "" "$out" "本番 script の EXIT trap の handler は local 変数を参照しない（登録時に展開するか global 変数を使う）"
}

# RB-W P3-2: common::trap_remove は指定した handler だけを外し、ほかの handler と後から登録する handler は動く。
# 外した handler は、後の common::trap_add で dispatcher が戻っても評価されない（trap - EXIT では配列に残って戻っていた）。
case_common_trap_remove_keeps_other_handlers() {
  local out rc
  out=$(bash -c '
    set -Eeuo pipefail
    . "$1/common.sh"
    common::trap_add "echo A"
    common::trap_add "echo B"
    common::trap_remove "echo A"
    common::trap_add "echo C"
    exit 0' _ "$LIB" 2>&1)
  assert_eq $'B\nC' "$out" "外した handler だけが動かず、残りと後から登録した handler は登録順に動く"

  out=$(bash -c '
    set -Eeuo pipefail
    . "$1/common.sh"
    common::trap_add "echo staging-removed"
    common::trap_remove "echo staging-removed"
    printf "trap=[%s]\n" "$(trap -p EXIT)"
    common::trap_add "echo later"
    exit 0' _ "$LIB" 2>&1)
  assert_contains "$out" "trap=[]" "handler が無くなれば dispatcher も外す"
  assert_not_contains "$out" "staging-removed" "外した handler は後の trap_add で dispatcher が戻っても評価されない"
  assert_contains "$out" "later" "後から登録した handler は動く"

  bash -c '. "$1/common.sh"; common::trap_add "echo X"; common::trap_remove "echo Y"' _ "$LIB" > /dev/null 2>&1
  rc=$?
  assert_nonzero "$rc" "登録していない handler を外そうとすると失敗を返す"
}

# RB-W P3-7: dump の起点（CHANGE REPLICATION SOURCE TO の SOURCE_LOG_FILE / SOURCE_LOG_POS）と GTID_PURGED を読む実装は
# lib/binlog.sh の 1 つだけにする（以前は backup-full.sh にも同じ実装があり、片方だけを直す恐れがあった）。
# 行全体のコメントは見ない。backup-full.sh がその実装を呼ぶことも確かめる。
case_dump_coordinate_parser_has_one_implementation() {
  local f out
  local -a files=()
  while IFS= read -r f; do files+=("$f"); done < <(production_scripts)
  out=$(awk '/^[[:space:]]*#/ { next } /SOURCE_LOG_FILE|GTID_PURGED/ { print FILENAME }' "${files[@]}" | LC_ALL=C sort -u)
  assert_eq "$LIB/binlog.sh" "$out" "dump の起点と GTID を読む実装は lib/binlog.sh だけにある"
  if grep -q '^[^#]*binlog::parse_dump_coords "\$dump_file"' "$BACKUP_ROOT/backup-full.sh" \
      && grep -q '^[^#]*binlog::parse_dump_gtid "\$dump_file"' "$BACKUP_ROOT/backup-full.sh"; then
    test_assert "backup-full.sh は lib/binlog.sh の実装を呼ぶ"
  else
    test_fail "backup-full.sh は lib/binlog.sh の実装を呼ぶ" "binlog::parse_dump_coords / binlog::parse_dump_gtid の呼び出しがありません"
  fi
}

# RB-W P3-2: common::trap_add を使う本番 script は trap - EXIT を書かない（dispatcher だけが外れ、配列に残った handler が
# 後で戻るため）。common.sh（trap_remove の実装）だけを除く。行全体のコメントは見ない。
trap_clears_outside_common() { # <file>...
  awk '/^[[:space:]]*#/ { next }
       /(^|[;&|[:space:]])trap[[:space:]]+(--[[:space:]]+)?-[[:space:]]+(EXIT|0)([[:space:]]|;|$)/ { print FILENAME ":" FNR }' "$@"
}

case_trap_add_users_do_not_clear_with_trap_minus_exit() {
  local t out f
  local -a files=()
  t=$(mktemp -d)
  cat > "$t/sample.sh" <<'EOF'
  trap - EXIT
x; trap -- - EXIT
  # trap - EXIT
  common::trap_remove "$h"
  trap '' INT
EOF
  out=$(trap_clears_outside_common "$t/sample.sh")
  assert_eq $'sample.sh:1\nsample.sh:2' "${out//"$t/"/}" "trap - EXIT と trap -- - EXIT を検出し、コメント・trap_remove・ほかの signal は検出しない"
  rm -rf "$t"

  while IFS= read -r f; do
    [[ "$f" == "$LIB/common.sh" ]] || files+=("$f")
  done < <(production_scripts)
  out=$(trap_clears_outside_common "${files[@]}")
  assert_eq "" "$out" "本番 script は trap - EXIT を書かず common::trap_remove で handler を外す"
}

# mysqldump の起動（"$MYSQLDUMP_BIN" / ${MYSQLDUMP_BIN} / 行頭・;・&・|・( の後の mysqldump。--version を除く）を、行末の \ で
# 続く行をつないだ 1 つの command として見て、--no-tablespaces の有無を出力する（OK|NG <file>:<開始行>）。行全体のコメントは見ない。
mysqldump_invocations() { # <file>...
  awk '
    function check(cmd, start) {
      if (cmd !~ /("[$][{]?MYSQLDUMP_BIN[}]?"|[$][{]?MYSQLDUMP_BIN[}]?)[[:space:]]/ &&
          cmd !~ /(^|[;&|(])[[:space:]]*mysqldump[[:space:]]/) return
      if (cmd ~ /[[:space:]]--version([[:space:]]|[)]|$)/) return
      print ((cmd ~ /[[:space:]]--no-tablespaces([[:space:]]|$)/) ? "OK " : "NG ") FILENAME ":" start
    }
    FNR == 1 { if (buf != "") check(buf, bstart); buf = "" }
    buf == "" && /^[[:space:]]*#/ { next }
    {
      line = $0
      if (buf == "") bstart = FNR
      if (line ~ /\\$/) { sub(/\\$/, "", line); buf = buf line " "; next }
      buf = buf line
      check(buf, bstart)
      buf = ""
    }
    END { if (buf != "") check(buf, bstart) }' "$@"
}

# RB-W（2026-11-18 利用者決定）: 本番 script の mysqldump はすべて --no-tablespaces を付ける（source 側の account に PROCESS を
# 与えないため）。走査器そのものも見本で確かめる。
case_mysqldump_invocations_use_no_tablespaces() {
  local t out f
  local -a files=()
  t=$(mktemp -d)
  cat > "$t/sample.sh" <<'EOF'
"$MYSQLDUMP_BIN" "${MYSQL_OPT_ARGS[@]}" \
  --single-transaction --no-tablespaces db > out
"$MYSQLDUMP_BIN" --single-transaction db > out
mysqldump --single-transaction \
  --routines db > out
x=$(mysqldump --version)
"$MYSQLDUMP_BIN" --version
MYSQLDUMP_BIN=${MYSQLDUMP_BIN:-mysqldump}
echo "mysqldump が失敗しました: $MYSQLDUMP_BIN"
# "$MYSQLDUMP_BIN" --single-transaction db
${MYSQLDUMP_BIN} --no-tablespaces db > out
EOF
  out=$(mysqldump_invocations "$t/sample.sh")
  assert_contains "$out" "OK $t/sample.sh:1" "--no-tablespaces を付けた起動（行の継続を含む）を OK と判定する"
  assert_contains "$out" "NG $t/sample.sh:3" "--no-tablespaces の無い起動を検出する"
  assert_contains "$out" "NG $t/sample.sh:4" "--no-tablespaces の無い起動（行の継続）を検出する"
  assert_contains "$out" "OK $t/sample.sh:11" "\${MYSQLDUMP_BIN} の起動も見る"
  assert_eq 4 "$(grep -c . <<< "$out")" "--version・変数の代入・文字列・コメントは起動として数えない"
  rm -rf "$t"

  while IFS= read -r f; do files+=("$f"); done < <(production_scripts)
  out=$(mysqldump_invocations "${files[@]}")
  assert_contains "$out" "OK $BACKUP_ROOT/backup-full.sh:" "backup-full.sh の mysqldump を走査した（前提）"
  assert_eq "" "$(grep '^NG ' <<< "$out" || true)" "本番 script の mysqldump はすべて --no-tablespaces を付ける"
}

# 上の --no-tablespaces の前提: production に入る migration（db/migration・db/migration-prod の SQL と、src/main/java/db/migration
# の Java migration）は general tablespace を使わない（TABLESPACE を書かない。大文字・小文字を区別しない）。使い始めるときは
# dump の option と source 側の account の権限を見直す。backup unit は repository 全体を /repo に mount して動くため読める。
MIGRATION_DIRS=("$ROOT/src/main/resources/db/migration" "$ROOT/src/main/resources/db/migration-prod" "$ROOT/src/main/java/db/migration")

tablespace_mentions() { # <dir>... → TABLESPACE を含む file:行
  grep -rniE 'tablespace' "$@" 2>/dev/null || true
}

case_migrations_do_not_use_tablespace() {
  local t d n=0
  t=$(mktemp -d)
  mkdir -p "$t/m"
  printf '%s\n' 'CREATE TABLE t_a (id BIGINT PRIMARY KEY) ENGINE=InnoDB;' > "$t/m/V1__ok.sql"
  printf '%s\n' 'create tablespace ts1 add datafile '"'"'ts1.ibd'"'"' engine=InnoDB;' > "$t/m/V2__ts.sql"
  printf '%s\n' 'ALTER TABLE t_a TABLESPACE = ts1;' > "$t/m/V3__alter.sql"
  assert_eq 2 "$(tablespace_mentions "$t/m" | grep -c .)" "走査器は TABLESPACE を大文字・小文字によらず検出する（見本）"
  rm -rf "$t"

  for d in "${MIGRATION_DIRS[@]}"; do
    if [[ -d "$d" ]]; then
      n=$((n + $(find "$d" -type f | wc -l)))
    else
      test_fail "migration の directory がある" "$d がありません"
    fi
  done
  if (( n > 100 )); then
    test_assert "production に入る migration を走査する（${n} file）"
  else
    test_fail "production に入る migration を走査する" "file が少なすぎます（${n}）"
  fi
  assert_eq "" "$(tablespace_mentions "${MIGRATION_DIRS[@]}")" \
    "migration は TABLESPACE を使わない（mysqldump の --no-tablespaces の前提。使うなら dump の option と権限を見直す）"
}

# ------------------------------------------------------------ RB-QUIESCE BWQ-009（書込み静止の provider）

QUIESCE_SQL_DIR="$BACKUP_ROOT/providers/quiesce-db.d"
QUIESCE_ALLOWED_PROVIDERS=(quiesce-local quiesce-db quiesce-app-stopped)

# lib/quiesce.sh を読む script の「長い子 process」の起動（行末の \ で続く行をつないだ 1 つの command）が、保持 process の
# pipe の書き口を閉じる指定 {QUIESCE_HOLD_FD}>&- を持つかを出力する（OK|NG <file>:<開始行>。design.md §7.6・D3-P3-2）。
# 対象: mysqldump・restic・mysqlbinlog・snapshot-binlog・preflight・mysql client の "$…_BIN"、provider の "$PROVIDERS_DIR/…"、
# cp -a、子 process を起動する lib の関数（restic::・content_hash::collect・schema_fingerprint::compute・binlog::verify_checksum・
# manifest::build / finalize / verify）。関数の定義・代入・行全体のコメントは見ない。
long_child_invocations() { # <file>...
  awk '
    function check(cmd, start,   m) {
      m = 0
      if (cmd ~ /"[$](MYSQLDUMP_BIN|RESTIC_BIN|MYSQLBINLOG_BIN|SNAPSHOT_BINLOG_BIN|PREFLIGHT_BIN|MYSQL_CLIENT_BIN)"[[:space:]]/) m = 1
      if (cmd ~ /"[$]PROVIDERS_DIR\/[A-Za-z0-9_.-]+"[[:space:]]/) m = 1
      if (cmd ~ /(^|[[:space:];&|(])cp[[:space:]]+-a[[:space:]]/) m = 1
      if (cmd ~ /(^|[[:space:];&|($])(restic::[a-z_]+|content_hash::collect|schema_fingerprint::compute|binlog::verify_checksum|manifest::(build|finalize|verify))[[:space:]]/) m = 1
      if (!m) return
      if (cmd ~ /^[[:space:]]*[A-Za-z_:]+\(\)[[:space:]]*\{/) return
      print ((cmd ~ /[{]QUIESCE_HOLD_FD[}]>&-/) ? "OK " : "NG ") FILENAME ":" start
    }
    FNR == 1 { if (buf != "") check(buf, bstart); buf = "" }
    buf == "" && /^[[:space:]]*#/ { next }
    {
      line = $0
      if (buf == "") bstart = FNR
      if (line ~ /\\$/) { sub(/\\$/, "", line); buf = buf line " "; next }
      buf = buf line
      check(buf, bstart)
      buf = ""
    }
    END { if (buf != "") check(buf, bstart) }' "$@"
}

# 本番 script のうち lib/quiesce.sh を source するもの
quiesce_users() {
  local f
  while IFS= read -r f; do
    [[ "$f" == "$LIB/quiesce.sh" ]] && continue
    grep -q 'LIB_DIR/quiesce\.sh' "$f" 2>/dev/null && printf '%s\n' "$f"
  done < <(production_scripts)
  return 0
}

case_long_children_close_hold_fd() {
  local t out f
  local -a files=()
  t=$(mktemp -d)
  cat > "$t/sample.sh" <<'EOF'
"$MYSQLDUMP_BIN" "${MYSQL_OPT_ARGS[@]}" \
  --no-tablespaces db > out {QUIESCE_HOLD_FD}>&- &
"$RESTIC_BIN" backup "$payload" --json
cp -a "$a"/. "$b/"
x=$(content_hash::collect "$t" "${args[@]}" {QUIESCE_HOLD_FD}>&-)
manifest::verify "$r" "$r/manifest.json" \
  || common::fail "x"
RESTIC_BIN=${RESTIC_BIN:-restic}
# "$RESTIC_BIN" backup はコメント
echo "mysqldump が失敗しました: $MYSQLDUMP_BIN"
restic::ensure_repository() {
"$PROVIDERS_DIR/uploads-local.sh" snapshot a b c d {QUIESCE_HOLD_FD}>&-
EOF
  out=$(long_child_invocations "$t/sample.sh")
  assert_contains "$out" "OK $t/sample.sh:1" "書き口を閉じた起動（行の継続を含む）を OK と判定する"
  assert_contains "$out" "NG $t/sample.sh:3" "書き口を閉じない restic を検出する"
  assert_contains "$out" "NG $t/sample.sh:4" "書き口を閉じない cp -a を検出する"
  assert_contains "$out" "OK $t/sample.sh:5" "lib の関数の起動も見る"
  assert_contains "$out" "NG $t/sample.sh:6" "書き口を閉じない manifest::verify（行の継続）を検出する"
  assert_contains "$out" "OK $t/sample.sh:12" "provider の起動も見る"
  assert_eq 6 "$(grep -c . <<< "$out")" "代入・コメント・文字列・関数の定義は起動として数えない"
  rm -rf "$t"

  while IFS= read -r f; do files+=("$f"); done < <(quiesce_users)
  assert_contains "${files[*]}" "$BACKUP_ROOT/backup-full.sh" "backup-full.sh は lib/quiesce.sh を読む（前提）"
  assert_contains "${files[*]}" "$BACKUP_ROOT/create-checkpoint.sh" "create-checkpoint.sh は lib/quiesce.sh を読む（前提）"
  out=$(long_child_invocations "${files[@]}")
  assert_contains "$out" "OK $BACKUP_ROOT/backup-full.sh:" "backup-full.sh の長い子 process を走査した（前提）"
  assert_eq "" "$(grep '^NG ' <<< "$out" || true)" \
    "lib/quiesce.sh を読む script の長い子 process は {QUIESCE_HOLD_FD}>&- で起動する（job が kill されたら保持 process が終わる）"
}

# provider は列挙した 3 つだけ（lib/quiesce.sh が振り分け、providers/ に他の quiesce の provider を置かない）
case_quiesce_providers_are_enumerated() {
  local f name p ok out rc=0
  for f in "$BACKUP_ROOT"/providers/quiesce-*.sh; do
    [[ -f "$f" ]] || continue
    name=$(basename "$f" .sh)
    ok=0
    for p in "${QUIESCE_ALLOWED_PROVIDERS[@]}"; do [[ "$name" == "$p" ]] && ok=1; done
    if (( ok == 1 )); then
      test_assert "列挙した provider: $name"
    else
      test_fail "列挙した provider だけを置く" "$name は lib/quiesce.sh の列挙にありません"
    fi
  done
  assert_file "$BACKUP_ROOT/providers/quiesce-local.sh" "quiesce-local がある"
  assert_file "$BACKUP_ROOT/providers/quiesce-db.sh" "quiesce-db がある"
  out=$(QUIESCE_PROVIDER=quiesce-evil LIB_UNDER_TEST="$LIB" bash -c \
    '. "$LIB_UNDER_TEST/common.sh"; . "$LIB_UNDER_TEST/quiesce.sh"; quiesce::check_provider' 2>&1) || rc=$?
  assert_nonzero "$rc" "列挙の外の provider は拒否する"
  assert_contains "$out" "未知の provider" "列挙の外の provider を示す"
}

# 本番 script は任意の command 文字列を shell に渡さない（bash -c / sh -c を書かない。行全体のコメントは除く。README の規約）
case_production_scripts_do_not_use_shell_c() {
  local -a files=()
  local f out
  while IFS= read -r f; do files+=("$f"); done < <(production_scripts)
  out=$(awk '/^[[:space:]]*#/ { next } /(^|[^A-Za-z0-9_-])(bash|sh)[[:space:]]+-c([[:space:]]|$)/ { print FILENAME ":" FNR ": " $0 }' \
    "${files[@]}")
  assert_eq "" "$out" "本番 script は bash -c / sh -c を使わない"
}

# 信号表の SQL（providers/quiesce-db.d/*.sql）: 時刻は UTC_TIMESTAMP(6) だけ（NOW( 等を書かない。§3.10）、先頭行は fake の mysql が
# 見分ける印、文は行末の ; で終わる（Java の MySQL 契約試験が同じ規則で文に分けて実行する。行の途中の ; と行末のコメントを書かない）
case_quiesce_sql_files_contract() {
  local f name first n=0 bad all
  for f in "$QUIESCE_SQL_DIR"/*.sql; do
    [[ -f "$f" ]] || continue
    n=$((n + 1))
    name=$(basename "$f" .sql)
    first=$(head -n 1 "$f")
    assert_eq "-- ses-backup:quiesce-db:$name" "$first" "SQL の先頭行は印（$name）"
    bad=$(grep -niE 'NOW[[:space:]]*\(|CURRENT_TIMESTAMP|LOCALTIMESTAMP|SYSDATE|CURDATE|CURTIME|UNIX_TIMESTAMP' "$f" || true)
    assert_eq "" "$bad" "SQL は UTC_TIMESTAMP(6) の外の時刻の関数を使わない（$name）"
    bad=$(awk '/^[[:space:]]*--/ { next } /;/ && !/;[[:space:]]*$/ { print FNR ": " $0 } /[^-]--[[:space:]]/ { print FNR ": " $0 }' "$f")
    assert_eq "" "$bad" "SQL の文は行末の ; で終わり、行の途中に ; と -- を書かない（$name）"
    bad=$(grep -vE '^[[:space:]]*--' "$f" | grep -oE '@[A-Za-z_]+' | grep -vE '^@(p|v)_[a-z0-9_]+$' || true)
    assert_eq "" "$bad" "SQL の変数は前置きの @p_ と SQL の中の @v_ だけ（$name）"
  done
  if (( n >= 10 )); then
    test_assert "信号表の SQL を走査する（$n file）"
  else
    test_fail "信号表の SQL を走査する" "file が少なすぎます（$n）"
  fi
  all=$(cat "$QUIESCE_SQL_DIR"/*.sql)
  assert_contains "$all" "GET_LOCK('ses_backup_ddl_freeze'" "DDL 凍結の lock の名前は Flyway の callback と同じ"
}

# 2d review P2-1 の回帰の守り（最終 review P3-8）: quiesce_db::run_sql を command substitution（$( ) と ` `）の中で呼ぶと、
# 失敗の文（QDB_SQL_ERROR）が親の shell に届かない。provider・preflight・保持 process は quiesce_db::query で呼び、run_sql を
# 直接呼ぶのは lib/quiesce-db-common.sh（query の中）だけにする。行全体のコメントは見ない。
# 出力: <file>:<行番号>: <理由>
quiesce_run_sql_misuse() { # <file...>
  awk '
    /^[[:space:]]*#/ { next }
    /\$\([[:space:]]*quiesce_db::run_sql/ || /`[[:space:]]*quiesce_db::run_sql/ {
      print FILENAME ":" FNR ": command substitution の中の quiesce_db::run_sql"; next
    }
    /quiesce_db::run_sql/ && FILENAME !~ /\/lib\/quiesce-db-common\.sh$/ {
      print FILENAME ":" FNR ": lib/quiesce-db-common.sh の外の quiesce_db::run_sql"
    }
  ' "$@"
}

case_quiesce_run_sql_is_not_called_in_command_substitution() {
  local -a files=()
  local f out fx
  fx=$(mktemp -d)
  mkdir -p "$fx/lib" "$fx/providers"
  # 走査器の見本: 検出すべき書き方（command substitution の 3 つの形と、lib の外の直接の呼出し）と、検出しない書き方
  cat > "$fx/providers/bad.sh" <<'FIXTURE'
out=$(quiesce_db::run_sql precheck "int:x=1") || rc=$?
out=$( quiesce_db::run_sql renew "int:x=1" {ddl_fd}>&- ) || rc=$?
out=`quiesce_db::run_sql release "int:x=1"`
FIXTURE
  cat > "$fx/providers/good.sh" <<'FIXTURE'
quiesce_db::query out precheck "int:x=1" || rc=$?
# out=$(quiesce_db::run_sql precheck) はコメントなので数えない
quiesce_db::log "失敗: ${QDB_SQL_ERROR:-rc=$rc}"
FIXTURE
  cat > "$fx/lib/quiesce-db-common.sh" <<'FIXTURE'
  quiesce_db::run_sql "$@" > "$__qdb_out" || __qdb_rc=$?
FIXTURE
  cat > "$fx/providers/direct.sh" <<'FIXTURE'
quiesce_db::run_sql precheck > "$tmp"
FIXTURE
  out=$(quiesce_run_sql_misuse "$fx/providers/bad.sh" "$fx/providers/good.sh" "$fx/lib/quiesce-db-common.sh" \
    "$fx/providers/direct.sh")
  assert_eq 4 "$(grep -c . <<< "$out")" "走査器は command substitution の 3 つと lib の外の直接の呼出し 1 つを検出する（$out）"
  assert_not_contains "$out" "good.sh" "query・コメント・log は検出しない"
  assert_not_contains "$out" "$fx/lib/quiesce-db-common.sh:" "lib の中の query からの呼出しは検出しない"
  rm -rf "$fx"
  while IFS= read -r f; do files+=("$f"); done < <(production_scripts)
  out=$(quiesce_run_sql_misuse "${files[@]}")
  assert_eq "" "$out" "本番 script は quiesce_db::run_sql を command substitution の中で呼ばず、lib の外で直接呼ばない"
}

run_case case_long_children_close_hold_fd
run_case case_quiesce_run_sql_is_not_called_in_command_substitution
run_case case_quiesce_providers_are_enumerated
run_case case_production_scripts_do_not_use_shell_c
run_case case_quiesce_sql_files_contract
run_case case_pipefail_scanner_detects_each_form
run_case case_pipefail_production_scripts_have_no_early_exit_reader_after_pipe
run_case case_pipefail_binlog_size_from_logs_with_large_list
run_case case_pipefail_validate_db_check_tables_with_large_output
run_case case_mysql84_no_removed_syntax
run_case case_trap_handlers_do_not_reference_function_locals
run_case case_dump_coordinate_parser_has_one_implementation
run_case case_common_trap_remove_keeps_other_handlers
run_case case_trap_add_users_do_not_clear_with_trap_minus_exit
run_case case_mysqldump_invocations_use_no_tablespaces
run_case case_migrations_do_not_use_tablespace

test_summary "本番 script の横断的な契約（pipefail と SIGPIPE・MySQL 8.4・EXIT trap・mysqldump の option）"
