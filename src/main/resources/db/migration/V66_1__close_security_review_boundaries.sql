-- ============================================================
-- V66.1: 未知actionのfail-closed化とbreak-glass操作scope
-- 公開済みV66は変更せず、非管理者の全局wildcardを既知resourceへ展開する。
-- ============================================================

DELETE a
FROM t_permission_group_action a
JOIN m_permission_group g ON g.id = a.group_id
WHERE g.tenant_id = 'default'
  AND g.group_key IN ('role-sales', 'role-hr', 'role-manager')
  AND a.action_key = '*'
  AND a.deny_flag = 0;

INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
JOIN (
  SELECT 'ai.*' AS action_key UNION ALL
  SELECT 'analytics.*' UNION ALL SELECT 'autocomplete.*' UNION ALL
  SELECT 'bp-availability.*' UNION ALL SELECT 'bp-availability-ingestion.*' UNION ALL
  SELECT 'candidate.*' UNION ALL SELECT 'cashflow.*' UNION ALL SELECT 'compliance.*' UNION ALL
  SELECT 'contract-document.*' UNION ALL SELECT 'contract.*' UNION ALL SELECT 'customer.*' UNION ALL
  SELECT 'dashboard.*' UNION ALL SELECT 'email.*' UNION ALL SELECT 'engineer.*' UNION ALL
  SELECT 'file.*' UNION ALL SELECT 'identity-provider.*' UNION ALL SELECT 'invoice.*' UNION ALL
  SELECT 'management-accounting.*' UNION ALL SELECT 'monthly-closing.*' UNION ALL SELECT 'my.*' UNION ALL
  SELECT 'notifications.*' UNION ALL SELECT 'organization.*' UNION ALL SELECT 'payroll.*' UNION ALL SELECT 'profile.*' UNION ALL
  SELECT 'project-ingestion.*' UNION ALL SELECT 'project.*' UNION ALL SELECT 'proposal.*' UNION ALL
  SELECT 'quotation.*' UNION ALL SELECT 'reconciliation.*' UNION ALL SELECT 'resume-ingestion.*' UNION ALL
  SELECT 'sales-performance.*' UNION ALL SELECT 'skill-tag.*' UNION ALL
  SELECT 'skillsheet-template.*' UNION ALL SELECT 'system-config.*' UNION ALL
  SELECT 'work-record.*' UNION ALL SELECT 'export.execute'
) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-hr', 'role-manager');

ALTER TABLE t_break_glass_incident
  ADD COLUMN allowed_actions VARCHAR(1000) NOT NULL DEFAULT 'dashboard.view'
  COMMENT '承認済みのexact action key（comma区切り、全局wildcard禁止）'
  AFTER correlation_id;
