#!/usr/bin/env bash
# ============================================================
# archive の安全展開（HFP-03-007 / RQ-003 AC-003-03）
# - path traversal（.. 成分）、絶対 path、symlink、device、FIFO、socket を拒否
# - 展開前に entry 一覧を検査し、安全な entry だけを展開する
# ============================================================

safe_extract::check_entries() { # src_di
  local src=$1
  local p=""
  while IFS= read -r -d '' p; do
    local rel
    rel=${p#"$src"/}
    case "$rel" in
      *'/'../*|../*|*'/..'|/*) echo "safe-extract: 不正 path を拒否: $rel" >&2; return 1 ;;
    esac
    if [[ -L "$p" || ! -f "$p" && ! -d "$p" ]]; then
      echo "safe-extract: 非 regular な entry を拒否: $rel" >&2
      return 1
    fi
    if [[ -f "$p" ]]; then
      local links
      links=$(stat -c %h "$p" 2>/dev/null || echo 1)
      if (( links > 1 )); then
        echo "safe-extract: hardlink を拒否: $rel" >&2
        return 1
      fi
    fi
  done < <(find "$src" -mindepth 1 -print0 2>/dev/null)
  return 0
}

# src の内容を dest へ安全に複製（検査済み entry のみ）
safe_extract::copy() { # src_dir dest_di
  local src=$1 dest=$2
  safe_extract::check_entries "$src" || return 1
  mkdir -p "$dest"
  local p=""
  while IFS= read -r -d '' p; do
    local rel
    rel=${p#"$src"/}
    if [[ -d "$p" ]]; then
      mkdir -p "$dest/$rel"
    else
      mkdir -p "$dest/$(dirname "$rel")"
      cp -a -- "$p" "$dest/$rel"
    fi
  done < <(find "$src" -mindepth 1 -print0 2>/dev/null)
  return 0
}
