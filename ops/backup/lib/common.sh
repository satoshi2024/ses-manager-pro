#!/usr/bin/env bash
# ============================================================
# HFP-03 共通ユーティリティ
# 各 script は最初に set -Eeuo pipefail; umask 077 を設定し、
# この file を source してから使うこと。
# ============================================================

# ISO 8601 UTC 現在時刻（末尾 Z）
common::now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# エラー終了（メッセージは標準エラーへ）
common::fail() { echo "[backup] ERROR: $*" >&2; exit 1; }

# 必要な環境変数の存在チェック（未設定なら fail）
common::require_env() { # var_name
  local v=$1
  [[ -n "${!v:-}" ]] || common::fail "$v が未設定です"
}

# EXIT trap を連結する（後から設定された trap が既存の trap を上書きしない。
# R2 P2-04: mysql-options 等の trap が呼び出し元の trap で失われるのを防ぐ）
# 実装: handler を配列に蓄積し、単一の dispatcher trap で順に評価する。
# 既存の trap（他 lib 由来）があれば初回に dispatcher へ移す。
declare -a _COMMON_TRAP_HANDLERS=()
_COMMON_TRAP_INIT=0
common::trap_add() { # handle
  local handler=$1
  if [[ "${_COMMON_TRAP_INIT:-0}" == "0" ]]; then
    local current
    current=$(trap -p EXIT 2>/dev/null || true)
    if [[ -n "$current" ]]; then
      local existing
      existing=${current#trap -- }
      existing=${existing% EXIT}
      existing=${existing#\'}
      existing=${existing%\'}
      _COMMON_TRAP_HANDLERS+=("$existing")
    fi
    _COMMON_TRAP_INIT=1
  fi
  _COMMON_TRAP_HANDLERS+=("$handler")
  trap common::_trap_run EXIT
}

common::_trap_run() {
  local h
  for h in "${_COMMON_TRAP_HANDLERS[@]:-}"; do
    eval "$h"
  done
}

# 既知の秘密値パターンを redaction する（URL の userinfo、password= 等）
common::redact() {
  sed -E \
    -e 's#(s3://)[^/]+@#\1<redacted>@#g' \
    -e 's#(password|secret|token|api[-_]?key)[=:][^[:space:]"'\''&]+#\1=<redacted>#gi'
}

# file の SHA-256（存在しなければ空）
common::sha256_file() { # path
  local path=$1
  [[ -r "$path" ]] || { echo ""; return 1; }
  sha256sum "$path" | awk '{print $1}'
}

# 整数判定
common::is_int() { [[ "$1" =~ ^-?[0-9]+$ ]]; }

# restic repository の存在確認（無ければ初期化。中身があるのに読めない場合は fail-closed）
restic::ensure_repository() { # restic_bin log_file
  local restic_bin=$1 log_file=$2
  if "$restic_bin" cat config > /dev/null 2>&1; then
    return 0
  fi
  local repo_dir=""
  case "$RESTIC_REPOSITORY" in
    /*) repo_dir=$RESTIC_REPOSITORY ;;
    *) echo "restic: repository path を判定できません: $RESTIC_REPOSITORY" >&2; return 1 ;;
  esac
  if [[ -d "$repo_dir" ]] && find "$repo_dir" -mindepth 1 -print -quit 2>/dev/null | grep -q .; then
    echo "restic: repository を読めません（password 不一致または破損）: $RESTIC_REPOSITORY" >&2
    return 1
  fi
  if ! "$restic_bin" init >> "$log_file" 2>&1; then
    echo "restic: repository を初期化できません: $(common::redact < "$log_file")" >&2
    return 1
  fi
  return 0
}

# snapshot を tag で解決する（restic 0.17 の tag コマンドは新 id の snapshot を
# 作って旧 id を削除するため、tag 後は必ずこの関数で id を再解決する）
restic::resolve_snapshot_by_tag() { # restic_bin tag_value
  local restic_bin=$1 tag=$2
  local TAG
  export TAG=$tag
  "$restic_bin" snapshots --tag "$tag" --json 2>/dev/null \
    | jq -r 'map(select(.tags | index(env.TAG))) | sort_by(.time) | last | .id // empty'
}
