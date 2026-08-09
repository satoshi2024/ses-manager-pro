-- T061 F1 R5 H2 schema. Mirrors the V1/V84 MySQL shape; document/contact/sys_user/engineer
-- FKs and DB triggers are omitted for H2 (immutability enforced by mapper INSERT/SELECT-only boundary).
DROP TABLE IF EXISTS t_document_delivery CASCADE;
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
