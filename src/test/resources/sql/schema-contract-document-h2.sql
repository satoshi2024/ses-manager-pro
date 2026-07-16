-- 契約書テンプレート・電子署名のH2テスト用スキーマ
CREATE TABLE IF NOT EXISTS m_contract_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  contract_type VARCHAR(30),
  html_content TEXT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  active_flag TINYINT NOT NULL DEFAULT 1,
  created_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS t_contract_document (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  template_version INT NOT NULL,
  rendered_html MEDIUMTEXT NOT NULL,
  pdf_path VARCHAR(500),
  pdf_sha256 CHAR(64),
  cloudsign_document_id VARCHAR(100),
  cloudsign_file_id VARCHAR(100),
  status VARCHAR(20) NOT NULL DEFAULT '下書き',
  recipient_name VARCHAR(100) NOT NULL,
  recipient_email VARCHAR(200) NOT NULL,
  signed_pdf_path VARCHAR(500),
  certificate_path VARCHAR(500),
  sent_at TIMESTAMP,
  completed_at TIMESTAMP,
  last_synced_at TIMESTAMP,
  error_message VARCHAR(1000),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);
