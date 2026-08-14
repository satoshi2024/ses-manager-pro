# syntax=docker/dockerfile:1
# ============================================================
# HFP-03 バックアップツールイメージ（Oracle MySQL 8 client + restic）
# - base image は digest 固定（debian bookworm-slim）
# - MySQL client は Oracle MySQL APT repo から exact version で固定導入する。
#   Alpine の汎用 mysql-client（MariaDB 系: HFP-03-BL-013）は使用しない。
# - restic は GitHub release から version 固定で導入する。
# - 注意（2026-08-14 実測）: mysql-apt-config 0.8.33 同梱の署名鍵は期限切れ
#   （EXPKEYSIG B7B3B788A8D3785C）。repo が InRelease を署名する現行鍵
#   （RPM-GPG-KEY-mysql-2025 に同梱、同一 fingerprint、有効期限 2027-10-23）を
#   keyring へ import してから apt-get update する。
# ビルド後の最終 image digest は evidence に記録し、compose/CI で参照する。
# ============================================================
ARG MYSQL_CLIENT_VERSION=8.0.46
FROM debian:bookworm-slim@sha256:abd67ffcfa541b485a3dff59865ab629aa048a6c613e639d36e7456b0b229241

ARG MYSQL_CLIENT_VERSION=8.0.46
ARG MYSQL_APT_CONFIG_VERSION=0.8.33-1
ARG RESTIC_VERSION=0.17.3
ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg jq tar bzip2 util-linux procps lsb-release wget \
 && curl -fsSL -o /tmp/mysql-apt-config.deb \
      "https://repo.mysql.com/mysql-apt-config_${MYSQL_APT_CONFIG_VERSION}_all.deb" \
 && echo "mysql-apt-config mysql-apt-config/select-server select mysql-8.0" | debconf-set-selections \
 && dpkg -i /tmp/mysql-apt-config.deb \
 && curl -fsSL -o /tmp/mysql-2025.asc https://repo.mysql.com/RPM-GPG-KEY-mysql-2025 \
 && mkdir -p /tmp/.gnupg && chmod 700 /tmp/.gnupg \
 && gpg --batch --homedir /tmp/.gnupg --no-default-keyring \
      --keyring /usr/share/keyrings/mysql-apt-config.gpg --import /tmp/mysql-2025.asc \
 && rm -rf /tmp/.gnupg \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      "mysql-community-client=${MYSQL_CLIENT_VERSION}-1debian12" \
      "mysql-community-server-core=${MYSQL_CLIENT_VERSION}-1debian12" \
 && curl -fsSL -o /tmp/restic.bz2 \
      "https://github.com/restic/restic/releases/download/v${RESTIC_VERSION}/restic_${RESTIC_VERSION}_linux_amd64.bz2" \
 && bzip2 -dc /tmp/restic.bz2 > /usr/local/bin/restic \
 && chmod 755 /usr/local/bin/restic \
 && rm -f /tmp/mysql-apt-config.deb /tmp/mysql-2025.asc /tmp/restic.bz2 \
 && rm -rf /var/lib/apt/lists/* \
 && mysql --version \
 && mysqlbinlog --version \
 && mysqldump --version \
 && restic version

COPY lib/ /usr/local/lib/ses-backup/
COPY *.sh /usr/local/bin/
RUN chmod 700 /usr/local/bin/*.sh

WORKDIR /var/lib/ses-backup
