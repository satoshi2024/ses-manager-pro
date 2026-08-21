#!/usr/bin/env bash
# ============================================================
# recovery target guard（HFP-03-007 / RQ-006）
# 書込み前に同一 connection で次を検証する（1 つでも失敗なら import 前停止）:
#   - target @@server_uuid が plan の source UUID と一致 → 拒否
#   - target UUID が allowlist に無い → 拒否
#   - recovery control schema の marker/plan_id 不一致 → 拒否
#   - target DB が既に存在し table を含む → 拒否
#   - host/db/user が空または default fallback → 拒否
#   - TARGET_HOST == SOURCE_HOST → 拒否（credential scope の前提）
# 接続は restore.sh が用意した TARGET_OPT_ARGS（option file 経由）を使う。
# ============================================================

target_guard::fail() { echo "target-guard: $*" >&2; return 1; }

# target の server_uuid（同一 connection で取得）
target_guard::target_uuid() {
  "$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B --execute "SELECT @@server_uuid;" 2>/dev/null
}

# recovery control schema の marker を読む（provision 時に挿入される）
target_guard::control_marker() { # target_uuid -> allowlist_ref plan_id
  "$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT allowlist_ref, plan_id FROM ses_recovery_control.targets WHERE uuid = '$1';" 2>/dev/null
}

# target DB の object 数（tables + routines + events）。
# COUNT 失敗・空出力・非整数は空文字を返し、呼び出し側で fail-closed する。
# （旧実装の -1 = 空扱い fail-open は禁止）
target_guard::object_count() { # db
  local out
  out=$("$MYSQL_CLIENT_BIN" "${TARGET_OPT_ARGS[@]}" -N -B \
    --execute "SELECT
      (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$1')
      + (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = '$1')
      + (SELECT COUNT(*) FROM information_schema.events WHERE event_schema = '$1');" 2>/dev/null) || out=""
  if [[ -z "$out" || ! "$out" =~ ^[0-9]+$ ]]; then
    echo ""
    return 1
  fi
  echo "$out"
  return 0
}

# 互換エイリアス
target_guard::table_count() { target_guard::object_count "$@"; }

# 全 guard を実行（0=通過、それ以外=拒否）
target_guard::run() { # plan_json target_db
  local plan_json=$1
  local plan_db=${2:-}
  common::require_env TARGET_HOST
  common::require_env TARGET_PORT
  common::require_env TARGET_USER
  common::require_env TARGET_PASSWORD_FILE
  common::require_env SOURCE_HOST

  # loopback / 空 / 未設定 fallback は拒否（同一 DB 機上の別 mysqld への誤適用防止）
  case "$TARGET_HOST" in
    ""|localhost|127.0.0.1|::1|"[::1]")
      target_guard::fail "TARGET_HOST が空または loopback（localhost/127.0.0.1/::1）です"
      return 1
      ;;
  esac
  if [[ -z "$TARGET_USER" || "$TARGET_USER" == "root" ]]; then
    target_guard::fail "TARGET_USER が空または root です（scoped account を指定）"
    return 1
  fi
  if [[ -z "$plan_db" ]]; then
    target_guard::fail "復元対象 DB 名が空です"
    return 1
  fi
  if [[ "$TARGET_HOST" == "$SOURCE_HOST" ]]; then
    target_guard::fail "TARGET_HOST が SOURCE_HOST と同じです（credential scope 違反）"
    return 1
  fi

  local source_uuid plan_id
  source_uuid=$(printf '%s' "$plan_json" | jq -r '.source_server_uuid // empty')
  plan_id=$(printf '%s' "$plan_json" | jq -r '.plan_id // empty')
  if [[ -z "$source_uuid" || -z "$plan_id" ]]; then
    target_guard::fail "plan に source UUID / plan_id がありません"
    return 1
  fi

  local target_uuid
  target_uuid=$(target_guard::target_uuid)
  if [[ -z "$target_uuid" ]]; then
    target_guard::fail "target に接続できません（TLS/credential を確認）"
    return 1
  fi

  if [[ "$target_uuid" == "$source_uuid" ]]; then
    target_guard::fail "target が production source と同じ server_uuid です（$target_uuid）"
    return 1
  fi

  local allowlist_file=${TARGET_ALLOWLIST_FILE:-}
  if [[ -z "$allowlist_file" || ! -f "$allowlist_file" ]]; then
    target_guard::fail "TARGET_ALLOWLIST_FILE がありません（apply は BLOCKED）"
    return 1
  fi
  if ! grep -qx "$target_uuid" "$allowlist_file"; then
    target_guard::fail "target UUID が allowlist にありません: $target_uuid"
    return 1
  fi

  local marker_ref marker_plan
  read -r marker_ref marker_plan <<< "$(target_guard::control_marker "$target_uuid")"
  if [[ -z "$marker_ref" ]]; then
    target_guard::fail "recovery control marker がありません（provision 未完了）"
    return 1
  fi
  if [[ "$marker_ref" != "$(printf '%s' "$plan_json" | jq -r '.target.allowlist_ref // empty')" ]]; then
    target_guard::fail "marker の allowlist_ref が plan と一致しません"
    return 1
  fi
  if [[ "$marker_plan" != "$plan_id" ]]; then
    target_guard::fail "marker の plan_id が plan と一致しません（別 plan への適用は禁止）"
    return 1
  fi

  # 非空検査は fail-closed: COUNT 失敗・非整数は拒否。
  # 許容は「schema 不在（COUNT=0）または table+routine+event=0」のみ。
  local count
  if ! count=$(target_guard::object_count "$plan_db"); then
    target_guard::fail "target DB の object COUNT に失敗しました（fail-closed）: $plan_db"
    return 1
  fi
  if (( count > 0 )); then
    target_guard::fail "target DB が空ではありません（objects=$count）: $plan_db"
    return 1
  fi
  return 0
}
