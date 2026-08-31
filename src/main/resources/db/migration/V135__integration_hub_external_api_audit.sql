-- ===================================================================
-- V130: NF-05 F2 external API専用audit
--
-- 既存 t_audit_log はportal/internal更新系の監査用であり、公開APIの
-- principal/decision契約とは分離する。raw target/body/IP/secretは保存しない。
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_external_api_audit (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部監査ID',
    pre_auth_principal       VARCHAR(64) NOT NULL COMMENT '常にUNAUTHENTICATED',
    post_auth_principal      VARCHAR(128) NOT NULL COMMENT 'clientIdまたはNONE',
    client_id                VARCHAR(100) NULL COMMENT '外部client識別子',
    credential_version       INT NULL COMMENT 'credential世代',
    key_id                   VARCHAR(100) NULL COMMENT 'credential key識別子',
    correlation_id           VARCHAR(128) NOT NULL,
    method                   VARCHAR(16) NOT NULL,
    route_template           VARCHAR(200) NOT NULL,
    authentication_decision VARCHAR(64) NOT NULL,
    scope_decision           VARCHAR(64) NOT NULL,
    data_scope_decision      VARCHAR(64) NOT NULL,
    command_decision         VARCHAR(64) NOT NULL,
    rate_decision            VARCHAR(64) NOT NULL,
    status                   SMALLINT NOT NULL,
    result_code              VARCHAR(64) NOT NULL,
    success_flag             BOOLEAN NOT NULL,
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_external_audit_created (created_at, id),
    INDEX idx_external_audit_client (client_id, created_at, id),
    CONSTRAINT chk_external_audit_principal CHECK (pre_auth_principal = 'UNAUTHENTICATED'),
    CONSTRAINT chk_external_audit_status CHECK (status >= 100 AND status <= 599)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 external API bounded audit';

-- ROLLBACK EVIDENCE (手動運用のみ。適用済みmigrationの編集・再実行は禁止):
-- 1) public-api request受付とaudit必須policyを停止し、backup/restore計画を承認する。
-- 2) retention/legal holdを確認し、t_external_api_auditのmetadataを退避・検証する。
-- 3) Flyway schema history、H2 schema、backup/restore後purge証跡を再確認する。
