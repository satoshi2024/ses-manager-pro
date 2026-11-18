#!/usr/bin/env bash
# ============================================================
# HFP-03 バックアップツールイメージの導入スクリプト（Dockerfile の RUN からだけ実行する）
#
# 方針（W1-B1 / X-08(b)。registry を使わず「ローカル build + SHA-256 検証 + image ID 記録 +
# docker save/load 配布」で運用するための再現性・供給網の担保）:
#   - 固定値（SHA-256・fingerprint・version）が占位値や不正な形式なら、何もダウンロードせずに失敗する。
#   - Oracle MySQL APT repo の署名鍵は SHA-256 と full fingerprint の両方で検証し、その鍵だけを
#     keyring にする。mysql-apt-config（旧版 deb の SHA-256 を一次資料で確認できない）は使わず、
#     apt source を直接定義する。MySQL client / server-core は exact version で固定する。
#   - restic は GitHub release の公式 SHA256SUMS に記載された値で検証する。
#   - tar / util-linux などの Essential パッケージは明示的には導入・再インストールしない。ただし MySQL client 等の
#     依存解決で base image の版から更新されることがあり、「base image の版を保つ」とは限らない（RB-W W-4）。
#     実際の版は導入後の dpkg-packages.tsv が正本で、Essential パッケージの base image からの変化は
#     essential-packages.tsv に記録する（変化があれば build ログに WARN）。Essential を含む全パッケージの版を
#     固定するのは apt-packages.lock（記載があれば完全一致しない build を失敗させる）。
#   - 導入後の全 dpkg パッケージの版一覧と固定値を image 内 MANIFEST_DIR に記録する。
#   - --verify-manifest: image の実際の dpkg の版が記録した dpkg-packages.tsv（と toolchain.env の SHA-256）と
#     一致することを確かめ、一致しなければ失敗する。Dockerfile の最後の RUN で実行する（記録の後に版が
#     変わった image を build させない）。tool-image.sh record も build 後の image で同じ照合を行う。
#
# 入力（Dockerfile の ARG。build 中は環境変数として見える）:
#   BASE_IMAGE, MYSQL_CLIENT_VERSION, MYSQL_DEB_REVISION, MYSQL_APT_SUITE, MYSQL_APT_COMPONENT,
#   MYSQL_REPO_KEY_URL, MYSQL_REPO_KEY_SHA256, MYSQL_REPO_KEY_FINGERPRINT,
#   RESTIC_VERSION, RESTIC_LINUX_AMD64_SHA256
# ============================================================
set -Eeuo pipefail
umask 022

BUILD_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LOCK_FILE=${APT_PACKAGES_LOCK_FILE:-$BUILD_DIR/apt-packages.lock}
MANIFEST_DIR=${IMAGE_MANIFEST_DIR:-/usr/local/share/ses-backup/image-manifest}
MYSQL_KEYRING=/usr/share/keyrings/mysql-release-engineering.gpg
TMP_DIR=""

die() { echo "install-toolchain: ERROR: $*" >&2; exit 1; }

cleanup() {
  if [[ -n "$TMP_DIR" ]]; then rm -rf "$TMP_DIR"; fi
}
trap cleanup EXIT

require_sha256() { # <name> <value>
  [[ "$2" =~ ^[0-9a-f]{64}$ ]] \
    || die "$1 が SHA-256（小文字 16 進 64 桁）で固定されていません（現在値: ${2:-未設定}）。一次資料で確認した値を設定するまで build しません"
}

require_fingerprint() { # <name> <value>
  [[ "$2" =~ ^[0-9A-F]{40}$ ]] \
    || die "$1 が OpenPGP fingerprint（大文字 16 進 40 桁）で固定されていません（現在値: ${2:-未設定}）"
}

verify_sha256() { # <file> <expected> <label>
  local actual
  actual=$(sha256sum "$1" | cut -d' ' -f1)
  [[ "$actual" == "$2" ]] || die "$3 の SHA-256 が一致しません（expected=$2 actual=$actual）"
}

# 導入済み（ii）の全パッケージを「パッケージ名<TAB>版<TAB>アーキテクチャ」で、LC_ALL=C の順に出力する
dpkg_installed() {
  dpkg-query -W -f='${db:Status-Abbrev}\t${Package}\t${Version}\t${Architecture}\n' \
    | awk -F'\t' '$1 ~ /^ii/ {print $2 "\t" $3 "\t" $4}' \
    | LC_ALL=C sort
}

# 導入済み（ii）のうち Essential: yes のパッケージ名
dpkg_essential_names() {
  dpkg-query -W -f='${db:Status-Abbrev}\t${Package}\t${Essential}\n' \
    | awk -F'\t' '$1 ~ /^ii/ && $3 == "yes" {print $2}' \
    | LC_ALL=C sort
}

# image の実際の dpkg の版が、記録した dpkg-packages.tsv と toolchain.env の SHA-256 に一致することを確かめる（W-4）
verify_manifest() {
  local recorded actual
  [[ -s "$MANIFEST_DIR/dpkg-packages.tsv" && -s "$MANIFEST_DIR/toolchain.env" ]] \
    || die "版一覧がありません（$MANIFEST_DIR の dpkg-packages.tsv / toolchain.env）"
  TMP_DIR=$(mktemp -d)
  dpkg_installed > "$TMP_DIR/dpkg-actual.tsv" || die "dpkg パッケージ一覧を取得できません"
  [[ -s "$TMP_DIR/dpkg-actual.tsv" ]] || die "dpkg パッケージ一覧が空です"
  if ! cmp -s "$TMP_DIR/dpkg-actual.tsv" "$MANIFEST_DIR/dpkg-packages.tsv"; then
    echo "install-toolchain: dpkg-packages.tsv と image の実際の版の差分（< 記録 / > 実際）:" >&2
    diff "$MANIFEST_DIR/dpkg-packages.tsv" "$TMP_DIR/dpkg-actual.tsv" >&2 || true
    die "image の実際の dpkg の版が dpkg-packages.tsv と一致しません（記録の後にパッケージが変わりました）"
  fi
  recorded=$(sed -n 's/^dpkg_packages_sha256=//p' "$MANIFEST_DIR/toolchain.env")
  actual=$(sha256sum "$MANIFEST_DIR/dpkg-packages.tsv" | cut -d' ' -f1)
  [[ "$recorded" == "$actual" ]] \
    || die "toolchain.env の dpkg_packages_sha256（${recorded:-なし}）が dpkg-packages.tsv（$actual）と一致しません"
  echo "install-toolchain: image の dpkg の版は dpkg-packages.tsv と一致します（$(wc -l < "$MANIFEST_DIR/dpkg-packages.tsv" | tr -d ' ') 件）"
}

if [[ "${1:-}" == --verify-manifest ]]; then
  verify_manifest
  exit 0
fi
[[ $# -eq 0 ]] || die "未知の引数です: $1（--verify-manifest だけを受け付けます）"

# ------------------------------------------------------------
# 0. 固定値の検査（ダウンロード・apt より前。占位値のままなら失敗する）
# ------------------------------------------------------------
[[ "${BASE_IMAGE:-}" == *@sha256:* ]] || die "BASE_IMAGE が digest で固定されていません（現在値: ${BASE_IMAGE:-未設定}）"
[[ "${MYSQL_CLIENT_VERSION:-}" =~ ^8\.0\.[0-9]{1,3}$ ]] || die "MYSQL_CLIENT_VERSION が不正です（現在値: ${MYSQL_CLIENT_VERSION:-未設定}）"
[[ "${MYSQL_DEB_REVISION:-}" =~ ^[0-9]+debian[0-9]+$ ]] || die "MYSQL_DEB_REVISION が不正です（現在値: ${MYSQL_DEB_REVISION:-未設定}）"
[[ "${MYSQL_APT_SUITE:-}" =~ ^[a-z]+$ ]] || die "MYSQL_APT_SUITE が不正です"
[[ "${MYSQL_APT_COMPONENT:-}" =~ ^mysql-[0-9.]+$ ]] || die "MYSQL_APT_COMPONENT が不正です"
[[ "${MYSQL_REPO_KEY_URL:-}" =~ ^https://repo\.mysql\.com/[A-Za-z0-9._-]+$ ]] || die "MYSQL_REPO_KEY_URL が不正です（https://repo.mysql.com/ 直下に限る）"
require_sha256 MYSQL_REPO_KEY_SHA256 "${MYSQL_REPO_KEY_SHA256:-}"
require_fingerprint MYSQL_REPO_KEY_FINGERPRINT "${MYSQL_REPO_KEY_FINGERPRINT:-}"
[[ "${RESTIC_VERSION:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "RESTIC_VERSION が不正です（現在値: ${RESTIC_VERSION:-未設定}）"
require_sha256 RESTIC_LINUX_AMD64_SHA256 "${RESTIC_LINUX_AMD64_SHA256:-}"
arch=$(dpkg --print-architecture)
[[ "$arch" == amd64 ]] || die "amd64 以外（$arch）の build には対応していません（restic の SHA-256 は linux_amd64 だけを固定）"
[[ -f "$LOCK_FILE" ]] || die "apt-packages.lock がありません: $LOCK_FILE"

TMP_DIR=$(mktemp -d)
export DEBIAN_FRONTEND=noninteractive

# base image の版（Essential パッケージの変化を記録するため、apt を使う前に取る）
dpkg_installed > "$TMP_DIR/base-dpkg.tsv" || die "base image の dpkg パッケージ一覧を取得できません"
dpkg_essential_names > "$TMP_DIR/essential-base.txt" || die "base image の Essential パッケージ一覧を取得できません"

# ------------------------------------------------------------
# 1. Debian パッケージ（Essential の tar / util-linux 等は指定しない。ただし依存解決で base image の版から
#    更新され得る。変化は 4. で essential-packages.tsv に記録する）
#    procps: 運用時の ps 等、bzip2: restic の展開、gnupg: 署名鍵の検証（build 時）
# ------------------------------------------------------------
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg jq bzip2 procps

# ------------------------------------------------------------
# 2. Oracle MySQL APT repo 署名鍵（SHA-256 + full fingerprint）→ apt source → exact version
# ------------------------------------------------------------
curl -fsSL --proto '=https' --tlsv1.2 -o "$TMP_DIR/mysql-repo-key.asc" "$MYSQL_REPO_KEY_URL"
verify_sha256 "$TMP_DIR/mysql-repo-key.asc" "$MYSQL_REPO_KEY_SHA256" "MySQL APT 署名鍵（$MYSQL_REPO_KEY_URL）"
export GNUPGHOME="$TMP_DIR/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --import "$TMP_DIR/mysql-repo-key.asc" 2> "$TMP_DIR/gpg-import.log" \
  || die "MySQL APT 署名鍵を読み込めません: $(cat "$TMP_DIR/gpg-import.log")"
gpg --batch --with-colons --fingerprint > "$TMP_DIR/fingerprint.txt" 2>/dev/null \
  || die "MySQL APT 署名鍵の一覧を取得できません"
# 鍵ファイルに含まれる primary key（pub）は 1 つだけであること
pub_count=$(grep -c '^pub:' "$TMP_DIR/fingerprint.txt" || true)
[[ "$pub_count" == 1 ]] || die "MySQL APT 署名鍵ファイルの primary key（pub）が 1 つではありません（${pub_count:-0} 個）"
# fingerprint は primary key のもの（pub の直後の fpr 行）だけを照合する（subkey の fpr 行とは照合しない）
primary_fpr=$(awk -F: '$1 == "pub" { want = 1; next } want && $1 == "fpr" { print $10; exit }' "$TMP_DIR/fingerprint.txt")
[[ "$primary_fpr" == "$MYSQL_REPO_KEY_FINGERPRINT" ]] \
  || die "MySQL APT 署名鍵の primary key の fingerprint が $MYSQL_REPO_KEY_FINGERPRINT と一致しません（actual=${primary_fpr:-なし}）"
# primary key の有効期限（pub の第 7 欄、epoch 秒。空は無期限）。切れていれば repo の署名検証も通らないため明示的に止める
primary_expiry=$(awk -F: '$1 == "pub" { print $7; exit }' "$TMP_DIR/fingerprint.txt")
if [[ -n "$primary_expiry" ]]; then
  [[ "$primary_expiry" =~ ^[0-9]+$ ]] || die "MySQL APT 署名鍵の有効期限を解釈できません（$primary_expiry）"
  (( primary_expiry > $(date +%s) )) \
    || die "MySQL APT 署名鍵の有効期限が切れています（$(date -u -d "@$primary_expiry" +%Y-%m-%d 2>/dev/null || echo "$primary_expiry")）。新しい鍵ファイルの SHA-256 と fingerprint を確認して Dockerfile を更新してください"
fi
# fingerprint を確認した鍵だけを keyring に書き出す（同梱された他の鍵は信頼しない）
gpg --batch --export "$MYSQL_REPO_KEY_FINGERPRINT" > "$MYSQL_KEYRING"
[[ -s "$MYSQL_KEYRING" ]] || die "MySQL APT keyring を作成できません"
chmod 644 "$MYSQL_KEYRING"
printf 'deb [signed-by=%s] https://repo.mysql.com/apt/debian/ %s %s\n' \
  "$MYSQL_KEYRING" "$MYSQL_APT_SUITE" "$MYSQL_APT_COMPONENT" > /etc/apt/sources.list.d/mysql.list
apt-get update
apt-get install -y --no-install-recommends \
  "mysql-community-client=${MYSQL_CLIENT_VERSION}-${MYSQL_DEB_REVISION}" \
  "mysql-community-server-core=${MYSQL_CLIENT_VERSION}-${MYSQL_DEB_REVISION}"

# ------------------------------------------------------------
# 3. restic（公式 SHA256SUMS の値で検証）
# ------------------------------------------------------------
curl -fsSL --proto '=https' --proto-redir '=https' --tlsv1.2 -o "$TMP_DIR/restic.bz2" \
  "https://github.com/restic/restic/releases/download/v${RESTIC_VERSION}/restic_${RESTIC_VERSION}_linux_amd64.bz2"
verify_sha256 "$TMP_DIR/restic.bz2" "$RESTIC_LINUX_AMD64_SHA256" "restic_${RESTIC_VERSION}_linux_amd64.bz2"
bzip2 -dc "$TMP_DIR/restic.bz2" > /usr/local/bin/restic
chmod 755 /usr/local/bin/restic

rm -rf /var/lib/apt/lists/*

mysql --version
mysqlbinlog --version
mysqldump --version
restic version

# ------------------------------------------------------------
# 4. 版一覧と固定値の記録（image 内）。lock があれば完全一致を要求する
# ------------------------------------------------------------
mkdir -p "$MANIFEST_DIR"
dpkg_installed > "$MANIFEST_DIR/dpkg-packages.tsv" || die "dpkg パッケージ一覧を取得できません"
[[ -s "$MANIFEST_DIR/dpkg-packages.tsv" ]] || die "dpkg パッケージ一覧を取得できません"

# Essential パッケージ（base image と導入後のどちらかで Essential: yes）の base image からの変化を記録する。
# 形式: パッケージ名<TAB>base image の版<TAB>導入後の版<TAB>same / changed / added / removed（版が無い側は -）
dpkg_essential_names > "$TMP_DIR/essential-after.txt" || die "Essential パッケージ一覧を取得できません"
LC_ALL=C sort -u "$TMP_DIR/essential-base.txt" "$TMP_DIR/essential-after.txt" > "$TMP_DIR/essential-all.txt"
awk -F'\t' -v OFS='\t' -v base_file="$TMP_DIR/base-dpkg.tsv" -v now_file="$MANIFEST_DIR/dpkg-packages.tsv" '
  FILENAME == base_file { base[$1] = $2; next }
  FILENAME == now_file { now[$1] = $2; next }
  $1 != "" {
    b = ($1 in base) ? base[$1] : "-"
    n = ($1 in now) ? now[$1] : "-"
    s = (b == n) ? "same" : (b == "-" ? "added" : (n == "-" ? "removed" : "changed"))
    print $1, b, n, s
  }' "$TMP_DIR/base-dpkg.tsv" "$MANIFEST_DIR/dpkg-packages.tsv" "$TMP_DIR/essential-all.txt" \
  > "$MANIFEST_DIR/essential-packages.tsv"
essential_changed=$(awk -F'\t' '$4 != "same"' "$MANIFEST_DIR/essential-packages.tsv" | wc -l | tr -d ' ')
if (( essential_changed > 0 )); then
  echo "install-toolchain: WARN: Essential パッケージ ${essential_changed} 件が base image の版から変わりました（依存解決。$MANIFEST_DIR/essential-packages.tsv）:" >&2
  awk -F'\t' '$4 != "same" { print "  " $1 ": " $2 " -> " $3 " (" $4 ")" }' "$MANIFEST_DIR/essential-packages.tsv" >&2
fi

apt_lock_mode=record-only
# CRLF で checkout された lock でも照合できるよう、行末の CR を除いてから読む
tr -d '\r' < "$LOCK_FILE" > "$TMP_DIR/apt-lock.raw"
if grep -qvE '^[[:space:]]*(#|$)' "$TMP_DIR/apt-lock.raw"; then
  grep -vE '^[[:space:]]*(#|$)' "$TMP_DIR/apt-lock.raw" | LC_ALL=C sort > "$TMP_DIR/apt-lock.tsv"
  if ! cmp -s "$TMP_DIR/apt-lock.tsv" "$MANIFEST_DIR/dpkg-packages.tsv"; then
    echo "install-toolchain: apt-packages.lock と導入結果の差分（< lock / > 導入結果）:" >&2
    diff "$TMP_DIR/apt-lock.tsv" "$MANIFEST_DIR/dpkg-packages.tsv" >&2 || true
    die "Debian パッケージの版が apt-packages.lock と一致しません（上流の更新で版が変わった可能性があります。差分を確認し、承認後に lock を更新してください）"
  fi
  apt_lock_mode=enforced
else
  echo "install-toolchain: WARN: apt-packages.lock が空のため、Debian パッケージ版は記録のみで固定していません（$MANIFEST_DIR/dpkg-packages.tsv を確認のうえ lock へ反映してください）" >&2
fi

{
  printf 'base_image=%s\n' "$BASE_IMAGE"
  printf 'mysql_packages=%s-%s\n' "$MYSQL_CLIENT_VERSION" "$MYSQL_DEB_REVISION"
  printf 'mysql_apt_source=%s\n' "$(cat /etc/apt/sources.list.d/mysql.list)"
  printf 'mysql_repo_key_url=%s\n' "$MYSQL_REPO_KEY_URL"
  printf 'mysql_repo_key_sha256=%s\n' "$MYSQL_REPO_KEY_SHA256"
  printf 'mysql_repo_key_fingerprint=%s\n' "$MYSQL_REPO_KEY_FINGERPRINT"
  printf 'restic_version=%s\n' "$RESTIC_VERSION"
  printf 'restic_linux_amd64_bz2_sha256=%s\n' "$RESTIC_LINUX_AMD64_SHA256"
  printf 'restic_binary_sha256=%s\n' "$(sha256sum /usr/local/bin/restic | cut -d' ' -f1)"
  printf 'apt_lock=%s\n' "$apt_lock_mode"
  printf 'dpkg_packages_sha256=%s\n' "$(sha256sum "$MANIFEST_DIR/dpkg-packages.tsv" | cut -d' ' -f1)"
  printf 'essential_changed=%s\n' "$essential_changed"
  printf 'essential_packages_sha256=%s\n' "$(sha256sum "$MANIFEST_DIR/essential-packages.tsv" | cut -d' ' -f1)"
} > "$MANIFEST_DIR/toolchain.env"
chmod 644 "$MANIFEST_DIR/toolchain.env" "$MANIFEST_DIR/dpkg-packages.tsv" "$MANIFEST_DIR/essential-packages.tsv"
echo "install-toolchain: 版一覧と固定値を記録しました: $MANIFEST_DIR"
