# 完了対応表（NF-03 candidate）

## Status

| 項目 | 値 |
|---|---|
| traceability | `CANDIDATE`（`2026-08-27-post-acceptance-traceability.md` NF-03） |
| production変更 | なし。Task 0/0R/0R-2/0R-3はspec文書のみ |
| worktree | `C:\work\ses-certification-learning-skill-gap` |
| branch | `codex/certification-learning-skill-gap` |
| base | `origin/main` / `455fc92e` |
| PR | 作成しない |

Review remediation status: R1（P1-02〜P1-07、P2-01〜P2-02）、R2（P1-08〜P1-10、P2-03〜P2-06）、R3（P2-03残件・P2-08）をcandidate spec/taskへ具体化済み。P1-01は外部blockerのまま。

## Task対応

| Task | 要件 | 成果物/実装 | test | Demo | status |
|---|---|---|---|---|---|
| 0 | 全体準備、重複回避、DG-03 table | `inventory.md`、`requirements.md`、`design.md`、`plan.md`、`tasks.md` | `git diff --check`、production変更なし、traceability再確認 | worktree/remote/base、source inventory、未決事項を確認 | [x] |
| 0R | Review P1/P2 remediation (R1) | `review-remediation.md` + spec更新 | spec内ID整合、production変更なし | 指摘1行ごとの対応表と外部blocker確認 | [x] |
| 0R-2 | Review R2 remediation | `inventory.md` §5.4/§5.5、`design.md` §3.4/§3.6-§3.9、`tasks.md` F1-2/F1-4/F2-2〜4/B1、`review-remediation.md` R2表 | 実在ファイル参照、R8残留なし、`git diff --check` | P1-08〜10・P2-03〜06の1行対応、F1禁止継続 | [x] |
| 0R-3 | Review R3 remediation | `completion-matrix.md`、`plan.md` Gate 0、`design.md` §3.4手順3、`inventory.md` §5.4 delete、`tasks.md` F1-4/F2-3/F2-5/A1、`review-remediation.md` R3表 | P2-03/P2-08対応、`git diff --check` | R3 finding 1行対応、F1禁止継続 | [x] |
| F1-1 | R1/R5 | 未着手（承認待ち） | 未着手 | 未着手 | [ ] |
| F1-2 | R1/R5/R6 | 未着手（承認待ち） | 未着手 | 未着手 | [ ] |
| F1-3 | R2/R5 | 未着手（承認待ち） | 未着手 | 未着手 | [ ] |
| F1-4 | R3/R6 | 未着手（承認待ち） | 未着手 | 未着手 | [ ] |
| F1-5 | R7 | 未着手（承認待ち） | 未着手 | 未着手 | [ ] |
| F2-1 | R1/R6 | 未着手（承認待ち） | 90/60/30、取消/訂正、重複、再実行 | 期限通知境界 | [ ] |
| F2-2 | R2/R6 | 未着手（承認待ち） | threshold、自己承認、CAS | approval後completion | [ ] |
| F2-3 | R3/R7 | 未着手（承認待ち） | as-of、synonym、unknown、period、0件、AI停止 | rule fallback／AI候補のみ | [ ] |
| F2-4 | R1/R6 | 未着手（承認待ち） | scheduler、timezone、lifecycle、複数JVM | semantic dedupe／復職通知 | [ ] |
| F2-5 | R3/R7 | 未着手（承認待ち） | assessment分離、AI timeout、adverse禁止 | human finalのみ | [ ] |
| A1 | R4/R5 | 未着手（承認待ち） | role scope、mask、responsive | list/detail/export一致 | [ ] |
| A2 | R1/R2/R4/R5 | 未着手（承認待ち） | account-link、他人ID、証憑 | 本人申請/plan | [ ] |
| B1 | R1/R2/R5/R6 | 未着手（承認待ち） | notification、approval、document scope | scheduler再実行/拒否 | [ ] |
| B2 | R3/R7 | 未着手（承認待ち） | staffing as-of、AI error/timeout | demand期間変更／unknown／最終配置なし | [ ] |
| M | 全要件 | `review-packet.md`は未作成 | 全CI相当gate未実施 | 全mandatory Demo未実施 | [ ] |

## 完了判定

Taskを `[x]` にするには、対応する実装commit、required testの実行結果、Demo evidence、scope/PII/document/approval/AI境界の証拠をこの表へ追記する。F1以降はNF-03の `APPROVED` とDG-03のdecision確定が先行条件である。Task 0/0R/0R-2/0R-3の文書補正が`[x]`であっても、PLAN PASSやproduction実装開始を意味しない。
