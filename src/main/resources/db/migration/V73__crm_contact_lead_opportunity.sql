-- ==========================================================
-- V73: CRM複数担当者・商機管理 DDL
-- spec: crm-contact-opportunity (S08 T048 / F1)
--
-- 【本スクリプトが唯一の正】
-- 本specで追加するテーブル・カラムは V1__create_tables.sql（統合baseline）へは追記しない。
-- V1へ追記すると、空DB(V1→V73)では V1 が作り V73 が重複定義して ERROR 1050/1060 になり、
-- 既存DB(V71適用済み、V1は再実行されない)とスキーマが分岐する。
-- V73だけを正にすることで fresh / legacy の両経路が必ず同一スキーマへ到達する。
-- t_sales_activity を作る V6 も適用済みのため編集しない（checksum破壊防止）。
-- ==========================================================

-- ============================================================
-- 1. t_customer_contact (顧客担当者)
--    主担当一意: 有効中(status=有効 / valid_to IS NULL / 未削除)の primary_flag=1 は
--    1顧客につき1件。生成列(VIRTUAL)＋UNIQUEで担保する（V60 uk_user_org_active_primary と同形）。
--    STOREDにすると既存DBのALTERがERROR 1215になるため必ずVIRTUALのままにする。
--    期間を閉じた行同士の重なりはDB制約で表現できないため、service層CASで担保する（design §6.1）。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_customer_contact (
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
  active_primary_customer_id BIGINT
    GENERATED ALWAYS AS (
      CASE WHEN primary_flag = 1 AND valid_to IS NULL AND status = '有効' AND deleted_flag = 0
           THEN customer_id ELSE NULL END
    ) VIRTUAL COMMENT '有効中の主担当一意判定用(NULL=対象外)',

  INDEX idx_customer_contact_customer (customer_id),
  INDEX idx_customer_contact_email (email),
  INDEX idx_customer_contact_status (status),
  INDEX idx_customer_contact_valid (customer_id, valid_from, valid_to),
  UNIQUE KEY uk_customer_contact_active_primary (active_primary_customer_id),

  CONSTRAINT fk_customer_contact_customer
    FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='顧客担当者';

-- ============================================================
-- 2. t_lead (リード)
-- ============================================================
CREATE TABLE IF NOT EXISTS t_lead (
  id                       BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  company_name             VARCHAR(200) NOT NULL                   COMMENT '会社名',
  contact_name             VARCHAR(100)                            COMMENT '担当者名',
  contact_email            VARCHAR(255)                            COMMENT '担当者メール',
  contact_phone            VARCHAR(50)                             COMMENT '担当者電話',
  source                   VARCHAR(100)                            COMMENT 'リードソース',
  owner_user_id            BIGINT                                  COMMENT '担当営業ID(NULL=未割当。営業全員から可視)',
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
CREATE TABLE IF NOT EXISTS t_opportunity (
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
  lost_reason            VARCHAR(500)                             COMMENT '失注理由(失注stageでは必須)',
  converted_project_id   BIGINT                                   COMMENT '変換先案件ID',
  converted_quotation_id BIGINT                                   COMMENT '変換先見積ID(NULL=未変換。forecast母集団の判定に使う)',
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
--    t_sales_activity は V6 で作られ V1 には無い。V6は適用済みなので編集せず、
--    fresh / legacy いずれも本ALTERで列を得る（＝両経路で同一形状）。
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
-- 5. t_project / t_quotation: source_opportunity_id (UNIQUEで冪等変換 / design §6.3)
--    受注操作を2回実行してもUNIQUEが2件目を拒否する。CAS＋UNIQUEの二重防御の後段。
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
-- 6. 既存 contact 移行: m_customer.contact_* → t_customer_contact (R1.2)
--    valid_from は顧客の登録日を起点にする。移行実行日(CURDATE())にすると、
--    移行日より前をasOfで読んだとき既存顧客の担当者が0件になる（design §6.1「対象日を含む区間」）。
--    created_at が無い旧行はsentinel 1900-01-01（＝以前から在籍していた扱い）へ倒す。
--    移行は1顧客1件・primary_flag=1・valid_to NULL なので uk_customer_contact_active_primary を満たす。
-- ============================================================
INSERT INTO t_customer_contact
  (customer_id, name, email, phone, primary_flag, valid_from, valid_to, status, version)
SELECT
  c.id,
  c.contact_person,
  c.contact_email,
  c.contact_phone,
  1,
  COALESCE(CAST(c.created_at AS DATE), DATE '1900-01-01'),
  NULL,
  '有効',
  1
FROM m_customer c
WHERE c.contact_person IS NOT NULL
  AND c.contact_person <> ''
  AND c.deleted_flag = 0
  AND NOT EXISTS (
    SELECT 1 FROM t_customer_contact e WHERE e.customer_id = c.id
  );

-- ============================================================
-- 7. CRM メニュー登録 (design §6.2: 管理者 / マネージャー / 営業 のみ可視。
--    HR / 要員 / portal user へは付与しない)
--    m_menu は menu_key / path_prefix / api_prefix を持つフラット構造で、親子階層は無い。
--    t_role_menu は role(sys_user.role と同じ日本語値) × menu_id。
--    action permission は ActionPermissionResolver が URI から機械的に導出するため
--    (`/api/crm/leads` → `crm.view`)、baseline+deny 方式のまま追加のseedは不要。
-- ============================================================
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order, created_at, updated_at) VALUES
('crm-lead', 'リード管理', '/crm/leads', '/api/crm/leads', 240, NOW(), NOW()),
('crm-opportunity', '商機管理', '/crm/opportunities', '/api/crm/opportunities', 250, NOW(), NOW());

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (
    SELECT '管理者' AS role UNION ALL
    SELECT 'マネージャー' AS role UNION ALL
    SELECT '営業' AS role
) r
CROSS JOIN m_menu m
WHERE m.menu_key IN ('crm-lead', 'crm-opportunity');
