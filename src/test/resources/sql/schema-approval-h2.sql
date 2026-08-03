-- テスト用(冪等): V75__approval_workflow.sql のDDL相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(JSON型/ENGINE/COLLATE/CHECK制約構文差異)はH2方言へ読み替える
-- （platform-invariants §4.3: MySQL migrationをH2 replayリストへ直接足さない）。
-- 外部キーは張らない（schema-crm-h2.sql等と同じ方針。共有インメモリH2は複数contextで
-- schema-locationsを再実行するため、FKを張るとDROP TABLE順序で他specの再生成が失敗しうる）。

DROP TABLE IF EXISTS t_approval_participant CASCADE;
DROP TABLE IF EXISTS t_approval_delegation_type CASCADE;
DROP TABLE IF EXISTS t_approval_action CASCADE;
DROP TABLE IF EXISTS t_approval_delegation CASCADE;
DROP TABLE IF EXISTS t_approval_request CASCADE;
DROP TABLE IF EXISTS m_approval_route_step CASCADE;
DROP TABLE IF EXISTS m_approval_route CASCADE;

CREATE TABLE IF NOT EXISTS m_approval_route (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL DEFAULT 1,
  request_type    VARCHAR(50)  NOT NULL,
  organization_id BIGINT,
  min_amount      DECIMAL(14,0),
  max_amount      DECIMAL(14,0),
  version_no      INT          NOT NULL DEFAULT 1,
  valid_from      DATE         NOT NULL,
  valid_to        DATE,
  active_flag     TINYINT      NOT NULL DEFAULT 1,
  created_by      BIGINT,
  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag    TINYINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_approval_route_lookup
  ON m_approval_route(tenant_id, request_type, active_flag, valid_from, valid_to);

CREATE TABLE IF NOT EXISTS m_approval_route_step (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
  route_id       BIGINT       NOT NULL,
  step_no        INT          NOT NULL,
  parallel_group INT          NOT NULL,
  approver_type  VARCHAR(30)  NOT NULL,
  approver_value VARCHAR(255),
  sla_hours      INT,
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag   TINYINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_approval_route_step_route ON m_approval_route_step(route_id, step_no);

CREATE TABLE IF NOT EXISTS t_approval_request (
  id                  BIGINT        AUTO_INCREMENT PRIMARY KEY,
  request_no          VARCHAR(30),
  request_type        VARCHAR(50)   NOT NULL,
  target_type         VARCHAR(30)   NOT NULL,
  target_id           BIGINT,
  target_version      BIGINT,
  applicant_id        BIGINT        NOT NULL,
  organization_id     BIGINT,
  amount_snapshot     DECIMAL(14,0),
  payload_json        CLOB          NOT NULL,
  diff_json           CLOB,
  route_snapshot_json CLOB          NOT NULL,
  status              VARCHAR(20)   NOT NULL DEFAULT 'draft',
  current_step        INT           NOT NULL DEFAULT 0,
  round_no            INT           NOT NULL DEFAULT 1,
  current_step_started_at DATETIME,
  requested_at        DATETIME,
  finalized_at        DATETIME,
  idempotency_key     VARCHAR(100),
  version             INT           NOT NULL DEFAULT 1,
  created_by          BIGINT,
  created_at          DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME      DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_approval_request_no UNIQUE (request_no),
  CONSTRAINT uk_approval_request_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_approval_request_applicant ON t_approval_request(applicant_id, status);
CREATE INDEX IF NOT EXISTS idx_approval_request_target ON t_approval_request(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_approval_request_status ON t_approval_request(status, current_step);

CREATE TABLE IF NOT EXISTS t_approval_action (
  id                    BIGINT      AUTO_INCREMENT PRIMARY KEY,
  request_id            BIGINT      NOT NULL,
  round_no              INT         NOT NULL DEFAULT 1,
  step_no               INT         NOT NULL,
  slot_index            INT         NOT NULL DEFAULT 0,
  approver_user_id      BIGINT      NOT NULL,
  approver_slot_user_id BIGINT      NOT NULL,
  action                VARCHAR(20) NOT NULL,
  comment               CLOB,
  delegated_from        BIGINT,
  acted_at              DATETIME    NOT NULL,
  CONSTRAINT uk_approval_action_slot UNIQUE (request_id, round_no, step_no, approver_slot_user_id)
);
CREATE INDEX IF NOT EXISTS idx_approval_action_request ON t_approval_action(request_id, round_no, step_no);

CREATE TABLE IF NOT EXISTS t_approval_delegation (
  id                 BIGINT      AUTO_INCREMENT PRIMARY KEY,
  from_user_id       BIGINT      NOT NULL,
  to_user_id         BIGINT      NOT NULL,
  valid_from         DATE        NOT NULL,
  valid_to           DATE,
  request_types_json CLOB,
  reason             VARCHAR(500),
  approved_by        BIGINT,
  created_by         BIGINT,
  created_at         DATETIME    DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME    DEFAULT CURRENT_TIMESTAMP,
  deleted_flag       TINYINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_approval_delegation_lookup
  ON t_approval_delegation(from_user_id, valid_from, valid_to);

CREATE TABLE IF NOT EXISTS t_approval_participant (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
  request_id       BIGINT       NOT NULL,
  user_id          BIGINT       NOT NULL,
  participant_role VARCHAR(20)  NOT NULL,
  round_no         INT          NOT NULL DEFAULT 1,
  CONSTRAINT uk_participant UNIQUE (request_id, round_no, user_id, participant_role)
);
CREATE INDEX IF NOT EXISTS idx_participant_user
  ON t_approval_participant(user_id, participant_role);

CREATE TABLE IF NOT EXISTS t_approval_delegation_type (
  delegation_id BIGINT      NOT NULL,
  request_type  VARCHAR(50) NOT NULL,
  CONSTRAINT pk_approval_delegation_type PRIMARY KEY (delegation_id, request_type)
);

-- V78対象entityの楽観ロック列。MySQLのV78と同じ列形状にする。
ALTER TABLE t_quotation ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

-- action permission seed（V75と同内容。H2側はt_permission_group_actionもschema-locations経由で
-- 別ファイルにより先に作成済みのため、ここではDMLのみ投入する）
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'approval.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-hr', 'role-manager')
  AND NOT EXISTS (
    SELECT 1 FROM t_permission_group_action x
    WHERE x.tenant_id = 'default' AND x.group_id = g.id AND x.action_key = 'approval.*'
  );

-- V76: A1のページ/APIをMenuPermissionFilterから到達可能にする。
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'approval', '承認ワークフロー', '/approval', '/api/approval', 58
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'approval');
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id FROM (SELECT '管理者' AS role UNION SELECT '営業' UNION SELECT 'HR' UNION SELECT 'マネージャー') r
CROSS JOIN m_menu m WHERE m.menu_key = 'approval'
AND NOT EXISTS (SELECT 1 FROM t_role_menu x WHERE x.role = r.role AND x.menu_id = m.id);
