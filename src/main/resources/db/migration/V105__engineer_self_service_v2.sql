-- ============================================================
-- 要員セルフサービスポータルV2 (S14 engineer-self-service-portal-v2 / V105)
-- プロフィール変更申請・経費・1on1・surveyのDDLとseed。
-- 本specの設計正は .kiro/specs/engineer-self-service-portal-v2/design.md（決定表確定済み）。
-- 本人scopeは engineer-account link (t_engineer_account_link) から解決し、リクエストのengineerIdを信用しない。
-- ============================================================

-- ============================================================
-- 1. プロフィール/スキル変更申請 (design §1)
--    status機械: 下書き→申請中→承認済→反映済 / 取下げ。
--    差戻し・競合はapproval engineの status=returned/conflict をUIで導出する（leaveと同じ扱い）。
--    applied_at IS NULL = 未反映（承認済でも反映前がありうる。design §6.1）。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_engineer_change_request (
  id                  BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id         BIGINT       NOT NULL COMMENT '申請元要員ID',
  request_type        VARCHAR(30)  NOT NULL COMMENT 'profile.change / skill.change / career.change',
  payload_json        TEXT         NOT NULL COMMENT '申請内容（type別DTOのallowlistのみを反映したJSON）',
  diff_json           TEXT         NOT NULL COMMENT 'before/after diff',
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

-- ============================================================
-- 2. 経費申請 (design §1/§4)
--    金額は円。accounting_job_id UNIQUEで会計連携の冪等を担保（design §6.3）。
--    承認済経費の領収書差替えは再申請（R3.3）。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_expense_request (
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

-- ============================================================
-- 3. 会計連携outbox job (design §4/§6.3)
--    外部APIはDB transaction外で呼ぶ。UNIQUE(expense_request_id)で同一経費から2件のjobを作らない。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_expense_accounting_job (
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

-- ============================================================
-- 4. 1on1申請/実施記録 (design §1/§6.2)
--    状態機械: 申請→日程確定→実施済 / 取消（状態CAS）。
--    private_note_ref はconfidential相談の参照。通常のRetentionRisk DTOへ出さない（design §5/§6.2）。
-- ============================================================
CREATE TABLE IF NOT EXISTS t_one_on_one_request (
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

-- ============================================================
-- 5. survey (design §1/§5/§6.1)
--    定型scaleと任意commentを分離。未回答は平均値の母数へ含めない。
--    comment_visibility=CONFIDENTIAL はHR/管理者のみ可視（R4.3）。
-- ============================================================
CREATE TABLE IF NOT EXISTS m_survey_template (
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

CREATE TABLE IF NOT EXISTS t_survey_campaign (
  id           BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  template_id  BIGINT        NOT NULL COMMENT 'テンプレートID',
  title        VARCHAR(200)  NOT NULL COMMENT 'キャンペーン名',
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

CREATE TABLE IF NOT EXISTS t_survey_response (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  campaign_id        BIGINT       NOT NULL COMMENT 'キャンペーンID',
  engineer_id        BIGINT       NOT NULL COMMENT '回答要員ID',
  question_key       VARCHAR(50)  NOT NULL COMMENT '質問キー',
  answer_value       INT          NULL COMMENT 'scale回答（1〜5。NULL=未回答）',
  comment            VARCHAR(1000) NULL COMMENT '任意コメント',
  comment_visibility VARCHAR(10)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/CONFIDENTIAL',
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
-- 6. skill sheet確認日/確認version（design §1/§6.1）
--    既存document/engineer link (t_document_link) へ追加。確認ごとに更新。
--    NULL=未確認。客先提出前チェックの対象。V1統合baselineにも同列を定義済みのため、
--    information_schema存在判定ガード付きALTER（legacy DBのみ追加。V60と同じpattern）。
-- ============================================================
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_document_link ADD COLUMN skill_sheet_confirmed_at DATETIME NULL COMMENT ''スキルシート確認日時（NULL=未確認）'' AFTER target_id, ADD COLUMN skill_sheet_confirmed_version VARCHAR(64) NULL COMMENT ''確認時のdocument version'' AFTER skill_sheet_confirmed_at',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_document_link' AND column_name = 'skill_sheet_confirmed_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 7. 文書種別seed（m_document_type。INSERT IGNOREで既存を上書きしない）
--    保存期間は法定baseline: 経費領収書=7年（税法の帳簿保存）、技能シート=3年、private note=3年。
-- ============================================================
INSERT IGNORE INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported) VALUES
  ('SKILL_SHEET',   'スキルシート',   'INTERNAL',  3, 'TRANSACTION_DATE', 1),
  ('RECEIPT',       '経費領収書',     'INCOMING',  7, 'TRANSACTION_DATE', 1),
  ('PRIVATE_NOTE',  '1on1相談メモ',   'INTERNAL',  3, 'TRANSACTION_DATE', 1);

-- ============================================================
-- 8. メニュー・権限seed
--    要員の各 /my/** ページ（path_prefix/api_prefixは最長一致でmy-timesheetより具体化）。
--    管理画面はdecision table §6.2の母集団に合わせたロールへ付与する。
--    ※ 管理API rootはActionPermissionResolver.RESOURCE_NAMESへコード側で登録する（CRM-R2-P1-01と同罠の防止）。
-- ============================================================
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('myDashboard',    'マイダッシュボード',   '/my/dashboard',    '/api/my/dashboard',    93),
  ('myProfile',      'プロフィール・スキル', '/my/profile',      '/api/my/profile',      94),
  ('myPayroll',      '給与明細',             '/my/payroll',      '/api/my/payroll',      95),
  ('myExpenses',     '経費申請',             '/my/expenses',     '/api/my/expenses',     96),
  ('myOneOnOnes',    '1on1',                 '/my/one-on-ones',  '/api/my/one-on-ones',  97),
  ('mySurveys',      'サーベイ',             '/my/surveys',      '/api/my/surveys',      98);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '要員') r
CROSS JOIN m_menu m
WHERE m.menu_key IN ('myDashboard', 'myProfile', 'myPayroll', 'myExpenses', 'myOneOnOnes', 'mySurveys');

-- 管理画面（変更申請: HR/管理者/マネージャー、経費: 管理者/マネージャー、1on1: 全4管理ロール、survey: HR/管理者/マネージャー）
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('engineerChangeRequests', '変更申請管理', '/engineer-change-requests', '/api/engineer-change-requests', 99),
  ('expenseManagement',      '経費管理',     '/expenses',                 '/api/expense-requests',         100),
  ('oneOnOneManagement',     '1on1管理',     '/one-on-ones',              '/api/one-on-ones',              101),
  ('surveyManagement',       'サーベイ管理', '/surveys',                  '/api/surveys',                  102);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'engineerChangeRequests';

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'expenseManagement';

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー' UNION ALL SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'oneOnOneManagement';

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'surveyManagement';

-- 権限seed（baseline+deny方式のため、group割当済み非管理者はseedが無いと機能全体で403になる）
-- decision table §6.2: 変更申請=HR/マネージャー、経費=マネージャー、1on1=営業/HR/マネージャー、survey=HR/マネージャー
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'engineer-change-request.*' AS action_key) a
WHERE g.tenant_id = 'default' AND g.enabled = 1
  AND g.group_key IN ('role-hr', 'role-manager');

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'expense-request.*' AS action_key) a
WHERE g.tenant_id = 'default' AND g.enabled = 1
  AND g.group_key IN ('role-manager');

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'one-on-one.*' AS action_key) a
WHERE g.tenant_id = 'default' AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-hr', 'role-manager');

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'survey.*' AS action_key) a
WHERE g.tenant_id = 'default' AND g.enabled = 1
  AND g.group_key IN ('role-hr', 'role-manager');

-- ============================================================
-- 9. システム設定seed（INSERT IGNOREで既存の管理者変更を上書きしない）
-- ============================================================
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('survey.min-answers', '3', 'サーベイ集計の最低回答数（未満の組織/segmentは匿名性保護のため非表示。design §5）'),
  ('expense.accounting.provider', 'mock', '経費の会計連携provider（mock/freee。外部連携はS15 accounting-payment-integration）');
