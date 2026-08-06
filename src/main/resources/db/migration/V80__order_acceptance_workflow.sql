-- ============================================================
-- V80: 注文・注文請・月次検収 (order-acceptance-workflow / S09)
-- 予約番号V80。V79_1(approval)適用後に適用する。V59は永久欠番のため埋めない。
--
-- 追加するDDL:
--   t_sales_order          : 注文ヘッダ（注文番号/顧客PO/期間/金額snapshot/支払条件snapshot）
--   t_sales_order_line     : 注文明細（1要員1明細、複数明細で複数要員注文を表現）
--   t_contract.order_line_id      : 1明細→1契約（UNIQUEで二重契約化を拒否）
--   t_contract.acceptance_required: 検収要否（NOT NULL DEFAULT TRUE。NULL不可）
--   t_acceptance           : 契約×月の検収（UNIQUE(contract_id, work_month)）
--   m_menu / t_role_menu   : sales-order(注文管理), acceptance(月次検収)
--   m_document_type        : ORDER_RECEIVED / ORDER_ACKNOWLEDGEMENT / ACCEPTANCE
--   t_permission_group_action: sales-order.* / acceptance.*（営業・マネージャー）
-- ============================================================

-- ============================================================
-- 1. t_sales_order — 注文ヘッダ
-- ============================================================
CREATE TABLE IF NOT EXISTS t_sales_order (
  id                       BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  tenant_id                VARCHAR(100)  NOT NULL DEFAULT 'default' COMMENT 'テナントID',
  legal_entity_id          BIGINT        COMMENT '法人ID（将来multi-entity用）',
  order_no                 VARCHAR(30)   NOT NULL COMMENT '注文番号',
  customer_po_no           VARCHAR(100)  COMMENT '顧客PO番号',
  customer_id              BIGINT        NOT NULL COMMENT '顧客ID',
  contact_id               BIGINT        COMMENT '顧客担当者ID',
  quotation_id             BIGINT        COMMENT '生成元見積ID',
  order_date               DATE          NOT NULL COMMENT '注文日',
  start_date               DATE          COMMENT '期間開始日',
  end_date                 DATE          COMMENT '期間終了日',
  status                   VARCHAR(20)   NOT NULL DEFAULT '下書き' COMMENT '状態: 下書き/受領確認/注文請提出/契約化/完了/取消',
  total_amount_snapshot    DECIMAL(15,0) COMMENT '注文確定時点の総額snapshot（下書きはNULL）',
  payment_terms_snapshot   VARCHAR(200)  COMMENT '注文確定時点の支払条件snapshot（下書きはNULL）',
  source_document_id       BIGINT        COMMENT '受領注文書document ID',
  acknowledgement_document_id BIGINT      COMMENT '注文請書document ID',
  version                  INT           NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
  created_by               BIGINT        COMMENT '登録者ID',
  created_at               DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag             TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_sales_order_no (order_no),
  INDEX idx_sales_order_customer (customer_id),
  INDEX idx_sales_order_po (customer_id, customer_po_no),
  INDEX idx_sales_order_date (order_date),
  INDEX idx_sales_order_status (status),
  CONSTRAINT fk_sales_order_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_sales_order_contact FOREIGN KEY (contact_id) REFERENCES t_customer_contact(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_sales_order_quotation FOREIGN KEY (quotation_id) REFERENCES t_quotation(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_sales_order_source_doc FOREIGN KEY (source_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_sales_order_ack_doc FOREIGN KEY (acknowledgement_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注文テーブル';

-- ============================================================
-- 2. t_sales_order_line — 注文明細（1要員1明細）
-- ============================================================
CREATE TABLE IF NOT EXISTS t_sales_order_line (
  id             BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  order_id       BIGINT        NOT NULL COMMENT '注文ID',
  line_no        INT           NOT NULL COMMENT '明細番号',
  project_id     BIGINT        COMMENT '案件ID',
  engineer_id    BIGINT        NOT NULL COMMENT '要員ID',
  quantity       INT           NOT NULL DEFAULT 1 COMMENT '数量',
  unit_price     DECIMAL(12,0) NOT NULL COMMENT '単価(円/月)',
  settlement_min DECIMAL(5,1)  COMMENT '精算下限(h)',
  settlement_max DECIMAL(5,1)  COMMENT '精算上限(h)',
  amount         DECIMAL(12,0) COMMENT '明細金額(円)',
  remarks        VARCHAR(500)  COMMENT '備考',
  created_at     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag   TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_sales_order_line (order_id, line_no),
  INDEX idx_sales_order_line_engineer (engineer_id),
  INDEX idx_sales_order_line_project (project_id),
  CONSTRAINT fk_sales_order_line_order FOREIGN KEY (order_id) REFERENCES t_sales_order(id)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_sales_order_line_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_sales_order_line_project FOREIGN KEY (project_id) REFERENCES t_project(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注文明細テーブル';

-- ============================================================
-- 3. t_contract への列追加（legacy DB用。V1統合baselineにも最終形を定義済み）
--    MySQL 8にADD COLUMN IF NOT EXISTSは無いため information_schema 判定で冪等にする。
-- ============================================================
SET @order_line_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND column_name = 'order_line_id') = 0,
    'ALTER TABLE t_contract ADD COLUMN order_line_id BIGINT NULL AFTER quotation_id',
    'SELECT 1'
);
PREPARE order_line_stmt FROM @order_line_sql;
EXECUTE order_line_stmt;
DEALLOCATE PREPARE order_line_stmt;

-- 1明細→1契約（R5二重click防止）。NULLは複数許容（注文経由でない契約）。
SET @order_line_uk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND index_name = 'uk_contract_order_line') = 0,
    'ALTER TABLE t_contract ADD UNIQUE KEY uk_contract_order_line (order_line_id)',
    'SELECT 1'
);
PREPARE order_line_uk_stmt FROM @order_line_uk_sql;
EXECUTE order_line_uk_stmt;
DEALLOCATE PREPARE order_line_uk_stmt;

-- 注文明細→契約の参照整合（R09-P1-05対応）。孤児 order_line_id を拒否する。
-- fresh/legacyともV1/V80にこのFKは無いため、guard付きで1本だけ追加し両経路を収束させる。
SET @contract_line_fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND constraint_name = 'fk_contract_order_line') = 0,
    'ALTER TABLE t_contract ADD CONSTRAINT fk_contract_order_line FOREIGN KEY (order_line_id) REFERENCES t_sales_order_line(id) ON UPDATE CASCADE ON DELETE RESTRICT',
    'SELECT 1'
);
PREPARE contract_line_fk_stmt FROM @contract_line_fk_sql;
EXECUTE contract_line_fk_stmt;
DEALLOCATE PREPARE contract_line_fk_stmt;

-- 検収要否（R3.3）。NOT NULL DEFAULT TRUE: 「未設定＝検収不要」に化けない。
SET @acceptance_required_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND column_name = 'acceptance_required') = 0,
    'ALTER TABLE t_contract ADD COLUMN acceptance_required TINYINT NOT NULL DEFAULT 1',
    'SELECT 1'
);
PREPARE acceptance_required_stmt FROM @acceptance_required_sql;
EXECUTE acceptance_required_stmt;
DEALLOCATE PREPARE acceptance_required_stmt;

-- 検収不要理由（P1-01対応）。acceptance_required=0時は必須（service層で検証）。NULL=検収要のまま。
SET @exemption_reason_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND column_name = 'acceptance_exemption_reason') = 0,
    'ALTER TABLE t_contract ADD COLUMN acceptance_exemption_reason VARCHAR(500) NULL',
    'SELECT 1'
);
PREPARE exemption_reason_stmt FROM @exemption_reason_sql;
EXECUTE exemption_reason_stmt;
DEALLOCATE PREPARE exemption_reason_stmt;

-- 【go-live移行方針（R09-P2-01/P2-04/P1-01対応）】V80適用時点で既に存在する契約
-- （order_line_idがNULL=注文経由でない既存契約）は、検収フロー導入前に稼働していた実績の請求が
-- 全面停止しないよう「検収不要（acceptance_required=0）」へ移行する。理由は固定文言を設定する。
-- V80以後の新規契約はNOT NULL DEFAULT 1（検収要）のまま。
-- markerテーブルに初回適用時点の契約ID集合を固定し、UPDATEはmarker行だけを対象にする。
-- 途中失敗→flyway repair→再実行でも、新規契約を誤って0へ書き換えない（repair-safe）。
CREATE TABLE IF NOT EXISTS t_contract_acceptance_backfill (
  contract_id   BIGINT PRIMARY KEY,
  backfilled_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='V80 legacy backfill marker';

-- markerが空の時（初回適用）だけ既存契約ID集合を固定する。repair再実行時は追加しない。
SET @marker_sql = IF(
    (SELECT COUNT(*) FROM t_contract_acceptance_backfill) = 0,
    'INSERT INTO t_contract_acceptance_backfill (contract_id) SELECT id FROM t_contract WHERE order_line_id IS NULL',
    'SELECT 1'
);
PREPARE marker_stmt FROM @marker_sql;
EXECUTE marker_stmt;
DEALLOCATE PREPARE marker_stmt;

UPDATE t_contract c
  JOIN t_contract_acceptance_backfill m ON m.contract_id = c.id
  SET c.acceptance_required = 0,
      c.acceptance_exemption_reason = '移行前契約（V80適用時点の既存契約）';

-- ============================================================
-- 4. t_acceptance — 契約×月の検収
-- ============================================================
CREATE TABLE IF NOT EXISTS t_acceptance (
  id                   BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  contract_id          BIGINT        NOT NULL COMMENT '契約ID',
  work_record_id       BIGINT        COMMENT '対象work record ID',
  work_month           CHAR(7)       NOT NULL COMMENT '対象月(YYYY-MM)',
  status               VARCHAR(20)   NOT NULL DEFAULT '未提出' COMMENT '状態: 未提出/提出済/検収済/差戻し',
  submitted_at         DATETIME      COMMENT '提出日時',
  customer_contact_id  BIGINT        COMMENT '顧客確認者ID',
  customer_contact_name_snapshot VARCHAR(100) COMMENT '顧客確認者名snapshot（検収実行時点。改名後も不変）',
  accepted_at          DATETIME      COMMENT '検収日時',
  reject_comment       VARCHAR(500)  COMMENT '差戻し理由',
  document_id          BIGINT        COMMENT '検収書document ID',
  hours_snapshot       DECIMAL(6,2)  COMMENT '提出時点の工数snapshot',
  amount_snapshot      DECIMAL(12,0) COMMENT '提出時点の請求金額snapshot',
  work_record_updated_at DATETIME    COMMENT '提出時点のwork record更新日時（version代用）',
  version              INT           NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
  created_by           BIGINT        COMMENT '登録者ID',
  created_at           DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  deleted_flag         TINYINT       NOT NULL DEFAULT 0 COMMENT '論理削除',
  UNIQUE KEY uk_acceptance_contract_month (contract_id, work_month),
  INDEX idx_acceptance_work_record (work_record_id),
  INDEX idx_acceptance_status_month (status, work_month),
  CONSTRAINT fk_acceptance_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_acceptance_work_record FOREIGN KEY (work_record_id) REFERENCES t_work_record(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_acceptance_contact FOREIGN KEY (customer_contact_id) REFERENCES t_customer_contact(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_acceptance_document FOREIGN KEY (document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次検収テーブル';


-- 【fresh/legacy FK収束（R09-P2-02対応）】
-- fresh DBはV1が t_sales_order / t_acceptance をFK無しで作成するため、V80のCREATE TABLE IF NOT EXISTSが
-- スキップされFKが欠落する。legacy DB（V80でCREATE）と同一形状へ収束させるため、constraint名が無い場合に
-- だけ情報スキーマ判定付きでADDする（MySQL 8にADD CONSTRAINT IF NOT EXISTSは無い）。
SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_sales_order' AND constraint_name = 'fk_sales_order_contact') = 0,
    'ALTER TABLE t_sales_order ADD CONSTRAINT fk_sales_order_contact FOREIGN KEY (contact_id) REFERENCES t_customer_contact(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_sales_order' AND constraint_name = 'fk_sales_order_quotation') = 0,
    'ALTER TABLE t_sales_order ADD CONSTRAINT fk_sales_order_quotation FOREIGN KEY (quotation_id) REFERENCES t_quotation(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_sales_order' AND constraint_name = 'fk_sales_order_source_doc') = 0,
    'ALTER TABLE t_sales_order ADD CONSTRAINT fk_sales_order_source_doc FOREIGN KEY (source_document_id) REFERENCES t_document(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_sales_order' AND constraint_name = 'fk_sales_order_ack_doc') = 0,
    'ALTER TABLE t_sales_order ADD CONSTRAINT fk_sales_order_ack_doc FOREIGN KEY (acknowledgement_document_id) REFERENCES t_document(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_acceptance' AND constraint_name = 'fk_acceptance_work_record') = 0,
    'ALTER TABLE t_acceptance ADD CONSTRAINT fk_acceptance_work_record FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_acceptance' AND constraint_name = 'fk_acceptance_contact') = 0,
    'ALTER TABLE t_acceptance ADD CONSTRAINT fk_acceptance_contact FOREIGN KEY (customer_contact_id) REFERENCES t_customer_contact(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

SET @fk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_acceptance' AND constraint_name = 'fk_acceptance_document') = 0,
    'ALTER TABLE t_acceptance ADD CONSTRAINT fk_acceptance_document FOREIGN KEY (document_id) REFERENCES t_document(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_stmt FROM @fk_sql; EXECUTE fk_stmt; DEALLOCATE PREPARE fk_stmt;

-- ============================================================
-- 5. m_menu / t_role_menu（要員ロールはマイメニューのみ。HRは不可視）
-- ============================================================
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('sales-order', '注文管理', '/sales-order', '/api/sales-orders', 57),
       ('acceptance', '月次検収', '/acceptance', '/api/acceptances', 59);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '営業' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key IN ('sales-order', 'acceptance');

-- ============================================================
-- 6. m_document_type（法定文書台帳の種別。保存10年・取引日起算）
-- ============================================================
INSERT IGNORE INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
VALUES
  ('ORDER_RECEIVED',       '注文書（受領）', 'INCOMING', 10, 'TRANSACTION_DATE', 1),
  ('ORDER_ACKNOWLEDGEMENT', '注文請書',       'OUTGOING', 10, 'TRANSACTION_DATE', 1),
  ('ACCEPTANCE',           '検収書',         'OUTGOING', 10, 'TRANSACTION_DATE', 1);

-- ============================================================
-- 7. action permission（ActionPermissionResolverのRESOURCE_NAMESへ
--    sales-orders→sales-order / acceptances→acceptance を登録した変更と対）
--    HRはmenu非付与のためseedしない。要員はSecurityConfigのanyRequest対象外。
-- ============================================================
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'sales-order.*' AS action_key UNION ALL SELECT 'acceptance.*') a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-manager');

-- ============================================================
-- 8. fresh/legacy metadata収束（R09-P2-02対応）
--    V1(fresh)はremarks直後に列を定義しCOMMENTを持つ。legacyはV80のADDでAFTER quotation_id・COMMENT無し
--    で追加されるため、末尾で両列をV1と同一の完全定義（COMMENT・位置）へMODIFYして収束させる。
--    MODIFYは既存データを変更せず、同一定義への再適用は冪等。
-- ============================================================
ALTER TABLE t_contract
  MODIFY COLUMN order_line_id BIGINT NULL COMMENT '注文明細ID（1明細→1契約）' AFTER remarks,
  MODIFY COLUMN acceptance_required TINYINT NOT NULL DEFAULT 1 COMMENT '検収要否(1:要 0:不要。未設定を不要にしない)' AFTER order_line_id,
  MODIFY COLUMN acceptance_exemption_reason VARCHAR(500) NULL COMMENT '検収不要理由（acceptance_required=0時は必須。R3.3）' AFTER acceptance_required;
