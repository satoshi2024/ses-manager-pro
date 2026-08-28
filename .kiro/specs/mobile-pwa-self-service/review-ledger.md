# mobile-pwa-self-service Review Ledger

## 1. 固定情報

| Field | Value |
|---|---|
| Feature | `NF-04 mobile-pwa-self-service` |
| Status | APPROVED / IMPLEMENTED / INDEPENDENT REVIEW FAIL（Task MのBrowser環境証拠はBLOCKED） |
| Owner | 管理者（プロジェクト責任者） |
| Approved scope | Chrome/Edge/Safari現行・直前、Android Chrome、iOS Safari、install任意、pushなし、承認済みoffline/cache/idempotency/version/retention/user boundary |
| Base branch | `origin/main` |
| Approved Base SHA | `455fc92e3aa259d2a93f25c6a545ca6c6af835bc` |
| Actual Base SHA | `455fc92e3aa259d2a93f25c6a545ca6c6af835bc` |
| Branch | `codex/mobile-pwa-self-service` |
| Worktree | `C:\work\ses-mobile-pwa-self-service` |
| Implementation PR | 作成しない（Review PASS後に別対話で作成） |

## 2. Plan self-review

**Result: PASS（2026-08-28、実装開始前）**

確認項目:

- [x] Central traceabilityのNF-04が`APPROVED`、DG-04が決定済みである。
- [x] requirements/design/tasksが承認scope、Owner、Base、worktree、branch、開始順序を共有している。
- [x] designにtime/as-of、subject×operation×visibility、state×conflictの3 decision tableがある。
- [x] cache allow-listとnetwork-only/no-store禁止境界が明示されている。
- [x] queueがclientRequestId、canonical payload hash、baseVersion、user scope、screen、month、createdAtを持つ。
- [x] same ID/same hash replay、same ID/different hash 409、stale version 409、no LWWが固定されている。
- [x] user A logout→B login、session expiry、shared deviceをfail-closed条件としている。
- [x] attendance/expenseの既存domain service・計算を再利用し、PWA独自計算を持たない。
- [ ] 390×844、offline recovery、SW update、double-click、CacheStorageの実測証拠がTask Mにある。
- [x] migrationは実装開始時の最新+1を使用し、既存migrationを変更しない。
- [x] implementation dialogではPR/mergeを行わない。

Self-review notes:

- 現在のBaseは`455fc92e...`で、通常checkoutは変更せず専用worktreeに分離済み。
- existing `/api/my/**`にはclient request ID/baseVersionがないため、F2をA2より先に完了させる。
- 既存の一部PII downloadにはno-storeがないため、F1の修正対象として固定した。
- user context bootstrap endpointは現状存在しないため、A2のflushを開始する前にF2でserver-issued contextを追加する。

## 3. Implementation status

| Task | Status | Commit | Remote SHA | Evidence |
|---|---|---|---|---|
| 0 Discovery/Gate/Spec | COMPLETED | b3c69d990932f1ea2e74020b95741a6b4757180f | b3c69d990932f1ea2e74020b95741a6b4757180f | this spec, central traceability, inventory |
| F1 Manifest/SW/Cache | COMPLETED | 1dc2056397c8236acc5c347b2629e0ce2933265c | 1dc2056397c8236acc5c347b2629e0ce2933265c | PwaAssetContractTest, PwaNoStoreFilterTest, static shell/cache policy |
| F2 Idempotency/Version | COMPLETED | d4f13189e8289300121863c619bb874c839bb58e | d4f13189e8289300121863c619bb874c839bb58e | PwaCanonicalizerTest, PwaClientMutationLedgerServiceTest, PwaMutationApiControllerTest, AllMappersSchemaSweepTest, MessageBundleConsistencyTest |
| A1 Mobile shell | COMPLETED | 215600ea74c30cc6587f08f7fe1bb3ccf48df65c | 215600ea74c30cc6587f08f7fe1bb3ccf48df65c | MobileResponsiveLayoutTest (28), PwaAssetContractTest (4); 44px touch target, focus-visible, modal focus restore, offline/online status |
| A2 Draft/Queue/Conflict | COMPLETED | 0003b54251d826d0fb10bfc40cddf8acbe2c98db | 0003b54251d826d0fb10bfc40cddf8acbe2c98db | pwa-queue.js; single queue store; allowed mutation adapters; 30-day cleanup; same-resource version advancement; conflict panel; PwaAssetContractTest (6); browser run attempted but SW/Tomcat loopback blocker recorded below |
| B1 Update/Cleanup/Monitoring | COMPLETED | 45b0a1b1e2c1619972609655a786c9109f8e0ac7 | 45b0a1b1e2c1619972609655a786c9109f8e0ac7 | PwaClientMutationCleanupSchedulerTest; server 30-day ledger cleanup; Micrometer outcome/screen metrics without user/payload tags; SW old-cache cleanup/update prompt |
| Review hardening | COMPLETED | 4bf26819〜06ed1471 | 06ed147103dc12a0b9a4d393a762cc1b480d9344 | payload allow-list/PII境界、409差分・再取得・手動再適用、日次row差分、署名付きopaque lease、scope lease更新とrecord retentionの分離、期限切れERROR payload削除、method/path/ID/operation検証、V113 operation境界、V112旧hash互換とoperation再束縛、実HTTPを含むMySQL実domain競合/rollback、scope更新後replay、動的no-store、同一user scope再束縛、atomic cursor更新、selectByUserAndClientRequestのFOR UPDATE解除によるInnoDB gap lock deadlock解消を追加 |
| M Integrated gate | PARTIAL_WITH_ENVIRONMENT_BLOCKER | 未完了（Task MのBrowser実測待ち） | 06ed147103dc12a0b9a4d393a762cc1b480d9344 | 対象契約gate 42/0/0/0、PWA専用MySQL実domain gate 5/0/0/0、migration smoke 5/0/0/0、legacy hash fixtureを含むledger gate 7/0/0/0、node --check PASS。実Browser/CacheStorage/IndexedDBは環境blockerで未実施 |

## 4. Completion matrix

| Requirement | Implementation | Automated test | Browser/Demo evidence | Status |
|---|---|---|---|---|
| PW-R1 SW/cache/no-store | service-worker.js allow-list、PwaNoStoreFilter、network-only | PwaAssetContractTest 7、PwaNoStoreFilterTest 3 | IAB/Chrome static probeでSW API非公開、CacheStorage実測は未実行 | AUTOMATED_PASS / BROWSER_BLOCKED |
| PW-R2 draft/queue | user-scoped単一queue store、30日retention、offline対象allow-list、online flush、期限payload除去、ERROR payload/hash/conflict除去 | PwaAssetContractTest 7、PwaCanonicalizerTest、PwaUserContextServiceTest 3、queue JS syntax/contract | 実Browserのoffline→online/再起動はTomcat loopback blockerで未実行 | AUTOMATED_PASS / BROWSER_BLOCKED |
| PW-R3 idempotency/version | V112 ledger + V113 operation境界、同hash replay、異hash/stale version 409、scope更新後replay、V112旧hashの同一operation再束縛、既存domain service/CAS再利用、同transaction rollback、InnoDB gap lock deadlock解消 | PwaClientMutationLedgerServiceTest 7、PwaMutationApiControllerTest 5、PWA専用MySQL実domain gate 5 | MySQL 8 Testcontainersでclaim race、scope更新後同hash replay、expense同一baseVersionの片側409/非上書き、ledger+業務行rollback、実HTTP controller更新、V113 operation列適用を実測（5/0/0/0 PASS） | AUTOMATED_PASS / BROWSER_PARTIAL |
| PW-R4 session/user boundary | 署名付きopaque userScope、同一user lease再利用/期限後再束縛、logout/user switch clear、expiry pause、context mismatch fail-closed | PwaUserContextServiceTest 3、PwaAssetContractTest 7、bundle/JS contract、portal regression | A logout→B login/session expiryはapp起動 blockerで未実行 | AUTOMATED_PASS / BROWSER_BLOCKED |
| PW-R5 conflict UX | resource/resourceId/fieldsのserver/client diff表示、対象日次row正規化、CONFLICT停止、no-store再取得、確認付きmanual reapply、LWW禁止 | PwaMutationApiControllerTest 3、PwaAssetContractTest 7 | 実409画面操作はapp起動 blockerで未実行 | AUTOMATED_PASS / BROWSER_BLOCKED |
| PW-R6 390px/accessibility | responsive shell、touch/focus/aria/status | MobileResponsiveLayoutTest 28、PwaAssetContractTest 7 | IAB/Chromeで390×844 viewport、DPR 1.25を確認。app画面操作は未実行 | PARTIAL |
| PW-R7 PII route hardening | `/api/**` `/my/**` `/portal/**` no-store、PDF/attachment/payroll/bank等SW除外、minimal ack | PwaNoStoreFilterTest 3、PwaAssetContractTest 7、PayrollSecurityAuditTest | CacheStorage禁止route 0件の実測はSW API blockerで未実行 | AUTOMATED_PASS / BROWSER_BLOCKED |
| PW-R8 evidence/handoff | ledger/spec/commit/remote/base/constraintsの固定 | MessageBundleConsistencyTest 4、AllMappersSchemaSweepTest 169、CI gate reports | Browser evidence limitationを本ledgerへ明記 | READY_WITH_BLOCKERS |

## 5. Independent Review handoff

Implementation completion時に次を埋める。Browser実測不能項目は未実施として残し、PASSとは記載しない。

- Base SHA: `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- Remote Head SHA（ledger更新前の実装検証値）: `06ed147103dc12a0b9a4d393a762cc1b480d9344`
- `git diff --stat Base..Head`: `63 files changed, 4371 insertions(+), 61 deletions(-)`（このledger更新commit後に再確認する）
- remote branch verification（ledger更新前の記録）: `git ls-remote origin refs/heads/codex/mobile-pwa-self-service` = `06ed147103dc12a0b9a4d393a762cc1b480d9344`; local HEADも同値。
- clean worktree verification（記録時）: `git status --short --branch` はbranch tracking行のみで差分なし。
- fast/MySQL/performance test result: 最新PWA対象契約gate `42/0/0/0`、PWA専用MySQL実domain gate `5/0/0/0`、MySQL migration契約`5/0/0/0`、performance`1/0/0/0`。全体gateの制約は下記summary。
- CacheStorage inspection artifact: `BLOCKED/未実行`。IAB/Chrome surfaceが`navigator.serviceWorker === undefined`で、アプリTomcatもJDK 21 loopback errorで起動不可。
- 390×844 artifact: IAB/Chrome static probeで`innerWidth=390, innerHeight=844, devicePixelRatio=1.25`を確認。実アプリ画面は未実行。
- offline→online artifact: `BLOCKED/未実行`（同上）。
- session expiry artifact: `BLOCKED/未実行`（同上）。
- SW update artifact: `BLOCKED/未実行`（同上）。
- double-click artifact: `BLOCKED/未実行`（同上）。50ms間隔の実Browser測定はできなかった。queue codeには250ms in-flight dedupeを実装し、静的契約で確認。
- A logout→B login artifact: `BLOCKED/未実行`（同上）。logout/user switch clearとscope mismatch fail-closedはコード・静的契約で確認。
- 409 server/client diff artifact: `BLOCKED/未実行`（同上）。controller stale conflict testとqueue conflict rendering、server再取得・確認付きreapplyは自動/静的テストで確認。
- IndexedDB prohibited-data inspection: `BLOCKED/未実行`。queue allow-list/minimal payload/no forbidden fieldを静的契約で確認。
- completion matrix: 本文4節。Browser項目は未実施をPASSに昇格していない。

## 6. Latest independent Review result

- Review対象SHA: `06ed147103dc12a0b9a4d393a762cc1b480d9344`（このledger更新前）。
- 判定: `PLAN FAIL` / `IMPLEMENTATION FAIL`、`P0=0`。コード上の残存P1/P2はなし。
- 必須残件P1: Task Mの実Browser証拠（390×844実画面、offline→online、再起動、session expiry、SW update、CacheStorage禁止route 0件、50ms double-click、A logout→B login、実409 conflict UI）が環境blockerで未実施。
- 前回のledger不整合P2は本更新で修正対象とし、次のremote Headで再Reviewする。

### Automated/Browser evidence summary

- 最新対象契約gate: `mvn test -Dtest=PwaUserContextServiceTest,PwaAssetContractTest,PwaCanonicalizerTest,PwaClientMutationLedgerServiceTest,PwaMutationApiControllerTest,PwaNoStoreFilterTest,ApiAuditFilterTest,MyTimesheetJsContractTest,TestIsolationAuditTest,PwaClientMutationCleanupSchedulerTest -Dsurefire.runOrder=alphabetical` → 42 tests / 0 failures / 0 errors / 0 skipped。
- 既存PWA関連を含むcombined gate: 216 tests / 0 failures / 0 errors / 0 skipped。
- PWA MySQL並行/domain gate: `mvn test -Pmysql-tests -Dtest=PwaClientMutationLedgerMySqlConcurrencyTest -Dsurefire.runOrder=alphabetical` → 5 / 0 / 0 / 0。MySQL 8 Testcontainersで二writerのclaim/conflict/DB一行、scope更新後同hash replay、実expense domainのstale 409/非上書き、ledger+業務行rollback、実HTTP controller経路でのledger・expense同一transaction完了を確認。
- MySQL migration契約: `mvn test -Pmysql-tests -Dtest=FlywaySelfServiceSchemaSmokeTest,FlywayV110AdminBoundaryUpgradeSmokeTest` → 5 / 0 / 0 / 0。
- Performance: `mvn test -Pperformance-tests` → 1 / 0 / 0 / 0。
- 全fast gateは11 errorsでFAIL。既存`PinningHttpsTransportTest`、`WebhookNotifierLoopbackIntegrationTest`、`CapacityBaselineScriptTest`、`PrometheusScraperLabE2ETest`のJDK 21 loopback確立失敗と、全体run-order時のみ再現する`ExpenseRequestFlowIntegrationTest` 4 error。Expense単独は7 / 0 / 0 / 0。PWA対象契約gateは18 / 0 / 0 / 0。
- 全MySQL gateは82 tests中、V112追加に伴う旧V111固定assertion 3件（修正後の対象契約gateで解消）と`FreeeConcurrentRefreshTest` loopback初期化1件でFAIL。PWA migration smokeの修正後再実行はPASS。
- 最新Browser gate `EngineerSelfServiceBrowserMTest` は1 errorで、Tomcat起動時のJDK 21 `Unable to establish loopback connection`（`java.net.SocketException: Invalid argument: connect`）によりアプリ画面へ到達できず終了。
- IAB/Chrome static probe: viewport `390x844`、DPR `1.25`を確認。両surfaceで`window.navigator.serviceWorker`、CacheStorage、IndexedDBが利用不可（`undefined`）のため、CacheStorage禁止route 0件、SW update、offline/online、session expiry、A→B、409画面、50ms double-clickの実Browser測定は未実行。
- Backup補助gate: unitはbinlog `61/0`、cutover `31/0`、restore drill `19/0`、full backup `36/0`、harness `8/0`、health `29/0`、preflight `59/0`、quiesce `45/0`、restore flow `31/0`、restore plan `44/0`、restore validation `32/0`まで通過したが、retention synthetic metadataのjq生成がハングしたため停止。integrationは未実行。PWA対象外。

Review is separate from implementation. This ledger must not be edited to claim independent PLAN/IMPLEMENTATION PASS before an independent Review task returns those verdicts.
