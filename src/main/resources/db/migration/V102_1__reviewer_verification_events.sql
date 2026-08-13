-- ============================================================
-- V102_1: reviewer verification / adoption events (R23-P1-01)
-- V102は変更しない。V102適用後のforward migrationとして、
--  1) chk_g2_external_review_action を forward replacement
--     ('APPROVED','REJECTED','REVOKED') -> ('SUBMITTED','APPROVED','REJECTED','REVOKED')
--     （新規write pathはSUBMITTEDのみ。既存APPROVED/REJECTED/REVOKED rowはlegacy扱い）
--  2) chk_g2_operation_type を forward replacement（verification/adoption系5種を追加）
--  3) t_compliance_external_reviewer_subject（person-stable subject master）
--  4) t_compliance_external_reviewer_verification_event（append-only）
--  5) t_compliance_external_review_adoption_event（append-only）
-- を追加する。UPDATE/DELETEはMySQL triggerで拒否する。
-- ============================================================

-- ---- 1) chk_g2_external_review_action forward replacement ----
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_1_repair_check$$
CREATE PROCEDURE __ses_g2_1_repair_check(IN p_table VARCHAR(64), IN p_constraint VARCHAR(64), IN p_sql TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
              WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = BINARY p_table
                AND CONSTRAINT_NAME = BINARY p_constraint AND CONSTRAINT_TYPE = 'CHECK') THEN
    SET @g2_drop_check_sql = CONCAT('ALTER TABLE ', p_table, ' DROP CHECK ', p_constraint);
    PREPARE g2_drop_check_stmt FROM @g2_drop_check_sql;
    EXECUTE g2_drop_check_stmt;
    DEALLOCATE PREPARE g2_drop_check_stmt;
  END IF;
  SET @g2_add_check_sql = p_sql;
  PREPARE g2_add_check_stmt FROM @g2_add_check_sql;
  EXECUTE g2_add_check_stmt;
  DEALLOCATE PREPARE g2_add_check_stmt;
END$$
DELIMITER ;

CALL __ses_g2_1_repair_check('t_compliance_external_review_event', 'chk_g2_external_review_action',
'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT chk_g2_external_review_action CHECK (action IN (''SUBMITTED'',''APPROVED'',''REJECTED'',''REVOKED''))');

-- ---- 2) chk_g2_operation_type forward replacement（5種追加） ----
CALL __ses_g2_1_repair_check('t_compliance_operation_ledger', 'chk_g2_operation_type',
'ALTER TABLE t_compliance_operation_ledger ADD CONSTRAINT chk_g2_operation_type CHECK (operation_type IN (''MAPPING_DRAFT_UPSERT'',''MAPPING_PROVISIONAL_REVIEW'',''ASSIGNMENT_CREATE'',''ASSIGNMENT_END'',''MAPPING_ACTIVE'',''MAPPING_SUPERSEDE'',''INTERNAL_APPROVAL'',''EXTERNAL_REVIEW'',''EXTERNAL_REVIEW_REVOKE'',''DELIVERY_GENERATE'',''REVIEWER_TYPE_CREATE'',''REVIEWER_TYPE_UPDATE'',''REVIEWER_TYPE_DISABLE'',''REVIEW_REQUIREMENT_UPDATE'',''EXTERNAL_REVIEW_SUBMIT'',''REVIEWER_VERIFICATION_RECORD'',''REVIEWER_VERIFICATION_REVOKE'',''EXTERNAL_REVIEW_ADOPT'',''EXTERNAL_REVIEW_REVOKE''))');

-- ---- 3) t_compliance_external_reviewer_subject（person-stable subject master） ----
CREATE TABLE IF NOT EXISTS t_compliance_external_reviewer_subject (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  subject_code               VARCHAR(100) NOT NULL,
  display_name               VARCHAR(200) NOT NULL,
  organization_name          VARCHAR(200) NOT NULL,
  person_fingerprint_snapshot CHAR(64) NOT NULL,
  fingerprint_key_version    VARCHAR(64) NOT NULL,
  created_by                 BIGINT,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by                 BIGINT,
  updated_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag               TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_subject UNIQUE (tenant_id, subject_code),
  CONSTRAINT uk_g2_subject_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_subject_fingerprint CHECK (CHAR_LENGTH(person_fingerprint_snapshot) = 64),
  CONSTRAINT fk_g2_subject_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external reviewer subject (person-stable)';

-- 仮の人物・資格・Review・verificationはseed/backfillしない（accepted v3 Step 2-7）。

-- ---- 4) t_compliance_external_reviewer_verification_event（append-only・K2用途別FK列） ----
CREATE TABLE IF NOT EXISTS t_compliance_external_reviewer_verification_event (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  reviewer_type_id           BIGINT NOT NULL,
  reviewer_type_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_subject_id        BIGINT NOT NULL,
  person_fingerprint_snapshot CHAR(64) NOT NULL,
  qualification_fingerprint_snapshot CHAR(64) NOT NULL,
  fingerprint_key_version    VARCHAR(64) NOT NULL,
  verification_kind          VARCHAR(20) NOT NULL,
  result                     VARCHAR(20) NOT NULL,
  method_code                VARCHAR(50) NOT NULL,
  authority_source_code      VARCHAR(50) NOT NULL,
  authority_source_name      VARCHAR(200) NOT NULL,
  official_url_reference_snapshot VARCHAR(1000),
  registration_identifier_encrypted TEXT,
  registration_identifier_key_version VARCHAR(64),
  registration_identifier_cipher_format VARCHAR(20),
  registration_identifier_masked_snapshot VARCHAR(255),
  checked_at                 DATETIME(6) NOT NULL,
  source_data_as_of          DATETIME(6),
  max_age_days_snapshot      INT,
  valid_until                DATETIME(6),
  checked_by                 BIGINT NOT NULL,
  evidence_document_id       BIGINT,
  evidence_document_version_id BIGINT,
  evidence_document_version  VARCHAR(100),
  evidence_document_hash     CHAR(64),
  review_policy_version      VARCHAR(50),
  review_policy_hash         CHAR(64),
  mapping_id                 BIGINT,
  mapping_version            VARCHAR(50),
  mapping_hash               CHAR(64),
  external_review_event_id   BIGINT,
  external_review_chain_id   VARCHAR(36),
  submitted_review_event_id  BIGINT NOT NULL,
  revoked_verification_event_id BIGINT,
  supersedes_verification_event_id BIGINT,
  operation_id               VARCHAR(36) NOT NULL,
  correlation_id             VARCHAR(100) NOT NULL,
  idempotency_key            VARCHAR(200) NOT NULL,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_verification_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT uk_g2_verification_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_verification_kind CHECK (verification_kind IN ('IDENTITY','QUALIFICATION','ACTIVE_STATUS','REVIEW_AUTHORSHIP')),
  CONSTRAINT chk_g2_verification_result CHECK (result IN ('VERIFIED','FAILED','INCONCLUSIVE','REVOKED')),
  CONSTRAINT chk_g2_verification_fingerprint CHECK (CHAR_LENGTH(person_fingerprint_snapshot) = 64 AND CHAR_LENGTH(qualification_fingerprint_snapshot) = 64),
  -- NOTE: MySQL 8はCHECKとFKの同一列併用不可（Error 3823）のため、evidence all-or-none・authorship binding・
  -- revoke targetの検証はBEFORE INSERT trigger（trg_g2_verification_*）で行う。H2側はCHECKで担保する。
  CONSTRAINT fk_g2_verification_subject FOREIGN KEY (tenant_id, reviewer_subject_id)
    REFERENCES t_compliance_external_reviewer_subject(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_submitted FOREIGN KEY (tenant_id, submitted_review_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_revoked FOREIGN KEY (tenant_id, revoked_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_supersedes FOREIGN KEY (tenant_id, supersedes_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_evidence FOREIGN KEY (tenant_id, evidence_document_version_id)
    REFERENCES t_document_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_mapping FOREIGN KEY (tenant_id, mapping_id)
    REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_verification_review FOREIGN KEY (tenant_id, external_review_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external reviewer verification event (append-only)';

-- ---- 5) t_compliance_external_review_adoption_event（append-only・gate採用はAPPROVEDのみ） ----
CREATE TABLE IF NOT EXISTS t_compliance_external_review_adoption_event (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  action                     VARCHAR(20) NOT NULL,
  review_chain_id            VARCHAR(36) NOT NULL,
  submitted_review_event_id  BIGINT NOT NULL,
  revoked_adoption_event_id  BIGINT,
  identity_verification_event_id BIGINT,
  qualification_verification_event_id BIGINT,
  active_status_verification_event_id BIGINT,
  authorship_verification_event_id BIGINT,
  mapping_id                 BIGINT,
  mapping_version            VARCHAR(50),
  mapping_hash               CHAR(64),
  review_policy_version      VARCHAR(50),
  review_policy_hash         CHAR(64),
  evidence_document_id       BIGINT,
  evidence_document_version_id BIGINT,
  evidence_document_version  VARCHAR(100),
  evidence_document_hash     CHAR(64),
  adopted_at                 DATETIME(6) NOT NULL,
  adopted_by                 BIGINT NOT NULL,
  operation_id               VARCHAR(36) NOT NULL,
  correlation_id             VARCHAR(100) NOT NULL,
  idempotency_key            VARCHAR(200) NOT NULL,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_adoption_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT uk_g2_adoption_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_adoption_action CHECK (action IN ('APPROVED','REJECTED','REVOKED')),
  -- NOTE: MySQL 8はCHECKとFKの同一列併用不可（Error 3823）のため、approved refs・revoke targetの検証は
  -- BEFORE INSERT trigger（trg_g2_adoption_*）で行う。H2側はCHECKで担保する。
  CONSTRAINT fk_g2_adoption_submitted FOREIGN KEY (tenant_id, submitted_review_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_revoked FOREIGN KEY (tenant_id, revoked_adoption_event_id)
    REFERENCES t_compliance_external_review_adoption_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_identity FOREIGN KEY (tenant_id, identity_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_qualification FOREIGN KEY (tenant_id, qualification_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_active_status FOREIGN KEY (tenant_id, active_status_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_authorship FOREIGN KEY (tenant_id, authorship_verification_event_id)
    REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_evidence FOREIGN KEY (tenant_id, evidence_document_version_id)
    REFERENCES t_document_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_adoption_mapping FOREIGN KEY (tenant_id, mapping_id)
    REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external review adoption event (append-only)';

-- ---- append-only trigger（UPDATE/DELETE拒否）＋revoke target検証（BEFORE INSERT） ----
DELIMITER $$
DROP TRIGGER IF EXISTS trg_g2_verification_no_update$$
CREATE TRIGGER trg_g2_verification_no_update BEFORE UPDATE ON t_compliance_external_reviewer_verification_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_verification_no_delete$$
CREATE TRIGGER trg_g2_verification_no_delete BEFORE DELETE ON t_compliance_external_reviewer_verification_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_verification_revoke_target$$
CREATE TRIGGER trg_g2_verification_revoke_target BEFORE INSERT ON t_compliance_external_reviewer_verification_event
FOR EACH ROW
BEGIN
  IF (NEW.result = 'REVOKED' AND NEW.revoked_verification_event_id IS NULL)
     OR (NEW.result <> 'REVOKED' AND NEW.revoked_verification_event_id IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification revoke target is invalid';
  END IF;
  IF (NEW.registration_identifier_encrypted IS NULL AND NEW.registration_identifier_key_version IS NULL
        AND NEW.registration_identifier_cipher_format IS NULL AND NEW.registration_identifier_masked_snapshot IS NULL)
     OR (NEW.registration_identifier_encrypted IS NOT NULL AND NEW.registration_identifier_key_version IS NOT NULL
        AND NEW.registration_identifier_cipher_format IS NOT NULL AND NEW.registration_identifier_masked_snapshot IS NOT NULL) THEN
    SET @g2_cred_ok = 1;
  ELSE
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification credential all-or-none violated';
  END IF;
  IF (NEW.evidence_document_id IS NULL AND NEW.evidence_document_version_id IS NULL
        AND NEW.evidence_document_version IS NULL AND NEW.evidence_document_hash IS NULL)
     OR (NEW.evidence_document_id IS NOT NULL AND NEW.evidence_document_version_id IS NOT NULL
        AND NEW.evidence_document_version IS NOT NULL AND NEW.evidence_document_hash IS NOT NULL) THEN
    SET @g2_ev_ok = 1;
  ELSE
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification evidence all-or-none violated';
  END IF;
  IF NEW.verification_kind = 'REVIEW_AUTHORSHIP' THEN
    IF NEW.review_policy_version IS NULL OR NEW.review_policy_hash IS NULL
       OR NEW.mapping_id IS NULL OR NEW.mapping_version IS NULL OR NEW.mapping_hash IS NULL
       OR NEW.external_review_event_id IS NULL OR NEW.external_review_chain_id IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification authorship binding missing';
    END IF;
  ELSE
    IF NEW.review_policy_version IS NOT NULL OR NEW.review_policy_hash IS NOT NULL
       OR NEW.mapping_id IS NOT NULL OR NEW.mapping_version IS NOT NULL OR NEW.mapping_hash IS NOT NULL
       OR NEW.external_review_event_id IS NOT NULL OR NEW.external_review_chain_id IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 verification binding only for REVIEW_AUTHORSHIP';
    END IF;
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_adoption_no_update$$
CREATE TRIGGER trg_g2_adoption_no_update BEFORE UPDATE ON t_compliance_external_review_adoption_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 adoption event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_adoption_no_delete$$
CREATE TRIGGER trg_g2_adoption_no_delete BEFORE DELETE ON t_compliance_external_review_adoption_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 adoption event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_adoption_revoke_target$$
CREATE TRIGGER trg_g2_adoption_revoke_target BEFORE INSERT ON t_compliance_external_review_adoption_event
FOR EACH ROW
BEGIN
  IF (NEW.action = 'REVOKED' AND NEW.revoked_adoption_event_id IS NULL)
     OR (NEW.action <> 'REVOKED' AND NEW.revoked_adoption_event_id IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 adoption revoke target is invalid';
  END IF;
  IF NEW.action = 'APPROVED' THEN
    IF NEW.identity_verification_event_id IS NULL OR NEW.authorship_verification_event_id IS NULL
       OR NEW.mapping_id IS NULL OR NEW.mapping_version IS NULL OR NEW.mapping_hash IS NULL
       OR NEW.review_policy_version IS NULL OR NEW.review_policy_hash IS NULL
       OR NEW.evidence_document_id IS NULL OR NEW.evidence_document_version_id IS NULL
       OR NEW.evidence_document_version IS NULL OR NEW.evidence_document_hash IS NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 adoption APPROVED refs missing';
    END IF;
  ELSE
    IF NEW.identity_verification_event_id IS NOT NULL OR NEW.authorship_verification_event_id IS NOT NULL
       OR NEW.mapping_id IS NOT NULL OR NEW.mapping_version IS NOT NULL OR NEW.mapping_hash IS NOT NULL
       OR NEW.review_policy_version IS NOT NULL OR NEW.review_policy_hash IS NOT NULL
       OR NEW.evidence_document_id IS NOT NULL OR NEW.evidence_document_version_id IS NOT NULL
       OR NEW.evidence_document_version IS NOT NULL OR NEW.evidence_document_hash IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 adoption refs only for APPROVED';
    END IF;
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_subject_no_delete$$
CREATE TRIGGER trg_g2_subject_no_delete BEFORE DELETE ON t_compliance_external_reviewer_subject
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 subject master is permanent'$$
DELIMITER ;

-- 社労士・弁護士等を固定enum/seedにしない（accepted v3 §3.8 dynamic master・Step 2-6）。
