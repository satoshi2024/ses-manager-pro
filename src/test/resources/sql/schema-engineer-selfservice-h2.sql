-- テスト用(冪等): V105__engineer_self_service_v2.sql のDDL相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(ENGINE/COLLATE/COMMENT)はH2方言へ読み替える（platform-invariants §4.3）。
-- 共有H2は複数contextでschema-locationsを再実行するため、冪等に再構築する。
-- t_engineer_change_request等のテーブル本体はV1 replay(統合baseline)でも作成される。
-- t_document_linkのskill_sheet確認列もV1 replayに含まれるため、本ファイルでは再作成しない。

DROP TABLE IF EXISTS t_expense_accounting_job CASCADE;
DROP TABLE IF EXISTS t_expense_request CASCADE;
DROP TABLE IF EXISTS t_one_on_one_request CASCADE;
DROP TABLE IF EXISTS t_survey_response CASCADE;
DROP TABLE IF EXISTS t_survey_campaign CASCADE;
DROP TABLE IF EXISTS m_survey_template CASCADE;
DROP TABLE IF EXISTS t_engineer_change_request CASCADE;

CREATE TABLE t_engineer_change_request (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id         BIGINT NOT NULL,
  request_type        VARCHAR(30) NOT NULL,
  payload_json        CLOB NOT NULL,
  diff_json           CLOB NOT NULL,
  reason              VARCHAR(1000),
  attachment_document_id BIGINT,
  status              VARCHAR(20) NOT NULL DEFAULT '下書き',
  approval_request_id BIGINT,
  applied_at          DATETIME,
  version             INT NOT NULL DEFAULT 0,
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_ecr_type CHECK (request_type IN ('profile.change', 'skill.change', 'career.change')),
  CONSTRAINT chk_ecr_status CHECK (status IN ('下書き', '申請中', '承認済', '反映済', '取下げ'))
);
CREATE INDEX idx_ecr_engineer_status ON t_engineer_change_request(engineer_id, status);
CREATE INDEX idx_ecr_approval ON t_engineer_change_request(approval_request_id);

CREATE TABLE t_expense_request (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id         BIGINT NOT NULL,
  expense_no          VARCHAR(30),
  expense_date        DATE NOT NULL,
  category            VARCHAR(30) NOT NULL,
  amount              DECIMAL(14,0) NOT NULL,
  customer_id         BIGINT,
  project_id          BIGINT,
  description         VARCHAR(1000),
  receipt_document_id BIGINT,
  status              VARCHAR(20) NOT NULL DEFAULT '下書き',
  approval_request_id BIGINT,
  accounting_job_id   BIGINT,
  paid_at             DATETIME,
  version             INT NOT NULL DEFAULT 0,
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_expense_category CHECK (category IN ('交通費', '立替経費', '研修費')),
  CONSTRAINT chk_expense_status CHECK (status IN ('下書き', '申請中', '承認済', '会計連携済', '支払済')),
  CONSTRAINT chk_expense_amount CHECK (amount >= 0)
);
CREATE UNIQUE INDEX uk_expense_no ON t_expense_request(expense_no);
CREATE UNIQUE INDEX uk_expense_accounting_job ON t_expense_request(accounting_job_id);
CREATE INDEX idx_expense_engineer_status ON t_expense_request(engineer_id, status);
CREATE INDEX idx_expense_receipt ON t_expense_request(receipt_document_id);
CREATE INDEX idx_expense_approval ON t_expense_request(approval_request_id);

CREATE TABLE t_expense_accounting_job (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  expense_request_id BIGINT NOT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  correlation_id     VARCHAR(64),
  payload_hash       CHAR(64) NOT NULL,
  attempt_count      INT NOT NULL DEFAULT 0,
  next_attempt_at    DATETIME,
  last_error_code    VARCHAR(40),
  sent_at            DATETIME,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_expense_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'))
);
CREATE UNIQUE INDEX uk_expense_job_request ON t_expense_accounting_job(expense_request_id);
CREATE INDEX idx_expense_job_status ON t_expense_accounting_job(status, next_attempt_at);

CREATE TABLE t_one_on_one_request (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id           BIGINT NOT NULL,
  counterpart_user_id   BIGINT NOT NULL,
  candidate_dates_json  CLOB NOT NULL,
  scheduled_at          DATE,
  status                VARCHAR(20) NOT NULL DEFAULT '申請',
  employee_visible_note VARCHAR(2000),
  private_note_ref      VARCHAR(64),
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_1on1_status CHECK (status IN ('申請', '日程確定', '実施済', '取消'))
);
CREATE INDEX idx_1on1_engineer_status ON t_one_on_one_request(engineer_id, status);
CREATE INDEX idx_1on1_counterpart ON t_one_on_one_request(counterpart_user_id);

CREATE TABLE m_survey_template (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_key   VARCHAR(50) NOT NULL,
  title          VARCHAR(200) NOT NULL,
  description    VARCHAR(1000),
  questions_json CLOB NOT NULL,
  status         VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  version        INT NOT NULL DEFAULT 0,
  created_by     BIGINT,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag   TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_survey_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);
CREATE UNIQUE INDEX uk_survey_template_key ON m_survey_template(template_key);

CREATE TABLE t_survey_campaign (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_id  BIGINT NOT NULL,
  title        VARCHAR(200) NOT NULL,
  template_snapshot_json CLOB,
  template_snapshot_version INT,
  period_from  DATE,
  period_to    DATE,
  status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_by   BIGINT,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_survey_campaign_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
  CONSTRAINT chk_survey_campaign_period CHECK (period_to IS NULL OR period_from IS NULL OR period_from <= period_to)
);
CREATE INDEX idx_survey_campaign_status ON t_survey_campaign(status);

CREATE TABLE t_survey_response (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  campaign_id        BIGINT NOT NULL,
  engineer_id        BIGINT NOT NULL,
  question_key       VARCHAR(50) NOT NULL,
  answer_value       INT,
  comment            VARCHAR(1000),
  comment_visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
  consent_flag       TINYINT NOT NULL DEFAULT 0,
  template_version   INT NOT NULL,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_survey_answer CHECK (answer_value IS NULL OR (answer_value BETWEEN 1 AND 5)),
  CONSTRAINT chk_survey_visibility CHECK (comment_visibility IN ('PUBLIC', 'CONFIDENTIAL'))
);
CREATE UNIQUE INDEX uk_survey_response ON t_survey_response(campaign_id, engineer_id, question_key);
CREATE INDEX idx_survey_response_engineer ON t_survey_response(engineer_id);

-- ---- seeds（V105相当。V1 replayには無いため本ファイルで補う） ----
INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'SKILL_SHEET', 'スキルシート', 'INTERNAL', 3, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'SKILL_SHEET');
INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'RECEIPT', '経費領収書', 'INCOMING', 7, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'RECEIPT');
INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'PRIVATE_NOTE', '1on1相談メモ', 'INTERNAL', 3, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'PRIVATE_NOTE');
INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'CHANGE_REQUEST_ATTACHMENT', '変更申請添付', 'INCOMING', 7, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'CHANGE_REQUEST_ATTACHMENT');

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myDashboard', 'マイダッシュボード', '/my/dashboard', '/api/my/dashboard', 93
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myDashboard');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myProfile', 'プロフィール・スキル', '/my/profile', '/api/my/profile', 94
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myProfile');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myPayroll', '給与明細', '/my/payroll', '/api/my/payroll', 95
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myPayroll');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myExpenses', '経費申請', '/my/expenses', '/api/my/expenses', 96
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myExpenses');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myOneOnOnes', '1on1', '/my/one-on-ones', '/api/my/one-on-ones', 97
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myOneOnOnes');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'mySurveys', 'サーベイ', '/my/surveys', '/api/my/surveys', 98
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'mySurveys');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'engineerChangeRequests', '変更申請管理', '/engineer-change-requests', '/api/engineer-change-requests', 99
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'engineerChangeRequests');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'expenseManagement', '経費管理', '/expenses', '/api/expense-requests', 100
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'expenseManagement');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'oneOnOneManagement', '1on1管理', '/one-on-ones', '/api/one-on-ones', 101
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'oneOnOneManagement');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'surveyManagement', 'サーベイ管理', '/surveys', '/api/surveys', 102
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'surveyManagement');

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '要員') r
CROSS JOIN m_menu m
WHERE m.menu_key IN ('myDashboard', 'myProfile', 'myPayroll', 'myExpenses', 'myOneOnOnes', 'mySurveys')
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'engineerChangeRequests'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'expenseManagement'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー' UNION ALL SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'oneOnOneManagement'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'surveyManagement'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'engineer-change-request.*' AS action_key UNION ALL
            SELECT 'expense-request.*' UNION ALL
            SELECT 'one-on-one.*' UNION ALL
            SELECT 'survey.*') a
WHERE g.tenant_id = 'default' AND g.enabled = 1
  AND g.group_key IN ('role-hr', 'role-manager', 'role-sales')
  AND NOT EXISTS (
    SELECT 1 FROM t_permission_group_action pga
    WHERE pga.tenant_id = 'default' AND pga.group_id = g.id AND pga.action_key = a.action_key
  );

INSERT INTO m_system_config (config_key, config_value, description)
SELECT 'survey.min-answers', '3', 'サーベイ集計の最低回答数（未満の組織/segmentは匿名性保護のため非表示。design §5）'
WHERE NOT EXISTS (SELECT 1 FROM m_system_config WHERE config_key = 'survey.min-answers');
INSERT INTO m_system_config (config_key, config_value, description)
SELECT 'expense.accounting.provider', 'mock', '経費の会計連携provider（mock/freee）'
WHERE NOT EXISTS (SELECT 1 FROM m_system_config WHERE config_key = 'expense.accounting.provider');
