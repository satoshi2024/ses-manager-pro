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
