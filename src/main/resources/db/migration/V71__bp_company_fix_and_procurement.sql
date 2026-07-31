-- V71__bp_company_fix_and_procurement.sql
-- BP会社・発注コンプライアンス修正およびカラム・インデックス補正

DELIMITER $$

DROP PROCEDURE IF EXISTS __ses_apply_v71_procurement_fix $$

CREATE PROCEDURE __ses_apply_v71_procurement_fix()
BEGIN
    -- 1. m_bp_company.normalized_name
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'm_bp_company' AND COLUMN_NAME = 'normalized_name'
    ) THEN
        ALTER TABLE `m_bp_company` ADD COLUMN `normalized_name` VARCHAR(255) AFTER `name_kana`;
    END IF;

    -- 2. m_bp_company.suspension_* 4列
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'm_bp_company' AND COLUMN_NAME = 'suspension_reason'
    ) THEN
        ALTER TABLE `m_bp_company` 
            ADD COLUMN `suspension_reason` VARCHAR(500) AFTER `status`,
            ADD COLUMN `suspension_start_date` DATE AFTER `suspension_reason`,
            ADD COLUMN `suspension_end_date` DATE AFTER `suspension_start_date`,
            ADD COLUMN `suspension_approved_by` BIGINT AFTER `suspension_end_date`;
    END IF;

    -- 3. m_bp_company UNIQUE KEY uk_bp_company_normalized
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'm_bp_company' AND INDEX_NAME = 'uk_bp_company_normalized'
    ) THEN
        ALTER TABLE `m_bp_company` ADD CONSTRAINT `uk_bp_company_normalized` UNIQUE (`tenant_id`, `normalized_name`);
    END IF;

    -- 4. t_bp_terms.fee_bearer_exception_reason / _approved_by / _approved_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_bp_terms' AND COLUMN_NAME = 'fee_bearer_exception_reason'
    ) THEN
        ALTER TABLE `t_bp_terms` 
            ADD COLUMN `fee_bearer_exception_reason` VARCHAR(500) AFTER `payment_method`,
            ADD COLUMN `fee_bearer_approved_by` BIGINT AFTER `fee_bearer_exception_reason`,
            ADD COLUMN `fee_bearer_approved_at` DATETIME AFTER `fee_bearer_approved_by`;
    END IF;

    -- 5. t_engineer_bp_affiliation UNIQUE KEY uk_affiliation_eng_from
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_engineer_bp_affiliation' AND INDEX_NAME = 'uk_affiliation_eng_from'
    ) THEN
        ALTER TABLE `t_engineer_bp_affiliation` ADD CONSTRAINT `uk_affiliation_eng_from` UNIQUE (`engineer_id`, `valid_from`);
    END IF;

    -- 6. t_contract 6列 (contract_date, job_description, work_location, inspection_due_date, payment_due_date, payment_method)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contract' AND COLUMN_NAME = 'contract_date'
    ) THEN
        ALTER TABLE `t_contract` 
            ADD COLUMN `contract_date` DATE AFTER `start_date`,
            ADD COLUMN `job_description` TEXT AFTER `contract_date`,
            ADD COLUMN `work_location` VARCHAR(255) AFTER `job_description`,
            ADD COLUMN `inspection_due_date` DATE AFTER `work_location`,
            ADD COLUMN `payment_due_date` DATE AFTER `inspection_due_date`,
            ADD COLUMN `payment_method` VARCHAR(100) AFTER `payment_due_date`;
    END IF;

    -- 7. m_system_config procurement.payment-max-days
    IF NOT EXISTS (
        SELECT 1 FROM `m_system_config` WHERE `config_key` = 'procurement.payment-max-days'
    ) THEN
        INSERT INTO `m_system_config` (`config_key`, `config_value`, `description`) 
        VALUES ('procurement.payment-max-days', '60', '発注コンプライアンス支払期日上限（日）');
    END IF;
END $$

DELIMITER ;

CALL __ses_apply_v71_procurement_fix();

DROP PROCEDURE IF EXISTS __ses_apply_v71_procurement_fix;
