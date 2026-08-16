#!/usr/bin/env bash
# ============================================================
# cutover 状態機械（HFP-03-009 / RQ-007）
#
# CUTOVER_STATE_FILE が単一の真実（single source of truth）。
# 遷移:
#   initial -> staged（restore+validation 完了時）
#   staged -> read-only-smoke-passed -> single-writer -> write-enabled
#   staged/read-only-smoke-passed/single-writer -> rolled-back（write-enable 前のみ）
#
# write-enable 後の rollback は禁止（新規 transaction を失うため）。
# ============================================================

cutover::require_state_file() {
  [[ -n "${CUTOVER_STATE_FILE:-}" ]] || { echo "cutover: CUTOVER_STATE_FILE が未設定です" >&2; return 1; }
  mkdir -p "$(dirname "$CUTOVER_STATE_FILE")"
  return 0
}

cutover::read_state() { # -> state
  cutover::require_state_file || return 1
  if [[ ! -f "$CUTOVER_STATE_FILE" ]]; then
    echo "initial"
    return 0
  fi
  jq -r '.state // "initial"' "$CUTOVER_STATE_FILE" 2>/dev/null || echo "initial"
}

cutover::write_state() { # state plan_id
  local state=$1 plan_id=$2
  cutover::require_state_file || return 1
  jq -n --arg s "$state" --arg p "$plan_id" --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{state: $s, plan_id: $p, updated_at_utc: $t}' > "$CUTOVER_STATE_FILE.tmp"
  mv "$CUTOVER_STATE_FILE.tmp" "$CUTOVER_STATE_FILE"
  chmod 600 "$CUTOVER_STATE_FILE"
}

# 遷移の正当性を確認（current_state -> next_state）
cutover::guard_transition() { # current_state next_state
  local cur=$1 next=$2
  case "$cur" in
    initial|staged)
      case "$next" in
        read-only-smoke-passed) return 0 ;;
        rolled-back) return 0 ;;
        *) echo "cutover: 不正な遷移です（$cur -> $next）" >&2; return 1 ;;
      esac
      ;;
    read-only-smoke-passed)
      case "$next" in
        single-writer) return 0 ;;
        rolled-back) return 0 ;;
        *) echo "cutover: 不正な遷移です（$cur -> $next）" >&2; return 1 ;;
      esac
      ;;
    single-writer)
      case "$next" in
        write-enabled) return 0 ;;
        *) echo "cutover: 不正な遷移です（$cur -> $next）" >&2; return 1 ;;
      esac
      ;;
    write-enabled)
      echo "cutover: すでに write-enabled です。rollback は禁止。" >&2
      return 1
      ;;
    rolled-back)
      echo "cutover: rolled-back 済みです。再度 restore+validate が必要です。" >&2
      return 1
      ;;
    *)
      echo "cutover: 未知の state です: $cur" >&2
      return 1
      ;;
  esac
}
