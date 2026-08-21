#!/usr/bin/env bash
# ============================================================
# 二者承認 claim の検証（HFP-03-006/009 / RQ-006）
# production の承認は canonical claim + detached signature で検証する。
# claim は plan SHA / target UUID / DB 名 / change ticket / actor / role /
# issued / expiry に bind される。2 名は異なる actor でなければならない。
# verifier は version 管理された executable（APPROVAL_VERIFIER）で、
# 未導入なら apply は BLOCKED（固定 env 文字列へ downgrade しない）。
# ============================================================

APPROVAL_SCHEMA_VERSION=1

approval::default_verifier() {
  local lib
  lib=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  local prov="$lib/providers"
  [[ -d "$prov" ]] || prov=$(cd "$lib/../providers" && pwd)
  echo "$prov/approval-verifier-local.sh"
}

# claim の構造・署名・有効期限を検証（0=valid）
approval::verify_claim() { # claim_file pubkey
  local claim_file=$1 pubkey=$2 verifier=${APPROVAL_VERIFIER:-}
  [[ -f "$claim_file" ]] || { echo "approval: claim file がありません: $claim_file" >&2; return 1; }
  [[ -f "$pubkey" ]] || { echo "approval: 検証鍵がありません: $pubkey" >&2; return 1; }
  if [[ -z "$verifier" ]]; then
    verifier=$(approval::default_verifier)
  fi
  [[ -x "$verifier" ]] || { echo "approval: verifier がありません: $verifier（apply は BLOCKED）" >&2; return 1; }
  "$verifier" verify "$claim_file" "$pubkey"
}

# actor ごとの検証鍵を解決（APPROVAL_PUBKEY_DIR/<actor>.pem、無ければ APPROVAL_PUBKEY）
approval::resolve_pubkey() { # acto
  local actor=$1
  if [[ -n "${APPROVAL_PUBKEY_DIR:-}" && -f "$APPROVAL_PUBKEY_DIR/$actor.pem" ]]; then
    echo "$APPROVAL_PUBKEY_DIR/$actor.pem"
    return 0
  fi
  if [[ -n "${APPROVAL_PUBKEY:-}" && -f "$APPROVAL_PUBKEY" ]]; then
    echo "$APPROVAL_PUBKEY"
    return 0
  fi
  echo ""
  return 1
}

# 2 名の claim を収集・検証（plan・target に bind、異なる actor、期限内）
# plan SHA は sidecar（plan::content_for_sha の canonical）と同一値を使う。
# sidecar が無い対象（例: retention report）は file SHA にフォールバックする。
approval::collect_and_verify() { # plan_path target_uuid claim1 claim2
  local plan_path=$1 target_uuid=$2 claim1=$3 claim2=$4
  local plan_sha
  if [[ -f "${plan_path}.sha256" ]]; then
    plan_sha=$(tr -d ' \t\r\n' < "${plan_path}.sha256")
  else
    plan_sha=$(sha256sum "$plan_path" | awk '{print $1}')
  fi
  local KEY1=""

  local actor1="" actor2=""
  for claim in "$claim1" "$claim2"; do
    local acto
    actor=$(jq -r '.actor // empty' "$claim" 2>/dev/null)
    [[ -n "$actor" ]] || { echo "approval: claim に actor がありません: $claim" >&2; return 1; }
    if [[ -z "$actor1" ]]; then
      actor1=$acto
    elif [[ "$actor" != "$actor1" ]]; then
      actor2=$acto
    else
      echo "approval: 同一 actor の claim を 2 件受け付けません: $actor" >&2
      return 1
    fi
  done
  [[ -n "$actor2" ]] || { echo "approval: 異なる 2 名の actor が必要です" >&2; return 1; }

  for claim in "$claim1" "$claim2"; do
    # plan SHA / target UUID / 有効期限の bind 検査
    local c_plan c_target c_expires c_acto
    c_plan=$(jq -r '.plan_sha256 // empty' "$claim" 2>/dev/null)
    c_target=$(jq -r '.target_uuid // empty' "$claim" 2>/dev/null)
    c_expires=$(jq -r '.expires_at_utc // empty' "$claim" 2>/dev/null)
    c_actor=$(jq -r '.actor // empty' "$claim" 2>/dev/null)
    [[ "$c_plan" == "$plan_sha" ]] || { echo "approval: claim の plan SHA が一致しません: $claim" >&2; return 1; }
    [[ "$c_target" == "$target_uuid" ]] || { echo "approval: claim の target UUID が一致しません: $claim" >&2; return 1; }
    local now
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    if [[ -n "$c_expires" && "$c_expires" < "$now" ]]; then
      echo "approval: claim が期限切れです: $claim" >&2
      return 1
    fi
    local pubkey=""
    pubkey=$(approval::resolve_pubkey "$c_actor") || {
      echo "approval: actor の検証鍵がありません: $c_actor（apply は BLOCKED）" >&2
      return 1
    }
    approval::verify_claim "$claim" "$pubkey" || {
      echo "approval: claim の署名検証に失敗しました: $claim" >&2
      return 1
    }
    # R1 P1-01: 2 名の承認は「異なる検証鍵」で署名されていること（同一鍵の
    # 別名 actor で二者承認を充足できない）。鍵の内容で比較する。
    if [[ -z "$KEY1" ]]; then
      KEY1=$(sha256sum "$pubkey" | awk '{print $1}')
    else
      local k2
      k2=$(sha256sum "$pubkey" | awk '{print $1}')
      if [[ "$k2" == "$KEY1" ]]; then
        echo "approval: 同一の検証鍵による 2 件の承認は受け付けません（1 名の承認に等しい）: $c_actor" >&2
        return 1
      fi
    fi
  done
  return 0
}
