-- ============================================================
-- 要員セルフサービスポータルV2 構造修復・追加カラム (S14 / V105.1)
-- 1. t_engineer_change_request への reason / attachment_document_id 追加
-- 2. t_survey_campaign への template_snapshot_json 追加
-- 3. information_schema による冪等ガード付きALTER
-- ============================================================

-- 1. t_engineer_change_request: 申請理由・添付書類IDの追加
SET @sql_ecr_reason = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_engineer_change_request ADD COLUMN reason VARCHAR(1000) NULL COMMENT ''申請理由'' AFTER diff_json',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_engineer_change_request' AND column_name = 'reason');
PREPARE stmt_ecr_reason FROM @sql_ecr_reason; EXECUTE stmt_ecr_reason; DEALLOCATE PREPARE stmt_ecr_reason;

SET @sql_ecr_attach = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_engineer_change_request ADD COLUMN attachment_document_id BIGINT NULL COMMENT ''添付書類ID（領収書/証明書等）'' AFTER reason',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_engineer_change_request' AND column_name = 'attachment_document_id');
PREPARE stmt_ecr_attach FROM @sql_ecr_attach; EXECUTE stmt_ecr_attach; DEALLOCATE PREPARE stmt_ecr_attach;

-- 2. t_survey_campaign: template snapshot JSON
SET @sql_sc_snap = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_survey_campaign ADD COLUMN template_snapshot_json LONGTEXT NULL COMMENT ''campaign開始時の質問定義snapshot'' AFTER title',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_survey_campaign' AND column_name = 'template_snapshot_json');
PREPARE stmt_sc_snap FROM @sql_sc_snap; EXECUTE stmt_sc_snap; DEALLOCATE PREPARE stmt_sc_snap;

-- 3. t_document_link: skill_sheet_confirmed_at / skill_sheet_confirmed_version (修復ガード)
SET @sql_doc_link1 = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_document_link ADD COLUMN skill_sheet_confirmed_at DATETIME NULL COMMENT ''スキルシート確認日時（NULL=未確認）'' AFTER target_id',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_document_link' AND column_name = 'skill_sheet_confirmed_at');
PREPARE stmt_doc_link1 FROM @sql_doc_link1; EXECUTE stmt_doc_link1; DEALLOCATE PREPARE stmt_doc_link1;

SET @sql_doc_link2 = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_document_link ADD COLUMN skill_sheet_confirmed_version VARCHAR(64) NULL COMMENT ''確認時のdocument version'' AFTER skill_sheet_confirmed_at',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_document_link' AND column_name = 'skill_sheet_confirmed_version');
PREPARE stmt_doc_link2 FROM @sql_doc_link2; EXECUTE stmt_doc_link2; DEALLOCATE PREPARE stmt_doc_link2;
