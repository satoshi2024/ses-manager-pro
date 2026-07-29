-- ============================================================
-- V63: 企業認証・MFA・session・action permission・file scan metadata
-- V61/V62は組織・会計履歴で使用済みのため再利用しない。
-- ============================================================

CREATE TABLE IF NOT EXISTS m_identity_provider (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id            VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '独立DBの論理tenant識別子',
  provider_type        VARCHAR(30)  NOT NULL COMMENT 'OIDC等',
  issuer_uri           VARCHAR(500) NOT NULL COMMENT '許可済みissuer',
  metadata_uri         VARCHAR(500) NULL COMMENT 'OIDC discovery metadata URI',
  client_id            VARCHAR(255) NOT NULL COMMENT 'OIDC client ID',
  encrypted_secret_ref VARCHAR(255) NULL COMMENT '暗号化secretのversion付き参照',
  enabled              TINYINT NOT NULL DEFAULT 1 COMMENT '1:有効 0:無効',
  created_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag         TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_identity_provider_issuer UNIQUE (tenant_id, provider_type, issuer_uri),
  INDEX idx_identity_provider_enabled (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部Identity Provider';

CREATE TABLE IF NOT EXISTS t_user_external_identity (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id      VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  user_id        BIGINT NOT NULL COMMENT 'sys_user.id',
  provider_id    BIGINT NOT NULL COMMENT 'm_identity_provider.id',
  subject        VARCHAR(255) NOT NULL COMMENT 'OIDC subject。emailではない',
  email_snapshot VARCHAR(255) NULL COMMENT 'link時点の表示用email',
  linked_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'link日時',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag   TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_external_identity_subject UNIQUE (tenant_id, provider_id, subject),
  CONSTRAINT fk_external_identity_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_external_identity_provider FOREIGN KEY (provider_id) REFERENCES m_identity_provider(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  INDEX idx_external_identity_user (tenant_id, user_id),
  INDEX idx_external_identity_email (tenant_id, email_snapshot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部IDと内部ユーザーの紐付け';

CREATE TABLE IF NOT EXISTS t_user_mfa (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  user_id               BIGINT NOT NULL COMMENT 'sys_user.id',
  encrypted_totp_secret VARCHAR(1024) NULL COMMENT 'version付き暗号化TOTP secret',
  secret_key_version    VARCHAR(64) NULL COMMENT '暗号鍵version',
  enabled_at            DATETIME NULL COMMENT 'MFA有効化日時',
  last_used_step        BIGINT NULL COMMENT '最後に受理したTOTP time step',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_user_mfa_user UNIQUE (tenant_id, user_id),
  CONSTRAINT fk_user_mfa_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  INDEX idx_user_mfa_enabled (tenant_id, enabled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ユーザーMFA設定';

CREATE TABLE IF NOT EXISTS t_mfa_recovery_code (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id      VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  user_id        BIGINT NOT NULL COMMENT 'sys_user.id',
  code_hash      CHAR(64) NOT NULL COMMENT 'recovery codeのhash原文は保存しない',
  hash_algorithm VARCHAR(32) NOT NULL DEFAULT 'SHA-256' COMMENT 'hash方式',
  used_at        DATETIME NULL COMMENT '一回使用済み日時',
  revoked_at     DATETIME NULL COMMENT '再発行時の旧code無効化日時',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag   TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_mfa_recovery_code_hash UNIQUE (tenant_id, user_id, code_hash),
  CONSTRAINT fk_mfa_recovery_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  INDEX idx_mfa_recovery_available (tenant_id, user_id, used_at, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MFA recovery code hash';

CREATE TABLE IF NOT EXISTS t_user_session (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id       VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  session_id_hash CHAR(64) NOT NULL COMMENT 'session IDのhash',
  user_id         BIGINT NOT NULL COMMENT 'sys_user.id',
  issued_at       DATETIME NOT NULL COMMENT '発行日時',
  last_seen_at    DATETIME NOT NULL COMMENT '最終利用日時',
  idle_expires_at DATETIME NOT NULL COMMENT 'idle timeout日時',
  expires_at      DATETIME NOT NULL COMMENT 'max lifetime日時',
  ip_hash         CHAR(64) NULL COMMENT 'IPのhash',
  user_agent      VARCHAR(512) NULL COMMENT 'user agent（秘密を含めない）',
  revoked_at      DATETIME NULL COMMENT '失効日時',
  revoke_reason   VARCHAR(100) NULL COMMENT '失効理由',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_user_session_hash UNIQUE (tenant_id, session_id_hash),
  CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  INDEX idx_user_session_user (tenant_id, user_id, revoked_at),
  INDEX idx_user_session_expiry (tenant_id, idle_expires_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='永続session metadata';

CREATE TABLE IF NOT EXISTS m_permission_group (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id    VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  group_key    VARCHAR(100) NOT NULL COMMENT 'permission group key',
  group_name   VARCHAR(200) NOT NULL COMMENT '表示名',
  description  VARCHAR(500) NULL COMMENT '説明',
  enabled      TINYINT NOT NULL DEFAULT 1 COMMENT '1:有効 0:無効',
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_permission_group_key UNIQUE (tenant_id, group_key),
  INDEX idx_permission_group_enabled (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='権限グループ';

CREATE TABLE IF NOT EXISTS t_user_permission_group (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id    VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  user_id      BIGINT NOT NULL COMMENT 'sys_user.id',
  group_id     BIGINT NOT NULL COMMENT 'm_permission_group.id',
  assigned_by  BIGINT NULL COMMENT '割当実行ユーザー',
  assigned_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '割当日時',
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_user_permission_group UNIQUE (tenant_id, user_id, group_id),
  CONSTRAINT fk_user_permission_group_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_user_permission_group_group FOREIGN KEY (group_id) REFERENCES m_permission_group(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_user_permission_group_assigned_by FOREIGN KEY (assigned_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  INDEX idx_user_permission_group_user (tenant_id, user_id),
  INDEX idx_user_permission_group_group (tenant_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ユーザー権限グループ割当';

CREATE TABLE IF NOT EXISTS t_permission_group_action (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id    VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  group_id     BIGINT NOT NULL COMMENT 'm_permission_group.id',
  action_key   VARCHAR(160) NOT NULL COMMENT 'action permission key',
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_permission_group_action UNIQUE (tenant_id, group_id, action_key),
  CONSTRAINT fk_permission_group_action_group FOREIGN KEY (group_id) REFERENCES m_permission_group(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  INDEX idx_permission_group_action_key (tenant_id, action_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='権限グループaction';

CREATE TABLE IF NOT EXISTS t_file_security_metadata (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id        VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  stored_name      VARCHAR(255) NOT NULL COMMENT 'FileStorageの保存名',
  file_kind        VARCHAR(50) NOT NULL COMMENT 'SKILL_SHEET/PHOTO等',
  owner_type       VARCHAR(80) NULL COMMENT '参照元entity種別',
  owner_id         BIGINT NULL COMMENT '参照元entity ID',
  storage_state    VARCHAR(30) NOT NULL DEFAULT 'QUARANTINED' COMMENT 'QUARANTINED/PUBLISHED/REJECTED',
  scan_status      VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLEAN/INFECTED/UNAVAILABLE/REJECTED',
  scanned_at       DATETIME NULL COMMENT 'scan日時',
  scanner_version  VARCHAR(100) NULL COMMENT 'scanner version',
  rejection_reason VARCHAR(500) NULL COMMENT '拒否理由（秘密・マルウェア内容を含めない）',
  created_by       BIGINT NULL COMMENT 'upload user',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag     TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_file_security_stored_name UNIQUE (tenant_id, stored_name),
  CONSTRAINT fk_file_security_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  INDEX idx_file_security_scan (tenant_id, scan_status, storage_state),
  INDEX idx_file_security_owner (tenant_id, owner_type, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ファイル隔離・scan metadata';
