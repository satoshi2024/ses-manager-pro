#!/usr/bin/env bash
# HFP-03-011 integration suite ランナー（ホスト側）
#
# docker compose（pinned MySQL 8.0.36 ×2 + tool イメージ）で隔離ネットワークを
# 構築し、integration-pitr.sh を tool コンテナ内で実行して実 PITR を検証する。
#
# - host port を公開しない（internal network）
# - パスワードは合成値（INTEGRATION_SRC_PW / INTEGRATION_TGT_PW）を自動生成
# - 全ステップ失敗時は非 0 で終了（CI では allow-failure にしない契約）
# - Docker が無い環境では BLOCKED として終了する
#
# usage: bash ops/backup/tests/run-integration.sh
set -uo pipefail
export MSYS_NO_PATHCONV=1

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
COMPOSE_FILE=$(cygpath -w "$HERE/docker-compose.integration.yml" 2>/dev/null || echo "$HERE/docker-compose.integration.yml")

if ! docker info > /dev/null 2>&1; then
  echo "BLOCKED: Docker が利用できません。integration suite は実 MySQL コンテナで実行します。" >&2
  exit 2
fi
if ! docker compose version > /dev/null 2>&1; then
  echo "BLOCKED: docker compose plugin が利用できません。" >&2
  exit 2
fi

INTEGRATION_SRC_PW=${INTEGRATION_SRC_PW:-"int-src-$(date +%s)-pw"}
INTEGRATION_TGT_PW=${INTEGRATION_TGT_PW:-"int-tgt-$(date +%s)-pw"}
WORK="${INTEGRATION_WORK_DIR:-$HERE/.integration-work}"
rm -rf "$WORK"
mkdir -p "$WORK/evid" "$WORK/uploads" "$WORK/work" "$WORK/repo" "$WORK/staging" \
  "$WORK/locks" "$WORK/capath" "$WORK/capath-tgt" "$WORK/heartbeats" "$WORK/scheduler"
export INTEGRATION_SRC_PW INTEGRATION_TGT_PW
WWORK=$(cygpath -w "$WORK" 2>/dev/null || echo "$WORK")
export INTEGRATION_WORK_DIR="$WWORK"
printf '%s\n' "$INTEGRATION_SRC_PW" > "$WORK/src-password"
printf '%s\n' "$INTEGRATION_TGT_PW" > "$WORK/tgt-password"
printf '%s\n' "$INTEGRATION_SRC_PW" > "$WORK/repo-password"

cleanup() {
  docker compose -f "$COMPOSE_FILE" down -v > /dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== compose up（pinned MySQL 8.0.36 ×2 + tool image build） =="
docker compose -f "$COMPOSE_FILE" up -d --build
UP_RC=$?
if [[ "$UP_RC" -ne 0 ]]; then
  echo "FAIL: compose up" >&2
  exit 1
fi
echo "== compose up --wait（healthcheck） =="
docker compose -f "$COMPOSE_FILE" up --wait -d
WAIT_RC=$?
if [[ "$WAIT_RC" -ne 0 ]]; then
  echo "FAIL: コンテナが healthy になりませんでした" >&2
  docker compose -f "$COMPOSE_FILE" ps >&2
  exit 1
fi

NET=$(docker compose -f "$COMPOSE_FILE" ps -q source | head -1 | xargs -r docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null)
NET=${NET:-ses-backup-integration_ses-bkup}
echo "network: $NET"

# TLS capath を抽出（source/target の CA 証明書）
docker cp "ses-bkup-int-source:/var/lib/mysql/ca.pem" "$WWORK/ca.pem" 2>/dev/null
docker cp "ses-bkup-int-target:/var/lib/mysql/ca.pem" "$WWORK/ca-tgt.pem" 2>/dev/null
CA_HASH=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  openssl x509 -in /work/ca.pem -noout -subject_hash 2>/dev/null)
CA_TGT_HASH=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  openssl x509 -in /work/ca-tgt.pem -noout -subject_hash 2>/dev/null)
[[ -n "$CA_HASH" ]] && cp "$WWORK/ca.pem" "$WORK/capath/$CA_HASH.0"
[[ -n "$CA_TGT_HASH" ]] && cp "$WWORK/ca-tgt.pem" "$WORK/capath-tgt/$CA_TGT_HASH.0"

echo "== integration-pitr.sh 実行（tool コンテナ内・実 MySQL PITR） =="
docker run --rm --network "$NET" \
  -e SRC_HOST=ses-bkup-int-source -e TGT_HOST=ses-bkup-int-target \
  -e SRC_PASSWORD_FILE=/work/src-password -e TGT_PASSWORD_FILE=/work/tgt-password \
  -e TARGET_HOST=ses-bkup-int-target -e TARGET_PORT=3306 -e TARGET_USER=restore-svc \
  -e TARGET_PASSWORD_FILE=/work/restore-svc-pw -e TARGET_DATABASE=ses_manager_db \
  -e TARGET_ALLOWLIST_FILE=/work/target-allowlist.txt \
  -e APPROVAL_PUBKEY_DIR=/work/pubkeys \
  -e SOURCE_HOST=ses-bkup-int-source -e SOURCE_PORT=3306 \
  -e WORK_DIR=/work -e MYSQL_DATABASE=ses_manager_db \
  -e MYSQL_HOST=ses-bkup-int-source -e MYSQL_PORT=3306 -e MYSQL_USER=root \
  -e MYSQL_PASSWORD_FILE=/work/src-password \
  -e MYSQL_SSL_CAPATH=/work/capath -e MYSQL_TLS_MODE=VERIFY_CA \
  -e TARGET_SSL_CAPATH=/work/capath-tgt -e TARGET_TLS_MODE=VERIFY_CA \
  -e BACKUP_REPOSITORY=/work/repo -e RESTIC_PASSWORD_FILE=/work/repo-password \
  -e RESTIC_REPOSITORY=/work/repo \
  -e FULL_COORDINATE_FILE=/work/full-coordinate \
  -e BACKUP_WORK_DIR=/work/work -e PLANS_DIR=/work/plans -e UPLOADS_DIR=/work/uploads \
  -e UPLOADS_STAGING_PARENT=/work/staging \
  -e REPLICA_HEARTBEAT_DIR=/work/heartbeats -e SCHEDULER_ACK_DIR=/work/scheduler \
  -e QUIESCE_DEADLINE_SECONDS=60 -e QUIESCE_STALE_SECONDS=60 \
  -e REPOSITORY_LOCK_DIR=/work/locks \
  -e APP_COMMIT=integration -e FLYWAY_VERSION=42 -e CRITICAL_TABLES=marker_test \
  -v "$WWORK:/work:rw" \
  ses-backup-tool:integration /usr/local/bin/integration-pitr.sh
PITR_RC=$?
if [[ "$PITR_RC" -ne 0 ]]; then
  echo "FAIL: integration-pitr.sh（rc=$PITR_RC）" >&2
  exit 1
fi

echo "== restore-drill 実行（実環境に対する drill。RPO/RTO segment 記録） =="
PLAN_ID=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq -r '.plan_id' /work/integration-summary.json)
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/drill-cutover-smoke.sh"
chmod +x "$WORK/drill-cutover-smoke.sh"
# drill の restore 用に target を初期化（target guard の空チェックを通す）
docker exec ses-bkup-int-target mysql -uroot -p"$INTEGRATION_TGT_PW" \
  -e "DROP DATABASE IF EXISTS ses_manager_db; CREATE DATABASE ses_manager_db;" > /dev/null 2>&1
docker run --rm --network "$NET" \
  -e TARGET_HOST=ses-bkup-int-target -e TARGET_PORT=3306 -e TARGET_USER=restore-svc \
  -e TARGET_PASSWORD_FILE=/work/restore-svc-pw -e TARGET_DATABASE=ses_manager_db \
  -e TARGET_ALLOWLIST_FILE=/work/target-allowlist.txt \
  -e APPROVAL_PUBKEY_DIR=/work/pubkeys \
  -e INDEX_DIR=/work/work/index -e BINLOG_INDEX=/work/immutable/binlog-index.json \
  -e PLANS_DIR=/work/plans -e BACKUP_WORK_DIR=/work/work \
  -e SOURCE_HOST=ses-bkup-int-source -e SOURCE_PORT=3306 \
  -e RESTIC_PASSWORD_FILE=/work/repo-password -e RESTIC_REPOSITORY=/work/repo \
  -e BACKUP_REPOSITORY=/work/repo \
  -e TARGET_SSL_CAPATH=/work/capath-tgt -e TARGET_TLS_MODE=VERIFY_CA \
  -e APP_SMOKE_SCRIPT=/work/drill-cutover-smoke.sh \
  -e DRILL_SMOKE_SCRIPT=/work/smoke.sh \
  -e RTO_SECONDS=14400 -e RPO_MAX_SECONDS=900 \
  -v "$WWORK:/work:rw" \
  ses-backup-tool:integration /usr/local/bin/restore-drill.sh \
  --plan "$PLAN_ID" \
  --approval /work/claim-alice.json --approval /work/claim-bob.json \
  --report-dir /work/drill-evidence
DRILL_RC=$?
if [[ "$DRILL_RC" -ne 0 ]]; then
  echo "FAIL: restore-drill（rc=$DRILL_RC）" >&2
  exit 1
fi
docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq '{state, rpo_seconds, rto_seconds, total_seconds, rto_ok, rpo_ok, segments}' \
  /work/drill-evidence/drill-report.json | tee "$WORK/evidence/drill-report.json"
if ! docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration \
  jq -e '.rto_ok == true and .rpo_ok == true' /work/drill-evidence/drill-report.json > /dev/null; then
  echo "FAIL: drill の RPO/RTO が目標内にない" >&2
  exit 1
fi

echo "== 結果検証 =="
SUMMARY="$WORK/integration-summary.json"
[[ -f "$SUMMARY" ]] || { echo "FAIL: summary がありません" >&2; exit 1; }
docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration jq -r . /work/integration-summary.json \
  | tee "$WORK/evidence/integration-summary.json"
STATE=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration jq -r '.state' /work/integration-summary.json)
if [[ "$STATE" != "SUCCESS" ]]; then
  echo "FAIL: state=$STATE" >&2
  exit 1
fi
for k in target_db.before_marker target_db.mid_dml_replayed target_db.after_marker \
  uploads_staging.before_marker uploads_staging.after_marker; do
  V=$(docker run --rm -v "$WWORK:/work:ro" ses-backup-tool:integration jq -r ".$k" /work/integration-summary.json)
  case "$k" in
    target_db.before_marker|target_db.mid_dml_replayed|uploads_staging.before_marker) [[ "$V" == "1" ]] || { echo "FAIL: $k=$V" >&2; exit 1; } ;;
    *) [[ "$V" == "0" ]] || { echo "FAIL: $k=$V" >&2; exit 1; } ;;
  esac
done

echo "== secret scan（evidence から合成パスワード・署名秘密鍵が漏れていないこと） =="
if grep -rl "$INTEGRATION_SRC_PW\|$INTEGRATION_TGT_PW\|PRIVATE KEY" "$WORK/evidence" 2>/dev/null \
  | grep -vE "src-password|tgt-password"; then
  echo "FAIL: evidence に secret が含まれます" >&2
  exit 1
fi
echo "secret scan: 0 matches"

echo "== evidence SHA =="
(cd "$WORK/evidence" && sha256sum integration-summary.json validate.json restore.log target-markers.txt uploads-markers.txt source-state.txt | tee "$WORK/evidence/evidence-sha.txt")

echo "== integration suite SUCCESS（skip 0・全ステップ実実行） =="
