-- REV-P0-001 / REV-P1-001:
-- identity-provider / system-config は管理者だけの硬境界へ戻す。
-- パッチ前の外部ID紐付けは fail-closed（QUARANTINED）とし、再承認までOIDCログインを拒否する。
-- 本スクリプトは部分失敗後の再実行（同一接続での再適用）に耐える。Flyway versionは前進のみ。

-- ------------------------------------------------------------
-- 1. 外部IDの再承認状態（既存行は DEFAULT で隔離される）
-- ------------------------------------------------------------
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_external_identity ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT ''QUARANTINED'' COMMENT ''APPROVED以外はOIDCログイン拒否'' AFTER linked_at',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 't_user_external_identity'
    AND column_name = 'review_status');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_external_identity ADD COLUMN reviewed_at DATETIME NULL COMMENT ''管理者再承認日時'' AFTER review_status',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 't_user_external_identity'
    AND column_name = 'reviewed_at');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_external_identity ADD COLUMN reviewed_by BIGINT NULL COMMENT ''再承認した管理者sys_user.id'' AFTER reviewed_at',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 't_user_external_identity'
    AND column_name = 'reviewed_by');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE t_user_external_identity
SET review_status = 'QUARANTINED'
WHERE review_status IS NULL
   OR review_status NOT IN ('APPROVED', 'QUARANTINED', 'PENDING_REVIEW');

UPDATE t_user_external_identity
SET review_status = 'QUARANTINED',
    reviewed_at = NULL,
    reviewed_by = NULL
WHERE review_status <> 'APPROVED'
  AND (reviewed_at IS NOT NULL OR reviewed_by IS NOT NULL);

-- パッチ適用時点の全既存bindingを隔離する（本migrationより後のAPPROVEDは維持）。
-- FlywayはV110を一度しか成功記録しないため、ここは「未承認の既存行」を対象にする。
UPDATE t_user_external_identity
SET review_status = 'QUARANTINED'
WHERE review_status = 'PENDING_REVIEW';

-- ------------------------------------------------------------
-- 2. 隔離インベントリ（tenant / provider / binding / subject hash / user / role / linked_at）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_oidc_binding_review_inventory (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  binding_id         BIGINT NOT NULL COMMENT 't_user_external_identity.id',
  tenant_id          VARCHAR(100) NOT NULL COMMENT 'tenant',
  provider_id        BIGINT NOT NULL COMMENT 'm_identity_provider.id',
  subject_sha256     CHAR(64) NOT NULL COMMENT 'subjectのSHA-256。原文は残さない',
  user_id            BIGINT NOT NULL COMMENT 'sys_user.id',
  user_role          VARCHAR(50) NULL COMMENT '隔離時点のrole',
  linked_at          DATETIME NULL COMMENT '元のlink日時',
  deleted_flag       TINYINT NOT NULL DEFAULT 0 COMMENT '隔離時点の論理削除',
  review_status      VARCHAR(32) NOT NULL COMMENT '隔離後の状態',
  inventory_reason   VARCHAR(64) NOT NULL DEFAULT 'V109_PREPATCH_QUARANTINE' COMMENT '凍結理由',
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'インベントリ作成日時',
  CONSTRAINT uk_oidc_binding_review_inventory UNIQUE (binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OIDC紐付け隔離インベントリ';

INSERT INTO t_oidc_binding_review_inventory (
    binding_id, tenant_id, provider_id, subject_sha256, user_id, user_role,
    linked_at, deleted_flag, review_status, inventory_reason, created_at)
SELECT e.id,
       e.tenant_id,
       e.provider_id,
       SHA2(e.subject, 256),
       e.user_id,
       u.role,
       e.linked_at,
       e.deleted_flag,
       e.review_status,
       'V109_PREPATCH_QUARANTINE',
       NOW()
FROM t_user_external_identity e
LEFT JOIN sys_user u ON u.id = e.user_id
WHERE NOT EXISTS (
    SELECT 1
    FROM t_oidc_binding_review_inventory inv
    WHERE inv.binding_id = e.id
);

-- 監査はbinding単位。同一provider/userの複数subjectを畳み込まない。
INSERT INTO t_audit_log (username, method, uri, status, application_code, success_flag, created_at)
SELECT COALESCE(u.username, 'SYSTEM'),
       'MIGRATE',
       CONCAT('/internal/oidc-bindings/', e.id, '/quarantine'),
       200,
       'OIDC_BINDING_QUARANTINE',
       TRUE,
       NOW()
FROM t_user_external_identity e
LEFT JOIN sys_user u ON u.id = e.user_id
WHERE NOT EXISTS (
    SELECT 1
    FROM t_audit_log l
    WHERE l.application_code = 'OIDC_BINDING_QUARANTINE'
      AND l.uri = CONCAT('/internal/oidc-bindings/', e.id, '/quarantine')
);

-- APPROVED は reviewed_at / reviewed_by 必須（再承認の追責をDBでも保証）
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_external_identity ADD CONSTRAINT chk_external_identity_approved_reviewer CHECK (review_status <> ''APPROVED'' OR (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL))',
  'SELECT 1')
  FROM information_schema.table_constraints
  WHERE table_schema = DATABASE()
    AND table_name = 't_user_external_identity'
    AND constraint_name = 'chk_external_identity_approved_reviewer');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. 非管理者groupから identity-provider / system-config を除去し deny を明示
-- ------------------------------------------------------------
UPDATE t_permission_group_action a
JOIN m_permission_group g
  ON g.id = a.group_id
SET a.deny_flag = 1,
    a.deleted_flag = 0,
    a.updated_at = NOW()
WHERE a.action_key IN ('identity-provider.*', 'system-config.*')
  AND g.group_key <> 'role-admin';

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, created_at, updated_at, deleted_flag)
SELECT g.tenant_id, g.id, a.action_key, 1, NOW(), NOW(), 0
FROM m_permission_group g
JOIN (
  SELECT 'identity-provider.*' AS action_key
  UNION ALL
  SELECT 'system-config.*' AS action_key
) a
WHERE g.group_key <> 'role-admin'
  AND NOT EXISTS (
    SELECT 1
    FROM t_permission_group_action existing
    WHERE existing.tenant_id = g.tenant_id
      AND existing.group_id = g.id
      AND existing.action_key = a.action_key
  );
