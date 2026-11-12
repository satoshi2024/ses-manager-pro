#!/usr/bin/env bash
# W1-B1 / X-08(b) ツールイメージの再現性・配布の検証（実 Docker・ネットワークは使わない）
#
# - image/tool-image.sh check / compose: production compose の pin（x-ses-backup-tool-image-id）が
#   占位値・不正・ローカル image ID と不一致なら拒否し、docker compose を実行しない
# - image/tool-image.sh load: 配布 tar の SHA-256 が一致しなければ docker load しない。
#   読み込んだ tag の image ID が一致しなければ失敗する
# - image/install-toolchain.sh: 固定値が占位値なら何もダウンロードせずに失敗し、
#   ダウンロードした署名鍵の SHA-256 が一致しなければ鍵を読み込まずに失敗する
# - Dockerfile / compose の静的契約（base digest、restic SHA-256、mysql-apt-config 不使用、
#   integration の mysql:8.0.36 digest 固定、production compose の build 禁止・pull_policy never）
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
BACKUP_ROOT="$ROOT/ops/backup"
TOOL_IMAGE="$BACKUP_ROOT/image/tool-image.sh"
INSTALL_TOOLCHAIN="$BACKUP_ROOT/image/install-toolchain.sh"
PROD_COMPOSE="$BACKUP_ROOT/docker-compose.yml"
PIN_ID="sha256:$(printf 'a%.0s' $(seq 1 64))"
OTHER_ID="sha256:$(printf 'b%.0s' $(seq 1 64))"

# fake docker: 呼び出しを FAKE_DOCKER_LOG に記録し、image inspect は FAKE_IMAGE_ID を返す（空なら not found）
setup_fake_docker() {
  T=$(mktemp -d)
  mkdir -p "$T/bin"
  cat > "$T/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$1 ${2:-}" in
  "image inspect")
    [[ -n "${FAKE_IMAGE_ID:-}" ]] || { echo "Error: No such image" >&2; exit 1; }
    printf '%s\n' "$FAKE_IMAGE_ID"
    exit 0 ;;
  "load -i") exit 0 ;;
  compose*) exit 0 ;;
esac
exit 0
MOCK
  chmod +x "$T/bin/docker"
  export FAKE_DOCKER_LOG="$T/docker.log"
  : > "$FAKE_DOCKER_LOG"
  export DOCKER_BIN="$T/bin/docker"
  unset FAKE_IMAGE_ID
}

# 現行の production compose を複製し、pin 値を差し替えたものを作る
pinned_compose() { # <out> <pin>
  sed "s|^x-ses-backup-tool-image-id: .*|x-ses-backup-tool-image-id: \"$2\"|" "$PROD_COMPOSE" > "$1"
}

run_tool() {
  TOOL_OUT=$(bash "$TOOL_IMAGE" "$@" 2>&1)
  TOOL_RC=$?
}

case_check_rejects_placeholder_pin() {
  setup_fake_docker
  pinned_compose "$T/compose.yml" "sha256:UNPINNED"
  export FAKE_IMAGE_ID="$PIN_ID"
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "占位値の pin は拒否"
  assert_contains "$TOOL_OUT" "未 pin" "未 pin を通知"
}

case_check_rejects_committed_compose_until_pinned() {
  setup_fake_docker
  local pin
  pin=$(sed -nE 's/^x-ses-backup-tool-image-id:[[:space:]]*"?([^"[:space:]]*)"?.*$/\1/p' "$PROD_COMPOSE")
  if [[ "$pin" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    # pin 済みの場合: 同じ image ID のローカル image だけを受理する
    export FAKE_IMAGE_ID="$pin"
    run_tool check --compose "$PROD_COMPOSE"
    assert_zero "$TOOL_RC" "pin 済みの production compose は一致する image で受理"
  else
    export FAKE_IMAGE_ID="$PIN_ID"
    run_tool check --compose "$PROD_COMPOSE"
    assert_nonzero "$TOOL_RC" "pin されていない production compose は拒否"
    assert_contains "$TOOL_OUT" "未 pin" "production compose の未 pin を通知"
  fi
}

case_check_accepts_matching_image() {
  setup_fake_docker
  pinned_compose "$T/compose.yml" "$PIN_ID"
  export FAKE_IMAGE_ID="$PIN_ID"
  run_tool check --compose "$T/compose.yml"
  assert_zero "$TOOL_RC" "pin とローカル image ID が一致すれば 0"
  assert_contains "$TOOL_OUT" "OK: ses-backup-tool:8.0.46 = $PIN_ID" "照合結果を表示"
}

case_check_rejects_mismatch_and_missing_image() {
  setup_fake_docker
  pinned_compose "$T/compose.yml" "$PIN_ID"
  export FAKE_IMAGE_ID="$OTHER_ID"
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "ローカル image ID が pin と違えば拒否"
  assert_contains "$TOOL_OUT" "一致しません" "不一致を通知"
  unset FAKE_IMAGE_ID
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "ローカルに image が無ければ拒否"
  assert_contains "$TOOL_OUT" "ありません" "image 不在を通知"
}

case_check_rejects_build_and_pull() {
  setup_fake_docker
  export FAKE_IMAGE_ID="$PIN_ID"
  pinned_compose "$T/compose.yml" "$PIN_ID"
  sed 's|^  image: ses-backup-tool|  build: .\n  image: ses-backup-tool|' "$T/compose.yml" > "$T/compose-build.yml"
  run_tool check --compose "$T/compose-build.yml"
  assert_nonzero "$TOOL_RC" "build: を含む production compose は拒否"
  assert_contains "$TOOL_OUT" "build:" "build: を通知"
  grep -v 'pull_policy' "$T/compose.yml" > "$T/compose-pull.yml"
  run_tool check --compose "$T/compose-pull.yml"
  assert_nonzero "$TOOL_RC" "pull_policy: never の無い production compose は拒否"
  assert_contains "$TOOL_OUT" "pull_policy: never" "pull_policy を通知"
}

case_compose_runs_only_after_check() {
  setup_fake_docker
  local pin
  pin=$(sed -nE 's/^x-ses-backup-tool-image-id:[[:space:]]*"?([^"[:space:]]*)"?.*$/\1/p' "$PROD_COMPOSE")
  export FAKE_IMAGE_ID="$OTHER_ID"
  run_tool compose run --rm preflight
  assert_nonzero "$TOOL_RC" "pin と一致しない状態では compose を実行しない"
  assert_not_contains "$(cat "$FAKE_DOCKER_LOG")" "compose -f" "docker compose を呼ばない"
  if [[ "$pin" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    export FAKE_IMAGE_ID="$pin"
    run_tool compose run --rm preflight
    assert_zero "$TOOL_RC" "pin と一致すれば compose を実行"
    assert_contains "$(cat "$FAKE_DOCKER_LOG")" "run --rm preflight" "引数をそのまま docker compose へ渡す"
  fi
}

case_load_verifies_tar_and_image_id() {
  setup_fake_docker
  printf 'fake image tar\n' > "$T/image.tar"
  local sha
  sha=$(sha256sum "$T/image.tar" | cut -d' ' -f1)
  export FAKE_IMAGE_ID="$PIN_ID"
  run_tool load --tar "$T/image.tar" --sha256 "$(printf '0%.0s' $(seq 1 64))" --image-id "$PIN_ID"
  assert_nonzero "$TOOL_RC" "tar の SHA-256 不一致は失敗"
  assert_contains "$TOOL_OUT" "SHA-256 が一致しません" "SHA-256 不一致を通知"
  assert_not_contains "$(cat "$FAKE_DOCKER_LOG")" "load -i" "SHA-256 不一致なら docker load しない"

  run_tool load --tar "$T/image.tar" --sha256 "$sha" --image-id "$OTHER_ID"
  assert_nonzero "$TOOL_RC" "読み込んだ image ID が期待と違えば失敗"
  assert_contains "$TOOL_OUT" "image ID が一致しません" "image ID 不一致を通知"

  run_tool load --tar "$T/image.tar" --sha256 "$sha" --image-id "$PIN_ID"
  assert_zero "$TOOL_RC" "SHA-256 と image ID が一致すれば 0"
  assert_contains "$(cat "$FAKE_DOCKER_LOG")" "load -i" "検証後に docker load する"
}

# install-toolchain.sh 用の fake（apt-get / curl / gpg / dpkg）。実ネットワーク・実 apt には触れない。
setup_fake_installer() {
  T=$(mktemp -d)
  mkdir -p "$T/bin"
  cat > "$T/bin/apt-get" <<'MOCK'
#!/usr/bin/env bash
printf 'apt-get %s\n' "$*" >> "$FAKE_INSTALL_LOG"
exit 0
MOCK
  cat > "$T/bin/gpg" <<'MOCK'
#!/usr/bin/env bash
printf 'gpg %s\n' "$*" >> "$FAKE_INSTALL_LOG"
exit 0
MOCK
  cat > "$T/bin/curl" <<'MOCK'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "$FAKE_INSTALL_LOG"
out=""
while (( $# > 0 )); do
  if [[ "$1" == -o ]]; then out=$2; shift 2; continue; fi
  shift
done
[[ -n "$out" ]] && printf 'fake downloaded content\n' > "$out"
exit 0
MOCK
  cat > "$T/bin/dpkg" <<'MOCK'
#!/usr/bin/env bash
[[ "${1:-}" == --print-architecture ]] && { echo amd64; exit 0; }
exit 1
MOCK
  chmod +x "$T/bin/apt-get" "$T/bin/gpg" "$T/bin/curl" "$T/bin/dpkg"
  export FAKE_INSTALL_LOG="$T/install.log"
  : > "$FAKE_INSTALL_LOG"
}

run_installer() { # [VAR=value ...]
  INSTALL_OUT=$(env PATH="$T/bin:$PATH" TMPDIR="$T" \
    BASE_IMAGE="debian:bookworm-slim@sha256:$(printf 'c%.0s' $(seq 1 64))" \
    MYSQL_CLIENT_VERSION=8.0.46 MYSQL_DEB_REVISION=1debian12 MYSQL_APT_SUITE=bookworm MYSQL_APT_COMPONENT=mysql-8.0 \
    MYSQL_REPO_KEY_URL=https://repo.mysql.com/RPM-GPG-KEY-mysql-2025 \
    MYSQL_REPO_KEY_SHA256="$(printf 'd%.0s' $(seq 1 64))" \
    MYSQL_REPO_KEY_FINGERPRINT=BCA43417C3B485DD128EC6D4B7B3B788A8D3785C \
    RESTIC_VERSION=0.17.3 RESTIC_LINUX_AMD64_SHA256="$(printf 'e%.0s' $(seq 1 64))" \
    IMAGE_MANIFEST_DIR="$T/manifest" \
    "$@" bash "$INSTALL_TOOLCHAIN" 2>&1)
  INSTALL_RC=$?
}

case_installer_rejects_placeholders_before_download() {
  setup_fake_installer
  run_installer MYSQL_REPO_KEY_SHA256=UNPINNED
  assert_nonzero "$INSTALL_RC" "署名鍵の SHA-256 が占位値なら失敗"
  assert_contains "$INSTALL_OUT" "MYSQL_REPO_KEY_SHA256 が SHA-256" "占位値の項目を通知"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "占位値のままでは apt・ダウンロードを一切行わない"

  : > "$FAKE_INSTALL_LOG"
  run_installer RESTIC_LINUX_AMD64_SHA256=UNPINNED
  assert_nonzero "$INSTALL_RC" "restic の SHA-256 が占位値なら失敗"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "restic の占位値でも何も実行しない"

  : > "$FAKE_INSTALL_LOG"
  run_installer BASE_IMAGE=debian:bookworm-slim
  assert_nonzero "$INSTALL_RC" "base image が digest 固定でなければ失敗"
  assert_contains "$INSTALL_OUT" "BASE_IMAGE が digest で固定されていません" "base digest 未固定を通知"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "base digest 未固定でも何も実行しない"
}

case_installer_rejects_key_checksum_mismatch() {
  setup_fake_installer
  run_installer
  assert_nonzero "$INSTALL_RC" "署名鍵の SHA-256 不一致は失敗"
  assert_contains "$INSTALL_OUT" "MySQL APT 署名鍵" "署名鍵の検証失敗を通知"
  assert_contains "$INSTALL_OUT" "一致しません" "不一致を通知"
  assert_contains "$(cat "$FAKE_INSTALL_LOG")" "curl " "鍵はダウンロードした（前提）"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "gpg " "SHA-256 不一致の鍵は読み込まない"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "mysql-community-client" "検証前に MySQL パッケージを導入しない"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "restic" "検証失敗後に restic をダウンロードしない"
}

case_static_image_contract() {
  local dockerfile integration prod
  dockerfile=$(cat "$BACKUP_ROOT/Dockerfile")
  integration=$(cat "$BACKUP_ROOT/tests/docker-compose.integration.yml")
  prod=$(cat "$PROD_COMPOSE")
  if [[ "$dockerfile" =~ ARG\ BASE_IMAGE=debian:bookworm-slim@sha256:[0-9a-f]{64} ]]; then
    test_assert "base image は digest 固定"
  else
    test_fail "base image は digest 固定" "ARG BASE_IMAGE が digest 固定ではありません"
  fi
  if [[ "$dockerfile" =~ ARG\ RESTIC_LINUX_AMD64_SHA256=[0-9a-f]{64} ]]; then
    test_assert "restic の SHA-256 を固定"
  else
    test_fail "restic の SHA-256 を固定" "RESTIC_LINUX_AMD64_SHA256 が 64 桁 hex ではありません"
  fi
  assert_contains "$dockerfile" "ARG MYSQL_REPO_KEY_FINGERPRINT=BCA43417C3B485DD128EC6D4B7B3B788A8D3785C" "MySQL 署名鍵の full fingerprint を固定"
  assert_not_contains "$dockerfile" "mysql-apt-config_" "SHA-256 を確認できない mysql-apt-config deb をダウンロードしない"
  assert_contains "$dockerfile" "install-toolchain.sh" "導入は検証付きの install-toolchain.sh で行う"
  if [[ "$integration" =~ image:\ mysql:8\.0\.36@sha256:[0-9a-f]{64} ]]; then
    test_assert "integration の mysql:8.0.36 を digest 固定"
  else
    test_fail "integration の mysql:8.0.36 を digest 固定" "tag だけの参照です"
  fi
  assert_not_contains "$prod" "  build:" "production compose は build しない"
  assert_contains "$prod" "pull_policy: never" "production compose は registry から pull しない"
  assert_contains "$prod" "x-ses-backup-tool-image-id:" "production compose は記録した image ID を参照する"
}

run_case case_check_rejects_placeholder_pin
run_case case_check_rejects_committed_compose_until_pinned
run_case case_check_accepts_matching_image
run_case case_check_rejects_mismatch_and_missing_image
run_case case_check_rejects_build_and_pull
run_case case_compose_runs_only_after_check
run_case case_load_verifies_tar_and_image_id
run_case case_installer_rejects_placeholders_before_download
run_case case_installer_rejects_key_checksum_mismatch
run_case case_static_image_contract

test_summary "W1-B1 tool image reproducibility"
