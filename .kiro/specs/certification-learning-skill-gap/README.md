# certification-learning-skill-gap

## 状態ガード

このspecはNF-03の準備成果物です。2026-08-28時点のtraceabilityは `CANDIDATE` であり、`<APPROVED_SCOPE>`、`<OWNER>`、`<BASE_COMMIT>`、`<BASE_BRANCH>` は実値で承認されていません。

直近Review（Head `928ea518` 時点）はPLAN `FAIL`、Implementation `NOT STARTED`です。Task 0R-2（R2 Review文書補正）まで完了。Ownerによる実承認を代行せず、PLAN PASSとは扱いません。

- 承認前にproduction code、migration、test、seed dataを変更しない。
- 本ディレクトリのinventory/spec/tasks作成と、読み取り専用の確認だけを許可する。
- `tasks.md` は成功条件を証拠で確認できたTaskだけを `[x]` にする。
- F1以降はNF-03のtraceabilityが `APPROVED` になり、Owner、scope、base、DG-03の未決事項が確定してから開始する。
- PRはこの実装対話では作成しない。M完了後にReviewへremote HEAD、spec、tasks、完了対応表を渡し、PLAN/IMPLEMENTATIONの双方がPASSになった後の別工程で作成する。

## 実行対象

| 項目 | 値 |
|---|---|
| 専用worktree | `C:\work\ses-certification-learning-skill-gap` |
| branch | `codex/certification-learning-skill-gap` |
| base branch | `origin/main`（開始時点で `455fc92e` へfast-forward） |
| feature branch開始時migration | `V111__optimistic_lock_version_core_entities.sql`（base `455fc92e` 時点） |
| `origin/main` 現行migration | `V114` 付近（`76e45340`）。F1着手時は**実装branchのlatest+1を再確認**し、本表の固定値に依存しない |
| 通常checkout | `C:\work\ses-manager-pro`。開始時・Task 0/0R完了時とも変更なしを確認 |

## 文書構成

- [inventory.md](inventory.md): 既存table/API/UI/serviceと重複master回避の調査結果。
- [requirements.md](requirements.md): 承認前のcandidate requirementsと受入条件。
- [design.md](design.md): candidate設計、scope、as-of、state、PII、DocumentLink、approval、AI境界。
- [plan.md](plan.md): 0→F1→F2→A1→A2→B1→B2→Mの実装順とゲート。
- [tasks.md](tasks.md): spec-driven task一覧。現時点で完了はTask 0・0R・0R-2・0R-3（文書のみ）。
- [completion-matrix.md](completion-matrix.md): 要件・task・証拠・Demoの対応表。
- [review-remediation.md](review-remediation.md): 今回のPLAN Review指摘への対応状況と、外部承認が必要な残件。
