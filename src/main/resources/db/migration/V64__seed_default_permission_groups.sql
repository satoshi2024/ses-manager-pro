-- ============================================================
-- 企業認証・セキュリティ: legacy roleからaction permissionへの初期移行
-- 公開済みV63は変更せず、本migrationでseed/backfillを追加する。
-- ============================================================

INSERT IGNORE INTO m_permission_group (tenant_id, group_key, group_name, description, enabled) VALUES
  ('default', 'role-admin',    '管理者既定権限',       '管理者の全action', 1),
  ('default', 'role-sales',    '営業既定権限',         '営業の標準action', 1),
  ('default', 'role-hr',       'HR既定権限',           'HRの標準action', 1),
  ('default', 'role-manager',  'マネージャー既定権限', 'マネージャーの標準action', 1),
  ('default', 'role-member',   '要員既定権限',         '要員の本人向けaction', 1);

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key)
SELECT 'default', g.id, a.action_key
FROM m_permission_group g
JOIN (
  SELECT 'role-sales' AS group_key, 'engineer.view' AS action_key UNION ALL
  SELECT 'role-sales', 'engineer.create' UNION ALL SELECT 'role-sales', 'engineer.update' UNION ALL
  SELECT 'role-sales', 'customer.view' UNION ALL SELECT 'role-sales', 'customer.create' UNION ALL
  SELECT 'role-sales', 'customer.update' UNION ALL SELECT 'role-sales', 'project.view' UNION ALL
  SELECT 'role-sales', 'project.create' UNION ALL SELECT 'role-sales', 'project.update' UNION ALL
  SELECT 'role-sales', 'proposal.view' UNION ALL SELECT 'role-sales', 'proposal.create' UNION ALL
  SELECT 'role-sales', 'proposal.update' UNION ALL SELECT 'role-sales', 'contract.view' UNION ALL
  SELECT 'role-sales', 'contract.create' UNION ALL SELECT 'role-sales', 'contract.update' UNION ALL
  SELECT 'role-sales', 'contract.cost.view' UNION ALL SELECT 'role-sales', 'export.execute' UNION ALL
  SELECT 'role-sales', 'file.download' UNION ALL SELECT 'role-sales', 'file.upload' UNION ALL
  SELECT 'role-sales', 'profile.*' UNION ALL SELECT 'role-sales', 'autocomplete.view' UNION ALL
  SELECT 'role-sales', 'candidate.*' UNION ALL
  SELECT 'role-hr', 'engineer.view' UNION ALL SELECT 'role-hr', 'engineer.create' UNION ALL
  SELECT 'role-hr', 'engineer.update' UNION ALL SELECT 'role-hr', 'file.download' UNION ALL
  SELECT 'role-hr', 'file.upload' UNION ALL SELECT 'role-hr', 'export.execute' UNION ALL
  SELECT 'role-hr', 'profile.*' UNION ALL SELECT 'role-hr', 'autocomplete.view' UNION ALL
  SELECT 'role-hr', 'candidate.*' UNION ALL
  SELECT 'role-manager', 'engineer.view' UNION ALL SELECT 'role-manager', 'engineer.update' UNION ALL
  SELECT 'role-manager', 'customer.view' UNION ALL SELECT 'role-manager', 'project.view' UNION ALL
  SELECT 'role-manager', 'proposal.view' UNION ALL SELECT 'role-manager', 'contract.view' UNION ALL
  SELECT 'role-manager', 'contract.update' UNION ALL SELECT 'role-manager', 'contract.cost.view' UNION ALL
  SELECT 'role-manager', 'invoice.view' UNION ALL SELECT 'role-manager', 'export.execute' UNION ALL
  SELECT 'role-manager', '*' UNION ALL
  SELECT 'role-member', 'file.download' UNION ALL SELECT 'role-member', 'profile.*' UNION ALL
  SELECT 'role-member', 'my.*'
) a ON a.group_key = g.group_key
WHERE g.tenant_id = 'default' AND g.enabled = 1;

-- V64適用時点の既存ユーザーをrole相当のdefault groupへ移行する。
-- 以後に明示割当がないユーザーだけはAuthorizationServiceのlegacy role fallbackを使う。
INSERT IGNORE INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_by, assigned_at)
SELECT 'default', u.id, g.id, NULL, CURRENT_TIMESTAMP
FROM sys_user u
JOIN m_permission_group g
  ON g.tenant_id = 'default'
 AND g.group_key = CASE u.role
   WHEN '管理者' THEN 'role-admin'
   WHEN '営業' THEN 'role-sales'
   WHEN 'HR' THEN 'role-hr'
   WHEN 'マネージャー' THEN 'role-manager'
   WHEN '要員' THEN 'role-member'
 END
WHERE u.deleted_flag = 0;
