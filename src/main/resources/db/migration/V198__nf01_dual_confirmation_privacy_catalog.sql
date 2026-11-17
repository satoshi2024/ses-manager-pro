-- ============================================================
-- V198: NF01 二者確認（B0、V195）の7表と自由記述2列を NF07 の data element catalog へ登録する（RB-NF01）。
--
-- 根拠: FINAL 複審 §7 (b) b-7、W3-21a review P3-4、NF01細粒度epoch計画 §10 Privacy 行、
--       deploy-runbook §1.7「NF01 二者確認」(2)、feature-switches.md 4.9.2。
-- 形式は V196 と同じ（構造の行と subject_attribution だけ。owner・データ分類・inventory 状態は既定の UNKNOWN のまま、
-- 保持期間・起算 trigger・法的根拠・処分方式は持たない。I-1）。値の正本は pii-inventory.md（inventory_ref で行を示す。
-- DB-141〜DB-147）で、保存期間等の値は D-02 の署名で確定する（本 migration では決めない）。
--
-- 分類の規則（V196 の「それ以外の未登録の data element」と同じ）:
--   - B0 の表を読む F2 provider は無い（第1期に登録しない）ため provider_id は NULL とし、coverage report で
--     PROVIDER_UNREGISTERED として報告させる（DSAR の検索では「要手動確認」になる）。
--   - B0 の表は追記専用で（projection を除く6表は V195 の trigger と AppendOnlyMapper が更新・削除を拒否する）、
--     hold の gate に接続する破壊的経路（plan §3.4.1 D1〜D7・D9）を持たない。そのため subject_attribution は
--     UNKNOWN（fail-closed）のままにする。本人の導出規則（engineer_id・submitter_user_id・confirmer_user_id・
--     actor_user_id）は provider を登録する batch で宣言する。
--   - 自由記述の2列（business_comment・decision_comment）は、削除・匿名化の経路（計画 §12 O-8）と表示範囲を
--     Privacy の署名で決めるため、表の行とは別の data element にする（表の行の storage_ref はその列を除く）。
--
-- tenant は sys_user に存在する tenant から取る。行が既にあれば入れない（NOT EXISTS。論理削除済みの行も既存として扱い、
-- 復活させない）。後から作られた tenant の行はこの seed では入らない（V196 と同じ。coverage report の扱いも同じ）。
-- H2 の test schema は本 migration をそのまま replay する（application-test.yml）。
-- ============================================================

INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id,
                                subject_attribution, inventory_ref)
SELECT t.tenant_id, d.data_element_id, d.storage_kind, d.storage_ref, d.provider_id, d.subject_attribution,
       d.inventory_ref
FROM (SELECT DISTINCT tenant_id FROM sys_user WHERE tenant_id IS NOT NULL AND tenant_id <> '') t
CROSS JOIN (
  SELECT 'LIFECYCLE_DUAL_SUBMISSION' AS data_element_id, 'DB_COLUMN' AS storage_kind,
         't_lifecycle_dual_submission.(business_comment以外)' AS storage_ref, NULL AS provider_id,
         'UNKNOWN' AS subject_attribution, 'pii-inventory.md#DB-141' AS inventory_ref
  UNION ALL SELECT 'LIFECYCLE_DUAL_SUBMISSION_COMMENT', 'DB_COLUMN', 't_lifecycle_dual_submission.business_comment',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-141'
  UNION ALL SELECT 'LIFECYCLE_DUAL_SUBMISSION_DOCUMENT', 'DB_COLUMN', 't_lifecycle_dual_submission_document',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-142'
  UNION ALL SELECT 'LIFECYCLE_DUAL_SUBMISSION_ORG_FENCE', 'DB_COLUMN', 't_lifecycle_dual_submission_org_fence',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-143'
  UNION ALL SELECT 'LIFECYCLE_DUAL_CONFIRMATION', 'DB_COLUMN', 't_lifecycle_dual_confirmation.(decision_comment以外)',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-144'
  UNION ALL SELECT 'LIFECYCLE_DUAL_DECISION_COMMENT', 'DB_COLUMN', 't_lifecycle_dual_confirmation.decision_comment',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-144'
  UNION ALL SELECT 'LIFECYCLE_DUAL_PROJECTION', 'DB_COLUMN', 't_lifecycle_dual_projection',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-145'
  UNION ALL SELECT 'LIFECYCLE_DUAL_IDEMPOTENCY', 'DB_COLUMN', 't_lifecycle_dual_idempotency',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-146'
  UNION ALL SELECT 'LIFECYCLE_DUAL_EVENT', 'DB_COLUMN', 't_lifecycle_dual_event',
         NULL, 'UNKNOWN', 'pii-inventory.md#DB-147'
) d
WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
                  WHERE e.tenant_id = t.tenant_id AND e.data_element_id = d.data_element_id);
