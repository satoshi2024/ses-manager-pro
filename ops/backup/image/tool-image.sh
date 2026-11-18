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
#   - content ID は store に依存しない識別子で、pin と照合に使う。os / arch、RootFS の layer diff ID 列
#     （非圧縮 layer の digest）と、image config の実行に関わる項目（Env / Entrypoint / Cmd / Shell / OnBuild /
#     WorkingDir / User / StopSignal / Volumes / ExposedPorts / Labels / Healthcheck。RB-W W-7）を項目ごとに
#     正規化した形から SHA-256 を計算する（形式: sha256:<16 進 64 桁>。算出方式 ses-backup-tool-content-id-v2）。
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
#       production compose を解釈し（block style の YAML だけを扱う。flow style などは拒否する）、pin と、
#       service ごと（merge を解決した実効値）の image・pull_policy: never・build / extends の不在を検査する。
#       tag を 1 回だけ image ID へ解決し、その image ID の content ID が pin と一致しなければ拒否する
#   tool-image.sh compose <許可された docker compose の使い方>
#       次の形だけを docker compose -f ops/backup/docker-compose.yml へ渡す（それ以外は拒否する）:
#         run --rm <service> [コンテナへ渡す引数...]   ※ check に合格した場合だけ
#         up -d binlog                                 ※ check に合格した場合だけ
#         ps [<service>] / logs [--tail N] <service> / stop <service>
#       <service> は preflight / backup / binlog / checkpoint / snapshot-binlog / check / retention。
#       -f / --file / -p / --pull / --build / --entrypoint / -e / -v などの compose 側の option は渡せない。
#       check に合格した run / up は、照合した image ID を全 service の image に固定する一時 override を
#       2 つ目の -f として渡して起動する（照合の後に tag を付け替えられても、照合した image だけが起動する）。
#
# 終了コード: 0 = 成功、1 = 失敗（未 pin・不一致を含む）、2 = 引数不正（許可されていない compose の使い方を含む）
# ============================================================
set -Eeuo pipefail
umask 077
export MSYS_NO_PATHCONV=1

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BACKUP_ROOT=$(cd "$HERE/.." && pwd)
DEFAULT_TAG=ses-backup-tool:8.4.13
PROD_COMPOSE="$BACKUP_ROOT/docker-compose.yml"
MANIFEST_IN_IMAGE=/usr/local/share/ses-backup/image-manifest
PIN_KEY=x-ses-backup-tool-content-id
CONTENT_ID_SCHEME=ses-backup-tool-content-id-v2
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

# content ID の対象（RB-W W-7。1 回の docker image inspect で、1 行 1 項目の正規形に描画する）:
#   os / arch、RootFS の layer diff ID（順序どおり）、Config の Env・Entrypoint・Cmd・Shell・OnBuild（各要素を順序どおり）、
#   WorkingDir・User・StopSignal、Volumes・ExposedPorts・Labels（map は Go template が key の昇順で回す）、Healthcheck。
# 正規化: 文字列は要素ごとに {{json}}（Go の encoding/json。改行等を escape し Docker の版で変わらない）で 1 行にし、
# list と map は Config 全体を 1 つの json にする出力（Docker の版で項目が増減する）ではなく要素を range で並べる。null と空の list / map は
# どちらも行を出さず同じになる。Id・Created・RepoTags・RepoDigests・GraphDriver・Metadata・DockerVersion・
# Config の Hostname / Domainname など、内容に関わらず store や Docker の版で変わる項目は使わない。
CONTENT_ID_TEMPLATE='os={{json .Os}}{{println}}arch={{json .Architecture}}{{println}}'\
'{{range .RootFS.Layers}}layer={{.}}{{println}}{{end}}'\
'{{range .Config.Env}}env={{json .}}{{println}}{{end}}'\
'{{range .Config.Entrypoint}}entrypoint={{json .}}{{println}}{{end}}'\
'{{range .Config.Cmd}}cmd={{json .}}{{println}}{{end}}'\
'{{range .Config.Shell}}shell={{json .}}{{println}}{{end}}'\
'{{range .Config.OnBuild}}onbuild={{json .}}{{println}}{{end}}'\
'workdir={{json .Config.WorkingDir}}{{println}}user={{json .Config.User}}{{println}}stopsignal={{json .Config.StopSignal}}{{println}}'\
'{{range $k, $v := .Config.Volumes}}volume={{json $k}}{{println}}{{end}}'\
'{{range $k, $v := .Config.ExposedPorts}}port={{json $k}}{{println}}{{end}}'\
'{{range $k, $v := .Config.Labels}}label={{json $k}}={{json $v}}{{println}}{{end}}'\
'healthcheck={{json .Config.Healthcheck}}{{println}}'

# content ID の正規形（image_content_id が SHA-256 にかける本体）を出力する。描画できない（Docker の版でいずれかの
# 項目が無い等）・想定外の行・layer が無い・単一項目の数が違う場合は失敗する（fail-closed）。
image_content_render() { # <image ID>
  local out
  out=$("$DOCKER_BIN" image inspect --format "$CONTENT_ID_TEMPLATE" "$1" 2>/dev/null) || return 1
  # 行末の CR（Windows の docker.exe 経由の出力に付くことがある）だけを除き、それ以外は正規形として扱う
  out=$(printf '%s\n' "$out" | tr -d '\r') || return 1
  printf '%s\n' "$out" | awk '
    /^(os|arch|workdir|user|stopsignal|healthcheck)=/ { split($0, kv, "="); single[kv[1]]++; next }
    /^layer=sha256:[0-9a-f]+$/ && length($0) == 77 { layers++; next }
    /^(env|entrypoint|cmd|shell|onbuild|volume|port)="/ { next }
    /^label="/ { next }
    { bad = 1; exit }
    END {
      if (bad || layers == 0) exit 1
      n = split("os arch workdir user stopsignal healthcheck", names, " ")
      for (i = 1; i <= n; i++) if (single[names[i]] != 1) exit 1
    }' || return 1
  printf '%s\n' "$out"
}

# image store に依存しない content ID（上の正規形の SHA-256。先頭に算出方式名を置く）
image_content_id() { # <image ID> -> sha256:<64 桁>
  local render sum
  render=$(image_content_render "$1") || return 1
  sum=$(printf '%s\n%s\n' "$CONTENT_ID_SCHEME" "$render" | sha256sum)
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

# build 後の image の実際の dpkg の版が、image 内の dpkg-packages.tsv（と toolchain.env の SHA-256）と一致することを
# 確かめる（RB-W W-4）。一致しなければ記録を書かずに失敗する。dpkg-query は network なしの一時コンテナで実行する。
verify_image_dpkg() { # <image ID> <取り出した image-manifest>
  local id=$1 manifest=$2 actual recorded expected_sha
  actual=$("$DOCKER_BIN" run --rm --network none --entrypoint dpkg-query "$id" -W \
      '--showformat=${db:Status-Abbrev}\t${Package}\t${Version}\t${Architecture}\n' 2>/dev/null) \
    || die "image の dpkg パッケージ一覧を取得できません（$id）"
  actual=$(printf '%s\n' "$actual" | tr -d '\r' | awk -F'\t' '$1 ~ /^ii/ {print $2 "\t" $3 "\t" $4}' | LC_ALL=C sort)
  [[ -n "$actual" ]] || die "image の dpkg パッケージ一覧が空です（$id）"
  recorded=$(LC_ALL=C sort "$manifest/dpkg-packages.tsv")
  if [[ "$actual" != "$recorded" ]]; then
    echo "tool-image: dpkg-packages.tsv と image の実際の版の差分（< 記録 / > 実際）:" >&2
    diff <(printf '%s\n' "$recorded") <(printf '%s\n' "$actual") >&2 || true
    die "image の実際の dpkg の版が dpkg-packages.tsv と一致しません（記録しません）"
  fi
  expected_sha=$(sed -n 's/^dpkg_packages_sha256=//p' "$manifest/toolchain.env")
  [[ "$expected_sha" == "$(sha256sum "$manifest/dpkg-packages.tsv" | cut -d' ' -f1)" ]] \
    || die "toolchain.env の dpkg_packages_sha256（${expected_sha:-なし}）が dpkg-packages.tsv と一致しません（記録しません）"
}

cmd_inspect() { # <tag>
  local tag=$1 content id store
  id=$(image_id_of "$tag") || die "image が見つからないか、image ID を取得できません: $tag"
  content=$(image_content_id "$id") || die "content ID を計算できません: $tag（$id）"
  store=$(image_store_type) || die "docker info から image store の種類を取得できません"
  printf 'tag=%s\ncontent_id=%s\nimage_id=%s\nimage_store=%s\n' "$tag" "$content" "$id" "$store"
}

cmd_record() { # <tag> <out>
  local tag=$1 out=$2 id content store driver cid server_version
  # tag は 1 回だけ image ID へ解決し、記録・版一覧の取り出しはその image ID で行う（RB-W W-3）
  id=$(image_id_of "$tag") || die "image が見つからないか、image ID を取得できません: $tag"
  content=$(image_content_id "$id") || die "content ID を計算できません: $tag（$id）"
  store=$(image_store_type) || die "docker info から image store の種類を取得できません"
  driver=$(storage_driver)
  mkdir -p "$out"
  require_fresh_path "$out/tool-content-id.txt"
  require_fresh_path "$out/tool-image-id.txt"
  require_fresh_path "$out/tool-image-record.txt"
  require_fresh_path "$out/image-manifest"
  mkdir "$out/image-manifest"
  cid=$("$DOCKER_BIN" create "$id" 2>/dev/null) || die "image から一時コンテナを作成できません: $tag（$id）"
  if ! "$DOCKER_BIN" cp "$cid:$MANIFEST_IN_IMAGE/." "$(host_path "$out/image-manifest")" > /dev/null 2>&1; then
    "$DOCKER_BIN" rm "$cid" > /dev/null 2>&1 || true
    die "image 内の版一覧（$MANIFEST_IN_IMAGE）を取り出せません"
  fi
  "$DOCKER_BIN" rm "$cid" > /dev/null 2>&1 || true
  [[ -s "$out/image-manifest/toolchain.env" && -s "$out/image-manifest/dpkg-packages.tsv" ]] \
    || die "image 内の版一覧が不完全です（toolchain.env / dpkg-packages.tsv）"
  verify_image_dpkg "$id" "$out/image-manifest"
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
  # save は tag を保つため tag で行う。tag を付け替えられて別の image を保存した場合は、配布先の load が
  # 記録した content ID との不一致で拒否する
  id=$(image_id_of "$tag") || die "image が見つからないか、image ID を取得できません: $tag"
  content=$(image_content_id "$id") || die "content ID を計算できません: $tag（$id）"
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
  id=$(image_id_of "$tag") || die "docker load 後に $tag が見つからないか、image ID を取得できません"
  content=$(image_content_id "$id") || die "docker load した $tag（$id）の content ID を計算できません"
  [[ "$content" == "$expected_content" ]] \
    || die "docker load した $tag の content ID が一致しません（expected=$expected_content actual=$content）。この tag は使わないでください"
  echo "tool-image: 読み込みました: $tag content_id=$content（image_store=$store image_id=$id）"
}

# production compose を解釈し、pin と service ごとの実効値を出力する（RB-W W-3）。
# 扱うのは block style の YAML のうち、この compose が使う部分集合だけ: 「key: スカラー」「key:（子 block）」
# 「- スカラー」、トップレベルの x- key に置く anchor（&name）と、x- mapping・service 直下の merge（<<: *name）。
# flow style（{ } / [ ]）、tag（!）、block scalar（| >）、alias の他の使い方、引用符付き・complex key、
# document marker、indent の tab、key の重複、複数行の値、sequence の中の mapping は解釈せずに拒否する
# （service の image・pull_policy を見落とす書き方を fail-closed にする）。merge は YAML と同じく、自身の key が
# merge した key より優先する。出力（tab 区切り）:
#   error <理由>                                          解釈できない（これ以外の行は出さない）
#   pin <値>                                              トップレベルの x-ses-backup-tool-content-id
#   top <key>                                             トップレベルの key
#   service <名前> <image> <pull_policy> <build 有無> <extends 有無>   merge を解決した実効値（無ければ空）
compose_model() { # <compose>
  awk -v pin_key="$PIN_KEY" '
    function fail(msg) { if (err == "") err = msg "（" NR " 行目）" }
    function trim(s) { sub(/^ +/, "", s); sub(/ +$/, "", s); return s }
    function scalar(v,   c, rest, q, out, after, p) {
      v = trim(v)
      c = substr(v, 1, 1)
      if (c == "\"" || c == "\047") {
        rest = substr(v, 2)
        q = index(rest, c)
        if (q == 0) { fail("引用符が閉じていません"); return "" }
        out = substr(rest, 1, q - 1)
        if (index(out, "\\") > 0) { fail("引用符内の escape は扱いません"); return "" }
        after = trim(substr(rest, q + 1))
        if (after != "" && substr(after, 1, 1) != "#") { fail("引用符の後に余分な文字があります"); return "" }
        return out
      }
      if (c == "{" || c == "[") { fail("flow style（" c "）の YAML は解釈しません。block style で書いてください"); return "" }
      if (index("&*!|>%@`?,]}", c) > 0) { fail("YAML の anchor・alias・tag・block scalar・予約文字（" c "）は扱いません"); return "" }
      p = index(v, " #")
      if (p > 0) v = trim(substr(v, 1, p - 1))
      if (v ~ /: / || v ~ /:$/) { fail("値に \": \" を含む行（mapping の可能性）は扱いません"); return "" }
      return v
    }
    function resolve(p, k,   n) {
      for (n = 0; p != "" && n < 8; n++) {
        if ((p "/" k) in seen) return p "/" k
        p = (p in merge) ? merge[p] : ""
      }
      return ""
    }
    BEGIN { depth = 0; bind[0] = 0; bpath[0] = ""; bkind[0] = "map"; pending = 0; err = "" }
    {
      line = $0
      if (err != "") next
      if (index(line, "\r") > 0) { fail("CR を含む行は扱いません"); next }
      if (line ~ /^ *(#.*)?$/) next
      if (line ~ /^ *\t/) { fail("indent に tab があります"); next }
      match(line, /^ */); ind = RLENGTH; rest = substr(line, ind + 1)
      if (ind == 0 && (rest ~ /^---/ || rest ~ /^\.\.\./ || rest ~ /^%/)) { fail("document marker・directive は扱いません"); next }
      if (pending) {
        pending = 0
        if (ind > pend_ind) { depth++; bind[depth] = ind; bpath[depth] = pend_path; bkind[depth] = "" }
      }
      while (depth > 0 && ind < bind[depth]) depth--
      if (ind != bind[depth]) { fail("indent が揃っていません（複数行の値は扱いません）"); next }
      if (rest == "-" || substr(rest, 1, 2) == "- ") {
        if (bkind[depth] == "map") { fail("mapping と sequence が混在しています"); next }
        bkind[depth] = "seq"
        item = trim(substr(rest, 2))
        if (item == "" || substr(item, 1, 2) == "- " || item == "-") { fail("空の要素・入れ子の sequence は扱いません"); next }
        if (item ~ /^[A-Za-z0-9_.<-]+:( |$)/) { fail("sequence の中の mapping は扱いません"); next }
        scalar(item)
        next
      }
      if (bkind[depth] == "seq") { fail("sequence と mapping が混在しています"); next }
      bkind[depth] = "map"
      if (!match(rest, /^(<<|[A-Za-z0-9_.-]+):/)) { fail("解釈できない key です（引用符付き key・complex key・flow style を含む）"); next }
      key = substr(rest, 1, RLENGTH - 1)
      after = substr(rest, RLENGTH + 1)
      if (after != "" && substr(after, 1, 1) != " ") { fail("key の \":\" の後に空白がありません: " key); next }
      v = trim(after)
      path = bpath[depth] "/" key
      if (path in seen) { fail("key が重複しています: " path); next }
      seen[path] = 1
      if (depth == 0) top[key] = 1
      if (depth == 1 && bpath[1] == "/services") svc[key] = 1
      if (key == "<<") {
        if (!match(v, /^\*[A-Za-z0-9_-]+( +#.*)?$/)) { fail("<< の値は *anchor だけを扱います"); next }
        a = v; sub(/^\*/, "", a); sub(/ +#.*$/, "", a)
        if (!(a in anchor)) { fail("未定義の anchor です: " a); next }
        owner = bpath[depth]
        if (owner !~ /^\/x-[^\/]+$/ && owner !~ /^\/services\/[^\/]+$/) { fail("<< を置けるのは x- mapping と service の直下だけです"); next }
        merge[owner] = anchor[a]
        next
      }
      if (substr(v, 1, 1) == "&") {
        if (!match(v, /^&[A-Za-z0-9_-]+( +#.*)?$/)) { fail("anchor の後にスカラー値を置く書き方は扱いません"); next }
        if (depth != 0 || key !~ /^x-/) { fail("anchor はトップレベルの x- key にだけ置けます"); next }
        a = v; sub(/^&/, "", a); sub(/ +#.*$/, "", a)
        if (a in anchor) { fail("anchor が重複しています: " a); next }
        anchor[a] = path
        v = ""
      }
      if (v == "" || substr(v, 1, 1) == "#") {
        pending = 1; pend_ind = ind; pend_path = path
        block[path] = 1
        next
      }
      val[path] = scalar(v)
    }
    END {
      if (err != "") { print "error\t" err; exit }
      if ("include" in top) { print "error\tトップレベルの include（別の compose ファイルの読み込み）は扱いません"; exit }
      if (!("services" in top)) { print "error\tservices がありません"; exit }
      p = "/" pin_key
      print "pin\t" (((p in seen) && !(p in block)) ? val[p] : "")
      for (k in top) print "top\t" k
      for (s in svc) {
        sp = "/services/" s
        # 値が無い・空の項目は <none>（tab 区切りの読み取りで空欄が詰まらないようにする）
        if (sp in block) {
          i = resolve(sp, "image"); pp = resolve(sp, "pull_policy")
          iv = (i == "" ? "" : ((i in block) ? "<block>" : val[i]))
          pv = (pp == "" ? "" : ((pp in block) ? "<block>" : val[pp]))
          printf "service\t%s\t%s\t%s\t%d\t%d\n", s, (iv == "" ? "<none>" : iv), (pv == "" ? "<none>" : pv),
            (resolve(sp, "build") != ""), (resolve(sp, "extends") != "")
        } else {
          printf "service\t%s\t<none>\t<none>\t0\t0\n", s
        }
      }
    }
  ' "$1"
}

# 照合に合格した image ID（cmd_check が設定し、cmd_compose が実行時に使う）
CHECKED_IMAGE_ID=""

cmd_check() { # <compose>
  local compose=$1 model reason pin tag="" name image pull build extends svcs="" expected content store id
  [[ -f "$compose" ]] || die "compose ファイルがありません: $compose"
  model=$(compose_model "$compose") || die "$compose を解釈できません"
  reason=$(printf '%s\n' "$model" | awk -F'\t' '$1 == "error" { print $2 }')
  [[ -z "$reason" ]] \
    || die "$compose を解釈できません: $reason（tool-image.sh check は block style の YAML だけを扱い、解釈できない書き方は拒否します）"
  pin=$(printf '%s\n' "$model" | awk -F'\t' '$1 == "pin" { print $2 }')
  valid_digest "$pin" \
    || die "未 pin: $compose の $PIN_KEY が content ID（sha256:<16 進 64 桁>）ではありません（現在値: ${pin:-なし}）。tool-image.sh build-record / load で確認した content ID を設定するまで production では使えません"
  if grep -qE '^[[:space:]]+build:' "$compose"; then
    die "$compose に build: があります（registry を使わない運用では、記録・配布した image だけを使う）"
  fi
  # service の集合は tool-image.sh compose が許可する 7 つと一致すること（依存で起動される別 service を許さない）
  svcs=$(printf '%s\n' "$model" | awk -F'\t' '$1 == "service" { print $2 }' | LC_ALL=C sort | tr '\n' ' ')
  expected=$(printf '%s\n' "${COMPOSE_SERVICES[@]}" | LC_ALL=C sort | tr '\n' ' ')
  [[ "$svcs" == "$expected" ]] \
    || die "$compose の services がツールの service（${expected% }）と一致しません（現在: ${svcs% }）"
  # service ごとに、merge を解決した実効値を検査する（1 つでも満たさなければ拒否）
  while IFS=$'\t' read -r _ name image pull build extends; do
    [[ "$build" == 0 ]] || die "$compose の service $name に build: があります（registry を使わない運用では、記録・配布した image だけを使う）"
    [[ "$extends" == 0 ]] || die "$compose の service $name に extends: があります（別の定義を取り込む書き方は扱いません）"
    [[ "$pull" == never ]] \
      || die "$compose の service $name に pull_policy: never がありません（現在: ${pull:-なし}。同名 image を registry から取得させない）"
    valid_tag "$image" || die "$compose の service $name の image: がツールイメージの tag ではありません: ${image:-なし}"
    if [[ -z "$tag" ]]; then
      tag=$image
    elif [[ "$image" != "$tag" ]]; then
      die "$compose の image: がツールイメージ 1 種類ではありません（$tag と $image）"
    fi
  done < <(printf '%s\n' "$model" | grep $'^service\t')
  # tag は 1 回だけ image ID へ解決し、以後の照合と実行はすべてこの image ID で行う（tag の付け替えの影響を受けない）
  id=$(image_id_of "$tag") \
    || die "ローカルに $tag が無いか、image ID を取得できません（tool-image.sh load で配布 tar を読み込んでください）"
  content=$(image_content_id "$id") \
    || die "ローカルの $tag（$id）の content ID を計算できません（tool-image.sh load で配布 tar を読み込んでください）"
  [[ "$content" == "$pin" ]] \
    || die "ローカルの $tag の content ID（$content）は pin（$pin）と一致しません。tool-image.sh load で配布 tar を読み込み直してください"
  store=$(image_store_type) || store=unknown
  CHECKED_IMAGE_ID=$id
  echo "tool-image: OK: $tag content_id=$pin（image_store=$store image_id=$id。実行はこの image ID に固定します）"
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

COMPOSE_OVERRIDE_DIR=""
cleanup_compose_override() {
  if [[ -n "$COMPOSE_OVERRIDE_DIR" ]]; then
    rm -f -- "$COMPOSE_OVERRIDE_DIR/image-id.yml"
    rmdir -- "$COMPOSE_OVERRIDE_DIR" 2> /dev/null || true
  fi
}

cmd_compose() {
  local rc=0 override s
  if ! allowed_compose_args "$@"; then
    usage_error "許可されていない docker compose の使い方です: $*（compose 側の option・別の compose ファイル・未定義の service は渡せません）"
  fi
  if (( ! COMPOSE_NEEDS_CHECK )); then
    # 状態確認・停止（ps / logs / stop）は image を起動しないため、pin 照合も image ID の固定もしない
    exec "$DOCKER_BIN" compose -f "$(host_path "$PROD_COMPOSE")" "$@"
  fi
  cmd_check "$PROD_COMPOSE" >&2
  valid_digest "$CHECKED_IMAGE_ID" || die "照合した image ID を取得できません"
  # 照合した image ID を全 service の image に固定する override を、本プロセス専用の一時ディレクトリ（0700）に作る。
  # compose は tag ではなくこの image ID（内容から決まり付け替えられない）で起動するため、照合の後に tag を
  # 別の image へ付け替えられても、照合していない image は起動されない（RB-W W-3）。
  COMPOSE_OVERRIDE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ses-backup-tool-compose.XXXXXX") \
    || die "一時ディレクトリを作成できません"
  trap cleanup_compose_override EXIT
  override="$COMPOSE_OVERRIDE_DIR/image-id.yml"
  {
    printf '# tool-image.sh check で照合した image ID に固定する（tool-image.sh compose が生成し、実行後に削除する）\n'
    printf 'services:\n'
    for s in "${COMPOSE_SERVICES[@]}"; do
      printf '  %s:\n    image: "%s"\n' "$s" "$CHECKED_IMAGE_ID"
    done
  } > "$override" || die "override を作成できません: $override"
  "$DOCKER_BIN" compose -f "$(host_path "$PROD_COMPOSE")" -f "$(host_path "$override")" "$@" || rc=$?
  return "$rc"
}

main() {
  local cmd=${1:-} tag=$DEFAULT_TAG out="" tar="" sha="" content_id="" compose=$PROD_COMPOSE
  [[ -n "$cmd" ]] || usage_error "サブコマンドを指定してください"
  shift
  if [[ "$cmd" == compose ]]; then
    cmd_compose "$@"
    exit $?
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
