#!/usr/bin/env bash
# ============================================================
# 復元 DB 検証（HFP-03-008 / RQ-007）
# - Flyway: history table の存在（常に必須）、failed row 0、metadata の flyway_max_success と一致
# - CHECK TABLE（主要 table）
# - 主要 table count と checkpoint metadata の照合
# - 主要 table の順序付き内容ハッシュと checkpoint metadata の照合（lib/content-hash.sh）
# - marker: before が存在、after が存在しない
# - DB→uploads 参照の解決（storage_key / stored_name / *_path）
# 検証は read-only のみ。対象は recovery target（staging）。
# ============================================================

validate_db::fail() { echo "validate-db: $*" >&2; return 1; }

# 直近の validate_db::flyway が求めた最新 version（照合まで進まなかった場合は空）
VALIDATE_DB_FLYWAY_LATEST=""

# Flyway 検証（G-15）
# flyway_schema_history を参照できなければ常に失敗する。history table の無い DB で Flyway 有効な
# アプリを起動すると、baseline-on-migrate により「管理外の既存 schema」として baseline され、
# 以降の migration が誤って再適用され得るため。照合を省略する設定は持たない（W1-B1 r2 で廃止）。
validate_db::flyway() { # metadata_flyway
  local expected=$1
  local failed maxv versions rc=0
  VALIDATE_DB_FLYWAY_LATEST=""
  failed=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;" 2>/dev/null)
  if [[ "$failed" == "0" || "$failed" == "" ]]; then
    :
  else
    validate_db::fail "flyway_schema_history に失敗行があります（$failed）"
    return 1
  fi
  # version 列は VARCHAR のため MAX(version) は辞書順になり、V185 より V99 を
  # 「最新」と誤判定する（"99" > "185"）。V102_1（version "102.1"）のような
  # 小数版もあるため数値 CAST も使えない。成功行の version を全件取得し、
  # 版番号順（sort -V）で最大を取る。repeatable migration（version NULL）は除外する。
  versions=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL;" 2>/dev/null) || rc=$?
  if (( rc != 0 )); then
    validate_db::fail "flyway_schema_history を参照できません（復元 DB に history table が無い、または SELECT 権限がありません）。Flyway 有効なアプリを起動すると baseline-on-migrate で誤って baseline されるため cutover できません"
    return 1
  fi
  if [[ -z "$expected" ]]; then
    validate_db::fail "checkpoint metadata に flyway_max_success がありません（復元後の最新 version を照合できません）"
    return 1
  fi
  if [[ "$expected" == unknown ]]; then
    validate_db::fail "checkpoint metadata の flyway_max_success が unknown です（backup 時に FLYWAY_VERSION が未設定）"
    return 1
  fi
  # 取得には成功したが成功 versioned 行が 0 件、または整列に失敗した場合は
  # actual=NULL として不一致にする（従来の MAX(version)=NULL と同じく fail-closed）。
  maxv=$(printf '%s\n' "$versions" | sed '/^$/d' | sort -V | tail -n 1) || maxv=""
  VALIDATE_DB_FLYWAY_LATEST=$maxv
  if [[ -z "$maxv" ]]; then
    validate_db::fail "flyway 最新 version 不一致（expected=$expected actual=NULL）"
    return 1
  fi
  if [[ "$maxv" != "$expected" ]]; then
    validate_db::fail "flyway 最新 version 不一致（expected=$expected actual=$maxv）"
    return 1
  fi
  return 0
}

# 主要 table の順序付き内容ハッシュ照合（A-NF09-5。lib/content-hash.sh を先に source すること）
validate_db::content_hashes() { # content_hashes_json
  content_hash::verify "$1" "${TARGET_OPT_ARGS[@]}"
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
  local table before afte
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
