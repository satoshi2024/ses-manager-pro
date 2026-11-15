-- V193相当のH2 seed（NF-06 取込センター画面の menu・import.metadata.manage の明示許可と deny）。
-- V193本体は MySQL の UPDATE ... JOIN を使うため、H2では副問合せで同じ最終状態を作る。
-- schema-locations の最後（V191相当の schema-privacy-permission-h2.sql より後）に置き、
-- 共有H2への再実行に耐えるよう全て存在確認付きで書く。
-- ActionPermissionMatrixTest 等の group fixture は permission-group-seed-h2.sql が同じ内容を明示 id で作る。

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'import-center', '取込センター', '/import-center', '/api/import-center/sources', 262
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'import-center');

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id
FROM m_menu m
WHERE m.menu_key = 'import-center'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu r WHERE r.role = '管理者' AND r.menu_id = m.id);

UPDATE t_permission_group_action SET deny_flag = 0, deleted_flag = 0
WHERE action_key = 'import.metadata.manage'
  AND group_id IN (SELECT id FROM m_permission_group WHERE group_key IN ('role-admin', 'privacy-admin'));

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag)
SELECT g.tenant_id, g.id, 'import.metadata.manage', 0, 0
FROM m_permission_group g
WHERE g.group_key IN ('role-admin', 'privacy-admin')
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id
                    AND x.action_key = 'import.metadata.manage');

UPDATE t_permission_group_action SET deny_flag = 1, deleted_flag = 0
WHERE action_key = 'import.metadata.manage'
  AND group_id IN (SELECT id FROM m_permission_group WHERE group_key NOT IN ('role-admin', 'privacy-admin'));

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag)
SELECT g.tenant_id, g.id, 'import.metadata.manage', 1, 0
FROM m_permission_group g
WHERE g.group_key NOT IN ('role-admin', 'privacy-admin')
  AND NOT EXISTS (SELECT 1 FROM t_permission_group_action x
                  WHERE x.tenant_id = g.tenant_id AND x.group_id = g.id
                    AND x.action_key = 'import.metadata.manage');
