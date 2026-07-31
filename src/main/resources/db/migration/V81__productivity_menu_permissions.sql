-- 生産性向上機能（横断検索・実ToDo・保存ビュー・安全な一括操作）のメニュー・権限定義

INSERT IGNORE INTO m_menu (id, menu_key, menu_name, path_prefix, api_prefix, sort_order, created_at, updated_at) VALUES
(20, 'search', '横断検索', '/search', '/api/search', 200, NOW(), NOW()),
(21, 'tasks', 'タスク管理', '/tasks', '/api/tasks', 210, NOW(), NOW()),
(22, 'saved-views', '保存ビュー', '/saved-views', '/api/saved-views', 220, NOW(), NOW()),
(23, 'batch-operations', '一括操作', '/batch-operations', '/api/batch-operations', 230, NOW(), NOW());

-- ロール権限マッピング (管理者、営業、HR、マネージャー、要員)
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (
    SELECT '管理者' AS role UNION ALL
    SELECT '営業' AS role UNION ALL
    SELECT 'HR' AS role UNION ALL
    SELECT 'マネージャー' AS role UNION ALL
    SELECT '要員' AS role
) r
CROSS JOIN m_menu m
WHERE m.menu_key IN ('search', 'tasks', 'saved-views', 'batch-operations');
