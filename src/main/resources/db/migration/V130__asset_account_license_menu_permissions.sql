-- ===================================================================
-- V130: NF-09 資産・アカウント管理のmenu/action境界
-- ===================================================================

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('asset-management', '資産・アカウント管理', '/asset', '/api/assets', 40);

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('my-assets', '貸与資産・アカウント', '/my/assets', '/api/my/assets', 41);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'asset-management'
UNION ALL
SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'asset-management'
UNION ALL
SELECT 'HR', id FROM m_menu WHERE menu_key = 'asset-management'
UNION ALL
SELECT '営業', id FROM m_menu WHERE menu_key = 'asset-management'
UNION ALL
SELECT '要員', id FROM m_menu WHERE menu_key = 'my-assets';

-- permission_group_action シード
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'asset.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key IN ('role-admin', 'role-manager', 'role-hr', 'role-sales')
  AND g.enabled = 1;

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'my.asset.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key = 'role-engineer'
  AND g.enabled = 1;
