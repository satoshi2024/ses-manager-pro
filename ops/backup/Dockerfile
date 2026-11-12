# ============================================================
# HFP-03 バックアップツールイメージ（Oracle MySQL 8 client + restic）
# - `# syntax=` 行は置かない（build のたびに docker/dockerfile:1 の floating tag の frontend image を
#   registry から取得させないため。本 Dockerfile は builtin frontend の機能だけを使う）。
# - base image は digest 固定（debian bookworm-slim）。tar / util-linux 等の Essential パッケージは
#   再インストールせず base image の版のまま使う（base digest で固定）。
# - MySQL client は Oracle MySQL APT repo から exact version で固定導入する。
#   Alpine の汎用 mysql-client（MariaDB 系: HFP-03-BL-013）は使用しない。
# - Oracle MySQL APT repo の署名鍵は SHA-256 と full fingerprint で検証し、apt source を直接定義する。
#   mysql-apt-config deb は使わない（同梱鍵は期限切れ（2026-08-14 実測 EXPKEYSIG B7B3B788A8D3785C）で、
#   旧版 0.8.33-1 の SHA-256 は一次資料で確認できないため）。repo の InRelease は同じ fingerprint の
#   現行鍵（RPM-GPG-KEY-mysql-2025、有効期限 2027-10-23）で署名されている。
# - restic は GitHub release から version 固定で導入し、公式 SHA256SUMS の値で検証する。
# - 固定値が占位値（UNPINNED）のまま、または検証が一致しなければ build を失敗させる（fail-closed）。
#   導入処理と固定値の検査は image/install-toolchain.sh、Debian パッケージ版の lock は
#   image/apt-packages.lock。導入結果の版一覧は image 内 /usr/local/share/ses-backup/image-manifest/。
# - registry は使わない。build 後の image ID の記録と docker save / load による配布は
#   image/tool-image.sh で行い、production の compose は記録した image ID と照合してから使う。
# ============================================================
ARG BASE_IMAGE=debian:bookworm-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241
FROM ${BASE_IMAGE}

ARG BASE_IMAGE
ARG MYSQL_CLIENT_VERSION=8.0.46
ARG MYSQL_DEB_REVISION=1debian12
ARG MYSQL_APT_SUITE=bookworm
ARG MYSQL_APT_COMPONENT=mysql-8.0
ARG MYSQL_REPO_KEY_URL=https://repo.mysql.com/RPM-GPG-KEY-mysql-2025
# 要人手確認: RPM-GPG-KEY-mysql-2025 の SHA-256 は未確認のため占位値（このままでは build が失敗する）。
# 取得した鍵ファイルの fingerprint が下記と一致することを確認してから、その SHA-256 を設定する。
ARG MYSQL_REPO_KEY_SHA256=UNPINNED
# MySQL Release Engineering <mysql-build@oss.oracle.com>（MySQL 8.0 Reference Manual「Signature Checking Using GnuPG」）
ARG MYSQL_REPO_KEY_FINGERPRINT=BCA43417C3B485DD128EC6D4B7B3B788A8D3785C
ARG RESTIC_VERSION=0.17.3
# restic v0.17.3 公式 SHA256SUMS の restic_0.17.3_linux_amd64.bz2 の値
ARG RESTIC_LINUX_AMD64_SHA256=5097faeda6aa13167aae6e36efdba636637f8741fed89bbf015678334632d4d3
ARG DEBIAN_FRONTEND=noninteractive

COPY image/install-toolchain.sh image/apt-packages.lock /usr/local/share/ses-backup/image-build/
RUN bash /usr/local/share/ses-backup/image-build/install-toolchain.sh

COPY lib/ /usr/local/lib/ses-backup/
COPY providers/ /usr/local/lib/ses-backup/providers/
COPY *.sh /usr/local/bin/
COPY tests/integration-pitr.sh /usr/local/bin/
RUN chmod 700 /usr/local/bin/*.sh /usr/local/lib/ses-backup/providers/*.sh

WORKDIR /var/lib/ses-backup
