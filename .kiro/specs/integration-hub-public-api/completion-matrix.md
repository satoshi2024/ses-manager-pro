# NF-05 完了対応表（Discovery時点）

## Task対応

| Task | 対応spec | 実装/テスト証跡 | 状態 | commit / remote |
|---|---|---|---|---|
| 0 Discovery | README, plan, requirements, design, inventory, review-ledger | worktree/base/status検証、read-only棚卸し、git diff check | COMPLETE（production変更なし） | b085c47f → 6e0f5067（remote固定済み） |
| 0R Review remediation | design, openapi-candidate, review-remediation, requirements, tasks, review-ledger | atomic outbox、candidate contract、metrics、retention、docs-only検証 | COMPLETE（spec修正のみ） | 48037c92（remoteへpush済み） |
| 0R-D Delta Review remediation | openapi-candidate, design, tasks, review-remediation, review-ledger | count/asOf/status-code/correlation headerの差分修正、YAML/assertion | COMPLETE（spec修正のみ） | 11ee82c1（remoteへpush済み） |
| F1 DDL | tasks/design | 未着手 | BLOCKED_BY_APPROVAL | — |
| F2 security chain | tasks/design/inventory | 未着手 | BLOCKED_BY_APPROVAL | — |
| A1 read/OpenAPI | tasks/design/requirements/openapi-candidate | candidateのみ。実装未着手 | BLOCKED_BY_APPROVAL | — |
| A2 commands | tasks/design/requirements | 未着手 | BLOCKED_BY_APPROVAL | — |
| B1 outbound webhook | tasks/design/inventory | 未着手 | BLOCKED_BY_APPROVAL | — |
| B2 inbound/DLQ/admin UI | tasks/design/requirements | 未着手 | BLOCKED_BY_APPROVAL | — |
| M verification | tasks/design | 未着手 | BLOCKED_BY_APPROVAL | — |

## Review handoff

現時点では独立Reviewへ渡せるのはDiscovery/Task 0R文書、非公開OpenAPI candidate、approval blocker一覧、
comparison base、通常checkout非変更、remote固定の証跡だけである。production implementation、migration、
test reportは存在しない。DG-05とBaseが承認された後にPLAN Reviewを行い、計画PASS後に実装を開始する。
PLAN/IMPLEMENTATION双方PASS前のPR作成は禁止する。

Review Head 6e0f5067はremediationの比較基点として固定する。Task 0Rのremediation commitと最終remote Headは
commit series＋外部handoff通知で固定し、自己参照hashはcompletion matrixへ埋め込まない。

Review baseline: 6e0f5067d9a6509775225278cc0dcfdc4d47643f
Task 0R remediation: 48037c923224f684968dbaf3410cdb37307ed100
Task 0R-D delta remediation: 11ee82c15a5cdf8f961b2a2d0518a52d81f4de71
Final remote Head: 外部handoff通知で固定（この行を含むcommit自身のhashは自己参照しない）
