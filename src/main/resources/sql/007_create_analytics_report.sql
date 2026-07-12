-- P7 稼動分析・帳票出力: 稼動履歴は t_contract から導出するため新規テーブルは作成しない。
-- メニューマスタへの登録のみ行う。

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
 ('analytics', '稼動分析', '/analytics', '/api/analytics', 65);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'analytics'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key = 'analytics'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'analytics';
