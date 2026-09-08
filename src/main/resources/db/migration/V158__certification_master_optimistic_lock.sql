-- V158: 資格masterのtenant境界と楽観ロック用version。既存migrationの履歴は変更しない。
ALTER TABLE m_certification
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER active_flag;
