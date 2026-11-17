-- V64〜V66.1・V78・V121・V126・V191・V193 のpermission group seedをH2で再現するfixture。
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
  (5, 'default', 'role-member',  '要員既定権限',         1, 0),
  -- V191: 管理者専用 group（privacy.* の唯一の許可根拠）
  (6, 'default', 'privacy-admin', 'プライバシー管理（管理者専用）', 1, 0);

-- V64の明示許可（抜粋: baselineと重なるが実DBにも残るため同じ状態にする）
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 2, 'engineer.view', 0, 0),
  ('default', 2, 'contract.cost.view', 0, 0),
  ('default', 2, 'export.execute', 0, 0),
  ('default', 3, 'engineer.view', 0, 0),
  ('default', 4, 'contract.cost.view', 0, 0),
  ('default', 5, 'file.download', 0, 0),
  ('default', 5, 'profile.*', 0, 0);

-- V66.1の既知resource許可。非管理者のV66グローバルwildcardは削除済み。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag)
SELECT 'default', g.group_id, a.action_key, 0, 0
FROM (VALUES (2), (3), (4)) g(group_id)
CROSS JOIN (VALUES
  ('ai.*'), ('analytics.*'), ('autocomplete.*'), ('bp-availability.*'),
  ('bp-availability-ingestion.*'), ('candidate.*'), ('cashflow.*'), ('compliance.*'),
  ('contract-document.*'), ('contract.*'), ('customer.*'), ('dashboard.*'), ('email.*'),
  ('engineer.*'), ('file.*'), ('invoice.*'),
  ('management-accounting.*'), ('monthly-closing.*'), ('my.*'), ('notifications.*'), ('organization.*'),
  ('payroll.*'), ('profile.*'), ('project-ingestion.*'), ('project.*'), ('proposal.*'),
  ('quotation.*'), ('reconciliation.*'), ('resume-ingestion.*'), ('sales-performance.*'),
  ('skill-tag.*'), ('skillsheet-template.*'), ('work-record.*'),
  ('export.execute'), ('document.*'), ('document-archive.*')
) a(action_key)
WHERE NOT EXISTS (
  SELECT 1 FROM t_permission_group_action existing
  WHERE existing.tenant_id = 'default'
    AND existing.group_id = g.group_id
    AND existing.action_key = a.action_key
);

-- V74: CRM(S08)は design §6.2 により営業(2)とマネージャー(4)だけ。HR(3)へは付与しない。
-- 併せてV68/V69(S05)・V70(S06)のseed漏れも補完する（付与先は各specのmenu付与に合わせる）。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 2, 'crm.*', 0, 0),
  ('default', 4, 'crm.*', 0, 0),
  ('default', 2, 'bp-company.*', 0, 0),
  ('default', 4, 'bp-company.*', 0, 0);

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag)
SELECT 'default', g.group_id, a.action_key, 0, 0
FROM (VALUES (2), (3), (4)) g(group_id)
CROSS JOIN (VALUES ('search.*'), ('task.*'), ('saved-view.*'), ('batch-operation.*')) a(action_key);

-- V75: approval-workflow-internal-control(S07)。営業(2)・HR(3)・マネージャー(4)へ付与。
-- 要員(5)はSecurityConfigのanyRequestが4管理ロール限定のため対象外（既存仕様、V74と同型）。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag)
SELECT 'default', g.group_id, 'approval.*', 0, 0
FROM (VALUES (2), (3), (4)) g(group_id);

INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 1, '*', 0, 0),
  ('default', 5, 'my.*', 0, 0),
  ('default', 5, 'notifications.*', 0, 0);

-- V66の拒否指定
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 2, 'user.*', 1, 0),
  ('default', 2, 'permission.manage', 1, 0),
  ('default', 2, 'audit.security.view', 1, 0),
  ('default', 2, 'file.scan.retry', 1, 0),
  ('default', 2, 'payroll.view', 1, 0),
  ('default', 2, 'identity-provider.*', 1, 0),
  ('default', 2, 'system-config.*', 1, 0),
  ('default', 3, 'user.*', 1, 0),
  ('default', 3, 'permission.manage', 1, 0),
  ('default', 3, 'audit.security.view', 1, 0),
  ('default', 3, 'file.scan.retry', 1, 0),
  ('default', 3, 'contract.cost.view', 1, 0),
  ('default', 3, 'identity-provider.*', 1, 0),
  ('default', 3, 'system-config.*', 1, 0),
  ('default', 4, 'user.*', 1, 0),
  ('default', 4, 'permission.manage', 1, 0),
  ('default', 4, 'audit.security.view', 1, 0),
  ('default', 4, 'file.scan.retry', 1, 0),
  ('default', 4, 'payroll.view', 1, 0),
  ('default', 4, 'identity-provider.*', 1, 0),
  ('default', 4, 'system-config.*', 1, 0),
  -- V78: 承認画面の口座fieldは専用actionで明示拒否する。BP会社マスタのbp-company.*は維持。
  ('default', 2, 'bp-company.bank-account.view', 1, 0),
  ('default', 4, 'bp-company.bank-account.view', 1, 0);

-- V121: 資格番号の復号（certification.pii.view）は role-admin（1）と role-hr（3）だけに明示許可する。
-- V126: 資格・学習・skill gap の画面actionは role-admin・role-hr・role-manager（1・3・4）へ付与する。
-- resource は certification-learning-gap で、certification.pii.view の候補（certification.*）とは別のresourceである。
-- 営業（2）・マネージャー（4）に certification.pii.view の許可根拠が無いことを ActionPermissionMatrixTest が検証する（FINAL P3-4-1）。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 1, 'certification.pii.view', 0, 0),
  ('default', 3, 'certification.pii.view', 0, 0),
  ('default', 1, 'certification-learning-gap.view', 0, 0),
  ('default', 3, 'certification-learning-gap.view', 0, 0),
  ('default', 4, 'certification-learning-gap.view', 0, 0),
  ('default', 1, 'certification-learning-gap.*', 0, 0),
  ('default', 3, 'certification-learning-gap.*', 0, 0),
  ('default', 4, 'certification-learning-gap.*', 0, 0);

-- V191: privacy.* は privacy-admin だけが許可し（'*' と privacy.* の明示許可）、他の全 group は deny する。
-- privacy の判定では '*' を許可根拠に数えないため、group 6 の許可根拠は privacy.* の行だけである。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 6, '*', 0, 0),
  ('default', 6, 'privacy.*', 0, 0),
  ('default', 1, 'privacy.*', 1, 0),
  ('default', 2, 'privacy.*', 1, 0),
  ('default', 3, 'privacy.*', 1, 0),
  ('default', 4, 'privacy.*', 1, 0),
  ('default', 5, 'privacy.*', 1, 0);

-- V193: import.metadata.manage（NF-06取込センター）は管理者専用 group（1・6）だけに明示許可し、他の group は deny する。
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES
  ('default', 1, 'import.metadata.manage', 0, 0),
  ('default', 6, 'import.metadata.manage', 0, 0),
  ('default', 2, 'import.metadata.manage', 1, 0),
  ('default', 3, 'import.metadata.manage', 1, 0),
  ('default', 4, 'import.metadata.manage', 1, 0),
  ('default', 5, 'import.metadata.manage', 1, 0);

-- role別のdefault group割当（V64/V66のbackfill相当）
INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_at, deleted_flag) VALUES
  ('default', 9001, 2, CURRENT_TIMESTAMP, 0),
  ('default', 9002, 3, CURRENT_TIMESTAMP, 0),
  ('default', 9003, 4, CURRENT_TIMESTAMP, 0),
  ('default', 9004, 5, CURRENT_TIMESTAMP, 0),
  -- NF07 W-5（ActionPermissionMatrixTest）: 9005=管理者・privacy-admin だけ、9006=営業・privacy-admin だけ（誤割当の再現）、
  -- 9007=管理者・role-admin だけ、9008=管理者・privacy-admin と role-admin の両方（deny 優先で privacy は不可）。
  ('default', 9005, 6, CURRENT_TIMESTAMP, 0),
  ('default', 9006, 6, CURRENT_TIMESTAMP, 0),
  ('default', 9007, 1, CURRENT_TIMESTAMP, 0),
  ('default', 9008, 6, CURRENT_TIMESTAMP, 0),
  ('default', 9008, 1, CURRENT_TIMESTAMP, 0);
