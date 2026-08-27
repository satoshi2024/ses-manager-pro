# mobile-pwa-self-service 要件

- Feature: `NF-04 mobile-pwa-self-service`
- Status: `APPROVED`
- Approved date: 2026-08-28
- Owner: 管理者（プロジェクト責任者）
- Base branch: `origin/main`
- Approved Base: `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- Implementation branch: `codex/mobile-pwa-self-service`
- Worktree: `C:\work\ses-mobile-pwa-self-service`

> 注: 上記のBase SHAは承認時に確認した値である。実装開始時にfetchした`origin/main`を再確認し、実際のSHAが異なる場合はこの文書、design、tasks、review-ledgerを同一Taskで更新する。

## 1. Scope

対象は、既存の要員セルフサービス `/my/**` を、install任意のPWA shellとして利用しやすくすることである。業務上の正本、認証、認可、勤怠計算、経費計算、承認、文書台帳、ファイルscanは既存実装を使用する。

対象browser/OSはChrome、Edge、Safariの現行版および直前版、Android Chrome、iOS Safariである。初版ではpush通知を実装しない。

## 2. Non-goals

- 勤怠、客先工数、経費、給与その他の業務計算をbrowser側へ複製・変更しない。
- 独自の認証、権限、user scope、approval engineを作らない。
- 給与、銀行、マイナンバー、文書本文、PDF、attachment、receipt、token、password、他人のPIIをoffline保存しない。
- portal利用者向けPWAやportal認証chainを内部要員PWAへ統合しない。
- push通知、background syncの自動実行、offlineでのsubmit/approve/reject/close/cancel/withdrawを実装しない。

## 3. Requirements

### PW-R1 Service Worker、manifest、cache境界

1. アプリはmanifest、icon、version付きService Worker、更新通知を提供しなければならない。
2. PWA installは任意とし、installしなくても既存のbrowser navigationが成立しなければならない。
3. Service WorkerがCache Storageへ保存できるのは、allow-listした同一originの静的shell/assetsとoffline fallbackだけでなければならない。
4. `/api/**`、`/portal/**`、document、payroll、bank、PDF、attachment、receipt、その他PII responseをService Worker cacheへ保存してはならない。
5. 動的page/APIおよび上記PII routeはnetwork-onlyとし、該当responseには`Cache-Control: no-store`を付ける。
6. push通知は未実装であることを仕様、UI、テストに明記する。

受入条件:

- Cache Storageを全cache・全requestについて検査し、禁止routeの保存が0件である。
- SW更新時に旧versionのcacheを削除し、waiting workerの更新通知と切替が一度だけ表示される。
- 未installの通常browserでもshellとserver-rendered pageが壊れない。

### PW-R2 最小draftとoffline queue

1. timesheet/attendanceは最小入力値のdraftおよびdaily save/delete queueを保存できる。
2. expensesはdraft create/updateだけをqueueできる。receipt upload/downloadとsubmitはonline-onlyとする。
3. change-requestはrequest typeごとのallowlist payloadによるdraft createだけをqueueできる。attachment、submit、resubmitはonline-onlyとする。
4. leave、profile、survey、1on1、lifecycle、submit、approve、reject、close、cancel、withdrawはonline-onlyとする。
5. IndexedDBのqueue recordは必ず`clientRequestId`、canonical payload hash、`baseVersion`、user scope、screen、month、createdAtを持つ。
6. draft/queue保持期間は30日とし、期限切れは自動送信せず、利用者へ破棄または再入力を促す。
7. 成功送信、logout、user switchでは該当userのdraft/queueを即時削除する。
8. queue payloadにはpassword、token、添付binary、給与、銀行、マイナンバー、他人のPIIを含めない。

受入条件:

- offline入力後にbrowserを再起動しても、同一userの最小draftが復元できる。
- online復帰後は同一commandが1回だけ適用され、成功後queue recordが消える。
- 30日を超えたrecordは送信されず、表示上も期限切れとして扱われる。
- serverは`X-Client-Created-At`をserver時計で検査し、30日超過commandを`QUEUE_EXPIRED`として拒否する。欠損・0以下・5分超の未来時刻も受理しない。

### PW-R3 Server idempotency、version、既存domain service

1. queueの再送は`X-Client-Request-Id`で識別し、serverはuser、operation、request IDの一意境界を持つ。
2. serverはcanonical requestからpayload hashを再計算し、同一ID・同一hashは元の結果をreplayしなければならない。
3. 同一ID・異なるhashは409として拒否しなければならない。
4. update/delete系は`baseVersion`を必須とし、staleなversionは409として拒否しなければならない。
5. createの未作成対象は`baseVersion=0`とし、server応答で確定したresource versionを次のcommandへ引き継ぐ。
6. idempotency処理は既存のattendance、WorkRecord、expense、change-request serviceのtransaction境界内で行い、PWA独自の計算・状態遷移を持たない。
7. 同一commandを二重click、timeout後再送、browser再起動後再送しても業務副作用は1件でなければならない。

受入条件:

- 同一ID・同一hashの2回送信が同一結果となり、DB業務行・監査・通知の重複がない。
- 同一ID・異なるhashが409となり、既存データを変更しない。
- 同時更新の一方だけが成功し、他方は409となる。last-write-winsが発生しない。
- 業務更新とidempotency ledgerのCOMPLETED ackは同一transactionでcommitされ、片方だけが確定する障害窓を持たない。

### PW-R4 認証、session expiry、logout、user switch

1. 既存Spring Security session、CSRF、PersistentSessionFilterを正本とする。
2. queue flush前にserver-issued opaque user contextを検証し、現在principalと一致しないqueueを表示・送信してはならない。
3. session expiry（401）時はqueue flushを停止し、login後に同一user contextを検証してから再開する。
4. logout時はclient側のactive user scopeを直ちに無効化し、SWにもclear指示を送り、server logoutが完了またはofflineでもAのqueueを送信不能にする。
5. user A logout後にuser Bがloginした場合、Aのdraft/queue/cacheをBへ表示・送信してはならない。
6. shared deviceまたは端末紛失を想定し、raw usernameやuser IDをstorage keyとして使用しない。context不一致時はfail-closedとする。

受入条件:

- A logout→B loginでAのdraftが0件表示、Aのcommandが0件送信となる。
- session expiry中のqueueが送信されず、同一user再認証後だけ再開する。
- user context endpoint障害時はqueue送信を行わない。

### PW-R5 409 conflict UX

1. 409 responseはresource、serverVersion、client baseVersion、server value、client value、差分fieldを返さなければならない。
2. UIはserver/client差分を同時表示し、自動上書き、暗黙のmerge、last-write-winsを行ってはならない。
3. 利用者がserverを再取得、draftを破棄、または差分を確認してfresh baseVersionで手動再適用できるようにする。
4. 競合中のqueue recordは利用者の選択まで保持するが、自動retryしてはならない。

受入条件:

- stale draftの再送が409になり、server値とclient draftのfield差分が表示される。
- conflict解消前にserver値がclient値で上書きされない。

### PW-R6 Mobile shell、操作性、accessibility

1. 390×844の実viewportで主要入力、保存、同期状態確認、競合確認を完了できる。
2. horizontal scrollを発生させず、touch target、keyboard focus、screen reader label、error summary、focus restoreを提供する。
3. offline、同期中、同期済み、競合、失敗、再認証必要、期限切れを区別して表示する。
4. 既存sidebar/navigation/common.jsの共通挙動を再利用し、要員専用のdesktop管理画面へ影響を与えない。

受入条件:

- 390×844、desktop、tabletで主要routeに水平scrollがなく、主要操作が完了する。
- 50ms間隔のdouble-clickでボタンがprocessingになり、toastと副作用が1件だけになる。

### PW-R7 PII routeのHTTP cache hardening

次のinventory対象routeをnetwork-only/no-store修正対象とする。

- `/api/my/payroll/**`、`/my/payroll`
- `/api/my/expenses/{id}/receipt`
- `/api/my/change-requests/{id}/attachment`
- `/api/my/timesheet/{workRecordId}/report.pdf`
- `/api/documents/**`、`/api/files/**`
- `/api/contract-documents/**`
- acceptance、compliance documentのdownload/preview
- `/portal/**`、`/api/portal/**`のPDF、document、submission、bank-account関連route
- `/api/payroll/**`、`/api/reconciliation/**`

既存のscope、scan、download ACL、MFA、監査を変更せず、response headerとSW policyだけを補強する。

### PW-R8 Verification、monitoring、handoff

1. PlaywrightでChromium/Firefox/WebKitを使い、Chrome/Edge/Safari対象とAndroid Chrome/iOS Safariの実行可能性を記録する。
2. 390×844、offline→online、session expiry、SW update、double-click、browser restart、409、A→B、CacheStorage検査を実測する。
3. build SHA、Base SHA、migration、fixture checksum、actor、browser/version、viewport、network profile、trace/video/screenshot/HAR、console/page error、DB before/afterを証拠として保存する。
4. monitoringはqueue enqueue/success/conflict/expired/failure、SW version/update、cache rejectionをPIIなしで記録する。
5. 完了時にremote Head、Base SHA、test result、completion matrix、CacheStorage inspection evidenceを独立Reviewへ渡す。実装対話ではPRを作成しない。
