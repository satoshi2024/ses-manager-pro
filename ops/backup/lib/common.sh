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
