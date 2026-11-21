-- ============================================================
-- V202: backup の書込み静止の信号表（RB-QUIESCE BWQ-001。.kiro/specs/backup-write-quiesce/design.md §3）
--
-- backup の provider（ops/backup の quiesce-db・quiesce-app-stopped）と app の静止応答（com.ses.backup.quiesce）が、
-- 静止の要求・instance の heartbeat・ack を受け渡す3表を作る。行は作らない。trigger・routine は作らない。
--   - sys_backup_quiesce_request  … 静止要求（provider が書く。app は読むだけで、保存期間を過ぎた行の削除だけをする）
--   - sys_backup_quiesce_instance … app の instance（JVM の起動ごとに1行。app が書く）
--   - sys_backup_quiesce_ack      … 要求×instance の応答（app が書く）
--
-- 時刻の列は全て UTC の値を入れる DATETIME(6) で、両側とも UTC_TIMESTAMP(6) だけで書き・比べる（design.md §3.10。
-- session の time_zone が app と provider で違っても判定がずれないため）。列の DEFAULT に CURRENT_TIMESTAMP を使わない。
-- 同時に有効な要求は1件だけとする。有効枠 active_slot は status から作る STORED の生成列で、一意索引で強制する
-- （MySQL の一意索引は NULL を重複とみなさないため、REQUESTED 以外の行は何件でも残せる。誰も値を書かない）。
-- tenant 列を持たない（静止の対象は DB と uploads の全体で、表は process と DB instance の状態を表す。design.md §3.4）。
-- 個人データは持たない（host 名・接続元・job 名・時刻だけ）。保存期間は 30 日（U-12。app が1日1回消す）。
-- CONTENT_HASH_TABLES（DBA-6）に指定させない（静止区間の中でも書かれ、復元の検証が必ず FAIL になる。design.md §3.7）。
-- 規約（決定XB-6）: V1 へ書き戻さず、本 migration だけで追加する。H2 の replay は
-- src/test/resources/sql/schema-backup-quiesce-h2.sql（STORED 指定・COMMENT なし）と engineer-schema-h2.sql が同じ定義を持ち、
-- BackupQuiesceSchemaContractTest が列・型・NULL 可否・生成式・一意索引・CHECK・FK の一致を固定する。
-- ============================================================

CREATE TABLE sys_backup_quiesce_request (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '要求の番号（epoch として使う）',
    request_token       CHAR(32)     NOT NULL COMMENT 'provider が作る16 byteの乱数のhex',
    mode                VARCHAR(16)  NOT NULL COMMENT 'ONLINE / APP_STOPPED',
    job_kind            VARCHAR(16)  NOT NULL COMMENT 'FULL / CHECKPOINT',
    status              VARCHAR(16)  NOT NULL COMMENT 'REQUESTED / RELEASED / ABORTED / EXPIRED',
    active_slot         TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'REQUESTED' THEN 1 ELSE NULL END) STORED
                        COMMENT '有効な要求だけ1（生成列。誰も書かない）',
    lease_seconds       INT          NOT NULL COMMENT '1回の延長で延ばす秒数',
    requested_by        VARCHAR(64)  NOT NULL COMMENT 'job名とbackup host（個人名・秘密を書かない）',
    protocol_version    SMALLINT     NOT NULL COMMENT 'protocolの版（初版1）',
    requested_at        DATETIME(6)  NOT NULL COMMENT '登録の時刻（UTC）',
    lease_expires_at    DATETIME(6)  NOT NULL COMMENT 'leaseの期限（UTC。延長ごとに保持processが更新）',
    verify_requested_at DATETIME(6)  NULL     COMMENT 'verifyの前にproviderが書く時刻（UTC）',
    released_at         DATETIME(6)  NULL     COMMENT '解除の時刻（UTC）',
    release_reason      VARCHAR(32)  NULL     COMMENT 'COMPLETED / ABORTED / VERIFY_FAILED / EXPIRED / NEW_INSTANCE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bq_request_token (request_token),
    UNIQUE KEY uk_bq_request_active (active_slot),
    KEY idx_bq_request_requested_at (requested_at),
    CONSTRAINT ck_bq_request_mode CHECK (mode IN ('ONLINE', 'APP_STOPPED')),
    CONSTRAINT ck_bq_request_kind CHECK (job_kind IN ('FULL', 'CHECKPOINT')),
    CONSTRAINT ck_bq_request_status CHECK (status IN ('REQUESTED', 'RELEASED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_bq_request_reason CHECK (release_reason IS NULL
        OR release_reason IN ('COMPLETED', 'ABORTED', 'VERIFY_FAILED', 'EXPIRED', 'NEW_INSTANCE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='backupの書込み静止の要求（時刻はUTC）';

CREATE TABLE sys_backup_quiesce_instance (
    instance_id            CHAR(36)     NOT NULL COMMENT '起動ごとのUUID',
    host_name              VARCHAR(255) NOT NULL COMMENT 'host名（取れなければunknown）',
    db_client_host         VARCHAR(255) NOT NULL COMMENT '制御用の接続のUSER()のhost部（portは付かない）',
    db_connection_id       BIGINT       NOT NULL COMMENT '登録時のCONNECTION_ID()（診断用）',
    app_commit             VARCHAR(64)  NOT NULL COMMENT 'buildのcommit（取れなければunknown）',
    protocol_version       SMALLINT     NOT NULL COMMENT 'protocolの版',
    poll_interval_ms       INT          NOT NULL COMMENT '要求の読取り間隔',
    heartbeat_interval_ms  INT          NOT NULL COMMENT 'heartbeatの間隔',
    fence_after_ms         INT          NOT NULL COMMENT 'self-fenceの閾値',
    max_hold_checkpoint_ms INT          NOT NULL COMMENT 'checkpointの最大保持時間',
    max_hold_full_ms       INT          NOT NULL COMMENT 'fullの最大保持時間',
    scheduling_enabled     TINYINT      NOT NULL COMMENT 'app.scheduling.enabledの値',
    status                 VARCHAR(16)  NOT NULL COMMENT 'STARTING / RUNNING / STOPPED',
    gate_state             VARCHAR(16)  NOT NULL COMMENT 'UNARMED / OPEN / CLOSING / CLOSED / FENCED / SHUTDOWN / DISABLED',
    started_at             DATETIME(6)  NOT NULL COMMENT '登録の時刻（UTC）',
    last_beat_at           DATETIME(6)  NOT NULL COMMENT '最後のheartbeatの時刻（UTC）',
    stopped_at             DATETIME(6)  NULL     COMMENT 'STOPPEDにした時刻（UTC。業務の接続poolを閉じた後）',
    PRIMARY KEY (instance_id),
    KEY idx_bq_instance_last_beat (last_beat_at),
    CONSTRAINT ck_bq_instance_status CHECK (status IN ('STARTING', 'RUNNING', 'STOPPED')),
    CONSTRAINT ck_bq_instance_gate CHECK (gate_state IN ('UNARMED', 'OPEN', 'CLOSING', 'CLOSED', 'FENCED',
        'SHUTDOWN', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='backupの書込み静止に応答するappのinstance（時刻はUTC）';

CREATE TABLE sys_backup_quiesce_ack (
    request_id         BIGINT        NOT NULL COMMENT '静止要求の番号',
    instance_id        CHAR(36)      NOT NULL COMMENT 'instanceのUUID（instanceの行は先に消え得るためFKを張らない）',
    state              VARCHAR(16)   NOT NULL COMMENT 'QUIESCED / REFUSED / RESUMED',
    scheduler_state    VARCHAR(16)   NOT NULL COMMENT 'PAUSED / DISABLED',
    drain_millis       INT           NOT NULL COMMENT '排水にかかった時間',
    hold_until         DATETIME(6)   NULL     COMMENT 'appが自動で再開し得る最も早い時刻（UTC）',
    observed_app_hosts VARCHAR(1024) NOT NULL COMMENT '同じapp accountの接続元と件数（host=件数をhostの昇順に,でつなぐ）',
    observed_at        DATETIME(6)   NOT NULL COMMENT '最後に観測した時刻（UTC）',
    refuse_reason      VARCHAR(64)   NULL     COMMENT 'DRAIN_TIMEOUT / ERROR / UNSUPPORTED_PROTOCOL / DISABLED',
    acked_at           DATETIME(6)   NOT NULL COMMENT '応答の時刻（UTC）',
    resumed_at         DATETIME(6)   NULL     COMMENT '再開の時刻（UTC）',
    resume_reason      VARCHAR(32)   NULL     COMMENT 'RELEASED / LEASE_EXPIRED / MAX_HOLD / REQUEST_GONE / SHUTDOWN',
    PRIMARY KEY (request_id, instance_id),
    CONSTRAINT fk_bq_ack_request FOREIGN KEY (request_id) REFERENCES sys_backup_quiesce_request (id) ON DELETE CASCADE,
    CONSTRAINT ck_bq_ack_state CHECK (state IN ('QUIESCED', 'REFUSED', 'RESUMED')),
    CONSTRAINT ck_bq_ack_scheduler CHECK (scheduler_state IN ('PAUSED', 'DISABLED')),
    CONSTRAINT ck_bq_ack_refuse CHECK (refuse_reason IS NULL
        OR refuse_reason IN ('DRAIN_TIMEOUT', 'ERROR', 'UNSUPPORTED_PROTOCOL', 'DISABLED')),
    CONSTRAINT ck_bq_ack_resume CHECK (resume_reason IS NULL
        OR resume_reason IN ('RELEASED', 'LEASE_EXPIRED', 'MAX_HOLD', 'REQUEST_GONE', 'SHUTDOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='backupの書込み静止の要求に対するinstanceの応答（時刻はUTC）';
