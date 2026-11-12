#!/usr/bin/env bash
# ============================================================
# 主要 table の順序付き内容ハッシュ（A-NF09-5 / G-04）
#
# row count だけでは「件数は同じだが内容が違う」復元を検出できないため、backup 時（full / checkpoint の
# 書込み静止区間内）に CONTENT_HASH_TABLES の各 table の内容ハッシュを metadata.json
# （manifest.json の entry として SHA-256 で固定される）と index に記録し、復元検証で再計算して照合する。
#
# 計算方法（algorithm = mysql-batch-ordered-sha256-v1）:
#   - 行の並び: 主キー列（序数順）。主キーが無い table は全列（序数順）。
#   - SELECT * の mysql -N -B 出力（tab 区切り・mysql client のエスケープ・NULL は NULL）を SHA-256 にかける。
#   - TIMESTAMP 列の表示が接続先の time_zone に左右されないよう、session time_zone を +00:00 に固定する。
#   - backup 時と復元検証時に同じツールイメージ（同じ mysql client）で計算することが前提。
# 呼び出し側は MYSQL_CLIENT_BIN を設定し、接続 option（option file・host・DB 名）を引数で渡す。
# ============================================================

CONTENT_HASH_ALGORITHM=mysql-batch-ordered-sha256-v1

content_hash::valid_identifier() { [[ "$1" =~ ^[A-Za-z0-9_]{1,64}$ ]]; }

# 並び順に使う列（主キー、無ければ全列）を「,」区切りで出力する。
content_hash::order_columns() { # <table> <mysql args...>
  local table=$1 out col cols=""
  shift
  content_hash::valid_identifier "$table" || { echo "content-hash: table 名が不正です: $table" >&2; return 1; }
  out=$("$MYSQL_CLIENT_BIN" "$@" -N -B --execute \
    "/* ses-backup:content-hash-order */ SELECT c.column_name FROM information_schema.columns c WHERE c.table_schema = DATABASE() AND c.table_name = '$table' AND (c.column_key = 'PRI' OR NOT EXISTS (SELECT 1 FROM information_schema.columns p WHERE p.table_schema = c.table_schema AND p.table_name = c.table_name AND p.column_key = 'PRI')) ORDER BY c.ordinal_position;" \
    2>/dev/null) || { echo "content-hash: 列情報を取得できません: $table" >&2; return 1; }
  while IFS= read -r col; do
    [[ -n "$col" ]] || continue
    content_hash::valid_identifier "$col" || { echo "content-hash: 列名が不正です: $table.$col" >&2; return 1; }
    cols+="${cols:+,}$col"
  done <<< "$out"
  [[ -n "$cols" ]] || { echo "content-hash: table がありません（列情報 0 件）: $table" >&2; return 1; }
  printf '%s\n' "$cols"
}

# 1 table の内容ハッシュ（16 進 64 桁）を出力する。
content_hash::table() { # <table> <order_columns> <mysql args...>
  local table=$1 order_csv=$2 order="" col sum
  local -a cols=()
  shift 2
  content_hash::valid_identifier "$table" || { echo "content-hash: table 名が不正です: $table" >&2; return 1; }
  IFS=',' read -ra cols <<< "$order_csv"
  (( ${#cols[@]} > 0 )) || { echo "content-hash: 並び順の列がありません: $table" >&2; return 1; }
  for col in "${cols[@]}"; do
    content_hash::valid_identifier "$col" || { echo "content-hash: 列名が不正です: $table.$col" >&2; return 1; }
    order+="${order:+, }\`$col\`"
  done
  # mysql の終了コードを保つ（パイプ後段の sha256sum の成否で上書きしない）
  sum=$("$MYSQL_CLIENT_BIN" "$@" --quick -N -B --execute \
    "/* ses-backup:content-hash */ SET SESSION time_zone = '+00:00'; SELECT * FROM \`$table\` ORDER BY $order;" \
    2>/dev/null | sha256sum; exit "${PIPESTATUS[0]}") || { echo "content-hash: 内容を取得できません: $table" >&2; return 1; }
  sum=${sum%% *}
  [[ "$sum" =~ ^[0-9a-f]{64}$ ]] || { echo "content-hash: SHA-256 を計算できません: $table" >&2; return 1; }
  printf '%s\n' "$sum"
}

# CONTENT_HASH_TABLES（「,」区切り）の全 table の内容ハッシュを JSON object で出力する。
# 1 table でも計算できなければ失敗する（記録を指定された table を黙って省かない）。
content_hash::collect() { # <tables_csv> <mysql args...>
  local csv=$1 json='{}' t order sum
  local -a tables=()
  shift
  IFS=',' read -ra tables <<< "$csv"
  for t in "${tables[@]}"; do
    t=${t//[[:space:]]/}
    [[ -n "$t" ]] || continue
    order=$(content_hash::order_columns "$t" "$@") || return 1
    sum=$(content_hash::table "$t" "$order" "$@") || return 1
    json=$(jq -c --arg t "$t" --arg s "$sum" --arg o "$order" --arg a "$CONTENT_HASH_ALGORITHM" \
      '.[$t] = {algorithm: $a, sha256: $s, order_by: ($o | split(","))}' <<< "$json") || return 1
  done
  printf '%s\n' "$json"
}

# 記録済みの内容ハッシュ（metadata / index の content_hashes）と、接続先の現在の内容を照合する。
content_hash::verify() { # <content_hashes_json> <mysql args...>
  local recorded=$1 t expected algorithm order actual failed=0
  shift
  printf '%s' "$recorded" | jq -e 'type == "object"' > /dev/null 2>&1 \
    || { echo "content-hash: 記録された content_hashes が JSON object ではありません" >&2; return 1; }
  while IFS= read -r t; do
    [[ -n "$t" ]] || continue
    expected=$(printf '%s' "$recorded" | jq -r --arg t "$t" '.[$t].sha256 // empty')
    algorithm=$(printf '%s' "$recorded" | jq -r --arg t "$t" '.[$t].algorithm // empty')
    if [[ ! "$expected" =~ ^[0-9a-f]{64}$ || "$algorithm" != "$CONTENT_HASH_ALGORITHM" ]]; then
      echo "content-hash: 記録が不正です: $t（algorithm=${algorithm:-なし}）" >&2
      failed=1
      continue
    fi
    if ! order=$(content_hash::order_columns "$t" "$@") || ! actual=$(content_hash::table "$t" "$order" "$@"); then
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
