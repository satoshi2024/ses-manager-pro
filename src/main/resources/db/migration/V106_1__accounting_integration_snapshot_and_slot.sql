-- V106.1: 会計・支払連携 forward repair migration (accounting-payment-integration / S15 Stage B R4-T01)
-- 既存 V106 適用済み環境に対して、multi-node CAS 列・NULL 一意性 UNIQUE を追加する。
-- S16 予約済みの V107 と衝突させないため V106.1 (V106_1) を採用する。
-- 各 DDL は information_schema チェック付きの冪等スクリプト。
-- MySQL は非トランザクショナル DDL のため partial 失敗時は後述の Rollback SQL を使用する。
--
-- Rollback 手順 (partial-safe, 厳格順序):
--   1. 新 uk_int_conn DROP (新制約を先に解除)
--   2. backup から退避行を UPDATE 復元
--   3. 旧 uk_int_conn 復元
--   4. connection 追加 5 列を個別存在判定 DROP
--   5. job 追加 6 列を個別存在判定 DROP
--   6. backup テーブル削除
--   7. flyway_schema_history の FAILED 行削除 (または flyway repair)
-- 詳細は design.md §1.2 Rollback SQL を参照。

-- ============================================================
-- Step 1: m_integration_connection_backup_v106_1 — 退避テーブル作成
-- ============================================================
CREATE TABLE IF NOT EXISTS m_integration_connection_backup_v106_1 (
    backup_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_id BIGINT NOT NULL COMMENT '退避元レコードのid',
    tenant_id VARCHAR(64) NOT NULL,
    legal_entity_id BIGINT NULL,
    provider VARCHAR(32) NOT NULL,
    product VARCHAR(32) NOT NULL,
    external_company_id BIGINT NULL,
    company_name VARCHAR(255) NULL,
    encrypted_tokens TEXT NULL,
    expires_at DATETIME NULL,
    status VARCHAR(32) NOT NULL,
    connected_by BIGINT NULL,
    connected_at DATETIME NULL,
    last_refreshed_at DATETIME NULL,
    token_version INT NOT NULL DEFAULT 1,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    backup_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_backup_orig (original_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='V106.1 migration 前の m_integration_connection 退避テーブル';

-- ============================================================
-- Step 2: 重複 active connection の事前検査・退避
--   tenant_id / COALESCE(legal_entity_id,0) / provider / product が同一の active 行が複数ある場合、
--   survivor 行 (優先度: CONNECTED+有効token > last_refreshed_at > id 降順) を残し、残りを退避
-- ============================================================
INSERT INTO m_integration_connection_backup_v106_1 (
    original_id, tenant_id, legal_entity_id, provider, product,
    external_company_id, company_name, encrypted_tokens, expires_at, status,
    connected_by, connected_at, last_refreshed_at, token_version, deleted_flag, version
)
SELECT
    c.id, c.tenant_id, c.legal_entity_id, c.provider, c.product,
    c.external_company_id, c.company_name, c.encrypted_tokens, c.expires_at, c.status,
    c.connected_by, c.connected_at, c.last_refreshed_at,
    1 AS token_version,
    c.deleted_flag, c.version
FROM m_integration_connection c
WHERE c.deleted_flag = 0
  AND c.id NOT IN (
      -- survivor: 各 (tenant_id, COALESCE(legal_entity_id,0), COALESCE(external_company_id,0), provider, product) グループで最優先1行
      SELECT survivor_id FROM (
          SELECT FIRST_VALUE(id) OVER (
              PARTITION BY tenant_id, COALESCE(legal_entity_id, 0), COALESCE(external_company_id, 0), provider, product
              ORDER BY
                  CASE WHEN status = 'CONNECTED' AND encrypted_tokens IS NOT NULL AND expires_at > NOW() THEN 0 ELSE 1 END,
                  COALESCE(last_refreshed_at, '1970-01-01') DESC,
                  id DESC
          ) AS survivor_id
          FROM m_integration_connection
          WHERE deleted_flag = 0
      ) survivors
      GROUP BY survivor_id
  )
  AND EXISTS (
      -- 重複する active connection が複数存在する場合のみ退避 (company_id 単位)
      SELECT 1
      FROM m_integration_connection c2
      WHERE c2.tenant_id = c.tenant_id
        AND COALESCE(c2.legal_entity_id, 0) = COALESCE(c.legal_entity_id, 0)
        AND COALESCE(c2.external_company_id, 0) = COALESCE(c.external_company_id, 0)
        AND c2.provider = c.provider
        AND c2.product = c.product
        AND c2.deleted_flag = 0
        AND c2.id <> c.id
  );

-- 退避した重複行を論理削除
UPDATE m_integration_connection c
INNER JOIN m_integration_connection_backup_v106_1 b ON c.id = b.original_id
SET c.deleted_flag = 1, c.version = c.version + 1, c.updated_at = NOW()
WHERE c.deleted_flag = 0;

-- ============================================================
-- Step 3: 旧 UNIQUE インデックス解除 (存在時のみ)
--   uk_int_conn が (tenant_id, legal_entity_id, provider, product, deleted_flag) の場合のみ DROP
--   ※ 新 uk_int_conn が既に生成列ベースなら何もしない
-- ============================================================
SET @old_uk_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'm_integration_connection'
      AND index_name = 'uk_int_conn'
);
SET @drop_old_uk = IF(@old_uk_exists > 0,
    'ALTER TABLE m_integration_connection DROP INDEX uk_int_conn',
    'SELECT 1');
PREPARE stmt FROM @drop_old_uk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 4: m_integration_connection に追加列を付与 (各列独立存在判定)
-- ============================================================
-- 4a. token_version
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'token_version') = 0,
    'ALTER TABLE m_integration_connection ADD COLUMN token_version INT NOT NULL DEFAULT 1 COMMENT ''トークン更新世代番号 (multi-node CAS用)''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4b. refresh_lease_token
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'refresh_lease_token') = 0,
    'ALTER TABLE m_integration_connection ADD COLUMN refresh_lease_token VARCHAR(64) NULL COMMENT ''トークン更新排他リースUUID''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4c. refresh_lease_expires_at
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'refresh_lease_expires_at') = 0,
    'ALTER TABLE m_integration_connection ADD COLUMN refresh_lease_expires_at DATETIME NULL COMMENT ''トークン更新排他リース期限''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4d. legal_entity_key (生成列 — COALESCE で NULL を 0 に正規化)
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'legal_entity_key') = 0,
    'ALTER TABLE m_integration_connection ADD COLUMN legal_entity_key BIGINT GENERATED ALWAYS AS (COALESCE(legal_entity_id, 0)) STORED COMMENT ''NULL一意性保証用生成列''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4e. external_company_key (生成列 — 事業所(company_id)単位の一意性保証: G4 legal×product×company)
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'external_company_key') = 0,
    'ALTER TABLE m_integration_connection ADD COLUMN external_company_key BIGINT GENERATED ALWAYS AS (COALESCE(external_company_id, 0)) STORED COMMENT ''事業所(company_id)一意性保証用生成列''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4f. active_slot (生成列 — 論理削除後の再登録保証)
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'active_slot') = 0,
    'ALTER TABLE m_integration_connection ADD COLUMN active_slot INT GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN 1 ELSE NULL END) STORED COMMENT ''論理削除後の再登録保証用生成列''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 5: 新 UNIQUE インデックス作成 (生成列ベース / 未存在時のみ)  — company_id 単位 (G4)
-- ============================================================
SET @new_uk_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'm_integration_connection'
      AND index_name = 'uk_int_conn'
);
SET @create_new_uk = IF(@new_uk_exists = 0,
    'ALTER TABLE m_integration_connection ADD UNIQUE KEY uk_int_conn (tenant_id, legal_entity_key, external_company_key, provider, product, active_slot)',
    'SELECT 1');
PREPARE stmt FROM @create_new_uk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- Step 6: t_integration_job に追加列を付与 (各列独立存在判定)
-- ============================================================
-- 6a. payload_snapshot
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'payload_snapshot') = 0,
    'ALTER TABLE t_integration_job ADD COLUMN payload_snapshot LONGTEXT NULL COMMENT ''送信時canonical byte列 (不変)''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6b. lease_token
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'lease_token') = 0,
    'ALTER TABLE t_integration_job ADD COLUMN lease_token VARCHAR(64) NULL COMMENT ''Worker lease UUID''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6c. lease_expires_at
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'lease_expires_at') = 0,
    'ALTER TABLE t_integration_job ADD COLUMN lease_expires_at DATETIME NULL COMMENT ''Worker lease 期限''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6d. tenant_id
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'tenant_id') = 0,
    'ALTER TABLE t_integration_job ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''テナントID''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6e. legal_entity_id
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'legal_entity_id') = 0,
    'ALTER TABLE t_integration_job ADD COLUMN legal_entity_id BIGINT NULL COMMENT ''法人ID''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6f. organization_id
SET @add_col = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'organization_id') = 0,
    'ALTER TABLE t_integration_job ADD COLUMN organization_id BIGINT NULL COMMENT ''スコープ解決用組織IDスナップショット''',
    'SELECT 1'));
PREPARE stmt FROM @add_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
