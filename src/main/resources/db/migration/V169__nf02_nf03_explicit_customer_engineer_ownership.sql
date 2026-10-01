-- V169: NF02/NF03の顧客・要員母集団を明示的tenant ownershipへ移行する。
-- 適用済みmigrationの履歴は変更しない。tenantを特定できないlegacy行はNULLのまま
-- となり、resolverが不可視にする（defaultへ推測補完せず、手動修復対象として残す）。

ALTER TABLE m_customer
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_engineer
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;

-- 複数の信頼できるtenant付き業務関係が異なるtenantを指す場合は、勝手に採番せず停止する。
CREATE TEMPORARY TABLE __ses_v169_customer_tenant_candidates (
    customer_id BIGINT NOT NULL,
    tenant_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    PRIMARY KEY (customer_id, tenant_id)
);
INSERT IGNORE INTO __ses_v169_customer_tenant_candidates (customer_id, tenant_id)
SELECT customer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_service_request
 WHERE customer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT customer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM m_portal_organization
 WHERE customer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT customer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM m_workplace
 WHERE customer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT customer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_sales_order
 WHERE customer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> '';

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_v169_check_customer_ownership $$
CREATE PROCEDURE __ses_v169_check_customer_ownership()
BEGIN
    DECLARE conflict_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO conflict_count
      FROM (
          SELECT customer_id
            FROM __ses_v169_customer_tenant_candidates
           GROUP BY customer_id
          HAVING COUNT(*) > 1
      ) conflicts;
    IF conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V169: 顧客tenant ownershipが一意に判定できないため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_v169_check_customer_ownership();
DROP PROCEDURE IF EXISTS __ses_v169_check_customer_ownership;

UPDATE m_customer c
JOIN __ses_v169_customer_tenant_candidates x ON x.customer_id = c.id
SET c.tenant_id = x.tenant_id
WHERE c.tenant_id IS NULL;
DROP TEMPORARY TABLE __ses_v169_customer_tenant_candidates;

CREATE TEMPORARY TABLE __ses_v169_engineer_tenant_candidates (
    engineer_id BIGINT NOT NULL,
    tenant_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    PRIMARY KEY (engineer_id, tenant_id)
);
INSERT IGNORE INTO __ses_v169_engineer_tenant_candidates (engineer_id, tenant_id)
SELECT l.engineer_id, CONVERT(l.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
  FROM t_engineer_account_link l
  JOIN sys_user u ON u.id = l.sys_user_id
                  AND CONVERT(u.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
                    = CONVERT(l.tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
 WHERE l.engineer_id IS NOT NULL AND l.tenant_id IS NOT NULL AND l.tenant_id <> ''
UNION ALL
SELECT engineer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_engineer_certification
 WHERE engineer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT engineer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_learning_plan
 WHERE engineer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT engineer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_training_enrollment
 WHERE engineer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT engineer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_engineer_skill_event
 WHERE engineer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> ''
UNION ALL
SELECT engineer_id, CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci FROM t_skill_gap_snapshot
 WHERE engineer_id IS NOT NULL AND tenant_id IS NOT NULL AND tenant_id <> '';

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_v169_check_engineer_ownership $$
CREATE PROCEDURE __ses_v169_check_engineer_ownership()
BEGIN
    DECLARE conflict_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO conflict_count
      FROM (
          SELECT engineer_id
            FROM __ses_v169_engineer_tenant_candidates
           GROUP BY engineer_id
          HAVING COUNT(*) > 1
      ) conflicts;
    IF conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V169: 要員tenant ownershipが一意に判定できないため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_v169_check_engineer_ownership();
DROP PROCEDURE IF EXISTS __ses_v169_check_engineer_ownership;

UPDATE t_engineer e
JOIN __ses_v169_engineer_tenant_candidates x ON x.engineer_id = e.id
SET e.tenant_id = x.tenant_id
WHERE e.tenant_id IS NULL;
DROP TEMPORARY TABLE __ses_v169_engineer_tenant_candidates;

-- 所有権を一意に復元できないlegacy行はdefaultへ寄せず、修復キューへ明示的に残す。
CREATE TABLE nf02_nf03_ownership_repair_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    reason VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_nf02_nf03_ownership_repair (entity_type, entity_id)
);

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue (entity_type, entity_id, reason)
SELECT 'CUSTOMER', id, 'TENANT_UNRESOLVED'
  FROM m_customer
 WHERE tenant_id IS NULL
UNION ALL
SELECT 'ENGINEER', id, 'TENANT_UNRESOLVED'
  FROM t_engineer
 WHERE tenant_id IS NULL;

CREATE INDEX idx_customer_tenant_population
    ON m_customer (tenant_id, deleted_flag, id);
CREATE INDEX idx_engineer_tenant_population
    ON t_engineer (tenant_id, deleted_flag, id);
