-- ===================================================================
-- V132: NF-05 B1 outbound webhook delivery support
--
-- V129のNF-05専用delivery ledgerを拡張する。第二outboxは作らない。
-- signing secretはAES-256-GCM envelopeのまま保存し、replay auditにもpayloadを保存しない。
-- ===================================================================

ALTER TABLE m_webhook_subscription
    ADD COLUMN signing_credential_version INT NOT NULL DEFAULT 1
    COMMENT 'webhook signing secretのcredential世代';

ALTER TABLE t_api_delivery
    ADD COLUMN scope_digest CHAR(64) NULL
    COMMENT 'enqueue時のeffective subscription scope digest';

UPDATE t_api_delivery
SET scope_digest = LOWER(SHA2(CONCAT(client_id, '|', scope_code, '|', tenant_id), 256))
WHERE scope_digest IS NULL;

ALTER TABLE t_api_delivery
    MODIFY COLUMN scope_digest CHAR(64) NOT NULL
    COMMENT 'enqueue時のeffective subscription scope digest';

CREATE TABLE t_api_delivery_replay_audit (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    delivery_id           BIGINT NOT NULL,
    event_id              VARCHAR(128) NOT NULL,
    replay_generation     INT NOT NULL,
    operator_ref          VARCHAR(128) NOT NULL COMMENT 'safe operator reference。実名・PII禁止',
    reason_code           VARCHAR(64) NOT NULL COMMENT 'bounded reason code',
    scope_digest          CHAR(64) NOT NULL,
    payload_hash          CHAR(64) NOT NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_delivery_replay_generation UNIQUE (delivery_id, replay_generation),
    CONSTRAINT fk_api_delivery_replay_delivery FOREIGN KEY (delivery_id) REFERENCES t_api_delivery (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 safe manual replay audit';

CREATE INDEX idx_api_delivery_replay_event ON t_api_delivery_replay_audit (event_id, created_at, id);

-- ROLLBACK EVIDENCE:
-- 新規送信/replayを停止しbackupを取得後、子表t_api_delivery_replay_auditをDROPし、
-- signing_credential_version/scope_digestの削除可否を全row検証してから手動でALTER DROPする。
-- 適用済みFlyway migrationの編集・再実行は行わない。
