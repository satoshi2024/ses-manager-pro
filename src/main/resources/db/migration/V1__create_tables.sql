-- ============================================================
-- SES Manager Pro - データベース定義 (DDL)
-- MySQL 8.0+
-- ファイル: 001_create_tables.sql
-- 説明: 全14テーブルの作成スクリプト
-- ============================================================


-- ============================================================
-- テーブル削除（依存関係の逆順）
-- ============================================================
DROP TABLE IF EXISTS t_notification_read;
DROP TABLE IF EXISTS t_notification;
DROP TABLE IF EXISTS t_monthly_accounting_dimension;
DROP TABLE IF EXISTS t_management_budget;
DROP TABLE IF EXISTS t_user_organization;
DROP TABLE IF EXISTS t_sales_activity;
DROP TABLE IF EXISTS t_role_menu;
DROP TABLE IF EXISTS m_menu;
DROP TABLE IF EXISTS m_system_config;
DROP TABLE IF EXISTS m_email_template;
DROP TABLE IF EXISTS t_ai_log;
DROP TABLE IF EXISTS t_proposal_history;
DROP TABLE IF EXISTS t_bp_payment;
DROP TABLE IF EXISTS t_invoice_item;
DROP TABLE IF EXISTS t_invoice;
DROP TABLE IF EXISTS t_work_record;
DROP TABLE IF EXISTS t_contract;
DROP TABLE IF EXISTS t_proposal;
DROP TABLE IF EXISTS t_project_skill;
DROP TABLE IF EXISTS t_project;
DROP TABLE IF EXISTS t_engineer_skill;
DROP TABLE IF EXISTS m_skill_tag;
DROP TABLE IF EXISTS t_engineer_career;
DROP TABLE IF EXISTS t_engineer;
DROP TABLE IF EXISTS m_cost_center;
DROP TABLE IF EXISTS m_organization_unit;
DROP TABLE IF EXISTS m_customer;
DROP TABLE IF EXISTS sys_user;


-- ============================================================
-- 1. sys_user (システムユーザー)
-- ============================================================
CREATE TABLE sys_user (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  username   VARCHAR(50)  NOT NULL UNIQUE             COMMENT 'ログインID',
  password   VARCHAR(255) NOT NULL                    COMMENT 'パスワード(BCrypt)',
  real_name  VARCHAR(50)                              COMMENT '氏名',
  role       ENUM('管理者','営業','HR','マネージャー') NOT NULL COMMENT '権限ロール',
  email      VARCHAR(100)                             COMMENT 'メールアドレス',
  status     TINYINT      DEFAULT 1                   COMMENT '1:有効 0:無効',
  failed_count INT        DEFAULT 0                   COMMENT 'ログイン失敗回数',
  locked_until DATETIME   NULL                        COMMENT 'アカウントロック解除日時',
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag TINYINT    DEFAULT 0                   COMMENT '論理削除フラグ'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='システムユーザー';


-- ============================================================
-- 2. m_customer (顧客マスタ)
-- ============================================================
CREATE TABLE m_customer (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  company_name      VARCHAR(200) NOT NULL                   COMMENT '会社名',
  company_name_kana VARCHAR(200)                            COMMENT '会社名カナ',
  contact_person    VARCHAR(100)                            COMMENT '担当者名',
  contact_email     VARCHAR(100)                            COMMENT '担当者メールアドレス',
  contact_phone     VARCHAR(20)                             COMMENT '担当者電話番号',
  address           VARCHAR(500)                            COMMENT '住所',
  commercial_flow   VARCHAR(50)                             COMMENT '商流位置(元請/一次請/二次請)',
  trust_level       ENUM('S','A','B','C') DEFAULT 'B'       COMMENT '信頼度ランク',
  remarks           TEXT                                    COMMENT '備考',
  created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag      TINYINT      DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_customer_company_name (company_name),
  INDEX idx_customer_trust_level  (trust_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='顧客マスタ';


-- ============================================================
-- 3. t_engineer (要員テーブル)
-- ============================================================
CREATE TABLE t_engineer (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  full_name          VARCHAR(100) NOT NULL                   COMMENT '氏名',
  full_name_kana     VARCHAR(100)                            COMMENT '氏名カナ',
  initial_name       VARCHAR(10)                             COMMENT 'イニシャル表記',
  gender             ENUM('男性','女性')                      COMMENT '性別',
  birth_date         DATE                                    COMMENT '生年月日',
  nationality        VARCHAR(50)                             COMMENT '国籍',
  nearest_station    VARCHAR(100)                            COMMENT '最寄り駅',
  prefecture         VARCHAR(50)                             COMMENT '最寄り駅の都道府県',
  railway_company    VARCHAR(150)                            COMMENT '最寄り駅の鉄道会社・路線',
  employment_type    ENUM('正社員','契約社員','BP') NOT NULL    COMMENT '雇用形態',
  status             ENUM('稼動中','退場予定','Bench','提案中') NOT NULL DEFAULT 'Bench' COMMENT '稼動ステータス',
  expected_unit_price DECIMAL(10,0)                          COMMENT '希望単価(円)',
  cost_center_id      BIGINT                                 COMMENT '既定原価部門ID',
  organization_id     BIGINT                                 COMMENT '所属組織ID（管理会計の帰属基準。未設定時のみアカウント連携で解決）',
  available_date     DATE                                    COMMENT '稼動可能日',
  experience_years   INT                                     COMMENT '経験年数',
  japanese_level     VARCHAR(20)                             COMMENT '日本語レベル',
  resume_summary     TEXT                                    COMMENT '経歴要約',
  photo_url          VARCHAR(500)                            COMMENT '顔写真URL',
  remarks            TEXT                                    COMMENT '備考',
  created_by         BIGINT                                  COMMENT '登録者ID',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag       TINYINT      DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_engineer_status          (status),
  INDEX idx_engineer_employment_type (employment_type),
  INDEX idx_engineer_available_date  (available_date),
  INDEX idx_engineer_created_by      (created_by),

  CONSTRAINT fk_engineer_created_by
    FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員テーブル';


-- ============================================================
-- 4. t_engineer_career (要員経歴)
-- ============================================================
CREATE TABLE t_engineer_career (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id     BIGINT       NOT NULL                   COMMENT '要員ID',
  period_from     DATE                                    COMMENT '開始時期',
  period_to       DATE                                    COMMENT '終了時期',
  project_name    VARCHAR(200)                            COMMENT 'プロジェクト名',
  client_industry VARCHAR(100)                            COMMENT '業種',
  role            VARCHAR(100)                            COMMENT '担当役割',
  description     TEXT                                    COMMENT '業務内容',
  tech_stack      VARCHAR(500)                            COMMENT '使用技術',
  team_size       INT                                     COMMENT 'チーム規模',

  INDEX idx_career_engineer_id (engineer_id),

  CONSTRAINT fk_career_engineer
    FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員経歴';


-- ============================================================
-- 5. m_skill_tag (スキルタグマスタ)
-- ============================================================
CREATE TABLE m_skill_tag (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  skill_name VARCHAR(100) NOT NULL UNIQUE             COMMENT 'スキル名',
  category   ENUM('言語','FW','DB','クラウド','OS','ツール','その他') COMMENT 'カテゴリ',
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',

  INDEX idx_skill_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スキルタグマスタ';


-- ============================================================
-- 6. t_engineer_skill (要員スキル中間テーブル)
-- ============================================================
CREATE TABLE t_engineer_skill (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id      BIGINT NOT NULL                   COMMENT '要員ID',
  skill_id         BIGINT NOT NULL                   COMMENT 'スキルID',
  proficiency      ENUM('初級','中級','上級') DEFAULT '中級' COMMENT '習熟度',
  experience_years INT                               COMMENT '経験年数',

  UNIQUE KEY uk_engineer_skill (engineer_id, skill_id),

  CONSTRAINT fk_engskill_engineer
    FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_engskill_skill
    FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員スキル中間テーブル';


-- ============================================================
-- 7. t_project (案件テーブル)
-- ============================================================
CREATE TABLE t_project (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  project_name    VARCHAR(200) NOT NULL                   COMMENT '案件名',
  customer_id     BIGINT       NOT NULL                   COMMENT '顧客ID',
  commercial_flow VARCHAR(50)                             COMMENT '商流',
  description     TEXT                                    COMMENT '案件詳細',
  required_count  INT          DEFAULT 1                  COMMENT '募集人数',
  unit_price_min  DECIMAL(10,0)                           COMMENT '単価下限(万円)',
  unit_price_max  DECIMAL(10,0)                           COMMENT '単価上限(万円)',
  work_location   VARCHAR(200)                            COMMENT '勤務地',
  remote_type     ENUM('フル出社','フルリモート','ハイブリッド') COMMENT 'リモート区分',
  start_date      DATE                                    COMMENT '開始予定日',
  end_date        DATE                                    COMMENT '終了予定日',
  status          ENUM('募集中','選考中','充足','クローズ') DEFAULT '募集中' COMMENT 'ステータス',
  priority        ENUM('通常','急募','高利益') DEFAULT '通常' COMMENT '優先度',
  remarks         TEXT                                    COMMENT '備考',
  created_by      BIGINT                                  COMMENT '登録者ID',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag    TINYINT      DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_project_status      (status),
  INDEX idx_project_customer_id (customer_id),
  INDEX idx_project_priority    (priority),
  INDEX idx_project_start_date  (start_date),
  INDEX idx_project_created_by  (created_by),

  CONSTRAINT fk_project_customer
    FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_project_created_by
    FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='案件テーブル';


-- ============================================================
-- 8. t_project_skill (案件要求スキル)
-- ============================================================
CREATE TABLE t_project_skill (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  project_id     BIGINT NOT NULL                   COMMENT '案件ID',
  skill_id       BIGINT NOT NULL                   COMMENT 'スキルID',
  required_level ENUM('初級','中級','上級') DEFAULT '中級' COMMENT '要求レベル',
  is_must        TINYINT DEFAULT 1                 COMMENT '必須フラグ(1:必須 0:尚可)',

  UNIQUE KEY uk_project_skill (project_id, skill_id),

  CONSTRAINT fk_prjskill_project
    FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_prjskill_skill
    FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='案件要求スキル';


-- ============================================================
-- 9. t_proposal (提案テーブル)
-- ============================================================
CREATE TABLE t_proposal (
  id                  BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id         BIGINT       NOT NULL                   COMMENT '要員ID',
  project_id          BIGINT       NOT NULL                   COMMENT '案件ID',
  proposed_unit_price DECIMAL(10,0)                           COMMENT '提案単価(万円)',
  status              ENUM('書類選考中','一次面接','二次面接','結果待ち','成約','見送り') DEFAULT '書類選考中' COMMENT '選考ステータス',
  skill_sheet_path    VARCHAR(500)                            COMMENT 'スキルシートファイルパス',
  proposal_email_text TEXT                                    COMMENT '提案メール本文',
  ai_match_score      DECIMAL(5,2)                            COMMENT 'AIマッチングスコア',
  match_reason        TEXT                                    COMMENT 'マッチング理由',
  remarks             TEXT                                    COMMENT '備考',
  proposed_by         BIGINT                                  COMMENT '提案者ID',
  proposed_at         DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '提案日時',
  closed_at           DATETIME     NULL                       COMMENT 'クローズ日時',
  updated_at          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag        TINYINT      DEFAULT 0                  COMMENT '論理削除フラグ',

  INDEX idx_proposal_status      (status),
  INDEX idx_proposal_engineer_id (engineer_id),
  INDEX idx_proposal_project_id  (project_id),
  INDEX idx_proposal_proposed_by (proposed_by),

  CONSTRAINT fk_proposal_engineer
    FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_proposal_project
    FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_proposal_proposed_by
    FOREIGN KEY (proposed_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提案テーブル';


-- ============================================================
-- 10. t_proposal_history (提案状態履歴)
-- ============================================================
CREATE TABLE t_proposal_history (
  id          BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  proposal_id BIGINT      NOT NULL                   COMMENT '提案ID',
  from_status VARCHAR(50)                            COMMENT '変更前ステータス',
  to_status   VARCHAR(50) NOT NULL                   COMMENT '変更後ステータス',
  changed_by  BIGINT                                 COMMENT '変更者ID',
  changed_at  DATETIME    DEFAULT CURRENT_TIMESTAMP  COMMENT '変更日時',
  remarks     TEXT                                   COMMENT '備考',

  INDEX idx_prophist_proposal_id (proposal_id),
  INDEX idx_prophist_changed_by  (changed_by),

  CONSTRAINT fk_prophist_proposal
    FOREIGN KEY (proposal_id) REFERENCES t_proposal(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_prophist_changed_by
    FOREIGN KEY (changed_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提案状態履歴';


-- ============================================================
-- 11. t_contract (契約テーブル)
-- ============================================================
CREATE TABLE t_contract (
  id                    BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  contract_no           VARCHAR(50)  UNIQUE                    COMMENT '契約番号',
  proposal_id           BIGINT                                 COMMENT '提案ID',
  engineer_id           BIGINT       NOT NULL                  COMMENT '要員ID',
  project_id            BIGINT       NOT NULL                  COMMENT '案件ID',
  customer_id           BIGINT       NOT NULL                  COMMENT '顧客ID',
  contract_type         ENUM('準委任','請負','派遣')            COMMENT '契約形態',
  start_date            DATE         NOT NULL                  COMMENT '契約開始日',
  end_date              DATE                                   COMMENT '契約終了日',
  selling_price         DECIMAL(10,0) NOT NULL                 COMMENT '売上単価(対上)',
  cost_price            DECIMAL(10,0) NOT NULL                 COMMENT '原価単価(対下)',
  cost_center_id        BIGINT                                 COMMENT '契約原価部門ID',
  settlement_hours_min  DECIMAL(5,1)                           COMMENT '精算下限(h)',
  settlement_hours_max  DECIMAL(5,1)                           COMMENT '精算上限(h)',
  fraction_rule         VARCHAR(200)                           COMMENT '端数処理ルール',
  auto_renew            TINYINT      DEFAULT 1                 COMMENT '自動更新(1:する 0:しない)',
  status                ENUM('準備中','稼動中','終了','解約') DEFAULT '準備中' COMMENT '契約ステータス',
  remarks               TEXT                                   COMMENT '備考',
  created_by            BIGINT                                 COMMENT '登録者ID',
  created_at            DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag          TINYINT      DEFAULT 0                 COMMENT '論理削除フラグ',

  INDEX idx_contract_status      (status),
  INDEX idx_contract_engineer_id (engineer_id),
  INDEX idx_contract_project_id  (project_id),
  INDEX idx_contract_customer_id (customer_id),
  INDEX idx_contract_start_date  (start_date),
  INDEX idx_contract_end_date    (end_date),

  CONSTRAINT fk_contract_proposal
    FOREIGN KEY (proposal_id) REFERENCES t_proposal(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_contract_engineer
    FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_contract_project
    FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_contract_customer
    FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約テーブル';


-- ============================================================
-- 12. t_ai_log (AI呼出ログ)
-- ============================================================
CREATE TABLE t_ai_log (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  request_type   ENUM('マッチング','スキルシート','営業メール') COMMENT 'リクエスト種別',
  request_params JSON                                    COMMENT 'リクエストパラメータ',
  response_text  TEXT                                    COMMENT 'レスポンス本文',
  tokens_used    INT                                     COMMENT '使用トークン数',
  cost_jpy       DECIMAL(10,4)                           COMMENT 'コスト(円)',
  created_by     BIGINT                                  COMMENT '実行者ID',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',

  INDEX idx_ailog_request_type (request_type),
  INDEX idx_ailog_created_by   (created_by),
  INDEX idx_ailog_created_at   (created_at),

  CONSTRAINT fk_ailog_created_by
    FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI呼出ログ';


-- ============================================================
-- 13. m_email_template (メールテンプレートマスタ)
-- ============================================================
CREATE TABLE m_email_template (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  template_name    VARCHAR(100) NOT NULL                   COMMENT 'テンプレート名',
  subject_template VARCHAR(500) NOT NULL                   COMMENT '件名テンプレート',
  body_template    TEXT         NOT NULL                   COMMENT '本文テンプレート',
  template_type    ENUM('提案','面接依頼','お礼','フォローアップ','その他') COMMENT 'テンプレート種別',
  created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',

  INDEX idx_emailtpl_type (template_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メールテンプレートマスタ';


-- ============================================================
-- 14. m_system_config (システム設定)
-- ============================================================
CREATE TABLE m_system_config (
  config_key   VARCHAR(100) PRIMARY KEY   COMMENT '設定キー',
  config_value TEXT                       COMMENT '設定値',
  description  VARCHAR(200)              COMMENT '説明'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='システム設定';


-- ============================================================
-- 15. m_menu (メニューマスタ)
-- ============================================================
CREATE TABLE m_menu (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  menu_key    VARCHAR(50)  NOT NULL UNIQUE             COMMENT '画面識別子 (例: engineer, customer, user)',
  menu_name   VARCHAR(100) NOT NULL                    COMMENT '表示名',
  path_prefix VARCHAR(100) NOT NULL                    COMMENT '画面アクセス制御対象のURLプレフィックス (例: /engineer)',
  api_prefix  VARCHAR(100)                             COMMENT 'API アクセス制御対象のURLプレフィックス (例: /api/engineers)',
  sort_order  INT          DEFAULT 0                   COMMENT '表示順',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メニューマスタ';


-- ============================================================
-- 16. t_role_menu (ロール別メニュー権限)
-- ============================================================
CREATE TABLE t_role_menu (
  id      BIGINT      AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  role    VARCHAR(50) NOT NULL                   COMMENT 'sys_user.role と同じ値',
  menu_id BIGINT      NOT NULL                   COMMENT 'm_menu.id',

  UNIQUE KEY uk_role_menu (role, menu_id),
  FOREIGN KEY (menu_id) REFERENCES m_menu(id)
  ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ロール別メニュー権限';


-- ============================================================
-- 17. m_organization_unit (組織マスタ)
-- ============================================================
CREATE TABLE m_organization_unit (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id      BIGINT                                  COMMENT '将来のテナントID（現行は独立DB）',
  legal_entity_id BIGINT                                 COMMENT '法人ID（法人マスタ実装前は外部参照値）',
  code           VARCHAR(50)  NOT NULL                    COMMENT '組織コード',
  name           VARCHAR(200) NOT NULL                    COMMENT '組織名',
  type           VARCHAR(20)  NOT NULL                    COMMENT '組織種別(事業部/部/課/チーム)',
  parent_id      BIGINT                                  COMMENT '親組織ID',
  valid_from     DATE         NOT NULL                    COMMENT '有効開始日',
  valid_to       DATE                                     COMMENT '有効終了日',
  status         VARCHAR(20)  NOT NULL DEFAULT '有効'      COMMENT '状態(有効/無効)',
  merged_into    BIGINT                                  COMMENT '統合先組織ID',
  version        INT          NOT NULL DEFAULT 0           COMMENT '楽観ロック版',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag   TINYINT      NOT NULL DEFAULT 0           COMMENT '論理削除フラグ',

  -- 法人IDはNULL可のため、そのままUNIQUEに含めるとNULL同士が重複扱いにならない。
  -- 既存の cost_center_key と同じ生成列パターンでNULLを0へ畳んでから一意化する。
  -- H2(テスト)は STORED/VIRTUAL キーワードを解釈しないため、ここでは既定(VIRTUAL)のまま定義する。
  legal_entity_key BIGINT AS (COALESCE(legal_entity_id, 0)),
  UNIQUE KEY uk_organization_code (legal_entity_key, code, valid_from),
  INDEX idx_org_parent (parent_id),
  INDEX idx_org_legal_entity_period (legal_entity_id, valid_from, valid_to),
  INDEX idx_org_status (status),
  INDEX idx_org_merged_into (merged_into),
  CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='組織マスタ';


-- ============================================================
-- 18. t_user_organization (ユーザー所属履歴)
-- ============================================================
CREATE TABLE t_user_organization (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  user_id         BIGINT       NOT NULL                    COMMENT 'ユーザーID',
  organization_id BIGINT       NOT NULL                    COMMENT '組織ID',
  position_name   VARCHAR(100)                            COMMENT '役職',
  manager_user_id BIGINT                                  COMMENT '上長ユーザーID',
  primary_flag    TINYINT      NOT NULL DEFAULT 0           COMMENT '主所属フラグ',
  valid_from      DATE         NOT NULL                    COMMENT '所属開始日',
  valid_to        DATE                                     COMMENT '所属終了日',
  version         INT          NOT NULL DEFAULT 0           COMMENT '楽観ロック版',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag    TINYINT      NOT NULL DEFAULT 0           COMMENT '論理削除フラグ',

  -- 「有効な主所属はユーザーごとに1件」をDBでも保証する。終了済み・論理削除済みはNULLへ畳んで
  -- UNIQUEの対象外にし、履歴は何件でも保持できるようにする。
  active_primary_user_id BIGINT AS (CASE WHEN primary_flag = 1 AND valid_to IS NULL AND deleted_flag = 0
                                         THEN user_id ELSE NULL END),
  UNIQUE KEY uk_user_org_active_primary (active_primary_user_id),
  UNIQUE KEY uk_user_org_period (user_id, organization_id, valid_from),
  INDEX idx_user_org_user_period (user_id, valid_from, valid_to),
  INDEX idx_user_org_organization (organization_id),
  INDEX idx_user_org_manager (manager_user_id),
  CONSTRAINT fk_user_org_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_user_org_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_user_org_manager FOREIGN KEY (manager_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ユーザー所属履歴';


-- ============================================================
-- 19. m_cost_center (原価部門マスタ)
-- ============================================================
CREATE TABLE m_cost_center (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  legal_entity_id BIGINT                                  COMMENT '法人ID（法人マスタ実装前は外部参照値）',
  code            VARCHAR(50)  NOT NULL                    COMMENT '原価部門コード',
  name            VARCHAR(200) NOT NULL                    COMMENT '原価部門名',
  organization_id BIGINT                                  COMMENT '既定組織ID',
  valid_from      DATE         NOT NULL                    COMMENT '有効開始日',
  valid_to        DATE                                     COMMENT '有効終了日',
  status          VARCHAR(20)  NOT NULL DEFAULT '有効'      COMMENT '状態(有効/無効)',
  version         INT          NOT NULL DEFAULT 0           COMMENT '楽観ロック版',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag    TINYINT      NOT NULL DEFAULT 0           COMMENT '論理削除フラグ',

  INDEX idx_cost_center_org (organization_id),
  INDEX idx_cost_center_legal_period (legal_entity_id, valid_from, valid_to),
  CONSTRAINT fk_cost_center_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原価部門マスタ';


-- ============================================================
-- 20. t_management_budget (管理会計予算)
-- ============================================================
CREATE TABLE t_management_budget (
  id                BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  organization_id   BIGINT       NOT NULL                    COMMENT '組織ID',
  cost_center_id    BIGINT                                  COMMENT '原価部門ID',
  budget_month      DATE         NOT NULL                    COMMENT '予算月(月初)',
  revenue           DECIMAL(15,0) NOT NULL DEFAULT 0         COMMENT '売上予算(円)',
  gross_profit      DECIMAL(15,0) NOT NULL DEFAULT 0         COMMENT '粗利予算(円)',
  utilization_count INT          NOT NULL DEFAULT 0           COMMENT '稼働人数予算',
  hire_count        INT          NOT NULL DEFAULT 0           COMMENT '採用人数予算',
  version           INT          NOT NULL DEFAULT 0           COMMENT '楽観ロック版',
  created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag      TINYINT      NOT NULL DEFAULT 0           COMMENT '論理削除フラグ',

  cost_center_key BIGINT AS (COALESCE(cost_center_id, 0)),
  UNIQUE KEY uk_management_budget (organization_id, cost_center_key, budget_month),
  INDEX idx_management_budget_month (budget_month),
  CONSTRAINT fk_management_budget_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_management_budget_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理会計予算';


-- ============================================================
-- 21. t_monthly_accounting_dimension (月次帰属snapshot)
-- ============================================================
CREATE TABLE t_monthly_accounting_dimension (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  work_month      DATE         NOT NULL                    COMMENT '対象月(月初)',
  source_type     VARCHAR(50)  NOT NULL                    COMMENT '金額ソース種別',
  source_id       BIGINT       NOT NULL                    COMMENT '金額ソースID',
  organization_id BIGINT                                  COMMENT 'snapshot組織ID',
  cost_center_id  BIGINT                                  COMMENT 'snapshot原価部門ID',
  sales_user_id   BIGINT                                  COMMENT '営業ユーザーID',
  revenue         DECIMAL(15,0) NOT NULL DEFAULT 0         COMMENT '売上(円)',
  cost            DECIMAL(15,0) NOT NULL DEFAULT 0         COMMENT '原価(円)',
  snapshot_at     DATETIME     NOT NULL                    COMMENT 'snapshot確定日時',
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '作成日時',
  updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',

  UNIQUE KEY uk_monthly_accounting_source (work_month, source_type, source_id),
  INDEX idx_monthly_accounting_org (work_month, organization_id),
  INDEX idx_monthly_accounting_cost_center (work_month, cost_center_id),
  CONSTRAINT fk_monthly_accounting_org FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_monthly_accounting_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_monthly_accounting_sales_user FOREIGN KEY (sales_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次管理会計帰属snapshot';


-- ============================================================
-- 法定文書台帳 (V67 legal-document-ledger-archive)
-- ============================================================
DROP TABLE IF EXISTS t_document_access_log;
DROP TABLE IF EXISTS t_document_disposal_request;
DROP TABLE IF EXISTS t_document_link;
DROP TABLE IF EXISTS t_document_version;
DROP TABLE IF EXISTS t_document;
DROP TABLE IF EXISTS m_document_type;

CREATE TABLE m_document_type (
  id                     BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  code                   VARCHAR(50)   NOT NULL COMMENT '種別コード',
  name                   VARCHAR(100)  NOT NULL COMMENT '種別名',
  direction              VARCHAR(10)   NOT NULL COMMENT '方向: OUTGOING/INCOMING/INTERNAL',
  retention_years        INT           NOT NULL COMMENT '法定保存年数',
  retention_start_rule   VARCHAR(50)   NOT NULL COMMENT '起算日ルール',
  legal_hold_supported   TINYINT       NOT NULL DEFAULT 1 COMMENT '法的hold可否',
  created_at             DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag           TINYINT       NOT NULL DEFAULT 0,
  UNIQUE KEY uk_document_type_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書種別マスタ';

CREATE TABLE t_document (
  id                       BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id                VARCHAR(100)  NOT NULL DEFAULT 'default',
  legal_entity_id          VARCHAR(100),
  document_type            VARCHAR(50)   NOT NULL COMMENT '文書種別コード',
  document_no              VARCHAR(100),
  title                    VARCHAR(500),
  counterparty_type        VARCHAR(50),
  counterparty_id          BIGINT,
  counterparty_name_snapshot VARCHAR(200),
  transaction_date         DATE,
  amount                   DECIMAL(15,0),
  currency                 CHAR(3)       NOT NULL DEFAULT 'JPY',
  direction                VARCHAR(10)   NOT NULL,
  status                   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
  retention_until          DATE,
  legal_hold_flag          TINYINT       NOT NULL DEFAULT 0,
  `version`                BIGINT        NOT NULL DEFAULT 1,
  created_by               BIGINT,
  created_at               DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag             TINYINT       NOT NULL DEFAULT 0,
  INDEX idx_document_type      (document_type),
  INDEX idx_document_transaction_date (transaction_date),
  INDEX idx_document_amount    (amount),
  INDEX idx_document_counterparty (counterparty_type, counterparty_id),
  INDEX idx_document_status    (status),
  INDEX idx_document_retention (retention_until),
  INDEX idx_document_tenant    (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書台帳';

CREATE TABLE t_document_version (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id        VARCHAR(100)  NOT NULL DEFAULT 'default' COMMENT 'テナントID',
  document_id      BIGINT        NOT NULL,
  version_no       INT           NOT NULL,
  storage_key      VARCHAR(500)  NOT NULL,
  original_name    VARCHAR(500)  NOT NULL,
  content_type     VARCHAR(100),
  size_bytes       BIGINT,
  sha256           CHAR(64)      NOT NULL,
  source_type      VARCHAR(50)   NOT NULL,
  business_key     VARCHAR(200)  NOT NULL,
  version_discriminator VARCHAR(100) NOT NULL,
  external_id      VARCHAR(200),
  scan_status      VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
  change_reason    VARCHAR(500),
  created_by       BIGINT        NOT NULL,
  created_at       DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag     TINYINT       NOT NULL DEFAULT 0,
  UNIQUE KEY uk_document_version_no (document_id, version_no),
  UNIQUE KEY uk_document_idempotency (tenant_id, source_type, business_key, version_discriminator),
  INDEX idx_dv_document   (document_id),
  INDEX idx_dv_sha256     (sha256),
  INDEX idx_dv_external   (external_id),
  INDEX idx_dv_scan_status (scan_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書版';

CREATE TABLE t_document_link (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
  document_id  BIGINT       NOT NULL,
  target_type  VARCHAR(50)  NOT NULL,
  target_id    BIGINT       NOT NULL,
  created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_document_link (document_id, target_type, target_id),
  INDEX idx_dl_target    (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書業務リンク';

CREATE TABLE t_document_access_log (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  document_id BIGINT       NOT NULL,
  version_id  BIGINT,
  action      VARCHAR(30)  NOT NULL,
  user_id     BIGINT       NOT NULL,
  ip_hash     VARCHAR(64),
  occurred_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_dal_document   (document_id),
  INDEX idx_dal_user       (user_id),
  INDEX idx_dal_occurred   (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書アクセス監査ログ';

CREATE TABLE t_document_disposal_request (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
  document_id   BIGINT       NOT NULL,
  requested_by  BIGINT       NOT NULL,
  approved_by   BIGINT,
  approved_at   DATETIME,
  status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  reason        VARCHAR(1000) NOT NULL,
  disposed_at   DATETIME,
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT      NOT NULL DEFAULT 0,
  INDEX idx_ddr_document (document_id),
  INDEX idx_ddr_status   (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書廃棄申請';

CREATE TABLE IF NOT EXISTS t_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT DEFAULT NULL,
    assignee_user_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    due_date DATE DEFAULT NULL,
    priority VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    target_type VARCHAR(64) DEFAULT NULL,
    target_id BIGINT DEFAULT NULL,
    completed_at DATETIME DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_task_assignee (assignee_user_id),
    INDEX idx_task_requester (requester_user_id),
    INDEX idx_task_status_due (status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='タスク';

CREATE TABLE IF NOT EXISTS m_saved_view (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) DEFAULT NULL,
    owner_user_id BIGINT DEFAULT NULL,
    page_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    filter_json TEXT DEFAULT NULL,
    sort_json TEXT DEFAULT NULL,
    columns_json TEXT DEFAULT NULL,
    page_size INT DEFAULT 20,
    shared_flag TINYINT(1) NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_saved_view_page_owner (page_key, owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='保存ビュー';

CREATE TABLE IF NOT EXISTS t_task_notification_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    notify_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_notify_date (task_id, notify_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='タスク期限通知ログ';

CREATE TABLE IF NOT EXISTS `m_bp_company` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `legal_name` VARCHAR(255) NOT NULL,
    `name_kana` VARCHAR(255),
    `normalized_name` VARCHAR(255),
    `entity_type` VARCHAR(50) NOT NULL COMMENT 'CORPORATE / INDIVIDUAL / FREELANCE / PROVISIONAL',
    `corporate_number` VARCHAR(13),
    `invoice_registration_number` VARCHAR(14),
    `capital_band` VARCHAR(50),
    `employee_band` VARCHAR(50),
    `address` VARCHAR(500),
    `representative` VARCHAR(100),
    `status` VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    `rating` INT DEFAULT 0,
    `primary_sales_user_id` BIGINT,
    `compliance_applicability` VARCHAR(50),
    `applicability_checked_by` BIGINT,
    `applicability_checked_at` DATETIME,
    `applicability_note` TEXT,
    `version` INT NOT NULL DEFAULT 1,
    `created_by` BIGINT,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_company_tenant_status` (`tenant_id`, `status`),
    INDEX `idx_bp_company_corporate_num` (`corporate_number`),
    INDEX `idx_bp_company_invoice_num` (`invoice_registration_number`),
    UNIQUE KEY `uk_bp_company_normalized` (`tenant_id`, `normalized_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP会社マスタ';

CREATE TABLE IF NOT EXISTS `t_bp_contact` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `department` VARCHAR(100),
    `role` VARCHAR(100),
    `email` VARCHAR(255),
    `phone` VARCHAR(50),
    `primary_flag` INT NOT NULL DEFAULT 0,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_contact_company` (`bp_company_id`, `deleted_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP担当者連絡先';

CREATE TABLE IF NOT EXISTS `t_bp_bank_account` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `bank_name` VARCHAR(100),
    `branch_name` VARCHAR(100),
    `account_type` VARCHAR(20) DEFAULT 'ORDINARY',
    `encrypted_account_number` VARCHAR(500),
    `account_holder` VARCHAR(100),
    `masked_label` VARCHAR(100) NOT NULL,
    `valid_from` DATE NOT NULL,
    `valid_to` DATE,
    `approval_status` VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    `approved_by` BIGINT,
    `approved_at` DATETIME,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_bank_company` (`bp_company_id`, `approval_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP口座情報';

CREATE TABLE IF NOT EXISTS `t_bp_terms` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `effective_from` DATE NOT NULL,
    `effective_to` DATE,
    `closing_day` INT NOT NULL DEFAULT 31,
    `payment_month_offset` INT NOT NULL DEFAULT 1,
    `payment_day` INT NOT NULL DEFAULT 30,
    `fee_bearer` VARCHAR(20) NOT NULL DEFAULT 'PAYEE',
    `payment_method` VARCHAR(50) NOT NULL DEFAULT 'BANK_TRANSFER',
    `fee_bearer_exception_reason` VARCHAR(500),
    `fee_bearer_approved_by` BIGINT,
    `fee_bearer_approved_at` DATETIME,
    `max_payment_days` INT NOT NULL DEFAULT 60,
    `version` INT NOT NULL DEFAULT 1,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_terms_company_effective` (`bp_company_id`, `effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP取引条件';

CREATE TABLE IF NOT EXISTS `t_engineer_bp_affiliation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `engineer_id` BIGINT NOT NULL,
    `bp_company_id` BIGINT NOT NULL,
    `valid_from` DATE NOT NULL,
    `valid_to` DATE,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_engineer_bp_affiliation` (`engineer_id`, `valid_from`, `valid_to`),
    INDEX `idx_bp_engineer_affiliation` (`bp_company_id`, `valid_from`, `valid_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP要員所属履歴';

CREATE TABLE IF NOT EXISTS `t_bp_evaluation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `period` VARCHAR(20) NOT NULL,
    `quality_score` INT DEFAULT 0,
    `response_score` INT DEFAULT 0,
    `retention_score` INT DEFAULT 0,
    `compliance_score` INT DEFAULT 0,
    `billing_accuracy_score` INT DEFAULT 0,
    `comment` TEXT,
    `evaluated_by` BIGINT,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_evaluation_company_period` (`bp_company_id`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP評価記録';

CREATE TABLE IF NOT EXISTS `t_bp_price_negotiation` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id` BIGINT NOT NULL DEFAULT 1,
    `bp_company_id` BIGINT NOT NULL,
    `requested_at` DATE NOT NULL,
    `responded_at` DATE,
    `status` VARCHAR(50) NOT NULL DEFAULT 'REQUESTED',
    `requested_amount` DECIMAL(15, 2),
    `agreed_amount` DECIMAL(15, 2),
    `summary` TEXT,
    `document_id` BIGINT,
    `deleted_flag` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_bp_price_neg_company` (`bp_company_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP価格協議記録';

-- ============================================================
-- DDL完了
-- ============================================================
ALTER TABLE t_engineer ADD CONSTRAINT fk_engineer_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
  ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE t_engineer ADD CONSTRAINT fk_engineer_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
  ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE t_contract ADD CONSTRAINT fk_contract_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
  ON UPDATE CASCADE ON DELETE SET NULL;
