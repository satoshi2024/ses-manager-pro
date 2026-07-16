#!/usr/bin/env bash
set -Eeuo pipefail
: "${BACKUP_REPOSITORY:?BACKUP_REPOSITORY is required}"; : "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE
latest=$(restic snapshots --tag full --latest 1 --json | jq -r '.[0].time // empty'); [[ -n "$latest" ]] || { echo '全備がありません' >&2; exit 1; }
age=$(( $(date +%s) - $(date -d "$latest" +%s) )); (( age <= ${MAX_FULL_AGE_HOURS:-26}*3600 )) || { echo '全備が古すぎます' >&2; exit 1; }
dir=${BINLOG_WORK_DIR:-/var/lib/ses-backup/binlog}; if [[ -d "$dir" ]] && find "$dir" -type f -mmin +"${MAX_BINLOG_AGE_MINUTES:-30}" -print -quit | grep -q .; then echo 'binlog同期遅延' >&2; exit 1; fi
echo 'backup health: OK'
