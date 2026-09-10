-- V162: NF02/NF03境界修復。既存migrationの履歴は変更しない。

-- Service DeskのREQ採番は業務表の最大値検索ではなく、tenant/月の行を原子的に更新する。
CREATE TABLE t_service_request_sequence (
    tenant_id      VARCHAR(100) NOT NULL,
    request_month  CHAR(6) NOT NULL,
    last_number    INT NOT NULL DEFAULT 0,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, request_month),
    CONSTRAINT chk_service_request_sequence_range CHECK (last_number BETWEEN 0 AND 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サービスリクエスト月次採番';

ALTER TABLE t_service_request
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
ALTER TABLE t_service_request
    DROP INDEX uk_service_request_no,
    ADD UNIQUE KEY uk_service_request_tenant_no (tenant_id, request_no);
CREATE INDEX idx_service_request_tenant_month ON t_service_request (tenant_id, request_no);

-- 文書リンク自身も親文書と同じtenantを保持し、typed link検証で横断を拒否できるようにする。
ALTER TABLE t_document_link
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
CREATE INDEX idx_document_link_tenant_target ON t_document_link (tenant_id, target_type, target_id, deleted_flag);

-- 既存breach flagのNULL時刻は「違反なし」ではなく履歴不明として明示する。
ALTER TABLE t_service_sla_clock
    ADD COLUMN response_breach_time_unknown TINYINT(1) NOT NULL DEFAULT 0 AFTER response_breached_at,
    ADD COLUMN resolve_breach_time_unknown TINYINT(1) NOT NULL DEFAULT 0 AFTER resolve_breached_at;
UPDATE t_service_sla_clock
SET response_breach_time_unknown = 1
WHERE response_breached = 1 AND response_breached_at IS NULL;
UPDATE t_service_sla_clock
SET resolve_breach_time_unknown = 1
WHERE resolve_breached = 1 AND resolve_breached_at IS NULL;
ALTER TABLE t_customer_health_snapshot
    ADD COLUMN sla_breach_historical_unknown_count INT NOT NULL DEFAULT 0 AFTER sla_breach_count_30d;

-- continuity_group_idはMySQLが採番し、renewは既存値をそのまま再利用する。
ALTER TABLE t_engineer_certification
    DROP FOREIGN KEY fk_eng_cert_continuity_group;
ALTER TABLE t_certification_continuity_group
    MODIFY COLUMN continuity_group_id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE t_engineer_certification
    ADD CONSTRAINT fk_eng_cert_continuity_group
    FOREIGN KEY (tenant_id, engineer_id, certification_id, continuity_group_id)
    REFERENCES t_certification_continuity_group (tenant_id, engineer_id, certification_id, continuity_group_id);

-- AI run・候補・人判断は同一tenantで結び、AIの結果を正式projectionへ書き込まない。
ALTER TABLE t_ai_recommendation_run
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
CREATE INDEX idx_ai_run_tenant_created ON t_ai_recommendation_run (tenant_id, created_at);

CREATE TABLE t_learning_candidate (
    id                       BIGINT NOT NULL,
    tenant_id                VARCHAR(100) NOT NULL,
    engineer_id              BIGINT NOT NULL,
    project_id               BIGINT NOT NULL,
    customer_id              BIGINT NULL,
    as_of_date               DATE NOT NULL,
    rule_gap_snapshot_id     BIGINT NULL,
    rule_course_ids_json     JSON NOT NULL,
    ai_course_ids_json       JSON NOT NULL,
    snapshot_hash            CHAR(64) NOT NULL,
    status                   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at               DATETIME NOT NULL,
    decision_actor_user_id   BIGINT NULL,
    decision_reason          VARCHAR(2000) NULL,
    decided_at               DATETIME NULL,
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag             TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_candidate_tenant_id (tenant_id, id),
    KEY idx_learning_candidate_scope (tenant_id, engineer_id, project_id, customer_id, status),
    CONSTRAINT fk_learning_candidate_run FOREIGN KEY (id) REFERENCES t_ai_recommendation_run(id),
    CONSTRAINT fk_learning_candidate_snapshot FOREIGN KEY (rule_gap_snapshot_id) REFERENCES t_skill_gap_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI学習候補の再読込可能な正本';

ALTER TABLE t_learning_decision_event
    ADD COLUMN idempotency_key VARCHAR(255) NULL AFTER snapshot_hash;
CREATE UNIQUE INDEX uk_learning_decision_tenant_idempotency
    ON t_learning_decision_event (tenant_id, idempotency_key);
