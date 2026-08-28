-- ===================================================================
-- V127: NF-03 A2 要員本人の資格申請・学習計画導線
-- ===================================================================

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('myCertificationLearningGap', '資格・学習計画（本人）',
        '/my/certification-learning-skill-gap', '/api/my/certification-learning-gap', 99);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '要員', id FROM m_menu WHERE menu_key = 'myCertificationLearningGap';

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'my.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default' AND g.group_key = 'role-engineer' AND g.enabled = 1;
