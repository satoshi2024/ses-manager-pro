-- V76: A1承認inbox/申請履歴のメニュー定義（適用済みV75は編集しない）
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('approval', '承認ワークフロー', '/approval', '/api/approval', 58);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '営業' UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'approval';
