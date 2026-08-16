-- ============================================================
-- V103: 要員配置・需給計画 (S12 / T075 F1)
-- V102_3適用済みlegacy DBへ、V1統合baselineと同じ最終shapeを順方向に追加する。
-- 予約番号はV103（S10/S11双方PASS後）。V59/V72/V82/V99は永久欠番であり、埋めない。
-- 本migrationは過去のmigrationを変更しない（V1/V102_3等は不変）。
-- ============================================================

-- ---- 1) 案件ポジション ----
CREATE TABLE IF NOT EXISTS t_project_position (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  project_id         BIGINT       NOT NULL                   COMMENT '案件ID',
  position_no        VARCHAR(50)  NOT NULL                   COMMENT 'ポジション番号（案件内一意）',
  role_name          VARCHAR(200) NOT NULL                   COMMENT '役割名',
  required_count     INT          NOT NULL DEFAULT 1         COMMENT '募集人数',
  skills_json        TEXT                                    COMMENT '必須/歓迎skillのJSON配列',
  unit_price_min     DECIMAL(10,0)                           COMMENT '単価帯下限(円/月)',
  unit_price_max     DECIMAL(10,0)                           COMMENT '単価帯上限(円/月)',
  start_date         DATE                                    COMMENT '開始日（inclusive）',
  end_date           DATE                                    COMMENT '終了日（inclusive・NULL=open end: 計画window末まで）',
  location           VARCHAR(255)                            COMMENT '勤務地',
  allocation_percent DECIMAL(5,2)  NOT NULL DEFAULT 100      COMMENT '想定稼働率(%)',
  priority           VARCHAR(20)                             COMMENT '優先度',
  status             VARCHAR(20)  NOT NULL DEFAULT '募集中'  COMMENT '募集中/候補選定/充足/保留/取消',
  version            INT          NOT NULL DEFAULT 0         COMMENT '楽観ロック',
  created_by         BIGINT                                  COMMENT '作成者ID',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag       TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  UNIQUE KEY uk_project_position_no (project_id, position_no),
  INDEX idx_project_position_status (status),
  INDEX idx_project_position_period (start_date, end_date),
  CONSTRAINT chk_project_position_count CHECK (required_count >= 1),
  CONSTRAINT chk_project_position_percent CHECK (allocation_percent > 0 AND allocation_percent <= 100),
  CONSTRAINT chk_project_position_price CHECK (unit_price_min IS NULL OR unit_price_max IS NULL OR unit_price_min <= unit_price_max),
  CONSTRAINT chk_project_position_period CHECK (end_date IS NULL OR start_date IS NULL OR start_date <= end_date),
  CONSTRAINT chk_project_position_status CHECK (status IN ('募集中','候補選定','充足','保留','取消')),
  CONSTRAINT fk_project_position_project FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='案件ポジション（募集枠）';

-- ---- 2) proposal/contractへposition_id（V1統合baselineの最終shapeをlegacy DBへ追加） ----
-- information_schemaガード付き（MySQL 8にADD COLUMN IF NOT EXISTSが無いため）。
SET @staffing_proposal_pos_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_proposal'
      AND COLUMN_NAME = 'position_id') = 0,
  'ALTER TABLE t_proposal ADD COLUMN position_id BIGINT NULL COMMENT ''ポジションID'' AFTER project_id',
  'SELECT 1');
PREPARE staffing_proposal_pos_stmt FROM @staffing_proposal_pos_sql;
EXECUTE staffing_proposal_pos_stmt;
DEALLOCATE PREPARE staffing_proposal_pos_stmt;

SET @staffing_contract_pos_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contract'
      AND COLUMN_NAME = 'position_id') = 0,
  'ALTER TABLE t_contract ADD COLUMN position_id BIGINT NULL COMMENT ''ポジションID'' AFTER project_id',
  'SELECT 1');
PREPARE staffing_contract_pos_stmt FROM @staffing_contract_pos_sql;
EXECUTE staffing_contract_pos_stmt;
DEALLOCATE PREPARE staffing_contract_pos_stmt;

SET @staffing_proposal_pos_fk_sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_proposal'
      AND CONSTRAINT_NAME = 'fk_proposal_position') = 0,
  'ALTER TABLE t_proposal ADD CONSTRAINT fk_proposal_position
     FOREIGN KEY (position_id) REFERENCES t_project_position(id)
     ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1');
PREPARE staffing_proposal_pos_fk_stmt FROM @staffing_proposal_pos_fk_sql;
EXECUTE staffing_proposal_pos_fk_stmt;
DEALLOCATE PREPARE staffing_proposal_pos_fk_stmt;

SET @staffing_contract_pos_fk_sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_contract'
      AND CONSTRAINT_NAME = 'fk_contract_position') = 0,
  'ALTER TABLE t_contract ADD CONSTRAINT fk_contract_position
     FOREIGN KEY (position_id) REFERENCES t_project_position(id)
     ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1');
PREPARE staffing_contract_pos_fk_stmt FROM @staffing_contract_pos_fk_sql;
EXECUTE staffing_contract_pos_fk_stmt;
DEALLOCATE PREPARE staffing_contract_pos_fk_stmt;

-- ---- 3) 配置計画 ----
-- position_id IS NULLは「社内/待機」という業務値（未割当ではない）。
-- source_contract_id IS NOT NULLの行はactual（契約由来）。需給集計SQLのWHERE句でplanと排他する。
CREATE TABLE IF NOT EXISTS t_allocation_plan (
  id                 BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  engineer_id        BIGINT       NOT NULL                   COMMENT '要員ID',
  position_id        BIGINT                                  COMMENT 'ポジションID（NULL=社内/待機）',
  allocation_type    VARCHAR(20)  NOT NULL DEFAULT '案件'    COMMENT '案件/社内/待機',
  start_date         DATE         NOT NULL                   COMMENT '開始日（inclusive）',
  end_date           DATE                                    COMMENT '終了日（inclusive・NULL=open end: 計画window末まで）',
  allocation_percent DECIMAL(5,2) NOT NULL                   COMMENT '配賦率(%)',
  status             VARCHAR(20)  NOT NULL DEFAULT '下書き'  COMMENT '下書き/確定/破棄',
  source_contract_id BIGINT                                  COMMENT '実契約ID（NOT NULL=actual。planと排他）',
  exception_reason   VARCHAR(1000)                           COMMENT '過配賦例外の理由（例外時必須）',
  approval_request_id BIGINT                                 COMMENT '過配賦例外の承認申請ID（例外時必須）',
  version            INT          NOT NULL DEFAULT 0         COMMENT '楽観ロック',
  created_by         BIGINT                                  COMMENT '作成者ID',
  created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag       TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  INDEX idx_allocation_plan_engineer_period (engineer_id, start_date, end_date),
  INDEX idx_allocation_plan_position (position_id),
  INDEX idx_allocation_plan_status (status),
  INDEX idx_allocation_plan_source_contract (source_contract_id),
  CONSTRAINT chk_allocation_plan_period CHECK (end_date IS NULL OR start_date <= end_date),
  CONSTRAINT chk_allocation_plan_percent CHECK (allocation_percent > 0 AND allocation_percent <= 100),
  CONSTRAINT chk_allocation_plan_status CHECK (status IN ('下書き','確定','破棄')),
  CONSTRAINT fk_allocation_plan_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_allocation_plan_position FOREIGN KEY (position_id) REFERENCES t_project_position(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_allocation_plan_contract FOREIGN KEY (source_contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員配置計画';

-- approval_request_idのFKはV1では張れない（t_approval_requestはV75所属）ため、
-- fresh/legacy共通でここにガード付きADD CONSTRAINTする（両経路で同一shapeに収束）。
SET @staffing_alloc_approval_fk_sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_allocation_plan'
      AND CONSTRAINT_NAME = 'fk_allocation_plan_approval') = 0,
  'ALTER TABLE t_allocation_plan ADD CONSTRAINT fk_allocation_plan_approval
     FOREIGN KEY (approval_request_id) REFERENCES t_approval_request(id)
     ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1');
PREPARE staffing_alloc_approval_fk_stmt FROM @staffing_alloc_approval_fk_sql;
EXECUTE staffing_alloc_approval_fk_stmt;
DEALLOCATE PREPARE staffing_alloc_approval_fk_stmt;

-- ---- 3.5) allocation_type×position_idの整合ガード（trigger） ----
-- MySQL 8はCHECKとFKの同一列併用不可（Error 3823相当）のため、
-- t_allocation_planの当該整合はBEFORE INSERT triggerで担保する（V102_1と同一の重担保方式。
-- H2はCHECK制約で担保する）。legacy DBではt_allocation_planが本migrationで作成されるため、
-- CREATE TABLEの後に配置する（S12-R1-P1-04で検出: 先頭配置だとテーブル不存在で失敗）。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_allocation_plan_type_guard$$
CREATE TRIGGER trg_allocation_plan_type_guard BEFORE INSERT ON t_allocation_plan
FOR EACH ROW
BEGIN
  IF NEW.allocation_type = '案件' AND NEW.position_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'allocation_type/position_id inconsistent';
  END IF;
  IF NEW.allocation_type IN ('社内','待機') AND NEW.position_id IS NOT NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'allocation_type/position_id inconsistent';
  END IF;
END$$
DELIMITER ;

-- ---- 4) 仮配置scenario（本データを変更しない） ----
CREATE TABLE IF NOT EXISTS t_staffing_scenario (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  owner_user_id    BIGINT       NOT NULL                   COMMENT '作成者ID（閲覧scopeの起点）',
  name             VARCHAR(200) NOT NULL                   COMMENT 'scenario名',
  base_date        DATE         NOT NULL                   COMMENT '実データcopy基準日（snapshot）',
  shared_flag      TINYINT      NOT NULL DEFAULT 0         COMMENT '共有フラグ（1=同一組織scope内で共有）',
  assumptions_json TEXT                                    COMMENT '仮定メモのJSON',
  created_at       DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag     TINYINT      NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  INDEX idx_staffing_scenario_owner (owner_user_id),
  CONSTRAINT fk_staffing_scenario_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需給計画シナリオ';

-- ---- 5) scenario内の仮配置（日単位） ----
-- datesは対象日のISO日付JSON配列（昇順・重複なし・[base_date, window末]の範囲）。
-- scenario操作は本テーブルのみを更新し、t_allocation_plan/契約/提案へ一切書き込まない（R3.3）。
CREATE TABLE IF NOT EXISTS t_staffing_scenario_allocation (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  scenario_id BIGINT       NOT NULL                   COMMENT 'scenarioID',
  engineer_id BIGINT       NOT NULL                   COMMENT '要員ID',
  position_id BIGINT                                  COMMENT 'ポジションID（NULL=社内/待機）',
  dates       TEXT         NOT NULL                   COMMENT '対象日のJSON配列（ISO日付・昇順・重複なし）',
  percent     DECIMAL(5,2) NOT NULL                   COMMENT '配賦率(%)',
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP  COMMENT '作成日時',
  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag TINYINT     NOT NULL DEFAULT 0         COMMENT '論理削除フラグ',
  INDEX idx_scenario_alloc_scenario (scenario_id),
  INDEX idx_scenario_alloc_engineer (engineer_id),
  CONSTRAINT chk_scenario_alloc_percent CHECK (percent > 0 AND percent <= 100),
  CONSTRAINT fk_scenario_alloc_scenario FOREIGN KEY (scenario_id) REFERENCES t_staffing_scenario(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_scenario_alloc_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_scenario_alloc_position FOREIGN KEY (position_id) REFERENCES t_project_position(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シナリオ仮配置';
