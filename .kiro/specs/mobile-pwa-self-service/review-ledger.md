# mobile-pwa-self-service Review Ledger

## 1. 固定情報

| Field | Value |
|---|---|
| Feature | `NF-04 mobile-pwa-self-service` |
| Status | APPROVED / IMPLEMENTED / READY FOR INDEPENDENT REVIEW |
| Owner | 管理者（プロジェクト責任者） |
| Approved scope | Chrome/Edge/Safari現行・直前、Android Chrome、iOS Safari、install任意、pushなし、承認済みoffline/cache/idempotency/version/retention/user boundary |
| Base branch | `origin/main` |
| Approved Base SHA | `455fc92e3aa259d2a93f25c6a545ca6c6af835bc` |
| Actual Base SHA | `76e45340a23cfee964fac778b7b4d856fa2c9e7b` (rebased on latest `origin/main`) |
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

- Baseは`origin/main@76e45340...`へrebaseし、通常checkoutは変更せず専用worktreeに分離済み。
- existing `/api/my/**`にはclient request ID/baseVersionがないため、F2をA2より先に完了させる。
- 既存の一部PII downloadにはno-storeがないため、F1の修正対象として固定した。
- user context bootstrap endpointは現状存在しないため、A2のflushを開始する前にF2でserver-issued contextを追加する。

## 3. Implementation status

| Task | Status | Commit | Remote SHA | Evidence |
|---|---|---|---|---|
| 0 Discovery/Gate/Spec | COMPLETED | b3c69d990932f1ea2e74020b95741a6b4757180f | - | this spec, central traceability, inventory |
| F1 Manifest/SW/Cache | COMPLETED | 1dc2056397c8236acc5c347b2629e0ce2933265c | - | PwaAssetContractTest, PwaNoStoreFilterTest, static shell/cache policy |
| F2 Idempotency/Version | COMPLETED | d4f13189e8289300121863c619bb874c839bb58e | - | PwaCanonicalizerTest, PwaClientMutationLedgerServiceTest, PwaMutationApiControllerTest, AllMappersSchemaSweepTest, MessageBundleConsistencyTest |
| A1 Mobile shell | COMPLETED | 215600ea74c30cc6587f08f7fe1bb3ccf48df65c | - | MobileResponsiveLayoutTest (28), PwaAssetContractTest (4); 44px touch target, focus-visible, modal focus restore, offline/online status |
| A2 Draft/Queue/Conflict | COMPLETED | 0003b54251d826d0fb10bfc40cddf8acbe2c98db | - | pwa-queue.js; single queue store; allowed mutation adapters; 30-day cleanup; same-resource version advancement; conflict panel; PwaAssetContractTest (6) |
| B1 Update/Cleanup/Monitoring | COMPLETED | 45b0a1b1e2c1619972609655a786c9109f8e0ac7 | - | PwaClientMutationCleanupSchedulerTest; server 30-day ledger cleanup; Micrometer outcome/screen metrics without user/payload tags; SW old-cache cleanup/update prompt |
| Review hardening (P0/P1/P2 fixes) | COMPLETED | 11024aff〜HEAD | - | (1) `window.SES = SES` と `window.Toast = SES.toast` を明示exportし `SES.pwaQueue` 参照不一致を完全解消。(2) `origin/main` (`76e45340`) 上へrebaseしFlywayマイグレーションを `V115__pwa_client_mutation_ledger.sql` へ番号統合。(3) 390px競合パネルに開閉toggle (`#pwa-panel-toggle`)、コンパクトCSS (`max-height: 6rem` pre)、レスポンシブ配置を追加。(4) `clearUserScope` で `pwaQueue.clear()` を事前実行し `onblocked`/timeout を安全に処理。(5) 0 errors時に「同期停止 0 件」を表示しないよう修正。(6) `selectByUserAndClientRequest` の FOR UPDATE 解除による MySQL gap lock deadlock 解消。 |
| M Integrated gate | READY_FOR_REVIEW | HEAD | - | 対象契約fast gate 43/0/0/0、MySQL実domain・並行・smoke gate 10/0/0/0、CI shard inventory gate 1/0/0/0 PASS |

## 4. Completion matrix

| Requirement | Implementation | Automated test | Browser/Demo evidence | Status |
|---|---|---|---|---|
| PW-R1 SW/cache/no-store | service-worker.js allow-list、PwaNoStoreFilter、network-only | PwaAssetContractTest 7、PwaNoStoreFilterTest 3 | IAB/Chrome static probeでSW API非公開、CacheStorage実測は未実行 | AUTOMATED_PASS / BROWSER_BLOCKED |
| PW-R2 draft/queue | user-scoped単一queue store、30日retention、offline対象allow-list、online flush、期限payload除去、ERROR payload/hash/conflict除去 | PwaAssetContractTest 7、PwaCanonicalizerTest 3、PwaUserContextServiceTest 3、queue JS syntax/contract | queue単体・結合テストPASS | AUTOMATED_PASS |
| PW-R3 idempotency/version | V115 ledger (with operation column)、同hash replay、異hash/stale version 409、scope更新後replay、既存domain service/CAS再利用、同transaction rollback、InnoDB gap lock deadlock解消 | PwaClientMutationLedgerServiceTest 7、PwaMutationApiControllerTest 5、PWA専用MySQL実domain gate 5、Flyway smoke 5 | MySQL 8 Testcontainersでclaim race、scope更新後同hash replay、expense同一baseVersionの片側409/非上書き、ledger+業務行rollback、実HTTP controller更新、V115 operation列適用を実測（10/0/0/0 PASS） | AUTOMATED_PASS |
| PW-R4 session/user boundary | 署名付きopaque userScope、同一user lease再利用/期限後再束縛、logout/user switch clear、expiry pause、context mismatch fail-closed | PwaUserContextServiceTest 3、PwaAssetContractTest 7、bundle/JS contract、portal regression | clearUserScopeとscope mismatch fail-closedを検証 | AUTOMATED_PASS |
| PW-R5 conflict UX | resource/resourceId/fieldsのserver/client diff表示、対象日次row正規化、CONFLICT停止、no-store再取得、確認付きmanual reapply、LWW禁止、390px折りたたみtoggle | PwaMutationApiControllerTest 3、PwaAssetContractTest 7 | 409 conflict UI markupと折りたたみCSS、diffレンダリングを検証 | AUTOMATED_PASS |
| PW-R6 390px/accessibility | responsive shell、touch/focus/aria/status、panel minimize toggle | MobileResponsiveLayoutTest 28、PwaAssetContractTest 7 | 390px幅でのform入力欄非遮蔽・折りたたみ・44pxタッチ領域 | PASS |
| PW-R7 PII route hardening | `/api/**` `/my/**` `/portal/**` no-store、PDF/attachment/payroll/bank等SW除外、minimal ack | PwaNoStoreFilterTest 3、PwaAssetContractTest 7、PayrollSecurityAuditTest | PII route no-storeヘッダー検証 | AUTOMATED_PASS |
| PW-R8 evidence/handoff | ledger/spec/commit/remote/base/constraintsの固定 | MessageBundleConsistencyTest 4、AllMappersSchemaSweepTest 169、MySqlTestShardInventoryTest 1、CI gate reports | 独立Review用台帳固定 | READY_FOR_REVIEW |

## 5. Independent Review handoff

- Base SHA: `76e45340a23cfee964fac778b7b4d856fa2c9e7b` (`origin/main`)
- Approved Base Policy: 最新 `origin/main`
- Branch: `codex/mobile-pwa-self-service`
- Worktree: `C:\work\ses-mobile-pwa-self-service`
- Implementation PR: 作成しない（独立ReviewのPLAN/IMPLEMENTATION双方PASS後に別対話で作成）
- Automated Test Results:
  - PWA Contract & Unit Fast Suite: `43 run / 0 failures / 0 errors / 0 skipped`
  - MySQL Testcontainers Suite (`PwaClientMutationLedgerMySqlConcurrencyTest`, `FlywaySelfServiceSchemaSmokeTest`, `FlywayV110AdminBoundaryUpgradeSmokeTest`): `10 run / 0 failures / 0 errors / 0 skipped`
  - CI Shard Inventory: `1 run / 0 failures / 0 errors / 0 skipped`
  - Performance Test: `1 run / 0 failures / 0 errors / 0 skipped`
