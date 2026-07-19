-- テスト用(冪等): V28__ar_management.sql の t_invoice_payment 追加相当を共有インメモリH2へ適用する。
-- 複数コンテキストで再実行されても失敗しないよう IF NOT EXISTS を用いる。
-- t_invoice.status は H2 では VARCHAR のため ENUM 値追加(一部入金)のスキーマ変更は不要。
CREATE TABLE IF NOT EXISTS t_invoice_payment (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id   BIGINT NOT NULL,
  paid_date    DATE NOT NULL,
  amount       DECIMAL(12,0) NOT NULL,
  fee          DECIMAL(12,0) NOT NULL DEFAULT 0,
  remarks      VARCHAR(300),
  created_by   BIGINT,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP
);
