#!/usr/bin/env bash
# G-04（RB-W W-1）schema fingerprint の正規化規則（lib/schema-fingerprint.sh）
#
# - 行の並び（server が返す順序）に依存しない。期待値は算出方式名の 1 行 + LC_ALL=C で並べた行の SHA-256
#   （golden の計算を lib に頼らずに行う）
# - 列の並び順（序数）の変更・既定値の NULL と文字列 'NULL' の違いは fingerprint を変え、違う区分だけが変わる
# - 想定外の行・table 0 件・読めない routine / view 定義は計算を失敗させる（fail-closed）
# - 取得する項目の規則: コメント・統計・日時・権限は含めず、列の序数・型・既定値・索引・制約・trigger・
#   routine・view の定義は含める。schema 名は含めない（同じ schema の参照先は '=' に正規化）
#   （実 MySQL での確認は integration-pitr.sh の「schema fingerprint の正規化規則」）
# - compute / verify: fake mysql の応答から JSON を作り、一致は 0、不一致は違う区分を通知して非 0
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
LIB="$ROOT/ops/backup/lib"
FIXTURES="$HERE/fixtures/bin"
ALGORITHM=mysql-information-schema-sha256-v1

# shellcheck disable=SC1091
. "$LIB/schema-fingerprint.sh"

ROW_TABLE=$'table\t\'t_a\'\t\'BASE TABLE\'\t\'InnoDB\'\t\'utf8mb4_0900_ai_ci\'\t\'\''
ROW_COL1=$'column\t\'t_a\'\t\'1\'\t\'id\'\t\'bigint\'\t\'NO\'\tNULL\tNULL\tNULL\t\'auto_increment\'\t\'\'\tNULL'
ROW_COL2=$'column\t\'t_a\'\t\'2\'\t\'name\'\t\'varchar(64)\'\t\'YES\'\tNULL\t\'utf8mb4\'\t\'utf8mb4_0900_ai_ci\'\t\'\'\t\'\'\tNULL'
ROW_INDEX=$'index\t\'t_a\'\t\'PRIMARY\'\t\'0\'\t\'1\'\t\'id\'\tNULL\t\'A\'\t\'BTREE\'\tNULL\t\'YES\''
# 序数だけが違う列（id の序数 1 → 3）
ROW_COL1_ORD3=$'column\t\'t_a\'\t\'3\'\t\'id\'\t\'bigint\'\t\'NO\'\tNULL\tNULL\tNULL\t\'auto_increment\'\t\'\'\tNULL'
ROW_TRIGGER=$'trigger\t\'trg_a\'\t\'t_a\'\t\'INSERT\'\t\'BEFORE\'\t\'1\'\t\'SET NEW.name = TRIM(NEW.name)\'\t\'STRICT_TRANS_TABLES\'\t\'app@%\'\t\'utf8mb4\'\t\'utf8mb4_0900_ai_ci\''
ROW_ROUTINE=$'routine\t\'FUNCTION\'\t\'f_a\'\t\'RETURN x + 1\'\t\'int\'\t\'YES\'\t\'NO SQL\'\t\'DEFINER\'\t\'\'\t\'app@%\'\t\'utf8mb4\'\t\'utf8mb4_0900_ai_ci\''

digest_of() { # <rows...> → schema_fingerprint::digest の出力（失敗時は DIGEST_RC が非 0）
  DIGEST_OUT=$(printf '%s\n' "$@" | schema_fingerprint::digest 2>&1)
  DIGEST_RC=$?
}

sha_of() { printf '%s\n' "$1" | sed -n 's/^sha256=//p'; }
section_of() { printf '%s\n' "$1" | grep "^section=$2 "; }

case_schema_fp_golden_and_row_order() {
  local expected sorted
  digest_of "$ROW_TRIGGER" "$ROW_COL2" "$ROW_TABLE" "$ROW_INDEX" "$ROW_COL1" "$ROW_ROUTINE"
  assert_zero "$DIGEST_RC" "正しい行は計算できる"
  sorted=$(printf '%s\n' "$ROW_TRIGGER" "$ROW_COL2" "$ROW_TABLE" "$ROW_INDEX" "$ROW_COL1" "$ROW_ROUTINE" | LC_ALL=C sort)
  expected=$(printf '%s\n%s\n' "$ALGORITHM" "$sorted" | sha256sum | awk '{print $1}')
  assert_eq "$expected" "$(sha_of "$DIGEST_OUT")" "算出方式名 1 行 + LC_ALL=C で並べた行の SHA-256（golden）"
  local first=$DIGEST_OUT
  digest_of "$ROW_COL1" "$ROW_ROUTINE" "$ROW_INDEX" "$ROW_TABLE" "$ROW_TRIGGER" "$ROW_COL2"
  assert_eq "$(sha_of "$first")" "$(sha_of "$DIGEST_OUT")" "server が返す行の順序が違っても同じ値"
  assert_contains "$(section_of "$DIGEST_OUT" column)" "count=2" "区分ごとの件数（column=2）"
  assert_contains "$(section_of "$DIGEST_OUT" view)" "count=0" "行の無い区分は件数 0"
  assert_eq "11" "$(printf '%s\n' "$DIGEST_OUT" | grep -c '^section=')" "11 区分すべてを出力"
  if [[ "$(sha_of "$DIGEST_OUT")" != "$(printf '%s\n' "$sorted" | sha256sum | awk '{print $1}')" ]]; then
    test_assert "算出方式名を hash に含める（方式の違う値と衝突しない）"
  else
    test_fail "算出方式名を hash に含める（方式の違う値と衝突しない）" "行だけの SHA-256 と同じです"
  fi
}

# 列の並び順（序数）は SELECT * や列名の無い INSERT の意味を変えるため fingerprint に含める
case_schema_fp_column_order_and_null_default() {
  local base swapped_col1 swapped_col2 null_string
  digest_of "$ROW_TABLE" "$ROW_COL1" "$ROW_COL2" "$ROW_INDEX"
  base=$DIGEST_OUT
  swapped_col1=${ROW_COL1/$'\t\'1\'\t'/$'\t\'2\'\t'}
  swapped_col2=${ROW_COL2/$'\t\'2\'\t'/$'\t\'1\'\t'}
  digest_of "$ROW_TABLE" "$swapped_col1" "$swapped_col2" "$ROW_INDEX"
  assert_zero "$DIGEST_RC" "並び順を変えた行も計算できる"
  if [[ "$(sha_of "$base")" != "$(sha_of "$DIGEST_OUT")" ]]; then
    test_assert "列の並び順の変更で fingerprint が変わる"
  else
    test_fail "列の並び順の変更で fingerprint が変わる" "同じ値です"
  fi
  if [[ "$(section_of "$base" column)" != "$(section_of "$DIGEST_OUT" column)" ]]; then
    test_assert "column 区分が変わる"
  else
    test_fail "column 区分が変わる" "同じ値です"
  fi
  assert_eq "$(section_of "$base" table)" "$(section_of "$DIGEST_OUT" table)" "table 区分は変わらない"
  assert_eq "$(section_of "$base" index)" "$(section_of "$DIGEST_OUT" index)" "index 区分は変わらない"
  # 既定値: SQL の NULL（囲まれない NULL）と文字列 'NULL'（QUOTE で囲まれる）を区別する
  null_string=${ROW_COL2/$'\'YES\'\tNULL\t'/$'\'YES\'\t\'NULL\'\t'}
  digest_of "$ROW_TABLE" "$ROW_COL1" "$null_string" "$ROW_INDEX"
  if [[ "$(sha_of "$base")" != "$(sha_of "$DIGEST_OUT")" ]]; then
    test_assert "既定値の NULL と文字列 'NULL' を区別する"
  else
    test_fail "既定値の NULL と文字列 'NULL' を区別する" "同じ値です"
  fi
}

case_schema_fp_rejects_unexpected_output() {
  digest_of "$ROW_TABLE" $'events\t\'e1\''
  assert_nonzero "$DIGEST_RC" "未知の区分は失敗"
  assert_contains "$DIGEST_OUT" "想定外の行があります（区分: events）" "未知の区分を通知"
  digest_of "$ROW_TABLE" "ERROR 1142 (42000): SELECT command denied"
  assert_nonzero "$DIGEST_RC" "区分の無い行は失敗"
  digest_of "$ROW_COL1" "$ROW_INDEX"
  assert_nonzero "$DIGEST_RC" "table が 1 つも無ければ失敗"
  assert_contains "$DIGEST_OUT" "table が 1 つも見えません" "table 0 件を通知"
  DIGEST_OUT=$(printf '' | schema_fingerprint::digest 2>&1)
  DIGEST_RC=$?
  assert_nonzero "$DIGEST_RC" "空の出力は失敗"
  digest_of "$ROW_TABLE" $'routine\t\'PROCEDURE\'\t\'p_a\'\tNULL\tNULL\t\'NO\'\t\'MODIFIES SQL DATA\'\t\'DEFINER\'\t\'\'\t\'app@%\'\t\'utf8mb4\'\t\'utf8mb4_0900_ai_ci\''
  assert_nonzero "$DIGEST_RC" "本体が NULL の routine（権限不足）は失敗"
  assert_contains "$DIGEST_OUT" "SHOW_ROUTINE" "必要な権限を通知"
  digest_of "$ROW_TABLE" $'view\t\'v_a\'\t\'\'\t\'NONE\'\t\'app@%\'\t\'DEFINER\'\t\'utf8mb4\'\t\'utf8mb4_0900_ai_ci\''
  assert_nonzero "$DIGEST_RC" "定義が空の view（権限不足）は失敗"
  assert_contains "$DIGEST_OUT" "SHOW VIEW" "必要な権限を通知"
}

# 取得する項目の規則（SQL）。コメント・統計・日時・権限は取得しない = それらだけの変更では値が変わらない
case_schema_fp_sql_rule() {
  local sql word
  sql=$(schema_fingerprint::sql)
  assert_eq "11" "$(printf '%s\n' "$sql" | grep -c '^SELECT ')" "11 区分を 1 回の実行で取得する"
  assert_eq "11" "$(printf '%s\n' "$sql" | grep -cE 'FROM information_schema\.[a-z_]+ WHERE [a-z_]+_schema = DATABASE\(\)')" \
    "全区分を接続先の DATABASE() に限定する"
  for word in ordinal_position column_type is_nullable column_default character_set_name collation_name extra \
      generation_expression srs_id index_name non_unique seq_in_index sub_part index_type expression is_visible \
      constraint_type enforced referenced_table_name referenced_column_name match_option update_rule delete_rule \
      check_clause action_statement action_order action_timing event_manipulation sql_mode definer \
      routine_definition dtd_identifier is_deterministic sql_data_access security_type parameter_mode \
      view_definition check_option engine table_collation create_options; do
    if printf '%s\n' "$sql" | grep -qw "$word"; then
      test_assert "取得する: $word"
    else
      test_fail "取得する: $word" "SQL に $word がありません"
    fi
  done
  for word in column_comment table_comment index_comment routine_comment comment auto_increment table_rows \
      data_length index_length avg_row_length cardinality create_time update_time check_time created \
      last_altered privileges column_key row_format database_collation; do
    if printf '%s\n' "$sql" | grep -qiw "$word"; then
      test_fail "取得しない: $word" "SQL に $word があります"
    else
      test_assert "取得しない: $word"
    fi
  done
  assert_contains "$sql" "IF(referenced_table_schema = DATABASE(), '=', referenced_table_schema)" \
    "外部キーの参照先が同じ schema なら '=' に正規化する"
  assert_contains "$sql" "REPLACE(view_definition, CONCAT('\`', DATABASE(), '\`.'), '\`=\`.')" \
    "view 定義中の schema 名を正規化する"
  assert_contains "$sql" "IF(\`column_default\` IS NULL, 'NULL', QUOTE(\`column_default\`))" "NULL と値を区別して取得する"
  assert_contains "$sql" "QUOTE(\`collation\`)" "keyword と同じ列名は \` で囲む"
}

case_schema_fp_compute_and_verify() {
  local t recorded rc out
  t=$(mktemp -d)
  export TMPDIR="$t"
  MYSQL_CLIENT_BIN="$FIXTURES/mysql"
  export FAKE_ARGV_LOG="$t/argv.log"
  export FAKE_SCHEMA_ROWS="$ROW_TABLE\n$ROW_COL1\n$ROW_COL2\n$ROW_INDEX\n"
  unset FAKE_SCHEMA_RC
  recorded=$(schema_fingerprint::compute --defaults-extra-file=/dev/null probe_db)
  rc=$?
  assert_zero "$rc" "compute は 0"
  assert_eq "$ALGORITHM" "$(jq -r '.algorithm' <<< "$recorded" 2>/dev/null)" "算出方式を記録"
  digest_of "$ROW_TABLE" "$ROW_COL1" "$ROW_COL2" "$ROW_INDEX"
  assert_eq "$(sha_of "$DIGEST_OUT")" "$(jq -r '.sha256' <<< "$recorded" 2>/dev/null)" "digest と同じ SHA-256"
  assert_eq "2" "$(jq -r '.sections.column.count' <<< "$recorded" 2>/dev/null)" "区分ごとの件数"
  assert_contains "$(head -n 1 "$FAKE_ARGV_LOG" 2>/dev/null)" "probe_db --quick -N -B --execute" "接続 option と DB 名を渡す"

  out=$(schema_fingerprint::verify "$recorded" probe_db 2>&1)
  assert_zero "$?" "同じ schema は一致"
  export FAKE_SCHEMA_ROWS="$ROW_TABLE\n$ROW_COL1_ORD3\n$ROW_COL2\n$ROW_INDEX\n"
  out=$(schema_fingerprint::verify "$recorded" probe_db 2>&1)
  assert_nonzero "$?" "違う schema は不一致"
  assert_contains "$out" "差異のある区分: column" "違う区分だけを通知"
  out=$(schema_fingerprint::verify '"not-object"' probe_db 2>&1)
  assert_nonzero "$?" "JSON object でない記録は失敗"
  out=$(schema_fingerprint::verify "$(jq -c '.algorithm = "other-v0"' <<< "$recorded")" probe_db 2>&1)
  assert_nonzero "$?" "算出方式の違う記録は失敗"
  assert_contains "$out" "算出方式が異なります" "算出方式の違いを通知"
  export FAKE_SCHEMA_RC=1
  out=$(schema_fingerprint::compute probe_db 2>&1)
  assert_nonzero "$?" "mysql の失敗は compute の失敗"
  assert_contains "$out" "information_schema を取得できません" "取得失敗を通知"
  unset FAKE_SCHEMA_RC FAKE_SCHEMA_ROWS FAKE_ARGV_LOG
  rm -rf "$t"
}

run_case case_schema_fp_golden_and_row_order
run_case case_schema_fp_column_order_and_null_default
run_case case_schema_fp_rejects_unexpected_output
run_case case_schema_fp_sql_rule
run_case case_schema_fp_compute_and_verify

test_summary "G-04 schema fingerprint"
