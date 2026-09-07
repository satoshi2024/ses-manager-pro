-- V150: サービスリクエスト月次採番シーケンス管理テーブル (NF-02)

CREATE TABLE IF NOT EXISTS t_service_request_sequence (
    sequence_month  VARCHAR(6) NOT NULL PRIMARY KEY COMMENT '対象年月 (yyyyMM)',
    current_val     INT NOT NULL DEFAULT 0 COMMENT '現在採番値 (1〜9999)',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サービスリクエスト月次採番シーケンス';
