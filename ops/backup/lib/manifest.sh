#!/usr/bin/env bash
# ============================================================
# manifest 生成・検証（HFP-03-003 / RQ-003）
#
# 作成順（BL-005 対応）:
#   1. metadata + 全 payload を staging に確定（producer 終了）
#   2. manifest.json = 全 payload の path/type/size/sha256
#   3. manifest.sha256 = manifest.json の SHA-256（これで manifest 自体を固定）
#   4. staging を read-only 化
#
# 検証（restore 側でも使用）:
#   - 全 entry の存在 / size / sha256 一致
#   - manifest に無い extra file を拒否（AC-003-03）
#   - manifest.sha256 と manifest.json の一致
# ============================================================

MANIFEST_SCHEMA_VERSION=1

manifest::build() { # staging_dir -> manifest.json を書く
  local staging=$1
  [[ -d "$staging" ]] || { echo "manifest: staging がありません: $staging" >&2; return 1; }

  local entries=""
  local f=""
  while IFS= read -r -d '' f; do
    local rel size sha type
    rel=${f#"$staging"/}
    case "$rel" in
      *'/'../*|../*|*'/..') echo "manifest: path traversal を拒否: $rel" >&2; return 1 ;;
      manifest.json|manifest.sha256) continue ;;
    esac
    if [[ -d "$f" ]]; then
      type=dir; size=0; sha=""
    elif [[ -L "$f" || ! -f "$f" ]]; then
      echo "manifest: 非 regular file を拒否: $rel" >&2
      return 1
    else
      type=file
      size=$(stat -c %s "$f")
      sha=$(sha256sum "$f" | awk '{print $1}')
    fi
    entries="$entries"$'\n'"{\"path\": $(printf '%s' "$rel" | jq -R .), \"type\": \"$type\", \"size\": $size, \"sha256\": \"$sha\"}"
  done < <(find "$staging" -mindepth 1 -print0 2>/dev/null)

  # 全 path の sort 順で並べる（再現可能な manifest）
  local files_json
  files_json=$(printf '%s\n' "$entries" | grep -v '^$' | jq -s 'sort_by(.path)')

  jq -n \
    --arg schema_version "$MANIFEST_SCHEMA_VERSION" \
    --arg created_at_utc "$(common::now_utc)" \
    --argjson files "$files_json" \
    '{schema_version: $schema_version, created_at_utc: $created_at_utc,
      files: $files}' > "$staging/manifest.json"
  chmod 600 "$staging/manifest.json"
  return 0
}

manifest::finalize() { # staging_dir -> manifest.sha256 を書く
  local staging=$1
  [[ -f "$staging/manifest.json" ]] || { echo "manifest: manifest.json がありません" >&2; return 1; }
  sha256sum "$staging/manifest.json" | awk '{print $1}' > "$staging/manifest.sha256"
  chmod 600 "$staging/manifest.sha256"
  # staging を read-only 化（後書き防止）
  chmod -R a-w "$staging" 2>/dev/null || true
  return 0
}

# 検証: manifest.json の全 entry が一致し、extra file が無いこと
manifest::verify() { # staging_dir [manifest_path]
  local staging=$1
  local manifest_path=${2:-$staging/manifest.json}
  [[ -f "$manifest_path" ]] || { echo "manifest: manifest がありません: $manifest_path" >&2; return 1; }
  local sha_file=""
  if [[ -f "$staging/manifest.sha256" ]]; then
    sha_file=$(cat "$staging/manifest.sha256" 2>/dev/null || true)
    local actual
    actual=$(sha256sum "$manifest_path" | awk '{print $1}')
    if [[ "$sha_file" != "$actual" ]]; then
      echo "manifest: manifest.sha256 と manifest.json が一致しません" >&2
      return 1
    fi
  fi

  local expected_sha=""
  local i=0
  local files_json=""
  # 空の sha256（dir entry）が IFS 空白 collapse で欠落しないよう unit separator で join する
  files_json=$(jq -r '.files[] | [.path, (.size|tostring), .sha256, .type] | join("\u001f")' "$manifest_path" 2>/dev/null) || {
    echo "manifest: manifest.json を解釈できません" >&2
    return 1
  }

  local line=""
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    local rel size sha type
    IFS=$'\x1f' read -r rel size sha type <<< "$line"
    case "$rel" in
      *'/'../*|../*|*'/..') echo "manifest: path traversal を拒否: $rel" >&2; return 1 ;;
    esac
    local f="$staging/$rel"
    if [[ "$type" == "dir" ]]; then
      if [[ ! -d "$f" ]]; then
        echo "manifest: dir entry が存在しません: $rel" >&2
        return 1
      fi
      continue
    fi
    if [[ ! -f "$f" ]]; then
      echo "manifest: entry が存在しません: $rel" >&2
      return 1
    fi
    local fsize
    fsize=$(stat -c %s "$f")
    if [[ "$fsize" != "$size" ]]; then
      echo "manifest: size 不一致: $rel (expected=$size actual=$fsize)" >&2
      return 1
    fi
    local fsha
    fsha=$(sha256sum "$f" | awk '{print $1}')
    if [[ "$fsha" != "$sha" ]]; then
      echo "manifest: sha256 不一致: $rel" >&2
      return 1
    fi
  done <<< "$files_json"

  # extra file 拒否
  local f=""
  while IFS= read -r -d '' f; do
    local rel
    rel=${f#"$staging"/}
    case "$rel" in
      manifest.json|manifest.sha256) continue ;;
    esac
    if ! jq -e --arg p "$rel" '.files[] | select(.path == $p)' "$manifest_path" > /dev/null 2>&1; then
      echo "manifest: manifest に列挙されない file を拒否: $rel" >&2
      return 1
    fi
  done < <(find "$staging" -mindepth 1 -type f -print0 2>/dev/null)
  return 0
}
