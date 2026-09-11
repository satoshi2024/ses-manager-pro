-- V180: V171が m_bp_company.tenant_id(BIGINT 1) を CAST して書き込んだ
-- t_bp_availability.tenant_id = '1' / '1.0' を、V165と同じ規則で 'default' へ正規化する。
-- 適用済みV171は変更しない。判定不能行は推測せず修復キューへ送る。

-- 1. 歴史的BIGINT 1由来の文字列を現行認証tenant表現へ揃える
UPDATE t_bp_availability
   SET tenant_id = 'default'
 WHERE tenant_id IN ('1', '1.0');

-- 2. なお残るNULL/空、および万一の '1'/'1.0' は修復キューへ（推測補完しない）
INSERT INTO nf02_nf03_ownership_repair_queue (
    entity_type, entity_id, reason, status, conflicting_tenant_id, evidence, evidence_hash, created_at)
SELECT
    'BP_AVAILABILITY',
    a.id,
    'TENANT_UNRESOLVED',
    'PENDING',
    NULL,
    CONCAT('bp_availability_id=', a.id, ';tenant_id=', IFNULL(a.tenant_id, 'NULL'),
           ';bp_company_id=', IFNULL(a.bp_company_id, 'NULL')),
    SHA2(CONCAT('BP_AVAILABILITY|', a.id, '|', IFNULL(a.tenant_id, ''), '|', IFNULL(a.bp_company_id, '')), 256),
    CURRENT_TIMESTAMP
  FROM t_bp_availability a
 WHERE a.tenant_id IS NULL
    OR a.tenant_id = ''
    OR a.tenant_id IN ('1', '1.0')
ON DUPLICATE KEY UPDATE
    reason = VALUES(reason),
    evidence = VALUES(evidence),
    evidence_hash = VALUES(evidence_hash),
    last_checked_at = CURRENT_TIMESTAMP;

-- 3. V180経路で '1'/'1.0' が残っていないことを保証する
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_check_v180_bp_availability_tenant $$
CREATE PROCEDURE __ses_check_v180_bp_availability_tenant()
BEGIN
    DECLARE leftover INT DEFAULT 0;
    SELECT COUNT(*) INTO leftover
      FROM t_bp_availability
     WHERE tenant_id IN ('1', '1.0');
    IF leftover > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V180: t_bp_availability.tenant_id に 1/1.0 が残っています';
    END IF;
END $$
DELIMITER ;
CALL __ses_check_v180_bp_availability_tenant();
DROP PROCEDURE IF EXISTS __ses_check_v180_bp_availability_tenant;
