-- ============================================================
-- V84: 派遣・準委任コンプライアンス台帳 (S10 / T061 F1)
-- V82は欠番。V83（attendance）の後にV84を適用する。
-- V1 fresh DBにも同じshapeを持たせ、V9 baseline/legacy DBへ順方向に追加する。
-- ============================================================

CREATE TABLE IF NOT EXISTS m_workplace (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '就業事業所ID',
  tenant_id         VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  customer_id       BIGINT NOT NULL COMMENT '顧客ID',
  organization_id   BIGINT COMMENT '組織scope',
  name              VARCHAR(200) NOT NULL COMMENT '事業所名',
  address           VARCHAR(500) COMMENT '所在地',
  organization_unit VARCHAR(200) COMMENT '組織単位名',
  phone             VARCHAR(50) COMMENT '連絡先',
  valid_from        DATE NOT NULL DEFAULT '1970-01-01' COMMENT '有効開始日',
  valid_to          DATE COMMENT '有効終了日（NULL=無期限）',
  status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状態',
  version           INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_workplace_period (customer_id, name, valid_from),
  INDEX idx_workplace_scope (tenant_id, customer_id, organization_id),
  INDEX idx_workplace_period (valid_from, valid_to),
  CONSTRAINT chk_workplace_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT fk_workplace_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_workplace_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派遣就業事業所マスタ';

CREATE TABLE IF NOT EXISTS t_contract_compliance_profile (
  id                              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'profile ID',
  tenant_id                       VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                     BIGINT NOT NULL COMMENT '契約ID',
  contract_type_detail            VARCHAR(50) COMMENT '派遣/準委任/請負の詳細',
  workplace_id                    BIGINT COMMENT '就業事業所ID',
  work_description                TEXT COMMENT '業務内容',
  work_location                   VARCHAR(500) COMMENT '就業場所',
  work_time                       VARCHAR(500) COMMENT '就業時間',
  break_time                      VARCHAR(500) COMMENT '休憩',
  holiday_rule                    VARCHAR(500) COMMENT '休日',
  overtime_rule                   VARCHAR(500) COMMENT '時間外',
  command_person_contact_id       BIGINT COMMENT '指揮命令者（顧客contact ID）',
  command_person_name_snapshot    VARCHAR(100) COMMENT '指揮命令者名snapshot',
  command_person_title_snapshot   VARCHAR(100) COMMENT '指揮命令者役職snapshot',
  client_responsible_contact_id   BIGINT COMMENT '派遣先責任者（顧客contact ID）',
  client_responsible_person       VARCHAR(200) COMMENT '派遣先責任者名/役職',
  client_responsible_phone        VARCHAR(50) COMMENT '派遣先責任者連絡先',
  dispatch_responsible_user_id    BIGINT COMMENT '派遣元責任者ユーザーID',
  dispatch_responsible_name_snapshot VARCHAR(100) COMMENT '派遣元責任者名snapshot',
  dispatch_responsible_title_snapshot VARCHAR(100) COMMENT '派遣元責任者役職snapshot',
  dispatch_responsible_phone_snapshot VARCHAR(50) COMMENT '派遣元責任者連絡先snapshot',
  dispatch_period_start           DATE COMMENT '派遣期間開始',
  dispatch_period_end             DATE COMMENT '派遣期間終了',
  limitation_date                 DATE COMMENT '抵触日（NULL=未算定）',
  workplace_limitation_date       DATE COMMENT '派遣先事業所単位の抵触日',
  worker_limitation_date          DATE COMMENT '派遣労働者個人単位の抵触日',
  treatment_scheme                VARCHAR(100) COMMENT '待遇方式',
  complaint_contact               VARCHAR(1000) COMMENT '苦情申出先',
  complaint_processing_history    TEXT COMMENT '苦情処理経過',
  training_info                   TEXT COMMENT '教育訓練',
  safety_health_info              TEXT COMMENT '安全衛生',
  insurance_notification          VARCHAR(1000) COMMENT '社会保険通知',
  welfare_info                    TEXT COMMENT '福利厚生',
  instruction_route               TEXT COMMENT '作業指示経路',
  responsibility_degree           VARCHAR(255) COMMENT '責任の程度',
  subcontract_allowed             TINYINT COMMENT '再委託可否（NULL=未確認）',
  acceptance_method               VARCHAR(255) COMMENT '検収方法',
  dispatch_worker_count           INT COMMENT '派遣人数',
  agreement_target_flag           TINYINT COMMENT '協定対象（NULL=未確認）',
  indefinite_term_flag            TINYINT COMMENT '無期雇用（NULL=未確認）',
  age_over_60_flag                TINYINT COMMENT '60歳以上（NULL=未確認）',
  employment_stability_measure    TEXT COMMENT '雇用安定措置',
  health_insurance_status         VARCHAR(20) COMMENT '健康保険加入状態',
  health_insurance_missing_reason VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date  DATE COMMENT '健康保険取得予定日',
  pension_insurance_status        VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status         VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date  DATE COMMENT '雇用保険取得予定日',
  snapshot_json                   JSON COMMENT '帳票生成時の不変snapshot',
  workplace_snapshot_json         JSON COMMENT '事業所/組織snapshot',
  worker_snapshot_json            JSON COMMENT '派遣労働者固有snapshot',
  snapshot_at                     DATETIME COMMENT 'snapshot確定日時',
  version                         INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                    TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_contract_compliance_profile_contract (contract_id),
  INDEX idx_profile_workplace (workplace_id),
  INDEX idx_profile_limitation (limitation_date),
  INDEX idx_profile_dispatch_period (dispatch_period_start, dispatch_period_end),
  CONSTRAINT chk_profile_period CHECK (dispatch_period_end IS NULL OR dispatch_period_start IS NULL OR dispatch_period_start <= dispatch_period_end),
  CONSTRAINT chk_profile_dispatch_count CHECK (dispatch_worker_count IS NULL OR dispatch_worker_count >= 0),
  CONSTRAINT fk_profile_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_profile_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_profile_responsible_user FOREIGN KEY (dispatch_responsible_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約compliance profile';

CREATE TABLE IF NOT EXISTS t_compliance_finding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'finding ID',
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id           BIGINT NOT NULL COMMENT '契約ID',
  code                  VARCHAR(80) NOT NULL COMMENT 'finding code',
  severity              VARCHAR(20) NOT NULL DEFAULT 'WARNING' COMMENT 'INFO/WARNING/ERROR',
  status                VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKNOWLEDGED/IN_PROGRESS/RESOLVED/EXCEPTION_APPROVED',
  condition_fingerprint VARCHAR(128) NOT NULL COMMENT '条件fingerprint',
  detected_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '検出日時',
  due_date              DATE COMMENT '対応期限',
  acknowledged_by       BIGINT COMMENT '確認者',
  acknowledged_at       DATETIME COMMENT '確認日時',
  resolution_note       VARCHAR(2000) COMMENT '解消/例外根拠',
  evidence_document_id  BIGINT COMMENT '根拠文書ID',
  version               INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_finding (contract_id, code, condition_fingerprint),
  INDEX idx_finding_status_due (status, due_date),
  INDEX idx_finding_contract (contract_id, detected_at),
  CONSTRAINT fk_finding_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_finding_acknowledged_by FOREIGN KEY (acknowledged_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_finding_evidence_document FOREIGN KEY (evidence_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='compliance finding';

CREATE TABLE IF NOT EXISTS t_document_delivery (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '交付履歴ID',
  tenant_id                VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id              BIGINT COMMENT '契約ID',
  document_id              BIGINT NOT NULL COMMENT '文書ID',
  recipient_contact_id     BIGINT COMMENT '受領者contact ID',
  recipient_name_snapshot  VARCHAR(200) COMMENT '受領者名snapshot',
  recipient_email_snapshot VARCHAR(255) COMMENT '受領者メールsnapshot',
  delivery_method          VARCHAR(30) NOT NULL COMMENT 'EMAIL/PORTAL/PAPER/OTHER',
  delivery_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELIVERED/FAILED',
  delivered_at             DATETIME COMMENT '交付日時',
  confirmed_at             DATETIME COMMENT '受領確認日時（NULL=未確認）',
  confirmation_note        VARCHAR(1000) COMMENT '受領確認メモ',
  idempotency_key          VARCHAR(200) COMMENT '交付冪等キー',
  version                  INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag             TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_document_delivery_idempotency (tenant_id, idempotency_key),
  INDEX idx_delivery_document (document_id, delivered_at),
  INDEX idx_delivery_contract (contract_id, delivered_at),
  INDEX idx_delivery_confirmation (confirmed_at),
  CONSTRAINT fk_delivery_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_delivery_document FOREIGN KEY (document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='法定帳票交付履歴';

-- V73で顧客contactが存在する環境では、責任者/受領者IDもFKで閉じる。
-- V1 fresh schemaではcontact表が後続V73で作られるため、V1内では参照を張らず、V84で順方向に追加する。
SET @profile_command_contact_fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 't_customer_contact') = 1
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name = 't_contract_compliance_profile'
        AND constraint_name = 'fk_profile_command_contact') = 0,
    'ALTER TABLE t_contract_compliance_profile ADD CONSTRAINT fk_profile_command_contact FOREIGN KEY (command_person_contact_id) REFERENCES t_customer_contact(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE profile_command_contact_fk_stmt FROM @profile_command_contact_fk_sql;
EXECUTE profile_command_contact_fk_stmt;
DEALLOCATE PREPARE profile_command_contact_fk_stmt;

SET @profile_client_contact_fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 't_customer_contact') = 1
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name = 't_contract_compliance_profile'
        AND constraint_name = 'fk_profile_client_contact') = 0,
    'ALTER TABLE t_contract_compliance_profile ADD CONSTRAINT fk_profile_client_contact FOREIGN KEY (client_responsible_contact_id) REFERENCES t_customer_contact(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE profile_client_contact_fk_stmt FROM @profile_client_contact_fk_sql;
EXECUTE profile_client_contact_fk_stmt;
DEALLOCATE PREPARE profile_client_contact_fk_stmt;

SET @delivery_contact_fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 't_customer_contact') = 1
    AND (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name = 't_document_delivery'
        AND constraint_name = 'fk_delivery_recipient_contact') = 0,
    'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_recipient_contact FOREIGN KEY (recipient_contact_id) REFERENCES t_customer_contact(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE delivery_contact_fk_stmt FROM @delivery_contact_fk_sql;
EXECUTE delivery_contact_fk_stmt;
DEALLOCATE PREPARE delivery_contact_fk_stmt;
