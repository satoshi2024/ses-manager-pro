-- ============================================================
-- break-glass incident二者承認とMFA試行制限
-- ============================================================

CREATE TABLE IF NOT EXISTS t_break_glass_incident (
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id              VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  incident_key           VARCHAR(64) NOT NULL COMMENT '外部ticket/correlation用識別子',
  status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/CLOSED/EXPIRED',
  reason                 VARCHAR(500) NOT NULL COMMENT '起票理由（秘密を含めない）',
  idp_outage_confirmed   TINYINT NOT NULL DEFAULT 0 COMMENT 'IdP障害確認済み',
  correlation_id         VARCHAR(100) NOT NULL COMMENT '監査correlation ID',
  requested_by           BIGINT NOT NULL COMMENT '起票者',
  approved_by_1          BIGINT NULL COMMENT '第一承認者',
  approved_at_1          DATETIME NULL COMMENT '第一承認日時',
  approved_by_2          BIGINT NULL COMMENT '第二承認者（第一承認者と別人）',
  approved_at_2          DATETIME NULL COMMENT '第二承認日時',
  enabled_from           DATETIME NULL COMMENT '利用開始日時',
  enabled_until          DATETIME NOT NULL COMMENT '利用終了予定日時',
  closed_by              BIGINT NULL COMMENT '終了実行者',
  closed_at              DATETIME NULL COMMENT '終了日時',
  created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag           TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_break_glass_incident_key UNIQUE (tenant_id, incident_key),
  CONSTRAINT fk_break_glass_requested_by FOREIGN KEY (requested_by) REFERENCES sys_user(id),
  CONSTRAINT fk_break_glass_approved_by_1 FOREIGN KEY (approved_by_1) REFERENCES sys_user(id),
  CONSTRAINT fk_break_glass_approved_by_2 FOREIGN KEY (approved_by_2) REFERENCES sys_user(id),
  CONSTRAINT fk_break_glass_closed_by FOREIGN KEY (closed_by) REFERENCES sys_user(id),
  INDEX idx_break_glass_active (tenant_id, status, enabled_from, enabled_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='break-glass incident承認状態';

CREATE TABLE IF NOT EXISTS t_mfa_attempt_guard (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id          VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT '論理tenant識別子',
  user_id            BIGINT NOT NULL COMMENT 'sys_user.id',
  session_key_hash   CHAR(64) NOT NULL COMMENT 'session識別子のsalt付きhash',
  source_hash        CHAR(64) NOT NULL COMMENT '接続元のsalt付きhash',
  failed_count       INT NOT NULL DEFAULT 0 COMMENT 'window内の失敗回数',
  window_started_at  DATETIME NULL COMMENT '試行window開始',
  locked_until       DATETIME NULL COMMENT '試行拒否期限',
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag       TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  CONSTRAINT uk_mfa_attempt_scope UNIQUE (tenant_id, user_id, session_key_hash, source_hash),
  CONSTRAINT fk_mfa_attempt_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  INDEX idx_mfa_attempt_lock (tenant_id, user_id, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MFA user/session/source試行制限';
