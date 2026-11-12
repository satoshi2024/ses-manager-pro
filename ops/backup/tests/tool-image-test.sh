#!/usr/bin/env bash
# W1-B1 / X-08(b) ツールイメージの再現性・配布の検証（実 Docker・ネットワークは使わない）
#
# - image/tool-image.sh check / compose: production compose の pin（x-ses-backup-tool-content-id）が
#   占位値・不正・ローカルの content ID と不一致なら拒否し、docker compose を実行しない。
#   pin と照合は image store（classic / containerd）に依存しない content ID で行う（W1-B1 r2 P2-2）。
# - image/tool-image.sh compose: 許可した使い方（run --rm <service> / up -d binlog / ps / logs / stop）だけを渡し、
#   -f・--pull・--build などの compose 側 option や未定義の service は docker を呼ばずに拒否する。
# - image/tool-image.sh record / save / load: image store の種類・content ID・image ID を記録し、
#   配布 tar は SHA-256 が一致しなければ docker load せず、読み込んだ tag の content ID で照合する。
# - image/install-toolchain.sh: 固定値が占位値なら何もダウンロードせずに失敗し、署名鍵は SHA-256・
#   primary key が 1 つであること・primary key の fingerprint・有効期限を検証してから keyring にする。
# - Dockerfile / compose / .dockerignore の静的契約
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
. "$HERE/lib/test-framework.sh"
ROOT=$(cd "$HERE/../../.." && pwd)
BACKUP_ROOT="$ROOT/ops/backup"
TOOL_IMAGE="$BACKUP_ROOT/image/tool-image.sh"
INSTALL_TOOLCHAIN="$BACKUP_ROOT/image/install-toolchain.sh"
PROD_COMPOSE="$BACKUP_ROOT/docker-compose.yml"
hex64() { printf "${1}%.0s" $(seq 1 64); }
ID_CLASSIC="sha256:$(hex64 a)"
ID_CONTAINERD="sha256:$(hex64 b)"
LAYERS_DEFAULT="sha256:$(hex64 1)\nsha256:$(hex64 2)\n"
CONFIG_DEFAULT='["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"] null ["bash"] "/var/lib/ses-backup" "" null'
STATUS_CLASSIC='[["Backing Filesystem","extfs"],["Supports d_type","true"]]'
STATUS_CONTAINERD='[["driver-type","io.containerd.snapshotter.v1"]]'

# fake docker: 呼び出しを FAKE_DOCKER_LOG に記録し、image inspect / info は FAKE_* を返す
setup_fake_docker() {
  T=$(mktemp -d)
  mkdir -p "$T/bin"
  cat > "$T/bin/docker" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$*" in
  "info --format {{json .DriverStatus}}") printf '%s\n' "$FAKE_DRIVER_STATUS"; exit 0 ;;
  "info --format {{.Driver}}") printf '%s\n' "$FAKE_STORAGE_DRIVER"; exit 0 ;;
  "version --format "*) printf '%s\n' 27.5.1; exit 0 ;;
esac
case "${1:-} ${2:-}" in
  "image inspect")
    [[ -n "${FAKE_IMAGE_ID:-}" ]] || { echo "Error: No such image" >&2; exit 1; }
    case "$*" in
      *".RootFS.Layers"*) printf '%b' "$FAKE_LAYERS" ;;
      *".Config.Env"*) printf '%s\n' "$FAKE_CONFIG" ;;
      *"{{.Id}}"*) printf '%s\n' "$FAKE_IMAGE_ID" ;;
      *) exit 9 ;;
    esac
    exit 0 ;;
  "load -i") exit 0 ;;
  "save -o") printf 'fake image tar\n' > "$3"; exit 0 ;;
  "create "*) printf '%s\n' fakecid; exit 0 ;;
  "cp "*)
    dest=${*: -1}
    mkdir -p "$dest"
    printf 'base_image=fake\n' > "$dest/toolchain.env"
    printf 'bash\t5.2\tamd64\n' > "$dest/dpkg-packages.tsv"
    exit 0 ;;
  "rm "*) exit 0 ;;
esac
exit 0
MOCK
  chmod +x "$T/bin/docker"
  export FAKE_DOCKER_LOG="$T/docker.log"
  : > "$FAKE_DOCKER_LOG"
  export DOCKER_BIN="$T/bin/docker"
  export FAKE_IMAGE_ID="$ID_CLASSIC" FAKE_LAYERS="$LAYERS_DEFAULT" FAKE_CONFIG="$CONFIG_DEFAULT"
  export FAKE_DRIVER_STATUS="$STATUS_CLASSIC" FAKE_STORAGE_DRIVER=overlay2
}

# 現行の production compose を複製し、pin 値を差し替えたものを作る
pinned_compose() { # <out> <pin>
  sed "s|^x-ses-backup-tool-content-id: .*|x-ses-backup-tool-content-id: \"$2\"|" "$PROD_COMPOSE" > "$1"
}

run_tool() {
  TOOL_OUT=$(bash "$TOOL_IMAGE" "$@" 2>&1)
  TOOL_RC=$?
}

# fake docker の現在の応答から content ID を求める（tool-image.sh inspect の出力）
current_content_id() {
  bash "$TOOL_IMAGE" inspect 2>/dev/null | sed -n 's/^content_id=//p'
}

case_inspect_reports_store_and_ids() {
  setup_fake_docker
  run_tool inspect
  assert_zero "$TOOL_RC" "inspect は 0"
  if [[ "$TOOL_OUT" =~ content_id=sha256:[0-9a-f]{64} ]]; then
    test_assert "content ID を表示"
  else
    test_fail "content ID を表示" "out=[$TOOL_OUT]"
  fi
  assert_contains "$TOOL_OUT" "image_store=classic" "classic store を識別"
  assert_contains "$TOOL_OUT" "image_id=$ID_CLASSIC" "image ID も表示（記録用）"
  export FAKE_DRIVER_STATUS="$STATUS_CONTAINERD"
  run_tool inspect
  assert_contains "$TOOL_OUT" "image_store=containerd" "containerd image store を識別"
}

case_check_rejects_placeholder_pin() {
  setup_fake_docker
  pinned_compose "$T/compose.yml" "sha256:UNPINNED"
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "占位値の pin は拒否"
  assert_contains "$TOOL_OUT" "未 pin" "未 pin を通知"
}

case_check_rejects_committed_compose_until_pinned() {
  setup_fake_docker
  local pin
  pin=$(sed -nE 's/^x-ses-backup-tool-content-id:[[:space:]]*"?([^"[:space:]]*)"?.*$/\1/p' "$PROD_COMPOSE")
  run_tool check --compose "$PROD_COMPOSE"
  if [[ "$pin" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    # pin 済みの場合: 一致しない（fake の）image は拒否する
    assert_nonzero "$TOOL_RC" "pin 済みの production compose は content ID の違う image を拒否"
  else
    assert_nonzero "$TOOL_RC" "pin されていない production compose は拒否"
    assert_contains "$TOOL_OUT" "未 pin" "production compose の未 pin を通知"
  fi
}

# P2-2: pin と照合は store に依存しない content ID で行う。build 機（classic）で pin した値は、
# image ID が変わる containerd image store のホストでも一致する。r1 は image ID で照合していたため拒否していた。
case_check_is_store_independent() {
  setup_fake_docker
  local content
  content=$(current_content_id)
  pinned_compose "$T/compose.yml" "$content"
  run_tool check --compose "$T/compose.yml"
  assert_zero "$TOOL_RC" "pin と同じ content ID の image を受理（classic）"
  assert_contains "$TOOL_OUT" "image_store=classic" "照合時の image store を表示"
  export FAKE_IMAGE_ID="$ID_CONTAINERD" FAKE_DRIVER_STATUS="$STATUS_CONTAINERD"
  run_tool check --compose "$T/compose.yml"
  assert_zero "$TOOL_RC" "image ID が異なる containerd image store でも content ID が同じなら受理"
  assert_contains "$TOOL_OUT" "image_store=containerd" "containerd image store であることを表示"
}

case_check_rejects_changed_content() {
  setup_fake_docker
  local content
  content=$(current_content_id)
  pinned_compose "$T/compose.yml" "$content"
  export FAKE_LAYERS="sha256:$(hex64 1)\nsha256:$(hex64 3)\n"
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "layer が違えば image ID が同じでも拒否"
  assert_contains "$TOOL_OUT" "一致しません" "不一致を通知"
  export FAKE_LAYERS="$LAYERS_DEFAULT" FAKE_CONFIG='["PATH=/usr/bin","RESTIC_BIN=/bin/true"] null ["bash"] "/var/lib/ses-backup" "" null'
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "config（Env）が違えば拒否"
  unset FAKE_IMAGE_ID
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "ローカルに image が無ければ拒否"
  assert_contains "$TOOL_OUT" "content ID を計算できません" "image 不在を通知"
}

case_check_rejects_build_and_pull() {
  setup_fake_docker
  local content
  content=$(current_content_id)
  pinned_compose "$T/compose.yml" "$content"
  sed 's|^  image: ses-backup-tool|  build: .\n  image: ses-backup-tool|' "$T/compose.yml" > "$T/compose-build.yml"
  run_tool check --compose "$T/compose-build.yml"
  assert_nonzero "$TOOL_RC" "build: を含む production compose は拒否"
  assert_contains "$TOOL_OUT" "build:" "build: を通知"
  grep -v 'pull_policy' "$T/compose.yml" > "$T/compose-pull.yml"
  run_tool check --compose "$T/compose-pull.yml"
  assert_nonzero "$TOOL_RC" "pull_policy: never の無い production compose は拒否"
  assert_contains "$TOOL_OUT" "pull_policy: never" "pull_policy を通知"
}

case_record_writes_store_and_refuses_overwrite() {
  setup_fake_docker
  local content
  content=$(current_content_id)
  export FAKE_DRIVER_STATUS="$STATUS_CONTAINERD" FAKE_IMAGE_ID="$ID_CONTAINERD"
  run_tool record --out "$T/rec"
  assert_zero "$TOOL_RC" "record は 0"
  assert_eq "$content" "$(cat "$T/rec/tool-content-id.txt" 2>/dev/null)" "content ID を記録"
  assert_eq "$ID_CONTAINERD" "$(cat "$T/rec/tool-image-id.txt" 2>/dev/null)" "image ID も記録"
  assert_contains "$(cat "$T/rec/tool-image-record.txt" 2>/dev/null)" "image_store=containerd" "image store の種類を記録"
  assert_contains "$(cat "$T/rec/tool-image-record.txt" 2>/dev/null)" "content_id=$content" "record に content ID"
  assert_file "$T/rec/image-manifest/dpkg-packages.tsv" "image 内の版一覧を取り出す"
  run_tool record --out "$T/rec"
  assert_nonzero "$TOOL_RC" "既存の記録は上書きしない"
  assert_contains "$TOOL_OUT" "上書きしません" "上書き拒否を通知"
}

case_save_and_load_by_content_id() {
  setup_fake_docker
  local content tar sha
  content=$(current_content_id)
  run_tool save --out "$T/dist"
  assert_zero "$TOOL_RC" "save は 0"
  tar="$T/dist/ses-backup-tool-${content:7:12}.tar"
  assert_file "$tar" "配布 tar を作成"
  assert_eq "$content" "$(cat "$tar.content-id" 2>/dev/null)" "配布 tar の content ID を記録"
  assert_eq "classic" "$(cat "$tar.image-store" 2>/dev/null)" "build 機の image store を記録"
  sha=$(sha256sum "$tar" | cut -d' ' -f1)

  : > "$FAKE_DOCKER_LOG"
  run_tool load --tar "$tar" --sha256 "$(hex64 0)" --content-id "$content"
  assert_nonzero "$TOOL_RC" "tar の SHA-256 不一致は失敗"
  assert_contains "$TOOL_OUT" "SHA-256 が一致しません" "SHA-256 不一致を通知"
  assert_not_contains "$(cat "$FAKE_DOCKER_LOG")" "load -i" "SHA-256 不一致なら docker load しない"

  # 配布先が containerd image store（image ID は変わる）でも content ID で照合して受理し、違いを通知する
  export FAKE_DRIVER_STATUS="$STATUS_CONTAINERD" FAKE_IMAGE_ID="$ID_CONTAINERD"
  run_tool load --tar "$tar" --sha256 "$sha" --content-id "$content"
  assert_zero "$TOOL_RC" "store が異なっても content ID が一致すれば 0"
  assert_contains "$TOOL_OUT" "image store（classic）と、このホストの image store（containerd）が異なります" "store の違いを通知"
  assert_contains "$(cat "$FAKE_DOCKER_LOG")" "load -i" "検証後に docker load する"

  export FAKE_LAYERS="sha256:$(hex64 9)\n"
  run_tool load --tar "$tar" --sha256 "$sha" --content-id "$content"
  assert_nonzero "$TOOL_RC" "読み込んだ image の content ID が違えば失敗"
  assert_contains "$TOOL_OUT" "content ID が一致しません" "content ID 不一致を通知"
}

# P3（W1-B1 r2）: 許可していない使い方は docker を一切呼ばずに拒否する（r1 は任意の引数を透過していた）
case_compose_rejects_unlisted_usage_without_docker() {
  setup_fake_docker
  local -a usage
  local args
  for args in \
    "-f extra.yml run --rm preflight" \
    "run --pull always --rm preflight" \
    "run --rm --entrypoint sh preflight" \
    "run preflight" \
    "run --rm unknown-service" \
    "up -d --build binlog" \
    "up -d backup" \
    "down -v" \
    "config" \
    "logs --tail all binlog"; do
    : > "$FAKE_DOCKER_LOG"
    read -ra usage <<< "$args"
    run_tool compose "${usage[@]}"
    assert_eq 2 "$TOOL_RC" "許可されていない使い方は 2: $args"
    assert_eq "" "$(cat "$FAKE_DOCKER_LOG")" "許可されていない使い方では docker を呼ばない: $args"
  done
  assert_contains "$TOOL_OUT" "許可されていない docker compose の使い方です" "拒否理由を通知"
}

# 許可した使い方だけを、pin 照合に合格した場合に渡す（production compose を差し替えた複製で検証する）
case_compose_runs_allowed_usage_after_check() {
  setup_fake_docker
  local copy content
  copy="$T/repo/ops/backup"
  mkdir -p "$copy/image"
  cp "$TOOL_IMAGE" "$copy/image/tool-image.sh"
  content=$(current_content_id)
  pinned_compose "$copy/docker-compose.yml" "$content"

  TOOL_OUT=$(bash "$copy/image/tool-image.sh" compose run --rm check --json 2>&1)
  TOOL_RC=$?
  assert_zero "$TOOL_RC" "pin と一致すれば run --rm <service> を実行"
  assert_contains "$(cat "$FAKE_DOCKER_LOG")" "compose -f $copy/docker-compose.yml run --rm check --json" \
    "production compose と許可した引数だけを docker compose へ渡す"

  : > "$FAKE_DOCKER_LOG"
  export FAKE_LAYERS="sha256:$(hex64 7)\n"
  TOOL_OUT=$(bash "$copy/image/tool-image.sh" compose up -d binlog 2>&1)
  TOOL_RC=$?
  assert_nonzero "$TOOL_RC" "content ID が一致しなければ up しない"
  assert_not_contains "$(cat "$FAKE_DOCKER_LOG")" "compose -f" "不一致なら docker compose を呼ばない"

  # 状態確認（ps / logs / stop）は image を起動しないため pin 照合なしで渡す（障害調査用）
  : > "$FAKE_DOCKER_LOG"
  pinned_compose "$copy/docker-compose.yml" "sha256:UNPINNED"
  TOOL_OUT=$(bash "$copy/image/tool-image.sh" compose ps 2>&1)
  TOOL_RC=$?
  assert_zero "$TOOL_RC" "ps は pin 照合なしで実行"
  assert_contains "$(cat "$FAKE_DOCKER_LOG")" "compose -f $copy/docker-compose.yml ps" "ps を渡す"
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
case "$*" in
  *--with-colons*) cat "${FAKE_GPG_LISTING:-/dev/null}" ;;
  *--export*) printf '%s' fake-keyring ;;
esac
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
  FAKE_KEY_SHA=$(printf 'fake downloaded content\n' | sha256sum | cut -d' ' -f1)
}

run_installer() { # [VAR=value ...]
  INSTALL_OUT=$(env PATH="$T/bin:$PATH" TMPDIR="$T" \
    BASE_IMAGE="debian:bookworm-slim@sha256:$(hex64 c)" \
    MYSQL_CLIENT_VERSION=8.0.46 MYSQL_DEB_REVISION=1debian12 MYSQL_APT_SUITE=bookworm MYSQL_APT_COMPONENT=mysql-8.0 \
    MYSQL_REPO_KEY_URL=https://repo.mysql.com/RPM-GPG-KEY-mysql-2025 \
    MYSQL_REPO_KEY_SHA256="$(hex64 d)" \
    MYSQL_REPO_KEY_FINGERPRINT=BCA43417C3B485DD128EC6D4B7B3B788A8D3785C \
    RESTIC_VERSION=0.17.3 RESTIC_LINUX_AMD64_SHA256="$(hex64 e)" \
    IMAGE_MANIFEST_DIR="$T/manifest" \
    "$@" bash "$INSTALL_TOOLCHAIN" 2>&1)
  INSTALL_RC=$?
}

# gpg --with-colons の一覧（fixture）を書く
write_gpg_listing() { # <file> <pub expiry> <primary fpr> [2 つ目の pub を置くなら yes]
  {
    printf 'tru::1:1700000000:0:3:1:5\n'
    printf 'pub:-:4096:1:B7B3B788A8D3785C:1698019200:%s::-:::scSC::::::23::0:\n' "$2"
    printf 'fpr:::::::::%s:\n' "$3"
    printf 'uid:-::::1698019200::0000000000000000000000000000000000000000::MySQL Release Engineering <mysql-build@oss.oracle.com>::::::::::0:\n'
    printf 'sub:-:4096:1:1111111111111111:1698019200:%s:::::s::::::23:\n' "$2"
    printf 'fpr:::::::::BCA43417C3B485DD128EC6D4B7B3B788A8D3785C:\n'
    if [[ "${4:-}" == yes ]]; then
      printf 'pub:-:4096:1:2222222222222222:1698019200:%s::-:::scSC::::::23::0:\n' "$2"
      printf 'fpr:::::::::%s:\n' "$(hex64 F | tr 'f' 'F')"
    fi
  } > "$1"
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

# P3（W1-B1 r2）: fingerprint は primary key のものだけを照合する。期待値が subkey の fpr 行にしか無い鍵、
# primary key が 2 つある鍵、期限切れの鍵は、keyring を作る（--export）前に拒否する。
# r1 は任意の fpr 行と照合していたため、subkey にだけ期待値がある鍵を受理していた。
case_installer_checks_primary_key_only() {
  setup_fake_installer
  write_gpg_listing "$T/listing-subkey" 1824293027 "$(hex64 0)"
  run_installer MYSQL_REPO_KEY_SHA256="$FAKE_KEY_SHA" FAKE_GPG_LISTING="$T/listing-subkey"
  assert_nonzero "$INSTALL_RC" "期待 fingerprint が subkey にしか無い鍵は失敗"
  assert_contains "$INSTALL_OUT" "primary key の fingerprint が BCA43417C3B485DD128EC6D4B7B3B788A8D3785C と一致しません" "primary key の不一致を通知"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "--export" "primary key を確認できない鍵で keyring を作らない"

  : > "$FAKE_INSTALL_LOG"
  write_gpg_listing "$T/listing-two" 1824293027 BCA43417C3B485DD128EC6D4B7B3B788A8D3785C yes
  run_installer MYSQL_REPO_KEY_SHA256="$FAKE_KEY_SHA" FAKE_GPG_LISTING="$T/listing-two"
  assert_nonzero "$INSTALL_RC" "primary key が 2 つある鍵ファイルは失敗"
  assert_contains "$INSTALL_OUT" "primary key（pub）が 1 つではありません（2 個）" "primary key の数を通知"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "--export" "primary key が複数なら keyring を作らない"

  : > "$FAKE_INSTALL_LOG"
  write_gpg_listing "$T/listing-expired" 1600000000 BCA43417C3B485DD128EC6D4B7B3B788A8D3785C
  run_installer MYSQL_REPO_KEY_SHA256="$FAKE_KEY_SHA" FAKE_GPG_LISTING="$T/listing-expired"
  assert_nonzero "$INSTALL_RC" "期限切れの鍵は失敗"
  assert_contains "$INSTALL_OUT" "有効期限が切れています" "期限切れを通知"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "--export" "期限切れの鍵で keyring を作らない"
}

case_static_image_contract() {
  local dockerfile integration prod dockerignore
  dockerfile=$(cat "$BACKUP_ROOT/Dockerfile")
  integration=$(cat "$BACKUP_ROOT/tests/docker-compose.integration.yml")
  prod=$(cat "$PROD_COMPOSE")
  dockerignore=$(cat "$BACKUP_ROOT/.dockerignore")
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
  assert_contains "$dockerfile" "ARG MYSQL_REPO_KEY_SHA256=a4bcd9f16a53cc763f87b9955dbcdced33c7aa90296b157eb6ceef0f156f4327" \
    "MySQL 署名鍵ファイルの SHA-256 を固定（2026-11-13 確認値）"
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
  assert_contains "$prod" "x-ses-backup-tool-content-id:" "production compose は store に依存しない content ID を参照する"
  assert_not_contains "$prod" "x-ses-backup-tool-image-id:" "store 依存の image ID では pin しない"
  assert_contains "$dockerignore" "secrets" "build context から secrets/ を除く"
  assert_contains "$dockerignore" ".env.backup" "build context から .env.backup を除く"
  # 運用文書は job の起動を tool-image.sh compose 経由に統一する（docker compose の直接実行は pin 照合を迂回する）
  local direct
  direct=$(cat "$BACKUP_ROOT/README.md" "$BACKUP_ROOT"/runbooks/*.md | grep -E 'docker compose (run|up|ps|logs|stop)' || true)
  assert_eq "" "$direct" "README・runbook に docker compose の直接実行を書かない"
}

run_case case_inspect_reports_store_and_ids
run_case case_check_rejects_placeholder_pin
run_case case_check_rejects_committed_compose_until_pinned
run_case case_check_is_store_independent
run_case case_check_rejects_changed_content
run_case case_check_rejects_build_and_pull
run_case case_record_writes_store_and_refuses_overwrite
run_case case_save_and_load_by_content_id
run_case case_compose_rejects_unlisted_usage_without_docker
run_case case_compose_runs_allowed_usage_after_check
run_case case_installer_rejects_placeholders_before_download
run_case case_installer_rejects_key_checksum_mismatch
run_case case_installer_checks_primary_key_only
run_case case_static_image_contract

test_summary "W1-B1 tool image reproducibility"
