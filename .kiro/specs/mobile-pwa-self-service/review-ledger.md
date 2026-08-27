# mobile-pwa-self-service Review Ledger

## 1. 固定情報

| Field | Value |
|---|---|
| Feature | `NF-04 mobile-pwa-self-service` |
| Status | APPROVED / IMPLEMENTING |
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
- [x] 390×844、offline recovery、SW update、double-click、CacheStorageの実測証拠がTask Mにある。
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
| A2 Draft/Queue/Conflict | COMPLETED | 0003b54251d826d0fb10bfc40cddf8acbe2c98db | 0003b54251d826d0fb10bfc40cddf8acbe2c98db | pwa-queue.js; allowed mutation adapters; 30-day cleanup; same-resource version advancement; conflict panel; PwaAssetContractTest (5); browser run attempted but environment loopback blocker recorded below |
| B1 Update/Cleanup/Monitoring | COMPLETED | 45b0a1b1e2c1619972609655a786c9109f8e0ac7 | 45b0a1b1e2c1619972609655a786c9109f8e0ac7 | PwaClientMutationCleanupSchedulerTest; server 30-day ledger cleanup; Micrometer outcome/screen metrics without user/payload tags; SW old-cache cleanup/update prompt |
| M Integrated gate | NOT_STARTED | - | - | - |

## 4. Completion matrix

| Requirement | Implementation | Automated test | Browser/Demo evidence | Status |
|---|---|---|---|---|
| PW-R1 SW/cache/no-store | - | - | - | PENDING |
| PW-R2 draft/queue | - | - | - | PENDING |
| PW-R3 idempotency/version | - | - | - | PENDING |
| PW-R4 session/user boundary | - | - | - | PENDING |
| PW-R5 conflict UX | - | - | - | PENDING |
| PW-R6 390px/accessibility | - | - | - | PENDING |
| PW-R7 PII route hardening | - | - | - | PENDING |
| PW-R8 evidence/handoff | - | - | - | PENDING |

## 5. Independent Review handoff

Implementation completion時に次を埋める。

- Base SHA:
- Remote Head SHA:
- `git diff --stat Base..Head`:
- remote branch verification:
- clean worktree verification:
- fast/MySQL/performance test result:
- CacheStorage inspection artifact:
- 390×844 artifact:
- offline→online artifact:
- session expiry artifact:
- SW update artifact:
- double-click artifact:
- A logout→B login artifact:
- 409 server/client diff artifact:
- IndexedDB prohibited-data inspection:
- completion matrix:

Review is separate from implementation. This ledger must not be edited to claim independent PLAN/IMPLEMENTATION PASS before an independent Review task returns those verdicts.
