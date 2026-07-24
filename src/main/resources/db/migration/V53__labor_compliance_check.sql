-- ============================================================
-- SES Manager Pro - 偽装請負・多重派遣リスクチェック (FR-10)
-- 説明: 契約への指揮命令実態フラグ、リスクルール設定(m_system_config)、
--       管理者/マネージャー向けリスク一覧メニューを追加。
-- 仕様: .kiro/specs/labor-compliance-check/
-- ============================================================

ALTER TABLE t_contract
  ADD COLUMN direct_command_flag TINYINT NOT NULL DEFAULT 0 COMMENT '指揮命令の実態フラグ(偽装請負判定用)' AFTER remarks;

-- リスクルール設定（既定値。管理者が /system-config から変更可能）
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('compliance.max-tier', '3', '多重下請けの許容階層数の上限（超過で警告）'),
  ('compliance.rule.tier-exceeded.enabled', 'true', 'リスクルール有効化: 多重下請け段数超過'),
  ('compliance.rule.direct-command.enabled', 'true', 'リスクルール有効化: 偽装請負兆候(指揮命令フラグ)'),
  ('compliance.rule.double-dispatch.enabled', 'true', 'リスクルール有効化: 二重派遣兆候'),
  ('compliance.rule.actual-mismatch.enabled', 'true', 'リスクルール有効化: 契約種別と精算実態の不整合');

-- コンプライアンス（リスク項目）一覧メニュー（管理者・マネージャーのみ）
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('compliance', 'コンプライアンス', '/compliance', '/api/compliance', 72);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'compliance'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'compliance';
