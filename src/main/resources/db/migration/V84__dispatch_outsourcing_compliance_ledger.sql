-- ============================================================
-- V84: 派遣・準委任コンプライアンス台帳 (S10 / T061 F1 R5 rework)
-- V82は欠番。V83（attendance）の後にV84を適用する。
-- V1 fresh DBにも同じshapeを持たせ、V9 baseline/legacy DBへ順方向に追加する。
--
-- R5契約（field-mapping.md §4 / design.md §5.5・5.6）:
--  - snapshotは UNIQUE(contract_id, snapshot_version) のみ。snapshot_hashは内容hashの非一意索引。
--  - retryの冪等性は operation_id と expected current version で管理し、content hashをidempotency keyにしない。
--  - A(v1,hA)→B(v2,hB)→A(v3,hA) を3 versionとして保持する。
--  - worker current pointerは t_contract_compliance_worker_state がFK/CASで管理する。
--  - snapshot/worker_snapshot/history tableは DB triggerで UPDATE/DELETE を拒否する。
--  - history訂正は CREATED/CORRECTED/CANCELLED の新event INSERTで行う（旧行は不変）。
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

-- ============================================================
-- profile snapshot（append-only。typed columnを契約時点で固定する）
-- ============================================================
CREATE TABLE IF NOT EXISTS t_contract_compliance_snapshot (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'snapshot ID',
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                       BIGINT NOT NULL COMMENT '契約ID',
  snapshot_version                  INT NOT NULL COMMENT '契約単位のsnapshot version（1始まり）',
  snapshot_hash                     VARCHAR(64) NOT NULL COMMENT '内容hash（非一意。idempotency keyにしない）',
  operation_id                      VARCHAR(64) COMMENT '生成operation ID（冪等性はoperation tableで担保）',
  snapshot_at                       DATETIME COMMENT 'snapshot確定日時',
  contract_no                       VARCHAR(100) COMMENT '契約番号（CONTRACT_PARTY_PERIOD_SNAPSHOT）',
  contract_date                     DATE COMMENT '契約締結日',
  party_name                        VARCHAR(200) COMMENT '当事者名typed snapshot',
  party_address                     VARCHAR(500) COMMENT '当事者住所typed snapshot',
  party_representative              VARCHAR(200) COMMENT '当事者代表者typed snapshot',
  dispatch_from                     DATE COMMENT '派遣期間開始typed snapshot',
  dispatch_to                       DATE COMMENT '派遣期間終了typed snapshot',
  workplace_name                    VARCHAR(200) COMMENT '事業所名typed snapshot（WORKPLACE_ORG_SNAPSHOT）',
  workplace_address                 VARCHAR(500) COMMENT '事業所所在地typed snapshot',
  workplace_department              VARCHAR(200) COMMENT '就業場所部署typed snapshot',
  workplace_phone                   VARCHAR(50) COMMENT '事業所電話typed snapshot',
  organization_unit                 VARCHAR(200) COMMENT '組織単位名typed snapshot',
  organization_head_title           VARCHAR(100) COMMENT '組織の長の職名typed snapshot',
  work_description                  TEXT COMMENT '業務内容（WORK_DESCRIPTION_TYPED）',
  statutory_job_flag                TINYINT COMMENT '政令業務該当flag',
  statutory_job_reference           VARCHAR(200) COMMENT '政令業務該当根拠',
  responsibility_level              VARCHAR(50) COMMENT '責任の程度（RESPONSIBILITY_TYPED）',
  responsibility_detail             TEXT COMMENT '責任・権限の内容',
  command_person_department         VARCHAR(100) COMMENT '指揮命令者部署',
  command_person_title              VARCHAR(100) COMMENT '指揮命令者役職',
  command_person_name               VARCHAR(100) COMMENT '指揮命令者氏名',
  command_person_phone              VARCHAR(50) COMMENT '指揮命令者電話',
  client_responsible_department     VARCHAR(100) COMMENT '派遣先責任者部署',
  client_responsible_title          VARCHAR(100) COMMENT '派遣先責任者役職',
  client_responsible_name           VARCHAR(100) COMMENT '派遣先責任者氏名',
  client_responsible_phone          VARCHAR(50) COMMENT '派遣先責任者電話',
  dispatch_responsible_department   VARCHAR(100) COMMENT '派遣元責任者部署',
  dispatch_responsible_title        VARCHAR(100) COMMENT '派遣元責任者役職',
  dispatch_responsible_name         VARCHAR(100) COMMENT '派遣元責任者氏名',
  dispatch_responsible_phone        VARCHAR(50) COMMENT '派遣元責任者電話',
  work_start_minute                 INT COMMENT '始業（分整数、0=00:00）（WORK_TIME_TYPED）',
  work_end_minute                   INT COMMENT '終業（分整数）',
  work_span_next_day_flag           TINYINT COMMENT '日跨ぎflag',
  break_start_minute                INT COMMENT '休憩開始（分整数）（WORK_TIME_TYPED。複数休憩はt_compliance_break_detail）',
  break_end_minute                  INT COMMENT '休憩終了（分整数）',
  work_day_code                     VARCHAR(30) COMMENT '就業日calendar code（WORK_CALENDAR_HISTORY）',
  holiday_calendar_code             VARCHAR(30) COMMENT '休日calendar code',
  agreement_reference_id            BIGINT COMMENT '36協定reference（OVERTIME_AGREEMENT_SNAPSHOT）',
  overtime_daily_limit              INT COMMENT '時間外1日上限',
  overtime_monthly_limit            INT COMMENT '時間外1月上限',
  overtime_yearly_limit             INT COMMENT '時間外1年上限',
  overtime_period_from              DATE COMMENT '適用期間開始',
  overtime_period_to                DATE COMMENT '適用期間終了',
  workplace_limitation_date         DATE COMMENT '事業所単位の抵触日（LIMITATION_DUAL_TYPED。NULL=未算定）',
  organization_limitation_date      DATE COMMENT '組織単位（個人単位）の抵触日（NULL=未算定）',
  safety_responsibility_detail      TEXT COMMENT '安全衛生の責任分担（SAFETY_TYPED）',
  safety_rule_reference             VARCHAR(200) COMMENT '安全衛生適用規程',
  benefits_detail                   TEXT COMMENT '福利厚生の具体的内容（BENEFITS_TYPED）',
  benefits_provided_flag            TINYINT COMMENT '福利厚生提供有無',
  dispatch_headcount                INT COMMENT '派遣人員（HEADCOUNT_TYPED）',
  agreement_target_flag             TINYINT COMMENT '協定対象flag（AGREEMENT_FLAG_TYPED）',
  treatment_scheme                  VARCHAR(100) COMMENT '待遇方式',
  source_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣元）部署（COMPLAINT_HISTORY）',
  source_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣元）役職',
  source_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣元）氏名',
  source_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣元）電話',
  client_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣先）部署',
  client_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣先）役職',
  client_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣先）氏名',
  client_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣先）電話',
  employment_stability_preference   TEXT COMMENT '雇用安定措置の希望（EMPLOYMENT_STABILITY_HISTORY）',
  limitation_exemption_type         VARCHAR(50) COMMENT '期間制限例外type（LIMITATION_EXEMPTION_TYPED）',
  limitation_exemption_detail       TEXT COMMENT '期間制限例外内容',
  limitation_exemption_basis        VARCHAR(200) COMMENT '期間制限例外根拠',
  limitation_exemption_from         DATE COMMENT '例外期間開始',
  limitation_exemption_to           DATE COMMENT '例外期間終了',
  dispatch_fee_amount               DECIMAL(14,2) COMMENT '派遣料金（DISPATCH_FEE_TYPED）',
  dispatch_fee_basis                VARCHAR(20) COMMENT '月額/日額/時間額',
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY' COMMENT '通貨',
  social_insurance_procedure_incomplete_reason TEXT COMMENT '社会保険手続未完了理由（SRC-E⑱）（INSURANCE_TYPED）',
  health_insurance_status           VARCHAR(20) COMMENT '健康保険加入状態',
  health_insurance_missing_reason   VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date    DATE COMMENT '健康保険取得予定日',
  pension_insurance_status          VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason  VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date   DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status       VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date DATE COMMENT '雇用保険取得予定日',
  instruction_route                 TEXT COMMENT '作業指示経路',
  subcontract_allowed               TINYINT COMMENT '再委託可否（NULL=未確認）',
  acceptance_method                 VARCHAR(255) COMMENT '検収方法',
  retention_due_date                DATE COMMENT '保存満了予定日（RETENTION_METADATA）',
  legal_hold_flag                   TINYINT COMMENT 'legal hold（削除停止）',
  version                           INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_snapshot_version (contract_id, snapshot_version),
  INDEX idx_compliance_snapshot_hash (snapshot_hash),
  INDEX idx_compliance_snapshot_contract (contract_id),
  CONSTRAINT fk_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約compliance profile snapshot（append-only）';

-- ============================================================
-- contract compliance profile（mutable current row。current pointerを持つ）
-- ============================================================
CREATE TABLE IF NOT EXISTS t_contract_compliance_profile (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'profile ID',
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                       BIGINT NOT NULL COMMENT '契約ID',
  contract_type_detail              VARCHAR(50) COMMENT '派遣/準委任/請負の詳細',
  workplace_id                      BIGINT COMMENT '就業事業所ID',
  work_description                  TEXT COMMENT '業務内容',
  statutory_job_flag                TINYINT COMMENT '政令業務該当flag',
  statutory_job_reference           VARCHAR(200) COMMENT '政令業務該当根拠',
  responsibility_level              VARCHAR(50) COMMENT '責任の程度',
  responsibility_detail             TEXT COMMENT '責任・権限の内容',
  command_person_contact_id         BIGINT COMMENT '指揮命令者（顧客contact ID）',
  command_person_department         VARCHAR(100) COMMENT '指揮命令者部署',
  command_person_title              VARCHAR(100) COMMENT '指揮命令者役職',
  command_person_name               VARCHAR(100) COMMENT '指揮命令者氏名',
  command_person_phone              VARCHAR(50) COMMENT '指揮命令者電話',
  client_responsible_contact_id     BIGINT COMMENT '派遣先責任者（顧客contact ID）',
  client_responsible_department     VARCHAR(100) COMMENT '派遣先責任者部署',
  client_responsible_title          VARCHAR(100) COMMENT '派遣先責任者役職',
  client_responsible_name           VARCHAR(100) COMMENT '派遣先責任者氏名',
  client_responsible_phone          VARCHAR(50) COMMENT '派遣先責任者電話',
  dispatch_responsible_user_id      BIGINT COMMENT '派遣元責任者ユーザーID',
  dispatch_responsible_department   VARCHAR(100) COMMENT '派遣元責任者部署',
  dispatch_responsible_title        VARCHAR(100) COMMENT '派遣元責任者役職',
  dispatch_responsible_name         VARCHAR(100) COMMENT '派遣元責任者氏名',
  dispatch_responsible_phone        VARCHAR(50) COMMENT '派遣元責任者電話',
  work_start_minute                 INT COMMENT '始業（分整数、0=00:00）',
  work_end_minute                   INT COMMENT '終業（分整数）',
  work_span_next_day_flag           TINYINT COMMENT '日跨ぎflag',
  break_start_minute                INT COMMENT '休憩開始（分整数）（複数休憩はt_compliance_break_detail）',
  break_end_minute                  INT COMMENT '休憩終了（分整数）',
  work_day_code                     VARCHAR(30) COMMENT '就業日calendar code',
  holiday_calendar_code             VARCHAR(30) COMMENT '休日calendar code',
  agreement_reference_id            BIGINT COMMENT '36協定reference',
  overtime_daily_limit              INT COMMENT '時間外1日上限',
  overtime_monthly_limit            INT COMMENT '時間外1月上限',
  overtime_yearly_limit             INT COMMENT '時間外1年上限',
  overtime_period_from              DATE COMMENT '適用期間開始',
  overtime_period_to                DATE COMMENT '適用期間終了',
  workplace_limitation_date         DATE COMMENT '事業所単位の抵触日（NULL=未算定）',
  organization_limitation_date      DATE COMMENT '組織単位（個人単位）の抵触日（NULL=未算定）',
  safety_responsibility_detail      TEXT COMMENT '安全衛生の責任分担',
  safety_rule_reference             VARCHAR(200) COMMENT '安全衛生適用規程',
  benefits_detail                   TEXT COMMENT '福利厚生の具体的内容',
  benefits_provided_flag            TINYINT COMMENT '福利厚生提供有無',
  dispatch_headcount                INT COMMENT '派遣人員',
  agreement_target_flag             TINYINT COMMENT '協定対象flag',
  treatment_scheme                  VARCHAR(100) COMMENT '待遇方式',
  source_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣元）部署',
  source_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣元）役職',
  source_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣元）氏名',
  source_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣元）電話',
  client_complaint_contact_department VARCHAR(100) COMMENT '苦情申出先（派遣先）部署',
  client_complaint_contact_title    VARCHAR(100) COMMENT '苦情申出先（派遣先）役職',
  client_complaint_contact_name     VARCHAR(100) COMMENT '苦情申出先（派遣先）氏名',
  client_complaint_contact_phone    VARCHAR(50) COMMENT '苦情申出先（派遣先）電話',
  employment_stability_preference   TEXT COMMENT '雇用安定措置の希望',
  limitation_exemption_type         VARCHAR(50) COMMENT '期間制限例外type',
  limitation_exemption_detail       TEXT COMMENT '期間制限例外内容',
  limitation_exemption_basis        VARCHAR(200) COMMENT '期間制限例外根拠',
  limitation_exemption_from         DATE COMMENT '例外期間開始',
  limitation_exemption_to           DATE COMMENT '例外期間終了',
  dispatch_fee_amount               DECIMAL(14,2) COMMENT '派遣料金',
  dispatch_fee_basis                VARCHAR(20) COMMENT '月額/日額/時間額',
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY' COMMENT '通貨',
  social_insurance_procedure_incomplete_reason TEXT COMMENT '社会保険手続未完了理由（SRC-E⑱）',
  health_insurance_status           VARCHAR(20) COMMENT '健康保険加入状態',
  health_insurance_missing_reason   VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date    DATE COMMENT '健康保険取得予定日',
  pension_insurance_status          VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason  VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date   DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status       VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date DATE COMMENT '雇用保険取得予定日',
  instruction_route                 TEXT COMMENT '作業指示経路',
  subcontract_allowed               TINYINT COMMENT '再委託可否（NULL=未確認）',
  acceptance_method                 VARCHAR(255) COMMENT '検収方法',
  dispatch_period_start             DATE COMMENT '派遣期間開始',
  dispatch_period_end               DATE COMMENT '派遣期間終了',
  retention_due_date                DATE COMMENT '保存満了予定日',
  legal_hold_flag                   TINYINT COMMENT 'legal hold（削除停止）',
  current_snapshot_id               BIGINT COMMENT 'current snapshot pointer（FK）',
  current_snapshot_version          INT COMMENT 'current snapshot version（CAS対象）',
  version                           INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_contract_compliance_profile_contract (contract_id),
  INDEX idx_profile_workplace (workplace_id),
  INDEX idx_profile_limitation (workplace_limitation_date, organization_limitation_date),
  INDEX idx_profile_dispatch_period (dispatch_period_start, dispatch_period_end),
  CONSTRAINT chk_profile_period CHECK (dispatch_period_end IS NULL OR dispatch_period_start IS NULL OR dispatch_period_start <= dispatch_period_end),
  CONSTRAINT chk_profile_dispatch_count CHECK (dispatch_headcount IS NULL OR dispatch_headcount >= 0),
  CONSTRAINT chk_profile_fee CHECK (dispatch_fee_amount IS NULL OR dispatch_fee_amount >= 0),
  CONSTRAINT fk_profile_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_profile_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_profile_responsible_user FOREIGN KEY (dispatch_responsible_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_profile_current_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_snapshot(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約compliance profile（mutable current）';

-- ============================================================
-- worker-specific snapshot / current state
-- ============================================================
CREATE TABLE IF NOT EXISTS t_contract_compliance_worker_snapshot (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'worker snapshot ID',
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                   BIGINT NOT NULL COMMENT '契約ID',
  worker_id                     BIGINT NOT NULL COMMENT '派遣労動者（t_engineer ID）',
  snapshot_version              INT NOT NULL COMMENT 'worker単位のsnapshot version（1始まり）',
  snapshot_hash                 VARCHAR(64) NOT NULL COMMENT '内容hash（非一意）',
  operation_id                  VARCHAR(64) COMMENT '生成operation ID',
  snapshot_at                   DATETIME COMMENT 'snapshot確定日時',
  worker_name                   VARCHAR(100) COMMENT '派遣労動者氏名（WORKER_PII_SNAPSHOT）',
  employer_name                 VARCHAR(200) COMMENT '派遣元名',
  employer_address              VARCHAR(500) COMMENT '派遣元住所',
  employer_title                VARCHAR(100) COMMENT '使用者職氏名',
  gender                        VARCHAR(20) COMMENT '性別',
  age_band                      VARCHAR(30) COMMENT '年齢区分（45歳以上/18歳未満/その他）',
  age_at_reference_date         DATE COMMENT '年齢判定基準日',
  employment_term_type          VARCHAR(20) COMMENT '無期/有期（WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT）',
  employment_from               DATE COMMENT '雇用期間開始',
  employment_to                 DATE COMMENT '雇用期間終了',
  indefinite_worker_flag        TINYINT COMMENT '無期雇用flag',
  age_over_60_flag              TINYINT COMMENT '60歳以上flag',
  worker_restriction_type       VARCHAR(30) COMMENT '無期/60歳以上/限定しないの区分',
  health_insurance_status       VARCHAR(20) COMMENT '健康保険加入状態（INSURANCE_TYPED）',
  health_insurance_missing_reason VARCHAR(500) COMMENT '健康保険未加入理由',
  health_insurance_expected_date DATE COMMENT '健康保険取得予定日',
  pension_insurance_status      VARCHAR(20) COMMENT '厚生年金加入状態',
  pension_insurance_missing_reason VARCHAR(500) COMMENT '厚生年金未加入理由',
  pension_insurance_expected_date DATE COMMENT '厚生年金取得予定日',
  employment_insurance_status   VARCHAR(20) COMMENT '雇用保険加入状態',
  employment_insurance_missing_reason VARCHAR(500) COMMENT '雇用保険未加入理由',
  employment_insurance_expected_date DATE COMMENT '雇用保険取得予定日',
  version                       INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_worker_snapshot_version (contract_id, worker_id, snapshot_version),
  INDEX idx_worker_snapshot_hash (snapshot_hash),
  INDEX idx_worker_snapshot_worker (worker_id),
  CONSTRAINT fk_worker_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_worker_snapshot_engineer FOREIGN KEY (worker_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='worker-specific snapshot（append-only）';

CREATE TABLE IF NOT EXISTS t_contract_compliance_worker_state (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'worker current state ID',
  tenant_id                   VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  contract_id                 BIGINT NOT NULL COMMENT '契約ID',
  worker_id                   BIGINT NOT NULL COMMENT '派遣労動者（t_engineer ID）',
  current_snapshot_id         BIGINT COMMENT 'current worker snapshot pointer（FK）',
  current_snapshot_version    INT COMMENT 'current snapshot version（CAS対象）',
  version                     INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版（CAS）',
  created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_worker_state_contract_worker (contract_id, worker_id),
  CONSTRAINT fk_worker_state_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_worker_state_engineer FOREIGN KEY (worker_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_worker_state_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='worker current pointer（CAS付き）';

-- ============================================================
-- snapshot operation（冪等性管理。content hashとは分離）
-- ============================================================
CREATE TABLE IF NOT EXISTS t_compliance_snapshot_operation (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'operation ID',
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナント境界',
  operation_id                  VARCHAR(64) NOT NULL COMMENT 'クライアント発行のoperation ID（冪等キー）',
  scope_type                    VARCHAR(20) NOT NULL COMMENT 'CONTRACT / WORKER',
  contract_id                   BIGINT NOT NULL COMMENT '契約ID',
  worker_id                     BIGINT COMMENT 'worker scope時のみ',
  expected_version              INT COMMENT 'expected current version（CAS）',
  resulting_snapshot_id         BIGINT COMMENT '成功時resulting contract snapshot ID',
  resulting_worker_snapshot_id  BIGINT COMMENT '成功時resulting worker snapshot ID',
  request_hash                  VARCHAR(64) COMMENT 'request内容hash（照合用。idempotency keyにしない）',
  status                        VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED' COMMENT 'SUCCEEDED / FAILED',
  version                       INT NOT NULL DEFAULT 0 COMMENT '楽観ロック版',
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_snapshot_operation (operation_id),
  INDEX idx_snapshot_operation_contract (contract_id, scope_type),
  CONSTRAINT fk_snapshot_operation_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='snapshot保存operation（retry idempotency）';

-- ============================================================
-- append-only history（訂正・取消は新event INSERT。旧行は不変）
-- 共通protocol列: event_id / event_type / supersedes_event_id / correction_reason /
--                actor_user_id / occurred_at / effective_from / effective_to
-- ============================================================
CREATE TABLE IF NOT EXISTS t_compliance_work_calendar (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT COMMENT 'worker-specific calendar時のみ',
  event_id              VARCHAR(64) NOT NULL COMMENT 'event ID（CREATED/CORRECTED/CANCELLEDで新規採番）',
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64) COMMENT '訂正・取消の対象event',
  correction_reason     VARCHAR(500) COMMENT '訂正理由（CORRECTED/CANCELLED時必須）',
  actor_user_id         BIGINT COMMENT '実actor（runtime承認はM/本番gate）',
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'event発生時刻',
  effective_from        DATE COMMENT '適用開始',
  effective_to          DATE COMMENT '適用終了',
  work_day_code         VARCHAR(30) COMMENT '就業日calendar code',
  holiday_calendar_code VARCHAR(30) COMMENT '休日calendar code',
  excluded_date         DATE COMMENT '休暇除外日',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_work_calendar_event (event_id),
  INDEX idx_work_calendar_contract (contract_id, effective_from),
  CONSTRAINT fk_work_calendar_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='就業日/休日/休暇除外のappend-only calendar history';

CREATE TABLE IF NOT EXISTS t_compliance_break_detail (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT COMMENT 'worker-specific break時のみ',
  event_id              VARCHAR(64) NOT NULL COMMENT 'event ID（CREATED/CORRECTED/CANCELLEDで新規採番）',
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64) COMMENT '訂正・取消の対象event',
  correction_reason     VARCHAR(500) COMMENT '訂正理由（CORRECTED/CANCELLED時必須）',
  actor_user_id         BIGINT COMMENT '実actor（runtime承認はM/本番gate）',
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'event発生時刻',
  effective_from        DATE COMMENT '適用開始',
  effective_to          DATE COMMENT '適用終了',
  break_no              INT NOT NULL COMMENT '休憩順序（1始まり）',
  start_offset_minute   INT NOT NULL COMMENT '勤務開始からの休憩開始offset（分）',
  end_offset_minute     INT NOT NULL COMMENT '勤務開始からの休憩終了offset（分）',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_break_detail_event (event_id),
  INDEX idx_break_detail_contract (contract_id, effective_from),
  CONSTRAINT chk_break_detail_offset CHECK (start_offset_minute >= 0 AND end_offset_minute > start_offset_minute),
  CONSTRAINT fk_break_detail_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='就業時間の複数休憩（反復detail、append-only）';

CREATE TABLE IF NOT EXISTS t_compliance_complaint_history (
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
  complaint_type        VARCHAR(20) COMMENT 'SOURCE / CLIENT',
  received_at           DATE COMMENT '申出日',
  content               TEXT COMMENT '申出内容',
  action                TEXT COMMENT '処理内容',
  resolution            TEXT COMMENT '結果',
  notified_at           DATE COMMENT '本人通知日',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_compliance_complaint_event (event_id),
  INDEX idx_complaint_contract (contract_id, received_at),
  CONSTRAINT fk_complaint_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='苦情受付・処理・通知のappend-only history';

CREATE TABLE IF NOT EXISTS t_employment_stability_history (
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
  request_at            DATE COMMENT '依頼日時',
  request_method        VARCHAR(100) COMMENT '依頼方法',
  response_at           DATE COMMENT '回答日時',
  response_content      TEXT COMMENT '回答内容',
  action                TEXT COMMENT '実施内容',
  outcome               VARCHAR(100) COMMENT '結果',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_employment_stability_event (event_id),
  INDEX idx_employment_stability_contract (contract_id, request_at),
  CONSTRAINT fk_employment_stability_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='雇用安定措置のappend-only history';

CREATE TABLE IF NOT EXISTS t_training_history (
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
  training_date         DATE COMMENT '実施日',
  minutes               INT COMMENT '実施時間（分）',
  content               TEXT COMMENT '研修内容',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_training_event (event_id),
  INDEX idx_training_contract (contract_id, training_date),
  CONSTRAINT fk_training_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教育訓練のappend-only history';

CREATE TABLE IF NOT EXISTS t_career_consulting_history (
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
  consulting_date       DATE COMMENT '実施日',
  content               TEXT COMMENT '内容（PII）',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_career_consulting_event (event_id),
  INDEX idx_career_consulting_contract (contract_id, consulting_date),
  CONSTRAINT fk_career_consulting_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='キャリア・コンサルティングのappend-only history';

CREATE TABLE IF NOT EXISTS t_planned_introduction_terms (
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
  effective_from        DATE COMMENT '予定条件の適用開始',
  effective_to          DATE COMMENT '予定条件の適用終了',
  contract_period_from  DATE COMMENT '契約期間開始（PLANNED_INTRODUCTION_TERMS）',
  contract_period_to    DATE COMMENT '契約期間終了',
  renewal_rule          VARCHAR(100) COMMENT '更新の有無・上限',
  renewal_limit         INT COMMENT '更新上限回数',
  work_change_scope     VARCHAR(500) COMMENT '業務/場所の変更範囲',
  trial_period          VARCHAR(200) COMMENT '試用期間',
  wage_detail           VARCHAR(500) COMMENT '賃金',
  insurance_detail      VARCHAR(500) COMMENT '保険',
  smoking_measure       VARCHAR(500) COMMENT '喫煙措置',
  employer_name         VARCHAR(200) COMMENT '雇用主',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_planned_introduction_terms_event (event_id),
  INDEX idx_planned_terms_contract (contract_id, effective_from),
  CONSTRAINT fk_planned_terms_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='紹介予定派遣の予定労動条件（current-condition sub-field）';

CREATE TABLE IF NOT EXISTS t_planned_introduction_history (
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
  introduction_date     DATE COMMENT '紹介時期',
  outcome               VARCHAR(30) COMMENT 'OFFERED / ACCEPTED / REJECTED / OTHER',
  reason                TEXT COMMENT '非採用理由等',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_planned_introduction_event (event_id),
  INDEX idx_planned_introduction_contract (contract_id, introduction_date),
  CONSTRAINT fk_planned_introduction_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='紹介予定派遣の紹介・採否・非採用理由（append-only）';

CREATE TABLE IF NOT EXISTS t_direct_hire_dispute_history (
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
  measure               VARCHAR(500) COMMENT '紛争防止措置（DIRECT_HIRE_DISPUTE_HISTORY）',
  fee_detail            VARCHAR(500) COMMENT '手数料',
  request_method        VARCHAR(200) COMMENT '申出方法',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_direct_hire_dispute_event (event_id),
  INDEX idx_direct_hire_dispute_contract (contract_id, effective_from),
  CONSTRAINT fk_direct_hire_dispute_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='直接雇用時の紛争防止措置（条件付きappend-only）';

CREATE TABLE IF NOT EXISTS t_notification_difference_history (
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
  difference_type       VARCHAR(50) COMMENT '差異種別（派遣期間/就業日/時間・休憩/責任者/時間外/その他）',
  contract_snapshot_id  BIGINT COMMENT '契約側snapshot ID',
  notice_snapshot_id    BIGINT COMMENT '明示側snapshot ID',
  difference_detail     TEXT COMMENT '差異の内容',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_notification_difference_event (event_id),
  INDEX idx_notification_difference_contract (contract_id, occurred_at),
  CONSTRAINT fk_notification_difference_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='契約内容と明示内容の差異（append-only）';

CREATE TABLE IF NOT EXISTS t_ledger_work_snapshot (
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
  work_month            DATE COMMENT '締め月（月初日）（LEDGER_WORK_HISTORY）',
  work_days             INT COMMENT '出勤日数',
  work_hours            INT COMMENT '就業時間（分）',
  overtime_hours        INT COMMENT '時間外（分）',
  absence_days          INT COMMENT '欠勤日数',
  gross_amount          DECIMAL(14,2) COMMENT '客先工数金額',
  closed_at             DATETIME COMMENT '締め確定日時',
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_ledger_work_event (event_id),
  INDEX idx_ledger_work_contract (contract_id, work_month),
  CONSTRAINT fk_ledger_work_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次就業状況・タイムシート（締め時点snapshotの反復行）';

-- ============================================================
-- finding / delivery
-- ============================================================
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
  document_type            VARCHAR(50) COMMENT '帳票種別（DOCUMENT_DELIVERY）',
  template_version         VARCHAR(50) COMMENT '帳票template version',
  effective_from           DATE COMMENT 'template適用開始',
  effective_to             DATE COMMENT 'template適用終了',
  snapshot_hash            VARCHAR(64) COMMENT '生成元snapshot hash',
  recipient_contact_id     BIGINT COMMENT '受領者contact ID',
  recipient_name_snapshot  VARCHAR(200) COMMENT '受領者名snapshot',
  recipient_email_snapshot VARCHAR(255) COMMENT '受領者メールsnapshot',
  delivery_method          VARCHAR(30) NOT NULL COMMENT 'EMAIL/PORTAL/PAPER/OTHER',
  delivery_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELIVERED/FAILED',
  delivered_at             DATETIME COMMENT '交付日時',
  confirmed_at             DATETIME COMMENT '受領確認日時（NULL=受領未確認。未交付ではない）',
  confirmation_note        VARCHAR(1000) COMMENT '受領確認メモ',
  idempotency_key          VARCHAR(200) COMMENT '交付冪等キー（生成キーは(contract_id, document_type, template_version, snapshot_hash)の業務一意）',
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

-- ============================================================
-- 既存環境（旧V84適用済み等）でt_document_deliveryが旧shapeのままの場合、
-- R5 shapeの追加列とFKを順方向に収束させる（partial schema path）。
-- ============================================================
SET @delivery_template_col_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_document_delivery'
        AND column_name = 'template_version') = 0,
    'ALTER TABLE t_document_delivery ADD COLUMN template_version VARCHAR(50) COMMENT ''帳票template version''',
    'SELECT 1'
);
PREPARE delivery_template_col_stmt FROM @delivery_template_col_sql;
EXECUTE delivery_template_col_stmt;
DEALLOCATE PREPARE delivery_template_col_stmt;

SET @delivery_effective_col_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_document_delivery'
        AND column_name = 'effective_from') = 0,
    'ALTER TABLE t_document_delivery ADD COLUMN effective_from DATE COMMENT ''template適用開始''',
    'SELECT 1'
);
PREPARE delivery_effective_col_stmt FROM @delivery_effective_col_sql;
EXECUTE delivery_effective_col_stmt;
DEALLOCATE PREPARE delivery_effective_col_stmt;

SET @delivery_effective_to_col_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_document_delivery'
        AND column_name = 'effective_to') = 0,
    'ALTER TABLE t_document_delivery ADD COLUMN effective_to DATE COMMENT ''template適用終了''',
    'SELECT 1'
);
PREPARE delivery_effective_to_col_stmt FROM @delivery_effective_to_col_sql;
EXECUTE delivery_effective_to_col_stmt;
DEALLOCATE PREPARE delivery_effective_to_col_stmt;

SET @delivery_snapshot_hash_col_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_document_delivery'
        AND column_name = 'snapshot_hash') = 0,
    'ALTER TABLE t_document_delivery ADD COLUMN snapshot_hash VARCHAR(64) COMMENT ''生成元snapshot hash''',
    'SELECT 1'
);
PREPARE delivery_snapshot_hash_col_stmt FROM @delivery_snapshot_hash_col_sql;
EXECUTE delivery_snapshot_hash_col_stmt;
DEALLOCATE PREPARE delivery_snapshot_hash_col_stmt;

SET @delivery_document_type_col_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_document_delivery'
        AND column_name = 'document_type') = 0,
    'ALTER TABLE t_document_delivery ADD COLUMN document_type VARCHAR(50) COMMENT ''帳票種別''',
    'SELECT 1'
);
PREPARE delivery_document_type_col_stmt FROM @delivery_document_type_col_sql;
EXECUTE delivery_document_type_col_stmt;
DEALLOCATE PREPARE delivery_document_type_col_stmt;

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

-- partial schema path: 旧shapeのt_document_delivery（旧V84適用済み等）にfk_delivery_documentが無い場合に追加する。
SET @delivery_document_fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name = 't_document_delivery'
        AND constraint_name = 'fk_delivery_document') = 0,
    'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_document FOREIGN KEY (document_id) REFERENCES t_document(id) ON UPDATE CASCADE ON DELETE RESTRICT',
    'SELECT 1'
);
PREPARE delivery_document_fk_stmt FROM @delivery_document_fk_sql;
EXECUTE delivery_document_fk_stmt;
DEALLOCATE PREPARE delivery_document_fk_stmt;

-- ============================================================
-- snapshot / worker snapshot / history tableは append-only。
-- DB triggerで直接SQLのUPDATE/DELETEを拒否する（application mapperはINSERT/SELECTのみ）。
-- 承認済みretention purgeは権限分離procedure＋監査eventの明示経路のみ（T066/B1 gate）。
-- MySQLはCREATE TRIGGER IF NOT EXISTSを持たないため、DROP IF EXISTS＋CREATEの冪等パターンとする。
-- ============================================================
DROP TRIGGER IF EXISTS trg_t_contract_compliance_snapshot_no_update;
CREATE TRIGGER trg_t_contract_compliance_snapshot_no_update BEFORE UPDATE ON t_contract_compliance_snapshot
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_contract_compliance_snapshot is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_contract_compliance_snapshot_no_delete;
CREATE TRIGGER trg_t_contract_compliance_snapshot_no_delete BEFORE DELETE ON t_contract_compliance_snapshot
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_contract_compliance_snapshot is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_contract_compliance_worker_snapshot_no_update;
CREATE TRIGGER trg_t_contract_compliance_worker_snapshot_no_update BEFORE UPDATE ON t_contract_compliance_worker_snapshot
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_contract_compliance_worker_snapshot is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_contract_compliance_worker_snapshot_no_delete;
CREATE TRIGGER trg_t_contract_compliance_worker_snapshot_no_delete BEFORE DELETE ON t_contract_compliance_worker_snapshot
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_contract_compliance_worker_snapshot is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_compliance_work_calendar_no_update;
CREATE TRIGGER trg_t_compliance_work_calendar_no_update BEFORE UPDATE ON t_compliance_work_calendar
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_compliance_work_calendar is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_compliance_work_calendar_no_delete;
CREATE TRIGGER trg_t_compliance_work_calendar_no_delete BEFORE DELETE ON t_compliance_work_calendar
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_compliance_work_calendar is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_compliance_break_detail_no_update;
CREATE TRIGGER trg_t_compliance_break_detail_no_update BEFORE UPDATE ON t_compliance_break_detail
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_compliance_break_detail is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_compliance_break_detail_no_delete;
CREATE TRIGGER trg_t_compliance_break_detail_no_delete BEFORE DELETE ON t_compliance_break_detail
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_compliance_break_detail is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_compliance_complaint_history_no_update;
CREATE TRIGGER trg_t_compliance_complaint_history_no_update BEFORE UPDATE ON t_compliance_complaint_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_compliance_complaint_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_compliance_complaint_history_no_delete;
CREATE TRIGGER trg_t_compliance_complaint_history_no_delete BEFORE DELETE ON t_compliance_complaint_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_compliance_complaint_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_employment_stability_history_no_update;
CREATE TRIGGER trg_t_employment_stability_history_no_update BEFORE UPDATE ON t_employment_stability_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_employment_stability_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_employment_stability_history_no_delete;
CREATE TRIGGER trg_t_employment_stability_history_no_delete BEFORE DELETE ON t_employment_stability_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_employment_stability_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_training_history_no_update;
CREATE TRIGGER trg_t_training_history_no_update BEFORE UPDATE ON t_training_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_training_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_training_history_no_delete;
CREATE TRIGGER trg_t_training_history_no_delete BEFORE DELETE ON t_training_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_training_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_career_consulting_history_no_update;
CREATE TRIGGER trg_t_career_consulting_history_no_update BEFORE UPDATE ON t_career_consulting_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_career_consulting_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_career_consulting_history_no_delete;
CREATE TRIGGER trg_t_career_consulting_history_no_delete BEFORE DELETE ON t_career_consulting_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_career_consulting_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_planned_introduction_terms_no_update;
CREATE TRIGGER trg_t_planned_introduction_terms_no_update BEFORE UPDATE ON t_planned_introduction_terms
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_planned_introduction_terms is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_planned_introduction_terms_no_delete;
CREATE TRIGGER trg_t_planned_introduction_terms_no_delete BEFORE DELETE ON t_planned_introduction_terms
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_planned_introduction_terms is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_planned_introduction_history_no_update;
CREATE TRIGGER trg_t_planned_introduction_history_no_update BEFORE UPDATE ON t_planned_introduction_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_planned_introduction_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_planned_introduction_history_no_delete;
CREATE TRIGGER trg_t_planned_introduction_history_no_delete BEFORE DELETE ON t_planned_introduction_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_planned_introduction_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_direct_hire_dispute_history_no_update;
CREATE TRIGGER trg_t_direct_hire_dispute_history_no_update BEFORE UPDATE ON t_direct_hire_dispute_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_direct_hire_dispute_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_direct_hire_dispute_history_no_delete;
CREATE TRIGGER trg_t_direct_hire_dispute_history_no_delete BEFORE DELETE ON t_direct_hire_dispute_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_direct_hire_dispute_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_notification_difference_history_no_update;
CREATE TRIGGER trg_t_notification_difference_history_no_update BEFORE UPDATE ON t_notification_difference_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_notification_difference_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_notification_difference_history_no_delete;
CREATE TRIGGER trg_t_notification_difference_history_no_delete BEFORE DELETE ON t_notification_difference_history
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_notification_difference_history is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_ledger_work_snapshot_no_update;
CREATE TRIGGER trg_t_ledger_work_snapshot_no_update BEFORE UPDATE ON t_ledger_work_snapshot
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_ledger_work_snapshot is immutable; use a new event row';
DROP TRIGGER IF EXISTS trg_t_ledger_work_snapshot_no_delete;
CREATE TRIGGER trg_t_ledger_work_snapshot_no_delete BEFORE DELETE ON t_ledger_work_snapshot
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_ledger_work_snapshot is immutable; use a new event row';
