-- ============================================================
-- SES Manager Pro - データベース定義 (DDL)
-- MySQL 8.0+
-- ファイル: 001_create_tables.sql
-- 説明: 全14テーブルの作成スクリプト
-- ============================================================


-- ============================================================
-- テーブル削除（依存関係の逆順）
-- ============================================================
DROP TABLE IF EXISTS m_system_config;
DROP TABLE IF EXISTS m_email_template;
DROP TABLE IF EXISTS t_ai_log;
DROP TABLE IF EXISTS t_proposal_history;
DROP TABLE IF EXISTS t_contract;
DROP TABLE IF EXISTS t_proposal;
DROP TABLE IF EXISTS t_project_skill;
DROP TABLE IF EXISTS t_project;
DROP TABLE IF EXISTS t_engineer_skill;
DROP TABLE IF EXISTS m_skill_tag;
DROP TABLE IF EXISTS t_engineer_career;
DROP TABLE IF EXISTS t_engineer;
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
  employment_type    ENUM('正社員','契約社員','BP') NOT NULL    COMMENT '雇用形態',
  status             ENUM('稼動中','退場予定','Bench','提案中') NOT NULL DEFAULT 'Bench' COMMENT '稼動ステータス',
  expected_unit_price DECIMAL(10,0)                          COMMENT '希望単価(万円)',
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
-- DDL完了
-- ============================================================
