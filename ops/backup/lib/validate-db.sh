#!/usr/bin/env bash
# ============================================================
# 復元 DB 検証（HFP-03-008 / RQ-007）
# - Flyway: failed row 0、metadata の flyway_max_success と一致
# - CHECK TABLE（主要 table）
# - 主要 table count と checkpoint metadata の照合
# - marker: before が存在、after が存在しない
# - DB→uploads 参照の解決（storage_key / stored_name / *_path）
# 検証は read-only のみ。対象は recovery target（staging）。
# ============================================================

validate_db::fail() { echo "validate-db: $*" >&2; return 1; }

# Flyway 検証
validate_db::flyway() { # metadata_flyway
  local expected=$1
  local failed maxv
  failed=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;" 2>/dev/null)
  if [[ "$failed" == "0" || "$failed" == "" ]]; then
    :
  else
    validate_db::fail "flyway_schema_history に失敗行があります（$failed）"
    return 1
  fi
  maxv=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT MAX(version) FROM flyway_schema_history WHERE success = 1;" 2>/dev/null)
  if [[ -n "$expected" && -n "$maxv" && "$maxv" != "$expected" ]]; then
    validate_db::fail "flyway 最新 version 不一致（expected=$expected actual=$maxv）"
    return 1
  fi
  return 0
}

# CHECK TABLE
validate_db::check_tables() { # "t1 t2 ..."
  local tables=$1
  local t=""
  for t in $tables; do
    local out
    out=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
      --execute "CHECK TABLE \`$t\`;" 2>/dev/null)
    if ! printf '%s\n' "$out" | grep -qiE 'ok$|status: OK'; then
      validate_db::fail "CHECK TABLE 失敗: $t（$(printf '%s' "$out" | tail -1)）"
      return 1
    fi
  done
  return 0
}

# 主要 count 照合（metadata の critical_table_counts と比較）
validate_db::counts() { # counts_json
  local counts=$1
  local t=""
  while IFS= read -r t; do
    [[ -n "$t" ]] || continue
    local expected actual
    expected=$(printf '%s' "$counts" | jq -r --arg t "$t" '.[$t] // empty')
    [[ -n "$expected" ]] || continue
    actual=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
      --execute "SELECT COUNT(*) FROM \`$t\`;" 2>/dev/null)
    if [[ "$actual" != "$expected" ]]; then
      validate_db::fail "table count 不一致: $t（expected=$expected actual=$actual）"
      return 1
    fi
  done <<< "$(printf '%s' "$counts" | jq -r 'keys[]' 2>/dev/null)"
  return 0
}

# marker: before が存在、after が存在しない（VALIDATE_MARKERS_JSON）
validate_db::markers() { # markers_json
  local markers=$1
  [[ -n "$markers" ]] || return 0
  local table before after
  table=$(printf '%s' "$markers" | jq -r '.table // empty')
  before=$(printf '%s' "$markers" | jq -r '.before // empty')
  after=$(printf '%s' "$markers" | jq -r '.after // empty')
  [[ -n "$table" && -n "$before" ]] || return 0
  local b a
  b=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT COUNT(*) FROM \`$table\` WHERE marker = '$before';" 2>/dev/null)
  if [[ "$b" == "0" || -z "$b" ]]; then
    validate_db::fail "before marker が存在しません: $before"
    return 1
  fi
  if [[ -n "$after" ]]; then
    a=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
      --execute "SELECT COUNT(*) FROM \`$table\` WHERE marker = '$after';" 2>/dev/null)
    if [[ -n "$a" && "$a" != "0" ]]; then
      validate_db::fail "after marker が存在します（復旧点より後）: $after"
      return 1
    fi
  fi
  return 0
}

# DB→uploads 参照の解決（VALIDATE_DB_REFERENCE_QUERIES の label→query 一覧）
validate_db::references() { # references_json -> "label<TAB>key" 行
  local refs=$1
  [[ -n "$refs" ]] || return 0
  local label=""
  while IFS= read -r label; do
    [[ -n "$label" ]] || continue
    local query
    query=$(printf '%s' "$refs" | jq -r --arg l "$label" '.[$l] // empty')
    [[ -n "$query" ]] || continue
    local out
    out=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B --execute "$query" 2>/dev/null)
    printf '%s\n' "$out" | sed "s/^/$label\t/"
  done <<< "$(printf '%s' "$refs" | jq -r 'keys[]' 2>/dev/null)"
  return 0
}
