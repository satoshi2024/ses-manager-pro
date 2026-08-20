-- ============================================================
-- SES Manager Pro - JP PINT Digital Invoice Fixes (T103)
-- ファイル: V107_1__jp_pint_digital_invoice_fixes.sql
-- ============================================================

-- 1. t_integration_job connection_id NULL可
ALTER TABLE t_integration_job MODIFY connection_id BIGINT NULL;

-- 2. m_menu, t_permission_group_action シードデータ追加
INSERT INTO m_menu (menu_key, name, icon, path_prefix, api_prefix, sort_order)
VALUES 
('digital-invoice', 'デジタルインボイス', 'bi-receipt', '/digital-invoice', '/api/digital-invoices', 801),
('inbound-invoice', '受信インボイス', 'bi-inbox', '/inbound-invoice', '/api/inbound-invoices', 802);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key IN ('digital-invoice', 'inbound-invoice');

INSERT INTO t_role_menu (role, menu_id)
SELECT '営業', id FROM m_menu WHERE menu_key IN ('digital-invoice');

INSERT INTO t_role_menu (role, menu_id)
SELECT 'HR', id FROM m_menu WHERE menu_key IN ('inbound-invoice');

INSERT INTO t_role_menu (role, menu_id)
SELECT 'マネージャー', id FROM m_menu WHERE menu_key IN ('inbound-invoice', 'digital-invoice');

-- Action permissions
INSERT INTO t_permission_group_action (group_id, action_key)
SELECT id, 'digital-invoice.*' FROM m_permission_group WHERE group_code = 'ADMIN_GROUP';
INSERT INTO t_permission_group_action (group_id, action_key)
SELECT id, 'inbound-invoice.*' FROM m_permission_group WHERE group_code = 'ADMIN_GROUP';

INSERT INTO t_permission_group_action (group_id, action_key)
SELECT id, 'digital-invoice.*' FROM m_permission_group WHERE group_code = 'SALES_GROUP';

INSERT INTO t_permission_group_action (group_id, action_key)
SELECT id, 'digital-invoice.*' FROM m_permission_group WHERE group_code = 'MANAGER_GROUP';
INSERT INTO t_permission_group_action (group_id, action_key)
SELECT id, 'inbound-invoice.*' FROM m_permission_group WHERE group_code = 'MANAGER_GROUP';

INSERT INTO t_permission_group_action (group_id, action_key)
SELECT id, 'inbound-invoice.*' FROM m_permission_group WHERE group_code = 'FINANCE_GROUP';

INSERT INTO t_role_menu (role, menu_id) SELECT '財務', id FROM m_menu WHERE menu_key IN ('inbound-invoice');
