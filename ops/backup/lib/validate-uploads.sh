#!/usr/bin/env bash
# ============================================================
# uploads staging 検証（HFP-03-008 / RQ-007）
# - staging の全 file を inventory 化（relpath + sha256）
# - checkpoint metadata の uploads inventory（保存済み SHA）と照合（hash mismatch 0）
# - DB 参照 key の存在チェック（missing は fatal）
# - 未参照 extra file は orphan report（自動削除しない）
# ============================================================

validate_uploads::fail() { echo "validate-uploads: $*" >&2; return 1; }

# staging の inventory を JSON で出力
validate_uploads::inventory() { # dir
  local dir=$1
  (cd "$dir" && find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do
    local rel sha
    rel=${f#./}
    sha=$(sha256sum "$f" | awk '{print $1}')
    printf '{"path":%s,"sha256":"%s"}\n' "$(printf '%s' "$rel" | jq -R .)" "$sha"
  done | jq -s .)
}

# checkpoint metadata の uploads inventory（保存済み SHA）との照合。
# 期待 inventory の各 entry について存在と SHA を検証する。
# 期待に無い extra file はここでは fatal にせず orphan report で扱う。
validate_uploads::hash_check() { # dir expected_inventory_json
  local dir=$1 expected=$2
  local entry=""
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    local p sha
    p=$(printf '%s' "$entry" | jq -r '.path')
    sha=$(printf '%s' "$entry" | jq -r '.sha256')
    [[ -n "$p" && -n "$sha" ]] || continue
    local f="$dir/$p"
    if [[ ! -f "$f" ]]; then
      validate_uploads::fail "uploads の保存済み file がありません: $p"
      return 1
    fi
    local actual
    actual=$(sha256sum "$f" | awk '{print $1}')
    if [[ "$actual" != "$sha" ]]; then
      validate_uploads::fail "uploads の hash mismatch: $p"
      return 1
    fi
  done <<< "$(printf '%s' "$expected" | jq -c '.[]' 2>/dev/null)"
  return 0
}

# DB 参照 key の存在チェック（"label<TAB>key" 行）
validate_uploads::check_references() { # dir refs_text
  local dir=$1 refs=$2
  local line=""
  local missing=0
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    local label key
    label=${line%%$'\t'*}
    key=${line#*$'\t'}
    [[ -n "$key" ]] || continue
    if [[ ! -e "$dir/$key" ]]; then
      echo "validate-uploads: missing reference: $label -> $key" >&2
      missing=1
    fi
  done <<< "$refs"
  (( missing == 0 )) || { validate_uploads::fail "参照 file がありません（missing reference）"; return 1; }
  return 0
}

# 未参照 extra file の report（fatal にしない）
validate_uploads::orphan_report() { # dir referenced_keys_text
  local dir=$1 refs=$2
  local orphans="[]"
  local f=""
  while IFS= read -r -d '' f; do
    local rel
    rel=${f#./}
    if ! grep -qF "$rel" <<< "$refs"; then
      orphans=$(printf '%s' "$orphans" | jq -c --arg p "$rel" '. + [$p]')
    fi
  done < <(cd "$dir" && find . -type f -print0 2>/dev/null)
  printf '%s' "$orphans"
}
