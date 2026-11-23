-- V203（機能の開放を system 内で行う。FE-A01）の H2 変種。
-- db/migration/V203 は ENGINE・COMMENT・生成列の STORED 指定・trigger を含むため、H2 の replay には同じ表・列・型・NULL 可否・
-- 生成式・主キー・一意 key・索引・CHECK・FK（複合 FK を含む）と、menu・data element の seed を置く（trigger は作らない。
-- 追記専用の3表の更新・削除は AppendOnlyMapper だけが拒否する）。
-- 共有H2（DB_CLOSE_DELAY=-1）は context ごとに schema-locations を再実行するため、表・索引・seed とも IF NOT EXISTS・NOT EXISTS で
-- 冪等にする。engineer-schema-h2.sql も同じ定義を持つ。V203 との一致は FeatureEnablementSchemaContractTest が固定する。
CREATE TABLE IF NOT EXISTS t_feature_enablement_generation (
    tenant_id   VARCHAR(100) NOT NULL,
    generation  BIGINT       NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (tenant_id)
);

CREATE TABLE IF NOT EXISTS t_feature_enablement_request (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id             VARCHAR(100)  NOT NULL,
    request_type          VARCHAR(20)   NOT NULL,
    target_key            VARCHAR(64)   NOT NULL,
    proposed_value        VARCHAR(100)  NULL,
    status                VARCHAR(20)   NOT NULL,
    pending_slot          TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'PENDING' THEN 1 ELSE NULL END),
    condition_fingerprint CHAR(64)      NOT NULL,
    condition_summary     VARCHAR(2000) NOT NULL,
    reason                VARCHAR(500)  NULL,
    requested_by          BIGINT        NOT NULL,
    requested_at          DATETIME(6)   NOT NULL,
    expires_at            DATETIME(6)   NOT NULL,
    decided_by            BIGINT        NULL,
    decided_at            DATETIME(6)   NULL,
    decision_reason       VARCHAR(500)  NULL,
    version               INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_request_pending UNIQUE (tenant_id, target_key, pending_slot),
    CONSTRAINT uk_fe_request_actors UNIQUE (id, requested_by, decided_by),
    CONSTRAINT ck_fe_request_type CHECK (request_type IN ('OPEN', 'SETTING_CHANGE', 'SIGN_OFF_EXTENSION')),
    CONSTRAINT ck_fe_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'STALE',
        'CANCELLED')),
    CONSTRAINT ck_fe_request_decision CHECK (
           (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> requested_by)
        OR (status = 'WITHDRAWN' AND decided_by IS NOT NULL AND decided_by = requested_by AND decided_at IS NOT NULL)
        OR (status IN ('EXPIRED', 'STALE', 'CANCELLED') AND decided_by IS NULL AND decided_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_fe_request_status ON t_feature_enablement_request (tenant_id, status, expires_at);

CREATE TABLE IF NOT EXISTS t_feature_enablement_state (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(100) NOT NULL,
    feature_key         VARCHAR(64)  NOT NULL,
    state               VARCHAR(30)  NOT NULL,
    version             INT          NOT NULL DEFAULT 0,
    open_request_id     BIGINT       NULL,
    opened_by           BIGINT       NULL,
    approved_by         BIGINT       NULL,
    opened_at           DATETIME(6)  NULL,
    restart_applied_at  DATETIME(6)  NULL,
    closed_reason       VARCHAR(64)  NULL,
    closed_by           BIGINT       NULL,
    closed_at           DATETIME(6)  NULL,
    unverifiable_since  DATETIME(6)  NULL,
    unverifiable_reason VARCHAR(64)  NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_state UNIQUE (tenant_id, feature_key),
    CONSTRAINT ck_fe_state_key CHECK (feature_key IN ('NF01', 'NF01_DUAL_CONFIRMATION', 'NF02', 'NF02_CUSTOMER_PORTAL',
        'NF03', 'NF03_CERTIFICATION_PII', 'NF04', 'NF05_INBOUND', 'NF05_OUTBOUND', 'NF06', 'NF07_RETENTION', 'NF07_DSAR',
        'NF10')),
    CONSTRAINT ck_fe_state_value CHECK (state IN ('CLOSED', 'OPEN', 'OPEN_PENDING_RESTART', 'AUTO_CLOSED',
        'MANUALLY_CLOSED')),
    CONSTRAINT ck_fe_state_open CHECK (state NOT IN ('OPEN', 'OPEN_PENDING_RESTART')
        OR (open_request_id IS NOT NULL AND opened_by IS NOT NULL AND approved_by IS NOT NULL
            AND opened_by <> approved_by AND opened_at IS NOT NULL)),
    CONSTRAINT ck_fe_state_restart CHECK (state <> 'OPEN_PENDING_RESTART' OR feature_key IN ('NF05_INBOUND',
        'NF05_OUTBOUND')),
    CONSTRAINT ck_fe_state_closed CHECK (state NOT IN ('AUTO_CLOSED', 'MANUALLY_CLOSED')
        OR (closed_reason IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_fe_state_manual CHECK (state <> 'MANUALLY_CLOSED' OR closed_by IS NOT NULL),
    CONSTRAINT fk_fe_state_request FOREIGN KEY (open_request_id, opened_by, approved_by)
        REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
);

CREATE TABLE IF NOT EXISTS t_feature_enablement_setting (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(100) NOT NULL,
    setting_key   VARCHAR(64)  NOT NULL,
    setting_value VARCHAR(100) NULL,
    version       INT          NOT NULL DEFAULT 0,
    request_id    BIGINT       NOT NULL,
    requested_by  BIGINT       NOT NULL,
    approved_by   BIGINT       NOT NULL,
    approved_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_setting UNIQUE (tenant_id, setting_key),
    CONSTRAINT ck_fe_setting_key CHECK (setting_key IN ('nf02-customer-portal.visible-since')),
    CONSTRAINT ck_fe_setting_separation CHECK (requested_by <> approved_by),
    CONSTRAINT fk_fe_setting_request FOREIGN KEY (request_id, requested_by, approved_by)
        REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
);

CREATE TABLE IF NOT EXISTS t_feature_sign_off (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id         VARCHAR(100) NOT NULL,
    kind_code         VARCHAR(64)  NOT NULL,
    reference         VARCHAR(128) NOT NULL,
    signer_role       VARCHAR(64)  NOT NULL,
    signer_name       VARCHAR(100) NULL,
    signed_on         DATE         NOT NULL,
    expires_on        DATE         NOT NULL,
    structured_values TEXT         NULL,
    stored_name       VARCHAR(255) NULL,
    original_name     VARCHAR(255) NULL,
    kind_class        VARCHAR(10)  NOT NULL,
    content_sha256    CHAR(64)     NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    active_slot       TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END),
    registered_by     BIGINT       NOT NULL,
    registered_at     DATETIME(6)  NOT NULL,
    confirmed_by      BIGINT       NULL,
    confirmed_at      DATETIME(6)  NULL,
    ended_by          BIGINT       NULL,
    ended_at          DATETIME(6)  NULL,
    end_reason        VARCHAR(64)  NULL,
    superseded_by_id  BIGINT       NULL,
    purge_after       DATE         NULL,
    purged_at         DATETIME(6)  NULL,
    version           INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_sign_off_active UNIQUE (tenant_id, kind_code, active_slot),
    CONSTRAINT uk_fe_sign_off_kind UNIQUE (id, kind_code, tenant_id),
    CONSTRAINT uk_fe_sign_off_stored_name UNIQUE (stored_name),
    CONSTRAINT ck_fe_sign_off_status CHECK (status IN ('REGISTERED', 'CONFIRMED', 'REJECTED', 'REVOKED', 'SUPERSEDED',
        'EXPIRED', 'PURGED')),
    CONSTRAINT ck_fe_sign_off_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
    CONSTRAINT ck_fe_sign_off_confirmed CHECK (status NOT IN ('CONFIRMED', 'SUPERSEDED', 'EXPIRED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_fe_sign_off_dates CHECK (expires_on > signed_on),
    CONSTRAINT ck_fe_sign_off_purged CHECK (status <> 'PURGED'
        OR (stored_name IS NULL AND signer_name IS NULL AND original_name IS NULL AND purged_at IS NOT NULL)),
    CONSTRAINT ck_fe_sign_off_class CHECK (kind_class IN ('DECISION', 'EVIDENCE'))
);
CREATE INDEX IF NOT EXISTS idx_fe_sign_off_kind ON t_feature_sign_off (tenant_id, kind_code, status);
CREATE INDEX IF NOT EXISTS idx_fe_sign_off_purge ON t_feature_sign_off (status, purge_after);

CREATE TABLE IF NOT EXISTS t_feature_sign_off_extension (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(100) NOT NULL,
    sign_off_id     BIGINT       NOT NULL,
    request_id      BIGINT       NOT NULL,
    old_expires_on  DATE         NOT NULL,
    new_expires_on  DATE         NOT NULL,
    requested_by    BIGINT       NOT NULL,
    confirmed_by    BIGINT       NOT NULL,
    confirmed_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_fe_extension_separation CHECK (confirmed_by <> requested_by),
    CONSTRAINT ck_fe_extension_dates CHECK (new_expires_on > old_expires_on),
    CONSTRAINT fk_fe_extension_sign_off FOREIGN KEY (sign_off_id) REFERENCES t_feature_sign_off (id),
    CONSTRAINT fk_fe_extension_request FOREIGN KEY (request_id, requested_by, confirmed_by)
        REFERENCES t_feature_enablement_request (id, requested_by, decided_by)
);
CREATE INDEX IF NOT EXISTS idx_fe_extension_sign_off ON t_feature_sign_off_extension (sign_off_id, id);

CREATE TABLE IF NOT EXISTS t_feature_sign_off_bundle (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     VARCHAR(100) NOT NULL,
    bundle_kind   VARCHAR(64)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    active_slot   TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END),
    registered_by BIGINT       NOT NULL,
    registered_at DATETIME(6)  NOT NULL,
    confirmed_by  BIGINT       NULL,
    confirmed_at  DATETIME(6)  NULL,
    ended_by      BIGINT       NULL,
    ended_at      DATETIME(6)  NULL,
    end_reason    VARCHAR(64)  NULL,
    version       INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_fe_bundle_active UNIQUE (tenant_id, bundle_kind, active_slot),
    CONSTRAINT uk_fe_bundle_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_fe_bundle_status CHECK (status IN ('REGISTERED', 'CONFIRMED', 'REJECTED', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT ck_fe_bundle_separation CHECK (confirmed_by IS NULL OR confirmed_by <> registered_by),
    CONSTRAINT ck_fe_bundle_confirmed CHECK (status NOT IN ('CONFIRMED', 'SUPERSEDED')
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS t_feature_sign_off_bundle_member (
    bundle_id     BIGINT       NOT NULL,
    tenant_id     VARCHAR(100) NOT NULL,
    kind_code     VARCHAR(64)  NOT NULL,
    sign_off_id   BIGINT       NOT NULL,
    carried_over  TINYINT      NOT NULL,
    PRIMARY KEY (bundle_id, kind_code),
    CONSTRAINT fk_fe_bundle_member_bundle FOREIGN KEY (bundle_id, tenant_id)
        REFERENCES t_feature_sign_off_bundle (id, tenant_id),
    CONSTRAINT fk_fe_bundle_member_sign_off FOREIGN KEY (sign_off_id, kind_code, tenant_id)
        REFERENCES t_feature_sign_off (id, kind_code, tenant_id)
);

CREATE TABLE IF NOT EXISTS t_feature_enablement_event (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id         VARCHAR(100)  NOT NULL,
    subject_type      VARCHAR(20)   NOT NULL,
    subject_key       VARCHAR(64)   NOT NULL,
    subject_id        BIGINT        NULL,
    event_type        VARCHAR(40)   NOT NULL,
    from_state        VARCHAR(30)   NULL,
    to_state          VARCHAR(30)   NULL,
    reason_code       VARCHAR(64)   NULL,
    condition_summary VARCHAR(2000) NULL,
    actor_type        VARCHAR(10)   NOT NULL,
    actor_user_id     BIGINT        NULL,
    correlation_id    VARCHAR(64)   NULL,
    generation        BIGINT        NOT NULL,
    occurred_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_fe_event_subject ON t_feature_enablement_event (tenant_id, subject_type, subject_key, id);
CREATE INDEX IF NOT EXISTS idx_fe_event_time ON t_feature_enablement_event (tenant_id, occurred_at);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'feature-enablement', '機能の開放', '/feature-enablement', '/api/feature-enablement', 263
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'feature-enablement');

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id
FROM m_menu m
WHERE m.menu_key = 'feature-enablement'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu r WHERE r.role = '管理者' AND r.menu_id = m.id);

INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id,
                                subject_attribution, inventory_ref)
SELECT t.tenant_id, d.data_element_id, d.storage_kind, d.storage_ref, d.provider_id, d.subject_attribution,
       d.inventory_ref
FROM (SELECT DISTINCT tenant_id FROM sys_user WHERE tenant_id IS NOT NULL AND tenant_id <> '') t
CROSS JOIN (
  SELECT 'FEATURE_SIGN_OFF_SIGNER_NAME' AS data_element_id, 'DB_COLUMN' AS storage_kind,
         't_feature_sign_off.signer_name' AS storage_ref, NULL AS provider_id,
         'NOT_ATTRIBUTABLE' AS subject_attribution, 'pii-inventory.md#DB-151' AS inventory_ref
  UNION ALL SELECT 'FEATURE_SIGN_OFF_DOCUMENT', 'FILE_OBJECT', 't_feature_sign_off.stored_name',
         NULL, 'NOT_ATTRIBUTABLE', 'pii-inventory.md#DB-151'
) d
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
                  WHERE e.tenant_id = t.tenant_id AND e.data_element_id = d.data_element_id);
