#!/usr/bin/env bash
# ============================================================
# 主要 table の順序付き内容ハッシュ（A-NF09-5 / G-04）
#
# row count だけでは「件数は同じだが内容が違う」復元を検出できないため、backup 時（full / checkpoint の
# 書込み静止区間内）に CONTENT_HASH_TABLES の各 table の内容ハッシュを metadata.json
# （manifest.json の entry として SHA-256 で固定される）と index に記録し、復元検証で再計算して照合する。
#
# 計算方法（algorithm = mysql-batch-pk-quote-sha256-v2）:
#   - 対象 table は主キーを持つこと（主キーが無ければ失敗する。行の並びを主キーで一意に決めるため）。
#   - 行の並び: 主キー列（序数順）の ORDER BY。主キーは一意なので並びは一意に決まる。
#   - 各列は IF(列 IS NULL, 'NULL', QUOTE(列)) で文字列化する。値は QUOTE() で ' に囲まれ、SQL の NULL は
#     囲まれない NULL になるため、NULL と文字列 'NULL' を区別できる。
#   - 上記の mysql -N -B 出力（tab 区切り・mysql client のエスケープ）を SHA-256 にかける。
#   - TIMESTAMP 列の表示が接続先の time_zone に左右されないよう、session time_zone を +00:00 に固定する。
#   - backup 時と復元検証時に同じツールイメージ（同じ mysql client）で計算することが前提。
# 呼び出し側は MYSQL_CLIENT_BIN を設定し、接続 option（option file・host・DB 名）を引数で渡す。
# ============================================================

CONTENT_HASH_ALGORITHM=mysql-batch-pk-quote-sha256-v2
# content_hash::describe の結果（「,」区切り）
CONTENT_HASH_COLUMNS=""
CONTENT_HASH_PK=""

content_hash::valid_identifier() { [[ "$1" =~ ^[A-Za-z0-9_]{1,64}$ ]]; }

# mysql の失敗時に、標準エラーの先頭（秘密を含まない MySQL のエラー文）を診断として出す
content_hash::report_error() { # <message> <stderr_file>
  local detail=""
  if [[ -s "$2" ]]; then
    detail=$(head -c 300 "$2" | tr '\n' ' ')
  fi
  echo "content-hash: $1${detail:+（$detail）}" >&2
}

# 列（序数順）と主キー列を CONTENT_HASH_COLUMNS / CONTENT_HASH_PK に設定する。
# table が無い・主キーが無い・列名が想定外なら失敗する。
content_hash::describe() { # <table> <mysql args...>
  local table=$1 out name key err rc=0
  shift
  CONTENT_HASH_COLUMNS=""
  CONTENT_HASH_PK=""
  content_hash::valid_identifier "$table" || { echo "content-hash: table 名が不正です: $table" >&2; return 1; }
  err=$(mktemp "${TMPDIR:-/tmp}/ses-content-hash.XXXXXX") || return 1
  out=$("$MYSQL_CLIENT_BIN" "$@" -N -B --execute \
    "/* ses-backup:content-hash-order */ SELECT column_name, column_key FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '$table' ORDER BY ordinal_position;" \
    2> "$err") || rc=$?
  if (( rc != 0 )); then
    content_hash::report_error "列情報を取得できません: $table" "$err"
    rm -f "$err"
    return 1
  fi
  rm -f "$err"
  while IFS=$'\t' read -r name key; do
    [[ -n "$name" ]] || continue
    content_hash::valid_identifier "$name" || { echo "content-hash: 列名が不正です: $table.$name" >&2; return 1; }
    CONTENT_HASH_COLUMNS+="${CONTENT_HASH_COLUMNS:+,}$name"
    if [[ "$key" == PRI ]]; then
      CONTENT_HASH_PK+="${CONTENT_HASH_PK:+,}$name"
    fi
  done <<< "$out"
  [[ -n "$CONTENT_HASH_COLUMNS" ]] || { echo "content-hash: table がありません（列情報 0 件）: $table" >&2; return 1; }
  [[ -n "$CONTENT_HASH_PK" ]] \
    || { echo "content-hash: 主キーの無い table は内容ハッシュの対象にできません（行の並びが一意に決まらないため）: $table" >&2; return 1; }
  return 0
}

# 1 table の内容ハッシュ（16 進 64 桁）を出力する。
content_hash::table() { # <table> <columns_csv> <pk_csv> <mysql args...>
  local table=$1 columns_csv=$2 pk_csv=$3 select="" order="" col sum err
  local -a cols=() pks=()
  shift 3
  content_hash::valid_identifier "$table" || { echo "content-hash: table 名が不正です: $table" >&2; return 1; }
  IFS=',' read -ra cols <<< "$columns_csv"
  IFS=',' read -ra pks <<< "$pk_csv"
  (( ${#cols[@]} > 0 && ${#pks[@]} > 0 )) || { echo "content-hash: 列または主キーがありません: $table" >&2; return 1; }
  for col in "${cols[@]}"; do
    content_hash::valid_identifier "$col" || { echo "content-hash: 列名が不正です: $table.$col" >&2; return 1; }
    select+="${select:+, }IF(\`$col\` IS NULL, 'NULL', QUOTE(\`$col\`))"
  done
  for col in "${pks[@]}"; do
    content_hash::valid_identifier "$col" || { echo "content-hash: 列名が不正です: $table.$col" >&2; return 1; }
    order+="${order:+, }\`$col\`"
  done
  err=$(mktemp "${TMPDIR:-/tmp}/ses-content-hash.XXXXXX") || return 1
  # mysql の終了コードを保つ（パイプ後段の sha256sum の成否で上書きしない）
  if ! sum=$("$MYSQL_CLIENT_BIN" "$@" --quick -N -B --execute \
      "/* ses-backup:content-hash */ SET SESSION time_zone = '+00:00'; SELECT $select FROM \`$table\` ORDER BY $order;" \
      2> "$err" | sha256sum; exit "${PIPESTATUS[0]}"); then
    content_hash::report_error "内容を取得できません: $table" "$err"
    rm -f "$err"
    return 1
  fi
  rm -f "$err"
  sum=${sum%% *}
  [[ "$sum" =~ ^[0-9a-f]{64}$ ]] || { echo "content-hash: SHA-256 を計算できません: $table" >&2; return 1; }
  printf '%s\n' "$sum"
}

# CONTENT_HASH_TABLES（「,」区切り）の全 table の内容ハッシュを JSON object で出力する。
# 1 table でも計算できなければ失敗する（記録を指定された table を黙って省かない）。
content_hash::collect() { # <tables_csv> <mysql args...>
  local csv=$1 json='{}' t sum
  local -a tables=()
  shift
  IFS=',' read -ra tables <<< "$csv"
  for t in "${tables[@]}"; do
    t=${t//[[:space:]]/}
    [[ -n "$t" ]] || continue
    content_hash::describe "$t" "$@" || return 1
    sum=$(content_hash::table "$t" "$CONTENT_HASH_COLUMNS" "$CONTENT_HASH_PK" "$@") || return 1
    json=$(jq -c --arg t "$t" --arg s "$sum" --arg o "$CONTENT_HASH_PK" --arg a "$CONTENT_HASH_ALGORITHM" \
      '.[$t] = {algorithm: $a, sha256: $s, order_by: ($o | split(","))}' <<< "$json") || return 1
  done
  printf '%s\n' "$json"
}

# 記録済みの内容ハッシュ（metadata / index の content_hashes）と、接続先の現在の内容を照合する。
content_hash::verify() { # <content_hashes_json> <mysql args...>
  local recorded=$1 t expected algorithm actual failed=0
  shift
  printf '%s' "$recorded" | jq -e 'type == "object"' > /dev/null 2>&1 \
    || { echo "content-hash: 記録された content_hashes が JSON object ではありません" >&2; return 1; }
  while IFS= read -r t; do
    [[ -n "$t" ]] || continue
    expected=$(printf '%s' "$recorded" | jq -r --arg t "$t" '.[$t].sha256 // empty')
    algorithm=$(printf '%s' "$recorded" | jq -r --arg t "$t" '.[$t].algorithm // empty')
    if [[ ! "$expected" =~ ^[0-9a-f]{64}$ || "$algorithm" != "$CONTENT_HASH_ALGORITHM" ]]; then
      echo "content-hash: 記録が不正、または算出方式が異なります: $t（algorithm=${algorithm:-なし}、期待: $CONTENT_HASH_ALGORITHM）" >&2
      failed=1
      continue
    fi
    if ! content_hash::describe "$t" "$@" \
        || ! actual=$(content_hash::table "$t" "$CONTENT_HASH_COLUMNS" "$CONTENT_HASH_PK" "$@"); then
      failed=1
      continue
    fi
    if [[ "$actual" != "$expected" ]]; then
      echo "content-hash: 内容ハッシュ不一致: $t（expected=$expected actual=$actual）" >&2
      failed=1
    fi
  done <<< "$(printf '%s' "$recorded" | jq -r 'keys[]' 2>/dev/null)"
  (( failed == 0 ))
}
