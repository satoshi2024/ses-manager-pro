#!/usr/bin/env bash
# ============================================================
# HFP-03 バックアップツールイメージの build・記録・配布・照合（private registry を使わない運用）
#
# 決定事項（XB-10）: private registry が無いため「ローカル build + ダウンロードの SHA-256 検証 +
# image ID の記録 + docker save / load による配布」とする。production の compose は build せず、
# 記録した image ID（docker-compose.yml の x-ses-backup-tool-image-id）とローカル tag の image ID が
# 一致する場合だけ使う（未 pin・不一致は拒否）。
#
# usage:
#   tool-image.sh build-record --out DIR [--tag TAG]
#       ops/backup を docker build し（Dockerfile の SHA-256 / fingerprint 検証込み）、record を行う
#   tool-image.sh record --out DIR [--tag TAG]
#       image ID と image 内の版一覧・固定値（/usr/local/share/ses-backup/image-manifest）を DIR に記録する
#   tool-image.sh save --out DIR [--tag TAG]
#       docker save で配布 tar を作り、tar の SHA-256 と image ID を DIR に記録する
#   tool-image.sh load --tar FILE --sha256 HEX --image-id sha256:HEX [--tag TAG]
#       tar の SHA-256 を照合してから docker load し、読み込んだ tag の image ID を照合する
#   tool-image.sh check [--compose FILE]
#       production compose の pin を検査し、ローカル tag の image ID と一致しなければ拒否する
#   tool-image.sh compose [docker compose の引数...]
#       check に合格した場合だけ docker compose -f ops/backup/docker-compose.yml を実行する
#
# 終了コード: 0 = 成功、1 = 失敗（未 pin・不一致を含む）、2 = 引数不正
# ============================================================
set -Eeuo pipefail
umask 077
export MSYS_NO_PATHCONV=1

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BACKUP_ROOT=$(cd "$HERE/.." && pwd)
DEFAULT_TAG=ses-backup-tool:8.0.46
PROD_COMPOSE="$BACKUP_ROOT/docker-compose.yml"
MANIFEST_IN_IMAGE=/usr/local/share/ses-backup/image-manifest
PIN_KEY=x-ses-backup-tool-image-id
DOCKER_BIN=${DOCKER_BIN:-docker}

die() { echo "tool-image: ERROR: $*" >&2; exit 1; }
usage_error() {
  echo "tool-image: $*" >&2
  cat >&2 <<'EOF'
usage: tool-image.sh build-record --out DIR [--tag TAG]
       tool-image.sh record --out DIR [--tag TAG]
       tool-image.sh save --out DIR [--tag TAG]
       tool-image.sh load --tar FILE --sha256 HEX --image-id sha256:HEX [--tag TAG]
       tool-image.sh check [--compose FILE]
       tool-image.sh compose [docker compose の引数...]
EOF
  exit 2
}

# docker（Windows では docker.exe）へ渡すホスト側パス
host_path() { cygpath -w "$1" 2>/dev/null || printf '%s\n' "$1"; }

valid_tag() { [[ "$1" =~ ^ses-backup-tool:[A-Za-z0-9._-]{1,128}$ ]]; }
valid_image_id() { [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]; }

image_id_of() { # <tag> -> sha256:<64 桁>
  local id
  id=$("$DOCKER_BIN" image inspect --format '{{.Id}}' "$1" 2>/dev/null) || return 1
  valid_image_id "$id" || return 1
  printf '%s\n' "$id"
}

# 記録・配布物は既存ファイルを上書きしない（取り違え防止）
require_fresh_path() { # <path>
  [[ ! -e "$1" && ! -L "$1" ]] || die "既存のファイルを上書きしません: $1"
}

cmd_record() { # <tag> <out>
  local tag=$1 out=$2 id cid server_version
  id=$(image_id_of "$tag") || die "image が見つかりません: $tag"
  mkdir -p "$out"
  require_fresh_path "$out/tool-image-id.txt"
  require_fresh_path "$out/image-manifest"
  mkdir "$out/image-manifest"
  cid=$("$DOCKER_BIN" create "$id" 2>/dev/null) || die "image から一時コンテナを作成できません: $id"
  if ! "$DOCKER_BIN" cp "$cid:$MANIFEST_IN_IMAGE/." "$(host_path "$out/image-manifest")" > /dev/null 2>&1; then
    "$DOCKER_BIN" rm "$cid" > /dev/null 2>&1 || true
    die "image 内の版一覧（$MANIFEST_IN_IMAGE）を取り出せません"
  fi
  "$DOCKER_BIN" rm "$cid" > /dev/null 2>&1 || true
  [[ -s "$out/image-manifest/toolchain.env" && -s "$out/image-manifest/dpkg-packages.tsv" ]] \
    || die "image 内の版一覧が不完全です（toolchain.env / dpkg-packages.tsv）"
  server_version=$("$DOCKER_BIN" version --format '{{.Server.Version}}' 2>/dev/null) || server_version=unknown
  printf '%s\n' "$id" > "$out/tool-image-id.txt"
  {
    printf 'tag=%s\n' "$tag"
    printf 'image_id=%s\n' "$id"
    printf 'recorded_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'docker_server_version=%s\n' "$server_version"
    printf 'toolchain_env_sha256=%s\n' "$(sha256sum "$out/image-manifest/toolchain.env" | cut -d' ' -f1)"
    printf 'dpkg_packages_sha256=%s\n' "$(sha256sum "$out/image-manifest/dpkg-packages.tsv" | cut -d' ' -f1)"
  } > "$out/tool-image-record.txt"
  echo "tool-image: 記録しました: $out（image_id=$id）"
  echo "tool-image: production で使う場合は docker-compose.yml の $PIN_KEY を \"$id\" に設定してください"
}

cmd_build_record() { # <tag> <out>
  local tag=$1 out=$2
  "$DOCKER_BIN" build -t "$tag" "$(host_path "$BACKUP_ROOT")" || die "docker build に失敗しました（SHA-256 / fingerprint の検証失敗を含む）"
  cmd_record "$tag" "$out"
}

cmd_save() { # <tag> <out>
  local tag=$1 out=$2 id tar sha
  id=$(image_id_of "$tag") || die "image が見つかりません: $tag"
  mkdir -p "$out"
  tar="$out/ses-backup-tool-${id:7:12}.tar"
  require_fresh_path "$tar"
  require_fresh_path "$tar.sha256"
  "$DOCKER_BIN" save -o "$(host_path "$tar")" "$tag" || die "docker save に失敗しました"
  [[ -s "$tar" ]] || die "docker save の出力がありません: $tar"
  sha=$(sha256sum "$tar" | cut -d' ' -f1)
  printf '%s  %s\n' "$sha" "${tar##*/}" > "$tar.sha256"
  printf '%s\n' "$id" > "$tar.image-id"
  echo "tool-image: 配布 tar を作成しました: $tar"
  echo "tool-image: sha256=$sha image_id=$id"
  echo "tool-image: 配布先では: tool-image.sh load --tar <tar> --sha256 $sha --image-id $id --tag $tag"
}

cmd_load() { # <tar> <sha256> <image-id> <tag>
  local tar=$1 expected_sha=$2 expected_id=$3 tag=$4 actual id
  [[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || die "--sha256 は小文字 16 進 64 桁で指定してください"
  valid_image_id "$expected_id" || die "--image-id は sha256:<16 進 64 桁> で指定してください"
  [[ -f "$tar" && ! -L "$tar" ]] || die "tar がありません: $tar"
  actual=$(sha256sum "$tar" | cut -d' ' -f1)
  [[ "$actual" == "$expected_sha" ]] || die "tar の SHA-256 が一致しません（expected=$expected_sha actual=$actual）。docker load しません"
  "$DOCKER_BIN" load -i "$(host_path "$tar")" || die "docker load に失敗しました"
  id=$(image_id_of "$tag") || die "docker load 後に $tag が見つかりません"
  [[ "$id" == "$expected_id" ]] \
    || die "docker load した $tag の image ID が一致しません（expected=$expected_id actual=$id）。この tag は使わないでください"
  echo "tool-image: 読み込みました: $tag = $id"
}

# compose の pin 値（x-ses-backup-tool-image-id）を 1 つだけ読む
read_pin() { # <compose>
  local lines count
  lines=$(grep -E "^${PIN_KEY}:" "$1" 2>/dev/null || true)
  count=$(printf '%s' "$lines" | grep -c . || true)
  [[ "$count" == 1 ]] || return 1
  printf '%s\n' "$lines" | sed -nE "s/^${PIN_KEY}:[[:space:]]*\"?([^\"[:space:]]*)\"?[[:space:]]*(#.*)?$/\1/p"
}

cmd_check() { # <compose>
  local compose=$1 pin tags tag count id
  [[ -f "$compose" ]] || die "compose ファイルがありません: $compose"
  pin=$(read_pin "$compose") || pin=""
  valid_image_id "$pin" \
    || die "未 pin: $compose の $PIN_KEY が image ID（sha256:<16 進 64 桁>）ではありません（現在値: ${pin:-なし}）。tool-image.sh build-record / load で確認した image ID を設定するまで production では使えません"
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
  id=$(image_id_of "$tag") || die "ローカルに $tag がありません（tool-image.sh load で配布 tar を読み込んでください）"
  [[ "$id" == "$pin" ]] || die "ローカルの $tag（$id）は pin（$pin）と一致しません。tool-image.sh load で配布 tar を読み込み直してください"
  echo "tool-image: OK: $tag = $pin"
}

cmd_compose() {
  cmd_check "$PROD_COMPOSE" >&2
  exec "$DOCKER_BIN" compose -f "$(host_path "$PROD_COMPOSE")" "$@"
}

main() {
  local cmd=${1:-} tag=$DEFAULT_TAG out="" tar="" sha="" image_id="" compose=$PROD_COMPOSE
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
      --image-id) [[ $# -ge 2 ]] || usage_error "--image-id に値が必要です"; image_id=$2; shift 2 ;;
      --compose) [[ $# -ge 2 ]] || usage_error "--compose に値が必要です"; compose=$2; shift 2 ;;
      *) usage_error "未知の引数です: $1" ;;
    esac
  done
  valid_tag "$tag" || usage_error "--tag は ses-backup-tool:<tag> の形式で指定してください: $tag"
  case "$cmd" in
    build-record) [[ -n "$out" ]] || usage_error "--out が必要です"; cmd_build_record "$tag" "$out" ;;
    record) [[ -n "$out" ]] || usage_error "--out が必要です"; cmd_record "$tag" "$out" ;;
    save) [[ -n "$out" ]] || usage_error "--out が必要です"; cmd_save "$tag" "$out" ;;
    load) [[ -n "$tar" && -n "$sha" && -n "$image_id" ]] || usage_error "--tar / --sha256 / --image-id が必要です"
      cmd_load "$tar" "$sha" "$image_id" "$tag" ;;
    check) cmd_check "$compose" ;;
    *) usage_error "未知のサブコマンドです: $cmd" ;;
  esac
}

main "$@"
