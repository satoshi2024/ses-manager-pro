#!/usr/bin/env bash
# ============================================================
# retention 用 dependency graph（HFP-03-010 / RQ-009）
#
# index/ の full/checkpoint メタデータと binlog-index.json から、
# checkpoint の復元チェーン（base full + binlog + uploads snapshot）を
# 構築し、retention policy に基づく削除候補を計算する。
# 本 lib は read-only で、restic 等には触れない。
# ============================================================

# index/full-*.json / index/checkpoint-*.json を読み、graph JSON を出力
dep::build_graph() { # index_dir binlog_index_path -> JSON
  local index_dir=$1 binlog_index=${2:-}
  local fulls="[]" checkpoints="[]"
  local f
  for f in "$index_dir"/full-*.json; do
    [[ -e "$f" ]] || continue
    local j
    j=$(jq -c '{kind, status, consistency_time_utc, restic_snapshot_id,
      binlog_start, uploads_snapshot_id, manifest_sha256, archive: (.archive // "none"),
      full_only: (.full_only // false)}' "$f" 2>/dev/null) || continue
    fulls=$(printf '%s' "$fulls" | jq -c --argjson e "$j" '. + [$e]')
  done
  for f in "$index_dir"/checkpoint-*.json; do
    [[ -e "$f" ]] || continue
    local j
    j=$(jq -c '{kind, status, consistency_time_utc, restic_snapshot_id,
      binlog_end, uploads_snapshot_id, manifest_sha256}' "$f" 2>/dev/null) || continue
    checkpoints=$(printf '%s' "$checkpoints" | jq -c --argjson e "$j" '. + [$e]')
  done
  local binlogs="[]"
  if [[ -n "$binlog_index" && -f "$binlog_index" ]]; then
    binlogs=$(jq -c '[.[] | {file, snapshot_id}]' "$binlog_index" 2>/dev/null || echo "[]")
  fi
  jq -n --argjson fulls "$fulls" --argjson cps "$checkpoints" --argjson bins "$binlogs" \
    '{fulls: $fulls, checkpoints: $cps, binlogs: $bins}'
}

# 対象 checkpoint のチェーン（base full + binlog 一覧）を計算
dep::chain_for_checkpoint() { # graph_json checkpoint_json -> JSON
  local graph=$1 cp=$2
  local cp_time
  cp_time=$(printf '%s' "$cp" | jq -r '.consistency_time_utc // ""')
  # base full: consistency_time_utc <= cp の最新 full
  local base_full="{}"
  local candidates
  candidates=$(printf '%s' "$graph" | jq -c --arg t "$cp_time" \
    '[.fulls[] | select(.status == "VALID" and .consistency_time_utc <= $t)]')
  if [[ "$(printf '%s' "$candidates" | jq -r 'length')" -gt 0 ]]; then
    base_full=$(printf '%s' "$candidates" | jq -c 'max_by(.consistency_time_utc)')
  fi
  # binlog: full.binlog_start.file .. cp.binlog_end.file
  local start_file end_file
  start_file=$(printf '%s' "$base_full" | jq -r '.binlog_start.file // empty')
  end_file=$(printf '%s' "$cp" | jq -r '.binlog_end.file // empty')
  local bins="[]"
  if [[ -n "$start_file" && -n "$end_file" ]]; then
    bins=$(printf '%s' "$graph" | jq -c --arg s "$start_file" --arg e "$end_file" \
      '[.binlogs[] | select(.file >= $s and .file <= $e) | .snapshot_id]')
  fi
  jq -n --argjson cp "$cp" --argjson base "$base_full" --argjson bins "$bins" \
    '{checkpoint: $cp, base_full: $base, binlog_snapshot_ids: $bins}'
}

# チェーンが完全か（base full / binlog が揃っているか）
dep::chain_complete() { # chain_json -> true/false
  local chain=$1
  local base
  base=$(printf '%s' "$chain" | jq -r '.base_full.restic_snapshot_id // empty')
  [[ -n "$base" ]] || { echo "false"; return 0; }
  local n
  n=$(printf '%s' "$chain" | jq -r '.binlog_snapshot_ids | length')
  # binlog が 1 本以上あること（full 直後の checkpoint でも rotation 済み file が存在）
  [[ "$n" -ge 1 ]] || { echo "false"; return 0; }
  echo "true"
}

# retention policy に基づき保持/削除候補を計算（dry-run と同一ロジック）
dep::compute_retention() { # graph_json pitr_days daily_days weekly_count monthly_count now_utc -> JSON
  local graph=$1 pitr=$2 daily=$3 weekly=$4 monthly=$5 now=$6
  local kept_cp_ids="[]"
  # 1) PITR window 内の checkpoint は全て保持
  # 2) window 外は daily_days 分は日次代表 1 つ、その後 weekly_count 週は週次代表、
  #    monthly_count ヶ月は月次代表
  local cp_rows
  cp_rows=$(printf '%s' "$graph" | jq -c --arg t "$now" \
    '[.checkpoints[] | select(.status == "VALID") | . + {age_days: (((($t | fromdateiso8601) - (.consistency_time_utc | fromdateiso8601)) / 86400) | floor)}]')
  kept_cp_ids=$(printf '%s' "$cp_rows" | jq -c \
    --argjson pitr "$pitr" --argjson daily "$daily" --argjson weekly "$weekly" --argjson monthly "$monthly" '
      def bucket7: ((.age_days / 7) | floor);
      def bucket30: ((.age_days / 30) | floor);
      {
        window: [.[] | select(.age_days <= $pitr) | .restic_snapshot_id],
        daily_rep: ([.[] | select(.age_days > $pitr and .age_days <= ($pitr + $daily))] | group_by(.consistency_time_utc[0:10]) | map(max_by(.consistency_time_utc)) | map(.restic_snapshot_id)),
        weekly_rep: ([.[] | select(.age_days > ($pitr + $daily))] | group_by(bucket7) | map(max_by(.consistency_time_utc)) | map(.restic_snapshot_id) | reverse | .[0:$weekly]),
        monthly_rep: ([.[] | select(.age_days > ($pitr + $daily))] | group_by(bucket30) | map(max_by(.consistency_time_utc)) | map(.restic_snapshot_id) | reverse | .[0:$monthly])
      } | (.window + .daily_rep + .weekly_rep + .monthly_rep) | unique')

  # 3) 保持 checkpoint のチェーン（base full / binlog / uploads）を残す
  local keep_snaps="[]" cp
  local kept_rows
  kept_rows=$(printf '%s' "$cp_rows" | jq -c --argjson kept "$kept_cp_ids" \
    '[.[] | select(.restic_snapshot_id as $id | $kept | index($id))]')
  # 保持 checkpoint 自身は、チェーンが不完全でも削除しない（手動調査の余地を残す）
  keep_snaps=$(printf '%s' "$kept_rows" | jq -c 'map(.restic_snapshot_id)')
  local row
  while IFS= read -r row; do
    [[ -n "$row" ]] || continue
    local chain
    chain=$(dep::chain_for_checkpoint "$graph" "$row")
    local complete
    complete=$(dep::chain_complete "$chain")
    local base bins
    base=$(printf '%s' "$chain" | jq -r '.base_full.restic_snapshot_id // empty')
    bins=$(printf '%s' "$chain" | jq -c '.binlog_snapshot_ids')
    local up
    up=$(printf '%s' "$row" | jq -r '.uploads_snapshot_id // empty')
    if [[ "$complete" == "true" ]]; then
      keep_snaps=$(printf '%s' "$keep_snaps" | jq -c --arg b "$base" '. + [$b]')
      keep_snaps=$(printf '%s' "$keep_snaps" | jq -c --argjson bs "$bins" '. + $bs')
      [[ -z "$up" ]] || keep_snaps=$(printf '%s' "$keep_snaps" | jq -c --arg u "$up" '. + [$u]')
    fi
  done <<< "$(printf '%s' "$kept_rows" | jq -r '.[] | @json')"

  # 4) 最新 full（now 以前）は常に保持（安全策。未来時刻の full は対象外）
  local newest_full
  newest_full=$(printf '%s' "$graph" | jq -r --arg t "$now" \
    '[.fulls[] | select(.status == "VALID" and .consistency_time_utc <= $t)] | max_by(.consistency_time_utc) | .restic_snapshot_id // empty')
  [[ -z "$newest_full" ]] || keep_snaps=$(printf '%s' "$keep_snaps" | jq -c --arg n "$newest_full" '. + [$n]')

  # 5) full-only archive（weekly 8 / monthly 12）
  local archive_keep
  archive_keep=$(printf '%s' "$graph" | jq -c --argjson w "$weekly" --argjson m "$monthly" '
    ([.fulls[] | select(.status == "VALID" and .full_only == true and .archive == "weekly")]
       | sort_by(.consistency_time_utc) | reverse | .[0:$w] | map(.restic_snapshot_id))
    + ([.fulls[] | select(.status == "VALID" and .full_only == true and .archive == "monthly")]
       | sort_by(.consistency_time_utc) | reverse | .[0:$m] | map(.restic_snapshot_id)) | unique')
  keep_snaps=$(printf '%s' "$keep_snaps" | jq -c --argjson a "$archive_keep" '. + $a')

  keep_snaps=$(printf '%s' "$keep_snaps" | jq -c 'unique')

  # 6) 削除候補: VALID で保持されていない restic snapshot（checkpoint/full）と
  #    binlog snapshot / uploads は、参照が無ければ削除
  local all_snaps
  all_snaps=$(printf '%s' "$graph" | jq -c \
    '[.fulls[] | select(.status == "VALID") | .restic_snapshot_id]
     + [.checkpoints[] | select(.status == "VALID") | .restic_snapshot_id] | unique')
  local deletable
  deletable=$(printf '%s' "$all_snaps" | jq -c --argjson keep "$keep_snaps" \
    '[.[] | select((. as $s | $keep | index($s)) | not)]')

  local orphan_binlogs
  orphan_binlogs=$(printf '%s' "$graph" | jq -c --argjson keep "$keep_snaps" \
    '[.binlogs[] | select((.snapshot_id as $s | $keep | index($s)) | not) | .snapshot_id]')

  jq -n --argjson keep "$keep_snaps" --argjson del "$deletable" \
    --argjson ob "$orphan_binlogs" --argjson kept_cps "$kept_cp_ids" \
    '{kept_snapshots: $keep, deletable_snapshots: $del, deletable_binlog_snapshots: $ob, kept_checkpoint_ids: $kept_cps}'
}
