-- Migration V70: BP会社マスタ・発注コンプライアンス DDL

CREATE TABLE IF NOT EXISTS `m_bp_company` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `legal_name` VARCHAR(255) NOT NULL,
    `name_kana` VARCHAR(255),
    `normalized_name` VARCHAR(255),
    `entity_type` VARCHAR(50) NOT NULL COMMENT 'CORPORATE / INDIVIDUAL / FREELANCE / PROVISIONAL',
    `corporate_number` VARCHAR(13),
    `invoice_registration_number` VARCHAR(14),
    `capital_band` VARCHAR(50),
    `employee_band` VARCHAR(50),
    `address` VARCHAR(500),
    `representative` VARCHAR(100),
    `status` VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE / SUSPENDED / MERGED',
    `rating` INT DEFAULT 0,
    `primary_sales_user_id` BIGINT,
    `compliance_applicability` VARCHAR(50) COMMENT 'FREELANCE_ACT / SUBCOMMITTEE_ACT / EXEMPT / NULL(UNCHECKED)',
    `applicability_checked_by` BIGINT,
    `applicability_checked_at` DATETIME,
    `applicability_note` TEXT,
    `version` INT NOT NULL DEFAULT 1,
    `created_by` BIGINT,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_company_tenant_status` (`tenant_id`, `status`),
    INDEX `idx_bp_company_corporate_num` (`corporate_number`),
    INDEX `idx_bp_company_invoice_num` (`invoice_registration_number`),
    UNIQUE KEY `uk_bp_company_normalized` (`tenant_id`, `normalized_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP会社マスタ';

CREATE TABLE IF NOT EXISTS `t_bp_contact` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `department` VARCHAR(100),
    `role` VARCHAR(100),
    `email` VARCHAR(255),
    `phone` VARCHAR(50),
    `primary_flag` INT NOT NULL DEFAULT 0,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_contact_company` (`bp_company_id`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP担当者連絡先';

CREATE TABLE IF NOT EXISTS `t_bp_bank_account` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `bank_name` VARCHAR(100),
    `branch_name` VARCHAR(100),
    `account_type` VARCHAR(20) DEFAULT 'ORDINARY',
    `encrypted_account_number` VARCHAR(500),
    `account_holder` VARCHAR(100),
    `masked_label` VARCHAR(100) NOT NULL,
    `valid_from` DATE NOT NULL,
    `valid_to` DATE,
    `approval_status` VARCHAR(50) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    `approved_by` BIGINT,
    `approved_at` DATETIME,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_bank_company` (`bp_company_id`, `approval_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP口座情報';

CREATE TABLE IF NOT EXISTS `t_bp_terms` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `effective_from` DATE NOT NULL,
    `effective_to` DATE,
    `closing_day` INT NOT NULL DEFAULT 31 COMMENT '31 = EOM',
    `payment_month_offset` INT NOT NULL DEFAULT 1 COMMENT '1 = Next month',
    `payment_day` INT NOT NULL DEFAULT 30 COMMENT '30 = 30th',
    `fee_bearer` VARCHAR(20) NOT NULL DEFAULT 'PAYEE' COMMENT 'PAYEE / PAYER',
    `payment_method` VARCHAR(50) NOT NULL DEFAULT 'BANK_TRANSFER',
    `fee_bearer_exception_reason` VARCHAR(500),
    `fee_bearer_approved_by` BIGINT,
    `fee_bearer_approved_at` DATETIME,
    `max_payment_days` INT NOT NULL DEFAULT 60,
    `version` INT NOT NULL DEFAULT 1,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_terms_company_effective` (`bp_company_id`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP取引条件';

CREATE TABLE IF NOT EXISTS `t_engineer_bp_affiliation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `engineer_id` BIGINT NOT NULL,
    `bp_company_id` BIGINT NOT NULL,
    `valid_from` DATE NOT NULL,
    `valid_to` DATE,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_engineer_bp_affiliation` (`engineer_id`, `valid_from`, `valid_to`),
    INDEX `idx_bp_engineer_affiliation` (`bp_company_id`, `valid_from`, `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP要員所属履歴';

CREATE TABLE IF NOT EXISTS `t_bp_evaluation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `period` VARCHAR(20) NOT NULL,
    `quality_score` INT DEFAULT 0,
    `response_score` INT DEFAULT 0,
    `retention_score` INT DEFAULT 0,
    `compliance_score` INT DEFAULT 0,
    `billing_accuracy_score` INT DEFAULT 0,
    `comment` TEXT,
    `evaluated_by` BIGINT,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_evaluation_company_period` (`bp_company_id`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP評価記録';

CREATE TABLE IF NOT EXISTS `t_bp_price_negotiation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `requested_at` DATE NOT NULL,
    `responded_at` DATE,
    `status` VARCHAR(50) NOT NULL DEFAULT 'REQUESTED',
    `requested_amount` DECIMAL(15, 2),
    `agreed_amount` DECIMAL(15, 2),
    `summary` TEXT,
    `document_id` BIGINT,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_price_neg_company` (`bp_company_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP価格協議記録';

-- 既存テーブル拡張 (テーブルが既に存在する場合に安全に ALTER)
ALTER TABLE `t_bp_availability` ADD COLUMN `bp_company_id` BIGINT AFTER `bp_company`;
ALTER TABLE `t_bp_payment` ADD COLUMN `bp_company_id` BIGINT AFTER `payee_company_name`;
ALTER TABLE `t_bp_payment` ADD COLUMN `bp_company_name_snapshot` VARCHAR(255) AFTER `bp_company_id`;
ALTER TABLE `t_bp_payment` ADD COLUMN `terms_snapshot_json` TEXT AFTER `bp_company_name_snapshot`;

-- 発注コンプライアンス必須明示項目（R3.2）。既存契約フローへの追記項目のみ。
ALTER TABLE `t_contract` ADD COLUMN `contract_date` DATE NULL COMMENT '委託日';
ALTER TABLE `t_contract` ADD COLUMN `job_description` VARCHAR(2000) NULL COMMENT '役務内容';
ALTER TABLE `t_contract` ADD COLUMN `work_location` VARCHAR(500) NULL COMMENT '提供場所';
ALTER TABLE `t_contract` ADD COLUMN `inspection_due_date` DATE NULL COMMENT '検査完了期日';
ALTER TABLE `t_contract` ADD COLUMN `payment_due_date` DATE NULL COMMENT '具体的支払期日';
ALTER TABLE `t_contract` ADD COLUMN `payment_method` VARCHAR(50) NULL COMMENT '支払方法';

-- 支払期日上限の法務設定（既定60日。R3.4）
INSERT INTO `m_system_config` (`config_key`, `config_value`, `description`)
VALUES ('procurement.payment-max-days', '60', '発注支払期日の法務設定上限（受領日からの日数）')
ON DUPLICATE KEY UPDATE `config_value` = VALUES(`config_value`);

-- メニュー初期設定 (BP管理画面)
INSERT INTO `m_menu` (`menu_key`, `menu_name`, `path_prefix`, `api_prefix`, `sort_order`)
VALUES ('bp-company', 'BP会社管理', '/bp-company', '/api/bp-companies', 45)
ON DUPLICATE KEY UPDATE `menu_name` = VALUES(`menu_name`);

INSERT INTO `t_role_menu` (`role`, `menu_id`)
SELECT '管理者', id FROM `m_menu` WHERE `menu_key` = 'bp-company'
ON DUPLICATE KEY UPDATE `role` = VALUES(`role`);

INSERT INTO `t_role_menu` (`role`, `menu_id`)
SELECT '営業', id FROM `m_menu` WHERE `menu_key` = 'bp-company'
ON DUPLICATE KEY UPDATE `role` = VALUES(`role`);

INSERT INTO `t_role_menu` (`role`, `menu_id`)
SELECT 'マネージャー', id FROM `m_menu` WHERE `menu_key` = 'bp-company'
ON DUPLICATE KEY UPDATE `role` = VALUES(`role`);
