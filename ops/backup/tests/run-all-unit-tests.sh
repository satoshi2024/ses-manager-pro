#!/usr/bin/env bash
# HFP-03 unit test ランナー
# Docker イメージ内でもホスト直実行でも動く。
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export PATH="$HERE/fixtures/bin:/usr/local/bin:$PATH"

failed=0
shopt -s nullglob
tests=("$HERE"/*-test.sh)
if ((${#tests[@]} == 0)); then
  echo "ERROR: *-test.sh が見つかりません: $HERE" >&2
  exit 1
fi
for t in "${tests[@]}"; do
  echo "== $(basename "$t") =="
  if ! bash "$t"; then
    echo "FAILED: $(basename "$t")" >&2
    failed=1
  fi
done
exit "$failed"
