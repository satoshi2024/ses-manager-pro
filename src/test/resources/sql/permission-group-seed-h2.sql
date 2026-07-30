-- V64 + V66 のpermission group seedをH2で再現するfixture。
-- 本体migrationは MySQL 固有構文（INSERT IGNORE / DELETE a FROM ... JOIN）を使うため
-- そのままH2へ流せない。最終状態だけを同じ内容で作る。
-- 差異が出ないよう、action keyと deny_flag は V64/V66 と1対1で対応させること。

DELETE FROM t_permission_group_action;
DELETE FROM t_user_permission_group;
DELETE FROM m_permission_group;

INSERT INTO m_permission_group (id, tenant_id, group_key, group_name, enabled, deleted_flag) VALUES
  (1, 'default', 'role-admin',   '管理者既定権限',       1, 0),
  (2, 'default', 'role-sales',   '営業既定権限',         1, 0),
  (3, 'default', 'role-hr',      'HR既定権限',           1, 0),
  (4, 'default', 'role-manager', 'マネージャー既定権限', 1, 0),
  (5, 'default', 'role-member',  '要員既定権限',         1, 0);

-- V64の明示許可（抜粋: baselineと重なるが実DBにも残るため同じ状態にする）
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 2, 'engineer.view', 0, 0),
  ('default', 2, 'contract.cost.view', 0, 0),
  ('default', 2, 'export.execute', 0, 0),
  ('default', 3, 'engineer.view', 0, 0),
  ('default', 4, 'contract.cost.view', 0, 0),
  ('default', 5, 'file.download', 0, 0),
  ('default', 5, 'profile.*', 0, 0);

-- V66のbaseline許可
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 1, '*', 0, 0),
  ('default', 2, '*', 0, 0),
  ('default', 3, '*', 0, 0),
  ('default', 4, '*', 0, 0),
  ('default', 5, 'my.*', 0, 0),
  ('default', 5, 'notifications.*', 0, 0);

-- V66の拒否指定
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 2, 'user.*', 1, 0),
  ('default', 2, 'permission.manage', 1, 0),
  ('default', 2, 'audit.security.view', 1, 0),
  ('default', 2, 'file.scan.retry', 1, 0),
  ('default', 2, 'payroll.view', 1, 0),
  ('default', 3, 'user.*', 1, 0),
  ('default', 3, 'permission.manage', 1, 0),
  ('default', 3, 'audit.security.view', 1, 0),
  ('default', 3, 'file.scan.retry', 1, 0),
  ('default', 3, 'contract.cost.view', 1, 0),
  ('default', 4, 'user.*', 1, 0),
  ('default', 4, 'permission.manage', 1, 0),
  ('default', 4, 'audit.security.view', 1, 0),
  ('default', 4, 'file.scan.retry', 1, 0),
  ('default', 4, 'payroll.view', 1, 0);

-- role別のdefault group割当（V64/V66のbackfill相当）
INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_at, deleted_flag) VALUES
  ('default', 9001, 2, CURRENT_TIMESTAMP, 0),
  ('default', 9002, 3, CURRENT_TIMESTAMP, 0),
  ('default', 9003, 4, CURRENT_TIMESTAMP, 0),
  ('default', 9004, 5, CURRENT_TIMESTAMP, 0);
