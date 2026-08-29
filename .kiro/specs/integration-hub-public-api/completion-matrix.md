# NF-05 完了対応表（Discovery時点）

## Task対応

| Task | 対応spec | 実装/テスト証跡 | 状態 | commit / remote |
|---|---|---|---|---|
| 0 Discovery | README, plan, requirements, design, inventory, review-ledger | worktree/base/status検証、read-only棚卸し、git diff check | local artifact completed; commit pending | 未確定 |
| F1 DDL | tasks/design | 未着手 | BLOCKED_BY_APPROVAL | — |
| F2 security chain | tasks/design/inventory | 未着手 | BLOCKED_BY_APPROVAL | — |
| A1 read/OpenAPI | tasks/design/requirements | 未着手 | BLOCKED_BY_APPROVAL | — |
| A2 commands | tasks/design/requirements | 未着手 | BLOCKED_BY_APPROVAL | — |
| B1 outbound webhook | tasks/design/inventory | 未着手 | BLOCKED_BY_APPROVAL | — |
| B2 inbound/DLQ/admin UI | tasks/design/requirements | 未着手 | BLOCKED_BY_APPROVAL | — |
| M verification | tasks/design | 未着手 | BLOCKED_BY_APPROVAL | — |

## Review handoff

現時点では独立Reviewへ渡せるのはDiscovery文書、approval blocker一覧、comparison base、通常checkout非変更の証跡だけである。
implementation commit、push済みremote Head、OpenAPI、migration、test reportは存在しない。DG-05とBaseが承認された後に
PLAN Reviewを行い、計画PASS後に実装を開始する。PLAN/IMPLEMENTATION双方PASS前のPR作成は禁止する。
