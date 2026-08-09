-- T061 F1 H2 schema。V1/V84のMySQL shapeに合わせ、H2では既存共有schemaにない文書FKだけ省略する。
DROP TABLE IF EXISTS t_document_delivery CASCADE;
DROP TABLE IF EXISTS t_compliance_finding CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_profile CASCADE;
DROP TABLE IF EXISTS m_workplace CASCADE;

CREATE TABLE m_workplace (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  customer_id BIGINT NOT NULL,
  organization_id BIGINT,
  name VARCHAR(200) NOT NULL,
  address VARCHAR(500),
  organization_unit VARCHAR(200),
  phone VARCHAR(50),
  valid_from DATE NOT NULL DEFAULT '1970-01-01',
  valid_to DATE,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_workplace_period UNIQUE (customer_id, name, valid_from),
  CONSTRAINT chk_workplace_period CHECK (valid_to IS NULL OR valid_from <= valid_to)
);
CREATE INDEX idx_workplace_scope ON m_workplace(tenant_id, customer_id, organization_id);
CREATE INDEX idx_workplace_period ON m_workplace(valid_from, valid_to);

CREATE TABLE t_contract_compliance_profile (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id BIGINT NOT NULL,
  contract_type_detail VARCHAR(50),
  workplace_id BIGINT,
  work_description CLOB,
  work_location VARCHAR(500),
  work_time VARCHAR(500),
  break_time VARCHAR(500),
  holiday_rule VARCHAR(500),
  overtime_rule VARCHAR(500),
  command_person_contact_id BIGINT,
  command_person_name_snapshot VARCHAR(100),
  command_person_title_snapshot VARCHAR(100),
  client_responsible_contact_id BIGINT,
  client_responsible_person VARCHAR(200),
  client_responsible_phone VARCHAR(50),
  dispatch_responsible_user_id BIGINT,
  dispatch_responsible_name_snapshot VARCHAR(100),
  dispatch_responsible_title_snapshot VARCHAR(100),
  dispatch_responsible_phone_snapshot VARCHAR(50),
  dispatch_period_start DATE,
  dispatch_period_end DATE,
  limitation_date DATE,
  workplace_limitation_date DATE,
  worker_limitation_date DATE,
  treatment_scheme VARCHAR(100),
  complaint_contact VARCHAR(1000),
  complaint_processing_history CLOB,
  training_info CLOB,
  safety_health_info CLOB,
  insurance_notification VARCHAR(1000),
  welfare_info CLOB,
  instruction_route CLOB,
  responsibility_degree VARCHAR(255),
  subcontract_allowed TINYINT,
  acceptance_method VARCHAR(255),
  dispatch_worker_count INT,
  agreement_target_flag TINYINT,
  indefinite_term_flag TINYINT,
  age_over_60_flag TINYINT,
  employment_stability_measure CLOB,
  health_insurance_status VARCHAR(20),
  health_insurance_missing_reason VARCHAR(500),
  health_insurance_expected_date DATE,
  pension_insurance_status VARCHAR(20),
  pension_insurance_missing_reason VARCHAR(500),
  pension_insurance_expected_date DATE,
  employment_insurance_status VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  snapshot_json CLOB,
  workplace_snapshot_json CLOB,
  worker_snapshot_json CLOB,
  snapshot_at TIMESTAMP,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_contract_compliance_profile_contract UNIQUE (contract_id),
  CONSTRAINT chk_profile_period CHECK (dispatch_period_end IS NULL OR dispatch_period_start IS NULL OR dispatch_period_start <= dispatch_period_end),
  CONSTRAINT chk_profile_dispatch_count CHECK (dispatch_worker_count IS NULL OR dispatch_worker_count >= 0),
  CONSTRAINT fk_profile_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE CASCADE,
  CONSTRAINT fk_profile_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id) ON DELETE SET NULL
);
CREATE INDEX idx_profile_workplace ON t_contract_compliance_profile(workplace_id);
CREATE INDEX idx_profile_limitation ON t_contract_compliance_profile(limitation_date);
CREATE INDEX idx_profile_dispatch_period ON t_contract_compliance_profile(dispatch_period_start, dispatch_period_end);

CREATE TABLE t_compliance_finding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id BIGINT NOT NULL,
  code VARCHAR(80) NOT NULL,
  severity VARCHAR(20) NOT NULL DEFAULT 'WARNING',
  status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  condition_fingerprint VARCHAR(128) NOT NULL,
  detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  due_date DATE,
  acknowledged_by BIGINT,
  acknowledged_at TIMESTAMP,
  resolution_note VARCHAR(2000),
  evidence_document_id BIGINT,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_finding UNIQUE (contract_id, code, condition_fingerprint),
  CONSTRAINT fk_finding_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE CASCADE
);
CREATE INDEX idx_finding_status_due ON t_compliance_finding(status, due_date);
CREATE INDEX idx_finding_contract ON t_compliance_finding(contract_id, detected_at);

CREATE TABLE t_document_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id BIGINT,
  document_id BIGINT NOT NULL,
  recipient_contact_id BIGINT,
  recipient_name_snapshot VARCHAR(200),
  recipient_email_snapshot VARCHAR(255),
  delivery_method VARCHAR(30) NOT NULL,
  delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  delivered_at TIMESTAMP,
  confirmed_at TIMESTAMP,
  confirmation_note VARCHAR(1000),
  idempotency_key VARCHAR(200),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_delivery_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_delivery_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE SET NULL
);
CREATE INDEX idx_delivery_document ON t_document_delivery(document_id, delivered_at);
CREATE INDEX idx_delivery_contract ON t_document_delivery(contract_id, delivered_at);
CREATE INDEX idx_delivery_confirmation ON t_document_delivery(confirmed_at);
