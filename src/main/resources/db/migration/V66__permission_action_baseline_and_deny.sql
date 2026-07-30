-- ============================================================
-- V66: action permissionのbaseline付与と拒否指定
--
-- V64のseedは各roleへ許可actionを列挙したが、action keyは
-- ActionPermissionResolverがURI rootから機械的に生成するため列挙しきれず、
-- 未列挙のaction（dashboard.view / analytics.view / quotation.view /
-- work-record.view / *.delete 等）が全て拒否されていた。
-- R3.1「固定roleは後方互換を維持しつつpermission groupへ段階移行する」に反するため、
-- baseline '*' と「拒否」指定の組み合わせで旧menu相当の母集団へ戻す。
--
-- 公開済みのV63〜V65は変更せず、本migrationで是正する。
-- ============================================================

ALTER TABLE t_permission_group_action
  ADD COLUMN deny_flag TINYINT NOT NULL DEFAULT 0
  COMMENT '1:このactionを明示拒否（同一groupのbaseline許可より優先）';

-- ------------------------------------------------------------
-- 1. baseline許可。管理者は全action、営業/HR/マネージャーは
--    「旧menu権限で到達できた範囲」＝下の拒否指定を除く全action。
--    要員はbaselineを持たず、許可listだけで到達範囲を決める
--    （SecurityConfigのanyRequest().hasAnyRole(...)が要員を除外しているため、
--    baselineを与えても到達経路は増えないが、明示的に最小権限を保つ）。
-- ------------------------------------------------------------
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
JOIN (
  SELECT 'role-admin' AS group_key, '*' AS action_key UNION ALL
  SELECT 'role-sales', '*' UNION ALL
  SELECT 'role-hr', '*' UNION ALL
  SELECT 'role-manager', '*' UNION ALL
  -- 要員本人向け経路。V64のseedに含まれていなかったmy.*を追加する
  -- （legacy fallback側でも要員のmy.viewが拒否されていた）。
  SELECT 'role-member', 'my.*' UNION ALL
  SELECT 'role-member', 'notifications.*'
) a ON a.group_key = g.group_key
WHERE g.tenant_id = 'default' AND g.enabled = 1;

-- ------------------------------------------------------------
-- 2. 拒否指定。baselineを持つroleから機密actionだけを外す。
--
--    V64はrole-managerへ '*' をseedしており、拒否の仕組みが無かったため
--    action層がpayroll.view / audit.security.view / user.* / permission.manage /
--    file.scan.retry まで素通しだった（SecurityConfigのhasRole('管理者')とmenu権限だけが
--    実害を止めていた）。baselineは後方互換のため維持し、ここで機密actionを差し引く。
--    role-managerの既存 '*' 行はそのまま許可baselineとして再利用する（INSERT IGNOREでskipされる）。
--    - user.* / permission.manage / audit.security.view / file.scan.retry
--      はSecurityConfigでも管理者限定だが、action層でも明示的に拒否する。
--    - payroll.view は m_menu(V21)で管理者とHRにだけ付与されているため、
--      営業とマネージャーで拒否する。
--    - contract.cost.view はR3.3の原価マスキング対象。V64のseedと同じく
--      HRには与えない（営業・マネージャーは粗利判断に必要なため許可）。
-- ------------------------------------------------------------
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 1
FROM m_permission_group g
JOIN (
  SELECT 'role-sales' AS group_key, 'user.*' AS action_key UNION ALL
  SELECT 'role-sales', 'permission.manage' UNION ALL
  SELECT 'role-sales', 'audit.security.view' UNION ALL
  SELECT 'role-sales', 'file.scan.retry' UNION ALL
  SELECT 'role-sales', 'payroll.view' UNION ALL
  SELECT 'role-hr', 'user.*' UNION ALL
  SELECT 'role-hr', 'permission.manage' UNION ALL
  SELECT 'role-hr', 'audit.security.view' UNION ALL
  SELECT 'role-hr', 'file.scan.retry' UNION ALL
  SELECT 'role-hr', 'contract.cost.view' UNION ALL
  SELECT 'role-manager', 'user.*' UNION ALL
  SELECT 'role-manager', 'permission.manage' UNION ALL
  SELECT 'role-manager', 'audit.security.view' UNION ALL
  SELECT 'role-manager', 'file.scan.retry' UNION ALL
  SELECT 'role-manager', 'payroll.view'
) a ON a.group_key = g.group_key
WHERE g.tenant_id = 'default' AND g.enabled = 1;

-- ------------------------------------------------------------
-- 3. V64適用後に作成されたユーザーはgroup未割当のままなので、
--    role相当のdefault groupへ移行する（V64と同じbackfillを再実行する）。
--    UserApiControllerの新規作成経路でも割当するよう修正済み。
-- ------------------------------------------------------------
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
