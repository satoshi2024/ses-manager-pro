#!/usr/bin/env bash
# ============================================================
# restore plan selector（HFP-03-006 / RQ-005）
# - 要求時刻は RFC 3339 UTC（末尾 Z）のみ。
# - 実効復旧点 = consistency_time <= target の最新 VALID checkpoint。
# - base full = consistency_time <= 実効復旧点の最新 VALID full（同一 lineage）。
# - restic snapshot の作成時刻や「単に最新の full」は選択根拠にしない。
# - 時刻比較は jq の fromdateiso8601（jq 1.6 は mktime が TZ 依存）を使わず、
#   `date -u` で epoch 化した数値で行う（AC-005-02: host timezone に依存しない）。
# ============================================================

# strict RFC3339 UTC パース（末尾 Z 必須）。成功時 epoch を出力
selector::parse_target() { # target -> epoch or 1
  local t=$1
  case "$t" in
    *Z) : ;;
    *) echo "target は UTC（末尾 Z）で指定してください: $t" >&2; return 1 ;;
  esac
  [[ "$t" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || {
    echo "target の形式が不正です（YYYY-MM-DDTHH:MM:SSZ）: $t" >&2
    return 1
  }
  local epoch
  epoch=$(date -u -d "$t" +%s 2>/dev/null) || {
    echo "target が実在する日時ではありません: $t" >&2
    return 1
  }
  printf '%s\n' "$epoch"
  return 0
}

# UTC 時刻文字列を epoch 化（date -u を使用。host timezone 非依存）
selector::epoch_of() { # utc_ts
  date -u -d "$1" +%s 2>/dev/null || echo 0
}

# index 配下の VALID な full/checkpoint を読み込み JSON 配列として出力
# （consistency_epoch を付与して時刻比較を数値化する）
selector::load_entries() { # index_dir
  local index_dir=$1
  local out="[]"
  local f=""
  for f in "$index_dir"/*.json; do
    [[ -e "$f" ]] || continue
    local kind status
    kind=$(jq -r '.kind // empty' "$f" 2>/dev/null)
    status=$(jq -r '.status // empty' "$f" 2>/dev/null)
    [[ "$kind" == "full" || "$kind" == "checkpoint" ]] || continue
    [[ "$status" == "VALID" ]] || continue
    local ts
    ts=$(jq -r '.consistency_time_utc // empty' "$f" 2>/dev/null)
    local epoch
    epoch=$(selector::epoch_of "$ts")
    local entry
    entry=$(jq -c --arg f "$(basename "$f")" --argjson e "$epoch" \
      '. + {index_file: $f, consistency_epoch: $e}' "$f" 2>/dev/null) || continue
    out=$(printf '%s' "$out" | jq -c --argjson e "$entry" '. + [$e]')
  done
  printf '%s\n' "$out"
}

# 最新 VALID checkpoint の lineage（選択の基準）
selector::latest_lineage() { # entries_json
  local entries=$1
  jq -r '[.[] | select(.kind == "checkpoint")] | sort_by(.consistency_epoch) | last | .source_lineage // empty' <<< "$entries"
}

# consistency_epoch <= target の最新 checkpoint（同一 lineage）
selector::effective_checkpoint() { # entries_json target_epoch lineage
  local entries=$1 target_epoch=$2 lineage=$3
  jq -c \
    --argjson te "$target_epoch" \
    --arg lin "$lineage" \
    '[.[] | select(.kind == "checkpoint"
       and .status == "VALID"
       and .source_lineage == $lin
       and (.consistency_epoch <= $te))] | sort_by(.consistency_epoch) | last // empty' \
    <<< "$entries"
}

# consistency_epoch <= checkpoint の最新 full（同一 lineage）
selector::base_full() { # entries_json checkpoint_epoch lineage
  local entries=$1 ckpt_epoch=$2 lineage=$3
  jq -c \
    --argjson ce "$ckpt_epoch" \
    --arg lin "$lineage" \
    '[.[] | select(.kind == "full"
       and .status == "VALID"
       and .source_lineage == $lin
       and (.consistency_epoch <= $ce))] | sort_by(.consistency_epoch) | last // empty' \
    <<< "$entries"
}

# binlog の連続性と snapshot 解決（binlog-index から）
selector::resolve_binlogs() { # binlog_index start_file end_file
  local index=$1 start=$2 end=$3
  local s e
  s=${start##*.}
  e=${end##*.}
  [[ "$s" =~ ^[0-9]+$ && "$e" =~ ^[0-9]+$ ]] || { echo "plan: binlog suffix を解釈できません: $start .. $end" >&2; return 1; }
  local arr="[]"
  local i
  for ((i = 10#$s; i <= 10#$e; i++)); do
    local f
    f=$(printf 'binlog.%06d' "$i")
    local entry
    entry=$(jq -c --arg f "$f" '.[] | select(.file == $f) | {file, snapshot_id, sha256, size}' "$index" 2>/dev/null | head -n1)
    if [[ -z "$entry" ]]; then
      echo "plan: 必要 binlog が repository にありません: $f" >&2
      return 1
    fi
    arr=$(printf '%s' "$arr" | jq -c --argjson e "$entry" '. + [$e]')
  done
  printf '%s\n' "$arr"
}
