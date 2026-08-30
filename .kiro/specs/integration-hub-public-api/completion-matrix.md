# NF-05 完了対応表（scope expansion承認済み・F2/A1 PASS・B1独立Review待ち）

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
| 0R-P5 Scope expansion Plan delta remediation | README、plan、requirements、design、tasks、inventory、review-ledger、review-remediation、中央traceability | dedicated chain、HMAC byte canonical、production fail-closed、mock/loopback destination、A2 N/A、current traceを補正。production変更なし | SPEC_ADDRESSED（P1-EXP-004/P2-EXP-005/006はReviewでCLOSED。残存P1あり） | 8d25215b9b651e99433becf50d13498da3699d2a（remoteへpush済み） |
| 0R-P6 Scope expansion Plan delta residual remediation | README、plan、requirements、design、tasks、inventory、review-ledger、review-remediation、中央traceability | security chain監査/error boundary、canonicalTarget完全byte手順（wire header、keyId、header/target/body上限、golden vector）、disabled deny-onlyとbean/config契約を補正。production変更なし | SPEC_ADDRESSED（再Review待ち） | e18f0d589b63223bf864bb33c6910b56a59d940e（remoteへpush済み。最終Headはtrace commitの外部handoffで固定） |
| F1 DDL | approval-decision, tasks, design, V129, H2 schema, entity/mapper/service/crypto, F1 tests | client/credential/scope/idempotency/usage bucket/nonce/webhook/retention persistence基盤。初回review指摘をtyped boundary、conflict/CAS、purge、route/overlapでremediate。H2 F1 31 tests、MySQL concurrency 5 tests、Flyway smoke PASS | IMPLEMENTATION_PASS | initial `a7654b44`、remediation `a184c1f4`、CAS correction `d476614e`、follow-up `5a2a0231`、typed snapshot `96d6801c`、独立Review PASS |
| F1 Implementation Review remediation | requirements, design, tasks, review-ledger, review-remediation, implementation/tests | 初回FAIL（P1=7、P2=2）とfollow-up FAIL（P1=4、再Review P1=1）への実装・テスト対応。typed snapshot field boundary、lease fail-closed、lock順序、delivery_generation predicateを追加。public endpoint/外部送信/F2以降は未実装 | IMPLEMENTATION_PASS | `a184c1f4` + `d476614e` + `5a2a0231` + `96d6801c`、fixed Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3` → 独立Review PASS |
| Scope expansion normalization | approval-decision、README、plan、requirements、design、tasks、inventory、review-ledger、中央traceability | DecisionId、OwnerRef、Base、reviewed Head、wave status、A2 N/A、production禁止境界を正本化。production変更なし | COMPLETE（docs-only gate、Plan delta PASS） | f7d7d144（remoteへpush済み。最終handoff Headは外部通知で固定） |
| F2 security chain | tasks/design/inventory、`src/main/java/com/ses/config/integrationhub/`、F2 tests、V130 | `@Order(0)`専用chain、stateless、HMAC byte canonical、connector raw-target、trusted proxy/CIDR、nonce、typed effective scope、route/quota、専用audit、有限metrics、deny-only、stable error | IMPLEMENTATION_PASS | 初回 `aadcfa98`、`e47025b5`、追加 `a16cdcba`。fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`で独立再Review PASS（P0/P1/P2=0/0/0） |
| F2 Implementation Review remediation | F2専用chain、V130、H2 schema、F2 tests | raw request-target供給、client×route scope intersection、audit一request一record、strict IP、metrics cardinality、namespace root | CLOSED_BY_REVIEW | fixed FAIL Head `220ac86f` → `e47025b5`（6件）、fixed FAIL Head `f57df6d2` → `a16cdcba`（2件）。19追加tests PASS |
| A1 read/OpenAPI | tasks/design/requirements/openapi-candidate、A1 production/test classes、V131/H2 snapshot schema、purge scheduler/tests | GET-only 11 paths、external DTO allow-list、invoice customer predicate、multi-contract非偽装、snapshot-bound cursor、canonical Base64URL、scope-bound list/detail/count、独立bounded purge、秒精度asOf、UTC E2E fixture | IMPLEMENTATION_PASS | remediation series後のfixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Review PASS（P0/P1/P2=0/0/0）。focused/integration 24/24 PASS |
| A2 commands | tasks/design/requirements | approved command=0件、command/exportはdefault deny | NOT_APPLICABLE_UNDER_CURRENT_DECISION | — |
| B1 outbound webhook | tasks/design/requirements/inventory、V132、delivery/transport/replay classes、B1 tests | atomic `t_api_delivery` enqueue、claim/lease、transaction外HTTP、HMAC signed event、provider idempotency key、retry/DLQ/replay、MOCK/STUB/LOOPBACK boundary | IMPLEMENTATION_IN_PROGRESS | implementation commit `971c17d7`、focused 28 tests PASS。独立Implementation Review待ち |
| B2 inbound/DLQ/admin UI | tasks/design/requirements | B1 Review後。production受信enablementなし | APPROVED_SEQUENCED | — |
| M verification | tasks/design | B2 Review後にsecurity/recovery/performance/scan/runbookを実施 | APPROVED_SEQUENCED | — |

## Review handoff

R-NF05の固定Head 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ecはPLAN FAIL（P0=0、P1=4）、そのremediation後の
678eac3f09b7ed54419655fcf326e0b15c6d7d62もPLAN FAIL（P0=0、P1=2）だった。最終的に固定Head
1db3b2fc2657831b7c6c1e59217301302b7caa80でPLAN PASS（P0=0、P1=0、P2=2）を受領し、F1を開始した。
Implementation Review follow-upは`dff90b3961b647035436abd378a352b1fa000dd1`でFAIL（P0=0、P1=4、P2=0）だったため、
`5a2a023178433882bc1c5dcf92e19b5ecfa19db6`のremediationを同一Reviewへ再提出した。再ReviewのP1-FU-001を
`96d6801c37d4b952e2601a06cf7edc1bc1a1bef8`で追加remediateし、fixed Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`で
独立Implementation Review PASS（P0=0、P1=0、P2=0）を受領した。
scope expansionのPlan delta Reviewは既存R-NF05へ固定remote Headを渡す。固定Head
1547871caed049ba14d1e5e4a25ad50fa19771fcはPLAN FAIL（P0=0、P1=4、P2=2）、
固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13もPLAN FAIL（P0=0、P1=3、P2=0）であり、
NF05-PLAN-EXP-007〜009のspec/architecture remediationだけを同じbranchへcommit/pushする。
Plan deltaはca27f455でPASS済み、F2はfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でIMPLEMENTATION PASS、A1はfixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS。B1は`971c17d7`で実装済み・独立Implementation Review待ち。A2はN/Aで全体完了をblockしない。
PLAN/IMPLEMENTATION双方PASS前のPR作成は禁止し、production enablement、実顧客credential、実provider送信、
merge、auto-mergeも禁止する。

Review Head 6e0f5067はremediationの比較基点として固定する。Task 0Rのremediation commit、Owner Gate normalization
commit、最終remote Headはcommit series＋外部handoff通知で固定し、自己参照hashはcompletion matrixへ埋め込まない。

Review baseline: 6e0f5067d9a6509775225278cc0dcfdc4d47643f
Scope expansion reviewed Head: 7e50bf1360ea8d7271acc0667593635451300268
Scope expansion DecisionId: DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02
Scope expansion Plan delta FAIL Head: 1547871caed049ba14d1e5e4a25ad50fa19771fc（P0=0、P1=4、P2=2）
Scope expansion Plan delta re-Review FAIL Head: 9cca2deec9ab1bd5417aaba98f859ed14210da13（P0=0、P1=3、P2=0）
Scope expansion Plan delta remediation commit: 8d25215b9b651e99433becf50d13498da3699d2a
Scope expansion Plan delta PASS: ca27f45532bbf96d29da7b9ba87ca52b9cf96d8a（P0=0、P1=0、P2=0）
F2 implementation evidence: 初回 `aadcfa98`、remediation `e47025b5`、追加remediation `a16cdcba`（connector raw-target、authoritative tenant/legal scope、audit V130、strict/mapped IP、metrics、namespace root、production enablementなし）。fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`で独立Implementation Review PASSを受領した。
A1 initial implementation: `466bd9aa44e8699f58cfe0ac033c9c444a7de71e`。独立Reviewはfixed Head `111f4baa37096a1419cc8aaddcb2fe8c71e0e229`でFAIL（P0=0/P1=2/P2=2）。`874fface3bfe90dd27b766ddf9aeff4e00eae591`でinvoice customer scope、snapshot-bound cursor、canonical Base64URL、DTO/path/entity/E2E証跡をremediateした。初回focused remediation suiteは16 tests PASS、その後の再Reviewで判明した3件を追加remediateし、focused/integration 23/23 PASSとした。後続remediationを含むfixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASSを受領し、B1を開始した。
Scope expansion Plan delta residual remediation commit: e18f0d589b63223bf864bb33c6910b56a59d940e（docs-only、remoteへpush済み）
Task 0R remediation: 48037c923224f684968dbaf3410cdb37307ed100
Task 0R-D delta remediation: 11ee82c15a5cdf8f961b2a2d0518a52d81f4de71
Owner Gate normalization: 2f91e5a584c5224989780cb323e40f33fda185b6
R-NF05 Plan remediation: b0151e7d8acc54da124c4464db1df263e4b3f716
R-NF05 residual remediation: a3b63d70f53bc799d1abcb6e26e34ad163aa9843
R-NF05 state mapping cleanup: fdea4bb18db3d3ae6542dc0c534425783dd28a24
F1 implementation follow-up remediation: 5a2a023178433882bc1c5dcf92e19b5ecfa19db6
F1 typed snapshot correction: 96d6801c37d4b952e2601a06cf7edc1bc1a1bef8
A1 remediation: 874fface3bfe90dd27b766ddf9aeff4e00eae591
A1 entity serialization contract follow-up: 9ed77cf3056d1bd3f913e461115f4ca732639519
A1 snapshot purge/asOf/E2E remediation: fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS

B1 implementation evidence: `971c17d7`でNF-05専用`t_api_delivery`を再利用するoutbound worker、HMAC-SHA256 signer、MOCK/STUB/LOOPBACK transport、
DLQ replay audit、V132、H2 schema/testを追加した。fixed framing golden vector、実loopback server、redirect拒否、provider idempotency header、
credential version、claim/HTTP/CAS、retry/no-retry、scope、replay、設定fail-closedを含むfocused 28 testsはfailure/error/skipなしでPASSした。
外部I/OはDB transaction外であり、実顧客credential、実provider、production enablementは未実施。B1独立Implementation Review後までB2を開始しない。
Final remote Head: 外部handoff通知で固定（この行を含むcommit自身のhashは自己参照しない）

## F1実装証跡

- Implementation commit: `a7654b44`（`feat: implement NF-05 F1 persistence foundation`）
- Remediation commit: `a184c1f4`（`fix: remediate NF-05 F1 implementation review findings`）
- Delivery CAS correction: `d476614e`（`fix: bind delivery CAS to generation`）
- MySQL: `FlywayMigrationSmokeTest`を`-Pmysql-tests`で実行し、empty/legacy V78/normal DBのV129適用を確認。
- H2: `IntegrationHubF1SchemaH2Test`、`IntegrationHubF1RetentionH2Test`および既存schema sweepを確認。
- F1 targeted suite: 31 tests、failure 0、error 0、skip 0。
- MySQL concurrency: `IntegrationHubF1MySqlConcurrencyTest` 5 tests、failure 0、error 0、skip 0。usage unique初期化、delivery CAS、hold/purge race、malformed lease、inbound duplicateを実service/mapper経路で確認。
- 全fast suite: F1失敗なし。ただし既存loopback接続・production-config系10 errors、既存規約/cache headerの
  2 failuresが残るため、全体PASSとは扱わない。
- 初回Implementation Review: fixed Head `b420911b63177763544edd1e02d663bf528d9dc1`、FAIL（P0=0、P1=7、P2=2）。
- follow-up Implementation Review: fixed Head `dff90b3961b647035436abd378a352b1fa000dd1`、FAIL（P0=0、P1=4、P2=0）。再Review fixed Head `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`はFAIL（P0=0、P1=1、P2=0）。
- 最終F1 Implementation Review: fixed Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`、PASS（P0=0、P1=0、P2=0）。状態: F1 IMPLEMENTATION_PASS、M/F2以降とproduction enablementは未着手。
