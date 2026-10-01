-- NF09 document-type validation × BP portal提出物の突合。
-- PortalBpServiceImpl は BP_SUBMISSION を登録するが、V67 シードに未登録のため
-- DocumentServiceImpl.validateDocumentType が 400 で拒否する。正本マスタへ追加する。

INSERT IGNORE INTO m_document_type
  (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
VALUES
  ('BP_SUBMISSION', 'BP提出物', 'INCOMING', 10, 'TRANSACTION_DATE', 1);
