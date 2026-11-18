#!/usr/bin/env bash
# ============================================================
# binlog coordinate / continuity ユーティリティ（HFP-03-004 / RQ-004,005）
# - dump の --source-data=2 coordinate 抽出
# - file suffix の連続性・同一 lineage・truncation 検査
# - closed file の end position（SHOW BINARY LOGS の File_size）照合
# ============================================================

# dump の --source-data=2 coordinate を抽出（BINLOG_START_FILE/POSITION を設定）。backup-full.sh の
# backup_full::parse_dump_coords と同じ規則（MySQL 8.4 の CHANGE REPLICATION SOURCE TO だけを読む。RB-W 8.4 対応）
binlog::parse_dump_coords() { # dump_file
  local dump=$1 line
  local re="CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='([^']+)', *SOURCE_LOG_POS=([0-9]+)"
  BINLOG_START_FILE=""
  BINLOG_START_POSITION=""
  while IFS= read -r line; do
    if [[ "$line" =~ $re ]]; then
      BINLOG_START_FILE=${BASH_REMATCH[1]}
      BINLOG_START_POSITION=${BASH_REMATCH[2]}
      return 0
    fi
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

# binlog file の suffix（binlog.000123 / mysql-bin.000123 → 000123）
binlog::suffix() { # file
  local f=$1
  [[ "$f" == *.* ]] && printf '%s' "${f##*.}" || echo ""
}

# binlog file の basename 接頭辞（binlog.000123 → binlog、mysql-bin.000001 → mysql-bin）
binlog::prefix() { # file
  local f=$1
  [[ "$f" == *.* ]] && printf '%s' "${f%.*}" || echo ""
}

# 同一接頭辞・同一桁幅の次 file 名（STATE_LAST_FILE / dump coordinate から派生）
binlog::next_file() { # file
  local f=$1
  local prefix suffix width next
  prefix=$(binlog::prefix "$f")
  suffix=$(binlog::suffix "$f")
  [[ -n "$prefix" && "$suffix" =~ ^[0-9]+$ ]] || {
    echo "binlog: 次 file 名を解釈できません: $f" >&2
    return 1
  }
  width=${#suffix}
  next=$((10#$suffix + 1))
  printf "%s.%0${width}d" "$prefix" "$next"
}

# 同一接頭辞・同一桁幅で start..end の欠番なく存在する file 名を列挙
binlog::files_in_range() { # start_file end_file -> 1行1 file
  local start=$1 end=$2
  local prefix_s prefix_e s e width i f
  prefix_s=$(binlog::prefix "$start")
  prefix_e=$(binlog::prefix "$end")
  s=$(binlog::suffix "$start")
  e=$(binlog::suffix "$end")
  [[ -n "$prefix_s" && "$prefix_s" == "$prefix_e" ]] || {
    echo "binlog: 接頭辞が一致しません: $start .. $end" >&2
    return 1
  }
  [[ "$s" =~ ^[0-9]+$ && "$e" =~ ^[0-9]+$ ]] || {
    echo "binlog: suffix を解釈できません: $start .. $end" >&2
    return 1
  }
  width=${#s}
  for ((i = 10#$s; i <= 10#$e; i++)); do
    f=$(printf "%s.%0${width}d" "$prefix_s" "$i")
    printf '%s\n' "$f"
  done
}

# file list の連続性: <dir> 内の <prefix>.XXXXXX が start..end まで欠番なく存在する
binlog::files_continuous() { # dir start_file end_file
  local dir=$1 start=$2 end=$3
  local f
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    if [[ ! -f "$dir/$f" ]]; then
      echo "binlog: 欠番を検出: $f（$start .. $end の範囲）" >&2
      return 1
    fi
  done < <(binlog::files_in_range "$start" "$end") || return 1
  return 0
}

# SHOW BINARY LOGS の出力（「file 名 File_size …」の行）から指定 file の File_size を出力する（無ければ何も出さない）。
# 一覧は here-string で渡す（パイプにしない。RB-W P1-1）。checkpoint ごとの FLUSH BINARY LOGS で、binlog の保持期間内の
# 一覧は pipe の容量（Linux 64 KiB）を超え得る。パイプの後段の awk が該当行で exit すると、残りを書く前段の printf が
# SIGPIPE（141）で終わり、呼び出し側（set -Eeuo pipefail）の archive / snapshot / checkpoint が止まっていた。
binlog::size_from_logs() { # <SHOW BINARY LOGS の出力> <file>
  awk -v f="$2" '$1 == f { print $2; exit }' <<< "$1"
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
