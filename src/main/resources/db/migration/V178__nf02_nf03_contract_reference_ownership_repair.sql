-- V178: 契約の参照先ownership不整合を修復キューへ記録する。
-- tenantを推測して補完せず、resolverから不可視のまま手動修復対象として残す。

CREATE INDEX idx_contract_tenant_reference
    ON t_contract (tenant_id, customer_id, engineer_id, project_id, sales_user_id, deleted_flag, id);

CREATE INDEX idx_user_org_tenant_owner
    ON t_user_organization (tenant_id, user_id, organization_id, deleted_flag, id);

-- 契約自身、顧客、要員、案件、案件登録者、担当営業の証拠を一括監査する。
INSERT INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, conflicting_tenant_id,
     evidence, evidence_hash, created_at, last_checked_at)
SELECT 'CONTRACT', c.id, 'TENANT_REFERENCE_MISMATCH', 'PENDING',
       COALESCE(e.tenant_id, su.tenant_id, mc.tenant_id),
       CONCAT('contract=', COALESCE(c.tenant_id, 'NULL'),
              '; customer=', COALESCE(mc.tenant_id, 'NULL'),
              '; engineer=', COALESCE(e.tenant_id, 'NULL'),
              '; project_customer=', COALESCE(pc.tenant_id, 'NULL'),
              '; project_creator=', COALESCE(pu.tenant_id, 'NULL'),
              '; sales=', COALESCE(su.tenant_id, 'NULL')),
       SHA2(CONCAT('CONTRACT:', c.id, ':',
                   COALESCE(c.tenant_id, 'NULL'), ':',
                   COALESCE(mc.tenant_id, 'NULL'), ':',
                   COALESCE(e.tenant_id, 'NULL'), ':',
                   COALESCE(pc.tenant_id, 'NULL'), ':',
                   COALESCE(pu.tenant_id, 'NULL'), ':',
                   COALESCE(su.tenant_id, 'NULL')), 256),
       NOW(), NOW()
  FROM t_contract c
  LEFT JOIN m_customer mc ON mc.id = c.customer_id
  LEFT JOIN t_engineer e ON e.id = c.engineer_id AND e.deleted_flag = 0
  LEFT JOIN t_project p ON p.id = c.project_id AND p.deleted_flag = 0
  LEFT JOIN m_customer pc ON pc.id = p.customer_id
  LEFT JOIN sys_user pu ON pu.id = p.created_by AND pu.deleted_flag = 0
  LEFT JOIN sys_user su ON su.id = c.sales_user_id AND su.deleted_flag = 0
 WHERE c.tenant_id IS NULL OR c.tenant_id = ''
    OR mc.id IS NULL OR mc.tenant_id IS NULL OR mc.tenant_id = ''
    OR e.id IS NULL OR e.tenant_id IS NULL OR e.tenant_id = ''
    OR p.id IS NULL OR p.deleted_flag <> 0 OR p.customer_id <> c.customer_id
    OR pc.id IS NULL OR pc.tenant_id IS NULL OR pc.tenant_id = ''
    OR (pu.id IS NULL AND p.created_by IS NOT NULL)
    OR (pu.id IS NOT NULL AND (pu.tenant_id IS NULL OR pu.tenant_id = ''))
    OR (pu.id IS NOT NULL AND CONVERT(pu.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(c.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
    OR (c.sales_user_id IS NOT NULL AND (su.id IS NULL OR su.tenant_id IS NULL
        OR su.tenant_id = '' OR CONVERT(su.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(c.tenant_id USING utf8mb4) COLLATE utf8mb4_bin))
    OR (mc.tenant_id IS NOT NULL AND c.tenant_id IS NOT NULL
        AND CONVERT(mc.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(c.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
    OR (e.tenant_id IS NOT NULL AND c.tenant_id IS NOT NULL
        AND CONVERT(e.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(c.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
    OR (pc.tenant_id IS NOT NULL AND c.tenant_id IS NOT NULL
        AND CONVERT(pc.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(c.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
ON DUPLICATE KEY UPDATE reason = VALUES(reason),
    conflicting_tenant_id = VALUES(conflicting_tenant_id), evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash), last_checked_at = VALUES(last_checked_at);

-- 顧客・要員・案件の母集団そのものに一意なtenant証拠が無い行を監査する。
INSERT INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, evidence, evidence_hash, created_at, last_checked_at)
SELECT 'CUSTOMER', c.id, 'TENANT_UNRESOLVED', 'PENDING',
       'customer.tenant_id is NULL or blank',
       SHA2(CONCAT('CUSTOMER:', c.id, ':', COALESCE(c.tenant_id, 'NULL')), 256), NOW(), NOW()
  FROM m_customer c
 WHERE c.tenant_id IS NULL OR c.tenant_id = ''
UNION ALL
SELECT 'ENGINEER', e.id, 'TENANT_REFERENCE_MISMATCH', 'PENDING',
       CONCAT('engineer=', COALESCE(e.tenant_id, 'NULL'),
              '; creator=', COALESCE(u.tenant_id, 'NULL')),
       SHA2(CONCAT('ENGINEER:', e.id, ':', COALESCE(e.tenant_id, 'NULL'), ':',
                   COALESCE(u.tenant_id, 'NULL')), 256), NOW(), NOW()
  FROM t_engineer e
  LEFT JOIN sys_user u ON u.id = e.created_by AND u.deleted_flag = 0
 WHERE e.tenant_id IS NULL OR e.tenant_id = ''
    OR (e.created_by IS NOT NULL AND (u.id IS NULL OR u.tenant_id IS NULL
        OR u.tenant_id = '' OR u.tenant_id <> e.tenant_id))
ON DUPLICATE KEY UPDATE reason = VALUES(reason),
    conflicting_tenant_id = VALUES(conflicting_tenant_id), evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash), last_checked_at = VALUES(last_checked_at);

INSERT INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, conflicting_tenant_id,
     evidence, evidence_hash, created_at, last_checked_at)
SELECT 'PROJECT', p.id, 'TENANT_REFERENCE_MISMATCH', 'PENDING', mc.tenant_id,
       CONCAT('project_customer=', COALESCE(mc.tenant_id, 'NULL'),
              '; project_creator=', COALESCE(u.tenant_id, 'NULL')),
       SHA2(CONCAT('PROJECT:', p.id, ':', COALESCE(mc.tenant_id, 'NULL'), ':',
                   COALESCE(u.tenant_id, 'NULL')), 256), NOW(), NOW()
  FROM t_project p
  LEFT JOIN m_customer mc ON mc.id = p.customer_id
  LEFT JOIN sys_user u ON u.id = p.created_by AND u.deleted_flag = 0
 WHERE mc.id IS NULL OR mc.tenant_id IS NULL OR mc.tenant_id = ''
    OR (p.created_by IS NOT NULL AND (u.id IS NULL OR u.tenant_id IS NULL
        OR u.tenant_id = '' OR u.tenant_id <> mc.tenant_id))
ON DUPLICATE KEY UPDATE reason = VALUES(reason),
    conflicting_tenant_id = VALUES(conflicting_tenant_id), evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash), last_checked_at = VALUES(last_checked_at);

-- 担当営業と連携ユーザーのtenant証拠が不足・衝突する場合も、既定tenantへ寄せない。
INSERT INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, conflicting_tenant_id,
     evidence, evidence_hash, created_at, last_checked_at)
SELECT 'SYS_USER', u.id, 'TENANT_UNRESOLVED', 'PENDING', u.tenant_id,
       CONCAT('sys_user tenant=', COALESCE(u.tenant_id, 'NULL')),
       SHA2(CONCAT('SYS_USER:', u.id, ':', COALESCE(u.tenant_id, 'NULL')), 256),
       NOW(), NOW()
  FROM sys_user u
 WHERE u.tenant_id IS NULL OR u.tenant_id = ''
ON DUPLICATE KEY UPDATE reason = VALUES(reason),
    conflicting_tenant_id = VALUES(conflicting_tenant_id), evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash), last_checked_at = VALUES(last_checked_at);

INSERT INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, conflicting_tenant_id,
     evidence, evidence_hash, created_at, last_checked_at)
SELECT 'ENGINEER_ACCOUNT_LINK', l.id, 'TENANT_REFERENCE_MISMATCH', 'PENDING',
       COALESCE(u.tenant_id, e.tenant_id),
       CONCAT('link=', COALESCE(l.tenant_id, 'NULL'),
              '; user=', COALESCE(u.tenant_id, 'NULL'),
              '; engineer=', COALESCE(e.tenant_id, 'NULL')),
       SHA2(CONCAT('ENGINEER_ACCOUNT_LINK:', l.id, ':',
                   COALESCE(l.tenant_id, 'NULL'), ':',
                   COALESCE(u.tenant_id, 'NULL'), ':',
                   COALESCE(e.tenant_id, 'NULL')), 256),
       NOW(), NOW()
  FROM t_engineer_account_link l
  LEFT JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0
  LEFT JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0
 WHERE l.tenant_id IS NULL OR l.tenant_id = ''
    OR u.id IS NULL OR u.tenant_id IS NULL OR u.tenant_id = ''
    OR e.id IS NULL OR e.tenant_id IS NULL OR e.tenant_id = ''
    OR (u.tenant_id IS NOT NULL AND CONVERT(l.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(u.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
    OR (e.tenant_id IS NOT NULL AND CONVERT(l.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(e.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
    OR (u.tenant_id IS NOT NULL AND e.tenant_id IS NOT NULL
        AND CONVERT(u.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(e.tenant_id USING utf8mb4) COLLATE utf8mb4_bin)
ON DUPLICATE KEY UPDATE reason = VALUES(reason),
    conflicting_tenant_id = VALUES(conflicting_tenant_id), evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash), last_checked_at = VALUES(last_checked_at);

INSERT INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, conflicting_tenant_id,
     evidence, evidence_hash, created_at, last_checked_at)
SELECT 'USER_ORGANIZATION', o.id, 'TENANT_REFERENCE_MISMATCH', 'PENDING', u.tenant_id,
       CONCAT('organization_link=', COALESCE(o.tenant_id, 'NULL'),
              '; user=', COALESCE(u.tenant_id, 'NULL')),
       SHA2(CONCAT('USER_ORGANIZATION:', o.id, ':',
                   COALESCE(o.tenant_id, 'NULL'), ':',
                   COALESCE(u.tenant_id, 'NULL')), 256), NOW(), NOW()
  FROM t_user_organization o
  LEFT JOIN sys_user u ON u.id = o.user_id AND u.deleted_flag = 0
 WHERE o.tenant_id IS NULL OR o.tenant_id = ''
    OR u.id IS NULL OR u.tenant_id IS NULL OR u.tenant_id = ''
    OR CONVERT(o.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
        <> CONVERT(u.tenant_id USING utf8mb4) COLLATE utf8mb4_bin
ON DUPLICATE KEY UPDATE reason = VALUES(reason),
    conflicting_tenant_id = VALUES(conflicting_tenant_id), evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash), last_checked_at = VALUES(last_checked_at);
