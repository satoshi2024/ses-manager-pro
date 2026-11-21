-- V202（backup の書込み静止の信号表。RB-QUIESCE BWQ-001）の H2 変種。
-- db/migration/V202 は ENGINE・COMMENT・生成列の STORED 指定を含むため、H2 の replay には同じ表・列・型・NULL 可否・
-- 生成式・主キー・一意 key・索引・CHECK・FK を置く（H2 は生成列の STORED 指定を解釈しない）。
-- 共有H2（DB_CLOSE_DELAY=-1）は context ごとに schema-locations を再実行するため、表・索引とも IF NOT EXISTS で冪等にする。
-- engineer-schema-h2.sql も同じ定義を持つ。V202 との一致は BackupQuiesceSchemaContractTest が固定する。
-- 信号表の SQL（JdbcBackupQuiesceSignalRepository）は時刻を UTC_TIMESTAMP(6) で書き、登録の診断に CONNECTION_ID() を使うため、
-- H2 にも同じ名前の関数 alias を置く（com.ses.test.H2MySqlTimeFunctions）。
CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'com.ses.test.H2MySqlTimeFunctions.utcTimestamp';
CREATE ALIAS IF NOT EXISTS CONNECTION_ID FOR 'com.ses.test.H2MySqlTimeFunctions.connectionId';
CREATE TABLE IF NOT EXISTS sys_backup_quiesce_request (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    request_token       CHAR(32)     NOT NULL,
    mode                VARCHAR(16)  NOT NULL,
    job_kind            VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    active_slot         TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'REQUESTED' THEN 1 ELSE NULL END),
    lease_seconds       INT          NOT NULL,
    requested_by        VARCHAR(64)  NOT NULL,
    protocol_version    SMALLINT     NOT NULL,
    requested_at        DATETIME(6)  NOT NULL,
    lease_expires_at    DATETIME(6)  NOT NULL,
    verify_requested_at DATETIME(6)  NULL,
    released_at         DATETIME(6)  NULL,
    release_reason      VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bq_request_token UNIQUE (request_token),
    CONSTRAINT uk_bq_request_active UNIQUE (active_slot),
    CONSTRAINT ck_bq_request_mode CHECK (mode IN ('ONLINE', 'APP_STOPPED')),
    CONSTRAINT ck_bq_request_kind CHECK (job_kind IN ('FULL', 'CHECKPOINT')),
    CONSTRAINT ck_bq_request_status CHECK (status IN ('REQUESTED', 'RELEASED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_bq_request_reason CHECK (release_reason IS NULL
        OR release_reason IN ('COMPLETED', 'ABORTED', 'VERIFY_FAILED', 'EXPIRED', 'NEW_INSTANCE'))
);
CREATE INDEX IF NOT EXISTS idx_bq_request_requested_at ON sys_backup_quiesce_request (requested_at);

CREATE TABLE IF NOT EXISTS sys_backup_quiesce_instance (
    instance_id            CHAR(36)     NOT NULL,
    host_name              VARCHAR(255) NOT NULL,
    db_client_host         VARCHAR(255) NOT NULL,
    db_connection_id       BIGINT       NOT NULL,
    app_commit             VARCHAR(64)  NOT NULL,
    protocol_version       SMALLINT     NOT NULL,
    poll_interval_ms       INT          NOT NULL,
    heartbeat_interval_ms  INT          NOT NULL,
    fence_after_ms         INT          NOT NULL,
    max_hold_checkpoint_ms INT          NOT NULL,
    max_hold_full_ms       INT          NOT NULL,
    scheduling_enabled     TINYINT      NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    gate_state             VARCHAR(16)  NOT NULL,
    started_at             DATETIME(6)  NOT NULL,
    last_beat_at           DATETIME(6)  NOT NULL,
    stopped_at             DATETIME(6)  NULL,
    PRIMARY KEY (instance_id),
    CONSTRAINT ck_bq_instance_status CHECK (status IN ('STARTING', 'RUNNING', 'STOPPED')),
    CONSTRAINT ck_bq_instance_gate CHECK (gate_state IN ('UNARMED', 'OPEN', 'CLOSING', 'CLOSED', 'FENCED',
        'SHUTDOWN', 'DISABLED'))
);
CREATE INDEX IF NOT EXISTS idx_bq_instance_last_beat ON sys_backup_quiesce_instance (last_beat_at);

CREATE TABLE IF NOT EXISTS sys_backup_quiesce_ack (
    request_id         BIGINT        NOT NULL,
    instance_id        CHAR(36)      NOT NULL,
    state              VARCHAR(16)   NOT NULL,
    scheduler_state    VARCHAR(16)   NOT NULL,
    drain_millis       INT           NOT NULL,
    hold_until         DATETIME(6)   NULL,
    observed_app_hosts VARCHAR(1024) NOT NULL,
    observed_at        DATETIME(6)   NOT NULL,
    refuse_reason      VARCHAR(64)   NULL,
    acked_at           DATETIME(6)   NOT NULL,
    resumed_at         DATETIME(6)   NULL,
    resume_reason      VARCHAR(32)   NULL,
    PRIMARY KEY (request_id, instance_id),
    CONSTRAINT fk_bq_ack_request FOREIGN KEY (request_id) REFERENCES sys_backup_quiesce_request (id) ON DELETE CASCADE,
    CONSTRAINT ck_bq_ack_state CHECK (state IN ('QUIESCED', 'REFUSED', 'RESUMED')),
    CONSTRAINT ck_bq_ack_scheduler CHECK (scheduler_state IN ('PAUSED', 'DISABLED')),
    CONSTRAINT ck_bq_ack_refuse CHECK (refuse_reason IS NULL
        OR refuse_reason IN ('DRAIN_TIMEOUT', 'ERROR', 'UNSUPPORTED_PROTOCOL', 'DISABLED')),
    CONSTRAINT ck_bq_ack_resume CHECK (resume_reason IS NULL
        OR resume_reason IN ('RELEASED', 'LEASE_EXPIRED', 'MAX_HOLD', 'REQUEST_GONE', 'SHUTDOWN'))
);
