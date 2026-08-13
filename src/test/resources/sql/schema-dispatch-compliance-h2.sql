-- T061 F1 R5 H2 schema. Mirrors the V1/V84 MySQL shape; document/contact/sys_user/engineer
-- FKs and DB triggers are omitted for H2 (immutability enforced by mapper INSERT/SELECT-only boundary).
DROP TABLE IF EXISTS t_document_delivery CASCADE;
DROP TABLE IF EXISTS t_compliance_operation_ledger CASCADE;
DROP TABLE IF EXISTS t_compliance_mapping_status_event CASCADE;
DROP TABLE IF EXISTS t_compliance_external_review_event CASCADE;
DROP TABLE IF EXISTS t_compliance_mapping_approval_event CASCADE;
DROP TABLE IF EXISTS t_compliance_responsible_assignment CASCADE;
DROP TABLE IF EXISTS m_compliance_mapping_review_requirement_type CASCADE;
DROP TABLE IF EXISTS m_compliance_mapping_review_requirement_group CASCADE;
DROP TABLE IF EXISTS m_compliance_external_reviewer_type CASCADE;
DROP TABLE IF EXISTS m_compliance_mapping_source CASCADE;
DROP TABLE IF EXISTS m_compliance_mapping_version CASCADE;
DROP TABLE IF EXISTS t_compliance_finding CASCADE;
DROP TABLE IF EXISTS t_ledger_work_snapshot CASCADE;
DROP TABLE IF EXISTS t_notification_difference_history CASCADE;
DROP TABLE IF EXISTS t_direct_hire_dispute_history CASCADE;
DROP TABLE IF EXISTS t_planned_introduction_history CASCADE;
DROP TABLE IF EXISTS t_planned_introduction_terms CASCADE;
DROP TABLE IF EXISTS t_career_consulting_history CASCADE;
DROP TABLE IF EXISTS t_training_history CASCADE;
DROP TABLE IF EXISTS t_employment_stability_history CASCADE;
DROP TABLE IF EXISTS t_compliance_complaint_history CASCADE;
DROP TABLE IF EXISTS t_compliance_break_detail CASCADE;
DROP TABLE IF EXISTS t_compliance_work_calendar CASCADE;
DROP TABLE IF EXISTS t_compliance_snapshot_operation CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_worker_state CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_worker_snapshot CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_profile CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_snapshot CASCADE;
DROP TABLE IF EXISTS m_workplace CASCADE;
CREATE TABLE m_workplace (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL DEFAULT 'default',
  customer_id       BIGINT NOT NULL,
  organization_id   BIGINT,
  name              VARCHAR(200) NOT NULL,
  address           VARCHAR(500),
  organization_unit VARCHAR(200),
  phone             VARCHAR(50),
  valid_from        DATE NOT NULL DEFAULT '1970-01-01',
  valid_to          DATE,
  status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  version           INT NOT NULL DEFAULT 0,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag      TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_workplace_period UNIQUE (customer_id, name, valid_from),
  CONSTRAINT uk_workplace_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_workplace_period CHECK (valid_to IS NULL OR valid_from <= valid_to)
);
CREATE INDEX idx_workplace_scope ON m_workplace (tenant_id, customer_id, organization_id);
CREATE INDEX idx_workplace_period ON m_workplace (valid_from, valid_to);

CREATE TABLE t_contract_compliance_snapshot (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                       BIGINT NOT NULL,
  snapshot_version                  INT NOT NULL,
  snapshot_hash                     VARCHAR(64) NOT NULL,
  operation_id                      VARCHAR(64),
  snapshot_at                       DATETIME,
  contract_no                       VARCHAR(100),
  contract_date                     DATE,
  party_name                        VARCHAR(200),
  party_address                     VARCHAR(500),
  party_representative              VARCHAR(200),
  dispatch_from                     DATE,
  dispatch_to                       DATE,
  workplace_name                    VARCHAR(200),
  workplace_address                 VARCHAR(500),
  workplace_department              VARCHAR(200),
  workplace_phone                   VARCHAR(50),
  organization_unit                 VARCHAR(200),
  organization_head_title           VARCHAR(100),
  work_description                  CLOB,
  statutory_job_flag                TINYINT,
  statutory_job_reference           VARCHAR(200),
  responsibility_level              VARCHAR(50),
  responsibility_detail             CLOB,
  command_person_department         VARCHAR(100),
  command_person_title              VARCHAR(100),
  command_person_name               VARCHAR(100),
  command_person_phone              VARCHAR(50),
  client_responsible_department     VARCHAR(100),
  client_responsible_title          VARCHAR(100),
  client_responsible_name           VARCHAR(100),
  client_responsible_phone          VARCHAR(50),
  dispatch_responsible_department   VARCHAR(100),
  dispatch_responsible_title        VARCHAR(100),
  dispatch_responsible_name         VARCHAR(100),
  dispatch_responsible_phone        VARCHAR(50),
  work_start_minute                 INT,
  work_end_minute                   INT,
  work_span_next_day_flag           TINYINT,
  break_start_minute                INT,
  break_end_minute                  INT,
  work_day_code                     VARCHAR(30),
  holiday_calendar_code             VARCHAR(30),
  agreement_reference_id            BIGINT,
  overtime_daily_limit              INT,
  overtime_monthly_limit            INT,
  overtime_yearly_limit             INT,
  overtime_period_from              DATE,
  overtime_period_to                DATE,
  workplace_limitation_date         DATE,
  organization_limitation_date      DATE,
  safety_responsibility_detail      CLOB,
  safety_rule_reference             VARCHAR(200),
  benefits_detail                   CLOB,
  benefits_provided_flag            TINYINT,
  dispatch_headcount                INT,
  agreement_target_flag             TINYINT,
  treatment_scheme                  VARCHAR(100),
  source_complaint_contact_department VARCHAR(100),
  source_complaint_contact_title    VARCHAR(100),
  source_complaint_contact_name     VARCHAR(100),
  source_complaint_contact_phone    VARCHAR(50),
  client_complaint_contact_department VARCHAR(100),
  client_complaint_contact_title    VARCHAR(100),
  client_complaint_contact_name     VARCHAR(100),
  client_complaint_contact_phone    VARCHAR(50),
  employment_stability_preference   CLOB,
  limitation_exemption_type         VARCHAR(50),
  limitation_exemption_detail       CLOB,
  limitation_exemption_basis        VARCHAR(200),
  limitation_exemption_from         DATE,
  limitation_exemption_to           DATE,
  dispatch_fee_amount               DECIMAL(14,2),
  dispatch_fee_basis                VARCHAR(20),
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY',
  social_insurance_procedure_incomplete_reason CLOB,
  health_insurance_status           VARCHAR(20),
  health_insurance_missing_reason   VARCHAR(500),
  health_insurance_expected_date    DATE,
  pension_insurance_status          VARCHAR(20),
  pension_insurance_missing_reason  VARCHAR(500),
  pension_insurance_expected_date   DATE,
  employment_insurance_status       VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  instruction_route                 CLOB,
  subcontract_allowed               TINYINT,
  acceptance_method                 VARCHAR(255),
  retention_due_date                DATE,
  legal_hold_flag                   TINYINT,
  version                           INT NOT NULL DEFAULT 0,
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_snapshot_version UNIQUE (contract_id, snapshot_version),
  CONSTRAINT uk_compliance_snapshot_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_compliance_snapshot_hash ON t_contract_compliance_snapshot (snapshot_hash);
CREATE INDEX idx_compliance_snapshot_contract ON t_contract_compliance_snapshot (contract_id);

CREATE TABLE t_contract_compliance_profile (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                       BIGINT NOT NULL,
  contract_type_detail              VARCHAR(50),
  workplace_id                      BIGINT,
  work_description                  CLOB,
  statutory_job_flag                TINYINT,
  statutory_job_reference           VARCHAR(200),
  responsibility_level              VARCHAR(50),
  responsibility_detail             CLOB,
  command_person_contact_id         BIGINT,
  command_person_department         VARCHAR(100),
  command_person_title              VARCHAR(100),
  command_person_name               VARCHAR(100),
  command_person_phone              VARCHAR(50),
  client_responsible_contact_id     BIGINT,
  client_responsible_department     VARCHAR(100),
  client_responsible_title          VARCHAR(100),
  client_responsible_name           VARCHAR(100),
  client_responsible_phone          VARCHAR(50),
  dispatch_responsible_user_id      BIGINT,
  dispatch_responsible_department   VARCHAR(100),
  dispatch_responsible_title        VARCHAR(100),
  dispatch_responsible_name         VARCHAR(100),
  dispatch_responsible_phone        VARCHAR(50),
  work_start_minute                 INT,
  work_end_minute                   INT,
  work_span_next_day_flag           TINYINT,
  break_start_minute                INT,
  break_end_minute                  INT,
  work_day_code                     VARCHAR(30),
  holiday_calendar_code             VARCHAR(30),
  agreement_reference_id            BIGINT,
  overtime_daily_limit              INT,
  overtime_monthly_limit            INT,
  overtime_yearly_limit             INT,
  overtime_period_from              DATE,
  overtime_period_to                DATE,
  workplace_limitation_date         DATE,
  organization_limitation_date      DATE,
  safety_responsibility_detail      CLOB,
  safety_rule_reference             VARCHAR(200),
  benefits_detail                   CLOB,
  benefits_provided_flag            TINYINT,
  dispatch_headcount                INT,
  agreement_target_flag             TINYINT,
  treatment_scheme                  VARCHAR(100),
  source_complaint_contact_department VARCHAR(100),
  source_complaint_contact_title    VARCHAR(100),
  source_complaint_contact_name     VARCHAR(100),
  source_complaint_contact_phone    VARCHAR(50),
  client_complaint_contact_department VARCHAR(100),
  client_complaint_contact_title    VARCHAR(100),
  client_complaint_contact_name     VARCHAR(100),
  client_complaint_contact_phone    VARCHAR(50),
  employment_stability_preference   CLOB,
  limitation_exemption_type         VARCHAR(50),
  limitation_exemption_detail       CLOB,
  limitation_exemption_basis        VARCHAR(200),
  limitation_exemption_from         DATE,
  limitation_exemption_to           DATE,
  dispatch_fee_amount               DECIMAL(14,2),
  dispatch_fee_basis                VARCHAR(20),
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY',
  social_insurance_procedure_incomplete_reason CLOB,
  health_insurance_status           VARCHAR(20),
  health_insurance_missing_reason   VARCHAR(500),
  health_insurance_expected_date    DATE,
  pension_insurance_status          VARCHAR(20),
  pension_insurance_missing_reason  VARCHAR(500),
  pension_insurance_expected_date   DATE,
  employment_insurance_status       VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  instruction_route                 CLOB,
  subcontract_allowed               TINYINT,
  acceptance_method                 VARCHAR(255),
  dispatch_period_start             DATE,
  dispatch_period_end               DATE,
  retention_due_date                DATE,
  legal_hold_flag                   TINYINT,
  current_snapshot_id               BIGINT,
  current_snapshot_version          INT,
  version                           INT NOT NULL DEFAULT 0,
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_contract_compliance_profile_contract UNIQUE (contract_id),
  CONSTRAINT chk_profile_period CHECK (dispatch_period_end IS NULL OR dispatch_period_start IS NULL OR dispatch_period_start <= dispatch_period_end),
  CONSTRAINT chk_profile_dispatch_count CHECK (dispatch_headcount IS NULL OR dispatch_headcount >= 0),
  CONSTRAINT chk_profile_fee CHECK (dispatch_fee_amount IS NULL OR dispatch_fee_amount >= 0),
  CONSTRAINT fk_profile_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_profile_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)
    ON DELETE SET NULL,
  CONSTRAINT fk_profile_current_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_snapshot(id)
    ON DELETE SET NULL
);
CREATE INDEX idx_profile_workplace ON t_contract_compliance_profile (workplace_id);
CREATE INDEX idx_profile_limitation ON t_contract_compliance_profile (workplace_limitation_date, organization_limitation_date);
CREATE INDEX idx_profile_dispatch_period ON t_contract_compliance_profile (dispatch_period_start, dispatch_period_end);

CREATE TABLE t_contract_compliance_worker_snapshot (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                   BIGINT NOT NULL,
  worker_id                     BIGINT NOT NULL,
  snapshot_version              INT NOT NULL,
  snapshot_hash                 VARCHAR(64) NOT NULL,
  operation_id                  VARCHAR(64),
  snapshot_at                   DATETIME,
  worker_name                   VARCHAR(100),
  employer_name                 VARCHAR(200),
  employer_address              VARCHAR(500),
  employer_title                VARCHAR(100),
  gender                        VARCHAR(20),
  age_band                      VARCHAR(30),
  age_at_reference_date         DATE,
  employment_term_type          VARCHAR(20),
  employment_from               DATE,
  employment_to                 DATE,
  indefinite_worker_flag        TINYINT,
  age_over_60_flag              TINYINT,
  worker_restriction_type       VARCHAR(30),
  health_insurance_status       VARCHAR(20),
  health_insurance_missing_reason VARCHAR(500),
  health_insurance_expected_date DATE,
  pension_insurance_status      VARCHAR(20),
  pension_insurance_missing_reason VARCHAR(500),
  pension_insurance_expected_date DATE,
  employment_insurance_status   VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  version                       INT NOT NULL DEFAULT 0,
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_worker_snapshot_version UNIQUE (contract_id, worker_id, snapshot_version),
  CONSTRAINT uk_worker_snapshot_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_worker_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_worker_snapshot_hash ON t_contract_compliance_worker_snapshot (snapshot_hash);
CREATE INDEX idx_worker_snapshot_worker ON t_contract_compliance_worker_snapshot (worker_id);

CREATE TABLE t_contract_compliance_worker_state (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                   VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                 BIGINT NOT NULL,
  worker_id                   BIGINT NOT NULL,
  current_snapshot_id         BIGINT,
  current_snapshot_version    INT,
  version                     INT NOT NULL DEFAULT 0,
  created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_worker_state_contract_worker UNIQUE (contract_id, worker_id),
  CONSTRAINT fk_worker_state_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_worker_state_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(id)
    ON DELETE SET NULL
);

CREATE TABLE t_compliance_snapshot_operation (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default',
  operation_id                  VARCHAR(64) NOT NULL,
  scope_type                    VARCHAR(20) NOT NULL,
  contract_id                   BIGINT NOT NULL,
  worker_id                     BIGINT,
  expected_version              INT,
  resulting_snapshot_id         BIGINT,
  resulting_worker_snapshot_id  BIGINT,
  request_hash                  VARCHAR(64),
  status                        VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED',
  version                       INT NOT NULL DEFAULT 0,
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_snapshot_operation UNIQUE (operation_id),
  CONSTRAINT fk_snapshot_operation_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_snapshot_operation_contract ON t_compliance_snapshot_operation (contract_id, scope_type);

CREATE TABLE t_compliance_work_calendar (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  work_day_code         VARCHAR(30),
  holiday_calendar_code VARCHAR(30),
  excluded_date         DATE,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_work_calendar_event UNIQUE (event_id),
  CONSTRAINT fk_work_calendar_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_work_calendar_contract ON t_compliance_work_calendar (contract_id, effective_from);

CREATE TABLE t_compliance_break_detail (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  break_no              INT NOT NULL,
  start_offset_minute   INT NOT NULL,
  end_offset_minute     INT NOT NULL,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_break_detail_event UNIQUE (event_id),
  CONSTRAINT chk_break_detail_offset CHECK (start_offset_minute >= 0 AND end_offset_minute > start_offset_minute),
  CONSTRAINT fk_break_detail_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_break_detail_contract ON t_compliance_break_detail (contract_id, effective_from);

CREATE TABLE t_compliance_complaint_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  complaint_type        VARCHAR(20),
  received_at           DATE,
  content               CLOB,
  action                CLOB,
  resolution            CLOB,
  notified_at           DATE,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_complaint_event UNIQUE (event_id),
  CONSTRAINT fk_complaint_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_complaint_contract ON t_compliance_complaint_history (contract_id, received_at);

CREATE TABLE t_employment_stability_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  request_at            DATE,
  request_method        VARCHAR(100),
  response_at           DATE,
  response_content      CLOB,
  action                CLOB,
  outcome               VARCHAR(100),
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_employment_stability_event UNIQUE (event_id),
  CONSTRAINT fk_employment_stability_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_employment_stability_contract ON t_employment_stability_history (contract_id, request_at);

CREATE TABLE t_training_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  training_date         DATE,
  minutes               INT,
  content               CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_training_event UNIQUE (event_id),
  CONSTRAINT fk_training_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_training_contract ON t_training_history (contract_id, training_date);

CREATE TABLE t_career_consulting_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  consulting_date       DATE,
  content               CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_career_consulting_event UNIQUE (event_id),
  CONSTRAINT fk_career_consulting_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_career_consulting_contract ON t_career_consulting_history (contract_id, consulting_date);

CREATE TABLE t_planned_introduction_terms (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  contract_period_from  DATE,
  contract_period_to    DATE,
  renewal_rule          VARCHAR(100),
  renewal_limit         INT,
  work_change_scope     VARCHAR(500),
  trial_period          VARCHAR(200),
  wage_detail           VARCHAR(500),
  insurance_detail      VARCHAR(500),
  smoking_measure       VARCHAR(500),
  employer_name         VARCHAR(200),
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_planned_introduction_terms_event UNIQUE (event_id),
  CONSTRAINT fk_planned_terms_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_planned_terms_contract ON t_planned_introduction_terms (contract_id, effective_from);

CREATE TABLE t_planned_introduction_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  introduction_date     DATE,
  outcome               VARCHAR(30),
  reason                CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_planned_introduction_event UNIQUE (event_id),
  CONSTRAINT fk_planned_introduction_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_planned_introduction_contract ON t_planned_introduction_history (contract_id, introduction_date);

CREATE TABLE t_direct_hire_dispute_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  measure               VARCHAR(500),
  fee_detail            VARCHAR(500),
  request_method        VARCHAR(200),
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_direct_hire_dispute_event UNIQUE (event_id),
  CONSTRAINT fk_direct_hire_dispute_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_direct_hire_dispute_contract ON t_direct_hire_dispute_history (contract_id, effective_from);

CREATE TABLE t_notification_difference_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  difference_type       VARCHAR(50),
  contract_snapshot_id  BIGINT,
  notice_snapshot_id    BIGINT,
  difference_detail     CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_notification_difference_event UNIQUE (event_id),
  CONSTRAINT fk_notification_difference_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_notification_difference_contract ON t_notification_difference_history (contract_id, occurred_at);

CREATE TABLE t_ledger_work_snapshot (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  work_month            DATE,
  work_days             INT,
  work_hours            INT,
  overtime_hours        INT,
  absence_days          INT,
  gross_amount          DECIMAL(14,2),
  closed_at             DATETIME,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_ledger_work_event UNIQUE (event_id),
  CONSTRAINT fk_ledger_work_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_ledger_work_contract ON t_ledger_work_snapshot (contract_id, work_month);

CREATE TABLE t_compliance_finding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  code                  VARCHAR(80) NOT NULL,
  severity              VARCHAR(20) NOT NULL DEFAULT 'WARNING',
  status                VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  condition_fingerprint VARCHAR(128) NOT NULL,
  detected_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  due_date              DATE,
  acknowledged_by      BIGINT,
  acknowledged_at       DATETIME,
  resolution_note       VARCHAR(2000),
  evidence_document_id  BIGINT,
  exception_expires_at  DATETIME,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_finding UNIQUE (contract_id, code, condition_fingerprint),
  CONSTRAINT fk_finding_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX idx_finding_status_due ON t_compliance_finding (status, due_date);
CREATE INDEX idx_finding_contract ON t_compliance_finding (contract_id, detected_at);

CREATE TABLE t_document_delivery (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id              BIGINT,
  document_id              BIGINT NOT NULL,
  document_type            VARCHAR(50),
  template_version         VARCHAR(50),
  effective_from           DATE,
  effective_to             DATE,
  snapshot_hash            VARCHAR(64),
  recipient_contact_id     BIGINT,
  recipient_name_snapshot  VARCHAR(200),
  recipient_email_snapshot VARCHAR(255),
  delivery_method          VARCHAR(30) NOT NULL,
  delivery_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  delivered_at             DATETIME,
  confirmed_at             DATETIME,
  confirmation_note        VARCHAR(1000),
  idempotency_key          VARCHAR(200),
  version                  INT NOT NULL DEFAULT 0,
  created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag             TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_delivery_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_delivery_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE SET NULL
);
CREATE INDEX idx_delivery_document ON t_document_delivery (document_id, delivered_at);
CREATE INDEX idx_delivery_contract ON t_document_delivery (contract_id, delivered_at);
CREATE INDEX idx_delivery_confirmation ON t_document_delivery (confirmed_at);

-- T066/R19-P1-01 G2 gate schema（H2ではtriggerを省略し、MySQL V102で強制する）
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mapping_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mapping_version VARCHAR(50);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mapping_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS review_policy_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS gate_evaluated_at TIMESTAMP(6);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS gate_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS profile_snapshot_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS profile_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS worker_snapshot_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS worker_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS workplace_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS render_input_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS recipient_display_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS company_config_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS field_mask_policy_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS render_engine_version VARCHAR(100);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS rendition_group_id VARCHAR(36);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS full_document_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS full_document_sha256 CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mask_document_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mask_document_sha256 CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS limited_document_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS limited_document_sha256 CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS delivery_business_key CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS generation_state VARCHAR(20);
CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_business_key ON t_document_delivery(tenant_id, delivery_business_key);
CREATE INDEX IF NOT EXISTS idx_delivery_mapping_version ON t_document_delivery(tenant_id, mapping_version_id);
CREATE INDEX IF NOT EXISTS idx_delivery_gate_evaluated ON t_document_delivery(tenant_id, gate_evaluated_at);
CREATE INDEX IF NOT EXISTS idx_delivery_rendition_group ON t_document_delivery(tenant_id, rendition_group_id);

CREATE TABLE IF NOT EXISTS m_compliance_mapping_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_code VARCHAR(100) NOT NULL,
  mapping_version VARCHAR(50) NOT NULL, mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL,
  effective_from DATE NOT NULL, effective_to DATE, status VARCHAR(30) NOT NULL, active_slot TINYINT, future_slot TINYINT,
  activated_at TIMESTAMP(6), activated_by BIGINT, version INT NOT NULL DEFAULT 0, created_by BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, mapping_version), UNIQUE(tenant_id, mapping_code, active_slot),
   UNIQUE(tenant_id, mapping_code, future_slot), UNIQUE(tenant_id, id), CHECK(status IN ('DRAFT','PROVISIONAL_REVIEWED','ACTIVE','SUPERSEDED')),
   CHECK((status = 'ACTIVE' AND active_slot = 1) OR (status <> 'ACTIVE' AND active_slot IS NULL)), CHECK(future_slot IS NULL OR future_slot = 1)
);
CREATE INDEX IF NOT EXISTS idx_g2_mapping_effective ON m_compliance_mapping_version(tenant_id, mapping_code, status, effective_from, effective_to);

CREATE TABLE IF NOT EXISTS m_compliance_mapping_source (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  source_code VARCHAR(100) NOT NULL, source_url VARCHAR(1000) NOT NULL, source_version VARCHAR(100) NOT NULL,
  confirmed_on DATE NOT NULL, effective_from DATE NOT NULL, effective_to DATE, created_by BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, mapping_id, source_code), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_source_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_g2_mapping_source_lookup ON m_compliance_mapping_source(tenant_id, source_code, confirmed_on);

CREATE TABLE IF NOT EXISTS m_compliance_external_reviewer_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', type_code VARCHAR(100) NOT NULL,
  display_name VARCHAR(200) NOT NULL, description VARCHAR(1000), credential_label VARCHAR(200) NOT NULL,
  credential_required TINYINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1, sort_order INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0, created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT,
  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, type_code), UNIQUE(tenant_id, id), CHECK(credential_required IN (0,1)), CHECK(enabled IN (0,1))
);
CREATE INDEX IF NOT EXISTS idx_g2_reviewer_type_enabled ON m_compliance_external_reviewer_type(tenant_id, enabled, sort_order);

CREATE TABLE IF NOT EXISTS m_compliance_mapping_review_requirement_group (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  requirement_group_code VARCHAR(100) NOT NULL, display_name VARCHAR(200) NOT NULL, minimum_distinct_reviewers INT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0, created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
   UNIQUE(tenant_id, mapping_id, requirement_group_code), UNIQUE(tenant_id, id), CHECK(minimum_distinct_reviewers >= 1),
   CONSTRAINT fk_g2_review_group_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_g2_review_group_mapping ON m_compliance_mapping_review_requirement_group(tenant_id, mapping_id, sort_order);

CREATE TABLE IF NOT EXISTS m_compliance_mapping_review_requirement_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', requirement_group_id BIGINT NOT NULL,
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  credential_label_snapshot VARCHAR(200) NOT NULL, credential_required_snapshot TINYINT NOT NULL, created_by BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, requirement_group_id, reviewer_type_id), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_review_type_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id),
   CONSTRAINT fk_g2_review_type_reviewer FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_g2_review_type_reviewer ON m_compliance_mapping_review_requirement_type(tenant_id, reviewer_type_id);

CREATE TABLE IF NOT EXISTS t_compliance_responsible_assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', workplace_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
   role_code VARCHAR(40) NOT NULL DEFAULT 'COMPLIANCE_RESPONSIBLE', effective_from TIMESTAMP(6) NOT NULL, effective_to TIMESTAMP(6), active_slot TINYINT,
  assigned_by BIGINT NOT NULL, ended_by BIGINT, end_reason VARCHAR(500), version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, workplace_id, active_slot), UNIQUE(tenant_id, id), CHECK(role_code = 'COMPLIANCE_RESPONSIBLE'),
  CHECK(effective_to IS NULL OR effective_from < effective_to),
   CHECK((effective_to IS NULL AND active_slot = 1 AND ended_by IS NULL AND end_reason IS NULL) OR (effective_to IS NOT NULL AND active_slot IS NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)),
   CONSTRAINT fk_g2_assignment_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_g2_assignment_period ON t_compliance_responsible_assignment(tenant_id, workplace_id, effective_from, effective_to);
CREATE INDEX IF NOT EXISTS idx_g2_assignment_user_period ON t_compliance_responsible_assignment(tenant_id, user_id, effective_from, effective_to);

CREATE TABLE IF NOT EXISTS t_compliance_mapping_approval_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  mapping_version VARCHAR(50) NOT NULL, mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, assignment_id BIGINT NOT NULL,
  workplace_id_snapshot BIGINT NOT NULL, actor_id BIGINT NOT NULL, actor_display_name_snapshot VARCHAR(200) NOT NULL, actor_role_snapshot VARCHAR(50) NOT NULL,
  action VARCHAR(20) NOT NULL, event_chain_id VARCHAR(36) NOT NULL, target_event_id BIGINT, supersedes_event_id BIGINT, occurred_at TIMESTAMP(6) NOT NULL,
  reason VARCHAR(1000), evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64),
   operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, id), CHECK(action IN ('APPROVE','REJECT','REVOKE')),
   CONSTRAINT fk_g2_approval_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
   CONSTRAINT fk_g2_approval_assignment FOREIGN KEY (tenant_id, assignment_id) REFERENCES t_compliance_responsible_assignment(tenant_id, id),
   CONSTRAINT fk_g2_approval_workplace FOREIGN KEY (tenant_id, workplace_id_snapshot) REFERENCES m_workplace(tenant_id, id),
   CONSTRAINT fk_g2_approval_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id),
   CONSTRAINT fk_g2_approval_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_g2_approval_scope ON t_compliance_mapping_approval_event(tenant_id, mapping_id, workplace_id_snapshot, assignment_id, occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_g2_approval_chain ON t_compliance_mapping_approval_event(tenant_id, event_chain_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS t_compliance_external_review_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, requirement_group_id BIGINT NOT NULL, requirement_group_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_name_snapshot VARCHAR(200) NOT NULL, organization_snapshot VARCHAR(255), credential_snapshot_encrypted CLOB, credential_key_version VARCHAR(64),
  credential_cipher_format VARCHAR(20), credential_masked_snapshot VARCHAR(255), reviewer_identity_hash CHAR(64) NOT NULL, action VARCHAR(20) NOT NULL,
  review_chain_id VARCHAR(36) NOT NULL, target_event_id BIGINT, supersedes_event_id BIGINT, reviewed_at TIMESTAMP(6) NOT NULL, valid_until TIMESTAMP(6), recorded_at TIMESTAMP(6) NOT NULL,
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64), recorded_by BIGINT NOT NULL,
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, id), CHECK(action IN ('SUBMITTED','APPROVED','REJECTED','REVOKED')),
   CONSTRAINT fk_g2_external_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
   CONSTRAINT fk_g2_external_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id),
   CONSTRAINT fk_g2_external_reviewer_type FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id),
   CONSTRAINT fk_g2_external_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
   CONSTRAINT fk_g2_external_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
   CHECK((credential_snapshot_encrypted IS NULL AND credential_key_version IS NULL AND credential_cipher_format IS NULL AND credential_masked_snapshot IS NULL) OR (credential_snapshot_encrypted IS NOT NULL AND credential_key_version IS NOT NULL AND credential_cipher_format IS NOT NULL AND credential_masked_snapshot IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_g2_external_review_scope ON t_compliance_external_review_event(tenant_id, mapping_id, requirement_group_id, reviewer_identity_hash, recorded_at, id);
CREATE INDEX IF NOT EXISTS idx_g2_external_review_chain ON t_compliance_external_review_event(tenant_id, review_chain_id, recorded_at, id);

CREATE TABLE IF NOT EXISTS t_compliance_mapping_status_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, before_status VARCHAR(30), after_status VARCHAR(30) NOT NULL,
  actor_id BIGINT NOT NULL, actor_display_name_snapshot VARCHAR(200) NOT NULL, actor_role_snapshot VARCHAR(50) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
  expected_version INT NOT NULL, gate_snapshot_hash CHAR(64), operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, reason VARCHAR(1000),
   created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, UNIQUE(tenant_id, id), CHECK(after_status IN ('DRAFT','PROVISIONAL_REVIEWED','ACTIVE','SUPERSEDED')),
   CONSTRAINT fk_g2_status_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_g2_status_mapping ON t_compliance_mapping_status_event(tenant_id, mapping_id, occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_g2_status_correlation ON t_compliance_mapping_status_event(tenant_id, correlation_id);

CREATE TABLE IF NOT EXISTS t_compliance_operation_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', operation_id VARCHAR(36) NOT NULL,
  operation_type VARCHAR(60) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, request_hash CHAR(64) NOT NULL, state VARCHAR(20) NOT NULL,
  retryable_flag TINYINT NOT NULL DEFAULT 0, attempt_count INT NOT NULL DEFAULT 1, started_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6), finished_at TIMESTAMP(6),
  result_reference_type VARCHAR(80), result_reference_id BIGINT, result_reference_version VARCHAR(100), result_summary_canonical CLOB, result_http_status INT,
  result_hash CHAR(64), failure_code VARCHAR(100), correlation_id VARCHAR(100) NOT NULL, expires_at TIMESTAMP(6), version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, operation_type, idempotency_key), UNIQUE(tenant_id, operation_id), CHECK(state IN ('PROCESSING','SUCCEEDED','FAILED')), CHECK(retryable_flag IN (0,1)),
  CHECK((state = 'SUCCEEDED' AND finished_at IS NOT NULL AND failure_code IS NULL
      AND result_summary_canonical IS NOT NULL AND result_http_status IS NOT NULL AND result_hash IS NOT NULL)
    OR (state = 'PROCESSING' AND finished_at IS NULL AND failure_code IS NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL)
    OR (state = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_g2_operation_lease ON t_compliance_operation_ledger(tenant_id, state, lease_until);
CREATE INDEX IF NOT EXISTS idx_g2_operation_result ON t_compliance_operation_ledger(tenant_id, result_reference_type, result_reference_id);
