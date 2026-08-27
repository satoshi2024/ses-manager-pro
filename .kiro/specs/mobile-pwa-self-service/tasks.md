# mobile-pwa-self-service Tasks

順序は `0 Discovery/Gate → F1 Cache/Shell → F2 Server Contract → A1 Mobile Shell → A2 Draft/Queue/Conflict → B1 Update/Cleanup/Monitoring → M Integrated Gate` とする。各完了Taskは専用branchへcommit/pushし、completion matrixとreview-ledgerへ証跡を追記する。

## Task 0 — Discovery、Gate、Spec固定 `[x]`

### Objective

承認済みscope、Base、既存route、認証/CSRF/session、PII/cache境界、domain service、migration latestを固定する。

### Implementation guidance

- `AGENTS.md`と指定spec/platform/integration資料を読み、NF-04の再利用境界を確認する。
- `origin/main`をfetchし、実際のBase SHAをrequirements/design/review-ledgerへ記録する。
- 中央traceabilityのNF-04/DG-04をAPPROVED/決定済みに更新する。
- 既存 `/my/**`、`common.js`、SecurityConfig、PersistentSession、CSRF、PII download、portal、idempotency/versionをinventoryする。
- designにtime/as-of、subject×operation×visibility、state×conflictの3 decision tableを置く。

### Test requirements

- dedicated worktree/root/branch/status/remote/baseを記録する。
- 通常checkoutに差分がないことを確認する。
- 新規spec 4点の整合性を自己Reviewする。

### Demo

承認条件、Base SHA、route inventory、offline decision table、非目標、review-ledgerのPlan self-review結果を確認する。

## Task F1 — Manifest、Service Worker、Cache/HTTP policy `[x]`

### Objective

静的shellだけをcacheし、dynamic/API/portal/PII responseをnetwork-only/no-storeにする。

### Implementation guidance

- `/manifest.webmanifest`、icon、`/service-worker.js`、offline fallbackを追加する。
- `common.js`からSWを登録し、更新prompt、logout時clear message、unregister/cleanupを実装する。
- SW cache allow-listを正規表現ではなく明示的なpath predicateで管理する。
- `/api/**`、`/my/**`、`/portal/**`、document/payroll/bank/PDF/attachment/receiptをcache putから除外する。
- inventoryでno-store不足だったdownload/preview/page/APIにno-storeを付ける。
- cross-origin opaque response、POST、redirected auth responseをcacheしない。

### Test requirements

- static asset cache、禁止route cache 0件、old cache cleanup、network-onlyを自動テストする。
- login redirect/401/403/HTML responseがcacheされないことを確認する。
- no-store headerをcontroller testで確認する。

### Demo

SW有効の実BrowserでCache Storageを開き、全entry URLを出力する。禁止route 0件、static allow-listのみ、更新prompt 1回を証拠化する。

## Task F2 — User context、idempotency、version/CAS contract `[x]`

### Objective

queue再送の副作用を1件にし、異hash/stale versionを409にして既存domain serviceへ接続する。

### Implementation guidance

- `GET /api/my/session-context`とserver-issued opaque contextを追加する。
- `X-Client-Request-Id`、canonical hash、baseVersion、409 response data contractをDTO/handlerへ追加する。
- NF-04専用client mutation ledger migration/entity/mapper/serviceを追加する。外部連携job/compliance ledgerは流用しない。
- timesheet/attendance/expense/change-requestの許可されたmutationだけをidempotency/CAS boundaryへ接続する。
- existing `AttendanceCalculator`、`WorkRecordService`、expense/change-request serviceを呼び、業務計算を複製しない。
- `System.nanoTime()`等のserver-generated keyをclient request IDの代替にしない。

### Test requirements

- 同一ID/同一hash replay、同一ID/異hash 409、stale baseVersion 409、concurrent update、DB rollbackを検証する。
- same request IDの二重clickで業務行/notification/auditが1件であることを検証する。
- H2 schema、fresh MySQL、legacy/partial migrationの必要範囲を同期する。

### Demo

network timeout後の同一command再送、同時2 session、stale draftを実行し、DB副作用1件、409差分、last-write-winsなしを証拠化する。

## Task A1 — Mobile shell/navigation `[x]`

### Objective

既存 `/my/**` を390×844で使えるshell/navigationへ適合する。

### Implementation guidance

- 既存layout/sidebar/common.jsを再利用し、要員の既存routeと権限を変えない。
- manifest link、offline/sync/conflict status indicator、更新通知をbase layoutへ追加する。
- focus restore、touch target、keyboard/screen reader label、error summaryを全対象formへ適用する。
- desktop/tablet layoutと既存管理画面へ影響させない。

### Test requirements

- 390×844実viewport、desktop/tablet、horizontal scroll、keyboard/focus、session expiryをPlaywrightで確認する。
- Chrome/Edge/Safari相当の主要browser matrixを記録する。

### Demo

timesheet、attendance、expense、change-requestのshell navigationを390×844で操作し、network statusとsession expiry表示を確認する。

## Task A2 — Draft、queue、sync、conflict UI `[x]`

### Objective

承認済みoperationだけをuser-scopedに保存・同期し、409差分を人へ提示する。

### Implementation guidance

- IndexedDBはschema version付きの単一`queue` storeを使い、最小入力draftと送信commandを同じuser-scope recordとして保持する（token/secret/給与・銀行/添付binary/他人PII用のstoreは作らない）。
- 保存前にallowlist、payload size、binary/secret/PII禁止を検査する。
- `clientRequestId`、hash、baseVersion、userScope、screen、month、createdAtを毎回生成する。
- syncはonline event、page visibility、明示再試行で実行し、logout/user switch/session expiry/context mismatchで停止する。
- queue中のsubmit/approval/attachment/receiptを作らない。
- 30日を超えたrecordは送信せずpayloadを除去し、破棄・再入力を促す。409 responseはresource/resourceIdとserver/clientのfield diffとして表示し、自動merge/retryをしない。
- 送信時は`X-Client-Created-At`を付け、server時計とserver-issued userScope leaseで30日保持期限と未来時刻を検査する。期限切れは自動再送せず、payloadを残さず破棄・再入力を促す。
- 50ms double-clickで同じcommandを複数生成しない。

### Test requirements

- offline→online、browser restart、30日expiry、A logout→B login、session expiry、409、double-clickを実測する。
- IndexedDBに禁止データがないことを検査する。

### Demo

各対象画面で入力→offline保存→再起動→online同期→server ack、stale conflict→manual reapplyを実行する。

## Task B1 — Update、cleanup、monitoring、recovery `[x]`

### Objective

SW更新、旧cache削除、queue retention、logout cleanup、監視を運用可能にする。

### Implementation guidance

- waiting worker promptとcontrollerchangeを一回だけ扱う。
- activate時は旧PWA cacheのみ削除し、同期中queueを無条件削除しない。
- logout/user switchはuser namespaceをclearし、clear確認失敗時はflushを止める。
- 30日expired draft/queueを送信せず、件数と理由だけ監視する。
- PII/secretをlogへ出さないmonitoringとrunbookを追加する。

### Test requirements

- SW version N→N+1、old cache cleanup、pending queue保持、logout cleanup、context mismatchを確認する。
- recovery時にduplicate sendが発生しないことを確認する。

### Demo

旧versionでqueueを作成し、新versionへ更新後、queueが保持され、禁止cacheが消え、同一user再認証後に一度だけ同期されることを確認する。

## Task M — Integrated gate、evidence、Review handoff `[ ]`（Browser環境BLOCKED）

### Objective

全requirements、既存domain boundary、security/cache/idempotency/UI/operation evidenceをまとめ、独立Reviewへhandoffする。

### Test requirements

- fast test、必要なMySQL/performance gate、migration/schema testsをskip 0で実行する。
- Playwrightで390×844、offline→online、session expiry、SW update、50ms double-click、CacheStorage、A→B、409 diffを実測する。
- build SHA、Base SHA、browser/OS、viewport、network、trace/video/HAR、console/page error、DB before/afterを保存する。

### Demo

completion matrix、CacheStorage inspection、test report、remote Head、clean worktree、Base..Head diff、approved spec 4点をReviewへ渡す。Review PLAN/IMPLEMENTATIONの双方がPASSするまでPRを作成しない。
