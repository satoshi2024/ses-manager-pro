# NF-05 完了対応表（Owner承認済み・Plan Review待ち）

## Task対応

| Task | 対応spec | 実装/テスト証跡 | 状態 | commit / remote |
|---|---|---|---|---|
| 0 Discovery | README, plan, requirements, design, inventory, review-ledger | worktree/base/status検証、read-only棚卸し、git diff check | COMPLETE（production変更なし） | b085c47f → 6e0f5067（remote固定済み） |
| 0R Review remediation | design, openapi-candidate, review-remediation, requirements, tasks, review-ledger | atomic outbox、candidate contract、metrics、retention、docs-only検証 | COMPLETE（spec修正のみ） | 48037c92（remoteへpush済み） |
| 0R-D Delta Review remediation | openapi-candidate, design, tasks, review-remediation, review-ledger | count/asOf/status-code/correlation headerの差分修正、YAML/assertion | COMPLETE（spec修正のみ） | 11ee82c1（remoteへpush済み） |
| 0/0R/0R-D Owner Gate normalization | approval-decision、README、plan、requirements、design、tasks、inventory、review trace、中央traceability | DG-05 DecisionId、OwnerRef、approved Base、F1 scope、auth/SLA/field/threat valuesを正本化。production変更なし | COMPLETE（docs-only gate） | 2f91e5a584c5224989780cb323e40f33fda185b6（remoteへpush済み） |
| F1 DDL | approval-decision, tasks, design | 未着手。PLAN PASS待ち | READY_AFTER_PLAN_PASS | — |
| F2 security chain | tasks/design/inventory | 未着手 | DEFERRED_BY_SCOPE | — |
| A1 read/OpenAPI | tasks/design/requirements/openapi-candidate | candidateのみ。public endpoint未実装 | DEFERRED_BY_SCOPE | — |
| A2 commands | tasks/design/requirements | command/export未承認・default deny | DISABLED | — |
| B1 outbound webhook | tasks/design/inventory | persistence contractのみ承認。外部送信未着手 | DEFERRED_BY_SCOPE | — |
| B2 inbound/DLQ/admin UI | tasks/design/requirements | persistence contractのみ承認。外部受信/UI未着手 | DEFERRED_BY_SCOPE | — |
| M verification | tasks/design | 未着手 | AFTER_IMPLEMENTATION | — |

## Review handoff

現時点では独立Plan Reviewへ渡せるのはapproval-decision、Discovery/Task 0R/0R-D文書、非公開OpenAPI candidate、
承認済みscope、comparison base、通常checkout非変更、remote固定の証跡である。production implementation、
migration、test reportは存在しない。PLAN PASS後にF1を開始する。
PLAN/IMPLEMENTATION双方PASS前のPR作成は禁止する。

Review Head 6e0f5067はremediationの比較基点として固定する。Task 0Rのremediation commit、Owner Gate normalization
commit、最終remote Headはcommit series＋外部handoff通知で固定し、自己参照hashはcompletion matrixへ埋め込まない。

Review baseline: 6e0f5067d9a6509775225278cc0dcfdc4d47643f
Task 0R remediation: 48037c923224f684968dbaf3410cdb37307ed100
Task 0R-D delta remediation: 11ee82c15a5cdf8f961b2a2d0518a52d81f4de71
Owner Gate normalization: 2f91e5a584c5224989780cb323e40f33fda185b6
Final remote Head: 外部handoff通知で固定（この行を含むcommit自身のhashは自己参照しない）
