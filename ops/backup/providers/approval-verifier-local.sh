#!/usr/bin/env bash
# ============================================================
# 承認 claim 検証 provider（隔離環境向け・version 管理された executable）
# claim JSON + detached signature（openssl dgst -sha256 -sign）を検証する。
# production では組織 identity provider 検証（HFP-03-PROD-006）へ差し替える。
#
# usage:
#   approval-verifier-local.sh verify <claim.json> <pubkey.pem>
#   approval-verifier-local.sh sign   <claim.json> <private.pem>   # 署名作成（operator 用）
# ============================================================
set -Eeuo pipefail

APPROVAL_SIG_SUFFIX=.sig

verifier::verify() { # claim pubkey
  local claim=$1 pubkey=$2
  local sig="$claim$APPROVAL_SIG_SUFFIX"
  [[ -f "$sig" ]] || { echo "approval-verifier: 署名 file がありません: $sig" >&2; return 1; }
  # canonical 化した claim に対して署名を検証する（改変検出）。JSON として読めない claim（改変・破損）は、jq の
  # 解析 error をそのまま出して errexit で抜けるのではなく、理由を書いて拒否し canonical の一時 file も残さない
  # （RB-W 追加修正 C。以前は jq の「parse error: Invalid numeric literal」だけが log に出て .canonical が残っていた）
  if ! jq -S -c . "$claim" > "$claim.canonical" 2> /dev/null; then
    echo "approval-verifier: claim を JSON として読めません（改変または破損）: $claim" >&2
    rm -f "$claim.canonical"
    return 1
  fi
  if ! openssl dgst -sha256 -verify "$pubkey" -signature "$sig" "$claim.canonical" > /dev/null 2>&1; then
    echo "approval-verifier: 署名検証に失敗しました: $claim" >&2
    rm -f "$claim.canonical"
    return 1
  fi
  rm -f "$claim.canonical"
  return 0
}

verifier::sign() { # claim private_key
  local claim=$1 key=$2
  jq -S -c . "$claim" > "$claim.canonical"
  openssl dgst -sha256 -sign "$key" -out "$claim$APPROVAL_SIG_SUFFIX" "$claim.canonical" 2>/dev/null
  local rc=$?
  rm -f "$claim.canonical"
  exit "$rc"
}

cmd=${1:-}
case "$cmd" in
  verify)
    [[ $# -eq 3 ]] || { echo "Usage: approval-verifier-local.sh verify <claim.json> <pubkey.pem>" >&2; exit 2; }
    verifier::verify "$2" "$3"
    ;;
  sign)
    [[ $# -eq 3 ]] || { echo "Usage: approval-verifier-local.sh sign <claim.json> <private.pem>" >&2; exit 2; }
    verifier::sign "$2" "$3"
    ;;
  *)
    echo "Usage: approval-verifier-local.sh verify|sign <claim.json> <key.pem>" >&2
    exit 2
    ;;
esac
