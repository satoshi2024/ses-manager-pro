-- ============================================================
-- SES Manager Pro - 請求書の支払期限・適格請求書対応 (改善第2弾 WS-F)
-- ファイル: V13__invoice_due_date.sql
-- 説明: 請求書に支払期限(due_date)を追加し、期限超過の検知・通知を可能にする。
--       あわせて適格請求書(インボイス制度)の必須記載事項用に、支払期限ルールと
--       自社の登録番号・住所をシステム設定へ追加する。
-- ============================================================

-- 支払期限
ALTER TABLE t_invoice ADD COLUMN due_date DATE NULL COMMENT '支払期限';

-- システム設定: 支払期限ルールと適格請求書用の自社情報
--   billing.payment-due-rule: next-month-end(翌月末) / next-next-month-end(翌々月末)
--   company.name は V9 で投入済みのため再投入しない
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('billing.payment-due-rule',            'next-month-end', '支払期限ルール(next-month-end/next-next-month-end)'),
  ('company.invoice-registration-number', '',               '適格請求書発行事業者 登録番号(T+13桁)'),
  ('company.address',                     '',               '会社住所（請求書用）');
