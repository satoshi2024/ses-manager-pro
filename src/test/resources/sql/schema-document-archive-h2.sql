-- 法定文書台帳 (V67 legal-document-ledger-archive) H2用スキーマ
-- MySQL固有構文(ENGINE,COLLATE,ON UPDATE)を除去したH2互換版

DROP TABLE IF EXISTS t_document_disposal_request;
DROP TABLE IF EXISTS t_document_access_log;
DROP TABLE IF EXISTS t_document_link;
DROP TABLE IF EXISTS t_document_version;
DROP TABLE IF EXISTS t_document;
DROP TABLE IF EXISTS m_document_type;

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
  created_at                 TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at                 TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag               TINYINT       NOT NULL DEFAULT 0
);

CREATE TABLE t_document_version (
  id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
  document_id           BIGINT        NOT NULL,
  version_no            INT           NOT NULL,
  storage_key           VARCHAR(500)  NOT NULL,
  original_name         VARCHAR(500)  NOT NULL,
  content_type          VARCHAR(100),
  size_bytes            BIGINT,
  sha256                CHAR(64)      NOT NULL,
  source_type           VARCHAR(50)   NOT NULL,
  business_key          VARCHAR(200),
  version_discriminator VARCHAR(100),
  external_id           VARCHAR(200),
  scan_status           VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
  change_reason         VARCHAR(500),
  created_by            BIGINT        NOT NULL,
  created_at            TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_version_no UNIQUE (document_id, version_no),
  CONSTRAINT uk_document_idempotency UNIQUE (source_type, business_key, version_discriminator)
);

CREATE TABLE t_document_link (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  document_id  BIGINT       NOT NULL,
  target_type  VARCHAR(50)  NOT NULL,
  target_id    BIGINT       NOT NULL,
  created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_link UNIQUE (document_id, target_type, target_id)
);

CREATE TABLE t_document_access_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  document_id BIGINT       NOT NULL,
  version_id  BIGINT,
  action      VARCHAR(30)  NOT NULL,
  user_id     BIGINT       NOT NULL,
  ip_hash     VARCHAR(64),
  occurred_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE t_document_disposal_request (
  id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
  document_id   BIGINT        NOT NULL,
  requested_by  BIGINT        NOT NULL,
  approved_by   BIGINT,
  status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
  reason        VARCHAR(1000) NOT NULL,
  disposed_at   TIMESTAMP,
  created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT       NOT NULL DEFAULT 0
);
