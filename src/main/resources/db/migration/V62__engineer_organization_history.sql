-- ============================================================
-- 要員の所属組織を「対象月時点の版」で解決できるようにする（V62）
--
-- 背景（第十四次Review P1-4）:
--   * design.md の帰属解決順は「1. t_engineer.organization_id（要員自身の所属組織） →
--     2. アカウント連携ユーザーの対象日時点の主所属（フォールバック限定）」だが、
--     Bench待機原価SQL（EngineerMapper.selectAccountingWaitCost系）は
--     アカウント連携側を先に見る逆順になっていた。
--   * t_engineer.organization_id 自体は現在値しか持たないため、締めが遅れている間に
--     組織統合・異動で要員の所属組織を付け替えると（reassignOrganization等）、
--     確定済み扱いの過去月へ新しい所属が焼き付く（V61が原価部門・想定単価で解決した
--     問題と同種）。
--
-- 既に本番ロールアウト済みの t_engineer_accounting_history（V61）を拡張し、
-- 要員の所属組織も同じ版管理テーブルへ同時に記録する。新規テーブルを作らないのは、
-- 原価部門・想定単価・所属組織はいずれも「要員の会計属性の版」という同じ粒度
-- （engineer_id, valid_from, valid_to）だからである。
-- ============================================================

ALTER TABLE t_engineer_accounting_history ADD COLUMN organization_id BIGINT AFTER engineer_id;
ALTER TABLE t_engineer_accounting_history
    ADD COLUMN organization_history_status VARCHAR(20) NOT NULL DEFAULT 'KNOWN' AFTER organization_id;

-- 既存の履歴行（V61のbackfillで作られた「現在値を初版とする」行）へ、要員の現在の
-- 所属組織を書き込む。ここで埋めないと、V62適用直後は現行版の organization_id が
-- 全てNULLになり、締め処理・待機原価集計が「未配賦」を返してしまう。
UPDATE t_engineer_accounting_history h
JOIN t_engineer e ON e.id = h.engineer_id
SET h.organization_id = e.organization_id
WHERE h.valid_to IS NULL
  AND h.organization_id IS NULL;

-- V61適用後に先に締められた履歴行は、V61〜V62間の異動元を復元できない。
-- 現在値やアカウント連携の組織を過去へ推測で書き戻さず、未配賦として明示する。
UPDATE t_engineer_accounting_history
SET organization_history_status = 'UNKNOWN'
WHERE valid_to IS NOT NULL
  AND organization_id IS NULL;
