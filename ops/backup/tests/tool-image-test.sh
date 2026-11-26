#!/usr/bin/env bash
# W1-B1 / X-08(b) ツールイメージの再現性・配布の検証（実 Docker・ネットワークは使わない）
#
# - image/tool-image.sh check / compose: production compose の pin（x-ses-backup-tool-content-id）が
#   仮置きの値・不正・ローカルの content ID と不一致なら拒否し、docker compose を実行しない。
#   pin と照合は image store（classic / containerd）に依存しない content ID で行う（W1-B1 r2 P2-2）。
# - image/tool-image.sh compose: 許可した使い方（run --rm <service> / up -d binlog / ps / logs / stop）だけを渡し、
#   -f・--pull・--build などの compose 側 option や未定義の service は docker を呼ばずに拒否する。
# - RB-W W-3: tag は 1 回だけ image ID へ解決し、照合と起動はその image ID で行う（照合後の tag の付け替えで
#   別の image を起動させない）。pull_policy・image は service ごとの実効値で検査し、flow style などの解釈しない
#   YAML は理由を示して拒否する。
# - image/tool-image.sh record / save / load: image store の種類・content ID・image ID を記録し、
#   配布 tar は SHA-256 が一致しなければ docker load せず、読み込んだ tag の content ID で照合する。
# - image/install-toolchain.sh: 固定値が仮置きの値なら何もダウンロードせずに失敗し、署名鍵は SHA-256・
#   primary key が 1 つであること・primary key の fingerprint・有効期限を検証してから keyring にする。
#   MySQL client の版と repo の component は列挙した MySQL 8.4 LTS の値（8.4.13.1・mysql-8.4-lts）だけを受け付ける。
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
# setup_fake_docker は TMPDIR を case 用に差し替えるため、case の作業ディレクトリは起動時の TMPDIR に作る（入れ子にしない）
TEST_TMP_ROOT=${TMPDIR:-/tmp}
hex64() { printf "${1}%.0s" $(seq 1 64); }
ID_CLASSIC="sha256:$(hex64 a)"
ID_CONTAINERD="sha256:$(hex64 b)"
LAYERS_DEFAULT="sha256:$(hex64 1)\nsha256:$(hex64 2)\n"
# content ID の描画（tool-image.sh の CONTENT_ID_TEMPLATE を docker が描画した結果）のうち Config の部分（printf %b 形式）
config_render() { # <user> <stopsignal> <healthcheck>（いずれも描画後の値）
  printf 'env="PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"\\ncmd="bash"\\nworkdir="/var/lib/ses-backup"\\nuser=%s\\nstopsignal=%s\\nhealthcheck=%s\\n' \
    "$1" "$2" "$3"
}
CONFIG_DEFAULT=$(config_render '""' '""' 'null')
STATUS_CLASSIC='[["Backing Filesystem","extfs"],["Supports d_type","true"]]'
STATUS_CONTAINERD='[["driver-type","io.containerd.snapshotter.v1"]]'

# fake docker: 呼び出しを FAKE_DOCKER_LOG に記録し、image inspect / info は FAKE_* を返す
#   FAKE_RETAG_ID / FAKE_RETAG_LAYERS / FAKE_RETAG_DONE: tag の image ID を 1 回答えた直後に、tag を別の image
#   （FAKE_RETAG_ID）へ付け替える第三者を再現する。image ID で問い合わせた場合はその image の内容を返す。
#   compose -f A -f B ...: 2 つ目の -f（実行後に削除される override）の内容を FAKE_DOCKER_LOG.override に残す。
setup_fake_docker() {
  T=$(mktemp -d "$TEST_TMP_ROOT/tool-image-test.XXXXXX")
  mkdir -p "$T/bin" "$T/tmp"
  export TMPDIR="$T/tmp"
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
    ref=${*: -1}
    id=$FAKE_IMAGE_ID
    layers=$FAKE_LAYERS
    if [[ -n "${FAKE_RETAG_ID:-}" ]]; then
      if [[ "$ref" == "$FAKE_RETAG_ID" || ( "$ref" != "$FAKE_IMAGE_ID" && -e "$FAKE_RETAG_DONE" ) ]]; then
        id=$FAKE_RETAG_ID
        layers=$FAKE_RETAG_LAYERS
      fi
      if [[ "$ref" != "$FAKE_IMAGE_ID" && "$ref" != "$FAKE_RETAG_ID" && "$*" == *"{{.Id}}"* ]]; then
        : > "$FAKE_RETAG_DONE"
      fi
    fi
    case "$*" in
      *".RootFS.Layers"*)
        # content ID の描画: os / arch、layer、Config の順（FAKE_RENDER_RC で描画の失敗、FAKE_RENDER_CRLF で CRLF を再現）
        if [[ -n "${FAKE_RENDER_RC:-}" ]]; then
          echo "template: :1:2: executing \"\" at <.Config.Healthcheck>: can't evaluate field Healthcheck" >&2
          exit "$FAKE_RENDER_RC"
        fi
        rendered=$( { printf '%b' "${FAKE_OS_ARCH:-os=\"linux\"\narch=\"amd64\"\n}"; printf '%b' "$layers" | sed '/^$/d; s/^/layer=/'; printf '%b' "$FAKE_CONFIG"; } )
        if [[ -n "${FAKE_RENDER_CRLF:-}" ]]; then printf '%s\n' "$rendered" | sed 's/$/\r/'; else printf '%s\n' "$rendered"; fi ;;
      *"{{.Id}}"*) printf '%s\n' "$id" ;;
      *) exit 9 ;;
    esac
    exit 0 ;;
  "compose -f")
    if [[ "${4:-}" == -f && -f "${5:-}" ]]; then cat "$5" >> "$FAKE_DOCKER_LOG.override"; fi
    exit 0 ;;
  "load -i") exit 0 ;;
  "save -o") printf 'fake image tar\n' > "$3"; exit 0 ;;
  "create "*) printf '%s\n' fakecid; exit 0 ;;
  "cp "*)
    # image 内の版一覧（toolchain.env の dpkg_packages_sha256 は dpkg-packages.tsv の SHA-256。FAKE_MANIFEST_BAD_SHA で崩す）
    dest=${*: -1}
    mkdir -p "$dest"
    printf 'bash\t5.2\tamd64\n' > "$dest/dpkg-packages.tsv"
    sha=$(sha256sum "$dest/dpkg-packages.tsv" | cut -d' ' -f1)
    [[ -n "${FAKE_MANIFEST_BAD_SHA:-}" ]] && sha=$(printf '%064d' 0)
    printf 'base_image=fake\ndpkg_packages_sha256=%s\n' "$sha" > "$dest/toolchain.env"
    exit 0 ;;
  "run --rm")
    # build 後の image の dpkg-query（FAKE_DPKG_RUN を printf %b で返す。FAKE_DPKG_RUN_RC で失敗させる）
    case "$*" in
      *"--entrypoint dpkg-query"*)
        printf '%b' "${FAKE_DPKG_RUN:-ii \tbash\t5.2\tamd64\n}"
        exit "${FAKE_DPKG_RUN_RC:-0}" ;;
    esac
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
  unset FAKE_RETAG_ID FAKE_RETAG_LAYERS FAKE_RETAG_DONE FAKE_MANIFEST_BAD_SHA FAKE_DPKG_RUN FAKE_DPKG_RUN_RC \
    FAKE_OS_ARCH FAKE_RENDER_RC FAKE_RENDER_CRLF
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
  assert_nonzero "$TOOL_RC" "仮置きの値の pin は拒否"
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
  export FAKE_LAYERS="$LAYERS_DEFAULT" FAKE_CONFIG="${CONFIG_DEFAULT}"'env="RESTIC_BIN=/bin/true"\n'
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "config（Env）が違えば拒否"
  assert_contains "$TOOL_OUT" "一致しません" "Env の違いを content ID の不一致として通知"
  unset FAKE_IMAGE_ID
  run_tool check --compose "$T/compose.yml"
  assert_nonzero "$TOOL_RC" "ローカルに image が無ければ拒否"
  assert_contains "$TOOL_OUT" "image ID を取得できません" "image 不在を通知"
}

# RB-W W-7: content ID は Volumes・ExposedPorts・Shell・StopSignal・Healthcheck・OnBuild・Labels・os / arch も覆う
# （r2 は Env / Entrypoint / Cmd / WorkingDir / User / Labels だけで、VOLUME・EXPOSE・STOPSIGNAL・HEALTHCHECK 等を
# 変えた image を同じ content ID として受理していた）。Docker の出力の CRLF は正規化し、描画できない・想定外の行・
# 単一項目の重複・layer なしは fail-closed にする。
case_content_id_covers_runtime_config() {
  setup_fake_docker
  local base variant changed
  base=$(current_content_id)
  if [[ "$base" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    test_assert "既定の描画から content ID を計算できる（前提）"
  else
    test_fail "既定の描画から content ID を計算できる（前提）" "[$base]"
  fi
  for variant in 'volume="/var/lib/ses-backup"' 'port="3306/tcp"' 'shell="/bin/sh"' 'onbuild="RUN true"' \
      'label="org.opencontainers.image.version"="8.4.13"' 'entrypoint="/bin/true"'; do
    export FAKE_CONFIG="${CONFIG_DEFAULT}${variant}\n"
    changed=$(current_content_id)
    if [[ "$changed" =~ ^sha256:[0-9a-f]{64}$ && "$changed" != "$base" ]]; then
      test_assert "content ID が変わる: ${variant%%=*}"
    else
      test_fail "content ID が変わる: ${variant%%=*}" "base=[$base] changed=[$changed]"
    fi
  done
  for variant in 'stopsignal' 'healthcheck' 'user'; do
    case "$variant" in
      stopsignal) FAKE_CONFIG=$(config_render '""' '"SIGKILL"' 'null') ;;
      healthcheck) FAKE_CONFIG=$(config_render '""' '""' '{"Test":["CMD","true"],"Interval":30000000000}') ;;
      user) FAKE_CONFIG=$(config_render '"root"' '""' 'null') ;;
    esac
    export FAKE_CONFIG
    changed=$(current_content_id)
    if [[ "$changed" =~ ^sha256:[0-9a-f]{64}$ && "$changed" != "$base" ]]; then
      test_assert "content ID が変わる: $variant"
    else
      test_fail "content ID が変わる: $variant" "base=[$base] changed=[$changed] config=[$FAKE_CONFIG]"
    fi
  done
  export FAKE_CONFIG="$CONFIG_DEFAULT" FAKE_OS_ARCH='os="linux"\narch="arm64"\n'
  changed=$(current_content_id)
  if [[ "$changed" =~ ^sha256:[0-9a-f]{64}$ && "$changed" != "$base" ]]; then
    test_assert "content ID が変わる: arch"
  else
    test_fail "content ID が変わる: arch" "base=[$base] changed=[$changed]"
  fi
  unset FAKE_OS_ARCH
  export FAKE_RENDER_CRLF=1
  assert_eq "$base" "$(current_content_id)" "docker の出力の CRLF は正規化して同じ content ID"
  unset FAKE_RENDER_CRLF

  export FAKE_RENDER_RC=1
  run_tool inspect
  assert_nonzero "$TOOL_RC" "Docker の版で描画できない項目があれば失敗（fail-closed）"
  assert_contains "$TOOL_OUT" "content ID を計算できません" "描画できないことを通知"
  unset FAKE_RENDER_RC
  for variant in "${CONFIG_DEFAULT}"'hostname="builder-01"\n' "${CONFIG_DEFAULT}"'stopsignal="SIGTERM"\n' \
      '\n'"${CONFIG_DEFAULT}"; do
    export FAKE_CONFIG="$variant"
    run_tool inspect
    assert_nonzero "$TOOL_RC" "想定外の行・単一項目の重複・途中の空行は失敗: [${variant:0:40}…]"
  done
  export FAKE_CONFIG="$CONFIG_DEFAULT" FAKE_LAYERS=""
  run_tool inspect
  assert_nonzero "$TOOL_RC" "layer の無い描画は失敗"
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

# RB-W W-4: record は build 後の image の実際の dpkg の版が image 内の dpkg-packages.tsv（と toolchain.env の SHA-256）と
# 一致することを確かめ、一致しなければ何も記録しない（「Essential は base の版を保つ」等の説明に頼らず、事実を照合する）。
case_record_verifies_image_dpkg_against_manifest() {
  setup_fake_docker
  run_tool record --out "$T/ok"
  assert_zero "$TOOL_RC" "実際の版と dpkg-packages.tsv が一致すれば記録する"
  assert_contains "$(cat "$FAKE_DOCKER_LOG")" "run --rm --network none --entrypoint dpkg-query $ID_CLASSIC -W" \
    "network なしの一時コンテナで、解決した image ID の dpkg-query を実行する"
  export FAKE_DPKG_RUN='ii \tbash\t5.2.1\tamd64\n'
  run_tool record --out "$T/mismatch"
  assert_nonzero "$TOOL_RC" "実際の版が dpkg-packages.tsv と違えば失敗"
  assert_contains "$TOOL_OUT" "image の実際の dpkg の版が dpkg-packages.tsv と一致しません" "不一致を通知"
  assert_contains "$TOOL_OUT" "5.2.1" "差分を表示"
  assert_no_file "$T/mismatch/tool-content-id.txt" "不一致なら content ID を記録しない"
  export FAKE_DPKG_RUN='ii \tbash\t5.2\tamd64\nii \ttar\t1.34\tamd64\n'
  run_tool record --out "$T/extra"
  assert_nonzero "$TOOL_RC" "記録に無いパッケージが image にあれば失敗"
  unset FAKE_DPKG_RUN
  export FAKE_MANIFEST_BAD_SHA=1
  run_tool record --out "$T/bad-sha"
  assert_nonzero "$TOOL_RC" "toolchain.env の dpkg_packages_sha256 が dpkg-packages.tsv と違えば失敗"
  assert_contains "$TOOL_OUT" "dpkg_packages_sha256" "SHA-256 の不一致を通知"
  assert_no_file "$T/bad-sha/tool-image-record.txt" "SHA-256 不一致なら記録しない"
  unset FAKE_MANIFEST_BAD_SHA
  export FAKE_DPKG_RUN_RC=1
  run_tool record --out "$T/run-fail"
  assert_nonzero "$TOOL_RC" "dpkg-query を実行できなければ失敗"
  unset FAKE_DPKG_RUN_RC
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
  assert_contains "$(cat "$FAKE_DOCKER_LOG")" "compose -f $copy/docker-compose.yml -f " \
    "production compose と image ID の override だけを docker compose へ渡す"
  assert_contains "$(grep '^compose ' "$FAKE_DOCKER_LOG")" "image-id.yml run --rm check --json" "許可した引数だけを渡す"

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

# RB-W W-3(a): tag は照合で 1 回だけ image ID へ解決し、compose はその image ID に固定した override で起動する。
# 照合の直後に tag を別の image へ付け替えられても、照合した image だけが起動される（r2 は tag のまま exec していた）。
case_compose_runs_checked_image_id_even_if_retagged() {
  setup_fake_docker
  local copy content override
  copy="$T/repo/ops/backup"
  mkdir -p "$copy/image"
  cp "$TOOL_IMAGE" "$copy/image/tool-image.sh"
  content=$(current_content_id)
  pinned_compose "$copy/docker-compose.yml" "$content"
  : > "$FAKE_DOCKER_LOG"
  export FAKE_RETAG_ID="$ID_CONTAINERD" FAKE_RETAG_LAYERS="sha256:$(hex64 6)\n" FAKE_RETAG_DONE="$T/retagged"
  TOOL_OUT=$(bash "$copy/image/tool-image.sh" compose run --rm backup 2>&1)
  TOOL_RC=$?
  assert_zero "$TOOL_RC" "照合した image で run する"
  assert_file "$T/retagged" "照合の直後に tag が別の image へ付け替えられた（前提）"
  assert_eq 1 "$(grep -c '{{.Id}} ses-backup-tool:8.4.13$' "$FAKE_DOCKER_LOG")" "tag の image ID への解決は 1 回だけ"
  assert_contains "$(grep 'RootFS.Layers' "$FAKE_DOCKER_LOG")" "$ID_CLASSIC" "content ID は解決した image ID から計算する"
  assert_not_contains "$(grep 'RootFS.Layers' "$FAKE_DOCKER_LOG")" "ses-backup-tool:8.4.13" "content ID の計算に tag を使わない"
  override=$(cat "$FAKE_DOCKER_LOG.override" 2>/dev/null)
  assert_eq 7 "$(grep -c "image: \"$ID_CLASSIC\"" <<< "$override")" "全 service の image を照合した image ID に固定する"
  assert_not_contains "$override" "$ID_CONTAINERD" "付け替え後の image は使わない"
  assert_contains "$(grep '^compose ' "$FAKE_DOCKER_LOG")" "run --rm backup" "許可した使い方を渡す"
  if compgen -G "$TMPDIR/ses-backup-tool-compose.*" > /dev/null; then
    test_fail "実行後に override を削除する" "$(ls "$TMPDIR")"
  else
    test_assert "実行後に override を削除する"
  fi
}

# compose の 1 行（<after> の直後）に <line> を足した複製を作る
compose_with_line() { # <src> <out> <after（完全一致の行）> <line>
  awk -v after="$3" -v add="$4" '{ print } $0 == after { print add }' "$1" > "$2"
}

# 変形した compose を check し、docker を呼ばずに拒否することを確かめる
expect_compose_rejected() { # <compose> <通知に含まれる語> <label>
  : > "$FAKE_DOCKER_LOG"
  run_tool check --compose "$1"
  assert_eq 1 "$TOOL_RC" "拒否: $3"
  assert_contains "$TOOL_OUT" "$2" "理由を通知: $3"
  assert_eq "" "$(cat "$FAKE_DOCKER_LOG")" "image を確かめる前に拒否（docker を呼ばない）: $3"
}

# RB-W W-3(b): pull_policy・image は service ごとに（merge を解決した実効値で）検査する。
# r2 は「pull_policy: never の行が 1 行以上あるか」しか見ず、1 つの service の pull_policy: always を見落としていた。
case_check_requires_pull_policy_per_service() {
  setup_fake_docker
  local content
  content=$(current_content_id)
  pinned_compose "$T/base.yml" "$content"
  run_tool check --compose "$T/base.yml"
  assert_zero "$TOOL_RC" "全 service が never なら受理（前提）"
  compose_with_line "$T/base.yml" "$T/override-always.yml" "  backup:" "    pull_policy: always"
  expect_compose_rejected "$T/override-always.yml" "service backup に pull_policy: never がありません（現在: always" \
    "1 つの service だけ pull_policy: always で上書き"
  grep -v '^  pull_policy: never$' "$T/base.yml" > "$T/anchor-only.yml.tmp"
  compose_with_line "$T/anchor-only.yml.tmp" "$T/one-service.yml" "  preflight:" "    pull_policy: never"
  expect_compose_rejected "$T/one-service.yml" "pull_policy: never がありません" "1 つの service にだけ never がある"
  compose_with_line "$T/base.yml" "$T/other-image.yml" "  check:" "    image: ses-backup-tool:other"
  expect_compose_rejected "$T/other-image.yml" "ツールイメージ 1 種類ではありません" "1 つの service だけ別の tag"
  compose_with_line "$T/base.yml" "$T/foreign-image.yml" "  check:" "    image: alpine:3"
  expect_compose_rejected "$T/foreign-image.yml" "service check の image: がツールイメージの tag ではありません" "ツール以外の image"
  compose_with_line "$T/base.yml" "$T/extra-service.yml" "services:" "  sidecar:"$'\n'"    image: ses-backup-tool:8.4.13"$'\n'"    pull_policy: never"
  expect_compose_rejected "$T/extra-service.yml" "services がツールの service" "ツール以外の service（依存で起動され得る）"
  compose_with_line "$T/base.yml" "$T/extends.yml" "  retention:" "    extends: check"
  expect_compose_rejected "$T/extends.yml" "extends:" "extends で別の定義を取り込む"
  # 引用符・行末コメントは正しく解釈して受理する
  sed 's|^  pull_policy: never$|  pull_policy: "never"  # registry から取得しない|' "$T/base.yml" > "$T/quoted.yml"
  run_tool check --compose "$T/quoted.yml"
  assert_zero "$TOOL_RC" "引用符・行末コメント付きの never も解釈して受理"
}

# RB-W W-3(c): flow style などの解釈しない書き方は、明確な理由を示して fail-closed にする。
case_check_rejects_flow_style_and_unparsed_yaml() {
  setup_fake_docker
  local content
  content=$(current_content_id)
  pinned_compose "$T/base.yml" "$content"
  compose_with_line "$T/base.yml" "$T/flow-map.yml" "  backup:" "    environment: {RESTIC_BIN: /bin/true}"
  expect_compose_rejected "$T/flow-map.yml" "flow style（{）の YAML は解釈しません" "service 内の flow mapping"
  compose_with_line "$T/base.yml" "$T/flow-seq.yml" "  backup:" "    secrets: [mysql_password]"
  expect_compose_rejected "$T/flow-seq.yml" "flow style（[）の YAML は解釈しません" "service 内の flow sequence"
  printf 'x-ses-backup-tool-content-id: "%s"\nservices: {preflight: {image: "ses-backup-tool:8.4.13", pull_policy: always}}\n' \
    "$content" > "$T/flow-services.yml"
  expect_compose_rejected "$T/flow-services.yml" "flow style" "services 全体の flow style"
  compose_with_line "$T/base.yml" "$T/dup.yml" "  pull_policy: never" "  pull_policy: always"
  expect_compose_rejected "$T/dup.yml" "key が重複しています" "同じ mapping の key の重複"
  compose_with_line "$T/base.yml" "$T/tab.yml" "  backup:" $'\t'"pull_policy: always"
  expect_compose_rejected "$T/tab.yml" "indent に tab があります" "indent の tab"
  compose_with_line "$T/base.yml" "$T/tag.yml" "  backup:" "    image: !!str ses-backup-tool:8.4.13"
  expect_compose_rejected "$T/tag.yml" "tag・block scalar" "YAML の tag"
  compose_with_line "$T/base.yml" "$T/block-scalar.yml" "  backup:" "    command: |"$'\n'"      sh -c true"
  expect_compose_rejected "$T/block-scalar.yml" "tag・block scalar" "block scalar"
  sed 's|^  backup:$|  backup: \&backup|' "$T/base.yml" > "$T/service-anchor.yml"
  expect_compose_rejected "$T/service-anchor.yml" "anchor はトップレベルの x- key にだけ置けます" "service の anchor"
  compose_with_line "$T/base.yml" "$T/alias.yml" "  backup:" "    image: *backup-common"
  expect_compose_rejected "$T/alias.yml" "予約文字（*）" "<< 以外の alias"
  { cat "$T/base.yml"; printf 'include:\n  - extra.yml\n'; } > "$T/include.yml"
  expect_compose_rejected "$T/include.yml" "include" "別の compose ファイルの include"
  { printf -- '---\n'; cat "$T/base.yml"; } > "$T/document.yml"
  expect_compose_rejected "$T/document.yml" "document marker" "document marker"
  compose_with_line "$T/base.yml" "$T/continuation.yml" "  image: ses-backup-tool:8.4.13" "    :other"
  expect_compose_rejected "$T/continuation.yml" "indent が揃っていません" "複数行にわたる値"
  compose_with_line "$T/base.yml" "$T/seq-map.yml" "  volumes:" "    - type: bind"
  expect_compose_rejected "$T/seq-map.yml" "sequence の中の mapping は扱いません" "sequence の中の mapping（long syntax）"
}

# install-toolchain.sh 用の fake（apt-get / curl / gpg / dpkg）。実ネットワーク・実 apt には触れない。
setup_fake_installer() {
  T=$(mktemp -d "$TEST_TMP_ROOT/tool-image-test.XXXXXX")
  mkdir -p "$T/bin"
  # apt-get: 呼び出しを記録し、install の後は FAKE_APT_INSTALLED を置く（dpkg-query が導入後の一覧を返す）
  cat > "$T/bin/apt-get" <<'MOCK'
#!/usr/bin/env bash
printf 'apt-get %s\n' "$*" >> "$FAKE_INSTALL_LOG"
[[ "${1:-}" == install ]] && : > "$FAKE_APT_INSTALLED"
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
  # curl: -o の先へ書く。restic の URL で FAKE_RESTIC_BZ2 があればその内容（それ以外は固定の内容）
  cat > "$T/bin/curl" <<'MOCK'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "$FAKE_INSTALL_LOG"
out=""
url=""
while (( $# > 0 )); do
  if [[ "$1" == -o ]]; then out=$2; shift 2; continue; fi
  [[ "$1" == https://* ]] && url=$1
  shift
done
if [[ -n "$out" ]]; then
  if [[ "$url" == *restic* && -n "${FAKE_RESTIC_BZ2:-}" ]]; then
    cp "$FAKE_RESTIC_BZ2" "$out"
  else
    printf 'fake downloaded content\n' > "$out"
  fi
fi
exit 0
MOCK
  cat > "$T/bin/dpkg" <<'MOCK'
#!/usr/bin/env bash
[[ "${1:-}" == --print-architecture ]] && { echo amd64; exit 0; }
exit 1
MOCK
  # dpkg-query: FAKE_DPKG_LIST（全パッケージ）/ FAKE_DPKG_ESSENTIAL（Essential の照会）を printf %b で返す。
  # apt-get install の後（FAKE_APT_INSTALLED がある）は *_AFTER があればそちらを返す（依存解決による更新の再現）。
  cat > "$T/bin/dpkg-query" <<'MOCK'
#!/usr/bin/env bash
printf 'dpkg-query %s\n' "$*" >> "$FAKE_INSTALL_LOG"
list=${FAKE_DPKG_LIST:-}
essential=${FAKE_DPKG_ESSENTIAL:-}
if [[ -e "$FAKE_APT_INSTALLED" ]]; then
  list=${FAKE_DPKG_LIST_AFTER:-$list}
  essential=${FAKE_DPKG_ESSENTIAL_AFTER:-$essential}
fi
case "$*" in
  *'${Essential}'*) printf '%b' "$essential" ;;
  *) printf '%b' "$list" ;;
esac
exit 0
MOCK
  chmod +x "$T/bin/apt-get" "$T/bin/gpg" "$T/bin/curl" "$T/bin/dpkg" "$T/bin/dpkg-query"
  export FAKE_INSTALL_LOG="$T/install.log"
  : > "$FAKE_INSTALL_LOG"
  export FAKE_APT_INSTALLED="$T/apt-installed"
  unset FAKE_DPKG_LIST FAKE_DPKG_ESSENTIAL FAKE_DPKG_LIST_AFTER FAKE_DPKG_ESSENTIAL_AFTER FAKE_RESTIC_BZ2
  FAKE_KEY_SHA=$(printf 'fake downloaded content\n' | sha256sum | cut -d' ' -f1)
}

# RB-W W-5: system path を INSTALL_TOOLCHAIN_SYSROOT で一時ディレクトリへ向け、導入経路を最後（lock の照合・記録）まで通す。
# base image では libc-bin（Essential）が deb12u7 で、導入後は依存解決で deb12u8 に上がる（W-4 の記録の確認にも使う）。
setup_installer_full_flow() {
  local b
  setup_fake_installer
  SYSROOT="$T/sysroot"
  mkdir -p "$SYSROOT/usr/share/keyrings" "$SYSROOT/etc/apt/sources.list.d" "$SYSROOT/usr/local/bin" "$SYSROOT/var/lib/apt/lists"
  printf 'stale\n' > "$SYSROOT/var/lib/apt/lists/stale-list"
  printf '#!/bin/sh\necho "restic 0.17.3 compiled with go1.23.3 on linux/amd64"\n' | bzip2 -c > "$T/restic.bz2"
  export FAKE_RESTIC_BZ2="$T/restic.bz2"
  FAKE_RESTIC_SHA=$(sha256sum "$T/restic.bz2" | cut -d' ' -f1)
  for b in mysql mysqlbinlog mysqldump; do
    printf '#!/usr/bin/env bash\necho "%s  Ver 8.4.13.1 for Linux on x86_64 (MySQL Community Server - GPL)"\n' "$b" > "$T/bin/$b"
    chmod +x "$T/bin/$b"
  done
  write_gpg_listing "$T/listing-ok" 1824293027 BCA43417C3B485DD128EC6D4B7B3B788A8D3785C
  export FAKE_DPKG_LIST='ii \tbash\t5.2.15-2+b2\tamd64\nii \tlibc-bin\t2.36-9+deb12u7\tamd64\nii \ttar\t1.34+dfsg-1.2\tamd64\n'
  export FAKE_DPKG_LIST_AFTER='ii \tbash\t5.2.15-2+b2\tamd64\nii \tlibc-bin\t2.36-9+deb12u8\tamd64\nii \tmysql-community-client\t8.4.13.1-1debian12\tamd64\nii \ttar\t1.34+dfsg-1.2\tamd64\n'
  export FAKE_DPKG_ESSENTIAL='ii \tbash\tyes\nii \tlibc-bin\tyes\nii \ttar\tyes\n'
  INSTALLED_TSV=$(printf '%b' "$FAKE_DPKG_LIST_AFTER" | awk -F'\t' '{print $2 "\t" $3 "\t" $4}' | LC_ALL=C sort)
}

run_installer_full_flow() { # <lock file> [VAR=value ...]
  local lock=$1
  shift
  run_installer MYSQL_REPO_KEY_SHA256="$FAKE_KEY_SHA" RESTIC_LINUX_AMD64_SHA256="$FAKE_RESTIC_SHA" \
    FAKE_GPG_LISTING="$T/listing-ok" INSTALL_TOOLCHAIN_SYSROOT="$SYSROOT" APT_PACKAGES_LOCK_FILE="$lock" "$@"
}

# lock に記載があり導入結果と完全一致する場合（enforced）。CRLF で checkout された lock も CR を除いて照合する。
# r2 までは system path に書くため単体試験が無く、初回 build の実測だけだった。
case_installer_apt_lock_enforced_via_injected_paths() {
  setup_installer_full_flow
  { printf '# lock（試験）\n'; printf '%s\n' "$INSTALLED_TSV" | sed 's/$/\r/'; } > "$T/lock"
  run_installer_full_flow "$T/lock"
  assert_zero "$INSTALL_RC" "lock と導入結果が一致すれば 0"
  assert_contains "$(cat "$T/manifest/toolchain.env" 2>/dev/null)" "apt_lock=enforced" "lock を enforced として記録"
  assert_eq "$INSTALLED_TSV" "$(cat "$T/manifest/dpkg-packages.tsv" 2>/dev/null)" "導入結果の版一覧を記録"
  assert_eq "fake-keyring" "$(cat "$SYSROOT/usr/share/keyrings/mysql-release-engineering.gpg" 2>/dev/null)" \
    "fingerprint を確認した鍵の keyring を注入した path に書く"
  assert_eq "deb [signed-by=$SYSROOT/usr/share/keyrings/mysql-release-engineering.gpg] https://repo.mysql.com/apt/debian/ bookworm mysql-8.4-lts" \
    "$(cat "$SYSROOT/etc/apt/sources.list.d/mysql.list" 2>/dev/null)" "apt source を注入した path に書く"
  if [[ -x "$SYSROOT/usr/local/bin/restic" ]]; then
    test_assert "SHA-256 を確認した restic を注入した path に導入する"
  else
    test_fail "SHA-256 を確認した restic を注入した path に導入する" "実行可能な restic がありません"
  fi
  assert_contains "$INSTALL_OUT" "restic 0.17.3 compiled" "導入した restic を実行して版を表示する"
  assert_no_file "$SYSROOT/var/lib/apt/lists/stale-list" "注入した path の apt lists を消す"
  # W-4: Essential の base image からの変化を記録する（依存解決で libc-bin が更新された）
  assert_contains "$(cat "$T/manifest/essential-packages.tsv" 2>/dev/null)" $'libc-bin\t2.36-9+deb12u7\t2.36-9+deb12u8\tchanged' \
    "依存解決で更新された Essential を changed として記録"
  assert_contains "$(cat "$T/manifest/essential-packages.tsv" 2>/dev/null)" $'tar\t1.34+dfsg-1.2\t1.34+dfsg-1.2\tsame' \
    "変わらない Essential を same として記録"
  assert_not_contains "$(cat "$T/manifest/essential-packages.tsv" 2>/dev/null)" "mysql-community-client" "Essential 以外は記録しない"
  assert_contains "$(cat "$T/manifest/toolchain.env" 2>/dev/null)" "essential_changed=1" "変化の件数を記録"
  assert_contains "$INSTALL_OUT" "WARN: Essential パッケージ 1 件が base image の版から変わりました" "変化を build ログに出す"
}

# lock に記載があり導入結果と一致しない場合は失敗し、固定値の記録（toolchain.env）を書かない。空の lock は記録だけ。
case_installer_apt_lock_mismatch_fails_via_injected_paths() {
  setup_installer_full_flow
  printf '%s\n' "$INSTALLED_TSV" | sed 's/deb12u8/deb12u7/' > "$T/lock"
  run_installer_full_flow "$T/lock"
  assert_nonzero "$INSTALL_RC" "lock と導入結果が違えば失敗"
  assert_contains "$INSTALL_OUT" "Debian パッケージの版が apt-packages.lock と一致しません" "不一致を通知"
  assert_contains "$INSTALL_OUT" "2.36-9+deb12u8" "差分を表示"
  assert_no_file "$T/manifest/toolchain.env" "不一致なら固定値の記録を書かない"

  rm -f "$FAKE_APT_INSTALLED"
  printf '# 記載なし\n\n' > "$T/lock"
  run_installer_full_flow "$T/lock"
  assert_zero "$INSTALL_RC" "空の lock は記録だけで 0"
  assert_contains "$(cat "$T/manifest/toolchain.env" 2>/dev/null)" "apt_lock=record-only" "record-only として記録"
  assert_contains "$INSTALL_OUT" "WARN: apt-packages.lock が空のため" "固定していないことを WARN で出す"

  : > "$FAKE_INSTALL_LOG"
  run_installer_full_flow "$T/lock" INSTALL_TOOLCHAIN_SYSROOT=relative/sysroot
  assert_nonzero "$INSTALL_RC" "相対パスの INSTALL_TOOLCHAIN_SYSROOT は失敗"
  assert_contains "$INSTALL_OUT" "INSTALL_TOOLCHAIN_SYSROOT は既存の絶対パス" "注入値の不正を通知"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "不正な注入値では apt・ダウンロードを一切行わない"
}

# RB-W W-4: --verify-manifest は image の実際の dpkg の版と dpkg-packages.tsv・toolchain.env の SHA-256 を照合する
# （Dockerfile の最後の RUN）。固定値の検査・ダウンロード・apt は行わない。
case_installer_verify_manifest() {
  setup_fake_installer
  mkdir -p "$T/manifest"
  printf 'bash\t5.2.15-2+b2\tamd64\ntar\t1.34+dfsg-1.2\tamd64\n' > "$T/manifest/dpkg-packages.tsv"
  printf 'dpkg_packages_sha256=%s\n' "$(sha256sum "$T/manifest/dpkg-packages.tsv" | cut -d' ' -f1)" > "$T/manifest/toolchain.env"
  export FAKE_DPKG_LIST='ii \ttar\t1.34+dfsg-1.2\tamd64\nii \tbash\t5.2.15-2+b2\tamd64\nrc \told-removed\t1.0\tamd64\n'
  run_installer -- --verify-manifest
  assert_zero "$INSTALL_RC" "実際の版（ii のみ・順不同）が dpkg-packages.tsv と一致すれば 0"
  assert_contains "$INSTALL_OUT" "dpkg-packages.tsv と一致します（2 件）" "一致を通知"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "apt-get" "verify では apt を使わない"
  assert_not_contains "$(cat "$FAKE_INSTALL_LOG")" "curl" "verify ではダウンロードしない"
  export FAKE_DPKG_LIST='ii \ttar\t1.35+dfsg-1\tamd64\nii \tbash\t5.2.15-2+b2\tamd64\n'
  run_installer -- --verify-manifest
  assert_nonzero "$INSTALL_RC" "実際の版が違えば失敗"
  assert_contains "$INSTALL_OUT" "image の実際の dpkg の版が dpkg-packages.tsv と一致しません" "不一致を通知"
  assert_contains "$INSTALL_OUT" "1.35+dfsg-1" "差分を表示"
  export FAKE_DPKG_LIST='ii \ttar\t1.34+dfsg-1.2\tamd64\nii \tbash\t5.2.15-2+b2\tamd64\n'
  printf 'dpkg_packages_sha256=%s\n' "$(hex64 0)" > "$T/manifest/toolchain.env"
  run_installer -- --verify-manifest
  assert_nonzero "$INSTALL_RC" "toolchain.env の SHA-256 が違えば失敗"
  assert_contains "$INSTALL_OUT" "dpkg_packages_sha256" "SHA-256 の不一致を通知"
  run_installer -- --unknown-option
  assert_nonzero "$INSTALL_RC" "未知の引数は失敗"
  unset FAKE_DPKG_LIST
}

run_installer() { # [VAR=value ...] [-- <install-toolchain.sh の引数>...]
  local -a vars=() args=()
  while (( $# > 0 )); do
    if [[ "$1" == -- ]]; then shift; args=("$@"); break; fi
    vars+=("$1")
    shift
  done
  INSTALL_OUT=$(env PATH="$T/bin:$PATH" TMPDIR="$T" \
    BASE_IMAGE="debian:bookworm-slim@sha256:$(hex64 c)" \
    MYSQL_CLIENT_VERSION=8.4.13.1 MYSQL_DEB_REVISION=1debian12 MYSQL_APT_SUITE=bookworm MYSQL_APT_COMPONENT=mysql-8.4-lts \
    MYSQL_REPO_KEY_URL=https://repo.mysql.com/RPM-GPG-KEY-mysql-2025 \
    MYSQL_REPO_KEY_SHA256="$(hex64 d)" \
    MYSQL_REPO_KEY_FINGERPRINT=BCA43417C3B485DD128EC6D4B7B3B788A8D3785C \
    RESTIC_VERSION=0.17.3 RESTIC_LINUX_AMD64_SHA256="$(hex64 e)" \
    IMAGE_MANIFEST_DIR="$T/manifest" \
    "${vars[@]}" bash "$INSTALL_TOOLCHAIN" "${args[@]}" 2>&1)
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
  assert_nonzero "$INSTALL_RC" "署名鍵の SHA-256 が仮置きの値なら失敗"
  assert_contains "$INSTALL_OUT" "MYSQL_REPO_KEY_SHA256 が SHA-256" "仮置きの値の項目を通知"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "仮置きの値のままでは apt・ダウンロードを一切行わない"

  : > "$FAKE_INSTALL_LOG"
  run_installer RESTIC_LINUX_AMD64_SHA256=UNPINNED
  assert_nonzero "$INSTALL_RC" "restic の SHA-256 が仮置きの値なら失敗"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "restic の仮置きの値でも何も実行しない"

  : > "$FAKE_INSTALL_LOG"
  run_installer BASE_IMAGE=debian:bookworm-slim
  assert_nonzero "$INSTALL_RC" "base image が digest 固定でなければ失敗"
  assert_contains "$INSTALL_OUT" "BASE_IMAGE が digest で固定されていません" "base digest 未固定を通知"
  assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "base digest 未固定でも何も実行しない"
}

# RB-W 8.4 対応: client の版と repo の component は、install-toolchain.sh に列挙した MySQL 8.4 LTS の値（8.4.13.1・
# mysql-8.4-lts）だけを受け付ける。8.0・列挙していない 8.4 の patch・8.5 以降・innovation 系・空・前後の空白・大文字は、
# apt・ダウンロードより前に失敗する。列挙した値（Dockerfile の ARG の値）は受け付け、その版を mysql-8.4-lts から入れる。
case_installer_accepts_only_listed_mysql_84_lts() {
  local variant df_version df_revision df_component
  setup_fake_installer
  for variant in MYSQL_CLIENT_VERSION=8.0.46 MYSQL_CLIENT_VERSION=8.0.36 MYSQL_CLIENT_VERSION=8.4.12 \
      MYSQL_CLIENT_VERSION=8.4.13 MYSQL_CLIENT_VERSION=8.4.14 MYSQL_CLIENT_VERSION=8.5.0 MYSQL_CLIENT_VERSION=9.1.0 \
      MYSQL_CLIENT_VERSION=8.4 MYSQL_CLIENT_VERSION=8.4.13.1-1debian12 'MYSQL_CLIENT_VERSION=8.4.13.1 ' \
      'MYSQL_CLIENT_VERSION= 8.4.13.1' 'MYSQL_CLIENT_VERSION='; do
    : > "$FAKE_INSTALL_LOG"
    run_installer "$variant"
    assert_nonzero "$INSTALL_RC" "列挙していない client の版は失敗: [$variant]"
    assert_contains "$INSTALL_OUT" "MYSQL_CLIENT_VERSION は MySQL 8.4 LTS の列挙した版（8.4.13.1）だけを受け付けます" \
      "受け付ける版を通知: [$variant]"
    assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "列挙していない client の版では apt・ダウンロードを一切行わない: [$variant]"
  done
  for variant in MYSQL_APT_COMPONENT=mysql-8.0 MYSQL_APT_COMPONENT=mysql-innovation MYSQL_APT_COMPONENT=mysql-8.4 \
      MYSQL_APT_COMPONENT=mysql-8.5-lts MYSQL_APT_COMPONENT=mysql-tools MYSQL_APT_COMPONENT=MYSQL-8.4-LTS \
      'MYSQL_APT_COMPONENT=mysql-8.4-lts ' 'MYSQL_APT_COMPONENT='; do
    : > "$FAKE_INSTALL_LOG"
    run_installer "$variant"
    assert_nonzero "$INSTALL_RC" "列挙していない component は失敗: [$variant]"
    assert_contains "$INSTALL_OUT" "MYSQL_APT_COMPONENT は mysql-8.4-lts だけを受け付けます" "受け付ける component を通知: [$variant]"
    assert_eq "" "$(cat "$FAKE_INSTALL_LOG")" "列挙していない component では apt・ダウンロードを一切行わない: [$variant]"
  done

  # 受け付ける側: Dockerfile の ARG の値（8.4.13.1・1debian12・mysql-8.4-lts）で導入の最後（版一覧の記録）まで進む。
  # Dockerfile の版だけを上げて install-toolchain.sh の列挙を直さないと、ここが失敗する。
  df_version=$(tr -d '\r' < "$BACKUP_ROOT/Dockerfile" | sed -n 's/^ARG MYSQL_CLIENT_VERSION=//p')
  df_revision=$(tr -d '\r' < "$BACKUP_ROOT/Dockerfile" | sed -n 's/^ARG MYSQL_DEB_REVISION=//p')
  df_component=$(tr -d '\r' < "$BACKUP_ROOT/Dockerfile" | sed -n 's/^ARG MYSQL_APT_COMPONENT=//p')
  assert_eq "8.4.13.1/1debian12/mysql-8.4-lts" "$df_version/$df_revision/$df_component" "Dockerfile の ARG は 8.4.13.1・1debian12・mysql-8.4-lts（前提）"
  setup_installer_full_flow
  printf '# 記載なし\n' > "$T/lock"
  run_installer_full_flow "$T/lock" MYSQL_CLIENT_VERSION="$df_version" MYSQL_DEB_REVISION="$df_revision" \
    MYSQL_APT_COMPONENT="$df_component"
  assert_zero "$INSTALL_RC" "Dockerfile の値（列挙した 8.4 LTS の値）は受け付けて導入の最後まで進む"
  assert_contains "$(cat "$FAKE_INSTALL_LOG")" "mysql-community-client=8.4.13.1-1debian12" "client を列挙した版で入れる"
  assert_contains "$(cat "$FAKE_INSTALL_LOG")" "mysql-community-server-core=8.4.13.1-1debian12" \
    "server-core（mysqlbinlog を含む）を列挙した版で入れる"
  assert_eq "deb [signed-by=$SYSROOT/usr/share/keyrings/mysql-release-engineering.gpg] https://repo.mysql.com/apt/debian/ bookworm mysql-8.4-lts" \
    "$(cat "$SYSROOT/etc/apt/sources.list.d/mysql.list" 2>/dev/null)" "apt source の component は mysql-8.4-lts"
  assert_contains "$(cat "$T/manifest/toolchain.env" 2>/dev/null)" "mysql_packages=8.4.13.1-1debian12" "導入した版を記録する"
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
  # RB-W W-4: 最後の RUN で image の実際の dpkg の版と dpkg-packages.tsv を照合し、「base の版を保つ」とは書かない
  assert_eq "RUN bash /usr/local/share/ses-backup/image-build/install-toolchain.sh --verify-manifest" \
    "$(grep '^RUN ' "$BACKUP_ROOT/Dockerfile" | tail -n 1)" "Dockerfile の最後の RUN で dpkg の版を照合する"
  assert_eq "" "$(grep -E 'base image の版のまま|base image の版を使う' "$BACKUP_ROOT/Dockerfile" "$INSTALL_TOOLCHAIN" || true)" \
    "Essential が base image の版を保つという記述をしない（依存解決で更新され得る）"
  # RB-W W-7: content ID は実行に関わる Config の項目を要素ごとに覆い、store・Docker の版で変わる項目を使わない
  local tool field
  tool=$(cat "$TOOL_IMAGE")
  for field in .Os .Architecture .RootFS.Layers .Config.Env .Config.Entrypoint .Config.Cmd .Config.Shell .Config.OnBuild \
      .Config.WorkingDir .Config.User .Config.StopSignal .Config.Volumes .Config.ExposedPorts .Config.Labels .Config.Healthcheck; do
    assert_contains "$tool" "$field" "content ID に含める: $field"
  done
  for field in '{{json .Config}}' .Created .RepoTags .RepoDigests .GraphDriver .Metadata .DockerVersion .Config.Hostname .Config.Domainname; do
    assert_not_contains "$tool" "$field" "content ID に使わない: $field"
  done
  assert_contains "$tool" "CONTENT_ID_SCHEME=ses-backup-tool-content-id-v2" "算出方式を v2 にする（v1 の pin は一致しない = fail-closed）"
  # RB-W W-5: 注入できる system path の既定値は実際の path のままで、Dockerfile は接頭辞を設定しない
  local installer path
  installer=$(cat "$INSTALL_TOOLCHAIN")
  assert_not_contains "$dockerfile" "INSTALL_TOOLCHAIN_SYSROOT" "Dockerfile は system path の接頭辞を設定しない"
  assert_contains "$installer" 'SYSROOT=${INSTALL_TOOLCHAIN_SYSROOT:-}' "接頭辞の既定値は空"
  for path in 'MYSQL_KEYRING=$SYSROOT/usr/share/keyrings/mysql-release-engineering.gpg' \
      'MYSQL_APT_LIST=$SYSROOT/etc/apt/sources.list.d/mysql.list' \
      'RESTIC_INSTALL_PATH=$SYSROOT/usr/local/bin/restic' 'APT_LISTS_DIR=$SYSROOT/var/lib/apt/lists'; do
    assert_contains "$installer" "$path" "既定の system path: ${path%%=*}"
  done
  # RB-W 8.4 対応: integration の MySQL は production と同じ 8.4 LTS 系列（index digest で固定）
  assert_contains "$integration" \
    "image: mysql:8.4.13@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902" \
    "integration の mysql:8.4.13 を index digest で固定"
  assert_not_contains "$integration" "image: mysql:8.0" "integration に 8.0 の MySQL を残さない"
  # コメントを含めて MySQL image の tag は 8.4.13 だけ（RB-MYSQL84 の image 守衛は既定と違う tag の参照を例外の要否に使う）
  assert_eq "8.4.13" "$(grep -oE 'mysql:[0-9A-Za-z][A-Za-z0-9._-]*' <<< "$integration" | sed 's/^mysql://' | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')" \
    "integration compose の MySQL image の tag は 8.4.13 だけ（コメントも含む）"
  assert_not_contains "$integration" "      - --binlog-format=" "8.4 で非推奨の --binlog-format を server に渡さない"
  assert_contains "$dockerfile" "ARG MYSQL_CLIENT_VERSION=8.4.13.1" "ツールイメージの client は 8.4.13.1"
  assert_contains "$dockerfile" "ARG MYSQL_APT_COMPONENT=mysql-8.4-lts" "repo の component は mysql-8.4-lts"
  assert_contains "$prod" "image: ses-backup-tool:8.4.13" "production compose のツールイメージの tag は 8.4.13"
  assert_not_contains "$prod" "  build:" "production compose は build しない"
  assert_contains "$prod" "pull_policy: never" "production compose は registry から pull しない"
  assert_contains "$prod" "x-ses-backup-tool-content-id:" "production compose は store に依存しない content ID を参照する"
  assert_not_contains "$prod" "x-ses-backup-tool-image-id:" "store 依存の image ID では pin しない"
  # RB-W W-3(c): production compose は block style だけで書く（tool-image.sh check は flow style を拒否する）
  assert_eq "" "$(grep -nE '^[^#]*:[[:space:]]*[[{]|^[[:space:]]*-[[:space:]]*[[{]' "$PROD_COMPOSE" || true)" \
    "production compose に flow style（{ } / [ ]）が無い"
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
run_case case_content_id_covers_runtime_config
run_case case_check_rejects_build_and_pull
run_case case_record_writes_store_and_refuses_overwrite
run_case case_record_verifies_image_dpkg_against_manifest
run_case case_save_and_load_by_content_id
run_case case_compose_rejects_unlisted_usage_without_docker
run_case case_compose_runs_allowed_usage_after_check
run_case case_compose_runs_checked_image_id_even_if_retagged
run_case case_check_requires_pull_policy_per_service
run_case case_check_rejects_flow_style_and_unparsed_yaml
run_case case_installer_rejects_placeholders_before_download
run_case case_installer_accepts_only_listed_mysql_84_lts
run_case case_installer_rejects_key_checksum_mismatch
run_case case_installer_checks_primary_key_only
run_case case_installer_verify_manifest
run_case case_installer_apt_lock_enforced_via_injected_paths
run_case case_installer_apt_lock_mismatch_fails_via_injected_paths
run_case case_static_image_contract

# RB-QUIESCE BWQ-009（design.md §7.6）: 書込み静止の保持 process は process substitution の $! と wait を使うため bash 4.4 以上が要る。
# unit test は tool image の中で動く（run-unit-tests.sh）ため、ここで動く bash の版が tool image の bash の版である。base は
# Debian bookworm（bash 5.2）。provider の SQL（providers/quiesce-db.d）は COPY providers/ で image に入る。
case_tool_image_bash_supports_hold_process() {
  local dockerfile
  if (( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) )); then
    test_assert "bash は 4.4 以上（現在 $BASH_VERSION）"
  else
    test_fail "bash は 4.4 以上" "現在 $BASH_VERSION"
  fi
  dockerfile=$(cat "$BACKUP_ROOT/Dockerfile")
  assert_contains "$dockerfile" "ARG BASE_IMAGE=debian:bookworm-slim@" "base は bookworm（bash 5.2）"
  assert_contains "$dockerfile" "COPY providers/ /usr/local/lib/ses-backup/providers/" "provider と SQL（quiesce-db.d）を image に入れる"
  assert_contains "$dockerfile" "COPY lib/ /usr/local/lib/ses-backup/" "lib（quiesce-db-common.sh）を image に入れる"
}

run_case case_tool_image_bash_supports_hold_process

# RB-QUIESCE BWQ-010（design.md §9.3）: app の停止を確かめる full は `tool-image.sh compose run --rm backup --app-stopped` で
# 起動する（tool-image.sh は変えない）。run --rm <service> の後ろの引数は container へそのまま渡る
case_compose_passes_app_stopped_to_backup() {
  setup_fake_docker
  local copy content
  copy="$T/repo/ops/backup"
  mkdir -p "$copy/image"
  cp "$TOOL_IMAGE" "$copy/image/tool-image.sh"
  content=$(current_content_id)
  pinned_compose "$copy/docker-compose.yml" "$content"

  TOOL_OUT=$(bash "$copy/image/tool-image.sh" compose run --rm backup --app-stopped 2>&1)
  TOOL_RC=$?
  assert_zero "$TOOL_RC" "run --rm backup --app-stopped を許可する"
  assert_contains "$(grep '^compose ' "$FAKE_DOCKER_LOG")" "image-id.yml run --rm backup --app-stopped" \
    "--app-stopped を backup の container の引数として渡す"
  assert_contains "$(cat "$PROD_COMPOSE")" "/usr/local/bin/backup-full.sh" "backup の service の entrypoint は backup-full.sh"
}

run_case case_compose_passes_app_stopped_to_backup

test_summary "W1-B1 tool image reproducibility"
