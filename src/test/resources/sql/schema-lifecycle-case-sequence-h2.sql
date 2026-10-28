CREATE TABLE IF NOT EXISTS t_lifecycle_case_sequence (
    tenant_id VARCHAR(100) NOT NULL,
    case_month CHAR(6) NOT NULL,
    last_number BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, case_month)
);
