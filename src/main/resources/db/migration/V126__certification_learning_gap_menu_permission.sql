-- ===================================================================
-- V126: NF-03 A1 資格・学習・skill gap管理画面のmenu/action境界
-- ===================================================================

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('certification-learning-skill-gap', '資格・学習・スキルギャップ',
        '/certification-learning-skill-gap', '/api/certification-learning-gap', 38);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'certification-learning-skill-gap'
UNION ALL
SELECT 'HR', id FROM m_menu WHERE menu_key = 'certification-learning-skill-gap'
UNION ALL
SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'certification-learning-skill-gap';

-- role group未割当環境でもactionの根拠を固定する。PII actionはV121のHR/admin専用seedを使用する。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'certification-learning-gap.view', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key IN ('role-admin', 'role-hr', 'role-manager')
  AND g.enabled = 1;

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'certification-learning-gap.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key IN ('role-admin', 'role-hr', 'role-manager')
  AND g.enabled = 1;
