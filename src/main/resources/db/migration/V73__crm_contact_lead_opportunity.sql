-- ==========================================================
-- V73: CRM複数担当者・商機管理 DDL
-- spec: crm-contact-opportunity (S08 T048)
-- ==========================================================

-- ============================================================
-- 1. t_customer_contact (顧客担当者)
-- ============================================================
CREATE TABLE t_customer_contact (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  customer_id   BIGINT       NOT NULL                   COMMENT '顧客ID',
  name          VARCHAR(100) NOT NULL                   COMMENT '担当者名',
  name_kana     VARCHAR(100)                            COMMENT '担当者名カナ',
  department    VARCHAR(100)                            COMMENT '部署',
  position      VARCHAR(100)                            COMMENT '役職',
  roles_json    JSON                                    COMMENT '役割(JSON配列: 決裁者/現場/調達/請求/契約)',
  email         VARCHAR(255)                            COMMENT 'メールアドレス',
  phone         VARCHAR(50)                             COMMENT '電話番号',
  primary_flag  TINYINT      DEFAULT 0                  COMMENT '主担当フラグ(1:主担当)',
  valid_from    DATE         NOT NULL                   COMMENT '有効開始日(inclusive)',
  valid_to      DATE                                    COMMENT '有効終了日(inclusive, NULL=無期限)',
  status        VARCHAR(20)  NOT NULL DEFAULT '有効'     COMMENT 'ステータス(有効/退職/異動)',
  version       INT          NOT NULL DEFAULT 1         COMMENT '楽観ロックバージョン',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag  TINYINT      DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_customer_contact_customer (customer_id),
  INDEX idx_customer_contact_email (email),
  INDEX idx_customer_contact_status (status),
  INDEX idx_customer_contact_valid (customer_id, valid_from, valid_to),

  CONSTRAINT fk_customer_contact_customer
    FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='顧客担当者';

-- ============================================================
-- 2. t_lead (リード)
-- ============================================================
CREATE TABLE t_lead (
  id                       BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  company_name             VARCHAR(200) NOT NULL                   COMMENT '会社名',
  contact_name             VARCHAR(100)                            COMMENT '担当者名',
  contact_email            VARCHAR(255)                            COMMENT '担当者メール',
  contact_phone            VARCHAR(50)                             COMMENT '担当者電話',
  source                   VARCHAR(100)                            COMMENT 'リードソース',
  owner_user_id            BIGINT                                  COMMENT '担当営業ID',
  status                   VARCHAR(20)  NOT NULL DEFAULT '未対応'   COMMENT 'ステータス(未対応/対応中/転換済/破棄)',
  converted_customer_id    BIGINT                                  COMMENT '転換先顧客ID',
  converted_opportunity_id BIGINT                                  COMMENT '転換先商機ID',
  version                  INT          NOT NULL DEFAULT 1         COMMENT '楽観ロックバージョン',
  created_at               DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at               DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag             TINYINT      DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_lead_owner (owner_user_id),
  INDEX idx_lead_status (status),
  INDEX idx_lead_company (company_name),
  INDEX idx_lead_email (contact_email),

  CONSTRAINT fk_lead_owner
    FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_lead_converted_customer
    FOREIGN KEY (converted_customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='リード';

-- ============================================================
-- 3. t_opportunity (商機)
-- ============================================================
CREATE TABLE t_opportunity (
  id                     BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  customer_id            BIGINT        NOT NULL                   COMMENT '顧客ID',
  title                  VARCHAR(200)  NOT NULL                   COMMENT '商機名',
  stage                  VARCHAR(30)   NOT NULL DEFAULT '見込'     COMMENT 'ステージ(見込/要件確認/提案準備/見積提出/交渉/受注/失注)',
  expected_start_month   VARCHAR(7)                               COMMENT '開始予定月(YYYY-MM)',
  duration_months        INT                                      COMMENT '想定期間(月)',
  required_count         INT           DEFAULT 1                  COMMENT '募集人数',
  unit_price             DECIMAL(12,0)                            COMMENT '想定単価(円)',
  expected_amount        DECIMAL(14,0)                            COMMENT '見込金額(円)',
  probability            INT                                      COMMENT '確度(%)',
  owner_user_id          BIGINT                                   COMMENT '担当営業ID',
  next_action_date       DATE                                     COMMENT '次回アクション予定日',
  competitor             VARCHAR(500)                             COMMENT '競合情報',
  lost_reason            VARCHAR(500)                             COMMENT '失注理由',
  converted_project_id   BIGINT                                   COMMENT '変換先案件ID',
  converted_quotation_id BIGINT                                   COMMENT '変換先見積ID',
  version                INT           NOT NULL DEFAULT 1         COMMENT '楽観ロックバージョン',
  created_at             DATETIME      DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at             DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag           TINYINT       DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_opportunity_customer (customer_id),
  INDEX idx_opportunity_stage (stage),
  INDEX idx_opportunity_owner (owner_user_id),
  INDEX idx_opportunity_next_action (next_action_date),

  CONSTRAINT fk_opportunity_customer
    FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_opportunity_owner
    FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_opportunity_project
    FOREIGN KEY (converted_project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_opportunity_quotation
    FOREIGN KEY (converted_quotation_id) REFERENCES t_quotation(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商機';

-- ============================================================
-- 4. t_sales_activity 拡張 (contact_id, opportunity_id, assignee_user_id)
-- ============================================================
ALTER TABLE t_sales_activity
  ADD COLUMN contact_id       BIGINT COMMENT '担当者ID' AFTER customer_id,
  ADD COLUMN opportunity_id   BIGINT COMMENT '商機ID' AFTER contact_id,
  ADD COLUMN assignee_user_id BIGINT COMMENT '担当営業ID' AFTER completed_flag,
  ADD INDEX idx_activity_contact (contact_id),
  ADD INDEX idx_activity_opportunity (opportunity_id),
  ADD INDEX idx_activity_assignee (assignee_user_id),
  ADD CONSTRAINT fk_activity_contact
    FOREIGN KEY (contact_id) REFERENCES t_customer_contact(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  ADD CONSTRAINT fk_activity_opportunity
    FOREIGN KEY (opportunity_id) REFERENCES t_opportunity(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  ADD CONSTRAINT fk_activity_assignee
    FOREIGN KEY (assignee_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ============================================================
-- 5. t_project / t_quotation: source_opportunity_id (UNIQUE冪等変換)
-- ============================================================
ALTER TABLE t_project
  ADD COLUMN source_opportunity_id BIGINT COMMENT '商機からの変換元ID' AFTER remarks,
  ADD UNIQUE INDEX uk_project_source_opportunity (source_opportunity_id),
  ADD CONSTRAINT fk_project_source_opportunity
    FOREIGN KEY (source_opportunity_id) REFERENCES t_opportunity(id)
    ON UPDATE CASCADE ON DELETE SET NULL;

ALTER TABLE t_quotation
  ADD COLUMN source_opportunity_id BIGINT COMMENT '商機からの変換元ID' AFTER remarks,
  ADD UNIQUE INDEX uk_quotation_source_opportunity (source_opportunity_id),
  ADD CONSTRAINT fk_quotation_source_opportunity
    FOREIGN KEY (source_opportunity_id) REFERENCES t_opportunity(id)
    ON UPDATE CASCADE ON DELETE SET NULL;

-- ============================================================
-- 6. 既存 contact 移行: m_customer.contact_* → t_customer_contact
-- ============================================================
INSERT INTO t_customer_contact (customer_id, name, email, phone, primary_flag, valid_from, status, version)
SELECT id, contact_person, contact_email, contact_phone, 1, CURDATE(), '有効', 1
FROM m_customer
WHERE contact_person IS NOT NULL
  AND contact_person <> ''
  AND deleted_flag = 0;

-- ============================================================
-- 7. CRM メニュー登録
-- ============================================================
INSERT INTO m_menu (menu_name, menu_path, parent_id, sort_order, icon, menu_type, created_at, updated_at, deleted_flag)
VALUES
  ('CRM', NULL, 0, 25, 'bi-people', 0, NOW(), NOW(), 0);

SET @crm_parent = LAST_INSERT_ID();

INSERT INTO m_menu (menu_name, menu_path, parent_id, sort_order, icon, menu_type, created_at, updated_at, deleted_flag)
VALUES
  ('リード管理', '/crm/leads', @crm_parent, 1, 'bi-person-plus', 1, NOW(), NOW(), 0),
  ('商機管理', '/crm/opportunities', @crm_parent, 2, 'bi-graph-up-arrow', 1, NOW(), NOW(), 0);

-- メニュー権限: 管理者・マネージャー・営業にCRMメニューを付与
INSERT INTO t_role_menu (role_id, menu_id, created_at, updated_at, deleted_flag)
SELECT r.id, m.id, NOW(), NOW(), 0
FROM m_role r
CROSS JOIN m_menu m
WHERE r.role_code IN ('admin', 'manager', 'sales')
  AND m.menu_path IN ('/crm/leads', '/crm/opportunities')
  AND NOT EXISTS (
    SELECT 1 FROM t_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
  );

-- CRM親メニューも同ロールへ
INSERT INTO t_role_menu (role_id, menu_id, created_at, updated_at, deleted_flag)
SELECT r.id, @crm_parent, NOW(), NOW(), 0
FROM m_role r
WHERE r.role_code IN ('admin', 'manager', 'sales')
  AND NOT EXISTS (
    SELECT 1 FROM t_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = @crm_parent
  );
