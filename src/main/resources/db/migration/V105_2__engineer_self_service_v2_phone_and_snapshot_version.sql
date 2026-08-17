-- ============================================================
-- 要員セルフサービスポータルV2 電話番号列およびサーベイtemplate_snapshot_version (S14 / V105.2)
-- 1. t_engineer への phone 追加（連絡先電話番号）
-- 2. t_survey_campaign への template_snapshot_version 追加
-- 3. information_schema による冪等ガード付きALTER
-- ============================================================

-- 1. t_engineer: phone (連絡先電話番号)
SET @sql_eng_phone = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_engineer ADD COLUMN phone VARCHAR(50) NULL COMMENT ''連絡先電話番号'' AFTER nearest_station',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_engineer' AND column_name = 'phone');
PREPARE stmt_eng_phone FROM @sql_eng_phone; EXECUTE stmt_eng_phone; DEALLOCATE PREPARE stmt_eng_phone;

-- 2. t_survey_campaign: template_snapshot_version
SET @sql_sc_snap_ver = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_survey_campaign ADD COLUMN template_snapshot_version INT NULL COMMENT ''campaign開始時のtemplate version'' AFTER template_snapshot_json',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_survey_campaign' AND column_name = 'template_snapshot_version');
PREPARE stmt_sc_snap_ver FROM @sql_sc_snap_ver; EXECUTE stmt_sc_snap_ver; DEALLOCATE PREPARE stmt_sc_snap_ver;
