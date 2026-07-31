-- Migration V71: BP会社取引停止・所属一意制約の追加補正 DDL

ALTER TABLE `m_bp_company` ADD COLUMN `suspension_reason` VARCHAR(500) AFTER `status`;
ALTER TABLE `m_bp_company` ADD COLUMN `suspension_start_date` DATE AFTER `suspension_reason`;
ALTER TABLE `m_bp_company` ADD COLUMN `suspension_end_date` DATE AFTER `suspension_start_date`;
ALTER TABLE `m_bp_company` ADD COLUMN `suspension_approved_by` BIGINT AFTER `suspension_end_date`;

ALTER TABLE `t_engineer_bp_affiliation` ADD CONSTRAINT `uk_affiliation_eng_from` UNIQUE (`engineer_id`, `valid_from`);
