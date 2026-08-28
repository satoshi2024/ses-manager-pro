# 完了対応表（NF-03 approved）

## Status

| 項目 | 値 |
|---|---|
| traceability | `APPROVED`（DecisionId `DG-03-SCOPE-APPROVAL-20260828-01`） |
| OwnerRef | `PROJECT_OWNER` |
| 承認 Base | `origin/main@76e45340` |
| Base merge commit | `5d4b5c27` |
| Gate 0 承認 commit | `03545127` |
| remote HEAD | `1f459267`（SHA 追記後） |
| production 変更（NF-03） | なし（Base merge のみ）。F1 未着手 |
| worktree | `C:\work\ses-certification-learning-skill-gap` |
| branch | `codex/certification-learning-skill-gap` |
| migration（F1 開始時） | `V115+`（現行 latest `V114`） |
| PR | 作成しない |

Review remediation: P1-01 **VERIFIED_CLOSED**（0G）。P1-02〜P1-10 は承認値反映済み — 実装証明は F1〜M。PLAN Review R6 は Gate 0 Head で再判定。

## Task対応

| Task | 要件 | 成果物/実装 | test | Demo | status |
|---|---|---|---|---|---|
| 0 | 全体準備 | inventory/requirements/design/plan/tasks/completion-matrix | `git diff --check`、production 変更なし | worktree/remote/base | [x] |
| 0R | R1 remediation | review-remediation + spec | spec ID 整合 | P1/P2 対応表 | [x] |
| 0R-2 | R2 remediation | inventory/design/tasks | 実在ファイル参照 | P1-08〜10、P2-03〜06 | [x] |
| 0R-3 | R3 remediation | completion-matrix/plan/design | P2-03/P2-08 | R3 finding 対応 | [x] |
| 0R-4 | Owner ポリシー | owner-policy.md | OwnerRef、実名非記録 | P1-01a 分離 | [x] |
| 0G | Gate 0 承認 | approval-decision.md、traceability `APPROVED`、main merge | Decision 証跡、Base `76e45340` | scope/DG-03/Base 一致 | [x] |
| F1-1 | R1/R5 | 未着手（PLAN PASS 待ち） | — | — | [ ] |
| F1-2 | R1/R5/R6 | 未着手 | — | — | [ ] |
| F1-3 | R2/R5 | 未着手 | — | — | [ ] |
| F1-4 | R3/R6 | 未着手 | — | — | [ ] |
| F1-5 | R7 | 未着手 | — | — | [ ] |
| F2-1〜F2-5 | R1〜R7 | 未着手 | — | — | [ ] |
| A1/A2 | R4/R5 | 未着手 | — | — | [ ] |
| B1/B2 | R1〜R7 | 未着手 | — | — | [ ] |
| M | 全要件 | review-packet 未作成 | CI 未実施 | Demo 未実施 | [ ] |

## 完了判定

Task を `[x]` にするには、対応する実装 commit、required test、Demo evidence を追記する。F1 以降は PLAN Review PASS が先行条件。Task 0G の `[x]` は Gate 0 文書＋Base merge の完了を意味し、Implementation Review PASS ではない。
