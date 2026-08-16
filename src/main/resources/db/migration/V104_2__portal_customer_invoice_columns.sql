-- ============================================================
-- V104_2: 顧客portal用t_invoice列（S13 external-customer-bp-portal / T084 A1）
-- spec: external-customer-bp-portal
--
-- t_invoice はV5所属でV1統合baselineには含まれないため、情報スキーマガード付きで追加する。
-- R2.3: 顧客は請求書の受領確認・支払予定日・問い合わせを登録できる（入金済状態は変更できない）。
-- ============================================================

SET @portal_invoice_received_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_invoice'
      AND COLUMN_NAME = 'received_confirmed_at') = 0,
  'ALTER TABLE t_invoice ADD COLUMN received_confirmed_at DATETIME NULL COMMENT ''受領確認日時（顧客portalが一度だけ設定。R2.3）''',
  'SELECT 1');
PREPARE portal_invoice_received_stmt FROM @portal_invoice_received_sql;
EXECUTE portal_invoice_received_stmt;
DEALLOCATE PREPARE portal_invoice_received_stmt;

SET @portal_invoice_payment_expected_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_invoice'
      AND COLUMN_NAME = 'payment_expected_date') = 0,
  'ALTER TABLE t_invoice ADD COLUMN payment_expected_date DATE NULL COMMENT ''支払予定日（顧客portalが登録。R2.3）''',
  'SELECT 1');
PREPARE portal_invoice_payment_expected_stmt FROM @portal_invoice_payment_expected_sql;
EXECUTE portal_invoice_payment_expected_stmt;
DEALLOCATE PREPARE portal_invoice_payment_expected_stmt;

SET @portal_invoice_inquiry_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_invoice'
      AND COLUMN_NAME = 'portal_inquiry') = 0,
  'ALTER TABLE t_invoice ADD COLUMN portal_inquiry VARCHAR(1000) NULL COMMENT ''請求に関する問い合わせ（顧客portalが登録。R2.3）''',
  'SELECT 1');
PREPARE portal_invoice_inquiry_stmt FROM @portal_invoice_inquiry_sql;
EXECUTE portal_invoice_inquiry_stmt;
DEALLOCATE PREPARE portal_invoice_inquiry_stmt;
