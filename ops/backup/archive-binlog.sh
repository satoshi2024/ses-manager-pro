#!/usr/bin/env bash
set -Eeuo pipefail
: "${MYSQL_HOST:?MYSQL_HOST is required}"; : "${MYSQL_PWD_FILE:?MYSQL_PWD_FILE is required}"
ROOT=${BINLOG_WORK_DIR:-/var/lib/ses-backup/binlog}; mkdir -p "$ROOT"; MYSQL_PWD=$(<"$MYSQL_PWD_FILE"); export MYSQL_PWD
exec mysqlbinlog --read-from-remote-server --raw --stop-never --host="$MYSQL_HOST" --port="${MYSQL_PORT:-3306}" --user="${MYSQL_USER:-backup}" --result-file="$ROOT/" --verify-binlog-checksum
