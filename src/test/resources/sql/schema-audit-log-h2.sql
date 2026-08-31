-- テスト用: 監査ログテーブル(t_audit_log)を冪等に用意する。
-- 本番の V11__create_audit_log.sql と同一構造だが、共有インメモリH2(DB_CLOSE_DELAY=-1)を
-- 複数コンテキスト/複数スキーマ(engineer-schema-h2.sql の @Sql)が共有する都合上、
-- 既存でも失敗しないよう CREATE TABLE IF NOT EXISTS を用いる。
CREATE TABLE IF NOT EXISTS t_audit_log (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
  username   VARCHAR(50),
  method     VARCHAR(10)  NOT NULL,
  uri        VARCHAR(500) NOT NULL,
  status     INT          NOT NULL,
  application_code VARCHAR(64),
  success_flag BOOLEAN NOT NULL DEFAULT TRUE,
  reference_type VARCHAR(64),
  reference_id BIGINT,
  actor_type VARCHAR(32),
  confirmation_source VARCHAR(32),
  human_user_id BIGINT,
  before_state VARCHAR(255),
  after_state VARCHAR(255),
  correlation_id VARCHAR(128),
  idempotency_key VARCHAR(128),
  CONSTRAINT ck_audit_actor_type CHECK (actor_type IS NULL OR actor_type IN ('HUMAN', 'SYSTEM', 'PROVIDER', 'LEGACY_UNRESOLVED')),
  CONSTRAINT ck_audit_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN ('MANUAL_API', 'SCHEDULER_POLL', 'PROVIDER_SYNC', 'PROVIDER_CALLBACK', 'LEGACY_UNRESOLVED')),
  CONSTRAINT ck_audit_actor_pair CHECK (
    actor_type IS NULL AND confirmation_source IS NULL AND human_user_id IS NULL
    OR actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
      actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0
      OR actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL
      OR actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL
      OR actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL
    )
  ),
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);

-- 共有H2(DB_CLOSE_DELAY=-1)では、別のschema scriptが先に旧V11形を作る場合がある。
-- CREATE IF NOT EXISTSだけでは既存テーブルを拡張しないため、NF-09列も明示的に補完する。
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS application_code VARCHAR(64);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS success_flag BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS reference_type VARCHAR(64);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS reference_id BIGINT;
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS actor_type VARCHAR(32);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS confirmation_source VARCHAR(32);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS human_user_id BIGINT;
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS before_state VARCHAR(255);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS after_state VARCHAR(255);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(128);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE t_audit_log ADD COLUMN IF NOT EXISTS created_at DATETIME DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_auditlog_reference ON t_audit_log(reference_type, reference_id);
ALTER TABLE t_audit_log ADD CONSTRAINT IF NOT EXISTS ck_audit_actor_type
    CHECK (actor_type IS NULL OR actor_type IN ('HUMAN', 'SYSTEM', 'PROVIDER', 'LEGACY_UNRESOLVED'));
ALTER TABLE t_audit_log ADD CONSTRAINT IF NOT EXISTS ck_audit_confirmation_source
    CHECK (confirmation_source IS NULL OR confirmation_source IN ('MANUAL_API', 'SCHEDULER_POLL', 'PROVIDER_SYNC', 'PROVIDER_CALLBACK', 'LEGACY_UNRESOLVED'));
ALTER TABLE t_audit_log ADD CONSTRAINT IF NOT EXISTS ck_audit_actor_pair CHECK (
    actor_type IS NULL AND confirmation_source IS NULL AND human_user_id IS NULL
    OR actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
      actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0
      OR actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL
      OR actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL
      OR actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL
    )
);
