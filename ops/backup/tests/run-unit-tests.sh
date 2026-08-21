#!/usr/bin/env bash
# HFP-03 unit test ランナー（ホスト側）
# 実装は pinned ツールイメージ（Dockerfile）内で実行する。
# - Docker が無ければ BLOCKED（CI では failure、ローカルは BLOCKED 表示）
# - 使い方: ops/backup/tests/run-unit-tests.sh [--no-build] [TEST_FILTER=...]
set -Eeuo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
IMAGE_TAG=${SES_BACKUP_TEST_IMAGE:-ses-backup-tool:test}
BUILD_FLAG=1

if [[ "${1:-}" == "--no-build" ]]; then BUILD_FLAG=0; shift || true; fi

if ! docker info > /dev/null 2>&1; then
  echo "BLOCKED: Docker が利用できません。unit test はツールイメージ内で実行します。" >&2
  exit 2
fi

if (( BUILD_FLAG )); then
  echo "== docker build (pinned tool image) =="
  docker build -t "$IMAGE_TAG" "$ROOT/ops/backup"
fi

WINROOT=$(cygpath -w "$ROOT" 2>/dev/null || echo "$ROOT")

# :ro だとコンテナ内で chmod +x できない。Windows checkout は filemode を落とすことが
# あり、Linux CI では Permission denied (exit 126) になるため rw でマウントする。
echo "== unit tests (in $IMAGE_TAG) =="
MSYS_NO_PATHCONV=1 docker run --rm \
  -e TEST_FILTER="${TEST_FILTER:-}" \
  -v "$WINROOT:/repo" \
  -w /repo \
  "$IMAGE_TAG" bash /repo/ops/backup/tests/run-all-unit-tests.sh
