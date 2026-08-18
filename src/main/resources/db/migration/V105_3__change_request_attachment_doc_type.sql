-- ============================================================
-- 要員セルフサービスポータルV2 変更申請添付の文書種別seed (S14 / V105.3)
-- R2-P1-01: t_engineer_change_request.attachment_document_id が参照する文書台帳の
-- documentType=CHANGE_REQUEST_ATTACHMENT を m_document_type へ追加する。
-- INSERT IGNORE のため既存DBへも安全に適用できる（fresh DBはV105.3到達時にseedされる）。
-- V105/V105.1/V105.2は変更禁止（Flyway checksum保護）のため、後付けseedは新規順方向migrationで行う。
-- ============================================================
INSERT IGNORE INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported) VALUES
  ('CHANGE_REQUEST_ATTACHMENT', '変更申請添付', 'INCOMING', 7, 'TRANSACTION_DATE', 1);