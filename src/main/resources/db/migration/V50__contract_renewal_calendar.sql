-- ============================================================
-- SES Manager Pro - 契約更新カレンダー＋エスカレーション (FR-06)
-- ファイル: V50__contract_renewal_calendar.sql
-- 説明: 契約に更新判断の明示フラグ(renewal_decision)を追加し、
--       更新エスカレーション段階設定を m_system_config に登録する。
-- 仕様: .kiro/specs/contract-renewal-calendar/
-- ============================================================

ALTER TABLE t_contract
  ADD COLUMN renewal_decision VARCHAR(20) NULL COMMENT '更新判断(CONTINUE:継続確定/END:更新不要、NULL:未定)';

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('renewal.escalation-days', '30:営業,14:上長', '契約更新エスカレーション段階設定(日数:通知先ロール のカンマ区切り。ロールは 営業=担当営業/上長=管理者・マネージャー)');
