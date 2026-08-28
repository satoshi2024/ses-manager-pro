# certification-learning-skill-gap

## 状態ガード

このspecはNF-03の準備成果物です。2026-08-28時点のtraceabilityは **`CANDIDATE`** である。approved scope・Base SHA・DG-03 実値は未承認のため、F1 以降と PR は禁止のままである。

直近Review（Head `aeab4077` 時点）はPLAN `FAIL`（P1-01: scope/Base/DG-03 実値未承認）、Implementation `NOT STARTED`。Task 0R-3 までの文書補正は完了している。

- 承認前にproduction code、migration、test、seed dataを変更しない。
- `tasks.md` は成功条件を証拠で確認できたTaskだけを `[x]` にする。
- F1以降はNF-03のtraceabilityが `APPROVED` になり、approved scope・base・DG-03の未決事項が確定してから開始する。
- PRはこの実装対話では作成しない。M完了後にReviewへremote HEAD、spec、tasks、完了対応表を渡す。

## 開発段階 Owner ポリシー

個人の実名は repository / `.kiro` / commit / test fixture に記録しない。責任主体は安定ロール **OwnerRef** で管理する。

| 項目 | 値 |
|---|---|
| OwnerRef | `PROJECT_OWNER` |
| OwnerDisplayName | `プロジェクト責任者` |
| OwnerType | `ROLE` |
| ApprovalMode | `ROLE_BASED_DEV` |
| DecisionId（本ポリシー） | `DG-03-DEV-20260828` |

詳細: [owner-policy.md](owner-policy.md)

**Gate 表現:** 「実名が必要」ではなく、**責任主体を一意に識別できる OwnerRef が必要**。承認証跡は DecisionId、決定日、対象 scope、Base SHA、承認 commit で追跡する（個人名は含めない）。

**`APPROVED` 遷移:** 残りの approved scope、Base、DG-03 実値が承認された時点で、Owner=`PROJECT_OWNER` として中央台帳を `CANDIDATE` → `APPROVED` へ更新する。本ポリシー確定だけでは `APPROVED` にしない。

## 実行対象

| 項目 | 値 |
|---|---|
| 専用worktree | `C:\work\ses-certification-learning-skill-gap` |
| branch | `codex/certification-learning-skill-gap` |
| base branch | `origin/main`（開始時点で `455fc92e` へfast-forward） |
| feature branch開始時migration | `V111__optimistic_lock_version_core_entities.sql`（base `455fc92e` 時点） |
| `origin/main` 現行migration | `V114` 付近（`76e45340`）。F1着手時は**実装branchのlatest+1を再確認** |
| 通常checkout | `C:\work\ses-manager-pro`。変更なしを維持 |

## 未承認 placeholder（`APPROVED` まで）

| 項目 | 状態 |
|---|---|
| approved scope | 未確定 |
| Base commit / branch | 技術比較 base `455fc92e`（承認 Base SHA は未記録） |
| DG-03 実値（6項目＋経費締め A/B） | 未確定 |

## 文書構成

- [owner-policy.md](owner-policy.md): 開発段階 OwnerRef・承認証跡・`APPROVED` 遷移条件。
- [inventory.md](inventory.md): 既存table/API/UI/serviceと重複master回避の調査結果。
- [requirements.md](requirements.md): 承認前のcandidate requirementsと受入条件。
- [design.md](design.md): candidate設計、scope、as-of、state、PII、DocumentLink、approval、AI境界。
- [plan.md](plan.md): 0→F1→F2→A1→A2→B1→B2→Mの実装順とゲート。
- [tasks.md](tasks.md): spec-driven task一覧。現時点で完了はTask 0・0R・0R-2・0R-3（文書のみ）。0R-4はOwnerポリシー文書化。
- [completion-matrix.md](completion-matrix.md): 要件・task・証拠・Demoの対応表。
- [review-remediation.md](review-remediation.md): PLAN Review指摘への対応状況。
