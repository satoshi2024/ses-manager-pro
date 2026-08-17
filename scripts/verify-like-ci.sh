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
  echo "Docker : あり  -> mysql-tests profileを実行できます"
else
  echo "Docker : なし  -> CI full suiteは実行できません"
fi

if node --version > /dev/null 2>&1; then
  node_ok=1
  echo "Node   : $(node --version)  -> JS構文チェック(JsSyntaxCheckTest)が実行されます"
else
  node_ok=0
  echo "Node   : なし  -> CI fast suiteは実行できません"
fi
echo

if [ "$docker_ok" -ne 1 ] || [ "$node_ok" -ne 1 ]; then
  echo "DockerとNode.jsを準備してから再実行してください。" >&2
  exit 1
fi

run_suite() {
  suite_name=$1
  profile=$2
  shift 2
  echo
  echo "=== $suite_name ==="
  if [ -n "$profile" ]; then
    "$MVN" -B clean test "-P$profile" "$@"
  else
    "$MVN" -B clean test "$@"
  fi
  suite_status=$?
  if [ "$suite_status" -ne 0 ]; then
    exit "$suite_status"
  fi
  skipped_files=$(grep -l 'skipped="[1-9]' target/surefire-reports/*.xml 2>/dev/null || true)
  if [ -n "$skipped_files" ]; then
    echo "以下のテストがskipされました。CIはこの状態を失敗として扱います:"
    echo "$skipped_files"
    exit 1
  fi
  echo "skipされたテストはありません"
}

run_suite "fast tests (H2 / unit / MVC)" "" "$@"
run_suite "MySQL integration / Flyway" "mysql-tests" "$@"
run_suite "performance regression" "performance-tests" "$@"

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

if [ "$integration_status" -ne 0 ]; then
  exit 1
fi
exit 0
