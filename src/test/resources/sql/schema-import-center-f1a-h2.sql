-- NF06 F1aのH2 counterpart。schema-locationsの再実行に耐える。`r`n-- validationは業務domain tableへ書き込まず、candidate registryは独立操作のみを許可する。

CREATE TABLE IF NOT EXISTS t_import_source_system (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100)  NOT NULL,
    source_code VARCHAR(64)  NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version_no BIGINT NOT NULL DEFAULT 1,
    lookup_key_version VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_source_tenant_code (tenant_id, source_code),
    UNIQUE KEY uk_import_source_tenant_id (tenant_id, id),
    CONSTRAINT chk_import_source_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_import_source_version CHECK (version_no > 0)
);

CREATE TABLE IF NOT EXISTS t_import_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100)  NOT NULL,
    source_system_id BIGINT NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    mapping_version INT NOT NULL,
    canonical_bytes BLOB NOT NULL,
    mapping_sha256 BINARY(32) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_mapping_tenant_id (tenant_id, id),
    UNIQUE KEY uk_import_mapping_namespace (tenant_id, source_system_id, entity_type, schema_version, mapping_version),
    UNIQUE KEY uk_import_mapping_snapshot (tenant_id, id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256),
    CONSTRAINT chk_import_mapping_entity CHECK (entity_type IN ('CUSTOMER', 'ENGINEER', 'PROJECT', 'PROPOSAL', 'CONTRACT')),
    CONSTRAINT chk_import_mapping_version CHECK (mapping_version > 0),
    CONSTRAINT chk_import_mapping_size CHECK (OCTET_LENGTH(canonical_bytes) <= 32768),
    CONSTRAINT fk_import_mapping_source FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES t_import_source_system (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS t_import_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100)  NOT NULL,
    source_system_id BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    source_encoding VARCHAR(16) NOT NULL,
    source_sha256 BINARY(32) NOT NULL,
    legal_entity_id VARCHAR(100) NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    document_version_no INT NOT NULL,
    document_sha256 BINARY(32) NOT NULL,
    mapping_id BIGINT NULL,
    mapping_version INT NULL,
    mapping_sha256 BINARY(32) NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'UPLOADED',
    version_no BIGINT NOT NULL DEFAULT 1,
    lease_owner VARCHAR(128) NULL,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_expires_at DATETIME(6) NULL,
    active_validation_run_id BIGINT NULL,
    current_validation_run_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_job_tenant_id (tenant_id, id),
    UNIQUE KEY uk_import_job_identity (tenant_id, source_system_id, entity_type, schema_version, source_sha256),
    UNIQUE KEY uk_import_job_source_snapshot (tenant_id, id, source_system_id, source_version, entity_type),
    UNIQUE KEY uk_import_job_mapping_snapshot (tenant_id, id, mapping_id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256),
    KEY idx_import_job_state (tenant_id, state, updated_at, id),
    CONSTRAINT chk_import_job_entity CHECK (entity_type IN ('CUSTOMER', 'ENGINEER', 'PROJECT', 'PROPOSAL', 'CONTRACT')),
    CONSTRAINT chk_import_job_encoding CHECK (source_encoding IN ('UTF8', 'SHIFT_JIS')),
    CONSTRAINT chk_import_job_state CHECK (state IN ('UPLOADED', 'MAPPED', 'VALIDATING', 'VALIDATED', 'BLOCKED')),
    CONSTRAINT chk_import_job_mapping_nulls CHECK ((mapping_id IS NULL AND mapping_version IS NULL AND mapping_sha256 IS NULL) OR (mapping_id IS NOT NULL AND mapping_version IS NOT NULL AND mapping_sha256 IS NOT NULL)),
    CONSTRAINT chk_import_job_lease_pair CHECK ((lease_owner IS NULL AND lease_expires_at IS NULL) OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT fk_import_job_source FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES t_import_source_system (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_job_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES t_document (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_job_document_version FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES t_document_version (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_job_mapping_snapshot FOREIGN KEY (tenant_id, mapping_id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256)
        REFERENCES t_import_mapping (tenant_id, id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS t_import_validation_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100)  NOT NULL,
    job_id BIGINT NOT NULL,
    run_no INT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
    created_lease_generation BIGINT NOT NULL,
    last_resumed_lease_generation BIGINT NOT NULL,
    active_slot TINYINT NULL DEFAULT 1,
    published_slot TINYINT NULL,
    actor_user_id BIGINT NOT NULL,
    source_system_id BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    source_code_snapshot VARCHAR(64) NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    source_encoding VARCHAR(16) NOT NULL,
    source_sha256 BINARY(32) NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NOT NULL,
    document_version_no INT NOT NULL,
    document_sha256 BINARY(32) NOT NULL,
    mapping_id BIGINT NOT NULL,
    mapping_version INT NOT NULL,
    mapping_sha256 BINARY(32) NOT NULL,
    validation_fingerprint BINARY(32) NOT NULL,
    source_row_count BIGINT NULL,
    physical_row_count BIGINT NULL,
    empty_row_count BIGINT NULL,
    accepted_row_count BIGINT NULL,
    rejected_row_count BIGINT NULL,
    finding_summary_bytes VARBINARY(8192) NULL,
    finding_summary_sha256 BINARY(32) NULL,
    expected_unit_price_accepted_known_count BIGINT NULL,
    expected_unit_price_accepted_sum_yen DECIMAL(38,0) NULL,
    expected_unit_price_rejected_known_count BIGINT NULL,
    expected_unit_price_rejected_sum_yen DECIMAL(38,0) NULL,
    expected_unit_price_excluded_count BIGINT NULL,
    unit_price_min_accepted_known_count BIGINT NULL,
    unit_price_min_accepted_sum_yen DECIMAL(38,0) NULL,
    unit_price_min_rejected_known_count BIGINT NULL,
    unit_price_min_rejected_sum_yen DECIMAL(38,0) NULL,
    unit_price_min_excluded_count BIGINT NULL,
    unit_price_max_accepted_known_count BIGINT NULL,
    unit_price_max_accepted_sum_yen DECIMAL(38,0) NULL,
    unit_price_max_rejected_known_count BIGINT NULL,
    unit_price_max_rejected_sum_yen DECIMAL(38,0) NULL,
    unit_price_max_excluded_count BIGINT NULL,
    selling_price_accepted_known_count BIGINT NULL,
    selling_price_accepted_sum_yen DECIMAL(38,0) NULL,
    selling_price_rejected_known_count BIGINT NULL,
    selling_price_rejected_sum_yen DECIMAL(38,0) NULL,
    selling_price_excluded_count BIGINT NULL,
    cost_price_accepted_known_count BIGINT NULL,
    cost_price_accepted_sum_yen DECIMAL(38,0) NULL,
    cost_price_rejected_known_count BIGINT NULL,
    cost_price_rejected_sum_yen DECIMAL(38,0) NULL,
    cost_price_excluded_count BIGINT NULL,
    amount_excluded_reason_counts VARBINARY(8192) NULL,
    amount_excluded_reason_sha256 BINARY(32) NULL,
    row_set_sha256 BINARY(32) NULL,
    summary_sha256 BINARY(32) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sealed_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_run_tenant_id (tenant_id, id),
    UNIQUE KEY uk_import_run_job_id (tenant_id, job_id, id),
    UNIQUE KEY uk_import_run_number (tenant_id, job_id, run_no),
    UNIQUE KEY uk_import_run_active_slot (tenant_id, job_id, active_slot),
    UNIQUE KEY uk_import_run_published_slot (tenant_id, job_id, published_slot),
    UNIQUE KEY uk_import_run_provenance (tenant_id, job_id, id, source_system_id, entity_type),
    KEY idx_import_run_source (tenant_id, source_system_id, source_version),
    CONSTRAINT chk_import_run_state CHECK (state IN ('BUILDING', 'SEALED', 'COMPLETED', 'ABORTED')),
    CONSTRAINT chk_import_run_entity CHECK (entity_type IN ('CUSTOMER', 'ENGINEER', 'PROJECT', 'PROPOSAL', 'CONTRACT')),
    CONSTRAINT chk_import_run_encoding CHECK (source_encoding IN ('UTF8', 'SHIFT_JIS')),
    CONSTRAINT chk_import_run_slots CHECK ((state IN ('BUILDING', 'SEALED') AND active_slot IS NOT NULL AND active_slot = 1 AND published_slot IS NULL) OR (state = 'COMPLETED' AND active_slot IS NULL AND (published_slot IS NULL OR published_slot = 1)) OR (state = 'ABORTED' AND active_slot IS NULL AND published_slot IS NULL)),
    CONSTRAINT chk_import_run_counts CHECK ((source_row_count IS NULL OR source_row_count >= 0) AND (physical_row_count IS NULL OR physical_row_count >= 0) AND (empty_row_count IS NULL OR empty_row_count >= 0) AND (accepted_row_count IS NULL OR accepted_row_count >= 0) AND (rejected_row_count IS NULL OR rejected_row_count >= 0)),
    CONSTRAINT chk_import_run_summary_counts CHECK (
        (expected_unit_price_accepted_known_count IS NULL OR expected_unit_price_accepted_known_count >= 0) AND
        (expected_unit_price_rejected_known_count IS NULL OR expected_unit_price_rejected_known_count >= 0) AND
        (expected_unit_price_excluded_count IS NULL OR expected_unit_price_excluded_count >= 0) AND
        (unit_price_min_accepted_known_count IS NULL OR unit_price_min_accepted_known_count >= 0) AND
        (unit_price_min_rejected_known_count IS NULL OR unit_price_min_rejected_known_count >= 0) AND
        (unit_price_min_excluded_count IS NULL OR unit_price_min_excluded_count >= 0) AND
        (unit_price_max_accepted_known_count IS NULL OR unit_price_max_accepted_known_count >= 0) AND
        (unit_price_max_rejected_known_count IS NULL OR unit_price_max_rejected_known_count >= 0) AND
        (unit_price_max_excluded_count IS NULL OR unit_price_max_excluded_count >= 0) AND
        (selling_price_accepted_known_count IS NULL OR selling_price_accepted_known_count >= 0) AND
        (selling_price_rejected_known_count IS NULL OR selling_price_rejected_known_count >= 0) AND
        (selling_price_excluded_count IS NULL OR selling_price_excluded_count >= 0) AND
        (cost_price_accepted_known_count IS NULL OR cost_price_accepted_known_count >= 0) AND
        (cost_price_rejected_known_count IS NULL OR cost_price_rejected_known_count >= 0) AND
        (cost_price_excluded_count IS NULL OR cost_price_excluded_count >= 0)
    ),
    CONSTRAINT chk_import_run_amount_nulls CHECK (
        (expected_unit_price_accepted_known_count IS NULL OR ((expected_unit_price_accepted_known_count = 0 AND expected_unit_price_accepted_sum_yen IS NULL) OR (expected_unit_price_accepted_known_count > 0 AND expected_unit_price_accepted_sum_yen IS NOT NULL))) AND
        (expected_unit_price_rejected_known_count IS NULL OR ((expected_unit_price_rejected_known_count = 0 AND expected_unit_price_rejected_sum_yen IS NULL) OR (expected_unit_price_rejected_known_count > 0 AND expected_unit_price_rejected_sum_yen IS NOT NULL))) AND
        (unit_price_min_accepted_known_count IS NULL OR ((unit_price_min_accepted_known_count = 0 AND unit_price_min_accepted_sum_yen IS NULL) OR (unit_price_min_accepted_known_count > 0 AND unit_price_min_accepted_sum_yen IS NOT NULL))) AND
        (unit_price_min_rejected_known_count IS NULL OR ((unit_price_min_rejected_known_count = 0 AND unit_price_min_rejected_sum_yen IS NULL) OR (unit_price_min_rejected_known_count > 0 AND unit_price_min_rejected_sum_yen IS NOT NULL))) AND
        (unit_price_max_accepted_known_count IS NULL OR ((unit_price_max_accepted_known_count = 0 AND unit_price_max_accepted_sum_yen IS NULL) OR (unit_price_max_accepted_known_count > 0 AND unit_price_max_accepted_sum_yen IS NOT NULL))) AND
        (unit_price_max_rejected_known_count IS NULL OR ((unit_price_max_rejected_known_count = 0 AND unit_price_max_rejected_sum_yen IS NULL) OR (unit_price_max_rejected_known_count > 0 AND unit_price_max_rejected_sum_yen IS NOT NULL))) AND
        (selling_price_accepted_known_count IS NULL OR ((selling_price_accepted_known_count = 0 AND selling_price_accepted_sum_yen IS NULL) OR (selling_price_accepted_known_count > 0 AND selling_price_accepted_sum_yen IS NOT NULL))) AND
        (selling_price_rejected_known_count IS NULL OR ((selling_price_rejected_known_count = 0 AND selling_price_rejected_sum_yen IS NULL) OR (selling_price_rejected_known_count > 0 AND selling_price_rejected_sum_yen IS NOT NULL))) AND
        (cost_price_accepted_known_count IS NULL OR ((cost_price_accepted_known_count = 0 AND cost_price_accepted_sum_yen IS NULL) OR (cost_price_accepted_known_count > 0 AND cost_price_accepted_sum_yen IS NOT NULL))) AND
        (cost_price_rejected_known_count IS NULL OR ((cost_price_rejected_known_count = 0 AND cost_price_rejected_sum_yen IS NULL) OR (cost_price_rejected_known_count > 0 AND cost_price_rejected_sum_yen IS NOT NULL)))
    ),
    CONSTRAINT chk_import_run_completed_summary CHECK (state <> 'COMPLETED' OR (
        source_row_count IS NOT NULL AND physical_row_count IS NOT NULL AND empty_row_count IS NOT NULL AND
        accepted_row_count IS NOT NULL AND rejected_row_count IS NOT NULL AND
        physical_row_count = source_row_count + empty_row_count AND
        source_row_count = accepted_row_count + rejected_row_count AND
        finding_summary_bytes IS NOT NULL AND finding_summary_sha256 IS NOT NULL AND
        expected_unit_price_accepted_known_count IS NOT NULL AND expected_unit_price_rejected_known_count IS NOT NULL AND expected_unit_price_excluded_count IS NOT NULL AND
        unit_price_min_accepted_known_count IS NOT NULL AND unit_price_min_rejected_known_count IS NOT NULL AND unit_price_min_excluded_count IS NOT NULL AND
        unit_price_max_accepted_known_count IS NOT NULL AND unit_price_max_rejected_known_count IS NOT NULL AND unit_price_max_excluded_count IS NOT NULL AND
        selling_price_accepted_known_count IS NOT NULL AND selling_price_rejected_known_count IS NOT NULL AND selling_price_excluded_count IS NOT NULL AND
        cost_price_accepted_known_count IS NOT NULL AND cost_price_rejected_known_count IS NOT NULL AND cost_price_excluded_count IS NOT NULL AND
        amount_excluded_reason_counts IS NOT NULL AND amount_excluded_reason_sha256 IS NOT NULL AND
        row_set_sha256 IS NOT NULL AND summary_sha256 IS NOT NULL
    )),
    CONSTRAINT fk_import_run_job_snapshot FOREIGN KEY (tenant_id, job_id, source_system_id, source_version, entity_type)
        REFERENCES t_import_job (tenant_id, id, source_system_id, source_version, entity_type) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_run_mapping_snapshot FOREIGN KEY (tenant_id, job_id, mapping_id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256)
        REFERENCES t_import_job (tenant_id, id, mapping_id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

ALTER TABLE t_import_job
    ADD CONSTRAINT IF NOT EXISTS fk_import_job_active_run FOREIGN KEY (tenant_id, id, active_validation_run_id)
        REFERENCES t_import_validation_run (tenant_id, job_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT IF NOT EXISTS fk_import_job_current_run FOREIGN KEY (tenant_id, id, current_validation_run_id)
        REFERENCES t_import_validation_run (tenant_id, job_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE TABLE IF NOT EXISTS t_import_validation_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100)  NOT NULL,
    job_id BIGINT NOT NULL,
    validation_run_id BIGINT NOT NULL,
    source_row_number BIGINT NOT NULL,
    source_row_sha256 BINARY(32) NOT NULL,
    canonical_fingerprint BINARY(32) NOT NULL,
    disposition VARCHAR(8) NOT NULL,
    finding_codes VARBINARY(4096) NOT NULL,
    row_commit_sha256 BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_row_run_number (tenant_id, validation_run_id, source_row_number),
    KEY idx_import_row_job_run_order (tenant_id, job_id, validation_run_id, source_row_number),
    CONSTRAINT chk_import_row_number CHECK (source_row_number > 0),
    CONSTRAINT chk_import_row_disposition CHECK (disposition IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT fk_import_row_run FOREIGN KEY (tenant_id, job_id, validation_run_id)
        REFERENCES t_import_validation_run (tenant_id, job_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS t_import_identity_registry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(100)  NOT NULL,
    source_system_id BIGINT NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    lookup_key_version VARCHAR(64) NOT NULL,
    lookup_digest BINARY(32) NOT NULL,
    cipher_format_version SMALLINT NOT NULL DEFAULT 1,
    encryption_key_version VARCHAR(64) NOT NULL,
    nonce BINARY(12) NOT NULL,
    identity_ciphertext VARBINARY(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',
    first_job_id BIGINT NOT NULL,
    first_validation_run_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_identity_namespace (tenant_id, source_system_id, entity_type, lookup_digest),
    UNIQUE KEY uk_import_identity_tenant_id (tenant_id, id),
    KEY idx_import_identity_first_job (tenant_id, first_job_id),
    CONSTRAINT chk_import_identity_entity CHECK (entity_type IN ('CUSTOMER', 'ENGINEER', 'PROJECT', 'PROPOSAL', 'CONTRACT')),
    CONSTRAINT chk_import_identity_candidate CHECK (status = 'CANDIDATE'),
    CONSTRAINT chk_import_identity_format CHECK (cipher_format_version = 1),
    CONSTRAINT fk_import_identity_source FOREIGN KEY (tenant_id, source_system_id)
        REFERENCES t_import_source_system (tenant_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_import_identity_provenance FOREIGN KEY (tenant_id, first_job_id, first_validation_run_id, source_system_id, entity_type)
        REFERENCES t_import_validation_run (tenant_id, job_id, id, source_system_id, entity_type) ON UPDATE RESTRICT ON DELETE RESTRICT
);
