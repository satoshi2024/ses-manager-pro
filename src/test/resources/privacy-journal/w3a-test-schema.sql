CREATE TABLE IF NOT EXISTS sf_w3a_case (
  tenant_id VARCHAR(128) NOT NULL,
  case_ref VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'SCOPED',
  PRIMARY KEY (tenant_id, case_ref)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sf_w3a_preview (
  tenant_id VARCHAR(128) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  case_ref VARCHAR(128) NOT NULL,
  case_revision BIGINT NOT NULL,
  identity_hash CHAR(64) NOT NULL,
  witness_hash CHAR(64) NOT NULL,
  witness_json LONGTEXT NOT NULL,
  items_json LONGTEXT NOT NULL,
  observed_at TIMESTAMP(6) NOT NULL,
  observed_to TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, request_id, idempotency_key),
  UNIQUE KEY uq_sf_w3a_case_revision (tenant_id, case_ref, case_revision),
  CONSTRAINT fk_sf_w3a_case FOREIGN KEY (tenant_id, case_ref)
    REFERENCES sf_w3a_case (tenant_id, case_ref)
) ENGINE=InnoDB;
