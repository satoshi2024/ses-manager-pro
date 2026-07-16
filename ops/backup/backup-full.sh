#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
ROOT=${BACKUP_WORK_DIR:-/var/lib/ses-backup}; mkdir -p "$ROOT"
: "${BACKUP_REPOSITORY:?BACKUP_REPOSITORY is required}"; : "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"; : "${MYSQL_HOST:?MYSQL_HOST is required}"; : "${MYSQL_PWD_FILE:?MYSQL_PWD_FILE is required}"
export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE MYSQL_PWD; MYSQL_PWD=$(<"$MYSQL_PWD_FILE")
MYSQL_USER=${MYSQL_USER:-root}; MYSQL_PORT=${MYSQL_PORT:-3306}; UPLOADS_DIR=${UPLOADS_DIR:-/app/uploads}
stamp=$(date -u +%Y%m%dT%H%M%SZ); work="$ROOT/full-$stamp"; trap 'rm -rf "$work"' EXIT; mkdir -p "$work/db"
mysqldump --single-transaction --quick --routines --events --triggers --hex-blob --source-data=2 -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "$MYSQL_DATABASE" > "$work/db/database.sql"
[[ -d "$UPLOADS_DIR" ]] && tar -C "$UPLOADS_DIR" -cf "$work/uploads.tar" . || true
(cd "$work" && find . -type f -print0 | sort -z | xargs -0 sha256sum > manifest.sha256)
echo "flyway_version=${FLYWAY_VERSION:-unknown}" > "$work/metadata.txt"
restic backup "$work" --tag full --tag "date-$stamp"; restic forget --keep-daily 30 --keep-weekly 8 --keep-monthly 12 --prune
