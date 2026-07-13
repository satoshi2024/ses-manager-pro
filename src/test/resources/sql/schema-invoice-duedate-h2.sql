-- テスト用(冪等): V13__invoice_due_date.sql 相当を共有インメモリH2へ適用する。
-- 複数コンテキストで再実行されても失敗しないよう IF NOT EXISTS / ON DUPLICATE KEY UPDATE を用いる。
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS due_date DATE;

INSERT INTO m_system_config (config_key, config_value, description) VALUES
  ('billing.payment-due-rule',            'next-month-end', '支払期限ルール(next-month-end/next-next-month-end)'),
  ('company.invoice-registration-number', '',               '適格請求書発行事業者 登録番号(T+13桁)'),
  ('company.address',                     '',               '会社住所（請求書用）')
ON DUPLICATE KEY UPDATE description = VALUES(description);
