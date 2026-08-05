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
