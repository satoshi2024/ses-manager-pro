#!/usr/bin/env bash
set -Eeuo pipefail
: "${BACKUP_REPOSITORY:?BACKUP_REPOSITORY is required}"; : "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE
dir=${BINLOG_WORK_DIR:-/var/lib/ses-backup/binlog}; [[ -d "$dir" ]] || { echo 'binlogディレクトリなし' >&2; exit 1; }
restic backup "$dir" --tag binlog --tag "date-$(date -u +%Y%m%dT%H%M%SZ)"
