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
      INSERT INTO marker_test (marker) VALUES ('marker-before-checkpoint');" \
  || fail "fixture 作成に失敗しました"
mkdir -p "$UPLOADS_DIR/published"
printf 'marker-before-checkpoint\n' > "$UPLOADS_DIR/published/marker-before.txt"

log "== 3. backup-full =="
/usr/local/bin/backup-full.sh > "$EVID/backup-full.log" 2>&1 || fail "backup-full が失敗しました"
log "backup-full OK"

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
PLAN_SHA=$(sha256sum "$PLANS_DIR/$PLAN_ID.json" | awk '{print $1}')
FUTURE=$(date -u -d "now + 2 hours" +%Y-%m-%dT%H:%M:%SZ)
for a in alice bob; do
  if [[ "$a" == alice ]]; then KEY=priv1.pem; else KEY=priv2.pem; fi
  jq -n --arg ps "$PLAN_SHA" --arg tu "$TGT_UUID" --arg a "$a" --arg r manager \
    --arg i "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg e "$FUTURE" --arg ct "CHG-011" \
    '{plan_sha256:$ps,target_uuid:$tu,change_ticket:$ct,actor:$a,role:$r,
      issued_at_utc:$i,expires_at_utc:$e}' > "$WORK_DIR/claim-$a.json"
  /usr/local/lib/ses-backup/providers/approval-verifier-local.sh sign "$WORK_DIR/claim-$a.json" "$KEY"
done
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
mysql "${ARGS[@]}" -N -B --execute "SELECT COUNT(*) FROM marker_test;" > /dev/null 2>&1
exit $?
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
  --arg after_cnt "$(grep -c 'marker-after-checkpoint' "$EVID/target-markers.txt" || true)" \
  --arg uploads_before "$(grep -c 'marker-before.txt' "$EVID/uploads-markers.txt" || true)" \
  --arg uploads_after "$(grep -c 'marker-after.txt' "$EVID/uploads-markers.txt" || true)" \
  '{state: "SUCCESS", plan_id: $plan_id, validation: $state, target_ts: $target_ts,
    target_db: {before_marker: ($before_cnt|tonumber), after_marker: ($after_cnt|tonumber)},
    uploads_staging: {before_marker: ($uploads_before|tonumber), after_marker: ($uploads_after|tonumber)}}' \
  | tee "$WORK_DIR/integration-summary.json"
