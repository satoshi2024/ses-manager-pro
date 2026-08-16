-- ============================================================
-- V104_4: portal通知設定列（S13 external-customer-bp-portal / R13-R1-P1-03）
-- spec: external-customer-bp-portal
--
-- R4.1: portal userごとのemail通知設定（1=通知する（既定）、0=通知しない）。
-- PortalNotificationServiceはこの列が1のuserのみに送信する。
-- ============================================================

SET @portal_notify_email_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_portal_user'
      AND COLUMN_NAME = 'notify_email') = 0,
  'ALTER TABLE t_portal_user ADD COLUMN notify_email TINYINT NOT NULL DEFAULT 1 COMMENT ''email通知設定（1=通知する。R4.1）'' AFTER mfa_policy',
  'SELECT 1');
PREPARE portal_notify_email_stmt FROM @portal_notify_email_sql;
EXECUTE portal_notify_email_stmt;
DEALLOCATE PREPARE portal_notify_email_stmt;
