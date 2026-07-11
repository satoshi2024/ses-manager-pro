-- エンティティとテーブル定義の不一致（共通カラムの有無）を検証するための最小スキーマ。
-- 本番 001_create_tables.sql に合わせ、以下の「共通カラムが存在しない」状態を再現する:
--   t_proposal        : created_at 列を持たない（作成日時は proposed_at）
--   t_ai_log          : updated_at / deleted_flag 列を持たない
--   m_skill_tag       : updated_at / deleted_flag 列を持たない
--   m_email_template  : deleted_flag 列を持たない
-- H2(MySQLモード)が解釈できない ENUM / ENGINE / インライン INDEX は使用しない。

DROP TABLE IF EXISTS t_proposal;
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

DROP TABLE IF EXISTS t_ai_log;
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

DROP TABLE IF EXISTS m_skill_tag;
CREATE TABLE m_skill_tag (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  skill_name VARCHAR(100),
  category   VARCHAR(20),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS m_email_template;
CREATE TABLE m_email_template (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_name    VARCHAR(100),
  subject_template VARCHAR(500),
  body_template    TEXT,
  template_type    VARCHAR(20),
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP
);
