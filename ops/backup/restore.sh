#!/usr/bin/env bash
set -Eeuo pipefail
usage(){ echo 'Usage: restore.sh --target YYYY-MM-DDTHH:MM:SSZ [--apply]'; }
target=; apply=false; while (($#)); do case "$1" in --target) target=$2; shift 2;; --apply) apply=true; shift;; --help) usage; exit 0;; *) usage >&2; exit 2;; esac; done
[[ -n "$target" ]] || { usage >&2; exit 2; }; : "${BACKUP_REPOSITORY:?BACKUP_REPOSITORY is required}"; : "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"; : "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
export RESTIC_REPOSITORY="$BACKUP_REPOSITORY" RESTIC_PASSWORD_FILE; snap=$(restic snapshots --tag full --latest 1 --json | jq -r '.[0].short_id // empty'); [[ -n "$snap" ]] || { echo '復元可能な全備なし' >&2; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT; restic restore "$snap" --target "$tmp" --verify; echo "対象時刻=$target、候補全備=$snap を検証しました。"
[[ "$apply" == true ]] || { echo '--apply を指定しない検証のみで終了'; exit 0; }; [[ "${CONFIRM_RESTORE:-}" == YES ]] || { echo 'CONFIRM_RESTORE=YES が必要' >&2; exit 2; }
[[ -z "${APP_STOP_COMMAND:-}" ]] || bash -c "$APP_STOP_COMMAND"; : "${MYSQL_PWD_FILE:?MYSQL_PWD_FILE is required}"; export MYSQL_PWD; MYSQL_PWD=$(<"$MYSQL_PWD_FILE")
mysql -h "${MYSQL_HOST:?}" -P "${MYSQL_PORT:-3306}" -u "${MYSQL_USER:-root}" "$MYSQL_DATABASE" < "$(find "$tmp" -name database.sql -print -quit)"; [[ -z "${APP_START_COMMAND:-}" ]] || bash -c "$APP_START_COMMAND"; echo 'DB復元完了'
