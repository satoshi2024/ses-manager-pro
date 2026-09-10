-- NF02/NF03の既存要員アカウント連携を監査する。tenantを推測・defaultへ補完せず、
-- sys_userと要員の明示ownershipが一致しない行は修復キューへ送る。
INSERT IGNORE INTO nf02_nf03_ownership_repair_queue (entity_type, entity_id, reason)
SELECT 'ENGINEER_ACCOUNT_LINK', l.id, 'TENANT_UNRESOLVED'
  FROM t_engineer_account_link l
  LEFT JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0
  LEFT JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0
 WHERE l.tenant_id IS NULL
    OR u.tenant_id IS NULL
    OR e.tenant_id IS NULL
    OR (CONVERT(l.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
            <> CONVERT(u.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci)
    OR (CONVERT(l.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
            <> CONVERT(e.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci)
    OR (CONVERT(u.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
            <> CONVERT(e.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci);

CREATE INDEX idx_engineer_account_link_tenant_owner
    ON t_engineer_account_link (tenant_id, engineer_id, sys_user_id);
