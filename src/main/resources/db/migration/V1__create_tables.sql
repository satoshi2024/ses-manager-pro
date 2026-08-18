-- ============================================================
-- SES Manager Pro - データベース定義 (DDL)
-- MySQL 8.0+
-- ファイル: 001_create_tables.sql
-- 説明: 全14テーブルの作成スクリプト
-- ============================================================


-- ============================================================
-- テーブル削除（依存関係の逆順）
-- ============================================================
DROP TABLE IF EXISTS t_portal_access_log;
DROP TABLE IF EXISTS t_portal_session;
DROP TABLE IF EXISTS t_portal_terms_consent;
DROP TABLE IF EXISTS t_portal_user_permission;
DROP TABLE IF EXISTS t_portal_invitation;
DROP TABLE IF EXISTS t_portal_user;
DROP TABLE IF EXISTS m_portal_organization;
DROP TABLE IF EXISTS t_notification_read;
DROP TABLE IF EXISTS t_notification;
DROP TABLE IF EXISTS t_compliance_operation_ledger;
DROP TABLE IF EXISTS t_compliance_mapping_status_event;
DROP TABLE IF EXISTS t_compliance_external_review_adoption_event;
DROP TABLE IF EXISTS t_compliance_external_reviewer_verification_event;
DROP TABLE IF EXISTS t_compliance_reviewer_qualification;
DROP TABLE IF EXISTS t_compliance_external_reviewer_subject;
DROP TABLE IF EXISTS t_compliance_external_review_event;
DROP TABLE IF EXISTS t_compliance_mapping_approval_event;
DROP TABLE IF EXISTS t_compliance_responsible_assignment;
DROP TABLE IF EXISTS m_compliance_mapping_review_requirement_type;
DROP TABLE IF EXISTS m_compliance_mapping_review_requirement_group;
DROP TABLE IF EXISTS m_compliance_external_reviewer_type;
DROP TABLE IF EXISTS m_compliance_verification_method;
DROP TABLE IF EXISTS m_compliance_verification_source;
DROP TABLE IF EXISTS m_compliance_mapping_source;
DROP TABLE IF EXISTS m_compliance_mapping_version;
DROP TABLE IF EXISTS t_document_delivery;
DROP TABLE IF EXISTS t_compliance_finding;
DROP TABLE IF EXISTS t_ledger_work_snapshot;
DROP TABLE IF EXISTS t_notification_difference_history;
DROP TABLE IF EXISTS t_direct_hire_dispute_history;
DROP TABLE IF EXISTS t_planned_introduction_history;
DROP TABLE IF EXISTS t_planned_introduction_terms;
DROP TABLE IF EXISTS t_career_consulting_history;
DROP TABLE IF EXISTS t_training_history;
DROP TABLE IF EXISTS t_employment_stability_history;
DROP TABLE IF EXISTS t_compliance_complaint_history;
DROP TABLE IF EXISTS t_compliance_break_detail;
DROP TABLE IF EXISTS t_compliance_work_calendar;
DROP TABLE IF EXISTS t_compliance_snapshot_operation;
DROP TABLE IF EXISTS t_contract_compliance_worker_state;
DROP TABLE IF EXISTS t_contract_compliance_worker_snapshot;
DROP TABLE IF EXISTS t_contract_compliance_profile;
DROP TABLE IF EXISTS t_contract_compliance_snapshot;
DROP TABLE IF EXISTS m_workplace;
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
DROP TABLE IF EXISTS t_acceptance;
DROP TABLE IF EXISTS t_contract_acceptance_backfill;
DROP TABLE IF EXISTS t_work_record;
DROP TABLE IF EXISTS t_contract;
DROP TABLE IF EXISTS t_sales_order_line;
DROP TABLE IF EXISTS t_sales_order;
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
  phone              VARCHAR(50)                             COMMENT '連絡先電話番号',
  prefecture         VARCHAR(50)                             COMMENT '最寄り駅の都道府県',
  railway_company    VARCHAR(150)                            COMMENT '最寄り駅の鉄道会社・路線',
  employment_type    ENUM('正社員','契約社員','BP') NOT NULL    COMMENT '雇用形態',
  status             ENUM('稼動中','退場予定','Bench','提案中') NOT NULL DEFAULT 'Bench' COMMENT '稼動ステータス',
  expected_unit_price DECIMAL(10,0)                          COMMENT '希望単価(円)',
  cost_center_id      BIGINT                                 COMMENT '既定原価部門ID',
  organization_id     BIGINT                                 COMMENT '所属組織ID（管理会計の帰属基準。未設定時のみアカウント連携で解決）',
  overtime_exempt_flag TINYINT      COMMENT '時間外上限の適用除外フラグ（NULL=HR未確認、確定値のみ設定）',
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
  INDEX idx_engineer_overtime_exempt (overtime_exempt_flag),
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
-- 8.5 t_project_position (案件ポジション/募集枠。staffing-capacity-planning / S12)
-- t_proposal/t_contractのFK先のため、先に定義する。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_project_position (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  project_id         BIGINT       NOT NULL                   COMMENT '案件ID',
  position_no        VARCHAR(50)  NOT NULL                   COMMENT 'ポジション番号（案件内一意）',
  role_name          VARCHAR(200) NOT NULL                   COMMENT '役割名',
  required_count     INT          NOT NULL DEFAULT 1         COMMENT '募集人数',
  skills_json        TEXT                                    COMMENT '必須/歓迎skillのJSON配列',
  unit_price_min     DECIMAL(10,0)                           COMMENT '単価帯下限(円/月)',
  unit_price_max     DECIMAL(10,0)                           COMMENT '単価帯上限(円/月)',
  start_date         DATE                                    COMMENT '開始日（inclusive）',
  end_date           DATE                                    COMMENT '終了日（inclusive・NULL=open end: 計画window末まで）',
  location           VARCHAR(255)                            COMMENT '勤務地',
  allocation_percent DECIMAL(5,2)  NOT NULL DEFAULT 100      COMMENT '想定稼働率(%)',
  priority           VARCHAR(20)                             COMMENT '優先度',
  status             VARCHAR(20)  NOT NULL DEFAULT '募集中'  COMMENT '募集中/候補選定/充足/保留/取消',
  version            INT          NOT NULL DEFAULT 0         COMMENT '楽観ロック',
  created_by         BIGINT                                  COMMENT '作成者ID',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag       TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  UNIQUE KEY uk_project_position_no (project_id, position_no),
  INDEX idx_project_position_status (status),
  INDEX idx_project_position_period (start_date, end_date),
  CONSTRAINT chk_project_position_count CHECK (required_count >= 1),
  CONSTRAINT chk_project_position_percent CHECK (allocation_percent > 0 AND allocation_percent <= 100),
  CONSTRAINT chk_project_position_price CHECK (unit_price_min IS NULL OR unit_price_max IS NULL OR unit_price_min <= unit_price_max),
  CONSTRAINT chk_project_position_period CHECK (end_date IS NULL OR start_date IS NULL OR start_date <= end_date),
  CONSTRAINT chk_project_position_status CHECK (status IN ('募集中','候補選定','充足','保留','取消')),
  CONSTRAINT fk_project_position_project FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='案件ポジション（募集枠）';


-- ============================================================
-- 9. t_proposal (提案テーブル)
-- ============================================================
CREATE TABLE t_proposal (
  id                  BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id         BIGINT       NOT NULL                   COMMENT '要員ID',
  project_id          BIGINT       NOT NULL                   COMMENT '案件ID',
  position_id         BIGINT                                  COMMENT 'ポジションID（staffing-capacity-planning）',
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
  CONSTRAINT fk_proposal_position
    FOREIGN KEY (position_id) REFERENCES t_project_position(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
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
  position_id           BIGINT                                 COMMENT 'ポジションID（staffing-capacity-planning）',
  customer_id           BIGINT       NOT NULL                  COMMENT '顧客ID',
  contract_type         ENUM('準委任','請負','派遣')            COMMENT '契約形態',
  start_date            DATE         NOT NULL                  COMMENT '契約開始日',
  contract_date         DATE                                   COMMENT '委託日（発注年月日）',
  job_description       TEXT                                   COMMENT '役務内容',
  work_location         VARCHAR(255)                           COMMENT '就業場所',
  inspection_due_date   DATE                                   COMMENT '検査完了期日',
  payment_due_date      DATE                                   COMMENT '具体的支払期日',
  payment_method        VARCHAR(100)                           COMMENT '支払方法',
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
  order_line_id         BIGINT                                 COMMENT '注文明細ID（1明細→1契約）',
  acceptance_required   TINYINT      NOT NULL DEFAULT 1        COMMENT '検収要否(1:要 0:不要。未設定を不要にしない)',
  acceptance_exemption_reason VARCHAR(500)                     COMMENT '検収不要理由（acceptance_required=0時は必須。R3.3）',
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
  UNIQUE KEY uk_contract_order_line (order_line_id),

  CONSTRAINT fk_contract_proposal
    FOREIGN KEY (proposal_id) REFERENCES t_proposal(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_contract_engineer
    FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_contract_project
    FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_contract_position
    FOREIGN KEY (position_id) REFERENCES t_project_position(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_contract_customer
    FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT chk_contract_acceptance_exemption
    CHECK (acceptance_required = 1 OR (acceptance_exemption_reason IS NOT NULL AND TRIM(acceptance_exemption_reason) != ''))
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
  INDEX idx_document_tenant    (tenant_id),
  UNIQUE KEY uk_document_tenant_id (tenant_id, id)
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
  UNIQUE KEY uk_document_version_tenant_id (tenant_id, id),
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
  skill_sheet_confirmed_at DATETIME NULL COMMENT 'スキルシート確認日時（NULL=未確認。S14 V105。客先提出前チェックの対象）',
  skill_sheet_confirmed_version VARCHAR(64) NULL COMMENT '確認時のdocument version（S14 V105）',
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
    `suspension_reason` VARCHAR(500),
    `suspension_start_date` DATE,
    `suspension_end_date` DATE,
    `suspension_approved_by` BIGINT,
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
    INDEX `idx_bp_engineer_affiliation` (`bp_company_id`, `valid_from`, `valid_to`),
    UNIQUE KEY `uk_affiliation_eng_from` (`engineer_id`, `valid_from`)
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
-- 28. t_sales_order (注文ヘッダ) / t_sales_order_line (注文明細) / t_acceptance (月次検収)
-- ============================================================
CREATE TABLE t_sales_order (
  id                       BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id                VARCHAR(100)  NOT NULL DEFAULT 'default' COMMENT 'テナントID',
  legal_entity_id          BIGINT        COMMENT '法人ID（将来multi-entity用）',
  order_no                 VARCHAR(30)   NOT NULL COMMENT '注文番号',
  customer_po_no           VARCHAR(100)  COMMENT '顧客PO番号',
  customer_id              BIGINT        NOT NULL COMMENT '顧客ID',
  contact_id               BIGINT        COMMENT '顧客担当者ID',
  quotation_id             BIGINT        COMMENT '生成元見積ID',
  order_date               DATE          NOT NULL COMMENT '注文日',
  start_date               DATE          COMMENT '期間開始日',
  end_date                 DATE          COMMENT '期間終了日',
  status                   VARCHAR(20)   NOT NULL DEFAULT '下書き' COMMENT '状態: 下書き/受領確認/注文請提出/契約化/完了/取消',
  total_amount_snapshot    DECIMAL(15,0) COMMENT '注文確定時点の総額snapshot（下書きはNULL）',
  payment_terms_snapshot   VARCHAR(200)  COMMENT '注文確定時点の支払条件snapshot（下書きはNULL）',
  source_document_id       BIGINT        COMMENT '受領注文書document ID',
  acknowledgement_document_id BIGINT      COMMENT '注文請書document ID',
  version                  INT           NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
  created_by               BIGINT        COMMENT '登録者ID',
  created_at               DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag             TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_sales_order_no (order_no),
  INDEX idx_sales_order_customer (customer_id),
  INDEX idx_sales_order_po (customer_id, customer_po_no),
  INDEX idx_sales_order_date (order_date),
  INDEX idx_sales_order_status (status),
  CONSTRAINT fk_sales_order_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注文テーブル';

CREATE TABLE t_sales_order_line (
  id             BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  order_id       BIGINT        NOT NULL COMMENT '注文ID',
  line_no        INT           NOT NULL COMMENT '明細番号',
  project_id     BIGINT        COMMENT '案件ID',
  engineer_id    BIGINT        NOT NULL COMMENT '要員ID',
  quantity       INT           NOT NULL DEFAULT 1 COMMENT '数量',
  unit_price     DECIMAL(12,0) NOT NULL COMMENT '単価(円/月)',
  settlement_min DECIMAL(5,1)  COMMENT '精算下限(h)',
  settlement_max DECIMAL(5,1)  COMMENT '精算上限(h)',
  amount         DECIMAL(12,0) COMMENT '明細金額(円)',
  remarks        VARCHAR(500)  COMMENT '備考',
  created_at     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag   TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_sales_order_line (order_id, line_no),
  INDEX idx_sales_order_line_engineer (engineer_id),
  INDEX idx_sales_order_line_project (project_id),
  CONSTRAINT fk_sales_order_line_order FOREIGN KEY (order_id) REFERENCES t_sales_order(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_sales_order_line_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_sales_order_line_project FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注文明細テーブル';

CREATE TABLE t_acceptance (
  id                   BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  contract_id          BIGINT        NOT NULL COMMENT '契約ID',
  work_record_id       BIGINT        COMMENT '対象work record ID',
  work_month           CHAR(7)       NOT NULL COMMENT '対象月(YYYY-MM)',
  status               VARCHAR(20)   NOT NULL DEFAULT '未提出' COMMENT '状態: 未提出/提出済/検収済/差戻し',
  submitted_at         DATETIME      COMMENT '提出日時',
  customer_contact_id  BIGINT        COMMENT '顧客確認者ID',
  customer_contact_name_snapshot VARCHAR(100) COMMENT '顧客確認者名snapshot（検収実行時点。改名後も不変）',
  accepted_at          DATETIME      COMMENT '検収日時',
  reject_comment       VARCHAR(500)  COMMENT '差戻し理由',
  document_id          BIGINT        COMMENT '検収書document ID',
  hours_snapshot       DECIMAL(6,2)  COMMENT '提出時点の工数snapshot',
  amount_snapshot      DECIMAL(12,0) COMMENT '提出時点の請求金額snapshot',
  work_record_updated_at DATETIME    COMMENT '提出時点のwork record更新日時（version代用）',
  version              INT           NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
  created_by           BIGINT        COMMENT '登録者ID',
  created_at           DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag         TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_acceptance_contract_month (contract_id, work_month),
  INDEX idx_acceptance_work_record (work_record_id),
  INDEX idx_acceptance_status_month (status, work_month),
  CONSTRAINT fk_acceptance_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次検収テーブル';


-- ============================================================
-- 29. t_contract_acceptance_backfill (V80 legacy backfillのrepair-safe marker)
-- ============================================================
CREATE TABLE t_contract_acceptance_backfill (
  contract_id   BIGINT       PRIMARY KEY COMMENT 'V80適用時点の既存契約ID（0=sentinel）。検収不要へ移行対象',
  backfilled_at DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'marker登録日時'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='V80 legacy backfill marker';

-- ============================================================
-- 30. t_document_hash_claim (文書HashアトミックClaim)
-- ============================================================
CREATE TABLE IF NOT EXISTS t_document_hash_claim (
  tenant_id     VARCHAR(100) NOT NULL COMMENT 'テナントID',
  document_type VARCHAR(50)  NOT NULL COMMENT '文書種別',
  sha256        VARCHAR(64)  NOT NULL COMMENT 'ファイルHash (SHA-256)',
  document_id   BIGINT       NOT NULL COMMENT '関連文書ID',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_type, sha256),
  CONSTRAINT fk_document_hash_claim_document FOREIGN KEY (document_id) REFERENCES t_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書HashアトミックClaimテーブル';


-- ============================================================
-- 36. 雇用勤怠・休暇・時間外コンプライアンス（attendance-leave-overtime-compliance / S11）
--     V83の増分migrationと同じ最終shapeを統合baselineにも保持する。
-- ============================================================

CREATE TABLE IF NOT EXISTS m_work_calendar (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  legal_entity_id BIGINT COMMENT '法人ID（法人マスタ実装前は外部参照値）',
  organization_id BIGINT COMMENT '組織scope（NULLは法人既定）',
  engineer_id     BIGINT COMMENT '個人scope（NULLは組織/法人既定）',
  name            VARCHAR(200) NOT NULL COMMENT 'カレンダー名',
  valid_from      DATE NOT NULL COMMENT '有効開始日(inclusive)',
  valid_to        DATE COMMENT '有効終了日(inclusive)',
  status          VARCHAR(20) NOT NULL DEFAULT '有効' COMMENT '状態',
  version         INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag    TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  INDEX idx_work_calendar_scope (legal_entity_id, organization_id, engineer_id, valid_from, valid_to),
  INDEX idx_work_calendar_period (valid_from, valid_to),
  CONSTRAINT chk_work_calendar_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT fk_work_calendar_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_work_calendar_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='勤務カレンダーバージョン';

CREATE TABLE IF NOT EXISTS m_work_calendar_day (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  calendar_id       BIGINT NOT NULL COMMENT '勤務カレンダーID',
  calendar_date     DATE NOT NULL COMMENT '対象日',
  day_type          VARCHAR(30) NOT NULL COMMENT '所定日/所定休日/法定休日等',
  scheduled_minutes INT COMMENT '所定時間（NULL=所定なし、0=所定日だが0分）',
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  UNIQUE KEY uk_work_calendar_day (calendar_id, calendar_date),
  INDEX idx_work_calendar_day_date (calendar_date),
  CONSTRAINT chk_work_calendar_day_minutes CHECK (scheduled_minutes IS NULL OR scheduled_minutes >= 0),
  CONSTRAINT fk_work_calendar_day_calendar FOREIGN KEY (calendar_id) REFERENCES m_work_calendar(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='勤務カレンダー日別定義';

CREATE TABLE IF NOT EXISTS t_employee_attendance (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id           BIGINT NOT NULL COMMENT '要員ID',
  legal_entity_id       BIGINT COMMENT '法人ID',
  organization_id       BIGINT COMMENT '組織ID（scope snapshot）',
  work_calendar_id      BIGINT COMMENT '勤務カレンダー版ID',
  work_date             DATE NOT NULL COMMENT '勤務日（跨夜も始業日の属する日）',
  clock_in              TIME COMMENT '始業時刻',
  clock_out             TIME COMMENT '終業時刻',
  break_minutes         INT NOT NULL DEFAULT 0 COMMENT '休憩分',
  regular_minutes       INT NOT NULL DEFAULT 0 COMMENT '法定内労働分',
  overtime_minutes      INT NOT NULL DEFAULT 0 COMMENT '時間外労働分（法定休日を含まない）',
  holiday_minutes       INT NOT NULL DEFAULT 0 COMMENT '法定休日労働分',
  late_night_minutes    INT NOT NULL DEFAULT 0 COMMENT '深夜労働分',
  work_type             VARCHAR(30) NOT NULL DEFAULT '通常' COMMENT '勤務区分',
  workplace_type        VARCHAR(30) COMMENT '勤務地区分',
  source                VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'manual/system/freee/import',
  source_external_id    VARCHAR(200) COMMENT '外部sourceの冪等ID',
  status                VARCHAR(20) NOT NULL DEFAULT '入力中' COMMENT '勤怠行状態',
  remarks               VARCHAR(500) COMMENT '備考',
  version               INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag          TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_employee_attendance_source (source, source_external_id),
  INDEX idx_employee_attendance_engineer_date (engineer_id, work_date),
  INDEX idx_employee_attendance_month (work_date, engineer_id),
  INDEX idx_employee_attendance_scope (legal_entity_id, organization_id, work_date),
  CONSTRAINT chk_employee_attendance_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source IN ('freee', 'import') AND source_external_id IS NOT NULL)
  ),
  CONSTRAINT chk_employee_attendance_minutes CHECK (
    break_minutes >= 0 AND regular_minutes >= 0 AND overtime_minutes >= 0
    AND holiday_minutes >= 0 AND late_night_minutes >= 0
  ),
  CONSTRAINT fk_employee_attendance_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_employee_attendance_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_employee_attendance_calendar FOREIGN KEY (work_calendar_id) REFERENCES m_work_calendar(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='雇用勤怠日次原簿';

-- 方式A（S11/R2-P1-02）: 休憩区間は勤務開始を0とする整数分offsetで保存し、
-- break_minutesは区間合計から導出する（V91と同じ最終shapeを統合baselineに保持）。
CREATE TABLE IF NOT EXISTS t_employee_attendance_break (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  attendance_id         BIGINT NOT NULL COMMENT '雇用勤怠日次行ID',
  sequence_no           INT NOT NULL COMMENT '開始offset昇順の区間番号（1始まり）',
  start_offset_minutes  INT NOT NULL COMMENT '勤務開始を0とする休憩開始offset（分）',
  end_offset_minutes    INT NOT NULL COMMENT '勤務開始を0とする休憩終了offset（分）',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  UNIQUE KEY uk_employee_attendance_break (attendance_id, sequence_no),
  INDEX idx_employee_attendance_break_attendance (attendance_id),
  CONSTRAINT chk_employee_attendance_break_offset CHECK (
    start_offset_minutes >= 0 AND end_offset_minutes > start_offset_minutes
  ),
  CONSTRAINT fk_employee_attendance_break_attendance FOREIGN KEY (attendance_id)
    REFERENCES t_employee_attendance(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='雇用勤怠日次の休憩区間（方式A）';

CREATE TABLE IF NOT EXISTS t_attendance_month (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id           BIGINT NOT NULL COMMENT '要員ID',
  legal_entity_id       BIGINT COMMENT '法人ID',
  organization_id       BIGINT COMMENT '組織ID（scope snapshot）',
  work_month            DATE NOT NULL COMMENT '対象月（月初）',
  scheduled_minutes     INT NOT NULL DEFAULT 0 COMMENT '所定分',
  worked_minutes        INT NOT NULL DEFAULT 0 COMMENT '実働分',
  regular_minutes       INT NOT NULL DEFAULT 0 COMMENT '法定内労働分',
  overtime_minutes      INT NOT NULL DEFAULT 0 COMMENT '時間外労働分',
  holiday_minutes       INT NOT NULL DEFAULT 0 COMMENT '法定休日労働分',
  late_night_minutes    INT NOT NULL DEFAULT 0 COMMENT '深夜労働分',
  leave_minutes         INT NOT NULL DEFAULT 0 COMMENT '休暇分',
  status                VARCHAR(20) NOT NULL DEFAULT '入力中' COMMENT '入力中/提出済/承認済/差戻し/締め済',
  submitted_at          DATETIME COMMENT '提出日時',
  submitted_by          BIGINT COMMENT '提出者',
  approved_at           DATETIME COMMENT '承認日時',
  approved_by           BIGINT COMMENT '承認者',
  closed_at             DATETIME COMMENT '締め日時',
  closed_by             BIGINT COMMENT '締め担当者',
  close_reason          VARCHAR(500) COMMENT '再open等の理由',
  version               INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag          TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_attendance_month_engineer (engineer_id, work_month),
  INDEX idx_attendance_month_scope (legal_entity_id, organization_id, work_month),
  CONSTRAINT chk_attendance_month_month_start CHECK (DAYOFMONTH(work_month) = 1),
  CONSTRAINT chk_attendance_month_minutes CHECK (
    scheduled_minutes >= 0 AND worked_minutes >= 0 AND regular_minutes >= 0
    AND overtime_minutes >= 0 AND holiday_minutes >= 0 AND late_night_minutes >= 0
    AND leave_minutes >= 0
  ),
  CONSTRAINT fk_attendance_month_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_attendance_month_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='雇用勤怠月次snapshot';

CREATE TABLE IF NOT EXISTS t_leave_request (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id           BIGINT NOT NULL COMMENT '要員ID',
  legal_entity_id       BIGINT COMMENT '法人ID',
  organization_id       BIGINT COMMENT '申請時組織scope',
  leave_type            VARCHAR(30) NOT NULL COMMENT '有給/半休/時間休/代休/欠勤/特別休暇',
  start_date            DATE NOT NULL COMMENT '休暇開始日',
  end_date              DATE NOT NULL COMMENT '休暇終了日',
  start_time            TIME COMMENT '時間休開始時刻',
  end_time              TIME COMMENT '時間休終了時刻',
  requested_minutes     INT NOT NULL COMMENT '申請分',
  reason                VARCHAR(500) COMMENT '理由',
  status                VARCHAR(20) NOT NULL DEFAULT '申請中' COMMENT '申請中/承認済/却下/取消',
  approval_request_id   BIGINT COMMENT 'approval engine request ID',
  version               INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_by            BIGINT COMMENT '申請者',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag          TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  INDEX idx_leave_request_engineer_period (engineer_id, start_date, end_date),
  INDEX idx_leave_request_status (status),
  INDEX idx_leave_request_approval (approval_request_id),
  CONSTRAINT chk_leave_request_period CHECK (start_date <= end_date),
  CONSTRAINT chk_leave_request_minutes CHECK (requested_minutes > 0),
  CONSTRAINT fk_leave_request_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_leave_request_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_leave_request_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='休暇申請';

-- 休暇残数付与/消化台帳（G6: 本システムが正、V98と同じ最終shapeを統合baselineに保持）。
CREATE TABLE IF NOT EXISTS t_leave_ledger (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id         BIGINT NOT NULL COMMENT '要員ID',
  legal_entity_id     BIGINT COMMENT '法人ID（scope snapshot）',
  leave_type          VARCHAR(30) NOT NULL COMMENT '有給/半休/時間休/代休/欠勤/特別休暇',
  ledger_type         VARCHAR(20) NOT NULL COMMENT 'GRANT（付与）/CONSUME（消化）',
  amount_minutes      INT NOT NULL COMMENT '分単位の付与/消化量（正の整数）',
  entry_date          DATE NOT NULL COMMENT '付与/消化の発生日',
  leave_request_id    BIGINT COMMENT '消化の由来となる休暇申請ID（GRANTはNULL）',
  source              VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'manual/system/import',
  source_external_id  VARCHAR(200) COMMENT '外部sourceの冪等ID',
  remarks             VARCHAR(500) COMMENT '備考',
  version             INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag        TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_leave_ledger_source (source, source_external_id),
  INDEX idx_leave_ledger_engineer_type (engineer_id, leave_type, entry_date),
  INDEX idx_leave_ledger_request (leave_request_id),
  CONSTRAINT chk_leave_ledger_type CHECK (ledger_type IN ('GRANT', 'CONSUME')),
  CONSTRAINT chk_leave_ledger_amount CHECK (amount_minutes > 0),
  CONSTRAINT chk_leave_ledger_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source = 'import' AND source_external_id IS NOT NULL)
  ),
  CONSTRAINT fk_leave_ledger_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_leave_ledger_request FOREIGN KEY (leave_request_id) REFERENCES t_leave_request(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='休暇残数付与/消化台帳（G6: 本システムが正）';

CREATE TABLE IF NOT EXISTS m_overtime_agreement (
  id                                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  legal_entity_id                     BIGINT NOT NULL COMMENT '法人ID',
  valid_from                          DATE NOT NULL COMMENT '協定年度起算月（月初）',
  valid_to                            DATE COMMENT '協定適用終了日',
  special_clause                     TINYINT NOT NULL DEFAULT 0 COMMENT '特別条項有無',
  normal_month_limit_minutes         INT COMMENT '法人別 月時間外上限分',
  normal_year_limit_minutes          INT COMMENT '法人別 年時間外上限分',
  special_year_limit_minutes         INT COMMENT '法人別 特別条項年上限分',
  total_month_limit_minutes          INT COMMENT '法人別 月合計上限分',
  multi_month_average_limit_minutes  INT COMMENT '法人別 複数月平均上限分',
  exceed_month_count_limit           INT COMMENT '法人別 45時間超月数上限',
  warning_threshold_percent           INT COMMENT '法人別 予兆閾値(%)',
  warning_recipients                  VARCHAR(100) COMMENT '法人別通知先',
  config_json                         TEXT COMMENT 'その他協定設定（未知値を推測して列化しない）',
  version                             INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at                          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag                        TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_overtime_agreement_period (legal_entity_id, valid_from),
  INDEX idx_overtime_agreement_lookup (legal_entity_id, valid_from, valid_to),
  CONSTRAINT chk_overtime_agreement_month_start CHECK (DAYOFMONTH(valid_from) = 1),
  CONSTRAINT chk_overtime_agreement_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT chk_overtime_agreement_limits CHECK (
    (normal_month_limit_minutes IS NULL OR normal_month_limit_minutes >= 0)
    AND (normal_year_limit_minutes IS NULL OR normal_year_limit_minutes >= 0)
    AND (special_year_limit_minutes IS NULL OR special_year_limit_minutes >= 0)
    AND (total_month_limit_minutes IS NULL OR total_month_limit_minutes >= 0)
    AND (multi_month_average_limit_minutes IS NULL OR multi_month_average_limit_minutes >= 0)
    AND (exceed_month_count_limit IS NULL OR exceed_month_count_limit >= 0)
    AND (warning_threshold_percent IS NULL OR warning_threshold_percent BETWEEN 0 AND 100)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='法人別36協定';

CREATE TABLE IF NOT EXISTS t_overtime_followup (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id           BIGINT NOT NULL COMMENT '要員ID',
  period_month          DATE NOT NULL COMMENT '判定対象月（月初）',
  warning_code          VARCHAR(50) NOT NULL COMMENT '警告コード',
  status                VARCHAR(20) NOT NULL DEFAULT '未対応' COMMENT '未対応/通知済/対応中/完了',
  notified_at           DATETIME COMMENT '通知日時',
  health_action_status  VARCHAR(30) COMMENT '健康対応状態（診療詳細は保存しない）',
  version               INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag          TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  UNIQUE KEY uk_overtime_followup (engineer_id, period_month, warning_code),
  INDEX idx_overtime_followup_period (period_month, status),
  CONSTRAINT chk_overtime_followup_month_start CHECK (DAYOFMONTH(period_month) = 1),
  CONSTRAINT fk_overtime_followup_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='時間外コンプライアンスfollow-up';

-- ============================================================
-- 派遣・準委任コンプライアンス台帳（S10 / T061 F1 R5 rework）
-- snapshotはUNIQUE(contract_id,snapshot_version)、content hashは非一意索引。
-- mutable current profileとappend-only snapshot/historyを分離し、
-- マスタ変更・profile改定・history訂正後も過去帳票を再生成できる。
-- ============================================================
CREATE TABLE m_workplace (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '就業事業所ID',
  tenant_id         VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  customer_id       BIGINT NOT NULL COMMENT '顧客ID',
  organization_id   BIGINT COMMENT '組織scope',
  name              VARCHAR(200) NOT NULL COMMENT '事業所名',
  address           VARCHAR(500) COMMENT '所在地',
  organization_unit VARCHAR(200) COMMENT '組織単位名',
  phone             VARCHAR(50) COMMENT '連絡先',
  valid_from        DATE NOT NULL DEFAULT '1970-01-01' COMMENT '有効開始日',
  valid_to          DATE COMMENT '有効終了日（NULL=無期限）',
  status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状態',
  version           INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_workplace_period (customer_id, name, valid_from),
  UNIQUE KEY uk_workplace_tenant_id (tenant_id, id),
  INDEX idx_workplace_scope (tenant_id, customer_id, organization_id),
  INDEX idx_workplace_period (valid_from, valid_to),
  CONSTRAINT chk_workplace_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT fk_workplace_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_workplace_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派遣就業事業所マスタ';

CREATE TABLE t_contract_compliance_snapshot (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'snapshot ID',
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                       BIGINT NOT NULL COMMENT '契約ID',
  snapshot_version                  INT NOT NULL COMMENT '契約単位のsnapshot version（1始まり）',
  snapshot_hash                     VARCHAR(64) NOT NULL COMMENT '内容hash（非一意。idempotency keyにしない）',
  operation_id                      VARCHAR(64) COMMENT '生成operation ID（冪等性はoperation tableで担保）',
  snapshot_at                       DATETIME COMMENT 'snapshot確定日時',
  contract_no                       VARCHAR(100) COMMENT '契約番号（CONTRACT_PARTY_PERIOD_SNAPSHOT）',
  contract_date                     DATE COMMENT '契約締結日',
  party_name                        VARCHAR(200) COMMENT '当事者名typed snapshot',
  party_address                     VARCHAR(500) COMMENT '当事者住所typed snapshot',
  party_representative              VARCHAR(200) COMMENT '当事者代表者typed snapshot',
  dispatch_from                     DATE COMMENT '派遣期間開始typed snapshot',
  dispatch_to                       DATE COMMENT '派遣期間終了typed snapshot',
  workplace_name                    VARCHAR(200) COMMENT '事業所名typed snapshot（WORKPLACE_ORG_SNAPSHOT）',
  workplace_address                 VARCHAR(500) COMMENT '事業所所在地typed snapshot',
  workplace_department              VARCHAR(200) COMMENT '就業場所部署typed snapshot',
  workplace_phone                   VARCHAR(50) COMMENT '事業所電話typed snapshot',
  organization_unit                 VARCHAR(200) COMMENT '組織単位名typed snapshot',
  organization_head_title           VARCHAR(100) COMMENT '組織の長の職名typed snapshot',
  work_description                  TEXT COMMENT '業務内容（WORK_DESCRIPTION_TYPED）',
  statutory_job_flag                TINYINT COMMENT '政令業務該当flag',
  statutory_job_reference           VARCHAR(200) COMMENT '政令業務該当根拠',
  responsibility_level              VARCHAR(50) COMMENT '責任の程度（RESPONSIBILITY_TYPED）',
  responsibility_detail             TEXT COMMENT '責任・権限の内容',
  command_person_department         VARCHAR(100) COMMENT '指揮命令者部署',
  command_person_title              VARCHAR(100) COMMENT '指揮命令者役職',
  command_person_name               VARCHAR(100) COMMENT '指揮命令者氏名',
  command_person_phone              VARCHAR(50) COMMENT '指揮命令者電話',
  client_responsible_department     VARCHAR(100) COMMENT '派遣先責任者部署',
  client_responsible_title          VARCHAR(100) COMMENT '派遣先責任者役職',
  client_responsible_name           VARCHAR(100) COMMENT '派遣先責任者氏名',
  client_responsible_phone          VARCHAR(50) COMMENT '派遣先責任者電話',
  dispatch_responsible_department   VARCHAR(100) COMMENT '派遣元責任者部署',
  dispatch_responsible_title        VARCHAR(100) COMMENT '派遣元責任者役職',
  dispatch_responsible_name         VARCHAR(100) COMMENT '派遣元責任者氏名',
  dispatch_responsible_phone        VARCHAR(50) COMMENT '派遣元責任者電話',
  work_start_minute                 INT COMMENT '始業（分整数、0=00:00）（WORK_TIME_TYPED）',
  work_end_minute                   INT COMMENT '終業（分整数）',
  work_span_next_day_flag           TINYINT COMMENT '日跨ぎflag',
  break_start_minute                INT COMMENT '休憩開始（分整数）（WORK_TIME_TYPED。複数休憩はt_compliance_break_detail）',
  break_end_minute                  INT COMMENT '休憩終了（分整数）',
  work_day_code                     VARCHAR(30) COMMENT '就業日calendar code（WORK_CALENDAR_HISTORY）',
  holiday_calendar_code             VARCHAR(30) COMMENT '休日calendar code',
  agreement_reference_id            BIGINT COMMENT '36協定reference（OVERTIME_AGREEMENT_SNAPSHOT）',
  overtime_daily_limit              INT COMMENT '時間外1日上限',
  overtime_monthly_limit            INT COMMENT '時間外1月上限',
  overtime_yearly_limit             INT COMMENT '時間外1年上限',
  overtime_period_from              DATE COMMENT '適用期間開始',
  overtime_period_to                DATE COMMENT '適用期間終了',
  workplace_limitation_date         DATE COMMENT '事業所単位の抵触日（LIMITATION_DUAL_TYPED。NULL=未算定）',
  organization_limitation_date      DATE COMMENT '組織単位（個人単位）の抵触日（NULL=未算定）',
  safety_responsibility_detail      TEXT COMMENT '安全衛生の責任分担（SAFETY_TYPED）',
  safety_rule_reference             VARCHAR(200) COMMENT '安全衛生適用規程',
  benefits_detail                   TEXT COMMENT '福利厚生の具体的内容（BENEFITS_TYPED）',
  benefits_provided_flag            TINYINT COMMENT '福利厚生提供有無',
  dispatch_headcount                INT COMMENT '派遣人員（HEADCOUNT_TYPED）',
  agreement_target_flag             TINYINT COMMENT '協定対象flag（AGREEMENT_FLAG_TYPED）',
  treatment_scheme                  VARCHAR(100) COMMENT '待遇方式',
  source_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣元）部署（COMPLAINT_HISTORY）',
  source_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣元）役職',
  source_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣元）氏名',
  source_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣元）電話',
  client_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣先）部署',
  client_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣先）役職',
  client_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣先）氏名',
  client_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣先）電話',
  employment_stability_preference   TEXT COMMENT '雇用安定措置の希望（EMPLOYMENT_STABILITY_HISTORY）',
  limitation_exemption_type         VARCHAR(50) COMMENT '期間制限例外type（LIMITATION_EXEMPTION_TYPED）',
  limitation_exemption_detail       TEXT COMMENT '期間制限例外内容',
  limitation_exemption_basis        VARCHAR(200) COMMENT '期間制限例外根拠',
  limitation_exemption_from         DATE COMMENT '例外期間開始',
  limitation_exemption_to           DATE COMMENT '例外期間終了',
  dispatch_fee_amount               DECIMAL(14,2) COMMENT '派遣料金（DISPATCH_FEE_TYPED）',
  dispatch_fee_basis                VARCHAR(20) COMMENT '月額/日額/時間額',
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY' COMMENT '通貨',
  social_insurance_procedure_incomplete_reason TEXT COMMENT '社会保険手続未完了理由（SRC-E⑱）（INSURANCE_TYPED）',
  health_insurance_status           VARCHAR(20) COMMENT '健康保険加入状態',
  health_insurance_missing_reason   VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date    DATE COMMENT '健康保険取得予定日',
  pension_insurance_status          VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason  VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date   DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status       VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date DATE COMMENT '雇用保険取得予定日',
  instruction_route                 TEXT COMMENT '作業指示経路',
  subcontract_allowed               TINYINT COMMENT '再委託可否（NULL=未確認）',
  acceptance_method                 VARCHAR(255) COMMENT '検収方法',
  retention_due_date                DATE COMMENT '保存満了予定日（RETENTION_METADATA）',
  legal_hold_flag                   TINYINT COMMENT 'legal hold（削除停止）',
  version                           INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
   UNIQUE KEY uk_compliance_snapshot_version (contract_id, snapshot_version),
   UNIQUE KEY uk_compliance_snapshot_tenant_id (tenant_id, id),
  INDEX idx_compliance_snapshot_hash (snapshot_hash),
  INDEX idx_compliance_snapshot_contract (contract_id),
  CONSTRAINT fk_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約compliance profile snapshot（append-only）';

CREATE TABLE t_contract_compliance_profile (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'profile ID',
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                       BIGINT NOT NULL COMMENT '契約ID',
  contract_type_detail              VARCHAR(50) COMMENT '派遣/準委任/請負の詳細',
  workplace_id                      BIGINT COMMENT '就業事業所ID',
  work_description                  TEXT COMMENT '業務内容',
  statutory_job_flag                TINYINT COMMENT '政令業務該当flag',
  statutory_job_reference           VARCHAR(200) COMMENT '政令業務該当根拠',
  responsibility_level              VARCHAR(50) COMMENT '責任の程度',
  responsibility_detail             TEXT COMMENT '責任・権限の内容',
  command_person_contact_id         BIGINT COMMENT '指揮命令者（顧客contact ID）',
  command_person_department         VARCHAR(100) COMMENT '指揮命令者部署',
  command_person_title              VARCHAR(100) COMMENT '指揮命令者役職',
  command_person_name               VARCHAR(100) COMMENT '指揮命令者氏名',
  command_person_phone              VARCHAR(50) COMMENT '指揮命令者電話',
  client_responsible_contact_id     BIGINT COMMENT '派遣先責任者（顧客contact ID）',
  client_responsible_department     VARCHAR(100) COMMENT '派遣先責任者部署',
  client_responsible_title          VARCHAR(100) COMMENT '派遣先責任者役職',
  client_responsible_name           VARCHAR(100) COMMENT '派遣先責任者氏名',
  client_responsible_phone          VARCHAR(50) COMMENT '派遣先責任者電話',
  dispatch_responsible_user_id      BIGINT COMMENT '派遣元責任者ユーザーID',
  dispatch_responsible_department   VARCHAR(100) COMMENT '派遣元責任者部署',
  dispatch_responsible_title        VARCHAR(100) COMMENT '派遣元責任者役職',
  dispatch_responsible_name         VARCHAR(100) COMMENT '派遣元責任者氏名',
  dispatch_responsible_phone        VARCHAR(50) COMMENT '派遣元責任者電話',
  work_start_minute                 INT COMMENT '始業（分整数、0=00:00）',
  work_end_minute                   INT COMMENT '終業（分整数）',
  work_span_next_day_flag           TINYINT COMMENT '日跨ぎflag',
  break_start_minute                INT COMMENT '休憩開始（分整数）（複数休憩はt_compliance_break_detail）',
  break_end_minute                  INT COMMENT '休憩終了（分整数）',
  work_day_code                     VARCHAR(30) COMMENT '就業日calendar code',
  holiday_calendar_code             VARCHAR(30) COMMENT '休日calendar code',
  agreement_reference_id            BIGINT COMMENT '36協定reference',
  overtime_daily_limit              INT COMMENT '時間外1日上限',
  overtime_monthly_limit            INT COMMENT '時間外1月上限',
  overtime_yearly_limit             INT COMMENT '時間外1年上限',
  overtime_period_from              DATE COMMENT '適用期間開始',
  overtime_period_to                DATE COMMENT '適用期間終了',
  workplace_limitation_date         DATE COMMENT '事業所単位の抵触日（NULL=未算定）',
  organization_limitation_date      DATE COMMENT '組織単位（個人単位）の抵触日（NULL=未算定）',
  safety_responsibility_detail      TEXT COMMENT '安全衛生の責任分担',
  safety_rule_reference             VARCHAR(200) COMMENT '安全衛生適用規程',
  benefits_detail                   TEXT COMMENT '福利厚生の具体的内容',
  benefits_provided_flag            TINYINT COMMENT '福利厚生提供有無',
  dispatch_headcount                INT COMMENT '派遣人員',
  agreement_target_flag             TINYINT COMMENT '協定対象flag',
  treatment_scheme                  VARCHAR(100) COMMENT '待遇方式',
  source_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣元）部署',
  source_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣元）役職',
  source_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣元）氏名',
  source_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣元）電話',
  client_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣先）部署',
  client_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣先）役職',
  client_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣先）氏名',
  client_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣先）電話',
  employment_stability_preference   TEXT COMMENT '雇用安定措置の希望',
  limitation_exemption_type         VARCHAR(50) COMMENT '期間制限例外type',
  limitation_exemption_detail       TEXT COMMENT '期間制限例外内容',
  limitation_exemption_basis        VARCHAR(200) COMMENT '期間制限例外根拠',
  limitation_exemption_from         DATE COMMENT '例外期間開始',
  limitation_exemption_to           DATE COMMENT '例外期間終了',
  dispatch_fee_amount               DECIMAL(14,2) COMMENT '派遣料金',
  dispatch_fee_basis                VARCHAR(20) COMMENT '月額/日額/時間額',
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY' COMMENT '通貨',
  social_insurance_procedure_incomplete_reason TEXT COMMENT '社会保険手続未完了理由（SRC-E⑱）',
  health_insurance_status           VARCHAR(20) COMMENT '健康保険加入状態',
  health_insurance_missing_reason   VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date    DATE COMMENT '健康保険取得予定日',
  pension_insurance_status          VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason  VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date   DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status       VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date DATE COMMENT '雇用保険取得予定日',
  instruction_route                 TEXT COMMENT '作業指示経路',
  subcontract_allowed               TINYINT COMMENT '再委託可否（NULL=未確認）',
  acceptance_method                 VARCHAR(255) COMMENT '検収方法',
  dispatch_period_start             DATE COMMENT '派遣期間開始',
  dispatch_period_end               DATE COMMENT '派遣期間終了',
  retention_due_date                DATE COMMENT '保存満了予定日',
  legal_hold_flag                   TINYINT COMMENT 'legal hold（削除停止）',
  current_snapshot_id               BIGINT COMMENT 'current snapshot pointer（FK）',
  current_snapshot_version          INT COMMENT 'current snapshot version（CAS対象）',
  version                           INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_contract_compliance_profile_contract (contract_id),
  INDEX idx_profile_workplace (workplace_id),
  INDEX idx_profile_limitation (workplace_limitation_date, organization_limitation_date),
  INDEX idx_profile_dispatch_period (dispatch_period_start, dispatch_period_end),
  CONSTRAINT chk_profile_period CHECK (dispatch_period_end IS NULL OR dispatch_period_start IS NULL OR dispatch_period_start <= dispatch_period_end),
  CONSTRAINT chk_profile_dispatch_count CHECK (dispatch_headcount IS NULL OR dispatch_headcount >= 0),
  CONSTRAINT chk_profile_fee CHECK (dispatch_fee_amount IS NULL OR dispatch_fee_amount >= 0),
  CONSTRAINT fk_profile_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_profile_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_profile_responsible_user FOREIGN KEY (dispatch_responsible_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_profile_current_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_snapshot(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約compliance profile（mutable current）';

CREATE TABLE t_contract_compliance_worker_snapshot (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'worker snapshot ID',
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                   BIGINT NOT NULL COMMENT '契約ID',
  worker_id                     BIGINT NOT NULL COMMENT '派遣労働者（t_engineer ID）',
  snapshot_version              INT NOT NULL COMMENT 'worker単位のsnapshot version（1始まり）',
  snapshot_hash                 VARCHAR(64) NOT NULL COMMENT '内容hash（非一意）',
  operation_id                  VARCHAR(64) COMMENT '生成operation ID',
  snapshot_at                   DATETIME COMMENT 'snapshot確定日時',
  worker_name                   VARCHAR(100) COMMENT '派遣労働者氏名（WORKER_PII_SNAPSHOT）',
  employer_name                 VARCHAR(200) COMMENT '派遣元名',
  employer_address              VARCHAR(500) COMMENT '派遣元住所',
  employer_title                VARCHAR(100) COMMENT '使用者職氏名',
  gender                        VARCHAR(20) COMMENT '性別',
  age_band                      VARCHAR(30) COMMENT '年齢区分（45歳以上/18歳未満/その他）',
  age_at_reference_date         DATE COMMENT '年齢判定基準日',
  employment_term_type          VARCHAR(20) COMMENT '無期/有期（WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT）',
  employment_from               DATE COMMENT '雇用期間開始',
  employment_to                 DATE COMMENT '雇用期間終了',
  indefinite_worker_flag        TINYINT COMMENT '無期雇用flag',
  age_over_60_flag              TINYINT COMMENT '60歳以上flag',
  worker_restriction_type       VARCHAR(30) COMMENT '無期/60歳以上/限定しないの区分',
  health_insurance_status       VARCHAR(20) COMMENT '健康保険加入状態（INSURANCE_TYPED）',
  health_insurance_missing_reason VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date DATE COMMENT '健康保険取得予定日',
  pension_insurance_status      VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status   VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date DATE COMMENT '雇用保険取得予定日',
  version                       INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
   UNIQUE KEY uk_worker_snapshot_version (contract_id, worker_id, snapshot_version),
   UNIQUE KEY uk_worker_snapshot_tenant_id (tenant_id, id),
  INDEX idx_worker_snapshot_hash (snapshot_hash),
  INDEX idx_worker_snapshot_worker (worker_id),
  CONSTRAINT fk_worker_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_worker_snapshot_engineer FOREIGN KEY (worker_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='worker-specific snapshot（append-only）';

CREATE TABLE t_contract_compliance_worker_state (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'worker current state ID',
  tenant_id                   VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                 BIGINT NOT NULL COMMENT '契約ID',
  worker_id                   BIGINT NOT NULL COMMENT '派遣労働者（t_engineer ID）',
  current_snapshot_id         BIGINT COMMENT 'current worker snapshot pointer（FK）',
  current_snapshot_version    INT COMMENT 'current snapshot version（CAS対象）',
  version                     INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版（CAS）',
  created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_worker_state_contract_worker (contract_id, worker_id),
  CONSTRAINT fk_worker_state_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_worker_state_engineer FOREIGN KEY (worker_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_worker_state_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='worker current pointer（CAS付き）';

CREATE TABLE t_compliance_snapshot_operation (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'operation ID',
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  operation_id                  VARCHAR(64) NOT NULL COMMENT 'クライアント発行のoperation ID（冪等キー）',
  scope_type                    VARCHAR(20) NOT NULL COMMENT 'CONTRACT / WORKER',
  contract_id                   BIGINT NOT NULL COMMENT '契約ID',
  worker_id                     BIGINT COMMENT 'worker scope時のみ',
  expected_version              INT COMMENT 'expected current version（CAS）',
  resulting_snapshot_id         BIGINT COMMENT '成功時resulting contract snapshot ID',
  resulting_worker_snapshot_id  BIGINT COMMENT '成功時resulting worker snapshot ID',
  request_hash                  VARCHAR(64) COMMENT 'request内容hash（照合用。idempotency keyにしない）',
  status                        VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED' COMMENT 'SUCCEEDED / FAILED',
  version                       INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_snapshot_operation (operation_id),
  INDEX idx_snapshot_operation_contract (contract_id, scope_type),
  CONSTRAINT fk_snapshot_operation_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='snapshot保存operation（retry idempotency）';

CREATE TABLE t_compliance_work_calendar (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT COMMENT 'worker-specific calendar時のみ',
  event_id              VARCHAR(64) NOT NULL COMMENT 'event ID（CREATED/CORRECTED/CANCELLEDで新規採番）',
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64) COMMENT '訂正・取消の対象event',
  correction_reason     VARCHAR(500) COMMENT '訂正理由（CORRECTED/CANCELLED時必須）',
  actor_user_id         BIGINT COMMENT '実actor（runtime承認はM/本番gate）',
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'event発生時刻',
  effective_from        DATE COMMENT '適用開始',
  effective_to          DATE COMMENT '適用終了',
  work_day_code         VARCHAR(30) COMMENT '就業日calendar code',
  holiday_calendar_code VARCHAR(30) COMMENT '休日calendar code',
  excluded_date         DATE COMMENT '休暇除外日',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_work_calendar_event (event_id),
  INDEX idx_work_calendar_contract (contract_id, effective_from),
  CONSTRAINT fk_work_calendar_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='就業日/休日/休暇除外のappend-only calendar history';

CREATE TABLE t_compliance_break_detail (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT COMMENT 'worker-specific break時のみ',
  event_id              VARCHAR(64) NOT NULL COMMENT 'event ID（CREATED/CORRECTED/CANCELLEDで新規採番）',
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64) COMMENT '訂正・取消の対象event',
  correction_reason     VARCHAR(500) COMMENT '訂正理由（CORRECTED/CANCELLED時必須）',
  actor_user_id         BIGINT COMMENT '実actor（runtime承認はM/本番gate）',
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'event発生時刻',
  effective_from        DATE COMMENT '適用開始',
  effective_to          DATE COMMENT '適用終了',
  break_no              INT NOT NULL COMMENT '休憩順序（1始まり）',
  start_offset_minute   INT NOT NULL COMMENT '勤務開始からの休憩開始offset（分）',
  end_offset_minute     INT NOT NULL COMMENT '勤務開始からの休憩終了offset（分）',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_break_detail_event (event_id),
  INDEX idx_break_detail_contract (contract_id, effective_from),
  CONSTRAINT chk_break_detail_offset CHECK (start_offset_minute >= 0 AND end_offset_minute > start_offset_minute),
  CONSTRAINT fk_break_detail_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='就業時間の複数休憩（反復detail、append-only）';

CREATE TABLE t_compliance_complaint_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  complaint_type        VARCHAR(20) COMMENT 'SOURCE / CLIENT',
  received_at           DATE COMMENT '申出日',
  content               TEXT COMMENT '申出内容',
  action                TEXT COMMENT '処理内容',
  resolution            TEXT COMMENT '結果',
  notified_at           DATE COMMENT '本人通知日',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_complaint_event (event_id),
  INDEX idx_complaint_contract (contract_id, received_at),
  CONSTRAINT fk_complaint_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='苦情受付・処理・通知のappend-only history';

CREATE TABLE t_employment_stability_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  request_at            DATE COMMENT '依頼日時',
  request_method        VARCHAR(100) COMMENT '依頼方法',
  response_at           DATE COMMENT '回答日時',
  response_content      TEXT COMMENT '回答内容',
  action                TEXT COMMENT '実施内容',
  outcome               VARCHAR(100) COMMENT '結果',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_employment_stability_event (event_id),
  INDEX idx_employment_stability_contract (contract_id, request_at),
  CONSTRAINT fk_employment_stability_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='雇用安定措置のappend-only history';

CREATE TABLE t_training_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  training_date         DATE COMMENT '実施日',
  minutes               INT COMMENT '実施時間（分）',
  content               TEXT COMMENT '研修内容',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_training_event (event_id),
  INDEX idx_training_contract (contract_id, training_date),
  CONSTRAINT fk_training_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教育訓練のappend-only history';

CREATE TABLE t_career_consulting_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  consulting_date       DATE COMMENT '実施日',
  content               TEXT COMMENT '内容（PII）',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_career_consulting_event (event_id),
  INDEX idx_career_consulting_contract (contract_id, consulting_date),
  CONSTRAINT fk_career_consulting_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='キャリア・コンサルティングのappend-only history';

CREATE TABLE t_planned_introduction_terms (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE COMMENT '予定条件の適用開始',
  effective_to          DATE COMMENT '予定条件の適用終了',
  contract_period_from  DATE COMMENT '契約期間開始（PLANNED_INTRODUCTION_TERMS）',
  contract_period_to    DATE COMMENT '契約期間終了',
  renewal_rule          VARCHAR(100) COMMENT '更新の有無・上限',
  renewal_limit         INT COMMENT '更新上限回数',
  work_change_scope     VARCHAR(500) COMMENT '業務/場所の変更範囲',
  trial_period          VARCHAR(200) COMMENT '試用期間',
  wage_detail           VARCHAR(500) COMMENT '賃金',
  insurance_detail      VARCHAR(500) COMMENT '保険',
  smoking_measure       VARCHAR(500) COMMENT '喫煙措置',
  employer_name         VARCHAR(200) COMMENT '雇用主',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_planned_introduction_terms_event (event_id),
  INDEX idx_planned_terms_contract (contract_id, effective_from),
  CONSTRAINT fk_planned_terms_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='紹介予定派遣の予定労働条件（current-condition sub-field）';

CREATE TABLE t_planned_introduction_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  introduction_date     DATE COMMENT '紹介時期',
  outcome               VARCHAR(30) COMMENT 'OFFERED / ACCEPTED / REJECTED / OTHER',
  reason                TEXT COMMENT '非採用理由等',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_planned_introduction_event (event_id),
  INDEX idx_planned_introduction_contract (contract_id, introduction_date),
  CONSTRAINT fk_planned_introduction_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='紹介予定派遣の紹介・採否・非採用理由（append-only）';

CREATE TABLE t_direct_hire_dispute_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  measure               VARCHAR(500) COMMENT '紛争防止措置（DIRECT_HIRE_DISPUTE_HISTORY）',
  fee_detail            VARCHAR(500) COMMENT '手数料',
  request_method        VARCHAR(200) COMMENT '申出方法',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_direct_hire_dispute_event (event_id),
  INDEX idx_direct_hire_dispute_contract (contract_id, effective_from),
  CONSTRAINT fk_direct_hire_dispute_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='直接雇用時の紛争防止措置（条件付きappend-only）';

CREATE TABLE t_notification_difference_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  difference_type       VARCHAR(50) COMMENT '差異種別（派遣期間/就業日/時間・休憩/責任者/時間外/その他）',
  contract_snapshot_id  BIGINT COMMENT '契約側snapshot ID',
  notice_snapshot_id    BIGINT COMMENT '明示側snapshot ID',
  difference_detail     TEXT COMMENT '差異の内容',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_notification_difference_event (event_id),
  INDEX idx_notification_difference_contract (contract_id, occurred_at),
  CONSTRAINT fk_notification_difference_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約内容と明示内容の差異（append-only）';

CREATE TABLE t_ledger_work_snapshot (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  work_month            DATE COMMENT '締め月（月初日）（LEDGER_WORK_HISTORY）',
  work_days             INT COMMENT '出勤日数',
  work_hours            INT COMMENT '就業時間（分）',
  overtime_hours        INT COMMENT '時間外（分）',
  absence_days          INT COMMENT '欠勤日数',
  gross_amount          DECIMAL(14,2) COMMENT '客先工数金額',
  closed_at             DATETIME COMMENT '締め確定日時',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_ledger_work_event (event_id),
  INDEX idx_ledger_work_contract (contract_id, work_month),
  CONSTRAINT fk_ledger_work_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次就業状況・タイムシート（締め時点snapshotの反復行）';

CREATE TABLE t_compliance_finding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'finding ID',
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id           BIGINT NOT NULL COMMENT '契約ID',
  code                  VARCHAR(80) NOT NULL COMMENT 'finding code',
  severity              VARCHAR(20) NOT NULL DEFAULT 'WARNING' COMMENT 'INFO/WARNING/ERROR',
  status                VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKNOWLEDGED/IN_PROGRESS/RESOLVED/EXCEPTION_APPROVED',
  condition_fingerprint VARCHAR(128) NOT NULL COMMENT '条件fingerprint',
  detected_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '検出日時',
  due_date              DATE COMMENT '対応期限',
  acknowledged_by      BIGINT COMMENT '確認者',
  acknowledged_at       DATETIME COMMENT '確認日時',
  resolution_note       VARCHAR(2000) COMMENT '解消/例外根拠',
  evidence_document_id  BIGINT COMMENT '根拠文書ID',
  version               INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_finding (contract_id, code, condition_fingerprint),
  INDEX idx_finding_status_due (status, due_date),
  INDEX idx_finding_contract (contract_id, detected_at),
  CONSTRAINT fk_finding_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_finding_acknowledged_by FOREIGN KEY (acknowledged_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_finding_evidence_document FOREIGN KEY (evidence_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='compliance finding';

CREATE TABLE t_document_delivery (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '交付履歴ID',
  tenant_id                VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id              BIGINT COMMENT '契約ID',
  document_id              BIGINT NOT NULL COMMENT '文書ID',
  document_type            VARCHAR(50) COMMENT '帳票種別',
  template_version         VARCHAR(50) COMMENT '帳票template version',
  effective_from           DATE COMMENT 'template適用開始',
  effective_to             DATE COMMENT 'template適用終了',
  snapshot_hash            VARCHAR(64) COMMENT '生成元snapshot hash',
  recipient_contact_id     BIGINT COMMENT '受領者contact ID',
  recipient_name_snapshot  VARCHAR(200) COMMENT '受領者名snapshot',
  recipient_email_snapshot VARCHAR(255) COMMENT '受領者メールsnapshot',
  delivery_method          VARCHAR(30) NOT NULL COMMENT 'EMAIL/PORTAL/PAPER/OTHER',
  delivery_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELIVERED/FAILED',
  delivered_at             DATETIME COMMENT '交付日時',
  confirmed_at             DATETIME COMMENT '受領確認日時（NULL=受領未確認。未交付ではない）',
  confirmation_note        VARCHAR(1000) COMMENT '受領確認メモ',
  idempotency_key          VARCHAR(200) COMMENT '交付冪等キー（生成キーは(contract_id, document_type, template_version, snapshot_hash)の業務一意）',
  mapping_version_id       BIGINT COMMENT 'G2 mapping version ID（legacyはNULL）',
  mapping_version          VARCHAR(50) COMMENT 'G2 mapping version',
  mapping_hash             CHAR(64) COMMENT 'G2 mapping hash',
  review_policy_hash       CHAR(64) COMMENT 'G2 review policy hash',
  gate_evaluated_at        DATETIME(6) COMMENT 'G2 gate評価時刻',
  gate_snapshot_hash       CHAR(64) COMMENT 'G2 gate snapshot hash',
  profile_snapshot_id      BIGINT COMMENT '採用profile snapshot ID',
  profile_snapshot_hash    CHAR(64) COMMENT '採用profile snapshot hash',
  worker_snapshot_id       BIGINT COMMENT '採用worker snapshot ID（不存在時NULL）',
  worker_snapshot_hash     CHAR(64) COMMENT '採用worker snapshot hash（不存在時NULL）',
  workplace_id             BIGINT COMMENT 'profileからserver解決した就業先',
  render_input_hash        CHAR(64) COMMENT 'render input provenance hash',
  recipient_display_snapshot_hash CHAR(64) COMMENT 'recipient/display実render hash',
  company_config_snapshot_hash    CHAR(64) COMMENT 'company/config実render hash',
  field_mask_policy_hash   CHAR(64) COMMENT 'field mask policy hash',
  render_engine_version    VARCHAR(100) COMMENT 'render engine version',
  rendition_group_id       VARCHAR(36) COMMENT 'FULL/MASK/LIMITEDの不変group',
  full_document_version_id BIGINT COMMENT 'FULL rendition document version ID',
  full_document_sha256     CHAR(64) COMMENT 'FULL rendition SHA-256',
  mask_document_version_id BIGINT COMMENT 'MASK rendition document version ID',
  mask_document_sha256     CHAR(64) COMMENT 'MASK rendition SHA-256',
  limited_document_version_id BIGINT COMMENT 'LIMITED rendition document version ID',
  limited_document_sha256  CHAR(64) COMMENT 'LIMITED rendition SHA-256',
  delivery_business_key    CHAR(64) COMMENT 'client keyと分離した業務一意key',
  generation_state         VARCHAR(20) COMMENT 'CREATING/READY。legacyはNULL',
  version                  INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag             TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_document_delivery_idempotency (tenant_id, idempotency_key),
  UNIQUE KEY uk_delivery_business_key (tenant_id, delivery_business_key),
  INDEX idx_delivery_document (document_id, delivered_at),
  INDEX idx_delivery_contract (contract_id, delivered_at),
  INDEX idx_delivery_confirmation (confirmed_at),
  INDEX idx_delivery_mapping_version (tenant_id, mapping_version_id),
  INDEX idx_delivery_gate_evaluated (tenant_id, gate_evaluated_at),
  INDEX idx_delivery_rendition_group (tenant_id, rendition_group_id),
  CONSTRAINT fk_delivery_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_delivery_document FOREIGN KEY (document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='法定帳票交付履歴';

-- ============================================================
-- G2 gate tables (V1 fresh baseline; V102 is the legacy forward migration)
-- ============================================================
CREATE TABLE m_compliance_mapping_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_code VARCHAR(100) NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL,
  effective_from DATE NOT NULL, effective_to DATE, status VARCHAR(30) NOT NULL,
  active_slot TINYINT, future_slot TINYINT, activated_at DATETIME(6), activated_by BIGINT,
  version INT NOT NULL DEFAULT 0, created_by BIGINT, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by BIGINT, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_mapping_version UNIQUE (tenant_id, mapping_version),
  CONSTRAINT uk_g2_mapping_active_slot UNIQUE (tenant_id, mapping_code, active_slot),
  CONSTRAINT uk_g2_mapping_future_slot UNIQUE (tenant_id, mapping_code, future_slot),
  CONSTRAINT uk_g2_mapping_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_mapping_status CHECK (status IN ('DRAFT','PROVISIONAL_REVIEWED','ACTIVE','SUPERSEDED')),
  CONSTRAINT chk_g2_mapping_active_slot CHECK ((status = 'ACTIVE' AND active_slot = 1) OR (status <> 'ACTIVE' AND active_slot IS NULL)),
  CONSTRAINT chk_g2_mapping_future_slot CHECK (future_slot IS NULL OR future_slot = 1),
  CONSTRAINT fk_g2_mapping_activated_by FOREIGN KEY (activated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_mapping_effective ON m_compliance_mapping_version
  (tenant_id, mapping_code, status, effective_from, effective_to);

CREATE TABLE m_compliance_mapping_source (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  source_code VARCHAR(100) NOT NULL, source_url VARCHAR(1000) NOT NULL, source_version VARCHAR(100) NOT NULL,
  confirmed_on DATE NOT NULL, effective_from DATE NOT NULL, effective_to DATE,
  created_by BIGINT, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_by BIGINT,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_mapping_source UNIQUE (tenant_id, mapping_id, source_code),
  CONSTRAINT uk_g2_mapping_source_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_g2_source_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
  CONSTRAINT fk_g2_source_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_g2_source_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_mapping_source_lookup ON m_compliance_mapping_source (tenant_id, source_code, confirmed_on);

CREATE TABLE m_compliance_external_reviewer_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', type_code VARCHAR(100) NOT NULL,
  display_name VARCHAR(200) NOT NULL, description VARCHAR(1000), credential_label VARCHAR(200) NOT NULL,
  credential_required TINYINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1, sort_order INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0, created_by BIGINT, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by BIGINT, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_reviewer_type UNIQUE (tenant_id, type_code), CONSTRAINT uk_g2_reviewer_type_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_reviewer_credential_required CHECK (credential_required IN (0,1)), CONSTRAINT chk_g2_reviewer_enabled CHECK (enabled IN (0,1)),
  CONSTRAINT fk_g2_reviewer_type_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_g2_reviewer_type_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_reviewer_type_enabled ON m_compliance_external_reviewer_type (tenant_id, enabled, sort_order);

CREATE TABLE m_compliance_mapping_review_requirement_group (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  requirement_group_code VARCHAR(100) NOT NULL, display_name VARCHAR(200) NOT NULL, minimum_distinct_reviewers INT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0, created_by BIGINT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_by BIGINT,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_review_group UNIQUE (tenant_id, mapping_id, requirement_group_code), CONSTRAINT uk_g2_review_group_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_review_group_minimum CHECK (minimum_distinct_reviewers >= 1),
  CONSTRAINT fk_g2_review_group_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
  CONSTRAINT fk_g2_review_group_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id),
  CONSTRAINT fk_g2_review_group_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_review_group_mapping ON m_compliance_mapping_review_requirement_group (tenant_id, mapping_id, sort_order);

CREATE TABLE m_compliance_mapping_review_requirement_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', requirement_group_id BIGINT NOT NULL,
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  credential_label_snapshot VARCHAR(200) NOT NULL, credential_required_snapshot TINYINT NOT NULL, created_by BIGINT,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_by BIGINT,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_review_type UNIQUE (tenant_id, requirement_group_id, reviewer_type_id), CONSTRAINT uk_g2_review_type_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_g2_review_type_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id),
  CONSTRAINT fk_g2_review_type_reviewer FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id),
  CONSTRAINT fk_g2_review_type_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_review_type_reviewer ON m_compliance_mapping_review_requirement_type (tenant_id, reviewer_type_id);

CREATE TABLE t_compliance_responsible_assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', workplace_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  role_code VARCHAR(40) NOT NULL DEFAULT 'COMPLIANCE_RESPONSIBLE', effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6), active_slot TINYINT,
  assigned_by BIGINT NOT NULL, ended_by BIGINT, end_reason VARCHAR(500), version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag TINYINT NOT NULL DEFAULT 0, CONSTRAINT uk_g2_assignment_active_slot UNIQUE (tenant_id, workplace_id, active_slot),
  CONSTRAINT uk_g2_assignment_tenant_id UNIQUE (tenant_id, id), CONSTRAINT chk_g2_assignment_role CHECK (role_code = 'COMPLIANCE_RESPONSIBLE'),
  CONSTRAINT chk_g2_assignment_period CHECK (effective_to IS NULL OR effective_from < effective_to),
  CONSTRAINT chk_g2_assignment_open_fields CHECK ((effective_to IS NULL AND active_slot = 1 AND ended_by IS NULL AND end_reason IS NULL) OR (effective_to IS NOT NULL AND active_slot IS NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)),
  CONSTRAINT fk_g2_assignment_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id),
  CONSTRAINT fk_g2_assignment_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_g2_assignment_assigned_by FOREIGN KEY (assigned_by) REFERENCES sys_user(id),
  CONSTRAINT fk_g2_assignment_ended_by FOREIGN KEY (ended_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_assignment_period ON t_compliance_responsible_assignment (tenant_id, workplace_id, effective_from, effective_to);
CREATE INDEX idx_g2_assignment_user_period ON t_compliance_responsible_assignment (tenant_id, user_id, effective_from, effective_to);

CREATE TABLE t_compliance_mapping_approval_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  mapping_version VARCHAR(50) NOT NULL, mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, assignment_id BIGINT NOT NULL,
  workplace_id_snapshot BIGINT NOT NULL, actor_id BIGINT NOT NULL, actor_display_name_snapshot VARCHAR(200) NOT NULL, actor_role_snapshot VARCHAR(50) NOT NULL,
  action VARCHAR(20) NOT NULL, event_chain_id VARCHAR(36) NOT NULL, target_event_id BIGINT, supersedes_event_id BIGINT, occurred_at DATETIME(6) NOT NULL,
  reason VARCHAR(1000), evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64),
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_approval_idempotency UNIQUE (tenant_id, idempotency_key), CONSTRAINT uk_g2_approval_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_approval_action CHECK (action IN ('APPROVE','REJECT','REVOKE')),
  CONSTRAINT fk_g2_approval_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
  CONSTRAINT fk_g2_approval_assignment FOREIGN KEY (tenant_id, assignment_id) REFERENCES t_compliance_responsible_assignment(tenant_id, id),
  CONSTRAINT fk_g2_approval_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
  CONSTRAINT fk_g2_approval_workplace FOREIGN KEY (tenant_id, workplace_id_snapshot) REFERENCES m_workplace(tenant_id, id),
  CONSTRAINT fk_g2_approval_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id),
  CONSTRAINT fk_g2_approval_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id),
  CONSTRAINT fk_g2_approval_evidence_document FOREIGN KEY (tenant_id, evidence_document_id) REFERENCES t_document(tenant_id, id),
  CONSTRAINT fk_g2_approval_evidence_version FOREIGN KEY (tenant_id, evidence_document_version_id) REFERENCES t_document_version(tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_approval_scope ON t_compliance_mapping_approval_event (tenant_id, mapping_id, workplace_id_snapshot, assignment_id, occurred_at, id);
CREATE INDEX idx_g2_approval_chain ON t_compliance_mapping_approval_event (tenant_id, event_chain_id, occurred_at, id);

CREATE TABLE t_compliance_external_review_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, requirement_group_id BIGINT NOT NULL, requirement_group_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_name_snapshot VARCHAR(200) NOT NULL, organization_snapshot VARCHAR(255), credential_snapshot_encrypted TEXT, credential_key_version VARCHAR(64),
  credential_cipher_format VARCHAR(20), credential_masked_snapshot VARCHAR(255), reviewer_identity_hash CHAR(64) NOT NULL, action VARCHAR(20) NOT NULL,
  review_chain_id VARCHAR(36) NOT NULL, target_event_id BIGINT, supersedes_event_id BIGINT, reviewed_at DATETIME(6) NOT NULL, valid_until DATETIME(6), recorded_at DATETIME(6) NOT NULL,
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64), recorded_by BIGINT NOT NULL,
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_external_review_idempotency UNIQUE (tenant_id, idempotency_key), CONSTRAINT uk_g2_external_review_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_external_review_action CHECK (action IN ('APPROVED','REJECTED','REVOKED')),
  CONSTRAINT chk_g2_external_credential_pair CHECK ((credential_snapshot_encrypted IS NULL AND credential_key_version IS NULL AND credential_cipher_format IS NULL AND credential_masked_snapshot IS NULL) OR (credential_snapshot_encrypted IS NOT NULL AND credential_key_version IS NOT NULL AND credential_cipher_format IS NOT NULL AND credential_masked_snapshot IS NOT NULL)),
  CONSTRAINT fk_g2_external_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
  CONSTRAINT fk_g2_external_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id),
  CONSTRAINT fk_g2_external_reviewer_type FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id),
  CONSTRAINT fk_g2_external_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
  CONSTRAINT fk_g2_external_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
  CONSTRAINT fk_g2_external_evidence_document FOREIGN KEY (tenant_id, evidence_document_id) REFERENCES t_document(tenant_id, id),
  CONSTRAINT fk_g2_external_evidence_version FOREIGN KEY (tenant_id, evidence_document_version_id) REFERENCES t_document_version(tenant_id, id),
  CONSTRAINT fk_g2_external_recorded_by FOREIGN KEY (recorded_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_external_review_scope ON t_compliance_external_review_event (tenant_id, mapping_id, requirement_group_id, reviewer_identity_hash, recorded_at, id);
CREATE INDEX idx_g2_external_review_chain ON t_compliance_external_review_event (tenant_id, review_chain_id, recorded_at, id);

CREATE TABLE t_compliance_mapping_status_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, before_status VARCHAR(30), after_status VARCHAR(30) NOT NULL,
  actor_id BIGINT NOT NULL, actor_display_name_snapshot VARCHAR(200) NOT NULL, actor_role_snapshot VARCHAR(50) NOT NULL, occurred_at DATETIME(6) NOT NULL,
  expected_version INT NOT NULL, gate_snapshot_hash CHAR(64), operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, reason VARCHAR(1000),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), CONSTRAINT uk_g2_status_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_status_after CHECK (after_status IN ('DRAFT','PROVISIONAL_REVIEWED','ACTIVE','SUPERSEDED')),
  CONSTRAINT fk_g2_status_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
  CONSTRAINT fk_g2_status_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_status_mapping ON t_compliance_mapping_status_event (tenant_id, mapping_id, occurred_at, id);
CREATE INDEX idx_g2_status_correlation ON t_compliance_mapping_status_event (tenant_id, correlation_id);

CREATE TABLE t_compliance_operation_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', operation_id VARCHAR(36) NOT NULL,
  operation_type VARCHAR(60) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, request_hash CHAR(64) NOT NULL, state VARCHAR(20) NOT NULL,
  retryable_flag TINYINT NOT NULL DEFAULT 0, attempt_count INT NOT NULL DEFAULT 1, started_at DATETIME(6) NOT NULL, lease_until DATETIME(6), finished_at DATETIME(6),
  result_reference_type VARCHAR(80), result_reference_id BIGINT, result_reference_version VARCHAR(100), result_summary_canonical TEXT, result_http_status INT,
  result_hash CHAR(64), failure_code VARCHAR(100), correlation_id VARCHAR(100) NOT NULL, expires_at DATETIME(6), version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_operation_key UNIQUE (tenant_id, operation_type, idempotency_key), CONSTRAINT uk_g2_operation_id UNIQUE (tenant_id, operation_id),
  CONSTRAINT chk_g2_operation_type CHECK (operation_type IN ('MAPPING_DRAFT_UPSERT','MAPPING_PROVISIONAL_REVIEW','ASSIGNMENT_CREATE','ASSIGNMENT_END','MAPPING_ACTIVE','MAPPING_SUPERSEDE','INTERNAL_APPROVAL','EXTERNAL_REVIEW','EXTERNAL_REVIEW_REVOKE','DELIVERY_GENERATE','REVIEWER_TYPE_CREATE','REVIEWER_TYPE_UPDATE','REVIEWER_TYPE_DISABLE','REVIEW_REQUIREMENT_UPDATE')),
  CONSTRAINT chk_g2_operation_state CHECK (state IN ('PROCESSING','SUCCEEDED','FAILED')), CONSTRAINT chk_g2_operation_retryable CHECK (retryable_flag IN (0,1)),
  CONSTRAINT chk_g2_operation_result CHECK (
    (state = 'SUCCEEDED' AND finished_at IS NOT NULL AND failure_code IS NULL
      AND result_summary_canonical IS NOT NULL AND result_http_status IS NOT NULL AND result_hash IS NOT NULL)
    OR (state = 'PROCESSING' AND finished_at IS NULL AND failure_code IS NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL)
    OR (state = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_g2_operation_lease ON t_compliance_operation_ledger (tenant_id, state, lease_until);
CREATE INDEX idx_g2_operation_result ON t_compliance_operation_ledger (tenant_id, result_reference_type, result_reference_id);

ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mapping_version
  FOREIGN KEY (tenant_id, mapping_version_id) REFERENCES m_compliance_mapping_version(tenant_id, id);
ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_profile_snapshot
  FOREIGN KEY (tenant_id, profile_snapshot_id) REFERENCES t_contract_compliance_snapshot(tenant_id, id);
ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_worker_snapshot
  FOREIGN KEY (tenant_id, worker_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(tenant_id, id);
ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_workplace
  FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id);
ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_full_document_version
  FOREIGN KEY (tenant_id, full_document_version_id) REFERENCES t_document_version(tenant_id, id);
ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mask_document_version
  FOREIGN KEY (tenant_id, mask_document_version_id) REFERENCES t_document_version(tenant_id, id);
ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_limited_document_version
  FOREIGN KEY (tenant_id, limited_document_version_id) REFERENCES t_document_version(tenant_id, id);

-- ============================================================
-- DDL完了
-- ============================================================
ALTER TABLE t_engineer ADD CONSTRAINT fk_engineer_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
  ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE t_engineer ADD CONSTRAINT fk_engineer_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
  ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE t_contract ADD CONSTRAINT fk_contract_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
  ON UPDATE CASCADE ON DELETE SET NULL;
ALTER TABLE t_contract ADD CONSTRAINT fk_contract_order_line FOREIGN KEY (order_line_id) REFERENCES t_sales_order_line(id)
  ON UPDATE CASCADE ON DELETE SET NULL;

-- ============================================================
-- R23-P1-01: reviewer subject / verification / adoption events
-- （V102_1__reviewer_verification_events.sql と同一shapeをconsolidated baselineへfold）
-- ============================================================
CREATE TABLE t_compliance_external_reviewer_subject (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  subject_code VARCHAR(100) NOT NULL, display_name VARCHAR(200) NOT NULL, organization_name VARCHAR(200) NOT NULL,
  person_fingerprint_snapshot CHAR(64) NOT NULL, fingerprint_key_version VARCHAR(64) NOT NULL,
  created_by BIGINT, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_by BIGINT,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_subject UNIQUE (tenant_id, subject_code), CONSTRAINT uk_g2_subject_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_subject_fingerprint CHECK (CHAR_LENGTH(person_fingerprint_snapshot) = 64),
  CONSTRAINT fk_g2_subject_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE t_compliance_external_reviewer_verification_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_subject_id BIGINT NOT NULL, person_fingerprint_snapshot CHAR(64) NOT NULL, qualification_fingerprint_snapshot CHAR(64) NOT NULL,
  fingerprint_key_version VARCHAR(64) NOT NULL, verification_kind VARCHAR(20) NOT NULL, result VARCHAR(20) NOT NULL,
  method_code VARCHAR(50) NOT NULL, authority_source_code VARCHAR(50) NOT NULL, authority_source_name VARCHAR(200) NOT NULL,
  official_url_reference_snapshot VARCHAR(1000), registration_identifier_encrypted TEXT,
  registration_identifier_key_version VARCHAR(64), registration_identifier_cipher_format VARCHAR(20), registration_identifier_masked_snapshot VARCHAR(255),
  checked_at DATETIME(6) NOT NULL, source_data_as_of DATETIME(6), max_age_days_snapshot INT, valid_until DATETIME(6), checked_by BIGINT NOT NULL,
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64),
  review_policy_version VARCHAR(50), review_policy_hash CHAR(64), mapping_id BIGINT, mapping_version VARCHAR(50), mapping_hash CHAR(64),
  external_review_event_id BIGINT, external_review_chain_id VARCHAR(36), submitted_review_event_id BIGINT NOT NULL,
  revoked_verification_event_id BIGINT, supersedes_verification_event_id BIGINT,
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_verification_idempotency UNIQUE (tenant_id, idempotency_key), CONSTRAINT uk_g2_verification_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_verification_kind CHECK (verification_kind IN ('IDENTITY','QUALIFICATION','ACTIVE_STATUS','REVIEW_AUTHORSHIP')),
  CONSTRAINT chk_g2_verification_result CHECK (result IN ('VERIFIED','FAILED','INCONCLUSIVE','REVOKED')),
  CONSTRAINT chk_g2_verification_fingerprint CHECK (CHAR_LENGTH(person_fingerprint_snapshot) = 64 AND CHAR_LENGTH(qualification_fingerprint_snapshot) = 64),
  CONSTRAINT fk_g2_verification_subject FOREIGN KEY (tenant_id, reviewer_subject_id)
    REFERENCES t_compliance_external_reviewer_subject(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_submitted FOREIGN KEY (tenant_id, submitted_review_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_revoked FOREIGN KEY (tenant_id, revoked_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_supersedes FOREIGN KEY (tenant_id, supersedes_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_evidence FOREIGN KEY (tenant_id, evidence_document_version_id)
    REFERENCES t_document_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_mapping FOREIGN KEY (tenant_id, mapping_id)
    REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_review FOREIGN KEY (tenant_id, external_review_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE t_compliance_external_review_adoption_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  action VARCHAR(20) NOT NULL, review_chain_id VARCHAR(36) NOT NULL, submitted_review_event_id BIGINT NOT NULL, revoked_adoption_event_id BIGINT,
  first_slot BIGINT GENERATED ALWAYS AS (CASE WHEN action IN ('APPROVED','REJECTED') THEN submitted_review_event_id ELSE NULL END) COMMENT '初回adoption一意化（R23-R1-P1-01）',
  identity_verification_event_id BIGINT, qualification_verification_event_id BIGINT, active_status_verification_event_id BIGINT,
  authorship_verification_event_id BIGINT, mapping_id BIGINT, mapping_version VARCHAR(50), mapping_hash CHAR(64),
  review_policy_version VARCHAR(50), review_policy_hash CHAR(64),
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64),
  adopted_at DATETIME(6) NOT NULL, adopted_by BIGINT NOT NULL,
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_adoption_idempotency UNIQUE (tenant_id, idempotency_key), CONSTRAINT uk_g2_adoption_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT uk_g2_adoption_first UNIQUE (tenant_id, first_slot),
  CONSTRAINT chk_g2_adoption_action CHECK (action IN ('APPROVED','REJECTED','REVOKED')),
  CONSTRAINT fk_g2_adoption_submitted FOREIGN KEY (tenant_id, submitted_review_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_revoked FOREIGN KEY (tenant_id, revoked_adoption_event_id)
    REFERENCES t_compliance_external_review_adoption_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_identity FOREIGN KEY (tenant_id, identity_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_qualification FOREIGN KEY (tenant_id, qualification_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_active_status FOREIGN KEY (tenant_id, active_status_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_authorship FOREIGN KEY (tenant_id, authorship_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_evidence FOREIGN KEY (tenant_id, evidence_document_version_id)
    REFERENCES t_document_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_mapping FOREIGN KEY (tenant_id, mapping_id)
    REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ============================================================
-- 39. 要員配置・需給計画（staffing-capacity-planning / S12 / V103）
-- V1統合baseline。legacy DBへはV103が同じshapeを順方向追加する。
-- approval_request_idのFKはV103のガード付きADD CONSTRAINTで追加される
-- （t_approval_requestはV75所属のため、V1ではFKを張れない）。
-- allocation_type×position_idの整合CHECKはMySQLではCHECK+FK同一列併用不可
-- （Error 3823相当）のためV1/V103には置かず、V103のtriggerで担保する
-- （H2はCHECKで担保。V102_1と同一の重担保方式）。
-- ============================================================

-- ---- 3) 配置計画 ----
-- position_id IS NULLは「社内/待機」という業務値（未割当ではない）。
-- source_contract_id IS NOT NULLの行はactual（契約由来）。需給集計SQLのWHERE句でplanと排他する。
CREATE TABLE IF NOT EXISTS t_allocation_plan (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id        BIGINT       NOT NULL                   COMMENT '要員ID',
  position_id        BIGINT                                  COMMENT 'ポジションID（NULL=社内/待機）',
  allocation_type    VARCHAR(20)  NOT NULL DEFAULT '案件'    COMMENT '案件/社内/待機',
  start_date         DATE         NOT NULL                   COMMENT '開始日（inclusive）',
  end_date           DATE                                    COMMENT '終了日（inclusive・NULL=open end: 計画window末まで）',
  allocation_percent DECIMAL(5,2) NOT NULL                   COMMENT '配賦率(%)',
  status             VARCHAR(20)  NOT NULL DEFAULT '下書き'  COMMENT '下書き/確定/破棄',
  source_contract_id BIGINT                                  COMMENT '実契約ID（NOT NULL=actual。planと排他）',
  exception_reason   VARCHAR(1000)                           COMMENT '過配賦例外の理由（例外時必須）',
  approval_request_id BIGINT                                 COMMENT '過配賦例外の承認申請ID（例外時必須）',
  version            INT          NOT NULL DEFAULT 0         COMMENT '楽観ロック',
  created_by         BIGINT                                  COMMENT '作成者ID',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag       TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  INDEX idx_allocation_plan_engineer_period (engineer_id, start_date, end_date),
  INDEX idx_allocation_plan_position (position_id),
  INDEX idx_allocation_plan_status (status),
  INDEX idx_allocation_plan_source_contract (source_contract_id),
  CONSTRAINT chk_allocation_plan_period CHECK (end_date IS NULL OR start_date <= end_date),
  CONSTRAINT chk_allocation_plan_percent CHECK (allocation_percent > 0 AND allocation_percent <= 100),
  CONSTRAINT chk_allocation_plan_status CHECK (status IN ('下書き','確定','破棄')),
  CONSTRAINT fk_allocation_plan_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_allocation_plan_position FOREIGN KEY (position_id) REFERENCES t_project_position(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_allocation_plan_contract FOREIGN KEY (source_contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員配置計画';

-- ---- 4) 仮配置scenario（本データを変更しない） ----
CREATE TABLE IF NOT EXISTS t_staffing_scenario (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  owner_user_id    BIGINT       NOT NULL                   COMMENT '作成者ID（閲覧scopeの起点）',
  name             VARCHAR(200) NOT NULL                   COMMENT 'scenario名',
  base_date        DATE         NOT NULL                   COMMENT '実データcopy基準日（snapshot）',
  shared_flag      TINYINT      NOT NULL DEFAULT 0         COMMENT '共有フラグ（1=同一組織scope内で共有）',
  assumptions_json TEXT                                    COMMENT '仮定メモのJSON',
  created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag     TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  INDEX idx_staffing_scenario_owner (owner_user_id),
  CONSTRAINT fk_staffing_scenario_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需給計画シナリオ';

-- ---- 5) scenario内の仮配置（日単位） ----
-- datesは対象日のISO日付JSON配列（昇順・重複なし・[base_date, window末]の範囲）。
-- scenario操作は本テーブルのみを更新し、t_allocation_plan/契約/提案へ一切書き込まない（R3.3）。
CREATE TABLE IF NOT EXISTS t_staffing_scenario_allocation (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  scenario_id BIGINT       NOT NULL                   COMMENT 'scenarioID',
  engineer_id BIGINT       NOT NULL                   COMMENT '要員ID',
  position_id BIGINT                                  COMMENT 'ポジションID（NULL=社内/待機）',
  dates       TEXT         NOT NULL                   COMMENT '対象日のJSON配列（ISO日付・昇順・重複なし）',
  percent     DECIMAL(5,2) NOT NULL                   COMMENT '配賦率(%)',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag TINYINT     NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  INDEX idx_scenario_alloc_scenario (scenario_id),
  INDEX idx_scenario_alloc_engineer (engineer_id),
  CONSTRAINT chk_scenario_alloc_percent CHECK (percent > 0 AND percent <= 100),
  CONSTRAINT fk_scenario_alloc_scenario FOREIGN KEY (scenario_id) REFERENCES t_staffing_scenario(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_scenario_alloc_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_scenario_alloc_position FOREIGN KEY (position_id) REFERENCES t_project_position(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シナリオ仮配置';

-- ---- 6) 顧客・BP外部ポータル（S13。V1 fresh baseline; V104がlegacy forward migration） ----
-- 本specはplatform-invariants §2の認可母集団の既定解が適用できない唯一のspecであり、
-- portal userの母集団は m_portal_organization → customer_id / bp_company_id から独立に導出する（design §6.2）。
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

CREATE TABLE IF NOT EXISTS t_portal_user (
  id                     BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  portal_org_id          BIGINT       NOT NULL COMMENT 'ポータル組織ID',
  email                  VARCHAR(255) NOT NULL COMMENT 'login email（全組織で一意）',
  display_name           VARCHAR(255) COMMENT '表示名（招待受諾時に設定）',
  password_hash          VARCHAR(255) COMMENT 'パスワードhash（招待受諾時に設定。BCrypt）',
  status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状態: ACTIVE / SUSPENDED（停止時はsession失効）',
  mfa_policy             VARCHAR(20)  NOT NULL DEFAULT 'REQUIRED' COMMENT 'MFA方針（既定REQUIRED=全user必須）',
  notify_email           TINYINT      NOT NULL DEFAULT 1 COMMENT 'email通知設定（1=通知する。R4.1）',
  totp_secret_encrypted  VARCHAR(255) COMMENT 'TOTP secret暗号化値（平文は保存しない）',
  totp_secret_key_version VARCHAR(64) COMMENT 'TOTP secretの暗号鍵version',
  mfa_enabled_at         DATETIME     COMMENT 'MFA設定完了日時（NULL=未設定でlogin不可）',
  recovery_code_hash     VARCHAR(255) COMMENT '1回限りrecovery codeのhash',
  recovery_code_used_at  DATETIME     COMMENT 'recovery code使用日時（NULL=未使用）',
  last_used_step         BIGINT       COMMENT '最後に受理したTOTP step（同一コードの再使用をCASで拒否）',
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

CREATE TABLE IF NOT EXISTS t_portal_user_permission (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  user_id        BIGINT       NOT NULL COMMENT 'portal user ID',
  permission_key VARCHAR(100) NOT NULL COMMENT '権限キー（例: document.view / acceptance.operate / availability.manage / bank-account.request）',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  UNIQUE KEY uk_portal_user_permission (user_id, permission_key),
  CONSTRAINT fk_portal_user_perm_user FOREIGN KEY (user_id) REFERENCES t_portal_user (id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータルユーザー権限';

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

-- ---- 7) ポータルsession（S13 T083 F2。V1 fresh baseline; V104_1がlegacy forward migration） ----
-- 生tokenは保存せずSHA-256 hashのみ保存する。内部t_user_session（V63）と同じ設計方針。
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

-- ---- 8) ポータル操作監査ログ（S13 T086 B1。V1 fresh baseline; V104_3がlegacy forward migration） ----
-- R4.2: portalのdownload/検収/提出/口座変更を外部user/組織/IP/時刻で監査する（append-only）。
CREATE TABLE IF NOT EXISTS t_portal_access_log (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  portal_user_id BIGINT       NOT NULL COMMENT 'portal user ID（論理削除後の監査参照に備えFKなし）',
  portal_org_id  BIGINT       NOT NULL COMMENT 'portal組織ID（FKなし）',
  email          VARCHAR(255) NOT NULL COMMENT 'portal user email',
  org_type       VARCHAR(20)  NOT NULL COMMENT '組織種別: CUSTOMER / BP',
  action         VARCHAR(50)  NOT NULL COMMENT '操作（DOWNLOAD_QUOTATION / ACCEPT / REJECT / SUBMIT / CONFIRM_RECEIPT / BANK_REQUEST 等）',
  target_type    VARCHAR(50)  COMMENT '対象種別（QUOTATION / SALES_ORDER / CONTRACT / ACCEPTANCE / INVOICE / BP_PAYMENT 等）',
  target_id      BIGINT       COMMENT '対象ID',
  ip_hash        VARCHAR(64)  COMMENT '接続元IPのSHA-256 hash（平文IPは保存しない）',
  user_agent     VARCHAR(512) COMMENT 'User-Agent',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '記録日時',
  INDEX idx_portal_access_log_org (portal_org_id, created_at),
  INDEX idx_portal_access_log_user (portal_user_id, created_at),
  INDEX idx_portal_access_log_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータル操作監査ログ（append-only）';

-- ============================================================
-- 要員セルフサービスポータルV2 (S14 / V105統合baseline)
-- 本specの増分migrationはV105。V1はfresh用の統合baselineとして同一shapeを定義する。
-- ============================================================
DROP TABLE IF EXISTS t_expense_accounting_job;
DROP TABLE IF EXISTS t_expense_request;
DROP TABLE IF EXISTS t_one_on_one_request;
DROP TABLE IF EXISTS t_survey_response;
DROP TABLE IF EXISTS t_survey_campaign;
DROP TABLE IF EXISTS m_survey_template;
DROP TABLE IF EXISTS t_engineer_change_request;

CREATE TABLE t_engineer_change_request (
  id                  BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id         BIGINT       NOT NULL COMMENT '申請元要員ID',
  request_type        VARCHAR(30)  NOT NULL COMMENT 'profile.change / skill.change / career.change',
  payload_json        TEXT         NOT NULL COMMENT '申請内容（type別DTOのallowlistのみを反映したJSON）',
  diff_json           TEXT         NOT NULL COMMENT 'before/after diff',
  reason              VARCHAR(1000) NULL COMMENT '申請理由',
  attachment_document_id BIGINT    NULL COMMENT '添付書類ID（領収書/証明書等）',
  status              VARCHAR(20)  NOT NULL DEFAULT '下書き' COMMENT '下書き/申請中/承認済/反映済/取下げ',
  approval_request_id BIGINT       NULL COMMENT '承認ワークフロー申請ID（approval engine連携）',
  applied_at          DATETIME     NULL COMMENT 'master反映日時（NULL=未反映）',
  version             INT          NOT NULL DEFAULT 0 COMMENT '楽観ロック',
  created_at          DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag        TINYINT       NOT NULL DEFAULT 0,
  INDEX idx_ecr_engineer_status (engineer_id, status),
  INDEX idx_ecr_approval (approval_request_id),
  CONSTRAINT fk_ecr_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
  CONSTRAINT chk_ecr_type CHECK (request_type IN ('profile.change', 'skill.change', 'career.change')),
  CONSTRAINT chk_ecr_status CHECK (status IN ('下書き', '申請中', '承認済', '反映済', '取下げ'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員プロフィール/スキル変更申請';

CREATE TABLE t_expense_request (
  id                  BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id         BIGINT        NOT NULL COMMENT '申請元要員ID',
  expense_no          VARCHAR(30)   NULL COMMENT '経費番号（初回申請時にEX-{id}を採番）',
  expense_date        DATE          NOT NULL COMMENT '経費発生日',
  category            VARCHAR(30)   NOT NULL COMMENT '交通費/立替経費（本人は任意の科目codeを送れない。design §4）',
  amount              DECIMAL(14,0) NOT NULL COMMENT '金額（円）',
  customer_id         BIGINT        NULL COMMENT '顧客ID（任意）',
  project_id          BIGINT        NULL COMMENT '案件ID（任意）',
  description         VARCHAR(1000) NULL COMMENT '理由',
  receipt_document_id BIGINT        NULL COMMENT '領収書の文書台帳ID（t_document。scan=CLEAN必須）',
  status              VARCHAR(20)   NOT NULL DEFAULT '下書き' COMMENT '下書き/申請中/承認済/会計連携済/支払済',
  approval_request_id BIGINT        NULL COMMENT '承認ワークフロー申請ID',
  accounting_job_id   BIGINT        NULL COMMENT '会計連携job ID（UNIQUE。二重連携防止）',
  paid_at             DATETIME      NULL COMMENT '支払日時（NULL=未払）',
  version             INT           NOT NULL DEFAULT 0 COMMENT '楽観ロック',
  created_at          DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag        TINYINT       NOT NULL DEFAULT 0,
  UNIQUE KEY uk_expense_no (expense_no),
  UNIQUE KEY uk_expense_accounting_job (accounting_job_id),
  INDEX idx_expense_engineer_status (engineer_id, status),
  INDEX idx_expense_receipt (receipt_document_id),
  INDEX idx_expense_approval (approval_request_id),
  CONSTRAINT fk_expense_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
  CONSTRAINT fk_expense_receipt_document FOREIGN KEY (receipt_document_id) REFERENCES t_document(id),
  CONSTRAINT chk_expense_category CHECK (category IN ('交通費', '立替経費')),
  CONSTRAINT chk_expense_status CHECK (status IN ('下書き', '申請中', '承認済', '会計連携済', '支払済')),
  CONSTRAINT chk_expense_amount CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員経費申請';

CREATE TABLE t_expense_accounting_job (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  expense_request_id BIGINT     NOT NULL COMMENT '経費申請ID（UNIQUE。冪等）',
  status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCEEDED/FAILED',
  correlation_id   VARCHAR(64)  NULL COMMENT '相関ID（外部連携追跡）',
  payload_hash     CHAR(64)     NOT NULL COMMENT '送信payloadのSHA-256',
  attempt_count    INT          NOT NULL DEFAULT 0 COMMENT '試行回数',
  next_attempt_at  DATETIME     NULL COMMENT '再試行可能時刻',
  last_error_code  VARCHAR(40)  NULL COMMENT 'PIIを含まない分類code',
  sent_at          DATETIME     NULL COMMENT '外部送信成功日時',
  created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_expense_job_request (expense_request_id),
  INDEX idx_expense_job_status (status, next_attempt_at),
  CONSTRAINT fk_expense_job_request FOREIGN KEY (expense_request_id) REFERENCES t_expense_request(id),
  CONSTRAINT chk_expense_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='経費の会計連携outbox job';

CREATE TABLE t_one_on_one_request (
  id                   BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id          BIGINT        NOT NULL COMMENT '申請要員ID',
  counterpart_user_id  BIGINT        NOT NULL COMMENT '相手（担当営業/上長等の内部ユーザー）',
  candidate_dates_json TEXT          NOT NULL COMMENT '候補日JSON（申請時点の希望日一覧）',
  scheduled_at         DATE          NULL COMMENT '確定日程（NULL=未確定）',
  status               VARCHAR(20)   NOT NULL DEFAULT '申請' COMMENT '申請/日程確定/実施済/取消',
  employee_visible_note VARCHAR(2000) NULL COMMENT '要員本人に公開する実施記録',
  private_note_ref     VARCHAR(64)   NULL COMMENT 'confidential相談の参照（HR/管理者のみ可視）',
  created_at           DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag         TINYINT       NOT NULL DEFAULT 0,
  INDEX idx_1on1_engineer_status (engineer_id, status),
  INDEX idx_1on1_counterpart (counterpart_user_id),
  CONSTRAINT fk_1on1_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
  CONSTRAINT chk_1on1_status CHECK (status IN ('申請', '日程確定', '実施済', '取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='1on1申請と実施記録';

CREATE TABLE m_survey_template (
  id              BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  template_key    VARCHAR(50)   NOT NULL COMMENT 'テンプレートキー',
  title           VARCHAR(200)  NOT NULL COMMENT 'テンプレート名',
  description     VARCHAR(1000) NULL COMMENT '説明',
  questions_json  TEXT          NOT NULL COMMENT '質問定義JSON（key/text/type/confidential_flag）',
  status          VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/ARCHIVED',
  version         INT           NOT NULL DEFAULT 0 COMMENT '回答時のtemplate version（回答に固定）',
  created_by      BIGINT        NULL COMMENT '作成ユーザー',
  created_at      DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT       NOT NULL DEFAULT 0,
  UNIQUE KEY uk_survey_template_key (template_key),
  CONSTRAINT chk_survey_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サーベイテンプレート';

CREATE TABLE t_survey_campaign (
  id           BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  template_id  BIGINT        NOT NULL COMMENT 'テンプレートID',
  title        VARCHAR(200)  NOT NULL COMMENT 'キャンペーン名',
  template_snapshot_json LONGTEXT NULL COMMENT 'campaign開始時の質問定義snapshot',
  template_snapshot_version INT NULL COMMENT 'campaign開始時の質問定義version',
  period_from  DATE          NULL COMMENT '回答期間開始',
  period_to    DATE          NULL COMMENT '回答期間終了',
  status       VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/CLOSED',
  created_by   BIGINT        NULL COMMENT '作成ユーザー',
  created_at   DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT       NOT NULL DEFAULT 0,
  INDEX idx_survey_campaign_status (status),
  CONSTRAINT fk_survey_campaign_template FOREIGN KEY (template_id) REFERENCES m_survey_template(id),
  CONSTRAINT chk_survey_campaign_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
  CONSTRAINT chk_survey_campaign_period CHECK (period_to IS NULL OR period_from IS NULL OR period_from <= period_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サーベイキャンペーン';

CREATE TABLE t_survey_response (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  campaign_id        BIGINT       NOT NULL COMMENT 'キャンペーンID',
  engineer_id        BIGINT       NOT NULL COMMENT '回答要員ID',
  question_key       VARCHAR(50)  NOT NULL COMMENT '質問キー',
  answer_value       INT          NULL COMMENT 'scale回答（1〜5。NULL=未回答）',
  comment            VARCHAR(1000) NULL COMMENT '任意コメント',
  comment_visibility VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/CONFIDENTIAL',
  consent_flag       TINYINT      NOT NULL DEFAULT 0 COMMENT '回答同意フラグ',
  template_version   INT          NOT NULL COMMENT '回答時template version',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag       TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_survey_response (campaign_id, engineer_id, question_key),
  INDEX idx_survey_response_engineer (engineer_id),
  CONSTRAINT fk_survey_response_campaign FOREIGN KEY (campaign_id) REFERENCES t_survey_campaign(id),
  CONSTRAINT chk_survey_answer CHECK (answer_value IS NULL OR (answer_value BETWEEN 1 AND 5)),
  CONSTRAINT chk_survey_visibility CHECK (comment_visibility IN ('PUBLIC', 'CONFIDENTIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サーベイ回答';

-- ============================================================
-- 会計・支払連携基盤 (accounting-payment-integration / S15 / V106)
-- ============================================================
CREATE TABLE IF NOT EXISTS m_integration_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    legal_entity_id BIGINT NULL COMMENT '法人ID (NULL=共通/全社)',
    provider VARCHAR(32) NOT NULL COMMENT 'プロバイダ (freee / csv / mock)',
    product VARCHAR(32) NOT NULL COMMENT 'プロダクト種別 (accounting / payroll)',
    external_company_id BIGINT NULL COMMENT '外部事業所ID (freee company_id等)',
    company_name VARCHAR(255) NULL COMMENT '外部事業所/会社名',
    encrypted_tokens TEXT NULL COMMENT '暗号化されたアクセストークン/リフレッシュトークンJSON',
    expires_at DATETIME NULL COMMENT 'トークン有効期限',
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED' COMMENT '接続状態 (CONNECTED / REAUTH_REQUIRED / DISCONNECTED)',
    connected_by BIGINT NULL COMMENT '接続実行ユーザーID',
    connected_at DATETIME NULL COMMENT '接続日時',
    last_refreshed_at DATETIME NULL COMMENT 'トークン最終リフレッシュ日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_int_conn (tenant_id, legal_entity_id, provider, product, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部サービス連携接続マスタ';

CREATE TABLE IF NOT EXISTS m_external_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL COMMENT '接続ID (m_integration_connection.id)',
    object_type VARCHAR(64) NOT NULL COMMENT 'マッピング対象種別 (CUSTOMER_PARTNER, BP_PARTNER, ACCOUNT_SALES, TAX_SALES_10 等)',
    internal_id BIGINT NULL COMMENT '内部エンティティID',
    internal_code VARCHAR(64) NOT NULL COMMENT '内部コード/キー',
    external_id VARCHAR(64) NOT NULL COMMENT '外部システムID',
    external_code VARCHAR(64) NULL COMMENT '外部システムコード',
    payload_snapshot TEXT NULL COMMENT '検証時点の外部マスタスナップショットJSON',
    verified_at DATETIME NULL COMMENT '検証日時 (NULL=未検証、送信不可)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ext_mapping (connection_id, object_type, internal_code, deleted_flag),
    INDEX idx_ext_mapping_conn (connection_id, object_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部マスタマッピング';

CREATE TABLE IF NOT EXISTS t_integration_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL COMMENT '接続ID',
    job_type VARCHAR(64) NOT NULL COMMENT 'ジョブ種別 (SALES_INVOICE_SYNC, SALES_INVOICE_CANCEL, PURCHASE_DEAL_SYNC, EXPENSE_DEAL_SYNC, PAYMENT_SYNC)',
    target_type VARCHAR(64) NOT NULL COMMENT '対象種別 (INVOICE, BP_PAYMENT, EXPENSE_REQUEST, PAYMENT)',
    target_id BIGINT NOT NULL COMMENT '対象エンティティID',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '冪等性キー',
    payload_hash VARCHAR(64) NOT NULL COMMENT '送信ペイロードSHA-256ハッシュ',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状態 (PENDING / RUNNING / SUCCEEDED / RETRYABLE / FAILED / CANCELLED)',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '試行回数',
    max_attempts INT NOT NULL DEFAULT 5 COMMENT '最大試行回数',
    next_retry_at DATETIME NULL COMMENT '次回再試行予定日時',
    external_id VARCHAR(128) NULL COMMENT '外部取引/伝票ID',
    provider_request_id VARCHAR(128) NULL COMMENT '外部リクエストID (X-Freee-Request-ID等)',
    error_code VARCHAR(64) NULL COMMENT '分類エラーコード',
    error_message_safe VARCHAR(500) NULL COMMENT '安全なエラー要約 (PII/Secret除外)',
    sent_at DATETIME NULL COMMENT '送信成功日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_int_job_idempotency (idempotency_key, deleted_flag),
    INDEX idx_int_job_status (status, next_retry_at),
    INDEX idx_int_job_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部連携非同期ジョブ (Outbox)';

CREATE TABLE IF NOT EXISTS t_integration_job_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL COMMENT 'ジョブID',
    from_status VARCHAR(32) NULL COMMENT '遷移前状態',
    to_status VARCHAR(32) NOT NULL COMMENT '遷移後状態',
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '発生日時',
    safe_detail VARCHAR(1000) NULL COMMENT '安全な詳細情報 (PII/Secret除外)',
    INDEX idx_job_event_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連携ジョブ状態遷移履歴';