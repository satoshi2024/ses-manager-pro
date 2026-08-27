-- V110: カスタマーサクセス・問い合わせ/SLA・顧客ヘルス (NF-02)

-- 1. SLAポリシーマスタ
CREATE TABLE IF NOT EXISTS m_service_sla_policy (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                  VARCHAR(100) NOT NULL COMMENT 'ポリシー名',
    priority              VARCHAR(20) NOT NULL COMMENT '優先度 (P0, P1, P2, P3)',
    response_time_hours   INT NOT NULL COMMENT '初回応答目標時間(時間)',
    resolve_time_hours    INT NOT NULL COMMENT '解決目標時間(時間)',
    business_hours_start  TIME NOT NULL DEFAULT '09:00:00' COMMENT '始業時刻',
    business_hours_end    TIME NOT NULL DEFAULT '18:00:00' COMMENT '終業時刻',
    include_holidays      TINYINT(1) NOT NULL DEFAULT 0 COMMENT '休日を含むか(0:除外, 1:含む)',
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE',
    version               INT NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_sla_policy_priority (priority, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLAポリシーマスタ';

-- 初期SLAポリシー投入
INSERT INTO m_service_sla_policy (name, priority, response_time_hours, resolve_time_hours, business_hours_start, business_hours_end, include_holidays, status, version)
VALUES
('緊急 (P0)', 'P0', 1, 4, '09:00:00', '18:00:00', 0, 'ACTIVE', 0),
('高 (P1)',   'P1', 2, 8, '09:00:00', '18:00:00', 0, 'ACTIVE', 0),
('中 (P2)',   'P2', 4, 24, '09:00:00', '18:00:00', 0, 'ACTIVE', 0),
('低 (P3)',   'P3', 8, 48, '09:00:00', '18:00:00', 0, 'ACTIVE', 0);

-- 2. サービスリクエスト (問い合わせ・課題)
CREATE TABLE IF NOT EXISTS t_service_request (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no        VARCHAR(64) NOT NULL COMMENT 'リクエスト番号 REQ-YYYYMM-XXXX',
    customer_id       BIGINT NOT NULL COMMENT '顧客ID',
    contact_id        BIGINT NULL COMMENT '顧客担当者ID',
    contract_id       BIGINT NULL COMMENT '契約ID',
    project_id        BIGINT NULL COMMENT '案件ID',
    engineer_id       BIGINT NULL COMMENT '要員ID',
    category          VARCHAR(50) NOT NULL COMMENT 'CONTRACT, BILLING, ATTENDANCE, QUALITY, SYSTEM, OTHER',
    priority          VARCHAR(20) NOT NULL COMMENT 'P0, P1, P2, P3',
    channel           VARCHAR(30) NOT NULL COMMENT 'PORTAL, EMAIL, PHONE, MEETING, INTERNAL',
    subject           VARCHAR(255) NOT NULL COMMENT '件名',
    description       TEXT NOT NULL COMMENT '詳細内容',
    owner_user_id     BIGINT NULL COMMENT '社内主担当者 sys_user.id',
    status            VARCHAR(30) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED',
    first_response_at DATETIME NULL COMMENT '初回応答日時',
    resolved_at       DATETIME NULL COMMENT '解決日時',
    closed_at         DATETIME NULL COMMENT '終了日時',
    reopened_at       DATETIME NULL COMMENT '最新再オープン日時',
    reopen_count      INT NOT NULL DEFAULT 0 COMMENT '再オープン回数',
    portal_user_id    BIGINT NULL COMMENT '起票元ポータルユーザーID',
    created_by        BIGINT NULL COMMENT '作成者 (内部sys_user.id)',
    updated_by        BIGINT NULL,
    version           INT NOT NULL DEFAULT 0,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_service_request_no (request_no),
    INDEX idx_sr_customer_status (customer_id, status),
    INDEX idx_sr_owner_status (owner_user_id, status),
    INDEX idx_sr_priority_status (priority, status),
    CONSTRAINT fk_sr_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サービスリクエスト(問い合わせ)';

-- 3. SLA計時・ラウンド履歴
CREATE TABLE IF NOT EXISTS t_service_sla_clock (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL COMMENT 'サービスリクエストID',
    round_no            INT NOT NULL DEFAULT 1 COMMENT 'ラウンド番号',
    policy_id           BIGINT NOT NULL COMMENT '適用SLAポリシーID',
    response_deadline   DATETIME NOT NULL COMMENT '初回応答期限',
    resolve_deadline    DATETIME NOT NULL COMMENT '解決目標期限',
    first_responded_at  DATETIME NULL COMMENT '実初回応答日時',
    response_breached   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '初回応答超過フラグ',
    resolved_at         DATETIME NULL COMMENT '実解決日時',
    resolve_breached    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '解決超過フラグ',
    total_pause_minutes INT NOT NULL DEFAULT 0 COMMENT '累計停止時間(分)',
    last_paused_at      DATETIME NULL COMMENT '最終停止開始日時',
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING, PAUSED, COMPLETED',
    version             INT NOT NULL DEFAULT 0,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sla_clock_req_round (service_request_id, round_no),
    CONSTRAINT fk_sla_clock_request FOREIGN KEY (service_request_id) REFERENCES t_service_request(id) ON DELETE CASCADE,
    CONSTRAINT fk_sla_clock_policy FOREIGN KEY (policy_id) REFERENCES m_service_sla_policy(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLA計時クロック';

-- 4. サービスリクエスト コメント・内部メモ
CREATE TABLE IF NOT EXISTS t_service_comment (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL COMMENT 'サービスリクエストID',
    author_type         VARCHAR(20) NOT NULL COMMENT 'INTERNAL_USER, PORTAL_USER, SYSTEM',
    author_id           BIGINT NOT NULL COMMENT 'sys_user.id or portal_user.id',
    author_name         VARCHAR(100) NOT NULL COMMENT '投稿者表示名',
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PORTAL_VISIBLE' COMMENT 'PORTAL_VISIBLE, INTERNAL',
    comment_text        TEXT NOT NULL COMMENT 'コメント本文',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_comment_request_vis (service_request_id, visibility, created_at),
    CONSTRAINT fk_comment_request FOREIGN KEY (service_request_id) REFERENCES t_service_request(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='リクエストコメント・内部メモ';

-- 5. サービスリクエスト 添付ファイルリンク
CREATE TABLE IF NOT EXISTS t_service_attachment_link (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL COMMENT 'サービスリクエストID',
    comment_id          BIGINT NULL COMMENT '紐づくコメントID',
    document_id         BIGINT NOT NULL COMMENT 't_document.id',
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PORTAL_VISIBLE' COMMENT 'PORTAL_VISIBLE, INTERNAL',
    file_name           VARCHAR(255) NOT NULL COMMENT 'ファイル名',
    file_size           BIGINT NOT NULL COMMENT 'ファイルサイズ',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_att_request_vis (service_request_id, visibility),
    CONSTRAINT fk_att_request FOREIGN KEY (service_request_id) REFERENCES t_service_request(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='リクエスト添付ファイルリンク';

-- 6. サービスリクエスト 状態変更監査イベント
CREATE TABLE IF NOT EXISTS t_service_state_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL COMMENT 'サービスリクエストID',
    round_no            INT NOT NULL DEFAULT 1 COMMENT 'ラウンド番号',
    from_status         VARCHAR(30) NULL COMMENT '変更前ステータス',
    to_status           VARCHAR(30) NOT NULL COMMENT '変更後ステータス',
    reason              VARCHAR(255) NULL COMMENT '変更理由',
    actor_type          VARCHAR(20) NOT NULL COMMENT 'INTERNAL_USER, PORTAL_USER, SYSTEM',
    actor_id            BIGINT NOT NULL COMMENT '実行者ID',
    actor_name          VARCHAR(100) NOT NULL COMMENT '実行者名',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_state_event_request (service_request_id, created_at),
    CONSTRAINT fk_state_event_request FOREIGN KEY (service_request_id) REFERENCES t_service_request(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='状態変更監査イベント';

-- 7. 顧客満足度調査回答 (CSAT)
CREATE TABLE IF NOT EXISTS t_customer_csat (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL COMMENT '対象サービスリクエストID',
    customer_id         BIGINT NOT NULL COMMENT '顧客ID',
    portal_user_id      BIGINT NOT NULL COMMENT '回答ポータルユーザーID',
    score               INT NOT NULL COMMENT '評価スコア (1-5)',
    feedback_comment    TEXT NULL COMMENT 'フィードバックコメント',
    answered_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_csat_request (service_request_id),
    INDEX idx_csat_customer (customer_id, answered_at),
    CONSTRAINT fk_csat_request FOREIGN KEY (service_request_id) REFERENCES t_service_request(id) ON DELETE CASCADE,
    CONSTRAINT fk_csat_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='顧客満足度調査回答(CSAT)';

-- 8. 定例会・QBR記録
CREATE TABLE IF NOT EXISTS t_customer_qbr (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id         BIGINT NOT NULL COMMENT '顧客ID',
    title               VARCHAR(255) NOT NULL COMMENT '会議タイトル',
    meeting_date        DATE NOT NULL COMMENT '開催日',
    attendees           TEXT NULL COMMENT '参加者',
    agenda              TEXT NULL COMMENT '議題',
    discussion          TEXT NULL COMMENT '討議内容',
    decisions           TEXT NULL COMMENT '決定事項',
    next_meeting_date   DATE NULL COMMENT '次回予定日',
    created_by          BIGINT NULL COMMENT '作成者 sys_user.id',
    updated_by          BIGINT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qbr_customer_date (customer_id, meeting_date),
    CONSTRAINT fk_qbr_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定例会・QBR記録';

-- 9. QBRアクションアイテム
CREATE TABLE IF NOT EXISTS t_customer_qbr_action (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    qbr_id              BIGINT NOT NULL COMMENT 'QBR ID',
    title               VARCHAR(255) NOT NULL COMMENT 'タスク件名',
    description         TEXT NULL COMMENT 'タスク詳細',
    owner_user_id       BIGINT NULL COMMENT '担当者 sys_user.id',
    due_date            DATE NOT NULL COMMENT '期日',
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN, IN_PROGRESS, COMPLETED, CANCELLED',
    completed_at        DATETIME NULL COMMENT '完了日時',
    version             INT NOT NULL DEFAULT 0,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_qbr_action_owner (owner_user_id, status),
    INDEX idx_qbr_action_due (due_date, status),
    CONSTRAINT fk_qbr_action_qbr FOREIGN KEY (qbr_id) REFERENCES t_customer_qbr(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='QBRアクションアイテム';

-- 10. 顧客ヘルススナップショット
CREATE TABLE IF NOT EXISTS t_customer_health_snapshot (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id                 BIGINT NOT NULL COMMENT '顧客ID',
    snapshot_date               DATE NOT NULL COMMENT 'スナップショット日付',
    health_status               VARCHAR(20) NOT NULL COMMENT 'HEALTHY, WARNING, CRITICAL',
    total_score                 INT NOT NULL COMMENT '総合スコア 0-100',
    open_critical_issues_count  INT NOT NULL DEFAULT 0 COMMENT '未解決P0/P1件数',
    sla_breach_count_30d        INT NOT NULL DEFAULT 0 COMMENT '直近30日SLA違反件数',
    avg_csat_score              DECIMAL(3,2) NULL COMMENT '平均CSAT',
    ar_overdue_flag             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '売掛金延滞有無',
    missing_inputs_json         TEXT NULL COMMENT '欠損入力項目JSON',
    factors_explanation         TEXT NULL COMMENT 'スコア算出根拠説明',
    created_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_health_customer_date (customer_id, snapshot_date),
    CONSTRAINT fk_health_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='顧客ヘルススナップショット';

-- 11. メニューマスタへのサービスデスク追加
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('service-desk', 'サービスデスク', '/service-desk', '/api/service-desk', 11)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM m_menu m
CROSS JOIN (SELECT '管理者' AS role UNION ALL SELECT '営業' UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key = 'service-desk';

-- 12. サービスデスク・カスタマーサクセスのアクション権限seed
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (
    SELECT 'service-desk.*' AS action_key UNION ALL
    SELECT 'customer-health.*' AS action_key UNION ALL
    SELECT 'customer-qbr.*' AS action_key
) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-manager', 'role-admin');

-- 13. ポータル顧客向けサービスデスク権限seed (WIP-8 / CS-R1.2)
INSERT IGNORE INTO t_portal_user_permission (user_id, permission_key)
SELECT u.id, p.permission_key
FROM t_portal_user u
JOIN m_portal_organization o ON u.portal_org_id = o.id
CROSS JOIN (
    SELECT 'service-desk.view' AS permission_key UNION ALL
    SELECT 'service-desk.create' AS permission_key
) p
WHERE o.org_type = 'CUSTOMER'
  AND u.deleted_flag = 0;
