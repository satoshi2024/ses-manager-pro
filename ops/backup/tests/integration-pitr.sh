#!/usr/bin/env bash
# HFP-03-011 integration suite 本体（tool コンテナ内で実行）
#
# 実 MySQL（source/target 別コンテナ）に対して:
#   preflight -> backup-full -> archive(checkpoint 前の binlog) ->
#   checkpoint（before marker を DB/uploads に固定）-> after marker 注入 ->
#   plan-restore -> restore -> validate-restore（before 存在 / after 不在 /
#   uploads 照合 / read-only smoke）-> summary JSON 出力
#
# 環境変数: SRC_HOST, TGT_HOST, SRC_PASSWORD_FILE, TGT_PASSWORD_FILE,
# WORK_DIR, REPOSITORY 系（BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE,
# RESTIC_REPOSITORY）, UPLOADS_DIR, BINLOG_RAW_DIR, BINLOG_IMMUTABLE_DIR,
# REPLICA_HEARTBEAT_DIR, SCHEDULER_ACK_DIR, QUIESCE_*, APP_COMMIT,
# FLYWAY_VERSION, CRITICAL_TABLES, PLANS_DIR, INDEX_DIR 等
set -uo pipefail

fail() { echo "[integration] FAIL: $*" >&2; exit 1; }
require_env() {
  local v=$1
  [[ -n "${!v:-}" ]] || fail "$v が未設定です"
}

require_env SRC_HOST
require_env TGT_HOST
require_env SRC_PASSWORD_FILE
require_env TGT_PASSWORD_FILE
require_env WORK_DIR
require_env BACKUP_REPOSITORY
require_env RESTIC_PASSWORD_FILE
require_env UPLOADS_DIR

MYSQL_CLIENT_BIN=${MYSQL_CLIENT_BIN:-mysql}
export RESTIC_REPOSITORY=${RESTIC_REPOSITORY:-$BACKUP_REPOSITORY}
EVID="$WORK_DIR/evidence"
mkdir -p "$EVID"

# 簡易ログ
log() { echo "[integration] $*"; }

src_root() { # mysql args...（source の root で実行）
  MYSQL_PWD="$(cat "$SRC_PASSWORD_FILE")" mysql -h"$SRC_HOST" -uroot "$@"
}
tgt_root() { # mysql args...（target の root で実行）
  MYSQL_PWD="$(cat "$TGT_PASSWORD_FILE")" mysql -h"$TGT_HOST" -uroot "$@"
}

log "== 1. preflight =="
/usr/local/bin/preflight.sh --json > "$EVID/preflight.json" 2>&1 \
  || fail "preflight が失敗しました"
grep -q '"status": "OK"' "$EVID/preflight.json" || fail "preflight が OK でない"
log "preflight OK"

log "== 2. fixture（marker テーブル + before marker） =="
src_root "$MYSQL_DATABASE" \
  -e "CREATE TABLE IF NOT EXISTS marker_test (id BIGINT AUTO_INCREMENT PRIMARY KEY, marker VARCHAR(64)) ENGINE=InnoDB;
      DELETE FROM marker_test;
      INSERT INTO marker_test (marker) VALUES ('marker-before-checkpoint');
      CREATE TABLE IF NOT EXISTS t_report_run (
        id BIGINT PRIMARY KEY, snapshot_version INT NOT NULL, status VARCHAR(32) NOT NULL, scope_hash CHAR(64) NOT NULL
      ) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_report_section_snapshot (
        id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL, section_key VARCHAR(64) NOT NULL,
        snapshot_hash CHAR(64) NOT NULL, value_json LONGTEXT NOT NULL
      ) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_report_section_attempt (
        id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL, section_key VARCHAR(64) NOT NULL,
        attempt_no INT NOT NULL, section_status VARCHAR(32) NOT NULL, snapshot_hash CHAR(64) NOT NULL
      ) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_document (
        id BIGINT PRIMARY KEY, document_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
        retention_until DATE NOT NULL
      ) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_document_version (
        id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL, version_no INT NOT NULL,
        sha256 CHAR(64) NOT NULL, scan_status VARCHAR(32) NOT NULL, storage_key VARCHAR(255) NOT NULL
      ) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_notification_outbox (
        id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL, dedupe_key VARCHAR(128) NOT NULL
      ) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_report_delivery (
        id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL, document_id BIGINT NOT NULL,
        document_version_no INT NOT NULL, notification_outbox_id BIGINT NOT NULL,
        delivery_status VARCHAR(32) NOT NULL
      ) ENGINE=InnoDB;
      DELETE FROM t_report_delivery;
      DELETE FROM t_notification_outbox;
      DELETE FROM t_document_version;
      DELETE FROM t_document;
      DELETE FROM t_report_section_attempt;
      DELETE FROM t_report_section_snapshot;
      DELETE FROM t_report_run;
      INSERT INTO t_report_run (id, snapshot_version, status, scope_hash)
        VALUES (91001, 3, 'SUCCEEDED', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
      INSERT INTO t_report_section_snapshot (id, run_id, section_key, snapshot_hash, value_json)
        VALUES (91002, 91001, 'sales', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', '{\"sales\":123}');
      INSERT INTO t_report_section_attempt (id, run_id, section_key, attempt_no, section_status, snapshot_hash)
        VALUES (91003, 91001, 'sales', 2, 'SUCCEEDED', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb');
      INSERT INTO t_document (id, document_type, status, retention_until)
        VALUES (91004, 'MANAGEMENT_REPORT', 'CONFIRMED', '2033-08-31');
      INSERT INTO t_document_version (id, document_id, version_no, sha256, scan_status, storage_key)
        VALUES (91005, 91004, 1, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 'CLEAN', 'published/report-restore.pdf');
      INSERT INTO t_notification_outbox (id, status, dedupe_key)
        VALUES (91006, 'PENDING', 'report-restore:91001:91004:1');
      INSERT INTO t_report_delivery (id, run_id, document_id, document_version_no, notification_outbox_id, delivery_status)
        VALUES (91007, 91001, 91004, 1, 91006, 'ENQUEUED');" \
  || fail "fixture 作成に失敗しました"
mkdir -p "$UPLOADS_DIR/published"
printf 'marker-before-checkpoint\n' > "$UPLOADS_DIR/published/marker-before.txt"
printf 'report-restore\n' > "$UPLOADS_DIR/published/report-restore.pdf"
export REPORT_RESTORE_EVIDENCE="$EVID/report-restore-contract.txt"

log "== 3. backup-full =="
/usr/local/bin/backup-full.sh > "$EVID/backup-full.log" 2>&1 || fail "backup-full が失敗しました"
log "backup-full OK"

log "== 3.5 中間 DML（full と checkpoint の間の更新。replay 検証用） =="
src_root "$MYSQL_DATABASE" \
  -e "INSERT INTO marker_test (marker) VALUES ('marker-mid');" \
  || fail "中間 DML 注入に失敗しました"

log "== 4. archiver（stop-never）起動 =="
BINLOG_RAW_DIR=${BINLOG_RAW_DIR:-"$WORK_DIR/raw"}
BINLOG_IMMUTABLE_DIR=${BINLOG_IMMUTABLE_DIR:-"$WORK_DIR/immutable"}
mkdir -p "$BINLOG_RAW_DIR" "$BINLOG_IMMUTABLE_DIR"
export BINLOG_RAW_DIR BINLOG_IMMUTABLE_DIR BINLOG_STATE="$WORK_DIR/archive-state.json"
/usr/local/bin/archive-binlog.sh --stop-never > "$EVID/archiver.log" 2>&1 &
ARCHIVER_PID=$!
sleep 5

log "== 5. checkpoint（before marker を固定） =="
/usr/local/bin/create-checkpoint.sh > "$EVID/checkpoint.log" 2>&1 || {
  kill "$ARCHIVER_PID" 2>/dev/null || true
  fail "checkpoint が失敗しました"
}
log "checkpoint OK"
sleep 2

log "== 6. after marker 注入（source のみ） =="
src_root "$MYSQL_DATABASE" \
  -e "INSERT INTO marker_test (marker) VALUES ('marker-after-checkpoint');" \
  || fail "after marker 注入に失敗しました"
printf 'marker-after-checkpoint\n' > "$UPLOADS_DIR/published/marker-after.txt"

kill "$ARCHIVER_PID" 2>/dev/null || true

log "== 7. target provision =="
TGT_UUID=$(tgt_root -N -B -e "SELECT @@server_uuid;") || fail "target UUID を取得できません"
printf '%s\n' "$TGT_UUID" > "$WORK_DIR/target-allowlist.txt"
tgt_root \
  -e "CREATE DATABASE IF NOT EXISTS ses_recovery_control;
      CREATE DATABASE IF NOT EXISTS $MYSQL_DATABASE;
      CREATE TABLE IF NOT EXISTS ses_recovery_control.targets (
        uuid VARCHAR(64) PRIMARY KEY, allowlist_ref VARCHAR(64), plan_id VARCHAR(64), provisioned_at DATETIME
      ) ENGINE=InnoDB;
      CREATE USER IF NOT EXISTS 'restore-svc'@'%' IDENTIFIED BY 'restore-svc-pw';
      GRANT SYSTEM_VARIABLES_ADMIN ON *.* TO 'restore-svc'@'%';
      GRANT REPLICATION_APPLIER ON *.* TO 'restore-svc'@'%';
      GRANT RELOAD ON *.* TO 'restore-svc'@'%';   -- RESET MASTER（GTID リセット）に必要
      GRANT ALL ON ses_recovery_control.* TO 'restore-svc'@'%';
      GRANT CREATE, INSERT, SELECT, UPDATE, DELETE, DROP, INDEX, ALTER, CREATE VIEW, TRIGGER, EXECUTE, EVENT, LOCK TABLES ON $MYSQL_DATABASE.* TO 'restore-svc'@'%';" \
  || fail "target provision に失敗しました"
printf 'restore-svc-pw\n' > "$WORK_DIR/restore-svc-pw"

log "== 8. plan + 承認 claim =="
INDEX_DIR=${INDEX_DIR:-"$BACKUP_WORK_DIR/index"}
PLANS_DIR=${PLANS_DIR:-"$WORK_DIR/plans"}
mkdir -p "$INDEX_DIR" "$PLANS_DIR"
CP_TIME=$(jq -r '.consistency_time_utc' "$INDEX_DIR"/checkpoint-*.json | head -1)
TARGET_TS=$(date -u -d "$CP_TIME + 60 seconds" +%Y-%m-%dT%H:%M:%SZ)
PLAN_OUT=$(INDEX_DIR="$INDEX_DIR" BINLOG_INDEX="$BINLOG_IMMUTABLE_DIR/binlog-index.json" \
  PLANS_DIR="$PLANS_DIR" BACKUP_WORK_DIR="$WORK_DIR" ALLOWLIST_REF=default \
  /usr/local/bin/plan-restore.sh --target "$TARGET_TS" 2>&1) || fail "plan 生成に失敗しました"
PLAN_ID=$(printf '%s\n' "$PLAN_OUT" | grep -oE 'plan_id=[a-f0-9]{16}' | cut -d= -f2)
[[ -n "$PLAN_ID" ]] || fail "plan_id を取得できません"
log "plan_id=$PLAN_ID"

mkdir -p "$WORK_DIR/keys" "$WORK_DIR/pubkeys"
cd "$WORK_DIR/keys" || fail "keys dir に移動できません"
openssl genrsa -out priv1.pem 2048 2>/dev/null
openssl rsa -in priv1.pem -pubout -out pub1.pem 2>/dev/null
openssl genrsa -out priv2.pem 2048 2>/dev/null
openssl rsa -in priv2.pem -pubout -out pub2.pem 2>/dev/null
cp pub1.pem "$WORK_DIR/pubkeys/alice.pem"
cp pub2.pem "$WORK_DIR/pubkeys/bob.pem"
# claim は sidecar canonical SHA（created_at_utc 除外）に bind する。file sha256sum は不可。
PLAN_SHA=$(tr -d ' \t\r\n' < "$PLANS_DIR/$PLAN_ID.json.sha256")
[[ -n "$PLAN_SHA" ]] || fail "plan sidecar SHA を取得できません"
FUTURE=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)
for a in alice bob; do
  if [[ "$a" == alice ]]; then KEY=priv1.pem; else KEY=priv2.pem; fi
  jq -n --arg ps "$PLAN_SHA" --arg tu "$TGT_UUID" --arg a "$a" --arg r manager \
    --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$FUTURE" --arg ct "CHG-011" \
    '{plan_sha256:$ps,target_uuid:$tu,change_ticket:$ct,actor:$a,role:$r,
      issued_at_utc:$i,expires_at_utc:$e}' > "$WORK_DIR/claim-$a.json"
  /usr/local/lib/ses-backup/providers/approval-verifier-local.sh sign "$WORK_DIR/claim-$a.json" "$KEY"
done
# R1 P2: 署名秘密鍵は evidence に残さない（署名後すぐ削除）
rm -f "$WORK_DIR/keys/priv1.pem" "$WORK_DIR/keys/priv2.pem"
tgt_root ses_recovery_control \
  -e "INSERT INTO targets (uuid, allowlist_ref, plan_id, provisioned_at)
      VALUES ('$TGT_UUID', 'default', '$PLAN_ID', NOW())
      ON DUPLICATE KEY UPDATE allowlist_ref='default', plan_id='$PLAN_ID';" \
  || fail "targets 登録に失敗しました"

log "== 9. restore =="
TARGET_SSL_CAPATH=${TARGET_SSL_CAPATH:-"$WORK_DIR/capath-tgt"}
MYSQL_SSL_CAPATH=${MYSQL_SSL_CAPATH:-"$WORK_DIR/capath"}
TARGET_TLS_MODE=${TARGET_TLS_MODE:-VERIFY_CA}
/usr/local/bin/restore.sh \
  --plan "$PLAN_ID" \
  --approval "$WORK_DIR/claim-alice.json" --approval "$WORK_DIR/claim-bob.json" \
  > "$EVID/restore.log" 2>&1 || fail "restore が失敗しました"
UPLOADS_READY=$(grep -oE '"uploads_ready": "[^"]+"' "$EVID/restore.log" | cut -d'"' -f4)
[[ -n "$UPLOADS_READY" ]] || fail "uploads_ready を取得できません"
log "restore OK（uploads_ready=$UPLOADS_READY）"

log "== 10. validate-restore =="
cat > "$WORK_DIR/smoke.sh" <<'SMOKE'
#!/usr/bin/env bash
set -uo pipefail
# shellcheck disable=SC2206
read -r -a ARGS <<< "$TARGET_OPT_ARGS_JSON"
DB="${TARGET_DATABASE:?}"
scalar() { mysql "${ARGS[@]}" -N -B --execute "$1"; }
expect() {
  local name=$1 sql=$2 expected=$3 actual
  actual=$(scalar "$sql") || { echo "report restore smoke: $name query failed" >&2; return 1; }
  [[ "$actual" == "$expected" ]] || {
    echo "report restore smoke: $name expected=$expected actual=$actual" >&2
    return 1
  }
}

expect marker_count "SELECT COUNT(*) FROM marker_test" 2 || exit 1
expect run_snapshot_version "SELECT snapshot_version FROM t_report_run WHERE id=91001" 3 || exit 1
expect run_scope_hash "SELECT scope_hash FROM t_report_run WHERE id=91001" \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa || exit 1
expect section_snapshot_hash "SELECT snapshot_hash FROM t_report_section_snapshot WHERE run_id=91001 AND section_key='sales'" \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb || exit 1
expect section_attempt_count "SELECT COUNT(*) FROM t_report_section_attempt WHERE run_id=91001" 1 || exit 1
expect section_attempt_no "SELECT attempt_no FROM t_report_section_attempt WHERE id=91003" 2 || exit 1
expect document_type "SELECT document_type FROM t_document WHERE id=91004" MANAGEMENT_REPORT || exit 1
expect document_version "SELECT version_no FROM t_document_version WHERE document_id=91004" 1 || exit 1
expect document_hash "SELECT sha256 FROM t_document_version WHERE id=91005" \
  cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc || exit 1
expect outbox_status "SELECT status FROM t_notification_outbox WHERE id=91006" PENDING || exit 1
expect delivery_status "SELECT delivery_status FROM t_report_delivery WHERE id=91007" ENQUEUED || exit 1
expect delivery_outbox "SELECT notification_outbox_id FROM t_report_delivery WHERE id=91007" 91006 || exit 1

evidence="${REPORT_RESTORE_EVIDENCE:?}"
mkdir -p "$(dirname "$evidence")"
{
  echo "restore_contract=PASS"
  echo "run_id=91001 snapshot_version=3 status=SUCCEEDED"
  echo "section=sales snapshot_hash=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb attempt_no=2"
  echo "document_id=91004 document_type=MANAGEMENT_REPORT version_no=1 sha256=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  echo "outbox_id=91006 outbox_status=PENDING delivery_status=ENQUEUED"
  echo "immutable_snapshot=PASS version_link=PASS outbox_link=PASS"
} > "$evidence"
exit 0
SMOKE
chmod +x "$WORK_DIR/smoke.sh"
/usr/local/bin/validate-restore.sh \
  --plan "$PLAN_ID" --uploads-dir "$UPLOADS_READY" --smoke "$WORK_DIR/smoke.sh" \
  > "$EVID/validate.json" 2>&1 || true
grep -q '"state": "READY_FOR_CUTOVER"' "$EVID/validate.json" \
  || fail "validate が READY_FOR_CUTOVER でない"

log "== 11. target の marker 状態を確認 =="
TGT_MARKERS=$(MYSQL_PWD="$(cat "$WORK_DIR/restore-svc-pw")" mysql -h"$TGT_HOST" -urestore-svc \
  "$MYSQL_DATABASE" -N -B \
  -e "SELECT marker FROM marker_test ORDER BY id;" 2>/dev/null)
printf '%s\n' "$TGT_MARKERS" > "$EVID/target-markers.txt"
grep -qx 'marker-before-checkpoint' "$EVID/target-markers.txt" \
  || fail "target に before marker がありません"
# 中間 DML が replay されていること（R1 P2: replay 不在の検出）
grep -qx 'marker-mid' "$EVID/target-markers.txt" \
  || fail "target に中間 DML（marker-mid）がありません（binlog replay が実行されていません）"
if grep -qx 'marker-after-checkpoint' "$EVID/target-markers.txt"; then
  fail "target に after marker があります（復旧点より後のデータが含まれる）"
fi
UPLOADS_MARKERS=$(find "$UPLOADS_READY" -name 'marker-*' -exec basename {} \; | sort)
printf '%s\n' "$UPLOADS_MARKERS" > "$EVID/uploads-markers.txt"
grep -qx 'marker-before.txt' "$EVID/uploads-markers.txt" \
  || fail "uploads staging に before marker がありません"
if grep -qx 'marker-after.txt' "$EVID/uploads-markers.txt"; then
  fail "uploads staging に after marker があります"
fi

log "== 12. source 不変 =="
SRC_STATE=$(src_root "$MYSQL_DATABASE" -N -B \
  -e "SELECT COUNT(*), SHA2(GROUP_CONCAT(marker ORDER BY id), 256) FROM marker_test;" 2>/dev/null | tail -1)
printf '%s\n' "$SRC_STATE" > "$EVID/source-state.txt"

# summary
jq -n \
  --arg plan_id "$PLAN_ID" \
  --arg state "$(jq -r '.state' "$EVID/validate.json")" \
  --arg target_ts "$TARGET_TS" \
  --arg before_cnt "$(grep -c 'marker-before-checkpoint' "$EVID/target-markers.txt" || true)" \
  --arg mid_cnt "$(grep -c 'marker-mid' "$EVID/target-markers.txt" || true)" \
  --arg after_cnt "$(grep -c 'marker-after-checkpoint' "$EVID/target-markers.txt" || true)" \
  --arg uploads_before "$(grep -c 'marker-before.txt' "$EVID/uploads-markers.txt" || true)" \
  --arg uploads_after "$(grep -c 'marker-after.txt' "$EVID/uploads-markers.txt" || true)" \
  '{state: "SUCCESS", plan_id: $plan_id, validation: $state, target_ts: $target_ts,
    target_db: {before_marker: ($before_cnt|tonumber), mid_dml_replayed: ($mid_cnt|tonumber), after_marker: ($after_cnt|tonumber)},
    uploads_staging: {before_marker: ($uploads_before|tonumber), after_marker: ($uploads_after|tonumber)}}' \
  | tee "$WORK_DIR/integration-summary.json"
