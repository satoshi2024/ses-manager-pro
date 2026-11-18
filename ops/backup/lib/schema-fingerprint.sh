#!/usr/bin/env bash
# ============================================================
# schema fingerprint（G-04 / RB-W W-1）
#
# 内容ハッシュ（lib/content-hash.sh）は主要 table の「行」しか見ないため、schema が backup 時と違う復元
# （table・列・索引・制約・trigger・routine・view の欠落や定義違い）を検出できない。backup 時（full /
# checkpoint の書込み静止区間内）に対象 DB の schema を information_schema から正規化して SHA-256 にかけ、
# metadata.json（manifest.json の entry として SHA-256 で固定される）と index に記録する。復元検証は
# 同じ方法で再計算し、記録が無い・算出方式が違う・一致しない場合は FAIL にする（照合を省略する設定は無い）。
#
# 正規化の規則（algorithm = mysql-information-schema-sha256-v1。README「schema fingerprint」と同じ）:
#   - 対象は接続先の DATABASE() の schema だけ。schema 名そのものは含めない（復元先の DB 名が違ってもよい）。
#     外部キーの参照先が同じ schema なら '=' に置き換え、view 定義中の `<schema>`. も `=`. に置き換える。
#   - 1 行 = 1 要素（区分名 + 項目）。各項目は IF(x IS NULL, 'NULL', QUOTE(x)) で文字列化し（SQL の NULL と
#     文字列 'NULL' を区別する）、mysql -N -B の出力（tab 区切り・改行等はエスケープ）を使う。
#   - 行は LC_ALL=C の sort で並べてから SHA-256 にかける（server が返す順序・照合順序に依存しない）。
#     先頭に算出方式名の 1 行を置く。区分ごとの件数と SHA-256 も記録し、不一致時の診断に使う。
#   - 含めるもの（動作や格納に影響する定義）:
#       table      : 名前、種別（BASE TABLE / VIEW）、engine、照合順序、CREATE_OPTIONS
#       column     : table、序数（列の並び順）、名前、COLUMN_TYPE、NULL 可否、既定値、文字集合、照合順序、
#                    EXTRA（auto_increment・DEFAULT_GENERATED・on update・生成列・INVISIBLE）、生成式、SRS_ID
#       index      : table、索引名、一意性、列内の序数、列名、prefix 長、昇降順、種別、関数索引の式、可視性
#       constraint : table、制約名、種別（PRIMARY KEY / UNIQUE / FOREIGN KEY / CHECK）、ENFORCED
#       fk_column  : 外部キーの列と参照先（schema・table・列）
#       fk_rule    : 外部キーの MATCH / ON UPDATE / ON DELETE
#       check      : CHECK 制約名と式
#       trigger    : 名前、table、event、timing、ACTION_ORDER、本体、SQL_MODE、DEFINER、client の文字集合・照合順序
#       routine    : 種別、名前、本体、戻り値の型、DETERMINISTIC、SQL DATA ACCESS、SQL SECURITY、SQL_MODE、
#                    DEFINER、client の文字集合・照合順序
#       parameter  : routine の引数（序数・IN/OUT・名前・型。function の戻り値は序数 0）
#       view       : 定義（schema 名を正規化）、CHECK OPTION、DEFINER、SQL SECURITY、client の文字集合・照合順序
#   - 含めないもの（動作に影響しない、または復元先の環境・統計で変わる値）:
#       コメント（table・列・索引・routine）、AUTO_INCREMENT の現在値、行数・容量などの統計、CARDINALITY、
#       作成・更新日時、PRIVILEGES（照会した利用者の権限で変わる）、COLUMN_KEY（索引の区分から導出）、
#       schema の既定の文字集合・照合順序と routine / trigger の DATABASE_COLLATION、ROW_FORMAT の実値
#       （明示した値は CREATE_OPTIONS に含まれる）、event、partition の詳細（CREATE_OPTIONS の partitioned だけ）。
#     したがって列コメントだけの変更では fingerprint は変わらず、列の並び順の変更では変わる。
#   - 次の場合は計算を失敗させる（fail-closed）: table が 1 つも見えない、routine の本体が NULL（SHOW_ROUTINE
#     権限が無い）、view の定義が空（SHOW VIEW 権限が無い）、想定外の行、mysql の失敗。
#   - backup 時と復元検証時で MySQL server の版や lower_case_table_names が違い、information_schema の表示が
#     変わる場合は不一致になる（fail-closed）。trigger は TRIGGER 権限の無い table の分が見えないため、
#     権限不足は件数の違い（不一致）として現れる。
# 呼び出し側は MYSQL_CLIENT_BIN を設定し、接続 option（option file・host・DB 名）を引数で渡す。
# ============================================================

SCHEMA_FINGERPRINT_ALGORITHM=mysql-information-schema-sha256-v1
# 区分の並び（記録・診断の順序。行の並びは sort で決まるため hash には影響しない）
SCHEMA_FINGERPRINT_SECTIONS=(table column index constraint fk_column fk_rule check trigger routine parameter view)

# IF(x IS NULL, 'NULL', QUOTE(x))。列名だけの場合は `列名` にする（collation・engine などの keyword と区別する）
schema_fingerprint::q() {
  local x=$1
  [[ "$x" =~ ^[a-z_]+$ ]] && x="\`$x\`"
  printf "IF(%s IS NULL, 'NULL', QUOTE(%s))" "$x" "$x"
}

# 1 回の mysql 実行で全区分を取得する SQL（区分名を先頭の列に置く）
schema_fingerprint::sql() {
  local q=schema_fingerprint::q
  printf '%s\n' "/* ses-backup:schema-fingerprint */"
  printf "SELECT 'table', %s, %s, %s, %s, %s FROM information_schema.tables WHERE table_schema = DATABASE();\n" \
    "$($q table_name)" "$($q table_type)" "$($q engine)" "$($q table_collation)" "$($q create_options)"
  printf "SELECT 'column', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s FROM information_schema.columns WHERE table_schema = DATABASE();\n" \
    "$($q table_name)" "$($q ordinal_position)" "$($q column_name)" "$($q column_type)" "$($q is_nullable)" \
    "$($q column_default)" "$($q character_set_name)" "$($q collation_name)" "$($q extra)" \
    "$($q generation_expression)" "$($q srs_id)"
  printf "SELECT 'index', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s FROM information_schema.statistics WHERE table_schema = DATABASE();\n" \
    "$($q table_name)" "$($q index_name)" "$($q non_unique)" "$($q seq_in_index)" "$($q column_name)" \
    "$($q sub_part)" "$($q collation)" "$($q index_type)" "$($q expression)" "$($q is_visible)"
  printf "SELECT 'constraint', %s, %s, %s, %s FROM information_schema.table_constraints WHERE constraint_schema = DATABASE();\n" \
    "$($q table_name)" "$($q constraint_name)" "$($q constraint_type)" "$($q enforced)"
  printf "SELECT 'fk_column', %s, %s, %s, %s, %s, %s, %s FROM information_schema.key_column_usage WHERE constraint_schema = DATABASE() AND referenced_table_name IS NOT NULL;\n" \
    "$($q table_name)" "$($q constraint_name)" "$($q ordinal_position)" "$($q column_name)" \
    "$($q "IF(referenced_table_schema = DATABASE(), '=', referenced_table_schema)")" \
    "$($q referenced_table_name)" "$($q referenced_column_name)"
  printf "SELECT 'fk_rule', %s, %s, %s, %s, %s FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE();\n" \
    "$($q table_name)" "$($q constraint_name)" "$($q match_option)" "$($q update_rule)" "$($q delete_rule)"
  printf "SELECT 'check', %s, %s FROM information_schema.check_constraints WHERE constraint_schema = DATABASE();\n" \
    "$($q constraint_name)" "$($q check_clause)"
  printf "SELECT 'trigger', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s FROM information_schema.triggers WHERE trigger_schema = DATABASE();\n" \
    "$($q trigger_name)" "$($q event_object_table)" "$($q event_manipulation)" "$($q action_timing)" \
    "$($q action_order)" "$($q action_statement)" "$($q sql_mode)" "$($q definer)" \
    "$($q character_set_client)" "$($q collation_connection)"
  # routine の本体（第 4 欄）が NULL なら読めていない（schema_fingerprint::digest が失敗させる）
  printf "SELECT 'routine', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s FROM information_schema.routines WHERE routine_schema = DATABASE();\n" \
    "$($q routine_type)" "$($q routine_name)" "$($q routine_definition)" "$($q dtd_identifier)" \
    "$($q is_deterministic)" "$($q sql_data_access)" "$($q security_type)" "$($q sql_mode)" "$($q definer)" \
    "$($q character_set_client)" "$($q collation_connection)"
  printf "SELECT 'parameter', %s, %s, %s, %s, %s, %s FROM information_schema.parameters WHERE specific_schema = DATABASE();\n" \
    "$($q routine_type)" "$($q specific_name)" "$($q ordinal_position)" "$($q parameter_mode)" \
    "$($q parameter_name)" "$($q dtd_identifier)"
  # view の定義（第 3 欄）が空なら読めていない
  printf "SELECT 'view', %s, %s, %s, %s, %s, %s, %s FROM information_schema.views WHERE table_schema = DATABASE();\n" \
    "$($q table_name)" "$($q "REPLACE(view_definition, CONCAT('\`', DATABASE(), '\`.'), '\`=\`.')")" \
    "$($q check_option)" "$($q definer)" "$($q security_type)" "$($q character_set_client)" "$($q collation_connection)"
}

# mysql -N -B の出力（標準入力）を検査・正規化し、次の形式で出力する:
#   sha256=<16 進 64 桁>
#   section=<区分> count=<件数> sha256=<16 進 64 桁>   （SCHEMA_FINGERPRINT_SECTIONS の順）
# 想定外の行・table 0 件・読めない routine / view 定義は失敗（1）。
schema_fingerprint::digest() {
  local sorted bad section count sum part
  sorted=$(sed '/^$/d' | LC_ALL=C sort) || return 1
  # 区分名 + tab で始まらない行（区分の無い行・未知の区分）は想定外の出力として失敗させる
  bad=$(printf '%s\n' "$sorted" | awk -F'\t' -v known="${SCHEMA_FINGERPRINT_SECTIONS[*]}" '
    BEGIN { n = split(known, names, " "); for (i = 1; i <= n; i++) ok[names[i]] = 1 }
    NF > 0 && (NF < 2 || !($1 in ok)) { print substr($1, 1, 32); exit }')
  if [[ -n "$bad" ]]; then
    echo "schema-fingerprint: 想定外の行があります（区分: $bad）" >&2
    return 1
  fi
  if ! printf '%s\n' "$sorted" | grep -q $'^table\t'; then
    echo "schema-fingerprint: table が 1 つも見えません（DB 名と、利用者の SELECT 権限を確認してください）" >&2
    return 1
  fi
  if printf '%s\n' "$sorted" | awk -F'\t' '$1 == "routine" && $4 == "NULL" { found = 1 } END { exit found ? 0 : 1 }'; then
    echo "schema-fingerprint: routine の本体を読めません（照会する利用者に SHOW_ROUTINE 権限が必要です）" >&2
    return 1
  fi
  if printf '%s\n' "$sorted" | awk -F'\t' -v empty="''" '$1 == "view" && ($3 == empty || $3 == "NULL") { found = 1 } END { exit found ? 0 : 1 }'; then
    echo "schema-fingerprint: view の定義を読めません（照会する利用者に対象 DB の SHOW VIEW 権限が必要です）" >&2
    return 1
  fi
  sum=$(printf '%s\n%s\n' "$SCHEMA_FINGERPRINT_ALGORITHM" "$sorted" | sha256sum) || return 1
  sum=${sum%% *}
  [[ "$sum" =~ ^[0-9a-f]{64}$ ]] || { echo "schema-fingerprint: SHA-256 を計算できません" >&2; return 1; }
  printf 'sha256=%s\n' "$sum"
  for section in "${SCHEMA_FINGERPRINT_SECTIONS[@]}"; do
    part=$(printf '%s\n' "$sorted" | grep "^$section"$'\t' || true)
    if [[ -n "$part" ]]; then
      count=$(printf '%s\n' "$part" | wc -l)
      count=${count//[[:space:]]/}
      sum=$(printf '%s\n' "$part" | sha256sum)
    else
      count=0
      sum=$(printf '' | sha256sum)
    fi
    printf 'section=%s count=%s sha256=%s\n' "$section" "$count" "${sum%% *}"
  done
}

# 接続先の schema の fingerprint を JSON object で出力する:
#   {"algorithm": ..., "sha256": ..., "sections": {"table": {"count": N, "sha256": ...}, ...}}
schema_fingerprint::compute() { # <mysql args...>
  local err out digest rc=0 json line name count sum
  err=$(mktemp "${TMPDIR:-/tmp}/ses-schema-fp.XXXXXX") || return 1
  out=$("$MYSQL_CLIENT_BIN" "$@" --quick -N -B --execute "$(schema_fingerprint::sql)" 2> "$err") || rc=$?
  if (( rc != 0 )); then
    local detail=""
    [[ -s "$err" ]] && detail=$(head -c 300 "$err" | tr '\n' ' ')
    rm -f "$err"
    echo "schema-fingerprint: information_schema を取得できません${detail:+（$detail）}" >&2
    return 1
  fi
  rm -f "$err"
  digest=$(printf '%s\n' "$out" | schema_fingerprint::digest) || return 1
  sum=$(printf '%s\n' "$digest" | sed -n 's/^sha256=//p')
  json=$(jq -n -c --arg a "$SCHEMA_FINGERPRINT_ALGORITHM" --arg s "$sum" '{algorithm: $a, sha256: $s, sections: {}}') || return 1
  while IFS= read -r line; do
    [[ "$line" =~ ^section=([a-z_]+)\ count=([0-9]+)\ sha256=([0-9a-f]{64})$ ]] || continue
    name=${BASH_REMATCH[1]} count=${BASH_REMATCH[2]} sum=${BASH_REMATCH[3]}
    json=$(jq -c --arg n "$name" --argjson c "$count" --arg s "$sum" '.sections[$n] = {count: $c, sha256: $s}' <<< "$json") || return 1
  done <<< "$digest"
  printf '%s\n' "$json"
}

# 記録済みの fingerprint（metadata / index の schema_fingerprint）と接続先の現在の schema を照合する。
# 不一致なら、件数または SHA-256 の違う区分を診断に出す。
schema_fingerprint::verify() { # <recorded_json> <mysql args...>
  local recorded=$1 algorithm expected current actual section diff="" r c
  shift
  printf '%s' "$recorded" | jq -e 'type == "object"' > /dev/null 2>&1 \
    || { echo "schema-fingerprint: 記録された schema_fingerprint が JSON object ではありません" >&2; return 1; }
  algorithm=$(printf '%s' "$recorded" | jq -r '.algorithm // empty')
  expected=$(printf '%s' "$recorded" | jq -r '.sha256 // empty')
  if [[ "$algorithm" != "$SCHEMA_FINGERPRINT_ALGORITHM" || ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
    echo "schema-fingerprint: 記録が不正、または算出方式が異なります（algorithm=${algorithm:-なし}、期待: $SCHEMA_FINGERPRINT_ALGORITHM）" >&2
    return 1
  fi
  current=$(schema_fingerprint::compute "$@") || return 1
  actual=$(printf '%s' "$current" | jq -r '.sha256')
  [[ "$actual" == "$expected" ]] && return 0
  for section in "${SCHEMA_FINGERPRINT_SECTIONS[@]}"; do
    r=$(printf '%s' "$recorded" | jq -c --arg n "$section" '.sections[$n] // null')
    c=$(printf '%s' "$current" | jq -c --arg n "$section" '.sections[$n] // null')
    [[ "$r" == "$c" ]] || diff+="${diff:+, }$section"
  done
  echo "schema-fingerprint: schema が backup 時と一致しません（expected=$expected actual=$actual。差異のある区分: ${diff:-不明}）" >&2
  return 1
}
