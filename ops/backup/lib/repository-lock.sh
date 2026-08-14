#!/usr/bin/env bash
# ============================================================
# repository 共有 lock（HFP-03-002 / RQ-009）
# 全 restic 操作に適用する flock ベースの lock contract。
#   shared      : restore / check（prune と同時不可）
#   exclusive   : full / checkpoint / binlog snapshot（短い bounded timeout）
#   maintenance : forget/prune（専用 role、通常 job と排他）
# lock は fd 経由で保持し、script 終了時に自動解放される。
# owner metadata は REPOSITORY_LOCK_DIR/owner.json に書く。
# ============================================================

REPOSITORY_LOCK_DIR=${REPOSITORY_LOCK_DIR:-/var/lib/ses-backup/locks}
REPO_LOCK_FD=9

repository_lock::acquire() { # mode timeout_seconds owner
  local mode=$1 timeout=$2 owner=$3
  case "$mode" in
    shared) : ;;
    exclusive|maintenance) : ;;
    *) echo "lock: 不正な mode です: $mode" >&2; return 2 ;;
  esac
  common::is_int "$timeout" || { echo "lock: timeout が整数ではありません: $timeout" >&2; return 2; }

  mkdir -p "$REPOSITORY_LOCK_DIR"
  local lockfile="$REPOSITORY_LOCK_DIR/repository.lock"
  if ! exec {REPO_LOCK_FD}>"$lockfile"; then
    echo "lock: lock file を開けません: $lockfile" >&2
    return 1
  fi

  local flock_opts=(-w "$timeout")
  [[ "$mode" == "shared" ]] && flock_opts+=(-s)

  if ! flock "${flock_opts[@]}" "$REPO_LOCK_FD"; then
    echo "lock: 取得できませんでした mode=$mode timeout=${timeout}s owner=$owner" >&2
    exec {REPO_LOCK_FD}>&-
    REPO_LOCK_FD=9
    return 1
  fi

  local owner_json
  owner_json=$(jq -n \
    --arg acquired_at_utc "$(common::now_utc)" \
    --arg mode "$mode" \
    --arg owner "$owner" \
    --arg pid "$$" \
    '{acquired_at_utc: $acquired_at_utc, mode: $mode, owner: $owner, pid: $pid}')
  printf '%s\n' "$owner_json" > "$REPOSITORY_LOCK_DIR/owner.json"
  chmod 600 "$REPOSITORY_LOCK_DIR/owner.json"
  return 0
}

repository_lock::release() {
  if [[ -n "${REPO_LOCK_FD:-}" && "$REPO_LOCK_FD" != "9" ]]; then
    exec {REPO_LOCK_FD}>&-
  fi
  REPO_LOCK_FD=9
  return 0
}

# script 全体で lock を保持するための trap 設定（EXIT 時に解放）
repository_lock::trap_release() {
  trap 'repository_lock::release' EXIT
}
