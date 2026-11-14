#!/usr/bin/env bash
# ============================================================
# HFP-03 バックアップツールイメージの build・記録・配布・照合（private registry を使わない運用）
#
# 決定事項（XB-10）: private registry が無いため「ローカル build + ダウンロードの SHA-256 検証 +
# 識別子の記録 + docker save / load による配布」とする。production の compose は build せず、
# 記録した content ID（docker-compose.yml の x-ses-backup-tool-content-id）とローカル tag の
# content ID が一致する場合だけ使う（未 pin・不一致は拒否）。
#
# 識別子（W1-B1 r2）:
#   - image ID（docker image inspect の .Id）は Docker の image store によって意味が変わる。
#     classic（graphdriver）store では image config の digest、containerd image store（containerd
#     snapshotter）では image index / manifest の digest で、store の異なるホスト間で docker save / load
#     すると一致しない。そのため image ID と image store の種類は記録だけに使う。
#   - content ID は store に依存しない識別子で、pin と照合に使う。RootFS の layer diff ID 列
#     （非圧縮 layer の digest）と、image config の実行に関わる項目（Env / Entrypoint / Cmd / WorkingDir /
#     User / Labels）から SHA-256 を計算する（形式: sha256:<16 進 64 桁>）。
#
# usage:
#   tool-image.sh build-record --out DIR [--tag TAG]
#       ops/backup を docker build し（Dockerfile の SHA-256 / fingerprint 検証込み）、record を行う
#   tool-image.sh record --out DIR [--tag TAG]
#       content ID・image ID・image store の種類と、image 内の版一覧・固定値を DIR に記録する
#   tool-image.sh inspect [--tag TAG]
#       ローカル tag の content ID・image ID・image store の種類を表示する（変更はしない）
#   tool-image.sh save --out DIR [--tag TAG]
#       docker save で配布 tar を作り、tar の SHA-256・content ID・image ID・image store を DIR に記録する
#   tool-image.sh load --tar FILE --sha256 HEX --content-id sha256:HEX [--tag TAG]
#       tar の SHA-256 を照合してから docker load し、読み込んだ tag の content ID を照合する
#   tool-image.sh check [--compose FILE]
#       production compose の pin を検査し、ローカル tag の content ID と一致しなければ拒否する
#   tool-image.sh compose <許可された docker compose の使い方>
#       次の形だけを docker compose -f ops/backup/docker-compose.yml へ渡す（それ以外は拒否する）:
#         run --rm <service> [コンテナへ渡す引数...]   ※ check に合格した場合だけ
#         up -d binlog                                 ※ check に合格した場合だけ
#         ps [<service>] / logs [--tail N] <service> / stop <service>
#       <service> は preflight / backup / binlog / checkpoint / snapshot-binlog / check / retention。
#       -f / --file / -p / --pull / --build / --entrypoint / -e / -v などの compose 側の option は渡せない。
#
# 終了コード: 0 = 成功、1 = 失敗（未 pin・不一致を含む）、2 = 引数不正（許可されていない compose の使い方を含む）
# ============================================================
set -Eeuo pipefail
umask 077
export MSYS_NO_PATHCONV=1

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BACKUP_ROOT=$(cd "$HERE/.." && pwd)
DEFAULT_TAG=ses-backup-tool:8.0.46
PROD_COMPOSE="$BACKUP_ROOT/docker-compose.yml"
MANIFEST_IN_IMAGE=/usr/local/share/ses-backup/image-manifest
PIN_KEY=x-ses-backup-tool-content-id
CONTENT_ID_SCHEME=ses-backup-tool-content-id-v1
COMPOSE_SERVICES=(preflight backup binlog checkpoint snapshot-binlog check retention)
DOCKER_BIN=${DOCKER_BIN:-docker}

die() { echo "tool-image: ERROR: $*" >&2; exit 1; }
usage_error() {
  echo "tool-image: $*" >&2
  cat >&2 <<'EOF'
usage: tool-image.sh build-record --out DIR [--tag TAG]
       tool-image.sh record --out DIR [--tag TAG]
       tool-image.sh inspect [--tag TAG]
       tool-image.sh save --out DIR [--tag TAG]
       tool-image.sh load --tar FILE --sha256 HEX --content-id sha256:HEX [--tag TAG]
       tool-image.sh check [--compose FILE]
       tool-image.sh compose run --rm <service> [args...] | up -d binlog | ps [<service>]
                             | logs [--tail N] <service> | stop <service>
EOF
  exit 2
}

# docker（Windows では docker.exe）へ渡すホスト側パス
host_path() { cygpath -w "$1" 2>/dev/null || printf '%s\n' "$1"; }

valid_tag() { [[ "$1" =~ ^ses-backup-tool:[A-Za-z0-9._-]{1,128}$ ]]; }
valid_digest() { [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]; }

image_id_of() { # <ref> -> sha256:<64 桁>（image store 依存。記録用）
  local id
  id=$("$DOCKER_BIN" image inspect --format '{{.Id}}' "$1" 2>/dev/null) || return 1
  valid_digest "$id" || return 1
  printf '%s\n' "$id"
}

# image store に依存しない content ID（RootFS の layer diff ID 列 + 実行に関わる config 項目の SHA-256）
image_content_id() { # <ref> -> sha256:<64 桁>
  local layers config line count=0 sum
  layers=$("$DOCKER_BIN" image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$1" 2>/dev/null) || return 1
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    valid_digest "$line" || return 1
    count=$((count + 1))
  done <<< "$layers"
  (( count > 0 )) || return 1
  config=$("$DOCKER_BIN" image inspect \
    --format '{{json .Config.Env}} {{json .Config.Entrypoint}} {{json .Config.Cmd}} {{json .Config.WorkingDir}} {{json .Config.User}} {{json .Config.Labels}}' \
    "$1" 2>/dev/null) || return 1
  [[ -n "$config" && "$config" != *$'\n'* ]] || return 1
  sum=$(printf '%s\nrootfs:\n%s\nconfig:%s\n' "$CONTENT_ID_SCHEME" "$(printf '%s\n' "$layers" | sed '/^$/d')" "$config" | sha256sum)
  sum=${sum%% *}
  [[ "$sum" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf 'sha256:%s\n' "$sum"
}

# Docker の image store の種類（containerd image store なら containerd、それ以外は classic）
image_store_type() {
  local status
  status=$("$DOCKER_BIN" info --format '{{json .DriverStatus}}' 2>/dev/null) || return 1
  case "$status" in
    *io.containerd.snapshotter*) printf 'containerd\n' ;;
    *) printf 'classic\n' ;;
  esac
}

storage_driver() {
  "$DOCKER_BIN" info --format '{{.Driver}}' 2>/dev/null || printf 'unknown\n'
}

# 記録・配布物は既存ファイルを上書きしない（取り違え防止）
require_fresh_path() { # <path>
  [[ ! -e "$1" && ! -L "$1" ]] || die "既存のファイルを上書きしません: $1"
}

cmd_inspect() { # <tag>
  local tag=$1 content id store
  content=$(image_content_id "$tag") || die "image が見つからないか、content ID を計算できません: $tag"
  id=$(image_id_of "$tag") || die "image ID を取得できません: $tag"
  store=$(image_store_type) || die "docker info から image store の種類を取得できません"
  printf 'tag=%s\ncontent_id=%s\nimage_id=%s\nimage_store=%s\n' "$tag" "$content" "$id" "$store"
}

cmd_record() { # <tag> <out>
  local tag=$1 out=$2 id content store driver cid server_version
  content=$(image_content_id "$tag") || die "image が見つからないか、content ID を計算できません: $tag"
  id=$(image_id_of "$tag") || die "image ID を取得できません: $tag"
  store=$(image_store_type) || die "docker info から image store の種類を取得できません"
  driver=$(storage_driver)
  mkdir -p "$out"
  require_fresh_path "$out/tool-content-id.txt"
  require_fresh_path "$out/tool-image-id.txt"
  require_fresh_path "$out/tool-image-record.txt"
  require_fresh_path "$out/image-manifest"
  mkdir "$out/image-manifest"
  cid=$("$DOCKER_BIN" create "$tag" 2>/dev/null) || die "image から一時コンテナを作成できません: $tag"
  if ! "$DOCKER_BIN" cp "$cid:$MANIFEST_IN_IMAGE/." "$(host_path "$out/image-manifest")" > /dev/null 2>&1; then
    "$DOCKER_BIN" rm "$cid" > /dev/null 2>&1 || true
    die "image 内の版一覧（$MANIFEST_IN_IMAGE）を取り出せません"
  fi
  "$DOCKER_BIN" rm "$cid" > /dev/null 2>&1 || true
  [[ -s "$out/image-manifest/toolchain.env" && -s "$out/image-manifest/dpkg-packages.tsv" ]] \
    || die "image 内の版一覧が不完全です（toolchain.env / dpkg-packages.tsv）"
  server_version=$("$DOCKER_BIN" version --format '{{.Server.Version}}' 2>/dev/null) || server_version=unknown
  printf '%s\n' "$content" > "$out/tool-content-id.txt"
  printf '%s\n' "$id" > "$out/tool-image-id.txt"
  {
    printf 'tag=%s\n' "$tag"
    printf 'content_id=%s\n' "$content"
    printf 'image_id=%s\n' "$id"
    printf 'image_store=%s\n' "$store"
    printf 'storage_driver=%s\n' "$driver"
    printf 'docker_server_version=%s\n' "$server_version"
    printf 'recorded_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'toolchain_env_sha256=%s\n' "$(sha256sum "$out/image-manifest/toolchain.env" | cut -d' ' -f1)"
    printf 'dpkg_packages_sha256=%s\n' "$(sha256sum "$out/image-manifest/dpkg-packages.tsv" | cut -d' ' -f1)"
  } > "$out/tool-image-record.txt"
  echo "tool-image: 記録しました: $out（content_id=$content image_store=$store image_id=$id）"
  echo "tool-image: production で使う場合は docker-compose.yml の $PIN_KEY を \"$content\" に設定してください"
  echo "tool-image: image_id は image store（$store）に依存するため照合には使いません（記録のみ）"
}

cmd_build_record() { # <tag> <out>
  local tag=$1 out=$2
  "$DOCKER_BIN" build -t "$tag" "$(host_path "$BACKUP_ROOT")" || die "docker build に失敗しました（SHA-256 / fingerprint の検証失敗を含む）"
  cmd_record "$tag" "$out"
}

cmd_save() { # <tag> <out>
  local tag=$1 out=$2 id content store tar sha
  content=$(image_content_id "$tag") || die "image が見つからないか、content ID を計算できません: $tag"
  id=$(image_id_of "$tag") || die "image ID を取得できません: $tag"
  store=$(image_store_type) || die "docker info から image store の種類を取得できません"
  mkdir -p "$out"
  tar="$out/ses-backup-tool-${content:7:12}.tar"
  local f
  for f in "$tar" "$tar.sha256" "$tar.content-id" "$tar.image-id" "$tar.image-store"; do
    require_fresh_path "$f"
  done
  "$DOCKER_BIN" save -o "$(host_path "$tar")" "$tag" || die "docker save に失敗しました"
  [[ -s "$tar" ]] || die "docker save の出力がありません: $tar"
  sha=$(sha256sum "$tar" | cut -d' ' -f1)
  printf '%s  %s\n' "$sha" "${tar##*/}" > "$tar.sha256"
  printf '%s\n' "$content" > "$tar.content-id"
  printf '%s\n' "$id" > "$tar.image-id"
  printf '%s\n' "$store" > "$tar.image-store"
  echo "tool-image: 配布 tar を作成しました: $tar"
  echo "tool-image: sha256=$sha content_id=$content（image_store=$store image_id=$id は記録のみ）"
  echo "tool-image: 配布先では: tool-image.sh load --tar <tar> --sha256 $sha --content-id $content --tag $tag"
}

cmd_load() { # <tar> <sha256> <content-id> <tag>
  local tar=$1 expected_sha=$2 expected_content=$3 tag=$4 actual content id store recorded_store=""
  [[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || die "--sha256 は小文字 16 進 64 桁で指定してください"
  valid_digest "$expected_content" || die "--content-id は sha256:<16 進 64 桁> で指定してください"
  [[ -f "$tar" && ! -L "$tar" ]] || die "tar がありません: $tar"
  actual=$(sha256sum "$tar" | cut -d' ' -f1)
  [[ "$actual" == "$expected_sha" ]] || die "tar の SHA-256 が一致しません（expected=$expected_sha actual=$actual）。docker load しません"
  store=$(image_store_type) || die "docker info から image store の種類を取得できません（docker load しません）"
  if [[ -f "$tar.image-store" ]]; then
    recorded_store=$(head -n 1 "$tar.image-store")
  fi
  if [[ -n "$recorded_store" && "$recorded_store" != "$store" ]]; then
    echo "tool-image: 注意: build 機の image store（$recorded_store）と、このホストの image store（$store）が異なります。image ID は一致しませんが、照合は store に依存しない content ID で行います" >&2
  fi
  "$DOCKER_BIN" load -i "$(host_path "$tar")" || die "docker load に失敗しました"
  content=$(image_content_id "$tag") || die "docker load 後に $tag が見つからないか、content ID を計算できません"
  [[ "$content" == "$expected_content" ]] \
    || die "docker load した $tag の content ID が一致しません（expected=$expected_content actual=$content）。この tag は使わないでください"
  id=$(image_id_of "$tag") || id=unknown
  echo "tool-image: 読み込みました: $tag content_id=$content（image_store=$store image_id=$id）"
}

# compose の pin 値（x-ses-backup-tool-content-id）を 1 つだけ読む
read_pin() { # <compose>
  local lines count
  lines=$(grep -E "^${PIN_KEY}:" "$1" 2>/dev/null || true)
  count=$(printf '%s' "$lines" | grep -c . || true)
  [[ "$count" == 1 ]] || return 1
  printf '%s\n' "$lines" | sed -nE "s/^${PIN_KEY}:[[:space:]]*\"?([^\"[:space:]]*)\"?[[:space:]]*(#.*)?$/\1/p"
}

cmd_check() { # <compose>
  local compose=$1 pin tags tag count content store id
  [[ -f "$compose" ]] || die "compose ファイルがありません: $compose"
  pin=$(read_pin "$compose") || pin=""
  valid_digest "$pin" \
    || die "未 pin: $compose の $PIN_KEY が content ID（sha256:<16 進 64 桁>）ではありません（現在値: ${pin:-なし}）。tool-image.sh build-record / load で確認した content ID を設定するまで production では使えません"
  if grep -qE '^[[:space:]]+build:' "$compose"; then
    die "$compose に build: があります（registry を使わない運用では、記録・配布した image だけを使う）"
  fi
  grep -qE '^[[:space:]]+pull_policy:[[:space:]]*never[[:space:]]*$' "$compose" \
    || die "$compose に pull_policy: never がありません（同名 image を registry から取得させない）"
  tags=$(sed -nE 's/^[[:space:]]+image:[[:space:]]*"?([^"[:space:]]+)"?[[:space:]]*$/\1/p' "$compose" | LC_ALL=C sort -u)
  count=$(printf '%s' "$tags" | grep -c . || true)
  [[ "$count" == 1 ]] || die "$compose の image: がツールイメージ 1 種類ではありません（$(printf '%s' "$tags" | tr '\n' ' ')）"
  tag=$tags
  valid_tag "$tag" || die "$compose の image: がツールイメージの tag ではありません: $tag"
  content=$(image_content_id "$tag") \
    || die "ローカルに $tag が無いか、content ID を計算できません（tool-image.sh load で配布 tar を読み込んでください）"
  [[ "$content" == "$pin" ]] \
    || die "ローカルの $tag の content ID（$content）は pin（$pin）と一致しません。tool-image.sh load で配布 tar を読み込み直してください"
  store=$(image_store_type) || store=unknown
  id=$(image_id_of "$tag") || id=unknown
  echo "tool-image: OK: $tag content_id=$pin（image_store=$store image_id=$id）"
}

is_compose_service() { # <name>
  local s
  for s in "${COMPOSE_SERVICES[@]}"; do
    [[ "$1" == "$s" ]] && return 0
  done
  return 1
}

# 許可された docker compose の使い方かを検査する。0 = 許可（COMPOSE_NEEDS_CHECK に pin 照合の要否）
COMPOSE_NEEDS_CHECK=1
allowed_compose_args() { # <args...>
  COMPOSE_NEEDS_CHECK=1
  case "${1:-}" in
    run)
      # run --rm <service> の後ろはコンテナのコマンド引数として渡る（compose の option にはならない）
      [[ "${2:-}" == --rm ]] && is_compose_service "${3:-}" ;;
    up)
      [[ $# -eq 3 && "$2" == -d && "$3" == binlog ]] ;;
    ps)
      COMPOSE_NEEDS_CHECK=0
      [[ $# -eq 1 ]] || { [[ $# -eq 2 ]] && is_compose_service "$2"; } ;;
    logs)
      COMPOSE_NEEDS_CHECK=0
      { [[ $# -eq 2 ]] && is_compose_service "$2"; } \
        || { [[ $# -eq 4 && "$2" == --tail && "$3" =~ ^[0-9]{1,6}$ ]] && is_compose_service "$4"; } ;;
    stop)
      COMPOSE_NEEDS_CHECK=0
      [[ $# -eq 2 ]] && is_compose_service "$2" ;;
    *) return 1 ;;
  esac
}

cmd_compose() {
  if ! allowed_compose_args "$@"; then
    usage_error "許可されていない docker compose の使い方です: $*（compose 側の option・別の compose ファイル・未定義の service は渡せません）"
  fi
  if (( COMPOSE_NEEDS_CHECK )); then
    cmd_check "$PROD_COMPOSE" >&2
  fi
  exec "$DOCKER_BIN" compose -f "$(host_path "$PROD_COMPOSE")" "$@"
}

main() {
  local cmd=${1:-} tag=$DEFAULT_TAG out="" tar="" sha="" content_id="" compose=$PROD_COMPOSE
  [[ -n "$cmd" ]] || usage_error "サブコマンドを指定してください"
  shift
  if [[ "$cmd" == compose ]]; then
    cmd_compose "$@"
  fi
  while (( $# > 0 )); do
    case "$1" in
      --tag) [[ $# -ge 2 ]] || usage_error "--tag に値が必要です"; tag=$2; shift 2 ;;
      --out) [[ $# -ge 2 ]] || usage_error "--out に値が必要です"; out=$2; shift 2 ;;
      --tar) [[ $# -ge 2 ]] || usage_error "--tar に値が必要です"; tar=$2; shift 2 ;;
      --sha256) [[ $# -ge 2 ]] || usage_error "--sha256 に値が必要です"; sha=$2; shift 2 ;;
      --content-id) [[ $# -ge 2 ]] || usage_error "--content-id に値が必要です"; content_id=$2; shift 2 ;;
      --compose) [[ $# -ge 2 ]] || usage_error "--compose に値が必要です"; compose=$2; shift 2 ;;
      *) usage_error "未知の引数です: $1" ;;
    esac
  done
  valid_tag "$tag" || usage_error "--tag は ses-backup-tool:<tag> の形式で指定してください: $tag"
  case "$cmd" in
    build-record) [[ -n "$out" ]] || usage_error "--out が必要です"; cmd_build_record "$tag" "$out" ;;
    record) [[ -n "$out" ]] || usage_error "--out が必要です"; cmd_record "$tag" "$out" ;;
    inspect) cmd_inspect "$tag" ;;
    save) [[ -n "$out" ]] || usage_error "--out が必要です"; cmd_save "$tag" "$out" ;;
    load) [[ -n "$tar" && -n "$sha" && -n "$content_id" ]] || usage_error "--tar / --sha256 / --content-id が必要です"
      cmd_load "$tar" "$sha" "$content_id" "$tag" ;;
    check) cmd_check "$compose" ;;
    *) usage_error "未知のサブコマンドです: $cmd" ;;
  esac
}

main "$@"
