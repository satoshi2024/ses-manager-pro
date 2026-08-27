# mobile-pwa-self-service 設計

## 1. Design baseline

NF-04は既存のSpring Security、`/my/**` API、`AttendanceService`、`WorkRecordService`、`ExpenseRequestService`、`EngineerChangeRequestService`、`DocumentService`、`common.js`を再利用する。PWA層はshell、最小draft、queue、sync状態、conflict表示だけを所有する。

雇用勤怠（`t_employee_attendance`/`t_attendance_month`）と客先工数（`t_work_record_daily`/`t_work_record`）は別の正本である。PWAは既存serviceへraw inputを渡し、稼働時間、休憩、割増、精算、税、承認状態を計算しない。

## 2. Data flow

```text
Authenticated browser
 ├─ Service Worker
 │   ├─ static shell/assets allow-list ──> Cache Storage
 │   └─ /api /my /portal /PII routes ────> network-only + no-store
 │
 ├─ pwa-queue.js / common.js
 │   ├─ GET /api/my/session-context ─────> opaque current user context
 │   ├─ IndexedDB draft/queue ───────────> user-scoped minimal input
 │   └─ same-origin fetch + CSRF header ─> existing domain API
 │
 └─ Server transaction
     ├─ user + operation + clientRequestId UNIQUE
     ├─ canonical payload hash replay / mismatch 409
     ├─ baseVersion CAS / stale 409
     └─ existing domain service and calculation
```

Service Workerはmutationをqueueへ変換しない。page側のsync moduleがqueueを読み、CSRF tokenを取得して既存APIへ送信する。SWは全APIと全認証済みdynamic responseをnetworkへ通過させる。

## 3. Decision tables

### 3.1 Time / as-of

| 対象 | 正本時刻 | offline時 | as-of/再計算方針 |
|---|---|---|---|
| draft/queue | server受信時刻が監査正本、client createdAtは送信時の検査入力兼表示用 | 端末時刻だけを信用して期限判定しない | `X-Client-Created-At`をserver現在時刻と比較し、30日超過または許容範囲外のcommandはclaim/送信しない |
| timesheet daily | `workDate`と既存WorkRecordの`workMonth` | raw date/time/break/remarksだけ保持 | `WorkRecordService.saveDaily/deleteDaily`が既存の月締め、契約期間、計算を判定 |
| employment attendance daily | `workDate`と既存AttendanceMonth | raw clock/break/remarksだけ保持 | `AttendanceService`/`AttendanceCalculator`がcalendar、scope、割増、月次集計を判定 |
| expense draft | `expenseDate`、server created/updated time | 最小入力だけ保持 | 金額validation、scope、approval、accounting連携は既存serviceが判定 |
| change-request draft | server created/updated time | allowlist payloadだけ保持 | fingerprint、approval、master反映は既存serviceが判定 |
| cache | static asset version | dynamic responseは保存しない | SW versionとasset manifestでcacheを置換し、業務as-ofを持ち込まない |

### 3.2 Subject × operation × visibility

| Subject | Operation | 可視範囲 | Offline可否 |
|---|---|---|---|
| 要員 | timesheet/attendance daily save/delete | link解決された本人のみ | 最小draftとqueue |
| 要員 | expense draft create/update | 本人のdraftのみ | 最小draftとqueue |
| 要員 | change-request draft create | 本人のallowlist payloadのみ | 最小draftとqueue |
| 要員 | payroll/profile/leave/survey/1on1/lifecycle | 既存本人scope | online-only |
| 要員 | submit/approve/reject/close/cancel/withdraw/resubmit | 既存state machine scope | online-only |
| portal user | portal page/API/document/bank | portal専用chain/scope | cache/draft/queue不可 |
| static asset | shell/icon/css/js/offline fallback | allow-list assetのみ | Cache Storage可 |
| sessionなし/別user | draft/queue | 0件 | flush不可 |

### 3.3 State × conflict

| Client state | Server state | 結果 | UI |
|---|---|---|---|
| queued | network unavailable | 保留 | offline/再送待ち、retryしない |
| queued | same request ID + same hash | replay | 同一結果を同期済みとしてqueue削除 |
| queued | same request ID + different hash | 409 | requestをconflict固定、差分表示 |
| baseVersion=current | current | CAS成功 | server responseのversionを保存 |
| baseVersion<current | current changed | 409 | server/client差分、手動再適用のみ |
| session expired | any | 401、flush停止 | loginへ遷移、同一context確認後再開 |
| user A scope | user B context | 送信拒否 | A storage clear、Bには非表示 |
| draft age>30 days | any | 期限切れ | 自動送信せず破棄確認 |
| double-click same request ID | any | 1副作用 | processing disable、toast 1件 |

## 4. Cache policy

### 4.1 Cache allow-list

`CACHE_NAME = ses-pwa-shell-<version>`とし、same-originの次の静的資産だけをcacheする。

- `/manifest.webmanifest`
- `/offline.html`
- `/favicon.svg`、PWA icon
- `/css/**`、`/js/**`、`/lib/**`のうち静的assetとしてallow-listしたresource

実装はrequest methodがGET、originが同一、URL pathがallow-list、responseが成功かつopaqueでない場合だけ`cache.put`する。navigation、`/my/**`、`/portal/**`、`/api/**`、download/preview、query responseはcacheしない。外部CDNはcacheしない。

### 4.2 Server headers

Payroll page/API、document/file/download、receipt、attachment、PDF、portal responseは、静的assetを除く全dynamic routeへ適用する共通のno-store filterまたは既存controllerの`CacheControl.noStore()`で統一する。エラーresponseも認証済みPII routeではno-storeを維持する。`common.js`のAPI/download fetchには`cache: 'no-store'`を指定する。

## 5. IndexedDB model

DB名は`ses-pwa-self-service`、schema versionを明示する。実装ではqueue record自身を最小draftのimmutable snapshotとして扱い、別の入力値storeを持たない。raw user ID/usernameはclient recordへ保存せず、serverが発行するopaque contextを`userScope`としてrecordに束縛する。contextは内部IDへHMACで束縛したserver-issued time lease（発行時刻＋nonce）を含み、server受信時刻を正本にrecord単位の`createdAt`/30日retentionを検査する。scope leaseの更新で同一ユーザーのrecordを早期削除せず、同一user確認後に旧scope recordを新scopeへ再束縛する。contextはsessionに結びつき、flush前にserverで再検証する。

### 5.1 Store

`queue`（最小draftを兼ねる）:

```text
{
  clientRequestId, payloadHash, baseVersion, userScope, screen, month,
  method, path, resourceKey, payload, createdAt, expiresAt, status
}
```

payloadは画面・method・pathごとのallowlist、32KiB、深さ、plain object、再帰的なreceipt/attachment/binary/secret/PII key検査を通過した最小入力に限定する。logoutではDB全体をclearし、user switchでは旧scopeのrecordだけを削除する。削除完了を確認できない場合はflushをfail-closedする。期限切れrecordは送信禁止にしてpayloadを除去し、破棄を明示的に促す。`meta`やraw user ID/usernameは保存しない。

## 6. Session context and user boundary

`GET /api/my/session-context`を新設し、authenticated internal userへopaque `userScope`と`preserveQueue`だけを返す。提示scopeが同一userとして署名検証できる場合だけ、scope lease更新時に既存recordを新scopeへ再束縛する。別principal・無効scopeでは旧scope recordだけを削除し、Bへ表示・送信しない。許可されたoffline operationはserver側のPWA endpoint allowlistで固定し、engineer linkや本人データはこのendpointへ含めない。認証失効、別principal、endpoint error、role/link変更時はqueue flushを停止する。オンラインでcontextを再検証できない場合、clientは保存済みscopeへfallbackせずfail-closedとする。

logout formのsubmit前にclient scopeをquarantine/clearし、SWへ`CLEAR_USER_SCOPE`を送る。server logout完了後にlogin pageへ遷移する。offline logoutではserver revokeは未確認だが、local queueを削除して再接続後の自動送信を禁止する。

## 7. Server idempotency contract

### 7.1 Request

- Header: `X-Client-Request-Id`（UUID、1 commandにつき不変）
- Header: `X-Client-Payload-Hash`、`X-Client-Base-Version`、`X-Client-Created-At`（epoch milliseconds）。bodyは`screen`、`month`、`payload`を持つ。
- Hash: serverが`operation`（screen + HTTP method + route）、`baseVersion`、`month`、canonical JSON `payload`、`screen`をUTF-8 bytesで正規化しSHA-256を再計算する。clientのhashはqueue監査用であり、serverは信用しない。V112以前の旧hash（operationなし）は、旧queue/ledgerの移行期間だけ同一HTTP operationへ再束縛する場合に限って受理する。
- Retention: serverは`X-Client-Created-At`をserver時計と比較し、record単位で30日超過を`QUEUE_EXPIRED`の409として拒否する。5分超の未来時刻、欠損、0以下も400で拒否する。scope lease失効はrecord retentionとは別に扱い、同一userとして更新したscopeへrecordを再束縛する。期限判定後はpayloadを保存・再送せず、client側でもERROR recordのpayload/hash/conflictを除去して破棄または再入力を促す。
- Response: `ApiResult<T>`。PWA経路の成功responseはid/status/version等の最小ackだけとし、replay時はledgerに保存した同じ最小ackを返す。同一userのscope lease更新後も、署名検証済みの現在scopeと`user_id`で既存ledger行を特定し、scope hashの更新だけで同hash replayを拒否しない。

### 7.2 Storage

汎用外部連携の`t_integration_job`やcompliance ledgerを流用せず、NF-04専用の`t_pwa_client_mutation` ledgerを追加する。`user_id,operation,client_request_id`を冪等性境界とし、opaque scope hash、screen/month、payload hash、base version、status、最小ack JSON、監査時刻を持つ。raw payload、入力内容、PII response bodyは保存しない。ledger本体はV112、operation境界の後方互換拡張はV113とする。

処理順序は、client作成時刻とheaderを検証し、domain mutationと同一transactionでledger keyをinsert、既存keyならscope/hash照合、同一hashのCOMPLETEDは最小ackをreplay、異hashは409、未登録なら既存domain serviceを実行し同じtransactionでledgerへ最小ackを保存する。V112以前のoperation=NULL行は行ロック中に現在のHTTP operationへ一度だけ再束縛し、以後は別operationを409とする。legacy/current hashは同一operationの移行期間だけ相互に受理する。ledger insert競合は再取得して同じ規則を適用する。PROCESSINGは二重実行を拒否し、失敗時はdomain mutationとclaim/ackを一緒にrollbackする。

## 8. Domain adapters

| Domain | Existing API | baseVersionの正本 | 追加設計 |
|---|---|---|---|
| timesheet | `/api/my/timesheet/daily`、daily delete | `WorkRecord.version`を対象row versionとして扱う | PWA入口のtransactionで対象rowを`FOR UPDATE`し、snapshot/baseVersion比較から既存`WorkRecordService`のlock・検証・CASまで同じtransactionで直列化 |
| employment attendance | `/api/my/attendance/daily`、daily delete | `AttendanceMonth.version` | PWA入口のtransactionで月次rowを`FOR UPDATE`し、snapshot/baseVersion比較から更新・削除・計算の既存`AttendanceService`委譲まで同じtransactionで直列化 |
| expense | `/api/my/expenses` POST/PUT | expense row `version`、createは0 | draft create/updateだけidempotent/CAS化 |
| change request | `/api/my/change-requests` POST | 起票元`Engineer.version`（起票時点のprofile基準） | allowlist payloadのdraft createだけidempotent化 |

submitやapproval state transitionへqueue headerを付ける場合もonline-onlyの二重click防止に限り、offline保存・自動再送は行わない。

## 9. Conflict UX

409のdataは次の形を基本とする。`fields`はserver/clientの項目差分、`resource/resourceId`は再適用対象を固定するために返す。UIはserverを再取得してversionを更新した後、ユーザー確認を経たmanual reapplyだけを許可し、自動merge/LWWを行わない。

```json
{
  "resource": "expense",
  "resourceId": 123,
  "serverVersion": 4,
  "clientBaseVersion": 3,
  "fields": [
    {"name": "description", "serverValue": "server", "clientValue": "draft"}
  ],
  "replayable": false
}
```

client draftはimmutable snapshotとして保持する。`serverを採用`、`draftを破棄`、`差分を反映して再編集`のいずれかを人が選択し、再編集時だけfresh server versionをbaseVersionへ設定する。

## 10. SW update、cleanup、monitoring

register時は既存registrationを再利用し、`updatefound`/`waiting`を一度だけ通知する。userが更新を選択したときだけ`SKIP_WAITING`を送信し、`controllerchange`後にreloadする。activate時は現行cache name以外の`ses-pwa-shell-*`だけ削除し、IndexedDB queueを削除しない。queue cleanupはserver ack、logout/user switch、30日expiryで別途行う。

監視eventは`sw_version`、operation名、結果code、queue count、latency、hash prefixだけを記録し、user ID、payload、給与、銀行、文書名を記録しない。

## 11. Test evidence design

通常UI harnessとSW/cache security runを分離する。各runはbuild SHA、Base SHA、actor、browser/version、390×844または指定viewport、network profile、trace/video/screenshot/HAR、console/page error、DB before/afterを保存する。Cache security runではSWを有効化し、Cache Storageの全entry URLと禁止route件数を出力する。
