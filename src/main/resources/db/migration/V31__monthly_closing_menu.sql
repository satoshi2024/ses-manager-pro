-- ============================================================
-- SES Manager Pro - 月次締めチェックリスト（monthly-closing-checklist / P3）
-- ファイル: V31__monthly_closing_menu.sql
-- 説明: 月次締めメニューを追加する（新テーブルなし・DDL変更なし）。
-- ============================================================

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('monthly-closing', '月次締め', '/monthly-closing', '/api/monthly-closing', 91);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'monthly-closing'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'monthly-closing'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'monthly-closing';
