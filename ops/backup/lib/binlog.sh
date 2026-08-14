#!/usr/bin/env bash
# ============================================================
# binlog coordinate / continuity ユーティリティ（HFP-03-004 / RQ-004,005）
# - dump の --source-data=2 coordinate 抽出
# - file suffix の連続性・同一 lineage・truncation 検査
# - closed file の end position（SHOW BINARY LOGS の File_size）照合
# ============================================================

# dump の --source-data=2 coordinate を抽出（BINLOG_START_FILE/POSITION を設定）
binlog::parse_dump_coords() { # dump_file
  local dump=$1
  BINLOG_START_FILE=""
  BINLOG_START_POSITION=""
  while IFS= read -r line; do
    case "$line" in
      *"CHANGE MASTER TO"*)
        local f="" p=""
        f=$(printf '%s' "$line" | grep -oE "MASTER_LOG_FILE='[^']+'" | head -n1 | sed "s/MASTER_LOG_FILE='//;s/'//")
        p=$(printf '%s' "$line" | grep -oE "MASTER_LOG_POS=[0-9]+" | head -n1 | sed 's/MASTER_LOG_POS=//')
        if [[ -n "$f" && -n "$p" ]]; then
          BINLOG_START_FILE=$f
          BINLOG_START_POSITION=$p
          return 0
        fi
        ;;
    esac
  done < "$dump"
  return 1
}

# dump 内の GTID 状態（GTID_EXECUTED を設定）
binlog::parse_dump_gtid() { # dump_file
  local dump=$1
  GTID_EXECUTED=""
  while IFS= read -r line; do
    case "$line" in
      *"SET @@GLOBAL.GTID_PURGED"*)
        GTID_EXECUTED=$(printf '%s' "$line" | sed "s/SET @@GLOBAL.GTID_PURGED=//" | tr -d ';')
        break
        ;;
    esac
  done < "$dump"
}

# binlog file の suffix（binlog.000123 → 123）
binlog::suffix() { # file
  local f=$1
  [[ "$f" == *.* ]] && printf '%s' "${f##*.}" || echo ""
}

# file list の連続性: <dir> 内の binlog.XXXXXX が start..end まで欠番なく存在する
binlog::files_continuous() { # dir start_file end_file
  local dir=$1 start=$2 end=$3
  local s e
  s=$(binlog::suffix "$start")
  e=$(binlog::suffix "$end")
  [[ -n "$s" && -n "$e" ]] || { echo "binlog: suffix を解釈できません: $start .. $end" >&2; return 1; }
  local i
  for ((i = 10#$s; i <= 10#$e; i++)); do
    local f
    f=$(printf 'binlog.%06d' "$i")
    if [[ ! -f "$dir/$f" ]]; then
      echo "binlog: 欠番を検出: $f（$start .. $end の範囲）" >&2
      return 1
    fi
  done
  return 0
}

# truncation 検査: local file の size が期待値以上（source File_size）であること
binlog::size_ok() { # file expected_size
  local f=$1 expected=$2
  [[ -f "$f" ]] || { echo "binlog: file がありません: $f" >&2; return 1; }
  local actual
  actual=$(stat -c %s "$f")
  if (( actual < expected )); then
    echo "binlog: truncated file: $f (actual=$actual expected>=$expected)" >&2
    return 1
  fi
  return 0
}

# closed file の checksum 検証（mysqlbinlog --verify-binlog-checksum）
binlog::verify_checksum() { # file
  local f=$1
  if ! "$MYSQLBINLOG_BIN" --verify-binlog-checksum --force-read "$f" > /dev/null 2>&1; then
    echo "binlog: checksum 検証失敗: $f" >&2
    return 1
  fi
  return 0
}
