-- ============================================================
-- V187: 同一要員の未終結退社案件をtenant内で1件に限定する（NF01 L7 / 決定「L7 重複退社」）
--
-- LifecycleCaseServiceImpl.createCase の重複確認はlocking readを伴わないため、並行起票では
-- 両方の確認が通過し、同一要員の退社案件が2件できる。本migrationのDB一意制約を正とし、アプリの
-- 事前確認は早期に400を返すためだけに残す（一意制約違反は同じ 400 error.lifecycle.duplicateActiveCase へ変換する）。
--
-- MySQLは部分一意indexを持たないため、生成列を「退社・未終結・未削除の行だけ engineer_id、それ以外はNULL」とし、
-- (tenant_id, 生成列) をUNIQUEにする。NULLは一意判定の対象外なので、完了・中止・論理削除済みの退社案件と
-- 退社以外の種別の案件は何件でも残せる。
-- 未終結 = status が COMPLETED / CANCELLED 以外（DRAFT・ACTIVE・ON_HOLD と想定外の値を含む。fail-closed）。
--
-- 既存データに同一tenant・同一要員の未終結退社案件が2件以上あると、本DDLは重複エラーで失敗する。
-- 適用前に NF01 read-only preflight の LIFECYCLE_RESIGNATION_ACTIVE_DUPLICATE が0件（PASS）であることを確認する
-- （.kiro/specs/engineer-lifecycle-workflow/runbook.md §5）。本番は空DBへの新規導入のため0件である。
-- 生成列のbase列（lifecycle_type・status・deleted_flag・engineer_id）にはFKが無いため、STOREDでよい。
-- 規約（決定XB-6）: V1へ書き戻さず、本migrationだけで追加する。H2のreplayは
-- src/test/resources/sql/schema-lifecycle-resignation-unique-h2.sql（STORED指定なし）が同じ式を持つ。
-- ============================================================
ALTER TABLE t_lifecycle_case
    ADD COLUMN active_resignation_engineer_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN lifecycle_type = 'RESIGNATION'
                 AND status NOT IN ('COMPLETED', 'CANCELLED')
                 AND deleted_flag = 0
                THEN engineer_id
                ELSE NULL
            END
        ) STORED COMMENT '未終結退社案件の一意用（退社・未終結・未削除の行だけengineer_id）',
    ADD UNIQUE KEY uk_lifecycle_case_active_resignation (tenant_id, active_resignation_engineer_id);
