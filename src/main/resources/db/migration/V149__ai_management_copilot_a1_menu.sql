-- NF-08 A1 (V149): 経営コパイロット chat 画面メニュー
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('management-copilot', '経営コパイロット', '/copilot', '/api/copilot', 74);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー' UNION ALL SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'management-copilot'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);

-- fail-closed action permission: マネージャー/営業へ copilot API を明示付与（管理者は role-admin の * で到達）。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'copilot.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key IN ('role-manager', 'role-sales')
  AND g.enabled = 1;
