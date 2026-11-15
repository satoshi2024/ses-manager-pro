-- ============================================================
-- V197: 履歴・スキルシート取込（RESUME_INGESTION）と取込センターCSV（IMPORT）の文書種別を登録する。
--
-- 既存の不具合の修正: ResumeIngestionServiceImpl と ImportCsvIngestionService はこの2種別で文書台帳へ登録するが、
-- どの migration も m_document_type に行を作っておらず、DocumentServiceImpl.validateDocumentType が
-- 400（error.document.invalidType）で拒否していた（スキルシート取込は upload が毎回失敗し、NF06 は ON にすると失敗する）。
--
-- 値（spec に根拠が無いものは暫定値。文書 owner の確認を要する）:
--   direction            INCOMING。両経路とも受領として登録する（registerReceived は RECEIVED から INCOMING、
--                        取込センターは INCOMING を明示）。種別の方向と一致しないと validateDocumentType が拒否する。
--   retention_years      3。spec に根拠が無いため、同種の SKILL_SHEET（V105 の法定 baseline 3年）に合わせた暫定値。
--   retention_start_rule TRANSACTION_DATE（SKILL_SHEET と同じ）。両経路とも transaction_date を設定しないため
--                        retention_until は NULL のままで、DocumentRetentionJob の処分候補にはならない
--                        （DocumentServiceImpl.computeRetentionUntil）。保存年数の確定は owner の判断後に別 migration で行う。
-- 行が既にあれば入れない（NOT EXISTS。V153 と同じ書き方）。
--
-- NF07 の data element catalog（V196）は文書種別ごとに DOCUMENT:<code> を持つため、同じ規則で行を足す
-- （DOCUMENT:RESUME_INGESTION は V196 で投入済みのため、ここでは DOCUMENT:IMPORT だけが入る）。
-- ============================================================

INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'RESUME_INGESTION', '履歴・スキルシート取込原本', 'INCOMING', 3, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'RESUME_INGESTION');

INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'IMPORT', '取込センターCSV原本', 'INCOMING', 3, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'IMPORT');

INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id,
                                subject_attribution, inventory_ref)
SELECT t.tenant_id, CONCAT('DOCUMENT:', dt.code), 'FILE_OBJECT', 't_document_version.storage_key', 'document-file',
       'ATTRIBUTABLE', 'pii-inventory.md#DB-017'
FROM (SELECT DISTINCT tenant_id FROM sys_user WHERE tenant_id IS NOT NULL AND tenant_id <> '') t
CROSS JOIN (SELECT code FROM m_document_type WHERE code IN ('RESUME_INGESTION', 'IMPORT')) dt
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
                  WHERE e.tenant_id = t.tenant_id AND e.data_element_id = CONCAT('DOCUMENT:', dt.code));
