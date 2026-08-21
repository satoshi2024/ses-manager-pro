#!/usr/bin/env bash
# ============================================================
# 書込み静止 protocol（HFP-03-002 / RQ-002）
# provider は repository 内の固定 executable（providers/*）と明示引数で呼ぶ。
# APP_STOP_COMMAND のような任意 bash -c の env 実行は行わない。
# 静止取得に失敗したら snapshot を発行してはならない（fail-closed）。
# ============================================================

LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROVIDERS_DIR="$LIB_DIR/providers"
[[ -d "$PROVIDERS_DIR" ]] || PROVIDERS_DIR=$(cd "$LIB_DIR/../providers" && pwd)

QUIESCE_PROVIDER=${QUIESCE_PROVIDER:-quiesce-local}
QUIESCE_STATE_DIR=${QUIESCE_STATE_DIR:-${TMPDIR:-/tmp}/ses-quiesce-$$}

# 静止取得。成功時は $QUIESCE_STATE_DIR/quiesce.json が書かれている。
quiesce::acquire() {
  mkdir -p "$QUIESCE_STATE_DIR"
  case "$QUIESCE_PROVIDER" in
    quiesce-local)
      "$PROVIDERS_DIR/quiesce-local.sh" acquire "$QUIESCE_STATE_DIR"
      ;;
    *)
      echo "quiesce: 未知の provider です: $QUIESCE_PROVIDER" >&2
      return 2
      ;;
  esac
}

# 静止解除。失敗時は重大 alert 相当（呼び出し元で incident 扱いにする）
quiesce::release() {
  case "$QUIESCE_PROVIDER" in
    quiesce-local)
      "$PROVIDERS_DIR/quiesce-local.sh" release "$QUIESCE_STATE_DIR"
      ;;
    *)
      echo "quiesce: 未知の provider です: $QUIESCE_PROVIDER" >&2
      return 2
      ;;
  esac
}

# 静止区間の情報（quiesce.json の内容を JSON で返す）
quiesce::status_json() {
  cat "$QUIESCE_STATE_DIR/quiesce.json"
}
