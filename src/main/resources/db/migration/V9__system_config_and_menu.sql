-- ============================================================
-- SES Manager Pro - システム設定キーとメニュー追加 (P8 Task8)
-- ファイル: 008_system_config_and_menu.sql
-- 説明: ハードコード値を設定化するためのキー投入と、システム設定画面メニュー登録
-- ============================================================

-- 設定キー（ハードコード値の設定化）
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('notice.contract-end-days',  '30',   '契約終了予告の日数'),
  ('notice.proposal-stale-days','7',    '提案停滞とみなす日数'),
  ('notice.bench-warn-days',    '30',   'Bench警告とみなす日数'),
  ('billing.tax-rate',          '0.10', '消費税率'),
  ('company.name',              'SES Manager Pro', '会社名（請求書用）'),
  ('company.bank-info',         '',     '振込先（請求書用）');

-- システム設定画面メニュー（管理者のみ）
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('system-config', 'システム設定', '/system-config', '/api/system-configs', 95);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'system-config';
