-- V182: V180 が conflicting_tenant_id=NULL で積んだ修復行を推測補完せず、
-- 監査専用ビューで可視化する。claim/resolve は引き続き conflicting_tenant_id 一致が必須。
-- V181: t_monthly_closing.tenant_id を NOT NULL 化（NULL 行は書込禁止のため削除）。

-- 1. 月次締め: NULL tenant 行は不可視・書込禁止のため除去し、列を NOT NULL へ
DELETE FROM t_monthly_closing WHERE tenant_id IS NULL OR tenant_id = '';

ALTER TABLE t_monthly_closing
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL COMMENT 'テナント境界（必須）';

-- 2. 所有権修復: 証拠tenant不明行を監査ビューで見える化（推測して conflicting_tenant_id を埋めない）
CREATE OR REPLACE VIEW v_nf02_nf03_ownership_repair_unresolved_audit AS
SELECT
    q.id,
    q.entity_type,
    q.entity_id,
    q.reason,
    q.status,
    q.conflicting_tenant_id,
    q.repair_tenant_id,
    q.actor_tenant_id,
    q.evidence,
    q.evidence_hash,
    q.created_at,
    q.last_checked_at,
    q.claimed_at,
    q.claimed_by,
    CASE
        WHEN q.conflicting_tenant_id IS NULL OR q.conflicting_tenant_id = '' THEN 1
        ELSE 0
    END AS conflict_tenant_unresolved,
    CASE
        WHEN q.conflicting_tenant_id IS NULL OR q.conflicting_tenant_id = '' THEN 'AUDIT_ONLY_NO_CLAIM'
        ELSE 'CLAIMABLE_WHEN_MATCHED'
    END AS ops_path
FROM nf02_nf03_ownership_repair_queue q
WHERE q.status IN ('PENDING', 'CLAIMED');

-- 3. 証拠tenant不明行の evidence に監査マーカーを追記（tenant 推測はしない）
UPDATE nf02_nf03_ownership_repair_queue
   SET evidence = CONCAT(
           IFNULL(evidence, ''),
           CASE WHEN evidence IS NULL OR evidence = '' THEN '' ELSE '; ' END,
           'V182:conflicting_tenant_unresolved;ops=AUDIT_ONLY_NO_CLAIM'),
       last_checked_at = CURRENT_TIMESTAMP
 WHERE status = 'PENDING'
   AND (conflicting_tenant_id IS NULL OR conflicting_tenant_id = '')
   AND (evidence IS NULL OR evidence NOT LIKE '%V182:conflicting_tenant_unresolved%');
