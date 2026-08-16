-- ============================================================
-- V104_1: ポータルsession・TOTP再使用防止（S13 external-customer-bp-portal / T083 F2）
-- spec: external-customer-bp-portal
--
-- t_portal_session: portal専用DB session（G3: portal session cookie名/pathを内部と分離、
-- 停止・MFA reset・password変更時は全sessionを失効させる。B1: 内部管理者がsessionを管理できる）。
-- 生tokenは保存せずSHA-256 hashのみ保存する（invitation tokenと同じ扱い）。
-- 内部のt_user_session（V63）と同じ設計方針。portal userはsys_userでないため別テーブル。
--
-- t_portal_user.last_used_step: 使用済みTOTP step（同一コードの再使用をCASで防ぐ。内部t_user_mfaと同じ）
-- ============================================================

-- ---- 1) t_portal_user.last_used_step ----
SET @portal_last_used_step_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_portal_user'
      AND COLUMN_NAME = 'last_used_step') = 0,
  'ALTER TABLE t_portal_user ADD COLUMN last_used_step BIGINT NULL COMMENT ''最後に受理したTOTP step（同一コードの再使用を拒否）'' AFTER recovery_code_used_at',
  'SELECT 1');
PREPARE portal_last_used_step_stmt FROM @portal_last_used_step_sql;
EXECUTE portal_last_used_step_stmt;
DEALLOCATE PREPARE portal_last_used_step_stmt;

-- ---- 2) ポータルsession ----
CREATE TABLE IF NOT EXISTS t_portal_session (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  user_id       BIGINT       NOT NULL COMMENT 'portal user ID',
  token_hash    CHAR(64)     NOT NULL COMMENT 'session tokenのSHA-256 hash（生tokenは保存しない）',
  issued_at     DATETIME     NOT NULL COMMENT '発行日時',
  last_seen_at  DATETIME     NOT NULL COMMENT '最終アクセス日時',
  idle_expires_at DATETIME   NOT NULL COMMENT 'アイドル期限（未達なら失効）',
  expires_at    DATETIME     NOT NULL COMMENT '絶対期限（既定12時間）',
  ip_hash       VARCHAR(64)  COMMENT '接続元IPのSHA-256 hash（監査用。R4.2）',
  user_agent    VARCHAR(512) COMMENT 'User-Agent（監査・一覧表示用）',
  revoked_at    DATETIME     COMMENT '失効日時。NULL=有効',
  revoked_reason VARCHAR(100) COMMENT '失効理由（LOGOUT / SUSPEND / ORG_SUSPEND / MFA_RESET / ADMIN / EXPIRED）',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  UNIQUE KEY uk_portal_session_token_hash (token_hash),
  INDEX idx_portal_session_user (user_id),
  INDEX idx_portal_session_revoked (revoked_at),
  CONSTRAINT fk_portal_session_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータルセッション';
