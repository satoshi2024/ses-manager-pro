#!/usr/bin/env bash
# ============================================================
# watermark 監視（HFP-03-005 / RQ-010）
# 「古い file が 1 件ある」ではなく、最新の成功 watermark と source の
# 現行 coordinate の差で RPO 可否を判定する。
# 状態: OK(0) / WARN(1) / CRITICAL(2) / UNKNOWN(3)
# ============================================================

# しきい値（秒）
HEALTH_FULL_WARN_SECONDS=${HEALTH_FULL_WARN_SECONDS:-72000}      # 20h
HEALTH_FULL_CRITICAL_SECONDS=${HEALTH_FULL_CRITICAL_SECONDS:-93600} # 26h
HEALTH_CHECKPOINT_WARN_SECONDS=${HEALTH_CHECKPOINT_WARN_SECONDS:-1200}   # 20m
HEALTH_CHECKPOINT_CRITICAL_SECONDS=${HEALTH_CHECKPOINT_CRITICAL_SECONDS:-1800} # 30m
HEALTH_BINLOG_WARN_SECONDS=${HEALTH_BINLOG_WARN_SECONDS:-1200}
HEALTH_BINLOG_CRITICAL_SECONDS=${HEALTH_BINLOG_CRITICAL_SECONDS:-1800}
HEALTH_ARCHIVER_HEARTBEAT_SECONDS=${HEALTH_ARCHIVER_HEARTBEAT_SECONDS:-300}
HEALTH_REPO_CHECK_WARN_SECONDS=${HEALTH_REPO_CHECK_WARN_SECONDS:-604800}   # 7d
HEALTH_DRILL_WARN_DAYS=${HEALTH_DRILL_WARN_DAYS:-90}

HEALTH_STATE=0   # 0 OK / 1 WARN / 2 CRITICAL / 3 UNKNOWN
HEALTH_REASONS=()

health::state_raise() { # new_state reason
  local new=$1 reason=$2
  if (( new > HEALTH_STATE )); then
    HEALTH_STATE=$new
  fi
  HEALTH_REASONS+=("$reason")
}

health::now_epoch() { date +%s; }

# index 配下の最新 VALID full/checkpoint の age
health::snapshot_ages() { # -> full_age checkpoint_age (seconds, 無ければ -1)
  local index_dir=$1
  local full_age=-1 ckpt_age=-1
  local f=""
  local newest_full_ts=0 newest_ckpt_ts=0
  for f in "$index_dir"/*.json; do
    [[ -e "$f" ]] || continue
    local kind status ts
    kind=$(jq -r '.kind // empty' "$f" 2>/dev/null)
    status=$(jq -r '.status // empty' "$f" 2>/dev/null)
    ts=$(jq -r '.consistency_time_utc // empty' "$f" 2>/dev/null)
    [[ "$status" == "VALID" && -n "$ts" ]] || continue
    local epoch
    epoch=$(date -u -d "$ts" +%s 2>/dev/null || echo 0)
    case "$kind" in
      full) (( epoch > newest_full_ts )) && newest_full_ts=$epoch ;;
      checkpoint) (( epoch > newest_ckpt_ts )) && newest_ckpt_ts=$epoch ;;
    esac
  done
  local now
  now=$(health::now_epoch)
  if (( newest_full_ts > 0 )); then
    full_age=$(( now - newest_full_ts ))
  fi
  if (( newest_ckpt_ts > 0 )); then
    ckpt_age=$(( now - newest_ckpt_ts ))
  fi
  printf '%s %s\n' "$full_age" "$ckpt_age"
}

# binlog-index の最新 closed file / gap 数
health::binlog_watermark() { # binlog_index -> last_file gap_count newest_archived_epoch
  local index=$1
  local last_file="" gap_count=0 newest_epoch=0
  if [[ ! -f "$index" ]]; then
    printf '%s %s %s\n' "" 0 0
    return 0
  fi
  local files
  files=$(jq -r '.[] | [.file, .archived_at_utc] | @tsv' "$index" 2>/dev/null || true)
  local line=""
  local prev=0
  local count=0
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    local file ts epoch
    IFS=$'\t' read -r file ts <<< "$line"
    local suf
    suf=${file##*.}
    [[ "$suf" =~ ^[0-9]+$ ]] || continue
    local n=$((10#$suf))
    if (( count > 0 && n != prev + 1 )); then
      gap_count=$((gap_count + n - prev - 1))
    fi
    prev=$n
    count=$((count + 1))
    epoch=$(date -u -d "$ts" +%s 2>/dev/null || echo 0)
    (( epoch > newest_epoch )) && newest_epoch=$epoch
    last_file=$file
  done <<< "$files"
  printf '%s %s %s\n' "$last_file" "$gap_count" "$newest_epoch"
}

# archiver heartbeat（BINLOG_STATE.heartbeat の mtime）
health::archiver_heartbeat_age() { # state_file
  local state=$1
  local hb="$state.heartbeat"
  if [[ -f "$hb" ]]; then
    echo $(( $(health::now_epoch) - $(stat -c %Y "$hb") ))
  else
    echo "-1"
  fi
}

# source の現行 binlog（SHOW BINARY LOGS の最終行）
health::source_current_file() {
  if ! mysql_options::init; then
    echo "UNKNOWN"
    return 1
  fi
  local out
  out=$("$MYSQL_CLIENT_BIN" "${MYSQL_OPT_ARGS[@]}" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -N -B \
    --execute "SHOW BINARY LOGS;" 2>/dev/null) || { echo "UNKNOWN"; return 1; }
  printf '%s\n' "$out" | awk 'END{print $1}'
}

health::file_age() { # path -> age seconds or -1
  local p=$1
  if [[ -f "$p" ]]; then
    echo $(( $(health::now_epoch) - $(stat -c %Y "$p") ))
  else
    echo "-1"
  fi
}

# 全体評価。結果はグローバル変数へ書き、JSON は呼び出し元が組む
health::evaluate() { # index_dir binlog_index binlog_state repo_check_ts drill_ts
  local index_dir=$1 binlog_index=$2 binlog_state=$3 repo_check_ts=$4 drill_ts=$5

  local full_age ckpt_age
  read -r full_age ckpt_age <<< "$(health::snapshot_ages "$index_dir")"

  if (( full_age < 0 )); then
    health::state_raise 2 "full が存在しません"
  elif (( full_age >= HEALTH_FULL_CRITICAL_SECONDS )); then
    health::state_raise 2 "full が ${full_age}s 経過（critical ${HEALTH_FULL_CRITICAL_SECONDS}s）"
  elif (( full_age >= HEALTH_FULL_WARN_SECONDS )); then
    health::state_raise 1 "full が ${full_age}s 経過（warning ${HEALTH_FULL_WARN_SECONDS}s）"
  fi

  if (( ckpt_age < 0 )); then
    health::state_raise 2 "checkpoint が存在しません"
  elif (( ckpt_age >= HEALTH_CHECKPOINT_CRITICAL_SECONDS )); then
    health::state_raise 2 "checkpoint が ${ckpt_age}s 経過（critical ${HEALTH_CHECKPOINT_CRITICAL_SECONDS}s）"
  elif (( ckpt_age >= HEALTH_CHECKPOINT_WARN_SECONDS )); then
    health::state_raise 1 "checkpoint が ${ckpt_age}s 経過（warning ${HEALTH_CHECKPOINT_WARN_SECONDS}s）"
  fi

  # binlog watermark vs source
  local last_file gap_count newest_epoch
  read -r last_file gap_count newest_epoch <<< "$(health::binlog_watermark "$binlog_index")"
  local source_current="UNKNOWN"
  if ! source_current=$(health::source_current_file); then
    health::state_raise 3 "source に接続できません（UNKNOWN）"
  else
    if (( gap_count > 0 )); then
      health::state_raise 2 "binlog gap を検出: ${gap_count} 件"
    fi
    if [[ -n "$last_file" ]]; then
      local ls lc
      ls=${last_file##*.}
      lc=${source_current##*.}
      local event_lag=0
      if [[ "$ls" =~ ^[0-9]+$ && "$lc" =~ ^[0-9]+$ ]]; then
        local behind=$(( 10#$lc - 10#$ls ))
        # 最新 closed が source の 1 つ前なら正常（active は対象外）
        if (( behind > 1 )); then
          event_lag=$(( (10#$lc - 10#$ls - 1) * HEALTH_CHECKPOINT_CRITICAL_SECONDS ))
          health::state_raise 2 "archiver が source より ${behind} file 遅れ"
        elif (( newest_epoch > 0 )); then
          local closed_age
          closed_age=$(( $(health::now_epoch) - newest_epoch ))
          if (( closed_age >= HEALTH_BINLOG_CRITICAL_SECONDS )); then
            health::state_raise 2 "最新 closed binlog が ${closed_age}s 経過（critical）"
          elif (( closed_age >= HEALTH_BINLOG_WARN_SECONDS )); then
            health::state_raise 1 "最新 closed binlog が ${closed_age}s 経過（warning）"
          fi
        fi
      fi
    else
      health::state_raise 2 "archived binlog が存在しません"
    fi
  fi

  # archiver heartbeat
  local hb_age
  hb_age=$(health::archiver_heartbeat_age "$binlog_state")
  if (( hb_age >= 0 && hb_age > HEALTH_ARCHIVER_HEARTBEAT_SECONDS )); then
    health::state_raise 2 "archiver heartbeat が ${hb_age}s 経過"
  fi

  # repository check 年齢
  local rc_age
  rc_age=$(health::file_age "$repo_check_ts")
  if (( rc_age >= 0 && rc_age > HEALTH_REPO_CHECK_WARN_SECONDS )); then
    health::state_raise 1 "restic check が ${rc_age}s 経過"
  fi

  # drill 年齢
  local dr_age
  dr_age=$(health::file_age "$drill_ts")
  if (( dr_age >= 0 )); then
    local dr_days=$(( dr_age / 86400 ))
    if (( dr_days > HEALTH_DRILL_WARN_DAYS )); then
      health::state_raise 1 "drill が ${dr_days} 日経過"
    fi
  fi
}
