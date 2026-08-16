-- HFP-02: 契約書 CloudSign 本番署名閉ループ（additive schema）
-- t_contract_document は V20 で導入された post-baseline テーブルのため V1 には追加しない。
-- 既存 V20 は編集しない。本migrationは additive のみで、既存ローカルPDF・既存CloudSign IDを保持する。

ALTER TABLE t_contract_document
  ADD COLUMN signed_pdf_sha256 CHAR(64) NULL COMMENT '締結済みPDFのSHA-256(送信原本hashとは別)',
  ADD COLUMN certificate_sha256 CHAR(64) NULL COMMENT '合意締結証明書PDFのSHA-256',
  ADD COLUMN signed_archive_document_id BIGINT NULL COMMENT '文書台帳の署名済みPDF document id',
  ADD COLUMN certificate_archive_document_id BIGINT NULL COMMENT '文書台帳の証明書PDF document id',
  ADD COLUMN cloudsign_participant_id VARCHAR(100) NULL COMMENT '公式participant ID',
  ADD COLUMN cloudsign_status INT NULL COMMENT 'provider raw numeric status(未知値も保存可)',
  ADD COLUMN dispatch_state VARCHAR(40) NOT NULL DEFAULT 'NONE' COMMENT '技術的な配送工程(NONE/QUEUED/CREATING/DOCUMENT_CREATED/UPLOADING/FILE_UPLOADED/ADDING_PARTICIPANT/READY_TO_SEND/SENDING/SENT/COMPLETED/CANCELED/RETRY_WAIT/FAILED_FINAL/RECONCILIATION_REQUIRED)',
  ADD COLUMN operation_id VARCHAR(36) NULL COMMENT '一送信操作のUUID。外部照合markerの元',
  ADD COLUMN send_payload_sha256 CHAR(64) NULL COMMENT 'source/recipient/title/optionsのcanonical hash',
  ADD COLUMN dispatch_attempt_count INT NOT NULL DEFAULT 0 COMMENT 'bounded retry制御',
  ADD COLUMN next_attempt_at DATETIME NULL COMMENT '再試行可能時刻',
  ADD COLUMN claimed_at DATETIME NULL COMMENT 'stale worker検出',
  ADD COLUMN claim_owner VARCHAR(100) NULL COMMENT 'claim中のworker識別子',
  ADD COLUMN last_provider_error_code VARCHAR(40) NULL COMMENT 'PIIを含まない分類code',
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '状態CAS/同期競合防止',
  ADD INDEX idx_contract_doc_dispatch(dispatch_state, next_attempt_at),
  ADD INDEX idx_contract_doc_operation(operation_id);
