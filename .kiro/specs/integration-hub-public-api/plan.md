# NF-05 Public API 実装計画（Owner承認済み・R-NF05 Plan remediation中）

## 現在のゲート

NF-05はAPPROVEDであり、DG-05-F1-APPROVAL-20260830-01（2026-08-30）、OwnerRef=PROJECT_OWNER、
Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd、scope=GET-only 11 pathsとF1 persistence基盤を
approval-decision.mdへ固定した。R-NF05の独立Plan Reviewは固定Head 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ecに対して
PLAN FAIL（P0=0、P1=4）となったが、Owner Gateは再オープンしていない。以下のspec remediationを再Reviewで
確認するまで、F1 production code/migration/testを開始しない。

## 推奨順序

| 順序 | Task | 成果物 | 開始条件 |
|---|---|---|---|
| 0 | threat / contract / field inventory | approval ledger、filter/secret/outbox/correlation/rate/DTO inventory | 完了。production変更なし |
| 0R | Review remediation | atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review trace | docs-only範囲で完了。実装PASSではない |
| 0R-D | delta Review remediation | count/asOf/status-code/correlation header契約 | docs-only範囲で完了。実装PASSではない |
| 0R-P | R-NF05 Plan finding remediation | rate key、nonce ledger、delivery分離、retention/hold/restore contract | docs-only修正後、R-NF05再Review |
| F1 | client / credential / scope / idempotency DDL | Flyway、H2、migration evidence、rollback、purge | R-NF05 PLAN PASS、F1 scope、schema方針確認 |
| F2 | dedicated security chain | client principal、scope/data scope/command permission、audit、rate/IP | F1完了。公開endpointは別承認まで禁止 |
| A1 | v1 read APIs / OpenAPI | external DTO、cursor/count/error contract、contract tests | 別のimplementation scope承認 |
| A2 | limited command APIs | permission、idempotency、CAS、audit | 未承認。default deny |
| B1 | outbound webhook | subscription、signed event、claim/lease/retry/DLQ | 未承認。外部送信禁止 |
| B2 | inbound webhook / DLQ / admin UI | event uniqueness、replay、safe admin operations | 未承認。外部受信/UI禁止 |
| M | penetration / recovery / performance | review evidence、load、failure drill、runbook、fixed head | 全機能完了、security review PASS、追加承認 |

## R-NF05 P1 remediationの完了条件

1. rate/quotaの保存キーが client × scope × tenant × route template に固定され、IPやraw pathを含まず、
   minute/day/burstをDB uniqueと条件付きincrementで原子的に扱うこと。
2. t_api_nonce_replayのclient + nonce hash atomic unique、credential rotation跨ぎの再利用拒否、
   max(accepted_at, signed_timestamp) + 5分のTTL、bounded purgeを保存契約とすること。
3. 第二の汎用outboxを作らず、既存notification outbox/Accounting IntegrationJobを変更・二重書込みせず、
   t_api_deliveryをNF-05専用delivery ledgerとして分離し、event/subscription/generation uniqueと
   atomic insert、claim/HTTP/CAS境界を固定すること。
4. retention_class、retention_expires_at、t_api_retention_hold、t_api_purge_checkpointの保存モデルと、
   hold/purgeのlock/CAS競合、active lease、部分失敗、restore epoch後の全件再評価を固定すること。

これらはPlan ReviewをPASS扱いにする自己判定ではない。0R-P docs-only commit後、R-NF05が独立に再判定する。

## 完了・引き渡し条件

各Taskは専用branchで検証し、Task単位のcommitを作る。production変更のpushはapproved scopeに含まれるremoteだけに限定し、
force pushは行わない。明示されたReview remediationのdocs-only commit/pushはレビュー証跡固定のために行う。
最終remote Head、commit一覧、plan/spec/tasks、completion matrix、evidence indexを
独立Reviewへ渡す。ReviewのPLAN/IMPLEMENTATION双方がPASSになるまでPRは作成しない。
