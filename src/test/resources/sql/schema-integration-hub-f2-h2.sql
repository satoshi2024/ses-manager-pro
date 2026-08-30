-- NF-05 F2専用external API audit H2 schema。
DROP TABLE IF EXISTS t_external_api_audit CASCADE;

CREATE TABLE t_external_api_audit (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    pre_auth_principal       VARCHAR(64) NOT NULL,
    post_auth_principal      VARCHAR(128) NOT NULL,
    client_id                VARCHAR(100),
    credential_version       INT,
    key_id                   VARCHAR(100),
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
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_external_audit_principal CHECK (pre_auth_principal = 'UNAUTHENTICATED'),
    CONSTRAINT chk_external_audit_status CHECK (status >= 100 AND status <= 599)
);
CREATE INDEX idx_external_audit_created ON t_external_api_audit (created_at, id);
CREATE INDEX idx_external_audit_client ON t_external_api_audit (client_id, created_at, id);
