# NF-05 Public API 実装計画（未承認ドラフト）

## 現在のゲート

NF-05はCANDIDATEであり、DG-05は未承認である。承認済みresources/commands、Owner、Base branch、Base commit、
threat model、認証方式、契約SLA、public field inventoryの確定記録がないため、production変更は開始しない。
この計画はDiscovery成果物としてのレビュー対象であり、approved planではない。

## 推奨順序

| 順序 | Task | 成果物 | 開始条件 |
|---|---|---|---|
| 0 | threat / contract / field inventory | approval ledger、filter/secret/outbox/correlation/rate/DTO inventory | 完了。production変更なし |
| 0R | Review remediation | atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review trace | docs-only範囲で完了。実装PASSではない |
| F1 | client / credential / scope / idempotency DDL | Flyway、H2、migration evidence、rollback | DG-05、Base、Owner、schema方針承認 |
| F2 | dedicated security chain | client principal、scope/data scope/command permission、audit、rate/IP | F1完了とauth/IP承認 |
| A1 | v1 read APIs / OpenAPI | external DTO、cursor/count/error contract、contract tests | field/resource/SLA承認 |
| A2 | limited command APIs | permission、idempotency、CAS、audit | command一覧と業務承認 |
| B1 | outbound webhook | subscription、signed event、claim/lease/retry/DLQ | delivery SLA/signature/retry承認 |
| B2 | inbound webhook / DLQ / admin UI | event uniqueness、replay、safe admin operations | provider contractと運用承認 |
| M | penetration / recovery / performance | review evidence、load、failure drill、runbook、fixed head | 全機能完了、security review PASS |

## 完了・引き渡し条件

各Taskは専用branchで検証し、Task単位のcommitを作る。production変更のpushはapproved scopeに含まれるremoteだけに限定し、
force pushは行わない。明示されたReview remediationのdocs-only commit/pushはレビュー証跡固定のために行う。
最終remote Head、commit一覧、plan/spec/tasks、completion matrix、evidence indexを
独立Reviewへ渡す。ReviewのPLAN/IMPLEMENTATION双方がPASSになるまでPRは作成しない。
