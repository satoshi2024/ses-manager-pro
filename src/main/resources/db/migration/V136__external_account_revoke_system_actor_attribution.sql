-- ===================================================================
-- V136: NF-09 失効確認のactor/source分離と旧データの明示的な移行
-- ===================================================================

-- 既存環境（旧V1または手動DDL）にも適用できるよう、列追加はメタデータで冪等化する。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD COLUMN revoke_requested_by BIGINT NULL COMMENT ''失効要求の起票者ユーザーID（確認主体とは別）'' AFTER revoke_requested_at',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND column_name = 'revoke_requested_by');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD COLUMN actor_type VARCHAR(32) NULL COMMENT ''確認主体: HUMAN, SYSTEM, PROVIDER, LEGACY_UNRESOLVED'' AFTER revoke_confirmed_by',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND column_name = 'actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD COLUMN confirmation_source VARCHAR(32) NULL COMMENT ''確認チャネル: MANUAL_API, SCHEDULER_POLL, PROVIDER_SYNC, PROVIDER_CALLBACK, LEGACY_UNRESOLVED'' AFTER actor_type',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND column_name = 'confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD COLUMN revoke_confirmed_source VARCHAR(32) NULL COMMENT ''旧クライアント互換。正本はconfirmation_source'' AFTER confirmation_source',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND column_name = 'revoke_confirmed_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧sourceが空のREVOKED行は、confirmed_byの値から主体を推測しない。
-- 旧MANUAL/SYSTEMは明示的な旧sourceがある場合だけ限定的に移行し、矛盾はLEGACYへ退避する。
UPDATE t_external_account_reference
SET actor_type = CASE
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) IN ('MANUAL', 'MANUAL_API')
           AND revoke_confirmed_by > 0 THEN 'HUMAN'
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) = 'SYSTEM'
           AND revoke_confirmed_by IS NULL THEN 'SYSTEM'
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK')
           AND revoke_confirmed_by IS NULL THEN 'PROVIDER'
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) = 'LEGACY_UNRESOLVED' THEN 'LEGACY_UNRESOLVED'
      ELSE 'LEGACY_UNRESOLVED'
    END,
    confirmation_source = CASE
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) IN ('MANUAL', 'MANUAL_API')
           AND revoke_confirmed_by > 0 THEN 'MANUAL_API'
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) = 'SYSTEM'
           AND revoke_confirmed_by IS NULL THEN 'SCHEDULER_POLL'
      WHEN UPPER(TRIM(COALESCE(revoke_confirmed_source, ''))) IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK')
           AND revoke_confirmed_by IS NULL THEN UPPER(TRIM(revoke_confirmed_source))
      ELSE 'LEGACY_UNRESOLVED'
    END
WHERE status = 'REVOKED'
  AND (actor_type IS NULL OR confirmation_source IS NULL OR revoke_confirmed_source IS NULL
       OR TRIM(revoke_confirmed_source) = '');

-- LEGACYには未解決の人間IDを残さない。confirmed_by=1をSYSTEMの根拠にはしない。
UPDATE t_external_account_reference
SET revoke_confirmed_by = NULL
WHERE status = 'REVOKED' AND actor_type <> 'HUMAN';

UPDATE t_external_account_reference
SET revoke_confirmed_at = CURRENT_TIMESTAMP
WHERE status = 'REVOKED' AND revoke_confirmed_at IS NULL;

UPDATE t_external_account_reference
SET revoke_confirmed_source = confirmation_source
WHERE confirmation_source IS NOT NULL
  AND (revoke_confirmed_source IS NULL OR TRIM(revoke_confirmed_source) = ''
       OR UPPER(TRIM(revoke_confirmed_source)) IN ('MANUAL', 'SYSTEM'));

-- 外部確認イベント/監査ログに必要な参照・相関属性。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN reference_type VARCHAR(64) NULL COMMENT ''対象種別'' AFTER asset_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'reference_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN reference_id BIGINT NULL COMMENT ''対象参照ID'' AFTER reference_type',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'reference_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN actor_type VARCHAR(32) NULL AFTER actor_user_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN confirmation_source VARCHAR(32) NULL AFTER actor_type',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN human_user_id BIGINT NULL AFTER confirmation_source',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'human_user_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN correlation_id VARCHAR(128) NULL AFTER details_json',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'correlation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER correlation_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'idempotency_key');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) > 0 AND MAX(IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE t_asset_event MODIFY COLUMN asset_id BIGINT NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND column_name = 'asset_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'CREATE INDEX idx_event_reference ON t_asset_event (reference_type, reference_id)',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_asset_event' AND index_name = 'idx_event_reference');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V11/V25以前の監査ログにも同じ属性を追加する。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN reference_type VARCHAR(64) NULL AFTER success_flag',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'reference_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN reference_id BIGINT NULL AFTER reference_type',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'reference_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN actor_type VARCHAR(32) NULL AFTER reference_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN confirmation_source VARCHAR(32) NULL AFTER actor_type',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN human_user_id BIGINT NULL AFTER confirmation_source',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'human_user_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN before_state VARCHAR(255) NULL AFTER human_user_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'before_state');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN after_state VARCHAR(255) NULL AFTER before_state',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'after_state');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN correlation_id VARCHAR(128) NULL AFTER after_state',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'correlation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER correlation_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'idempotency_key');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'CREATE INDEX idx_auditlog_reference ON t_audit_log (reference_type, reference_id)',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND index_name = 'idx_auditlog_reference');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 自動昇格イベントは固定ユーザーを要求しない。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_lifecycle_event ADD COLUMN actor_type VARCHAR(32) NULL AFTER actor_role_snapshot',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_lifecycle_event' AND column_name = 'actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_lifecycle_event ADD COLUMN confirmation_source VARCHAR(32) NULL AFTER actor_type',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_lifecycle_event' AND column_name = 'confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 1,
  'ALTER TABLE t_lifecycle_event MODIFY COLUMN actor_user_id BIGINT NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_lifecycle_event' AND column_name = 'actor_user_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V136自身は新しい列をcanonicalにする。制約名が既に存在する場合は再作成しない。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD CONSTRAINT ck_ext_revoke_actor_type CHECK (actor_type IS NULL OR actor_type IN (''HUMAN'', ''SYSTEM'', ''PROVIDER'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_external_account_reference' AND constraint_name = 'ck_ext_revoke_actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD CONSTRAINT ck_ext_revoke_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN (''MANUAL_API'', ''SCHEDULER_POLL'', ''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_external_account_reference' AND constraint_name = 'ck_ext_revoke_confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD CONSTRAINT ck_ext_revoke_attribution CHECK ((revoke_confirmed_at IS NULL AND actor_type IS NULL AND confirmation_source IS NULL AND revoke_confirmed_by IS NULL AND revoke_confirmed_source IS NULL) OR (revoke_confirmed_at IS NOT NULL AND ((actor_type = ''HUMAN'' AND confirmation_source = ''MANUAL_API'' AND revoke_confirmed_by IS NOT NULL AND revoke_confirmed_by > 0) OR (actor_type = ''SYSTEM'' AND confirmation_source = ''SCHEDULER_POLL'' AND revoke_confirmed_by IS NULL) OR (actor_type = ''PROVIDER'' AND confirmation_source IN (''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'') AND revoke_confirmed_by IS NULL) OR (actor_type = ''LEGACY_UNRESOLVED'' AND confirmation_source = ''LEGACY_UNRESOLVED'' AND revoke_confirmed_by IS NULL)) AND revoke_confirmed_source = confirmation_source))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_external_account_reference' AND constraint_name = 'ck_ext_revoke_attribution');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_external_account_reference ADD CONSTRAINT ck_ext_revoke_status_attribution CHECK (status <> ''REVOKED'' OR (revoke_confirmed_at IS NOT NULL AND actor_type IS NOT NULL AND confirmation_source IS NOT NULL))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_external_account_reference' AND constraint_name = 'ck_ext_revoke_status_attribution');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧既存DBの検索互換インデックス。canonical列にも検索インデックスを付与する。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'CREATE INDEX idx_ext_acc_revoke_confirmed ON t_external_account_reference (revoke_confirmed_at, confirmation_source)',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_external_account_reference' AND index_name = 'idx_ext_acc_revoke_confirmed');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 既存のイベント/監査テーブルにも閉じたenumの制約を適用する。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD CONSTRAINT ck_asset_event_actor_type CHECK (actor_type IS NULL OR actor_type IN (''HUMAN'', ''SYSTEM'', ''PROVIDER'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_asset_event' AND constraint_name = 'ck_asset_event_actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD CONSTRAINT ck_asset_event_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN (''MANUAL_API'', ''SCHEDULER_POLL'', ''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_asset_event' AND constraint_name = 'ck_asset_event_confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_asset_event ADD CONSTRAINT ck_asset_event_actor_pair CHECK ((actor_type IS NULL AND confirmation_source IS NULL AND human_user_id IS NULL) OR (actor_type = ''HUMAN'' AND confirmation_source = ''MANUAL_API'' AND human_user_id IS NOT NULL AND human_user_id > 0 AND actor_user_id IS NOT NULL AND actor_user_id = human_user_id) OR (actor_type = ''SYSTEM'' AND confirmation_source = ''SCHEDULER_POLL'' AND human_user_id IS NULL AND actor_user_id IS NULL) OR (actor_type = ''PROVIDER'' AND confirmation_source IN (''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'') AND human_user_id IS NULL AND actor_user_id IS NULL) OR (actor_type = ''LEGACY_UNRESOLVED'' AND confirmation_source = ''LEGACY_UNRESOLVED'' AND human_user_id IS NULL AND actor_user_id IS NULL))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_asset_event' AND constraint_name = 'ck_asset_event_actor_pair');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD CONSTRAINT ck_audit_actor_type CHECK (actor_type IS NULL OR actor_type IN (''HUMAN'', ''SYSTEM'', ''PROVIDER'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_audit_log' AND constraint_name = 'ck_audit_actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD CONSTRAINT ck_audit_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN (''MANUAL_API'', ''SCHEDULER_POLL'', ''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_audit_log' AND constraint_name = 'ck_audit_confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD CONSTRAINT ck_audit_actor_pair CHECK ((actor_type IS NULL AND confirmation_source IS NULL AND human_user_id IS NULL) OR (actor_type = ''HUMAN'' AND confirmation_source = ''MANUAL_API'' AND human_user_id IS NOT NULL AND human_user_id > 0) OR (actor_type = ''SYSTEM'' AND confirmation_source = ''SCHEDULER_POLL'' AND human_user_id IS NULL) OR (actor_type = ''PROVIDER'' AND confirmation_source IN (''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'') AND human_user_id IS NULL) OR (actor_type = ''LEGACY_UNRESOLVED'' AND confirmation_source = ''LEGACY_UNRESOLVED'' AND human_user_id IS NULL))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_audit_log' AND constraint_name = 'ck_audit_actor_pair');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_lifecycle_event ADD CONSTRAINT ck_lifecycle_event_actor_type CHECK (actor_type IS NULL OR actor_type IN (''HUMAN'', ''SYSTEM'', ''PROVIDER'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_lifecycle_event' AND constraint_name = 'ck_lifecycle_event_actor_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_lifecycle_event ADD CONSTRAINT ck_lifecycle_event_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN (''MANUAL_API'', ''SCHEDULER_POLL'', ''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'', ''LEGACY_UNRESOLVED''))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_lifecycle_event' AND constraint_name = 'ck_lifecycle_event_confirmation_source');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_lifecycle_event ADD CONSTRAINT ck_lifecycle_event_actor_pair CHECK ((actor_type IS NULL AND confirmation_source IS NULL) OR (actor_type = ''HUMAN'' AND confirmation_source = ''MANUAL_API'' AND actor_user_id IS NOT NULL AND actor_user_id > 0) OR (actor_type = ''SYSTEM'' AND confirmation_source = ''SCHEDULER_POLL'' AND actor_user_id IS NULL) OR (actor_type = ''PROVIDER'' AND confirmation_source IN (''PROVIDER_SYNC'', ''PROVIDER_CALLBACK'') AND actor_user_id IS NULL) OR (actor_type = ''LEGACY_UNRESOLVED'' AND confirmation_source = ''LEGACY_UNRESOLVED'' AND actor_user_id IS NULL))',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_lifecycle_event' AND constraint_name = 'ck_lifecycle_event_actor_pair');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
