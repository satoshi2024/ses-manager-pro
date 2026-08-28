# 実装 plan（approved）

## Gate 0: 承認・Base 取り込み（完了）

1. worktree/root/branch/status/remote/base を検証する。
2. 指定された依存 spec と NF-03 の backlog/requirements-design/traceability を読む。
3. 資格・skill・career・training・staffing・document・approval・self-service を inventory する。
4. 重複 master 回避、PII、DocumentLink、taxonomy、as-of、費用 approval、AI 境界を decision table にする。
5. `tasks.md` と完了対応表を作成する。
6. **Owner 承認:** DecisionId `DG-03-SCOPE-APPROVAL-20260828-01` で approved scope・DG-03 実値・Base `76e45340` を記録し、traceability を `APPROVED` へ遷移。
7. **`origin/main@76e45340` を feature branch へ merge**（旧 merge-base `455fc92e` は承認 Base として不使用）。
8. PLAN Review を Gate 0 Head で再実行し、PASS 後に F1 へ進む。

Gate 0 は production の NF-03 実装変更なし（Base merge のみ）。Task 0/0R/0G の commit/push で完了。

承認前 remediation で固定した契約:

- supply (`t_engineer_skill`)、project skill、position demand は current projection と append-only effective event を併存。履歴欠落時は current fallback しない。
- `CERTIFICATION_EVIDENCE` は `CERTIFICATION_RECORD` typed DocumentLink のみ。mixed-link OR-union を restricted policy で遮断。
- course 予定額は learning plan の申請時 snapshot。actual cost・payment・accounting は既存 `t_expense_request` 正本。
- `CORRECTED` は資格 current status ではなく event。renew は continuity group の新 record。
- notification key は semantic expiry date＋threshold＋recipient。注入 Clock、lifecycle population、DB unique＋outbox claim。
- SELF/MANAGER/HR_FINAL assessment と人の decision event を分離。AI から評価・配置・採否・不利益判断への直接遷移を禁止。
- 既存 skill/position 書込み（`EngineerSkillServiceImpl`/`ProjectSkillServiceImpl`/`PositionServiceImpl`、`delete` 含む）を as-of event の必須フック対象とする。
- `FileScopeValidationService` へ `CERTIFICATION_EVIDENCE` 専用分岐を追加。empty-link・admin bypass を資格証憑で禁止。
- **経費締めは選択肢 A（`ExpenseRequestServiceImpl` 共有化）— DG-03-5 で確定。**

## Gate 1: F1 DDL（PLAN PASS 後）

資格 master、engineer 取得 record、append-only event、course、course-skill、learning plan、plan-skill、enrollment、effective history、assessment、decision event を確定する。mainのV115を保持し、NF-03 migration番号は **V116+**（承認Baseからの次）。V1/H2 専用 schema/entity 同期を設計に従って実施。PII field、自然同一性、continuity/current unique、version/CAS、exact document version、expense relation を先に固定する。

## Gate 2: F2 service（F1 後）

取得・期限・cancel/correct/renew・duplicate 防止、course/plan/enrollment state、既存 ExpenseRequest 連携、typed DocumentLink、approval adapter、effective as-of skill gap、synonym/unknown、rule fallback、scheduler population/dedupe、人の assessment を実装する。

## Gate 3: A1/A2 UI（F2 後）

- A1: HR/manager の資格、期限、training、gap list/detail。
- A2: 本人の取得申請、証憑、learning plan、enrollment。

## Gate 4: B1/B2 連携（A 後）

- B1: 90/60/30 通知、approval route、DocumentService/scan/legal hold/download。
- B2: staffing demand との as-of 連携、taxonomy alias、unknown、AI candidate。

## Gate 5: M completion

mandatory tests、Demo evidence、population matrix、migration/H2/MySQL gates、security/scope、remote HEAD を確認。M 後にのみ Review packet を作成。Review の PLAN/IMPLEMENTATION 双方 PASS 後に PR 作成。

## 変更許可ゲート（Gate 0 完了状況）

| 項目 | Status |
|---|---|
| NF-03 traceability `APPROVED` | ✅ `DG-03-SCOPE-APPROVAL-20260828-01` |
| approved scope | ✅ [approval-decision.md](approval-decision.md) |
| OwnerRef | ✅ `PROJECT_OWNER` |
| Base commit | ✅ `76e45340`（merge 済み） |
| DG-03 実値（6 項目＋経費 A） | ✅ approval-decision.md |
| PLAN Review PASS | Gate 0 Head で再判定（F1 前必須） |

承認証跡は DecisionId、決定日、OwnerRef、対象 scope、Base SHA、承認 commit で追跡する。
