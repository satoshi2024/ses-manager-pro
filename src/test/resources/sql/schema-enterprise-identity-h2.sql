-- 企業認証・セキュリティ（V63）H2用schema
DROP TABLE IF EXISTS t_file_security_metadata;
DROP TABLE IF EXISTS t_permission_group_action;
DROP TABLE IF EXISTS t_user_permission_group;
DROP TABLE IF EXISTS m_permission_group;
DROP TABLE IF EXISTS t_user_session;
DROP TABLE IF EXISTS t_mfa_recovery_code;
DROP TABLE IF EXISTS t_user_mfa;
DROP TABLE IF EXISTS t_user_external_identity;
DROP TABLE IF EXISTS m_identity_provider;

CREATE TABLE m_identity_provider (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  provider_type VARCHAR(30) NOT NULL,
  issuer_uri VARCHAR(500) NOT NULL,
  metadata_uri VARCHAR(500),
  client_id VARCHAR(255) NOT NULL,
  encrypted_secret_ref VARCHAR(255),
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_identity_provider_issuer UNIQUE (tenant_id, provider_type, issuer_uri)
);

CREATE TABLE t_user_external_identity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  user_id BIGINT NOT NULL,
  provider_id BIGINT NOT NULL,
  subject VARCHAR(255) NOT NULL,
  email_snapshot VARCHAR(255),
  linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_external_identity_subject UNIQUE (tenant_id, provider_id, subject)
);

CREATE TABLE t_user_mfa (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  user_id BIGINT NOT NULL,
  encrypted_totp_secret VARCHAR(1024),
  secret_key_version VARCHAR(64),
  enabled_at TIMESTAMP,
  last_used_step BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_user_mfa_user UNIQUE (tenant_id, user_id)
);

CREATE TABLE t_mfa_recovery_code (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  user_id BIGINT NOT NULL,
  code_hash CHAR(64) NOT NULL,
  hash_algorithm VARCHAR(32) NOT NULL DEFAULT 'SHA-256',
  used_at TIMESTAMP,
  revoked_at TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_mfa_recovery_code_hash UNIQUE (tenant_id, user_id, code_hash)
);

CREATE TABLE t_user_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  session_id_hash CHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  issued_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL,
  idle_expires_at TIMESTAMP NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  ip_hash CHAR(64),
  user_agent VARCHAR(512),
  revoked_at TIMESTAMP,
  revoke_reason VARCHAR(100),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_user_session_hash UNIQUE (tenant_id, session_id_hash)
);

CREATE TABLE m_permission_group (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  group_key VARCHAR(100) NOT NULL,
  group_name VARCHAR(200) NOT NULL,
  description VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_permission_group_key UNIQUE (tenant_id, group_key)
);

CREATE TABLE t_user_permission_group (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  user_id BIGINT NOT NULL,
  group_id BIGINT NOT NULL,
  assigned_by BIGINT,
  assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_user_permission_group UNIQUE (tenant_id, user_id, group_id)
);

CREATE TABLE t_permission_group_action (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  group_id BIGINT NOT NULL,
  action_key VARCHAR(160) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_permission_group_action UNIQUE (tenant_id, group_id, action_key)
);

CREATE TABLE t_file_security_metadata (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  stored_name VARCHAR(255) NOT NULL,
  file_kind VARCHAR(50) NOT NULL,
  owner_type VARCHAR(80),
  owner_id BIGINT,
  storage_state VARCHAR(30) NOT NULL DEFAULT 'QUARANTINED',
  scan_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  scanned_at TIMESTAMP,
  scanner_version VARCHAR(100),
  rejection_reason VARCHAR(500),
  created_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_file_security_stored_name UNIQUE (tenant_id, stored_name)
);
