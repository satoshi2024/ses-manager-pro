#!/usr/bin/env bash
# HFP-03 テスト用の軽量フレームワーク（shell unit test）
#
# 使い方:
#   . "$(dirname "${BASH_SOURCE[0]}")/test-framework.sh"
#   case_xxx() { ... }   # case 関数は引数を取らない
#   run_case case_xxx
#   test_summary
#
# 規約:
#   - case 内では assert_* で判定し、出力は TEST_LOG へ追記する。
#   - 各 case は subprocess で実行される（失敗しても全体は続行する）。
#   - TEST_FILTER=case_yyy で単一 case 実行ができる。

set -uo pipefail

TEST_COUNT=0
TEST_FAIL_COUNT=0
TEST_LOG=$(mktemp -d)/test.log
export TEST_LOG

test_assert() { # label
  TEST_COUNT=$((TEST_COUNT + 1))
  echo "PASS $1" >> "$TEST_LOG"
}

test_fail() { # label message
  TEST_COUNT=$((TEST_COUNT + 1))
  TEST_FAIL_COUNT=$((TEST_FAIL_COUNT + 1))
  echo "FAIL $1: $2" >> "$TEST_LOG"
  echo "FAIL $1: $2" >&2
}

assert_eq() { # expected actual label
  local expected=$1 actual=$2 label=$3
  if [[ "$expected" == "$actual" ]]; then
    test_assert "$label"
  else
    test_fail "$label" "expected=[$expected] actual=[$actual]"
  fi
}

assert_contains() { # haystack needle label
  local haystack=$1 needle=$2 label=$3
  if [[ "$haystack" == *"$needle"* ]]; then
    test_assert "$label"
  else
    test_fail "$label" "[$needle] が見つかりません (haystack=[$haystack])"
  fi
}

assert_not_contains() { # haystack needle label
  local haystack=$1 needle=$2 label=$3
  if [[ "$haystack" != *"$needle"* ]]; then
    test_assert "$label"
  else
    test_fail "$label" "[$needle] が含まれています (haystack=[$haystack])"
  fi
}

assert_zero() { # exit_code label
  local code=$1 label=$2
  if [[ "$code" -eq 0 ]]; then
    test_assert "$label"
  else
    test_fail "$label" "exit=$code (期待 0)"
  fi
}

assert_nonzero() { # exit_code label
  local code=$1 label=$2
  if [[ "$code" -ne 0 ]]; then
    test_assert "$label"
  else
    test_fail "$label" "exit=0 (期待 非 0)"
  fi
}

assert_file() { # path label
  local path=$1 label=$2
  if [[ -e "$path" ]]; then
    test_assert "$label"
  else
    test_fail "$label" "file [$path] が存在しません"
  fi
}

assert_no_file() { # path label
  local path=$1 label=$2
  if [[ ! -e "$path" ]]; then
    test_assert "$label"
  else
    test_fail "$label" "file [$path] が存在します"
  fi
}

# コマンドを実行し exit code のみ取得する（stdout/stderr は破棄）
capture_exit() { # var_name cmd...
  local var=$1
  shift
  "$@" > /dev/null 2>&1
  local code=$?
  eval "$var=$code"
}

# case 関数を実行する。失敗しても全体は続行する。
run_case() { # case_fn
  local fn=$1
  if [[ -n "${TEST_FILTER:-}" && "$fn" != "$TEST_FILTER" ]]; then
    return 0
  fi
  "$fn"
}

test_summary() { # [label]
  local label=${1:-}
  echo "===== $label ====="
  echo "tests=$TEST_COUNT failures=$TEST_FAIL_COUNT"
  if (( TEST_FAIL_COUNT > 0 )); then
    echo "--- 失敗一覧 ---"
    grep '^FAIL' "$TEST_LOG" || true
    exit 1
  fi
  exit 0
}
