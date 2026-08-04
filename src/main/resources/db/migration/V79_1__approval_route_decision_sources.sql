-- ============================================================
-- V79.1: 承認routeの申請者role条件と責任者assignment
--
-- V75〜V79は公開済みのため編集しない。V80〜V88は後続specが予約済みなので、
-- V79.1をV79とV80の間のpatch versionとして追加する。
-- ============================================================

SET @approval_route_role_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE m_approval_route ADD COLUMN applicant_role_condition VARCHAR(30) NULL COMMENT ''申請者role条件(NULL=全role)'' AFTER request_type',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'm_approval_route'
    AND column_name = 'applicant_role_condition'
);
PREPARE approval_route_role_stmt FROM @approval_route_role_sql;
EXECUTE approval_route_role_stmt;
DEALLOCATE PREPARE approval_route_role_stmt;

CREATE TABLE IF NOT EXISTS t_approval_responsibility (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    tenant_id           BIGINT NOT NULL DEFAULT 1 COMMENT 'テナントID(現行は1固定)',
    responsibility_type VARCHAR(30) NOT NULL COMMENT 'ORGANIZATION_MANAGER / FINANCE_MANAGER',
    organization_id     BIGINT NULL COMMENT '組織ID(NULL=財務責任者の全社assignment)',
    user_id             BIGINT NOT NULL COMMENT '責任者ユーザー(sys_user.id)',
    valid_from          DATE NOT NULL COMMENT '適用開始日(inclusive)',
    valid_to            DATE NULL COMMENT '適用終了日(inclusive、NULL=無期限)',
    active_flag         TINYINT NOT NULL DEFAULT 1 COMMENT '有効フラグ',
    created_by          BIGINT NULL COMMENT '登録者(sys_user.id)',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag        TINYINT NOT NULL DEFAULT 0,
    INDEX idx_approval_responsibility_lookup
        (tenant_id, responsibility_type, organization_id, valid_from, valid_to),
    INDEX idx_approval_responsibility_user (tenant_id, user_id),
    -- organization_idは下記CHECK制約でも参照するため、MySQL 8の制約上、
    -- CASCADE/SET NULLのreferential actionを付けない。組織は論理削除が正本であり、
    -- assignmentが残る物理削除はDBで拒否する。
    CONSTRAINT fk_approval_responsibility_org FOREIGN KEY (organization_id)
        REFERENCES m_organization_unit(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_approval_responsibility_user FOREIGN KEY (user_id)
        REFERENCES sys_user(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT fk_approval_responsibility_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT chk_approval_responsibility_type
        CHECK (responsibility_type IN ('ORGANIZATION_MANAGER', 'FINANCE_MANAGER')),
    CONSTRAINT chk_approval_responsibility_period
        CHECK (valid_to IS NULL OR valid_from <= valid_to),
    CONSTRAINT chk_approval_responsibility_organization
        CHECK (responsibility_type = 'FINANCE_MANAGER' OR organization_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認責任者assignment';
