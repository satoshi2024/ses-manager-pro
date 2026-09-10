-- 法定文書台帳 (V67 legal-document-ledger-archive) H2用スキーマ
-- MySQL固有構文(ENGINE,COLLATE,ON UPDATE)を除去したH2互換版

DROP TABLE IF EXISTS t_document_hash_claim CASCADE;
DROP TABLE IF EXISTS t_document_disposal_request CASCADE;
DROP TABLE IF EXISTS t_document_access_log CASCADE;
DROP TABLE IF EXISTS t_document_link CASCADE;
DROP TABLE IF EXISTS t_document_version CASCADE;
DROP TABLE IF EXISTS t_document CASCADE;
DROP TABLE IF EXISTS m_document_type CASCADE;

CREATE TABLE m_document_type (
  id                     BIGINT        AUTO_INCREMENT PRIMARY KEY,
  code                   VARCHAR(50)   NOT NULL,
  name                   VARCHAR(100)  NOT NULL,
  direction              VARCHAR(10)   NOT NULL,
  retention_years        INT           NOT NULL,
  retention_start_rule   VARCHAR(50)   NOT NULL,
  legal_hold_supported   TINYINT       NOT NULL DEFAULT 1,
  created_at             TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag           TINYINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_type_code UNIQUE (code)
);

INSERT INTO m_document_type
  (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
VALUES
  ('CONTRACT', '契約書', 'OUTGOING', 10, 'CLOSED_AT', 1),
  ('INVOICE_OUT', '請求書（発行）', 'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('INVOICE_IN', '請求書（受領）', 'INCOMING', 10, 'TRANSACTION_DATE', 1),
  ('QUOTATION', '見積書', 'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('WORK_REPORT', '作業報告書', 'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('SIGNED_PDF', '署名済PDF', 'OUTGOING', 10, 'SIGNED_AT', 1),
  ('ESIGN_CERT', '合意締結証明書', 'INCOMING', 10, 'SIGNED_AT', 1),
  ('DISPATCH_LEDGER', '派遣元管理台帳', 'INTERNAL', 3, 'DISPATCH_END', 1),
  ('EMPLOYMENT_CONDITIONS_STATEMENT', '就業条件明示書', 'OUTGOING', 3, 'DISPATCH_END', 1),
  ('DISPATCH_NOTICE', '派遣先通知書', 'OUTGOING', 3, 'DISPATCH_END', 1),
  ('INDIVIDUAL_CONTRACT', '個別契約書', 'OUTGOING', 3, 'DISPATCH_END', 1),
  ('ORDER_RECEIVED', '注文書（受領）', 'INCOMING', 10, 'TRANSACTION_DATE', 1),
  ('ORDER_ACKNOWLEDGEMENT', '注文請書', 'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('ACCEPTANCE', '検収書', 'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('SERVICE_REQUEST_ATTACHMENT', 'サービス依頼添付', 'INCOMING', 10, 'TRANSACTION_DATE', 1);

CREATE TABLE t_document (
  id                         BIGINT        AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100)  NOT NULL DEFAULT 'default',
  legal_entity_id            VARCHAR(100),
  document_type              VARCHAR(50)   NOT NULL,
  document_no                VARCHAR(100),
  title                      VARCHAR(500),
  counterparty_type          VARCHAR(50),
  counterparty_id            BIGINT,
  counterparty_name_snapshot VARCHAR(200),
  transaction_date           DATE,
  amount                     DECIMAL(15,0),
  currency                   CHAR(3)       NOT NULL DEFAULT 'JPY',
  direction                  VARCHAR(10)   NOT NULL,
  status                     VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
  retention_until            DATE,
  legal_hold_flag            TINYINT       NOT NULL DEFAULT 0,
  version                    BIGINT        NOT NULL DEFAULT 1,
  created_by                 BIGINT,
  actor_type                 VARCHAR(20),
  confirmation_source        VARCHAR(40),
  human_user_id              BIGINT,
  correlation_id             VARCHAR(128),
  idempotency_key            VARCHAR(190),
  created_at                 TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at                 TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag               TINYINT       NOT NULL DEFAULT 0
);

CREATE TABLE t_document_version (
  id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100)  NOT NULL DEFAULT 'default',
  document_id           BIGINT        NOT NULL,
  version_no            INT           NOT NULL,
  storage_key           VARCHAR(500)  NOT NULL,
  original_name         VARCHAR(500)  NOT NULL,
  content_type          VARCHAR(100),
  size_bytes            BIGINT,
  sha256                CHAR(64)      NOT NULL,
  source_type           VARCHAR(50)   NOT NULL,
  business_key          VARCHAR(200)  NOT NULL,
  version_discriminator VARCHAR(100) NOT NULL,
  external_id           VARCHAR(200),
  scan_status           VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
  change_reason         VARCHAR(500),
  created_by            BIGINT,
  actor_type            VARCHAR(20),
  confirmation_source   VARCHAR(40),
  human_user_id         BIGINT,
  correlation_id        VARCHAR(128),
  idempotency_key       VARCHAR(190),
  created_at            TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_version_no UNIQUE (document_id, version_no),
  CONSTRAINT uk_document_idempotency UNIQUE (tenant_id, source_type, business_key, version_discriminator)
);

CREATE TABLE t_document_link (
  id                             BIGINT       AUTO_INCREMENT PRIMARY KEY,
  tenant_id                      VARCHAR(100) NOT NULL DEFAULT 'default',
  document_id                    BIGINT       NOT NULL,
  target_type                    VARCHAR(50)  NOT NULL,
  target_id                      BIGINT       NOT NULL,
  skill_sheet_confirmed_at       TIMESTAMP    NULL,
  skill_sheet_confirmed_version   VARCHAR(64)  NULL,
  created_at                     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  updated_at                     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                   TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_link UNIQUE (document_id, target_type, target_id)
);

CREATE TABLE t_document_access_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  document_id BIGINT       NOT NULL,
  version_id  BIGINT,
  action      VARCHAR(30)  NOT NULL,
  user_id     BIGINT,
  actor_type  VARCHAR(20),
  confirmation_source VARCHAR(40),
  human_user_id BIGINT,
  correlation_id VARCHAR(128),
  idempotency_key VARCHAR(190),
  ip_hash     VARCHAR(64),
  occurred_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE t_document_disposal_request (
  id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
  document_id   BIGINT        NOT NULL,
  requested_by  BIGINT        NOT NULL,
  approved_by   BIGINT,
  approved_at   TIMESTAMP,
  status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
  reason        VARCHAR(1000) NOT NULL,
  disposed_at   TIMESTAMP,
  created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_document_hash_claim (
  tenant_id     VARCHAR(100) NOT NULL,
  document_type VARCHAR(50)  NOT NULL,
  sha256        VARCHAR(64)  NOT NULL,
  document_id   BIGINT       NOT NULL,
  created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_type, sha256)
);
