#!/usr/bin/env bash
# HFP-03 unit test ランナー（ツールイメージ内で実行される）
# ops/backup/tests/*-test.sh を順に実行し、1 つでも失敗すれば非 0 で終了する。
set -uo pipefail

export PATH="/repo/ops/backup/tests/fixtures/bin:$PATH"
failed=0
for t in /repo/ops/backup/tests/*-test.sh; do
  echo "== $(basename "$t") =="
  if ! bash "$t"; then
    echo "FAILED: $(basename "$t")" >&2
    failed=1
  fi
done
exit "$failed"
