#!/usr/bin/env bash
# HFP-03-011 integration suite 本体（tool コンテナ内で実行）
#
# 実 MySQL（source/target 別コンテナ）に対して:
#   信号表（V202）と backup account の表単位の権限 -> preflight -> backup-full（--app-stopped）-> responder の起動 ->
#   archive(checkpoint 前の binlog) -> 負例（静止の途中で応答の止まった app の checkpoint は VALID にならない）->
#   checkpoint（quiesce-db。before marker を DB/uploads に固定）-> after marker 注入 ->
#   plan-restore -> restore -> validate-restore（before 存在 / after 不在 /
#   flyway_schema_history の存在と version 照合 / 主要 table の内容ハッシュ照合 /
#   uploads 照合 / read-only smoke）-> 生成列 active_slot の往復 -> responder の停止 -> summary JSON 出力
# 書込み静止は DB の protocol で取る（RB-QUIESCE BWQ-012。design.md §16）。app の instance の代わりに compose の responder
# （tests/integration-responder.sh）が信号表に応答する。
#
# 環境変数: SRC_HOST, TGT_HOST, SRC_PASSWORD_FILE, TGT_PASSWORD_FILE,
# WORK_DIR, REPOSITORY 系（BACKUP_REPOSITORY, RESTIC_PASSWORD_FILE,
# RESTIC_REPOSITORY）, UPLOADS_ROOT（uploads の根。UPLOADS_POINTER の既定は current）, BINLOG_RAW_DIR, BINLOG_IMMUTABLE_DIR,
# QUIESCE_PROVIDER（quiesce-db）, QUIESCE_SCHEMA_FILE（WORK に写した V202）, RESPONDER_DIR（responder の制御の file）,
# QUIESCE_*, APP_COMMIT,
# FLYWAY_VERSION（fixture の history 最新 42 と一致必須）, CRITICAL_TABLES,
# CONTENT_HASH_TABLES, PLANS_DIR, INDEX_DIR 等
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
require_env UPLOADS_ROOT
require_env QUIESCE_SCHEMA_FILE
require_env RESPONDER_DIR
export UPLOADS_POINTER=${UPLOADS_POINTER:-current}
# 定期の checkpoint と同じく環境変数は quiesce-db にする。app の停止を確かめる full は --app-stopped でだけ選ぶ（§9.3）
[[ "${QUIESCE_PROVIDER:-}" == quiesce-db ]] || fail "QUIESCE_PROVIDER は quiesce-db にします（現在: ${QUIESCE_PROVIDER:-未設定}）"

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

# ------------------------------------------------------------ 書込み静止の DB の protocol（RB-QUIESCE BWQ-012）
src_db() { # <SQL> → 結果（tab 区切り・見出し無し。source の業務 DB を root で読む）
  src_root "$MYSQL_DATABASE" -N -B -e "$1"
}

# SQL の結果が期待の値になるまで 1 秒ごとに待つ。届かなければ最後の値を出して失敗する
wait_db() { # <説明> <秒> <SQL> <期待の値>
  local i last=""
  for (( i = 0; i < $2; i++ )); do
    last=$(src_db "$3" 2>/dev/null) || last=""
    [[ "$last" == "$4" ]] && return 0
    sleep 1
  done
  fail "$1 を ${2} 秒の内に確かめられません（期待: $4、最後の値: ${last:-なし}）"
}

# job の state dir の quiesce.json を、host・接続元・要求の token を除いて evidence に写し、provider・mode・job の種類・
# 要求の番号・verify の時刻を確かめる。要求の番号を出力する
quiesce_evidence() { # <state dir> <evidence の file> <provider> <mode> <job の種類>
  local src="$1/quiesce.json" id
  [[ -f "$src" ]] || fail "書込み静止の記録（$src）がありません"
  jq '{provider, mode, job_kind, request_id, started_at_utc, verified_at_utc, released_at_utc, release_reason,
       window_seconds, window_breakdown_seconds, instances: ((.instances // []) | length)}' "$src" > "$2" \
    || fail "書込み静止の記録（$src）を読めません"
  jq -e --arg p "$3" --arg m "$4" --arg k "$5" \
    '.provider == $p and .mode == $m and .job_kind == $k and ((.request_id | tostring) | test("^[0-9]+$"))
     and (.verified_at_utc // "" | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T"))' "$2" > /dev/null \
    || fail "書込み静止の記録が $3・$4・$5 の verify 済みの要求ではありません（$2）"
  id=$(jq -r '.request_id' "$2")
  printf '%s\n' "$id"
}

# index の VALID の数（full と checkpoint）
valid_index_count() {
  local n=0 f
  for f in "${BACKUP_WORK_DIR:?}/index"/*.json; do
    [[ -f "$f" ]] || continue
    [[ "$(jq -r '.status // empty' "$f" 2>/dev/null)" == VALID ]] && n=$((n + 1))
  done
  printf '%s\n' "$n"
}

# RB-W 追加修正 C: uploads の根の下に版 v0 と相対・1 段の pointer current -> v0 を置く（production の配置。app の初回の
# 起動前に作るものに当たる）。preflight・full・checkpoint は pointer を解決して v0 を backup する。
UPLOADS_V0="$UPLOADS_ROOT/v0"
mkdir -p "$UPLOADS_V0"
if [[ ! -L "$UPLOADS_ROOT/$UPLOADS_POINTER" ]]; then
  ln -s v0 "$UPLOADS_ROOT/$UPLOADS_POINTER" || fail "uploads の pointer（$UPLOADS_POINTER -> v0）を作れません"
fi
[[ "$(readlink "$UPLOADS_ROOT/$UPLOADS_POINTER")" == v0 ]] || fail "uploads の pointer が v0 を指していません"

log "== 0. 書込み静止の信号表（V202）・backup account の表単位の権限・responder の合成 user（RB-QUIESCE BWQ-012） =="
# 本番と同じ順: migration（V202）の後に、backup account へ要求の表の表単位の INSERT・UPDATE を付ける（deploy-runbook §3.4。
# 表を作る前の表単位の GRANT は BWQ-011 の MySQL 契約試験が記録する）。integration の backup account は root（全権限を持つ）
# だが、preflight は表単位の GRANT の行を求めるため、本番と同じ GRANT を付ける（接続は root@'%'）。
[[ -f "$QUIESCE_SCHEMA_FILE" ]] || fail "信号表の migration（$QUIESCE_SCHEMA_FILE）がありません（run-integration.sh が WORK へ写す）"
# 本番の Flyway job と同じ utf8mb4 の接続で適用する。生成列 active_slot の式の文字列の introducer は DDL の接続の
# 文字集合で決まり、mysql client の既定（container の locale では latin1）で作ると、mysqldump（SET NAMES utf8mb4）
# からの復元後に式が変わって schema fingerprint（G-04）が一致しない（CI run 44977161397 で検出。design §19）。
src_root --default-character-set=utf8mb4 "$MYSQL_DATABASE" < "$QUIESCE_SCHEMA_FILE" \
  || fail "信号表の migration（V202）を source に適用できません"
src_root -e "GRANT INSERT, UPDATE ON \`$MYSQL_DATABASE\`.\`sys_backup_quiesce_request\` TO 'root'@'%';" \
  || fail "backup account に要求の表の表単位の権限を付けられません"
src_root -N -B -e "SHOW GRANTS FOR 'root'@'%';" 2>/dev/null | grep -F 'sys_backup_quiesce_request' > "$EVID/quiesce-grants.txt" \
  || fail "backup account の表単位の GRANT の行がありません"
# responder の専用の合成 user（app account に当たる。PROCESS を持たず、自分の接続だけを PROCESSLIST で見る。U-2）
RESPONDER_USER=ses-int-app
RESPONDER_PW="int-app-$(od -An -tx1 -N12 /dev/urandom | tr -d ' \n')"
src_root -e "CREATE USER '$RESPONDER_USER'@'%' IDENTIFIED BY '$RESPONDER_PW';
    GRANT SELECT ON \`$MYSQL_DATABASE\`.\`sys_backup_quiesce_request\` TO '$RESPONDER_USER'@'%';
    GRANT SELECT, INSERT, UPDATE ON \`$MYSQL_DATABASE\`.\`sys_backup_quiesce_instance\` TO '$RESPONDER_USER'@'%';
    GRANT SELECT, INSERT, UPDATE ON \`$MYSQL_DATABASE\`.\`sys_backup_quiesce_ack\` TO '$RESPONDER_USER'@'%';" \
  || fail "responder の合成 user を作成できません"
[[ -d "$RESPONDER_DIR" ]] || fail "responder の制御の directory（$RESPONDER_DIR）がありません"
( umask 077
  printf '[mysql]\nhost=%s\nport=%s\nuser=%s\npassword=%s\ndatabase=%s\n' \
    "$SRC_HOST" "${MYSQL_PORT:-3306}" "$RESPONDER_USER" "$RESPONDER_PW" "$MYSQL_DATABASE" > "$RESPONDER_DIR/client.cnf" ) \
  || fail "responder の接続の option file を書けません"
log "信号表・表単位の権限・responder の合成 user OK"

log "== 1. preflight =="
/usr/local/bin/preflight.sh --json > "$EVID/preflight.json" 2>&1 \
  || fail "preflight が失敗しました"
grep -q '"status": "OK"' "$EVID/preflight.json" || fail "preflight が OK でない"
# 書込み静止の検査（checks.quiesce）が quiesce-db で行われ、信号表と表単位の権限を見たこと
grep -q '"provider": "quiesce-db"' "$EVID/preflight.json" && grep -q '"request_table_grant": true' "$EVID/preflight.json" \
  || fail "preflight が書込み静止の検査（checks.quiesce の provider・表単位の権限）を行っていません"
log "preflight OK"

log "== 1.5 MySQL server の版（RB-W 8.4 対応。production と同じ 8.4 系列で検証したことを evidence に残す） =="
SRC_VERSION=$(src_root -N -B -e "SELECT VERSION();" 2>/dev/null) || fail "source の版を取得できません"
TGT_VERSION=$(tgt_root -N -B -e "SELECT VERSION();" 2>/dev/null) || fail "target の版を取得できません"
printf 'source=%s\ntarget=%s\n' "$SRC_VERSION" "$TGT_VERSION" > "$EVID/server-versions.txt"
[[ "$SRC_VERSION" == 8.4.* && "$TGT_VERSION" == 8.4.* ]] \
  || fail "integration の MySQL server が 8.4 系列ではありません（source=$SRC_VERSION target=$TGT_VERSION）"

log "== 2. fixture（marker テーブル + before marker + 最小の flyway_schema_history） =="
# 復元検証の Flyway 照合（history table の存在・成功行の版番号順の最大）を実 MySQL で通すため、
# Flyway と同じ列構成の最小 history を置く。版番号順の最大は 42（辞書順の最大は "9" なので、
# 辞書順で判定する実装はここで失敗する）。FLYWAY_VERSION（checkpoint metadata の期待値）と一致させる。
INTEGRATION_FLYWAY_LATEST=42
[[ "${FLYWAY_VERSION:-}" == "$INTEGRATION_FLYWAY_LATEST" ]] \
  || fail "FLYWAY_VERSION（${FLYWAY_VERSION:-未設定}）が fixture の flyway_schema_history の最新 version（$INTEGRATION_FLYWAY_LATEST）と一致しません"
# RB-W P2-1: source DB の既定の照合順序を production と同じ utf8mb4_unicode_ci にする（server の既定は
# utf8mb4_0900_ai_ci）。復元 DB が server の既定で作られると schema fingerprint の schema 区分が一致しないため、
# restore.sh が記録値で DB を作ることをこの往復で確かめる。
src_root -e "ALTER DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" \
  || fail "source DB の既定の照合順序を設定できません"
src_root "$MYSQL_DATABASE" \
  -e "CREATE TABLE IF NOT EXISTS flyway_schema_history (
        installed_rank INT NOT NULL PRIMARY KEY, version VARCHAR(50), description VARCHAR(200) NOT NULL,
        type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL, checksum INT, installed_by VARCHAR(100) NOT NULL,
        installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, execution_time INT NOT NULL, success TINYINT(1) NOT NULL
      ) ENGINE=InnoDB;
      DELETE FROM flyway_schema_history;
      INSERT INTO flyway_schema_history
        (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) VALUES
        (1, '1', 'create tables', 'SQL', 'V1__create_tables.sql', 1, 'integration', 1, 1),
        (2, '9', 'integration nine', 'SQL', 'V9__integration_nine.sql', 9, 'integration', 1, 1),
        (3, '10', 'integration ten', 'SQL', 'V10__integration_ten.sql', 10, 'integration', 1, 1),
        (4, '41.1', 'integration dotted', 'SQL', 'V41_1__integration_dotted.sql', 411, 'integration', 1, 1),
        (5, '42', 'integration latest', 'SQL', 'V42__integration_latest.sql', 42, 'integration', 1, 1),
        (6, NULL, 'integration repeatable', 'SQL', 'R__integration_repeatable.sql', 7, 'integration', 1, 1);" \
  || fail "flyway_schema_history fixture の作成に失敗しました"
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
# RB-W P2-2: production と同じく、migrator 相当の root 以外の利用者（DEFINER = その利用者）が trigger・view・
# function・外部キー・CHECK を作る。restore-svc は README の restore target role の権限だけで、DEFINER を保ったまま
# 復元し、schema fingerprint が一致することをこの往復で確かめる。binlog が有効な server で root 以外が function・
# trigger を作るため log_bin_trust_function_creators を ON にする（production では DBA が設定する前提）。
FIXTURE_MIGRATOR=ses-migrator
FIXTURE_MIGRATOR_PW=ses-migrator-pw
src_root -e "SET GLOBAL log_bin_trust_function_creators = 1;
    CREATE USER IF NOT EXISTS '$FIXTURE_MIGRATOR'@'%' IDENTIFIED BY '$FIXTURE_MIGRATOR_PW';
    GRANT ALL ON \`$MYSQL_DATABASE\`.* TO '$FIXTURE_MIGRATOR'@'%';" \
  || fail "migrator 相当の利用者を作成できません"
MYSQL_PWD="$FIXTURE_MIGRATOR_PW" mysql -h"$SRC_HOST" -u "$FIXTURE_MIGRATOR" "$MYSQL_DATABASE" \
  -e "CREATE TABLE IF NOT EXISTS t_fp_parent (id BIGINT PRIMARY KEY, name VARCHAR(32) NOT NULL) ENGINE=InnoDB;
      CREATE TABLE IF NOT EXISTS t_fp_child (
        id BIGINT PRIMARY KEY, parent_id BIGINT NOT NULL, qty INT NOT NULL DEFAULT 0,
        CONSTRAINT fk_fp_child_parent FOREIGN KEY (parent_id) REFERENCES t_fp_parent (id) ON DELETE CASCADE,
        CONSTRAINT ck_fp_child_qty CHECK (qty >= 0)
      ) ENGINE=InnoDB;
      DROP TRIGGER IF EXISTS trg_fp_child_bi;
      CREATE TRIGGER trg_fp_child_bi BEFORE INSERT ON t_fp_child FOR EACH ROW SET NEW.qty = GREATEST(NEW.qty, 0);
      DROP FUNCTION IF EXISTS f_fp_next;
      CREATE FUNCTION f_fp_next(x INT) RETURNS INT DETERMINISTIC NO SQL RETURN x + 1;
      CREATE OR REPLACE VIEW v_fp_child AS SELECT id, parent_id, qty FROM t_fp_child;
      DELETE FROM t_fp_child;
      DELETE FROM t_fp_parent;
      INSERT INTO t_fp_parent (id, name) VALUES (1, 'parent-1');
      INSERT INTO t_fp_child (id, parent_id, qty) VALUES (1, 1, 3);" \
  || fail "migrator 相当の利用者で trigger・view・function・外部キー・CHECK を作成できません"
# 復元後の比較のため、source の DEFINER を記録する
definer_list() { # <root 実行関数> → 「種別 名前 DEFINER」の行（並びは固定）
  "$1" -N -B -e "SELECT CONCAT('trigger ', trigger_name, ' ', definer) FROM information_schema.triggers WHERE trigger_schema = '$MYSQL_DATABASE'
      UNION ALL SELECT CONCAT('routine ', routine_name, ' ', definer) FROM information_schema.routines WHERE routine_schema = '$MYSQL_DATABASE'
      UNION ALL SELECT CONCAT('view ', table_name, ' ', definer) FROM information_schema.views WHERE table_schema = '$MYSQL_DATABASE'
      ORDER BY 1;" 2>/dev/null
}
SRC_DEFINERS=$(definer_list src_root) || fail "source の DEFINER を取得できません"
printf '%s\n' "$SRC_DEFINERS" > "$EVID/source-definers.txt"
[[ "$SRC_DEFINERS" == "routine f_fp_next $FIXTURE_MIGRATOR@%"$'\n'"trigger trg_fp_child_bi $FIXTURE_MIGRATOR@%"$'\n'"view v_fp_child $FIXTURE_MIGRATOR@%" ]] \
  || fail "source の trigger・routine・view の DEFINER が migrator 相当の利用者ではありません（$EVID/source-definers.txt）"
mkdir -p "$UPLOADS_V0/published"
printf 'marker-before-checkpoint\n' > "$UPLOADS_V0/published/marker-before.txt"
printf 'report-restore\n' > "$UPLOADS_V0/published/report-restore.pdf"
export REPORT_RESTORE_EVIDENCE="$EVID/report-restore-contract.txt"

log "== 3. backup-full（--app-stopped。app が動く前の最初の full。deploy-runbook §2 の5） =="
QUIESCE_STATE_DIR="$WORK_DIR/quiesce/full" /usr/local/bin/backup-full.sh --app-stopped > "$EVID/backup-full.log" 2>&1 \
  || fail "backup-full が失敗しました"
# RB-W 追加修正 C: full は pointer を解決して backup した版（v0）を記録する
grep -q '"target": "v0"' "$EVID/backup-full.log" \
  || fail "full の結果に backup した uploads の版（uploads_pointer.target=v0）がありません"
# RB-QUIESCE BWQ-012: 停止確認の保持（APP_STOPPED）を登録・verify・解除したこと
FULL_REQUEST_ID=$(quiesce_evidence "$WORK_DIR/quiesce/full" "$EVID/quiesce-full.json" quiesce-app-stopped APP_STOPPED FULL) \
  || exit 1
[[ "$(src_db "SELECT CONCAT(status, ' ', COALESCE(release_reason, '-')) FROM sys_backup_quiesce_request WHERE id = $FULL_REQUEST_ID;")" \
    == "RELEASED COMPLETED" ]] || fail "full の停止確認の保持（番号 $FULL_REQUEST_ID）が RELEASED（COMPLETED）になっていません"
# design.md §19: mysqldump 8.4 の --single-transaction --source-data=2 の起点の採取の lock の取り方と待ち（記録だけ。判定しない）。
# performance_schema の digest の集計（server の起動から。full の前に lock の文を出す処理は無い）と、静止区間の内訳の dump の秒数。
{
  echo "# mysqldump の起点の採取の文（performance_schema.events_statements_summary_by_digest。ms）"
  printf 'digest\tcount\tsum_ms\tmax_ms\tlock_ms\n'
  src_root -N -B -e "SELECT DIGEST_TEXT, COUNT_STAR, ROUND(SUM_TIMER_WAIT / 1000000000, 3), ROUND(MAX_TIMER_WAIT / 1000000000, 3),
      ROUND(SUM_LOCK_TIME / 1000000000, 3) FROM performance_schema.events_statements_summary_by_digest
      WHERE COALESCE(DIGEST_TEXT, '') REGEXP '^(FLUSH|LOCK INSTANCE|UNLOCK|SHOW BINARY LOG|START TRANSACTION|SET SESSION TRANSACTION)'
      ORDER BY DIGEST_TEXT;" 2>&1 || echo "（performance_schema を読めません）"
  echo "# full の静止区間の内訳（秒。quiesce-full.json の window_breakdown_seconds）"
  jq -c '.window_breakdown_seconds // {}' "$EVID/quiesce-full.json"
} > "$EVID/full-dump-start-lock.txt"
log "backup-full OK（停止確認の保持 番号 $FULL_REQUEST_ID）"

log "== 3.2 responder（app の instance 相当）の起動 =="
: > "$RESPONDER_DIR/start"
wait_db "responder の instance の登録（RUNNING・OPEN）" 30 \
  "SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'RUNNING' AND gate_state = 'OPEN';" 1
RESPONDER_INSTANCE=$(cat "$RESPONDER_DIR/instance-id" 2>/dev/null)
[[ "$RESPONDER_INSTANCE" =~ ^[0-9a-f-]{36}$ ]] || fail "responder の instance の番号を読めません"
log "responder OK（instance $RESPONDER_INSTANCE）"

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
# archiver を止めてから失敗する（以降の段の失敗）
fail_archiver() { kill "$ARCHIVER_PID" 2>/dev/null || true; fail "$@"; }

log "== 4.5 負例: 静止の途中で応答の止まった app（responder の freeze）では checkpoint が VALID にならない =="
# responder は QUIESCED を書いた直後に止まる（heartbeat・hold 期限の延長・観測し直しをしない）。checkpoint は verify で
# 観測し直しが揃わず失敗し、要求は ABORTED（VERIFY_FAILED）になり、index に VALID が増えない。
NEG_VALID_BEFORE=$(valid_index_count)
NEG_LAST_ID=$(src_db "SELECT COALESCE(MAX(id), 0) FROM sys_backup_quiesce_request;") || fail_archiver "静止要求の番号を読めません"
: > "$RESPONDER_DIR/freeze-after-ack"
NEG_RC=0
QUIESCE_STATE_DIR="$WORK_DIR/quiesce/checkpoint-negative" /usr/local/bin/create-checkpoint.sh \
  > "$EVID/checkpoint-negative.log" 2>&1 || NEG_RC=$?
NEG_FROZE=no
[[ -e "$RESPONDER_DIR/freeze" ]] && NEG_FROZE=yes
rm -f "$RESPONDER_DIR/freeze-after-ack" "$RESPONDER_DIR/freeze"
NEG_ID=$(src_db "SELECT COALESCE(MAX(id), 0) FROM sys_backup_quiesce_request;") || fail_archiver "静止要求の番号を読めません"
NEG_ROW=$(src_db "SELECT CONCAT(status, ' ', COALESCE(release_reason, '-')) FROM sys_backup_quiesce_request WHERE id = $NEG_ID;")
NEG_VALID_AFTER=$(valid_index_count)
printf 'checkpoint_rc=%s\nresponder_froze_after_ack=%s\nrequest_id=%s\nrequest=%s\nvalid_index_before=%s\nvalid_index_after=%s\n' \
  "$NEG_RC" "$NEG_FROZE" "$NEG_ID" "$NEG_ROW" "$NEG_VALID_BEFORE" "$NEG_VALID_AFTER" > "$EVID/quiesce-negative.txt"
(( NEG_RC != 0 )) || fail_archiver "静止の途中で応答の止まった app で checkpoint が成功しました（$EVID/quiesce-negative.txt）"
[[ "$NEG_FROZE" == yes ]] || fail_archiver "responder が QUIESCED を書く前に checkpoint が終わりました（負例の前提。$EVID/checkpoint-negative.log）"
(( NEG_ID > NEG_LAST_ID )) || fail_archiver "負例の checkpoint が静止要求を登録していません"
[[ "$NEG_ROW" == "ABORTED VERIFY_FAILED" ]] \
  || fail_archiver "負例の静止要求（番号 $NEG_ID）が ABORTED（VERIFY_FAILED）ではありません（$NEG_ROW）"
grep -q '静止が途中で途切れたため VALID にしません' "$EVID/checkpoint-negative.log" \
  || fail_archiver "負例の checkpoint が verify で止まっていません（$EVID/checkpoint-negative.log）"
[[ "$NEG_VALID_AFTER" == "$NEG_VALID_BEFORE" ]] \
  || fail_archiver "負例の checkpoint で index の VALID が増えました（$NEG_VALID_BEFORE → $NEG_VALID_AFTER）"
# freeze を解いた responder は、中止された要求の ack を RESUMED（REQUEST_GONE）にする
wait_db "freeze を解いた responder の再開（番号 $NEG_ID の RESUMED）" 30 \
  "SELECT CONCAT(state, ' ', COALESCE(resume_reason, '-')) FROM sys_backup_quiesce_ack WHERE request_id = $NEG_ID AND instance_id = '$RESPONDER_INSTANCE';" \
  "RESUMED REQUEST_GONE"
log "負例 OK（番号 $NEG_ID は ABORTED（VERIFY_FAILED）、index の VALID は $NEG_VALID_AFTER のまま）"

log "== 5. checkpoint（quiesce-db。before marker を固定） =="
QUIESCE_STATE_DIR="$WORK_DIR/quiesce/checkpoint" /usr/local/bin/create-checkpoint.sh > "$EVID/checkpoint.log" 2>&1 \
  || fail_archiver "checkpoint が失敗しました"
CP_REQUEST_ID=$(quiesce_evidence "$WORK_DIR/quiesce/checkpoint" "$EVID/quiesce-checkpoint.json" quiesce-db ONLINE CHECKPOINT) \
  || { kill "$ARCHIVER_PID" 2>/dev/null || true; exit 1; }
[[ "$(jq -r '.instances' "$EVID/quiesce-checkpoint.json")" == 1 ]] \
  || fail_archiver "checkpoint の静止で ack した必要な instance が responder の 1 つではありません（$EVID/quiesce-checkpoint.json）"
[[ "$(src_db "SELECT CONCAT(status, ' ', COALESCE(release_reason, '-')) FROM sys_backup_quiesce_request WHERE id = $CP_REQUEST_ID;")" \
    == "RELEASED COMPLETED" ]] || fail_archiver "checkpoint の静止要求（番号 $CP_REQUEST_ID）が RELEASED（COMPLETED）になっていません"
wait_db "responder の再開（番号 $CP_REQUEST_ID の RESUMED）" 15 \
  "SELECT CONCAT(state, ' ', COALESCE(resume_reason, '-')) FROM sys_backup_quiesce_ack WHERE request_id = $CP_REQUEST_ID AND instance_id = '$RESPONDER_INSTANCE';" \
  "RESUMED RELEASED"
(( $(valid_index_count) == NEG_VALID_AFTER + 1 )) || fail_archiver "checkpoint の後に index の VALID が 1 つ増えていません"
log "checkpoint OK（静止要求 番号 $CP_REQUEST_ID）"
sleep 2

log "== 6. after marker 注入（source のみ） =="
src_root "$MYSQL_DATABASE" \
  -e "INSERT INTO marker_test (marker) VALUES ('marker-after-checkpoint');" \
  || fail "after marker 注入に失敗しました"
printf 'marker-after-checkpoint\n' > "$UPLOADS_V0/published/marker-after.txt"

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
      SET GLOBAL log_bin_trust_function_creators = 1;
      CREATE USER IF NOT EXISTS '$FIXTURE_MIGRATOR'@'%' IDENTIFIED BY '$FIXTURE_MIGRATOR_PW';
      GRANT ALL ON \`$MYSQL_DATABASE\`.* TO '$FIXTURE_MIGRATOR'@'%';
      CREATE USER IF NOT EXISTS 'restore-svc'@'%' IDENTIFIED BY 'restore-svc-pw';
      GRANT SYSTEM_VARIABLES_ADMIN, REPLICATION_APPLIER, RELOAD, SHOW_ROUTINE, SET_ANY_DEFINER ON *.* TO 'restore-svc'@'%';
      GRANT ALL ON ses_recovery_control.* TO 'restore-svc'@'%';
      GRANT CREATE, INSERT, SELECT, UPDATE, DELETE, DROP, INDEX, ALTER, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER,
        CREATE ROUTINE, ALTER ROUTINE, EXECUTE, EVENT, LOCK TABLES ON \`$MYSQL_DATABASE\`.* TO 'restore-svc'@'%';" \
  || fail "target provision に失敗しました"
# RB-W P2-2: restore-svc の権限は README の restore target role と同じ（RELOAD は GTID のリセット、SHOW_ROUTINE・
# SHOW VIEW・TRIGGER は schema fingerprint、SET_ANY_DEFINER（MySQL 8.4）は dump の DEFINER の再現、REFERENCES は外部キー、
# CREATE ROUTINE / ALTER ROUTINE は function）。DEFINER の account（migrator 相当）は復元先に用意する（DBA の前提）。
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
# history table の存在確認と version 照合の経路を実 MySQL で通ったこと（skip 経路でないこと）
grep -q '"flyway_history_required": true' "$EVID/validate.json" \
  || fail "validate が flyway_schema_history を必須として検証していません"
grep -q "\"flyway_latest\": \"$INTEGRATION_FLYWAY_LATEST\"" "$EVID/validate.json" \
  || fail "validate が flyway_schema_history の version 照合を経由していません"
# 主要 table の内容ハッシュ（checkpoint 記録値）と復元 DB が一致したこと
grep -q '"content_hash": "PASS"' "$EVID/validate.json" \
  || fail "validate の内容ハッシュ照合が PASS でない（CONTENT_HASH_TABLES の記録・照合を確認）"
# schema fingerprint（G-04。checkpoint 記録値）と復元 DB の schema が一致したこと
grep -q '"schema_fingerprint": "PASS"' "$EVID/validate.json" \
  || fail "validate の schema fingerprint 照合が PASS でない（lib/schema-fingerprint.sh の記録・照合を確認）"

log "== 11. target の marker 状態を確認 =="
TGT_MARKERS=$(MYSQL_PWD="$(cat "$WORK_DIR/restore-svc-pw")" mysql -h"$TGT_HOST" -urestore-svc \
  "$MYSQL_DATABASE" -N -B \
  -e "SELECT marker FROM marker_test ORDER BY id;" 2>/dev/null)
printf '%s\n' "$TGT_MARKERS" > "$EVID/target-markers.txt"
grep -qx 'marker-before-checkpoint' "$EVID/target-markers.txt" \
  || fail "target に before marker がありません"
# RB-W P2-1: 復元 DB の既定の照合順序が source（production と同じ utf8mb4_unicode_ci）と同じであること
TGT_DB_DEFAULTS=$(MYSQL_PWD="$(cat "$WORK_DIR/restore-svc-pw")" mysql -h"$TGT_HOST" -urestore-svc -N -B \
  -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.schemata WHERE schema_name = '$MYSQL_DATABASE';" 2>/dev/null)
printf '%s\n' "$TGT_DB_DEFAULTS" > "$EVID/target-db-defaults.txt"
[[ "$TGT_DB_DEFAULTS" == $'utf8mb4\tutf8mb4_unicode_ci' ]] \
  || fail "復元 DB の既定の文字集合・照合順序が source と違います（$TGT_DB_DEFAULTS）"
# RB-W P2-2: trigger・routine・view が DEFINER を保ったまま復元されたこと（restore-svc は README の権限だけ）
TGT_DEFINERS=$(definer_list tgt_root) || fail "target の DEFINER を取得できません"
printf '%s\n' "$TGT_DEFINERS" > "$EVID/target-definers.txt"
[[ "$TGT_DEFINERS" == "$SRC_DEFINERS" ]] \
  || fail "復元後の trigger・routine・view の DEFINER が source と違います（$EVID/target-definers.txt）"
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

log "== 11.5 uploads の版の切替え（uploads-cutover.sh。RB-W 追加修正 C） =="
# 復元した uploads を新しい版 v1-restore として stage し（今の版 v0 と pointer は変えない）、pointer を 1 回の rename で
# 切り替え、逆向きの switch で rollback する。v0 には checkpoint の後に書いた after marker があり、復元した版には無い。
UPLOADS_CUTOVER_LOG="$EVID/uploads-cutover.txt"
/usr/local/bin/uploads-cutover.sh stage --from "$UPLOADS_READY" --root "$UPLOADS_ROOT" --version v1-restore \
  > "$UPLOADS_CUTOVER_LOG" 2>&1 || fail "復元した uploads を新しい版として stage できません（$UPLOADS_CUTOVER_LOG）"
[[ "$(readlink "$UPLOADS_ROOT/$UPLOADS_POINTER")" == v0 ]] || fail "stage が pointer を変えました"
/usr/local/bin/uploads-cutover.sh switch --root "$UPLOADS_ROOT" --to v1-restore --expect v0 \
  >> "$UPLOADS_CUTOVER_LOG" 2>&1 || fail "pointer を新しい版へ切り替えられません（$UPLOADS_CUTOVER_LOG）"
[[ "$(readlink "$UPLOADS_ROOT/$UPLOADS_POINTER")" == v1-restore ]] || fail "switch の後の pointer が新しい版を指していません"
[[ -f "$UPLOADS_ROOT/$UPLOADS_POINTER/published/marker-before.txt" ]] \
  || fail "切替え後の pointer から before marker を読めません"
[[ ! -e "$UPLOADS_ROOT/$UPLOADS_POINTER/published/marker-after.txt" ]] \
  || fail "切替え後の版に after marker があります（復旧点より後の file）"
/usr/local/bin/uploads-cutover.sh switch --root "$UPLOADS_ROOT" --to v0 --expect v1-restore \
  >> "$UPLOADS_CUTOVER_LOG" 2>&1 || fail "rollback（逆向きの switch）ができません（$UPLOADS_CUTOVER_LOG）"
[[ "$(readlink "$UPLOADS_ROOT/$UPLOADS_POINTER")" == v0 ]] || fail "rollback の後の pointer が旧版を指していません"
[[ -f "$UPLOADS_ROOT/$UPLOADS_POINTER/published/marker-after.txt" ]] || fail "rollback の後の旧版の内容が変わっています"
/usr/local/bin/uploads-cutover.sh status --root "$UPLOADS_ROOT" >> "$UPLOADS_CUTOVER_LOG" 2>&1 \
  || fail "uploads の pointer の状態を確認できません（$UPLOADS_CUTOVER_LOG）"
log "uploads の版の切替え OK（stage → switch → rollback）"

log "== 11.6 信号表の生成列 active_slot の dump・復元（RB-QUIESCE D-P3-7。design.md §19） =="
# full の dump（停止確認の保持の行を含む）と binlog の replay（負例・checkpoint の要求）で復元した信号表が、STORED の生成列と
# 一意索引のまま戻ること。schema fingerprint の照合（10. の PASS）に加えて、列の定義と値を source と比べる。
gen_column() { # <root 実行関数> → 列の型・EXTRA・生成式
  "$1" -N -B -e "SELECT COLUMN_TYPE, EXTRA, GENERATION_EXPRESSION FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = '$MYSQL_DATABASE' AND TABLE_NAME = 'sys_backup_quiesce_request' AND COLUMN_NAME = 'active_slot';" 2>/dev/null
}
SRC_GEN=$(gen_column src_root) || fail "source の生成列の定義を読めません"
TGT_GEN=$(gen_column tgt_root) || fail "復元 DB の生成列の定義を読めません"
TGT_SLOTS=$(tgt_root "$MYSQL_DATABASE" -N -B -e "SELECT COUNT(*), COALESCE(SUM(status = 'REQUESTED'), 0),
    COALESCE(SUM(active_slot = 1), 0), COALESCE(SUM(active_slot IS NULL AND status <> 'REQUESTED'), 0)
    FROM sys_backup_quiesce_request;" 2>/dev/null) || fail "復元 DB の信号表を読めません"
TGT_UNIQUE=$(tgt_root -N -B -e "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '$MYSQL_DATABASE'
    AND TABLE_NAME = 'sys_backup_quiesce_request' AND INDEX_NAME = 'uk_bq_request_active' AND NON_UNIQUE = 0;" 2>/dev/null) \
  || fail "復元 DB の一意索引を読めません"
IFS=$'\t' read -r SLOT_ROWS SLOT_REQUESTED SLOT_ACTIVE SLOT_INACTIVE <<< "$TGT_SLOTS"
printf 'source=%s\ntarget=%s\ntarget_rows=%s requested=%s active_slot_1=%s inactive_null=%s\ntarget_unique_index=%s\n' \
  "$SRC_GEN" "$TGT_GEN" "$SLOT_ROWS" "$SLOT_REQUESTED" "$SLOT_ACTIVE" "$SLOT_INACTIVE" "$TGT_UNIQUE" \
  > "$EVID/quiesce-generated-column.txt"
[[ -n "$SRC_GEN" && "$TGT_GEN" == "$SRC_GEN" && "$TGT_GEN" == *"STORED GENERATED"* ]] \
  || fail "復元 DB の active_slot が source と同じ STORED の生成列ではありません（$EVID/quiesce-generated-column.txt）"
[[ "$TGT_UNIQUE" == 1 ]] || fail "復元 DB に有効枠の一意索引（uk_bq_request_active）がありません"
(( SLOT_ROWS >= 2 && SLOT_REQUESTED == SLOT_ACTIVE && SLOT_REQUESTED <= 1 && SLOT_ACTIVE + SLOT_INACTIVE == SLOT_ROWS )) \
  || fail "復元 DB の active_slot の値が status と合いません（$EVID/quiesce-generated-column.txt）"
log "生成列 active_slot の往復 OK（$EVID/quiesce-generated-column.txt）"

log "== 12. source 不変 =="
SRC_STATE=$(src_root "$MYSQL_DATABASE" -N -B \
  -e "SELECT COUNT(*), SHA2(GROUP_CONCAT(marker ORDER BY id), 256) FROM marker_test;" 2>/dev/null | tail -1)
printf '%s\n' "$SRC_STATE" > "$EVID/source-state.txt"

log "== 13. schema fingerprint の正規化規則（実 MySQL・source の使い捨て DB） =="
# README「schema fingerprint」の規則を実 MySQL（8.4）で確かめる。PITR の対象 DB とは別の DB で行い、最後に削除する
# （checkpoint より後の source の変更は復元・drill に影響しない）。
#   - 列コメント・table コメントだけの変更では変わらない
#   - 列の並び順を変えると column 区分だけが変わり、元に戻すと元の値に戻る
#   - 索引の追加は index 区分だけ、既定値の変更は column 区分だけが変わる
#   - trigger・routine・view・CHECK・外部キーも区分として数えられる（root は定義を読める）
# shellcheck disable=SC1091
. /usr/local/lib/ses-backup/schema-fingerprint.sh
FP_PROBE_DB=ses_fp_probe
probe_fp() {
  (
    MYSQL_PWD="$(cat "$SRC_PASSWORD_FILE")"
    export MYSQL_PWD
    MYSQL_CLIENT_BIN=mysql
    schema_fingerprint::compute -h"$SRC_HOST" -uroot "$FP_PROBE_DB"
  )
}
fp_diff() { # <fp a> <fp b> → 件数または SHA-256 の違う区分（「,」区切り。一致なら空）
  jq -rn --argjson a "$1" --argjson b "$2" \
    '[($a.sections | keys[]) as $k | select($a.sections[$k] != $b.sections[$k]) | $k] | join(",")'
}
FP_EVID="$EVID/schema-fingerprint-boundary.txt"
src_root -e "DROP DATABASE IF EXISTS $FP_PROBE_DB; CREATE DATABASE $FP_PROBE_DB;
    CREATE TABLE $FP_PROBE_DB.p (id BIGINT PRIMARY KEY, a VARCHAR(16) NOT NULL DEFAULT 'x', b INT NULL) ENGINE=InnoDB;
    CREATE TABLE $FP_PROBE_DB.c (id BIGINT PRIMARY KEY, p_id BIGINT NOT NULL,
      CONSTRAINT fk_c_p FOREIGN KEY (p_id) REFERENCES $FP_PROBE_DB.p (id) ON DELETE CASCADE,
      CONSTRAINT ck_c_p CHECK (p_id > 0)) ENGINE=InnoDB;
    CREATE TRIGGER $FP_PROBE_DB.trg_p BEFORE INSERT ON $FP_PROBE_DB.p FOR EACH ROW SET NEW.b = COALESCE(NEW.b, 0);
    CREATE FUNCTION $FP_PROBE_DB.f_next(x INT) RETURNS INT DETERMINISTIC NO SQL RETURN x + 1;
    CREATE VIEW $FP_PROBE_DB.v_p AS SELECT id, a FROM $FP_PROBE_DB.p;" \
  || fail "schema fingerprint の確認用 DB を作成できません"
FP_BASE=$(probe_fp) || fail "確認用 DB の schema fingerprint を計算できません"
{
  echo "base=$(jq -r '.sha256' <<< "$FP_BASE")"
  jq -r '.sections | to_entries[] | "section=\(.key) count=\(.value.count)"' <<< "$FP_BASE"
} > "$FP_EVID"
for want in "table 3" "trigger 1" "routine 1" "parameter 2" "view 1" "check 1" "fk_column 1" "fk_rule 1"; do
  [[ "$(jq -r --arg k "${want% *}" '.sections[$k].count' <<< "$FP_BASE")" == "${want#* }" ]] \
    || fail "schema fingerprint の区分 ${want% *} の件数が ${want#* } ではありません（$FP_EVID）"
done
src_root -e "ALTER TABLE $FP_PROBE_DB.p MODIFY a VARCHAR(16) NOT NULL DEFAULT 'x' COMMENT 'comment only', COMMENT = 'table comment';" \
  || fail "コメントを変更できません"
FP_NEXT=$(probe_fp) || fail "コメント変更後の schema fingerprint を計算できません"
[[ "$(jq -r '.sha256' <<< "$FP_NEXT")" == "$(jq -r '.sha256' <<< "$FP_BASE")" ]] \
  || fail "コメントだけの変更で schema fingerprint が変わりました（差異: $(fp_diff "$FP_BASE" "$FP_NEXT")）"
echo "comment_only=unchanged" >> "$FP_EVID"
src_root -e "ALTER TABLE $FP_PROBE_DB.p MODIFY b INT NULL AFTER id;" || fail "列の並び順を変更できません"
FP_NEXT=$(probe_fp) || fail "列の並び順の変更後の schema fingerprint を計算できません"
[[ "$(fp_diff "$FP_BASE" "$FP_NEXT")" == column ]] \
  || fail "列の並び順の変更で column 区分だけが変わっていません（差異: $(fp_diff "$FP_BASE" "$FP_NEXT")）"
echo "column_order=changed(column)" >> "$FP_EVID"
src_root -e "ALTER TABLE $FP_PROBE_DB.p MODIFY b INT NULL AFTER a;" || fail "列の並び順を戻せません"
FP_NEXT=$(probe_fp) || fail "列の並び順を戻した後の schema fingerprint を計算できません"
[[ "$(jq -r '.sha256' <<< "$FP_NEXT")" == "$(jq -r '.sha256' <<< "$FP_BASE")" ]] \
  || fail "列の並び順を戻しても schema fingerprint が元に戻りません（差異: $(fp_diff "$FP_BASE" "$FP_NEXT")）"
echo "column_order_restored=unchanged" >> "$FP_EVID"
src_root -e "CREATE INDEX ix_p_a ON $FP_PROBE_DB.p (a);" || fail "索引を追加できません"
FP_NEXT=$(probe_fp) || fail "索引追加後の schema fingerprint を計算できません"
[[ "$(fp_diff "$FP_BASE" "$FP_NEXT")" == index ]] \
  || fail "索引の追加で index 区分だけが変わっていません（差異: $(fp_diff "$FP_BASE" "$FP_NEXT")）"
echo "index_added=changed(index)" >> "$FP_EVID"
src_root -e "DROP INDEX ix_p_a ON $FP_PROBE_DB.p; ALTER TABLE $FP_PROBE_DB.p ALTER a SET DEFAULT 'y';" \
  || fail "既定値を変更できません"
FP_NEXT=$(probe_fp) || fail "既定値変更後の schema fingerprint を計算できません"
[[ "$(fp_diff "$FP_BASE" "$FP_NEXT")" == column ]] \
  || fail "既定値の変更で column 区分だけが変わっていません（差異: $(fp_diff "$FP_BASE" "$FP_NEXT")）"
echo "default_changed=changed(column)" >> "$FP_EVID"
# RB-W P3-4: 件数だけでなく定義の変更も該当区分だけを変えること（直前の状態と比べる）
fp_expect_change() { # <説明> <期待する区分> <SQL>
  local prev next
  prev=$(probe_fp) || fail "$1 の前の schema fingerprint を計算できません"
  src_root -e "$3" || fail "$1 を実行できません"
  next=$(probe_fp) || fail "$1 の後の schema fingerprint を計算できません"
  [[ "$(fp_diff "$prev" "$next")" == "$2" ]] \
    || fail "$1 で $2 区分だけが変わっていません（差異: $(fp_diff "$prev" "$next")）"
  echo "$1=changed($2)" >> "$FP_EVID"
}
fp_expect_change trigger_body trigger \
  "DROP TRIGGER $FP_PROBE_DB.trg_p; CREATE TRIGGER $FP_PROBE_DB.trg_p BEFORE INSERT ON $FP_PROBE_DB.p FOR EACH ROW SET NEW.b = COALESCE(NEW.b, 1);"
fp_expect_change view_definition view \
  "CREATE OR REPLACE VIEW $FP_PROBE_DB.v_p AS SELECT id, a FROM $FP_PROBE_DB.p WHERE id > 0;"
fp_expect_change fk_on_delete fk_rule \
  "ALTER TABLE $FP_PROBE_DB.c DROP FOREIGN KEY fk_c_p; ALTER TABLE $FP_PROBE_DB.c ADD CONSTRAINT fk_c_p FOREIGN KEY (p_id) REFERENCES $FP_PROBE_DB.p (id) ON DELETE RESTRICT;"
# RB-W P2-1: DB の既定の照合順序の変更は schema 区分だけを変える（既存の table・routine の照合順序は変わらない）
fp_expect_change database_collation schema \
  "ALTER DATABASE $FP_PROBE_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
src_root -e "DROP DATABASE $FP_PROBE_DB;" || fail "確認用 DB を削除できません"
log "schema fingerprint の正規化規則 OK（$FP_EVID）"

log "== 14. responder の停止（STOPPED。業務の接続を閉じた後の書込みに当たる） =="
: > "$RESPONDER_DIR/stop"
wait_db "responder の停止（STOPPED・SHUTDOWN）" 30 \
  "SELECT CONCAT(status, ' ', gate_state) FROM sys_backup_quiesce_instance WHERE instance_id = '$RESPONDER_INSTANCE';" \
  "STOPPED SHUTDOWN"
# responder の合成 user のパスワードを evidence に残さない
if grep -rlF -- "$RESPONDER_PW" "$EVID" > /dev/null 2>&1; then
  fail "evidence に responder の合成 user のパスワードが含まれます"
fi
log "responder の停止 OK"

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
  --slurpfile quiesce_full "$EVID/quiesce-full.json" \
  --slurpfile quiesce_checkpoint "$EVID/quiesce-checkpoint.json" \
  --arg negative_id "$NEG_ID" --arg negative_request "$NEG_ROW" \
  '{state: "SUCCESS", plan_id: $plan_id, validation: $state, target_ts: $target_ts,
    target_db: {before_marker: ($before_cnt|tonumber), mid_dml_replayed: ($mid_cnt|tonumber), after_marker: ($after_cnt|tonumber)},
    uploads_staging: {before_marker: ($uploads_before|tonumber), after_marker: ($uploads_after|tonumber)},
    quiesce: {full: ($quiesce_full[0] | {provider, mode, request_id, verified_at_utc}),
              checkpoint: ($quiesce_checkpoint[0] | {provider, mode, request_id, verified_at_utc}),
              negative: {request_id: ($negative_id|tonumber), request: $negative_request}}}' \
  | tee "$WORK_DIR/integration-summary.json"
