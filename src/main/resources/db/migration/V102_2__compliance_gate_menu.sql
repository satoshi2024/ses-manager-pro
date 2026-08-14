-- ============================================================
-- SES Manager Pro - 派遣コンプライアンスG2 gate管理メニュー（R23-P1-01 §5）
-- 説明: /compliance-gate ページ（Mapping / Reviewer Type / Review Policy / Assignment /
--       Internal Approval / External Review / 本人・資格・作成者確認 / ACTIVE / Event History）
--       と /api/compliance-gate/** を m_menu へ登録する。
--       ActionPermissionResolver（java）へ 'compliance-gate' を登録しない限り
--       MenuPermissionFilter は全ロールで403を返す（CRM-R2-P1-01と同じ罠）。
--       管理者: 全操作 / HR・マネージャー: approval画面（serviceでassignment一致必須）。
--       営業・要員: menu未付与＋action seedなし（403）。
--       V98のleave seedと同一パターン（baseline+deny方式・group割当済み非管理者はseedが無ければ403）。
-- ============================================================

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('compliance-gate', '派遣コンプライアンスG2', '/compliance-gate', '/api/compliance-gate', 73);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'compliance-gate';

-- compliance-gate resourceの権限seed。approval画面はHR/マネージャーへ付与し、
-- 営業group（role-sales）へは入れない（design §5: 営業はG2 gate操作権限を持たない）。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'compliance-gate.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-hr', 'role-manager');
