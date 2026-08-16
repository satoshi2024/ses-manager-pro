-- ============================================================
-- V104: 顧客・BP外部ポータル（S13 external-customer-bp-portal / T082 F1）
-- spec: external-customer-bp-portal
--
-- 予約番号はV104（S13）。V1統合baselineへ同じ最終shapeをfresh用に折り込み済み。
-- 本スクリプトはlegacy DB（V103_1適用済み）へ順方向に追加する。
-- 過去のmigrationは変更しない（V5/V70/V103等は不変）。
--
-- 対象: portal組織/portal user/招待token/権限/規約同意テーブル、
--       t_bp_payment.received_confirmed_at（BP受領確認）、
--       m_system_config（portal.terms.current-version / portal.base-domain）、
--       m_menu seed（portal-admin。管理者・営業のみ。design §6.2）。
--
-- 招待tokenの一回性はDB CAS（UPDATE ... WHERE used_at IS NULL）で保証する（design §6.3）。
-- token_hashはSHA-256 hashのみ保存し、平文を保存しない（G3・design §2）。
-- ============================================================

-- ---- 1) ポータル組織（顧客/BP組織の外部identity。customer_id/bp_company_idへ1:1で紐付く） ----
-- 本specはplatform-invariants §2の認可母集団の既定解が適用できない唯一のspecであり、
-- portal userの母集団は本テーブル → customer_id / bp_company_id から独立に導出する（design §6.2）。
CREATE TABLE IF NOT EXISTS m_portal_organization (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT 'テナントID（独立DB方式のため既定default）',
  type          VARCHAR(20)  NOT NULL COMMENT '組織種別: CUSTOMER / BP',
  customer_id   BIGINT                                  COMMENT '顧客ID（type=CUSTOMER時。1顧客1組織）',
  bp_company_id BIGINT                                  COMMENT 'BP会社ID（type=BP時。1BP会社1組織）',
  status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'  COMMENT '状態: ACTIVE / SUSPENDED（停止時は全portal session失効）',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag  TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  UNIQUE KEY uk_portal_org_customer (customer_id),
  UNIQUE KEY uk_portal_org_bp (bp_company_id),
  INDEX idx_portal_org_status (status),
  CONSTRAINT chk_portal_org_type CHECK (type IN ('CUSTOMER','BP')),
  CONSTRAINT chk_portal_org_status CHECK (status IN ('ACTIVE','SUSPENDED')),
  CONSTRAINT fk_portal_org_customer FOREIGN KEY (customer_id) REFERENCES m_customer (id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_portal_org_bp FOREIGN KEY (bp_company_id) REFERENCES m_bp_company (id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータル組織（顧客/BPの外部identity）';

-- ---- 2) ポータルユーザー（内部sys_userとは別identity。全user TOTP MFA必須: G3） ----
-- emailは全組織で一意。loginはemailで解決する（複数組織に同一emailの曖昧loginを作らない）。
-- 論理削除済みemailの再招待はservice側でreactivateする（T083/T086）。
CREATE TABLE IF NOT EXISTS t_portal_user (
  id                     BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  portal_org_id          BIGINT       NOT NULL COMMENT 'ポータル組織ID',
  email                  VARCHAR(255) NOT NULL COMMENT 'login email（全組織で一意）',
  display_name           VARCHAR(255) COMMENT '表示名（招待受諾時に設定）',
  password_hash          VARCHAR(255) COMMENT 'パスワードhash（招待受諾時に設定。BCrypt）',
  status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状態: ACTIVE / SUSPENDED（停止時はsession失効）',
  mfa_policy             VARCHAR(20)  NOT NULL DEFAULT 'REQUIRED' COMMENT 'MFA方針（既定REQUIRED=全user必須）',
  totp_secret_encrypted  VARCHAR(255) COMMENT 'TOTP secret暗号化値（平文は保存しない）',
  totp_secret_key_version VARCHAR(64) COMMENT 'TOTP secretの暗号鍵version',
  mfa_enabled_at         DATETIME     COMMENT 'MFA設定完了日時（NULL=未設定でlogin不可）',
  recovery_code_hash     VARCHAR(255) COMMENT '1回限りrecovery codeのhash',
  recovery_code_used_at  DATETIME     COMMENT 'recovery code使用日時（NULL=未使用）',
  last_login_at          DATETIME     COMMENT '最終login日時',
  version                INT          NOT NULL DEFAULT 0 COMMENT '楽観ロック',
  created_at             DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at             DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag           TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_portal_user_email (email),
  INDEX idx_portal_user_org (portal_org_id),
  INDEX idx_portal_user_status (status),
  CONSTRAINT chk_portal_user_status CHECK (status IN ('ACTIVE','SUSPENDED')),
  CONSTRAINT chk_portal_user_mfa_policy CHECK (mfa_policy IN ('REQUIRED','OPTIONAL')),
  CONSTRAINT fk_portal_user_org FOREIGN KEY (portal_org_id) REFERENCES m_portal_organization (id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータルユーザー';

-- ---- 3) 招待token（72時間有効・1回限り・hash保存・email/組織/権限へ固定: G3） ----
-- 一回性は used_at のDB CAS（UPDATE ... WHERE used_at IS NULL）で保証する（design §6.3）。
-- 有効判定は used_at IS NULL だけでなく、期限・email一致・組織一致の4条件すべてを検証する（design §6.1）。
CREATE TABLE IF NOT EXISTS t_portal_invitation (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  portal_org_id BIGINT       NOT NULL COMMENT 'ポータル組織ID',
  email         VARCHAR(255) NOT NULL COMMENT '招待先email（このemailで受諾する）',
  role          VARCHAR(50)  NOT NULL DEFAULT 'MEMBER' COMMENT '役割: MEMBER / ADMIN（組織管理者。2人目以降の招待は組織管理者の承認が必要: G3）',
  token_hash    CHAR(64)     NOT NULL COMMENT '招待tokenのSHA-256 hash（平文は保存しない）',
  expires_at    DATETIME     NOT NULL COMMENT '有効期限（既定72時間）',
  used_at       DATETIME     COMMENT '使用日時。NULL=未使用（有効と同義ではない。期限を別途確認）',
  accepted_by   BIGINT       COMMENT '受諾後に作成されたportal user ID',
  invited_by    BIGINT       COMMENT '招待者（内部sys_user ID）',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_portal_invite_token_hash (token_hash),
  INDEX idx_portal_invite_org_email (portal_org_id, email),
  INDEX idx_portal_invite_expires (expires_at),
  CONSTRAINT chk_portal_invite_role CHECK (role IN ('MEMBER','ADMIN')),
  CONSTRAINT fk_portal_invite_org FOREIGN KEY (portal_org_id) REFERENCES m_portal_organization (id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータル招待';

-- ---- 4) ポータルユーザー権限（組織種別の既定に加えて管理者が個別付与する） ----
CREATE TABLE IF NOT EXISTS t_portal_user_permission (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  user_id        BIGINT       NOT NULL COMMENT 'portal user ID',
  permission_key VARCHAR(100) NOT NULL COMMENT '権限キー（例: document.view / acceptance.operate / availability.manage / bank-account.request）',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  UNIQUE KEY uk_portal_user_permission (user_id, permission_key),
  CONSTRAINT fk_portal_user_perm_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータルユーザー権限';

-- ---- 5) 利用規約同意（append-only。versionごとに1行。UNIQUEで二重同意を防ぐ: design §6.3） ----
CREATE TABLE IF NOT EXISTS t_portal_terms_consent (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  user_id       BIGINT       NOT NULL COMMENT 'portal user ID',
  terms_version VARCHAR(50)  NOT NULL COMMENT '同意した利用規約version',
  consented_at  DATETIME     NOT NULL COMMENT '同意日時',
  ip_hash       VARCHAR(64)  COMMENT '同意時IPのSHA-256 hash（監査用。R4.2）',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  UNIQUE KEY uk_portal_terms_consent (user_id, terms_version),
  CONSTRAINT fk_portal_terms_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータル利用規約同意';

-- ---- 6) t_bp_payment.received_confirmed_at（BP受領確認。T085 A2で使用） ----
-- t_bp_payment はV5所属でV1統合baselineには含まれないため、V104で情報スキーマガード付きで追加する。
-- （MySQL 8にはADD COLUMN IF NOT EXISTSが無い。V103と同一パターン）
SET @portal_bp_received_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_bp_payment'
      AND COLUMN_NAME = 'received_confirmed_at') = 0,
  'ALTER TABLE t_bp_payment ADD COLUMN received_confirmed_at DATETIME NULL COMMENT ''受領確認日時（BP portalが一度だけ設定。R3.2）''',
  'SELECT 1');
PREPARE portal_bp_received_stmt FROM @portal_bp_received_sql;
EXECUTE portal_bp_received_stmt;
DEALLOCATE PREPARE portal_bp_received_stmt;

-- ---- 7) システム設定（portal用。G3/G8・field-inventory §6.3） ----
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
('portal.terms.current-version', '1', 'ポータル利用規約の現行version（改定時に管理者が更新し、未同意userへ再同意を要求する）'),
('portal.base-domain', 'localhost', 'ポータル公開host（portal.<base-domain>）。本番ではDNS/証明書と合わせて変更する');

-- ---- 8) portal管理メニュー（内部 管理者・営業のみ。design §6.2。HR/要員は不可視） ----
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('portal-admin', 'ポータル管理', '/portal-admin', '/api/portal-admin', 97);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'portal-admin';

-- portal-admin resourceの権限seed。role-adminは全局'*'を持ち個別seed不要。
-- menu付与と一致させ、営業groupだけへ付与する（HR/マネージャー/要員は403）。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'portal-admin.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales');
