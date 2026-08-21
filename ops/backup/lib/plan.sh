#!/usr/bin/env bash
# ============================================================
# restore plan の生成・検証（HFP-03-006 / RQ-005）
# plan は canonical JSON（jq -S）で SHA-256 固定し、生成後の改変を検出する。
# plan SHA / plan_id は volatile な生成時刻・期限（created_at_utc /
# valid_until_utc / plan_id）を除いた内容から計算する（AC-005-02:
# host timezone を変えても同一 plan SHA になることを保証するため）。
# plan は read-only で生成され、apply は別 command の責務。
# ============================================================

PLAN_SCHEMA_VERSION=1

# canonical JSON から SHA 計算用の内容を取り出す。
# 除外するのは実行時タイムスタンプ（created_at_utc）のみ。
# valid_until_utc（期限）と plan_id は改変されると SHA 不一致になるため含める
# （R1 P1-02: 期限バイパス・plan_id 改変の検出を保証する）。
plan::content_for_sha() { # plan_json
  printf '%s' "$1" | jq -S -c 'del(.created_at_utc)'
}

# plan_id 導出用（timezone 非依存かつ時刻非依存）。
# 同一 target からは常に同一 plan_id になるよう、時刻系（created_at /
# valid_until）と自身（plan_id）を除いた内容から導出する。
# 改変検出は plan::content_for_sha（valid_until/plan_id を含む）が担う。
plan::content_for_id() { # plan_json
  printf '%s' "$1" | jq -S -c 'del(.created_at_utc, .valid_until_utc, .plan_id)'
}

# canonical plan JSON を生成し、plan.sha256 を書き、plans ディレクトリへ保存
plan::write() { # plan_json plans_di
  local plan_json=$1 plans_dir=$2
  mkdir -p "$plans_dir"
  local canonical
  canonical=$(printf '%s' "$plan_json" | jq -S -c .)
  local id_content
  id_content=$(plan::content_for_id "$canonical")
  local plan_id
  plan_id=$(printf '%s' "$id_content" | sha256sum | cut -c1-16)
  canonical=$(printf '%s' "$canonical" | jq -S -c --arg id "$plan_id" '. + {plan_id: $id}')
  local sha
  sha=$(printf '%s' "$(plan::content_for_sha "$canonical")" | sha256sum | awk '{print $1}')
  printf '%s' "$canonical" > "$plans_dir/$plan_id.json"
  printf '%s\n' "$sha" > "$plans_dir/$plan_id.json.sha256"
  chmod 600 "$plans_dir/$plan_id.json" "$plans_dir/$plan_id.json.sha256"
  printf '%s' "$canonical"
}

# plan の完全性検証（.sha256 と一致 + JSON 解釈可能）
plan::verify() { # plan_path [sha_path]
  local plan_path=$1
  local sha_path=${2:-$plan_path.sha256}
  [[ -f "$plan_path" && -f "$sha_path" ]] || { echo "plan: plan file がありません: $plan_path" >&2; return 1; }
  local want actual
  want=$(cat "$sha_path")
  actual=$(printf '%s' "$(plan::content_for_sha "$(cat "$plan_path")")" | sha256sum | awk '{print $1}')
  if [[ "$want" != "$actual" ]]; then
    echo "plan: plan SHA が一致しません（改変検出）: $plan_path" >&2
    return 1
  fi
  jq -e . "$plan_path" > /dev/null 2>&1 || { echo "plan: JSON として解釈できません" >&2; return 1; }
  return 0
}

# plan の有効期限と状態判定
plan::status() { # plan_path -> APPLYABLE / EXPIRED / RPO_MISSED / UNKNOWN
  local plan_path=$1
  plan::verify "$plan_path" || { echo "UNKNOWN"; return 1; }
  local state
  state=$(jq -r '.state // empty' "$plan_path")
  case "$state" in
    RPO_MISSED) echo "RPO_MISSED"; return 0 ;;
  esac
  local valid_until
  valid_until=$(jq -r '.valid_until_utc // empty' "$plan_path")
  local now
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  if [[ -n "$valid_until" && "$valid_until" < "$now" ]]; then
    echo "EXPIRED"
    return 0
  fi
  echo "APPLYABLE"
  return 0
}
