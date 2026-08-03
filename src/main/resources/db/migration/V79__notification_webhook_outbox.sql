-- ============================================================
-- V79: 通知Webhook outbox（B1 / R2.3）
-- V78は編集せず、承認通知を含む外部Webhook配信をcommit後に再送可能にする。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_notification_outbox (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    notification_id   BIGINT NULL COMMENT '元の通知ID',
    type              VARCHAR(30) NOT NULL COMMENT '通知種別',
    title             VARCHAR(200) NOT NULL COMMENT '通知タイトル',
    message           VARCHAR(500) NULL COMMENT '通知本文',
    link_url          VARCHAR(300) NULL COMMENT '関連画面URL',
    menu_key          VARCHAR(100) NULL COMMENT 'メニューキー',
    recipient_user_id BIGINT NULL COMMENT '宛先ユーザーID',
    organization_id   BIGINT NULL COMMENT '通知時点の組織ID',
    dedupe_key        VARCHAR(200) NOT NULL COMMENT '通知dedupe key',
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RETRY/SENT/FAILED',
    attempt_count     INT NOT NULL DEFAULT 0 COMMENT '送信試行回数',
    next_attempt_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '次回送信可能時刻',
    locked_at         DATETIME NULL COMMENT '処理claim時刻',
    last_error        VARCHAR(1000) NULL COMMENT '直近の送信失敗理由',
    sent_at           DATETIME NULL COMMENT '送信完了時刻',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    UNIQUE KEY uk_notification_outbox_dedupe (dedupe_key),
    INDEX idx_notification_outbox_due (status, next_attempt_at),
    CONSTRAINT fk_notification_outbox_notification
        FOREIGN KEY (notification_id) REFERENCES t_notification(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知外部配信outbox';
