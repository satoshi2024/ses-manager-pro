# NF-05 完了対応表（Owner承認済み・R-NF05 Plan PASS・F1 implementation remediation中）

## Task対応

| Task | 対応spec | 実装/テスト証跡 | 状態 | commit / remote |
|---|---|---|---|---|
| 0 Discovery | README, plan, requirements, design, inventory, review-ledger | worktree/base/status検証、read-only棚卸し、git diff check | COMPLETE（production変更なし） | b085c47f → 6e0f5067（remote固定済み） |
| 0R Review remediation | design, openapi-candidate, review-remediation, requirements, tasks, review-ledger | atomic outbox、candidate contract、metrics、retention、docs-only検証 | COMPLETE（spec修正のみ） | 48037c92（remoteへpush済み） |
| 0R-D Delta Review remediation | openapi-candidate, design, tasks, review-remediation, review-ledger | count/asOf/status-code/correlation headerの差分修正、YAML/assertion | COMPLETE（spec修正のみ） | 11ee82c1（remoteへpush済み） |
| 0/0R/0R-D Owner Gate normalization | approval-decision、README、plan、requirements、design、tasks、inventory、review trace、中央traceability | DG-05 DecisionId、OwnerRef、approved Base、F1 scope、auth/SLA/field/threat valuesを正本化。production変更なし | COMPLETE（docs-only gate） | 2f91e5a584c5224989780cb323e40f33fda185b6（remoteへpush済み） |
| 0R-P R-NF05 Plan finding remediation | plan, design, requirements, tasks, inventory, review-ledger, review-remediation | P1-001 rate key、P1-002 nonce ledger、P1-003 delivery分離、P1-004 retention/hold/restoreを具体化。production変更なし | SPEC_ADDRESSED（再Reviewでnonce/delivery closed、rate/retention残存） | b0151e7d8acc54da124c4464db1df263e4b3f716（remoteへpush済み） |
| 0R-P2 R-NF05 residual Plan remediation | plan, design, requirements, tasks, inventory, review-ledger, review-remediation | P1-005 burst algorithm、P1-006 canonical state/terminal retention mappingを具体化。production変更なし | SPEC_ADDRESSED（state alias残存を追加補正） | a3b63d70f53bc799d1abcb6e26e34ad163aa9843（remoteへpush済み） |
| 0R-P3 R-NF05 state mapping cleanup | design, tasks | design内の旧RETRY/SENT/EXPIRED aliasをcanonical enum・retention mappingから除去。production変更なし | COMPLETE（docs-only） | fdea4bb18db3d3ae6542dc0c534425783dd28a24（remoteへpush済み） |
| 0R-P4 R-NF05 final Plan Review | review-ledger, review-remediation, completion-matrix | 独立ReviewがP0=0、P1=0、P2=2でPLAN PASS。P2は非blocking | COMPLETE（Review gate） | 1db3b2fc2657831b7c6c1e59217301302b7caa80（fixed review Head） |
| F1 DDL | approval-decision, tasks, design, V129, H2 schema, entity/mapper/service/crypto, F1 tests | client/credential/scope/idempotency/usage bucket/nonce/webhook/retention persistence基盤。初回review指摘をtyped boundary、conflict/CAS、purge、route/overlapでremediate。H2 F1 31 tests、MySQL concurrency 3 tests、Flyway smoke PASS | IMPLEMENTED_PENDING_REVIEW | initial `a7654b44`、remediation `a184c1f4`、CAS correction `d476614e`、再Review待ち |
| F1 Implementation Review remediation | requirements, design, tasks, review-ledger, review-remediation, implementation/tests | 初回FAIL（P1=7、P2=2）への実装・テスト対応。delivery_generation predicateを追加。public endpoint/外部送信/F2以降は未実装 | IMPLEMENTED_PENDING_REVIEW | `a184c1f4` + `d476614e` → 独立再Reviewへhandoff |
| F2 security chain | tasks/design/inventory | 未着手 | DEFERRED_BY_SCOPE | — |
| A1 read/OpenAPI | tasks/design/requirements/openapi-candidate | candidateのみ。public endpoint未実装 | DEFERRED_BY_SCOPE | — |
| A2 commands | tasks/design/requirements | command/export未承認・default deny | DISABLED | — |
| B1 outbound webhook | tasks/design/inventory | persistence contractのみ承認。外部送信未着手 | DEFERRED_BY_SCOPE | — |
| B2 inbound/DLQ/admin UI | tasks/design/requirements | persistence contractのみ承認。外部受信/UI未着手 | DEFERRED_BY_SCOPE | — |
| M verification | tasks/design | 未着手 | AFTER_IMPLEMENTATION | — |

## Review handoff

R-NF05の固定Head 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ecはPLAN FAIL（P0=0、P1=4）、そのremediation後の
678eac3f09b7ed54419655fcf326e0b15c6d7d62もPLAN FAIL（P0=0、P1=2）だった。最終的に固定Head
1db3b2fc2657831b7c6c1e59217301302b7caa80でPLAN PASS（P0=0、P1=0、P2=2）を受領し、F1を開始した。
PLAN/IMPLEMENTATION双方PASS前のPR作成は禁止する。

Review Head 6e0f5067はremediationの比較基点として固定する。Task 0Rのremediation commit、Owner Gate normalization
commit、最終remote Headはcommit series＋外部handoff通知で固定し、自己参照hashはcompletion matrixへ埋め込まない。

Review baseline: 6e0f5067d9a6509775225278cc0dcfdc4d47643f
Task 0R remediation: 48037c923224f684968dbaf3410cdb37307ed100
Task 0R-D delta remediation: 11ee82c15a5cdf8f961b2a2d0518a52d81f4de71
Owner Gate normalization: 2f91e5a584c5224989780cb323e40f33fda185b6
R-NF05 Plan remediation: b0151e7d8acc54da124c4464db1df263e4b3f716
R-NF05 residual remediation: a3b63d70f53bc799d1abcb6e26e34ad163aa9843
R-NF05 state mapping cleanup: fdea4bb18db3d3ae6542dc0c534425783dd28a24
Final remote Head: 外部handoff通知で固定（この行を含むcommit自身のhashは自己参照しない）

## F1実装証跡

- Implementation commit: `a7654b44`（`feat: implement NF-05 F1 persistence foundation`）
- Remediation commit: `a184c1f4`（`fix: remediate NF-05 F1 implementation review findings`）
- Delivery CAS correction: `d476614e`（`fix: bind delivery CAS to generation`）
- MySQL: `FlywayMigrationSmokeTest`を`-Pmysql-tests`で実行し、empty/legacy V78/normal DBのV129適用を確認。
- H2: `IntegrationHubF1SchemaH2Test`、`IntegrationHubF1RetentionH2Test`および既存schema sweepを確認。
- F1 targeted suite: 31 tests、failure 0、error 0、skip 0。
- MySQL concurrency: `IntegrationHubF1MySqlConcurrencyTest` 3 tests、failure 0、error 0、skip 0。
- 全fast suite: F1失敗なし。ただし既存loopback接続・production-config系10 errors、既存規約/cache headerの
  2 failuresが残るため、全体PASSとは扱わない。
- 初回Implementation Review: fixed Head `b420911b63177763544edd1e02d663bf528d9dc1`、FAIL（P0=0、P1=7、P2=2）。
- 状態: F1 remediation実装済み・独立Implementation Review再Review待ち。F2以降とproduction enablementは未着手。
