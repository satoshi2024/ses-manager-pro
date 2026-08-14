-- ============================================================
-- V102_3: compliance gate dynamic policy repair (R23-P1-01 repair delta)
-- V102/V102_1/V102_2 は変更しない（published/immutable）。
-- forward migrationとして:
--  1) m_compliance_verification_source（dynamic official source master・§3.8）
--  2) m_compliance_verification_method（dynamic verification method master・§3.8）
--  3) m_compliance_external_reviewer_type へ dynamic 設定列を追加
--     （qualification_verification_required / active_status_verification_required:
--      TINYINT NULL=UNCONFIGURED・§8 DEFAULT 0 禁止・verification_source_id /
--      verification_method_id / max_age_days / effective_from / effective_to）
--  4) m_compliance_mapping_review_requirement_type へ frozen snapshot 列を追加
--     （qualification_verification_required_snapshot /
--      active_status_verification_required_snapshot: TINYINT NOT NULL・freeze時確定）
--  5) t_compliance_reviewer_qualification（subject×qualification association・§9）
--  6) internal approval event へ exact evidence snapshot 列を追加（P0-5）
--  7) 並行first adoptionのDB一意化（P1-5・生成列+UNIQUE）
--  8) subject masterのUPDATE拒否trigger（P1-4・immutable契約）
-- 注: mysql CLIは複数回のDELIMITER切り替えを処理できないため、triggerブロックを先頭に置く。
-- ============================================================

-- ---- 8) subject masterのUPDATE拒否trigger（P1-4・immutable契約） ----
-- V102_1でDELETE拒否済み。person-stable正本はimmutable（§9・G2-VERIFY-10）。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_g2_subject_no_update$$
CREATE TRIGGER trg_g2_subject_no_update BEFORE UPDATE ON t_compliance_external_reviewer_subject
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reviewer subject is immutable';
END$$
DELIMITER ;

-- ---- 1) dynamic official source master（§3.8） ----
CREATE TABLE IF NOT EXISTS m_compliance_verification_source (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        VARCHAR(100) NOT NULL DEFAULT 'default',
  source_code      VARCHAR(50)  NOT NULL COMMENT 'source code（例: PUBLIC_REGISTRY・動的）',
  source_name      VARCHAR(200) NOT NULL COMMENT '表示名',
  official_url     VARCHAR(1000) NULL COMMENT '公式URL（server-side fetch禁止・§G2-VERIFY-05）',
  enabled          TINYINT      NOT NULL DEFAULT 1,
  effective_from   DATE         NULL COMMENT '有効開始日（NULL=制限なし）',
  effective_to     DATE         NULL COMMENT '有効終了日（NULL=無期限）',
  sort_order       INT          NOT NULL DEFAULT 0,
  created_by       BIGINT       NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by       BIGINT       NULL,
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag     TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_verification_source UNIQUE (tenant_id, source_code),
  CONSTRAINT uk_g2_verification_source_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_verification_source_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT fk_g2_verification_source_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_verification_source_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 dynamic verification official source master (R23-P1-01 §3.8)';

-- ---- 2) dynamic verification method master（§3.8） ----
CREATE TABLE IF NOT EXISTS m_compliance_verification_method (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        VARCHAR(100) NOT NULL DEFAULT 'default',
  method_code      VARCHAR(50)  NOT NULL COMMENT 'method code（例: MANUAL_PUBLIC_SOURCE・動的）',
  method_name      VARCHAR(200) NOT NULL COMMENT '表示名',
  description      VARCHAR(1000) NULL,
  enabled          TINYINT      NOT NULL DEFAULT 1,
  effective_from   DATE         NULL COMMENT '有効開始日（NULL=制限なし）',
  effective_to     DATE         NULL COMMENT '有効終了日（NULL=無期限）',
  sort_order       INT          NOT NULL DEFAULT 0,
  created_by       BIGINT       NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by       BIGINT       NULL,
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag     TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_verification_method UNIQUE (tenant_id, method_code),
  CONSTRAINT uk_g2_verification_method_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_verification_method_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT fk_g2_verification_method_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_verification_method_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 dynamic verification method master (R23-P1-01 §3.8)';

-- ---- 3) reviewer type へ dynamic 設定列を追加（§8・NULL=UNCONFIGURED） ----
SET @g2_v103_has_col := NULL;
SELECT COUNT(*) INTO @g2_v103_has_col FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'm_compliance_external_reviewer_type'
    AND COLUMN_NAME = 'qualification_verification_required';
SET @g2_v103_sql := IF(@g2_v103_has_col = 0,
  'ALTER TABLE m_compliance_external_reviewer_type
     ADD COLUMN qualification_verification_required TINYINT NULL COMMENT ''資格有効性確認必須（NULL=UNCONFIGURED・§8）'' AFTER credential_required,
   ADD COLUMN active_status_verification_required TINYINT NULL COMMENT ''業務状態確認必須（NULL=UNCONFIGURED・§8）'' AFTER qualification_verification_required,
   ADD COLUMN verification_source_id BIGINT NULL COMMENT ''dynamic official source（§3.8）'' AFTER active_status_verification_required,
   ADD COLUMN verification_method_id BIGINT NULL COMMENT ''dynamic verification method（§3.8）'' AFTER verification_source_id,
   ADD COLUMN max_age_days INT NULL COMMENT ''確認freshness上限（日・NULL=UNCONFIGURED）'' AFTER verification_method_id,
   ADD COLUMN effective_from DATE NULL COMMENT ''type有効開始日'' AFTER max_age_days,
   ADD COLUMN effective_to DATE NULL COMMENT ''type有効終了日（NULL=無期限）'' AFTER effective_from',
  'SELECT 1');
PREPARE g2_v103_stmt FROM @g2_v103_sql;
EXECUTE g2_v103_stmt;
DEALLOCATE PREPARE g2_v103_stmt;

-- CHECK制約（source/method参照列の整合・enabled等）
SET @g2_v103_has_check := NULL;
SELECT COUNT(*) INTO @g2_v103_has_check FROM information_schema.TABLE_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = BINARY 'm_compliance_external_reviewer_type'
    AND CONSTRAINT_NAME = BINARY 'chk_g2_reviewer_verify_required' AND CONSTRAINT_TYPE = 'CHECK';
SET @g2_v103_check_sql := IF(@g2_v103_has_check = 0,
  'ALTER TABLE m_compliance_external_reviewer_type
     ADD CONSTRAINT chk_g2_reviewer_verify_required CHECK (
       (qualification_verification_required IS NULL OR qualification_verification_required IN (0, 1))
       AND (active_status_verification_required IS NULL OR active_status_verification_required IN (0, 1))
       AND (max_age_days IS NULL OR max_age_days >= 1))',
  'SELECT 1');
PREPARE g2_v103_check_stmt FROM @g2_v103_check_sql;
EXECUTE g2_v103_check_stmt;
DEALLOCATE PREPARE g2_v103_check_stmt;

-- ---- 4) requirement type へ frozen snapshot 列を追加（freeze時に確定） ----
SET @g2_v103_has_snap := NULL;
SELECT COUNT(*) INTO @g2_v103_has_snap FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'm_compliance_mapping_review_requirement_type'
    AND COLUMN_NAME = 'qualification_verification_required_snapshot';
SET @g2_v103_snap_sql := IF(@g2_v103_has_snap = 0,
  'ALTER TABLE m_compliance_mapping_review_requirement_type
     ADD COLUMN qualification_verification_required_snapshot TINYINT NOT NULL DEFAULT 0 COMMENT ''freeze時snapshot・§8'' AFTER credential_required_snapshot,
   ADD COLUMN active_status_verification_required_snapshot TINYINT NOT NULL DEFAULT 0 COMMENT ''freeze時snapshot・§8'' AFTER qualification_verification_required_snapshot',
  'SELECT 1');
PREPARE g2_v103_snap_stmt FROM @g2_v103_snap_sql;
EXECUTE g2_v103_snap_stmt;
DEALLOCATE PREPARE g2_v103_snap_stmt;

-- ---- 5) subject×qualification association（§9・G2-SUBJECT-01） ----
CREATE TABLE IF NOT EXISTS t_compliance_reviewer_qualification (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  reviewer_subject_id   BIGINT NOT NULL COMMENT 'person-stable subject（§G2-VERIFY-13）',
  reviewer_type_id      BIGINT NOT NULL COMMENT '資格type（dynamic master）',
  registration_identifier_masked_snapshot VARCHAR(255) NULL COMMENT '登録識別子（maskedのみ・full値はverification event側で暗号化）',
  registration_identifier_label VARCHAR(200) NULL COMMENT '登録識別子のlabel/rule（§3.8）',
  enabled               TINYINT NOT NULL DEFAULT 1,
  created_by            BIGINT NULL,
  created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by            BIGINT NULL,
  updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_reviewer_qualification UNIQUE (tenant_id, reviewer_subject_id, reviewer_type_id),
  CONSTRAINT uk_g2_reviewer_qualification_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_reviewer_qualification_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT fk_g2_qualification_subject FOREIGN KEY (tenant_id, reviewer_subject_id)
    REFERENCES t_compliance_external_reviewer_subject(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_qualification_type FOREIGN KEY (tenant_id, reviewer_type_id)
    REFERENCES m_compliance_external_reviewer_type(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_qualification_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_qualification_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 reviewer subject qualification association (R23-P1-01 §9)';

-- ---- 6) internal approval event へ exact evidence snapshot 列を追加（P0-5） ----
SET @g2_v103_has_ev_col := NULL;
SELECT COUNT(*) INTO @g2_v103_has_ev_col FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_compliance_mapping_approval_event'
    AND COLUMN_NAME = 'evidence_document_version_id';
SET @g2_v103_ev_sql := IF(@g2_v103_has_ev_col = 0,
  'ALTER TABLE t_compliance_mapping_approval_event
     ADD COLUMN evidence_document_version_id BIGINT NULL COMMENT ''exact evidence version（§4-5/6・P0-5）'' AFTER evidence_document_id,
   ADD COLUMN evidence_document_version VARCHAR(100) NULL COMMENT ''exact version番号'' AFTER evidence_document_version_id,
   ADD COLUMN evidence_document_hash CHAR(64) NULL COMMENT ''exact SHA-256'' AFTER evidence_document_version,
   ADD COLUMN evidence_scan_status VARCHAR(30) NULL COMMENT ''scan=CLEAN必須'' AFTER evidence_document_hash',
  'SELECT 1');
PREPARE g2_v103_ev_stmt FROM @g2_v103_ev_sql;
EXECUTE g2_v103_ev_stmt;
DEALLOCATE PREPARE g2_v103_ev_stmt;

-- ---- 7) 同一SUBMITTED chainの並行first adoptionをDBで一意化（P1-5） ----
-- UNIQUE(tenant_id, submitted_review_event_id, action) により、
-- 同一chainのAPPROVED/REJECTED/REVOKEDは各1件のみ（REVOKEDはAPPROVEDのみtarget・§3.2）。
-- 生成列はMySQL 8.4のALTER再構築で既存FKが "Cannot add foreign key constraint" になるため使用しない。
SET @g2_v103_has_uk := NULL;
SELECT COUNT(*) INTO @g2_v103_has_uk FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_compliance_external_review_adoption_event'
    AND INDEX_NAME = 'uk_g2_adoption_first';
SET @g2_v103_uk_sql := IF(@g2_v103_has_uk = 0,
  'ALTER TABLE t_compliance_external_review_adoption_event
     ADD UNIQUE KEY uk_g2_adoption_first (tenant_id, submitted_review_event_id, action)',
  'SELECT 1');
PREPARE g2_v103_uk_stmt FROM @g2_v103_uk_sql;
EXECUTE g2_v103_uk_stmt;
DEALLOCATE PREPARE g2_v103_uk_stmt;
