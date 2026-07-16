#!/usr/bin/env bash
set -Eeuo pipefail
: "${DRILL_MYSQL_CONTAINER:?DRILL_MYSQL_CONTAINER is required}"; docker exec "$DRILL_MYSQL_CONTAINER" mysqladmin ping
echo '隔離環境での復元演習を実施し、件数・uploadsハッシュ・所要時間を記録してください。'
