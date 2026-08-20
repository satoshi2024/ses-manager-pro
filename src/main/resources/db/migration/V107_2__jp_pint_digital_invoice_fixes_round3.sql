-- ============================================================
-- SES Manager Pro - JP PINT Digital Invoice Fixes Round 3 (T103)
-- ============================================================

-- 1. m_menu シードデータ追加
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES 
('digital-invoice', 'デジタルインボイス', '/digital-invoice', '/api/digital-invoices', 801),
('inbound-invoice', '受信インボイス', '/inbound-invoice', '/api/inbound-invoices', 802);

-- 2. t_role_menu
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT '営業' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'digital-invoice'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー' ) r
CROSS JOIN m_menu m
WHERE m.menu_key = 'inbound-invoice'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);

-- 3. t_permission_group_action
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'digital-invoice.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-admin', 'role-sales', 'role-manager');

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'inbound-invoice.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-admin', 'role-manager');
ALTER TABLE t_digital_invoice DROP INDEX uk_digital_invoice_send;

