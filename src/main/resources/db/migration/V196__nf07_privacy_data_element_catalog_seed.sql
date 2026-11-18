-- ============================================================
-- V196: NF07 F2（read-only provider）の data element catalog seed。
--
-- production-bridge-phased-plan.md §3.2 の草案を実装する。入れるのは構造の行（data element・保存場所・provider）と
-- subject_attribution だけで、owner・データ分類・inventory 状態は既定の UNKNOWN のまま、保持期間は持たない（I-1）。
-- 値の正本は pii-inventory.md（inventory_ref で行を示す）。分類の変更は migration と Plan Review でだけ行う（C-2）。
--
-- tenant は sys_user に存在する tenant から取る（権限 group とは結び付けない。P3-8）。行が既にあれば入れない
-- （NOT EXISTS。論理削除済みの行も既存として扱い、復活させない）。後から作られた tenant の行はこの seed では
-- 入らず、catalog API の coverage report が CATALOG_MISSING として報告する。
--
-- 分類の規則:
--   - F2 の provider が扱う data element は provider の宣言と同じ分類にする
--     （ProductionPrivacyProviderRegistry が UNKNOWN の宣言を拒否する）。文書種別ごとの分類の正本は
--     DocumentFileProvider.DOCUMENT_TYPE_RULES で、本人を導出できない種別（MANAGEMENT_REPORT・
--     SERVICE_REQUEST_ATTACHMENT・IMPORT）は NOT_ATTRIBUTABLE、他は ATTRIBUTABLE とする（一致は test で固定する）。
--   - §3.4.1 で hold の gate に接続する data element（D1〜D7・D9）は UNKNOWN にしない（C-2-2）。
--     provider を持たない D7・D9 は行ごとに本人を導出できないため NOT_ATTRIBUTABLE とする。
--     V192 で purge の対象に加わった外部API監査（EXTERNAL_AUDIT）も D7 と同じ規則で分類する。
--   - それ以外の未登録の data element（監査ログ、backup、外部 provider 側の写し等）は provider_id を NULL、
--     分類を UNKNOWN のままにし、coverage report で PROVIDER_UNREGISTERED として報告する。
--     外部 provider（Gemini・CloudSign・freee・mailbox・backup/replica）は第1期に登録しない。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 固定の data element（provider が扱うもの・gate に接続するもの・未登録のもの）
-- ------------------------------------------------------------
INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id,
                                subject_attribution, inventory_ref)
SELECT t.tenant_id, d.data_element_id, d.storage_kind, d.storage_ref, d.provider_id, d.subject_attribution,
       d.inventory_ref
FROM (SELECT DISTINCT tenant_id FROM sys_user WHERE tenant_id IS NOT NULL AND tenant_id <> '') t
CROSS JOIN (
  SELECT 'ENGINEER_PROFILE' AS data_element_id, 'DB_COLUMN' AS storage_kind,
         't_engineer.(allow-list)' AS storage_ref, 'engineer-profile' AS provider_id,
         'ATTRIBUTABLE' AS subject_attribution, 'pii-inventory.md#DB-004' AS inventory_ref
  UNION ALL SELECT 'ENGINEER_PHOTO', 'FILE_OBJECT', 't_engineer.photo_url', 'engineer-profile',
         'ATTRIBUTABLE', 'pii-inventory.md#DB-004'
  UNION ALL SELECT 'CANDIDATE_PROFILE', 'DB_COLUMN', 't_candidate.(allow-list),t_candidate_activity.(allow-list)',
         'candidate', 'ATTRIBUTABLE', 'pii-inventory.md#DB-008'
  UNION ALL SELECT 'SYS_USER_ACCOUNT', 'DB_COLUMN', 'sys_user.(allow-list)', 'sys-user',
         'ATTRIBUTABLE', 'pii-inventory.md#DB-001'
  UNION ALL SELECT 'RESUME_INGESTION_ORIGINAL', 'FILE_OBJECT', 't_resume_ingestion.stored_file_name', 'ingestion-text',
         'ATTRIBUTABLE', 'pii-inventory.md#DB-009'
  UNION ALL SELECT 'RESUME_INGESTION_TEXT', 'DB_COLUMN', 't_resume_ingestion.extracted_text', 'ingestion-text',
         'ATTRIBUTABLE', 'pii-inventory.md#DB-009'
  UNION ALL SELECT 'BP_AVAILABILITY_INGESTION_ORIGINAL', 'FILE_OBJECT', 't_bp_availability_ingestion.stored_file_name',
         'ingestion-text', 'ATTRIBUTABLE', 'pii-inventory.md#DB-010'
  UNION ALL SELECT 'BP_AVAILABILITY_INGESTION_TEXT', 'DB_COLUMN', 't_bp_availability_ingestion.extracted_text',
         'ingestion-text', 'ATTRIBUTABLE', 'pii-inventory.md#DB-010'
  UNION ALL SELECT 'AI_REDACTED_SUMMARY', 'AI_PAYLOAD', 't_ai_recommendation_run.redacted_summary_json', 'ai-log',
         'ATTRIBUTABLE', 'pii-inventory.md#DB-029'
  UNION ALL SELECT 'DOCUMENT:RESUME_INGESTION', 'FILE_OBJECT', 't_document_version.storage_key', 'document-file',
         'ATTRIBUTABLE', 'pii-inventory.md#DB-017'
  UNION ALL SELECT 'INTEGRATION_HUB_IDEMPOTENCY', 'DB_COLUMN', 't_api_idempotency_record', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D7'
  UNION ALL SELECT 'INTEGRATION_HUB_DELIVERY', 'DB_COLUMN', 't_api_delivery', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D7'
  UNION ALL SELECT 'INTEGRATION_HUB_INBOUND', 'DB_COLUMN', 't_inbound_event', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D7'
  UNION ALL SELECT 'INTEGRATION_HUB_INBOUND_REPLAY', 'DB_COLUMN', 't_inbound_event_replay', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D7'
  UNION ALL SELECT 'INTEGRATION_HUB_AUDIT', 'DB_COLUMN', 't_api_delivery_replay_audit', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D7'
  UNION ALL SELECT 'INTEGRATION_HUB_EXTERNAL_AUDIT', 'DB_COLUMN', 't_external_api_audit', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D7'
  UNION ALL SELECT 'EXTERNAL_API_READ_SNAPSHOT', 'DB_COLUMN', 't_api_read_snapshot,t_api_read_snapshot_item', NULL,
         'NOT_ATTRIBUTABLE', 'production-bridge-phased-plan.md#3.4.1-D9'
  UNION ALL SELECT 'AI_LEGACY_LOG', 'DB_COLUMN', 't_ai_log.request_params,t_ai_log.response_text', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-028'
  UNION ALL SELECT 'AI_RECOMMENDATION_ITEM', 'DB_COLUMN', 't_ai_recommendation_item.(score,explanation_json)', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-029'
  UNION ALL SELECT 'PROJECT_INGESTION_TEXT', 'DB_COLUMN', 't_project_ingestion.raw_text', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-033'
  UNION ALL SELECT 'PROJECT_INGESTION_ORIGINAL', 'FILE_OBJECT', 't_project_ingestion.stored_file_name', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-033'
  UNION ALL SELECT 'FILE_SECURITY_METADATA', 'DB_COLUMN', 't_file_security_metadata.(stored_name,owner)', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-023'
  UNION ALL SELECT 'AUDIT_LOG', 'DB_COLUMN', 't_audit_log.(username,uri)', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-019'
  UNION ALL SELECT 'DATABASE_BACKUP', 'LOG_OR_BACKUP', 'backup/replica', NULL,
         'UNKNOWN', 'pii-inventory.md#5'
  UNION ALL SELECT 'AI_EXTERNAL_PROVIDER_COPY', 'AI_PAYLOAD', 'gemini-adapter.request', NULL,
         'UNKNOWN', 'pii-inventory.md#3'
  UNION ALL SELECT 'CLOUDSIGN_REMOTE_DOCUMENT', 'FILE_OBJECT', 'cloudsign.remote-document', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-031'
  UNION ALL SELECT 'FREEE_REMOTE_RECORD', 'FILE_OBJECT', 'freee.remote-record', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-036'
  UNION ALL SELECT 'MAILBOX_SOURCE_MESSAGE', 'FILE_OBJECT', 'mailbox.source-message', NULL,
         'UNKNOWN', 'pii-inventory.md#DB-033'
) d
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
                  WHERE e.tenant_id = t.tenant_id AND e.data_element_id = d.data_element_id);

-- ------------------------------------------------------------
-- 2. 文書種別ごとの data element（§3.4.1 D1〜D3 の DOCUMENT:<document_type>）
--    m_document_type の全種別（論理削除済みを含む。その種別の文書が残り得るため）を入れる。
--    本人を導出できない種別は NOT_ATTRIBUTABLE（DocumentFileProvider.DOCUMENT_TYPE_RULES と同じ。plan §3.4.1 D1）。
-- ------------------------------------------------------------
INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id,
                                subject_attribution, inventory_ref)
SELECT t.tenant_id, CONCAT('DOCUMENT:', dt.code), 'FILE_OBJECT', 't_document_version.storage_key', 'document-file',
       CASE WHEN dt.code IN ('MANAGEMENT_REPORT', 'SERVICE_REQUEST_ATTACHMENT', 'IMPORT')
            THEN 'NOT_ATTRIBUTABLE' ELSE 'ATTRIBUTABLE' END,
       'pii-inventory.md#DB-017'
FROM (SELECT DISTINCT tenant_id FROM sys_user WHERE tenant_id IS NOT NULL AND tenant_id <> '') t
CROSS JOIN (SELECT DISTINCT code FROM m_document_type WHERE code IS NOT NULL AND code <> '') dt
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
                  WHERE e.tenant_id = t.tenant_id AND e.data_element_id = CONCAT('DOCUMENT:', dt.code));
