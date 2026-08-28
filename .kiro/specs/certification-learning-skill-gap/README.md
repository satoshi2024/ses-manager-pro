# certification-learning-skill-gap

## 状態ガード

NF-03 traceability は **`APPROVED`**（DecisionId `DG-03-SCOPE-APPROVAL-20260828-01`、2026-08-28、OwnerRef=`PROJECT_OWNER`）。Gate 0（承認記録・Base 取り込み）完了後、**PLAN Review PASS** を得てから F1 を開始する。PR は M 完了＋Implementation Review PASS 後。

- 承認前に変更していた production code / migration / test は本 feature 用ではない（Base 取り込みの merge のみ）。
- `tasks.md` は成功条件を証拠で確認できた Task だけを `[x]` にする。
- PR はこの実装対話では作成しない。M 完了後に Review へ remote HEAD、spec、tasks、完了対応表を渡す。

## 承認 Decision

| 項目 | 値 |
|---|---|
| DecisionId | `DG-03-SCOPE-APPROVAL-20260828-01` |
| 決定日 | `2026-08-28` |
| OwnerRef | `PROJECT_OWNER` |
| OwnerType | `ROLE` |
| ApprovalMode | `ROLE_BASED_DEV` |
| Base branch | `origin/main` |
| Base commit | `76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| 旧 merge-base | `455fc92e`（承認 Base として不使用） |

詳細: [approval-decision.md](approval-decision.md)

## 開発段階 Owner ポリシー

個人の実名は repository / `.kiro` / commit / test fixture に記録しない。責任主体は安定ロール **OwnerRef** で管理する。

| 項目 | 値 |
|---|---|
| OwnerRef | `PROJECT_OWNER` |
| OwnerDisplayName | `プロジェクト責任者` |
| OwnerType | `ROLE` |
| ApprovalMode | `ROLE_BASED_DEV` |
| DecisionId（Owner 識別） | `DG-03-DEV-20260828` |

詳細: [owner-policy.md](owner-policy.md)

**Gate 表現:** 「実名が必要」ではなく、**責任主体を一意に識別できる OwnerRef が必要**。承認証跡は DecisionId、決定日、OwnerRef、対象 scope、Base SHA、承認 commit で追跡する（個人名は含めない）。

## 実行対象

| 項目 | 値 |
|---|---|
| 専用 worktree | `C:\work\ses-certification-learning-skill-gap` |
| branch | `codex/certification-learning-skill-gap` |
| 承認 Base | `origin/main@76e45340` |
| 現行 migration（Base 取り込み後） | mainの`V115__pwa_client_mutation_ledger.sql`を保持。NF-03は **V116+**（現行V127） |
| 通常 checkout | `C:\work\ses-manager-pro`。変更なしを維持 |

## Approved scope（要約）

**In scope:** 資格 master/取得/期限/証憑、course/plan/enrollment、既存 ExpenseRequest 研修費、90/60/30 通知、as-of skill gap、rule-based gap＋AI 候補、本人/manager/HR workflow。

**Out of scope:** 外部 LMS 自動連携、AI 自動評価/配置、AI による採否・昇格・給与・不利益判断。

## 文書構成

- [approval-decision.md](approval-decision.md): 開発開始承認 Decision 全文（DG-03-1〜6、scope、Base）。
- [owner-policy.md](owner-policy.md): 開発段階 OwnerRef・承認証跡。
- [inventory.md](inventory.md): 既存 table/API/UI/service と重複 master 回避の調査結果。
- [requirements.md](requirements.md): 承認済み requirements と受入条件。
- [design.md](design.md): 承認済み設計、scope、as-of、state、PII、DocumentLink、approval、AI 境界。
- [plan.md](plan.md): 0→F1→F2→A1→A2→B1→B2→M の実装順とゲート。
- [tasks.md](tasks.md): spec-driven task 一覧。
- [completion-matrix.md](completion-matrix.md): 要件・task・証拠・Demo の対応表。
- [review-remediation.md](review-remediation.md): PLAN Review 指摘への対応状況。
