#!/usr/bin/env bash
# CIと同じ条件でテストを実行し、CIとの差分（skipされたテスト）を明示する。
# .github/workflows/ci.yml と同じコマンド・同じ判定を行うので、
# ここが緑なら push 後のCIも（環境差では）落ちない。
#
# 使い方:
#   ./scripts/verify-like-ci.sh              # 全テスト
#   ./scripts/verify-like-ci.sh -Dtest=Foo   # 追加引数はそのままmvnへ渡す
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

MVN=mvn
if [ -x "./apache-maven-3.9.6/bin/mvn" ]; then
  MVN="./apache-maven-3.9.6/bin/mvn"
fi

echo "=== 前提ツールの確認（CIとの差分） ==="
"$MVN" -v 2>/dev/null | head -1
java -version 2>&1 | head -1

docker_ok=0
if docker info > /dev/null 2>&1; then
  docker_ok=1
  echo "Docker : あり  -> Flyway系のMySQL smoke（マイグレーション検証）が実行されます"
else
  echo "Docker : なし  -> Flyway*SmokeTest / FlywayRepairRunbookTest / ConcurrentUpdateTest はskipされます。"
  echo "                 これらはMySQL方言・マイグレーション衝突を検出する唯一のテストで、CIでは必ず実行されます。"
  echo "                 つまり今の環境で緑でも、CIで落ちる可能性が残ります。"
fi

if node --version > /dev/null 2>&1; then
  echo "Node   : $(node --version)  -> JS構文チェック(JsSyntaxCheckTest)が実行されます"
else
  echo "Node   : なし  -> JsSyntaxCheckTest はskipされます（CIでは必須）"
fi
echo

echo "=== mvn -B clean test ==="
"$MVN" -B clean test "$@"
test_status=$?

echo
echo "=== skipされたテストの確認（CIと同じ判定） ==="
skipped_files=$(grep -l 'skipped="[1-9]' target/surefire-reports/*.xml 2>/dev/null || true)
if [ -n "$skipped_files" ]; then
  echo "以下のテストがskipされました。CIはこの状態を失敗として扱います:"
  echo "$skipped_files"
  if [ "$docker_ok" -eq 0 ]; then
    echo
    echo "Dockerを起動してから再実行すると、CIと同じ範囲を検証できます。"
  fi
  skip_status=1
else
  echo "skipされたテストはありません（CIと同じ範囲を検証できています）"
  skip_status=0
fi

if [ "$test_status" -ne 0 ]; then
  exit "$test_status"
fi

echo
echo "=== HFP-03-011: backup integration suite（実 MySQL PITR） ==="
if [ "$docker_ok" -eq 1 ]; then
  echo "Docker あり -> integration suite を実行します（数分かかります）"
  if bash ops/backup/tests/run-integration.sh; then
    echo "integration suite: SUCCESS"
    integration_status=0
  else
    echo "integration suite: FAIL（CI と同じ判定で失敗扱い）" >&2
    integration_status=1
  fi
else
  echo "Docker なし -> integration suite は実行できません（CI では必須・失敗扱い）" >&2
  integration_status=1
fi

if [ "$skip_status" -ne 0 ] || [ "$integration_status" -ne 0 ]; then
  exit 1
fi
exit 0
