-- エンティティとテーブル定義の不一致（共通カラムの有無）を検証するための最小スキーマ。
-- 本番 001_create_tables.sql に合わせ、以下の「共通カラムが存在しない」状態を再現する:
--   t_proposal        : created_at 列を持たない（作成日時は proposed_at）
--   t_ai_log          : updated_at / deleted_flag 列を持たない
--   m_skill_tag       : updated_at / deleted_flag 列を持たない
--   m_email_template  : deleted_flag 列を持たない
-- H2(MySQLモード)が解釈できない ENUM / ENGINE / インライン INDEX は使用しない。
SET REFERENTIAL_INTEGRITY FALSE;
DROP TABLE IF EXISTS t_mail_delivery CASCADE;
CREATE TABLE t_mail_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient VARCHAR(320) NOT NULL,
  subject VARCHAR(500) NOT NULL,
  body VARCHAR(1000000) NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000),
  queued_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  sent_at DATETIME,
  failed_at DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
DROP TABLE IF EXISTS t_proposal CASCADE;
CREATE TABLE t_proposal (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id         BIGINT,
  project_id          BIGINT,
  proposed_unit_price DECIMAL(10,0),
  status              VARCHAR(20) DEFAULT '書類選考中',
  skill_sheet_path    VARCHAR(500),
  proposal_email_text TEXT,
  ai_match_score      DECIMAL(5,2),
  match_reason        TEXT,
  remarks             TEXT,
  proposed_by         BIGINT,
  proposed_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  closed_at           DATETIME,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_ai_log CASCADE;
CREATE TABLE t_ai_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_type   VARCHAR(20),
  request_params VARCHAR(2000),
  response_text  TEXT,
  tokens_used    INT,
  cost_jpy       DECIMAL(10,4),
  created_by     BIGINT,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS m_skill_tag CASCADE;
CREATE TABLE m_skill_tag (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  skill_name VARCHAR(100),
  category   VARCHAR(20),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS m_email_template CASCADE;
CREATE TABLE m_email_template (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_name    VARCHAR(100),
  subject_template VARCHAR(500),
  body_template    TEXT,
  template_type    VARCHAR(20),
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_notification CASCADE;
CREATE TABLE t_notification (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  type        VARCHAR(30)  NOT NULL,
  title       VARCHAR(200) NOT NULL,
  message     VARCHAR(500),
  link_url    VARCHAR(300),
  menu_key    VARCHAR(50),
  dedupe_key  VARCHAR(200) NOT NULL UNIQUE,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_notification_read CASCADE;
CREATE TABLE t_notification_read (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  read_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notification_read (notification_id, user_id)
);

DROP TABLE IF EXISTS t_proposal_history CASCADE;
CREATE TABLE t_proposal_history (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  proposal_id     BIGINT NOT NULL,
  from_status     VARCHAR(30),
  to_status       VARCHAR(30) NOT NULL,
  changed_by      BIGINT,
  changed_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  remarks         TEXT
);

DROP TABLE IF EXISTS m_menu CASCADE;
CREATE TABLE m_menu (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_key   VARCHAR(50) NOT NULL,
  menu_name  VARCHAR(100),
  path       VARCHAR(200),
  parent_id  BIGINT,
  sort_order INT DEFAULT 0
);

DROP TABLE IF EXISTS t_role_menu CASCADE;
CREATE TABLE t_role_menu (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  role     VARCHAR(50) NOT NULL,
  menu_id  BIGINT NOT NULL
);
