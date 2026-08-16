-- テスト用(冪等): V104__external_customer_bp_portal.sql のDDL相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(ENGINE/COLLATE/COMMENT)はH2方言へ読み替える（platform-invariants §4.3）。
-- 共有H2は複数contextでschema-locationsを再実行するため、冪等に再構築する。

-- ---- t_bp_payment.received_confirmed_at（V104。t_bp_paymentはV5 replayで存在） ----
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS received_confirmed_at DATETIME;

-- ---- t_invoice portal列（V104_2。t_invoiceはV5 replayで存在） ----
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS received_confirmed_at DATETIME;
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS payment_expected_date DATE;
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS portal_inquiry VARCHAR(1000);

-- V5 replayのt_invoice.status ENUMには'一部入金'（V28）が無いため、portalのIN絞り込みで
-- H2のENUMチェックに失敗する。H2のENUM型をV28適用後MySQLと同じ値域へ拡張する。
ALTER TABLE t_invoice ALTER COLUMN status SET DATA TYPE ENUM('未送付','送付済','一部入金','入金済');

-- ---- 1) ポータル組織 ----
DROP TABLE IF EXISTS t_portal_session CASCADE;
DROP TABLE IF EXISTS t_portal_terms_consent CASCADE;
DROP TABLE IF EXISTS t_portal_user_permission CASCADE;
DROP TABLE IF EXISTS t_portal_invitation CASCADE;
DROP TABLE IF EXISTS t_portal_user CASCADE;
DROP TABLE IF EXISTS m_portal_organization CASCADE;

CREATE TABLE m_portal_organization (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL DEFAULT 'default',
  type          VARCHAR(20) NOT NULL,
  customer_id   BIGINT,
  bp_company_id BIGINT,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_portal_org_type CHECK (type IN ('CUSTOMER','BP')),
  CONSTRAINT chk_portal_org_status CHECK (status IN ('ACTIVE','SUSPENDED'))
);
CREATE UNIQUE INDEX uk_portal_org_customer ON m_portal_organization(customer_id);
CREATE UNIQUE INDEX uk_portal_org_bp ON m_portal_organization(bp_company_id);
CREATE INDEX idx_portal_org_status ON m_portal_organization(status);

-- ---- 2) ポータルユーザー ----
CREATE TABLE t_portal_user (
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  portal_org_id          BIGINT NOT NULL,
  email                  VARCHAR(255) NOT NULL,
  display_name           VARCHAR(255),
  password_hash          VARCHAR(255),
  status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  mfa_policy             VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
  totp_secret_encrypted  VARCHAR(255),
  totp_secret_key_version VARCHAR(64),
  mfa_enabled_at         DATETIME,
  recovery_code_hash     VARCHAR(255),
  recovery_code_used_at  DATETIME,
  last_used_step         BIGINT,
  last_login_at          DATETIME,
  version                INT NOT NULL DEFAULT 0,
  created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag           TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_portal_user_status CHECK (status IN ('ACTIVE','SUSPENDED')),
  CONSTRAINT chk_portal_user_mfa_policy CHECK (mfa_policy IN ('REQUIRED','OPTIONAL')),
  CONSTRAINT fk_portal_user_org FOREIGN KEY (portal_org_id) REFERENCES m_portal_organization (id)
);
CREATE UNIQUE INDEX uk_portal_user_email ON t_portal_user(email);
CREATE INDEX idx_portal_user_org ON t_portal_user(portal_org_id);
CREATE INDEX idx_portal_user_status ON t_portal_user(status);

-- ---- 3) 招待token ----
CREATE TABLE t_portal_invitation (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  portal_org_id BIGINT NOT NULL,
  email         VARCHAR(255) NOT NULL,
  role          VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
  token_hash    CHAR(64) NOT NULL,
  expires_at    DATETIME NOT NULL,
  used_at       DATETIME,
  accepted_by   BIGINT,
  invited_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_portal_invite_role CHECK (role IN ('MEMBER','ADMIN')),
  CONSTRAINT fk_portal_invite_org FOREIGN KEY (portal_org_id) REFERENCES m_portal_organization (id)
);
CREATE UNIQUE INDEX uk_portal_invite_token_hash ON t_portal_invitation(token_hash);
CREATE INDEX idx_portal_invite_org_email ON t_portal_invitation(portal_org_id, email);
CREATE INDEX idx_portal_invite_expires ON t_portal_invitation(expires_at);

-- ---- 4) ポータルユーザー権限 ----
CREATE TABLE t_portal_user_permission (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT NOT NULL,
  permission_key VARCHAR(100) NOT NULL,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_portal_user_perm_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
);
CREATE UNIQUE INDEX uk_portal_user_permission ON t_portal_user_permission(user_id, permission_key);

-- ---- 5) 利用規約同意 ----
CREATE TABLE t_portal_terms_consent (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL,
  terms_version VARCHAR(50) NOT NULL,
  consented_at  DATETIME NOT NULL,
  ip_hash       VARCHAR(64),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_portal_terms_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
);
CREATE UNIQUE INDEX uk_portal_terms_consent ON t_portal_terms_consent(user_id, terms_version);

-- ---- 6) ポータルsession（V104_1） ----
CREATE TABLE t_portal_session (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL,
  token_hash    CHAR(64) NOT NULL,
  issued_at     DATETIME NOT NULL,
  last_seen_at  DATETIME NOT NULL,
  idle_expires_at DATETIME NOT NULL,
  expires_at    DATETIME NOT NULL,
  ip_hash       VARCHAR(64),
  user_agent    VARCHAR(512),
  revoked_at    DATETIME,
  revoked_reason VARCHAR(100),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_portal_session_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
);
CREATE UNIQUE INDEX uk_portal_session_token_hash ON t_portal_session(token_hash);
CREATE INDEX idx_portal_session_user ON t_portal_session(user_id);
CREATE INDEX idx_portal_session_revoked ON t_portal_session(revoked_at);
