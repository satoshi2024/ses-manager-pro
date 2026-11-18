-- テスト用(冪等): V52__payment_reconciliation.sql の t_bank_deposit 相当を共有インメモリH2へ用意する。
-- 入金消込（FR-09）とfreeeへの反映（DEPOSIT_RECONCILE_SYNC）を実サービスで通すH2統合テストが使う。
-- 複数コンテキスト・engineer-schema-h2.sql の @Sql と共有するため IF NOT EXISTS を用い、
-- engineer-schema-h2.sql と同じく外部キーは張らない（ENUM は H2 では VARCHAR とする）。
CREATE TABLE IF NOT EXISTS t_bank_deposit (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  freee_deposit_id    VARCHAR(64) NOT NULL,
  deposit_date        DATE NOT NULL,
  amount              DECIMAL(12,0) NOT NULL,
  payer_name          VARCHAR(200),
  status              VARCHAR(10) NOT NULL DEFAULT '未消込',
  matched_invoice_id  BIGINT,
  matched_payment_id  BIGINT,
  remarks             VARCHAR(300),
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (freee_deposit_id)
);
