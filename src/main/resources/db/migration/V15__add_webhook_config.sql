-- ============================================================
-- SES Manager Pro - Slack/Webhook通知連携
-- 説明: notification.webhook-url / notification.webhook-types 設定キーを追加
-- 仕様: .kiro/specs/webhook-notifications/
-- ============================================================

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('notification.webhook-url', '', 'Slack互換Incoming Webhook URL(空=無効)'),
  ('notification.webhook-types', 'CONTRACT_END,PROJECT_URGENT', '転送対象の通知種別(カンマ区切り)');
