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
 ├─ pwa-sync.js / common.js
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
| draft/queue | client createdAtは表示用、server受信時刻が監査正本 | 端末時刻を信用して期限判定しない | server現在時刻で30日判定し、期限超過commandは送信しない |
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

Payroll page/API、document/file/download、receipt、attachment、PDF、portal responseは共通のno-store helperまたは既存controllerの`CacheControl.noStore()`で統一する。エラーresponseも認証済みPII routeではno-storeを維持する。`common.js`のAPI/download fetchには`cache: 'no-store'`を指定する。

## 5. IndexedDB model

DB名は`ses-pwa-self-service`、schema versionを明示する。raw user ID/usernameはkeyにせず、serverが発行するopaque contextをSHA-256化した`userScopeKey`をnamespaceにする。contextはsessionに結びつき、flush前にserverで再検証する。

### 5.1 Stores

`drafts`:

```text
{ id, userScopeKey, screen, month, targetKey, fields, createdAt, updatedAt, expiresAt }
```

`queue`:

```text
{
  id, userScopeKey, operation, endpoint, method, screen, month,
  targetKey, payload, clientRequestId, payloadHash, baseVersion,
  createdAt, expiresAt, state, attemptCount, lastErrorCode
}
```

`meta`:

```text
{ key, swVersion, userScopeKey, contextVersion, lastSyncAt }
```

payloadは最小入力に限定し、receipt/attachment/binary/secretを拒否する。IndexedDBのclearはuser namespace単位で実行し、logoutでは全user namespaceも列挙して削除する。削除完了を確認できない場合はflushをfail-closedする。

## 6. Session context and user boundary

`GET /api/my/session-context`を新設し、authenticated internal userへopaque `userScope`、context version、server time、許可されたoffline operation keyだけを返す。engineer linkや本人データはこのendpointへ含めない。認証失効、別principal、endpoint error、role/link変更時はqueue flushを停止する。

logout formのsubmit前にclient scopeをquarantine/clearし、SWへ`CLEAR_USER_SCOPE`を送る。server logout完了後にlogin pageへ遷移する。offline logoutではserver revokeは未確認だが、local queueを削除して再接続後の自動送信を禁止する。

## 7. Server idempotency contract

### 7.1 Request

- Header: `X-Client-Request-Id`（UUID、1 commandにつき不変）
- Body/query: `baseVersion`
- Hash: serverがmethod、path、query、canonical JSON bodyのUTF-8 bytesからSHA-256を再計算する。clientのhashはqueue監査用であり、serverは信用しない。
- Response: `ApiResult<T>`。replay時は元のresourceを再取得して同じ結果形を返す。

### 7.2 Storage

汎用外部連携の`t_integration_job`やcompliance ledgerを流用せず、NF-04専用の`client mutation` ledgerを追加する。候補名は`t_my_client_mutation`とし、`tenant_id,user_id,operation,client_request_id`のunique、`payload_hash`、`resource_type/resource_id`、`result_hash`、`state`、`expires_at`、`version`、監査時刻を持つ。raw payloadとPII response bodyは保存しない。migrationは実装開始時の全Flyway locationの最新+1（現時点ではV111の次だが、開始時に再確認）とする。

処理順序は、ledger keyをlock/insert、既存keyならhash照合、同一hashはresource/result replay、異hashは409、未登録なら既存domain serviceを同一transactionで実行しledgerへresource reference/result digestを保存する。ledger insert競合は再取得して同じ規則を適用する。

## 8. Domain adapters

| Domain | Existing API | baseVersionの正本 | 追加設計 |
|---|---|---|---|
| timesheet | `/api/my/timesheet/daily`、daily delete | `WorkRecord.version`を月次aggregate versionとして扱う | save/deleteにexpected versionを渡し、`WorkRecordService`のCASを維持 |
| employment attendance | `/api/my/attendance/daily`、daily delete | `AttendanceMonth.version` | `AttendanceService`へexpected month versionを渡し、計算は既存calculatorのみ |
| expense | `/api/my/expenses` POST/PUT | expense row `version`、createは0 | draft create/updateだけidempotent/CAS化 |
| change request | `/api/my/change-requests` POST | createは0 | allowlist payloadのdraft createだけidempotent化 |

submitやapproval state transitionへqueue headerを付ける場合もonline-onlyの二重click防止に限り、offline保存・自動再送は行わない。

## 9. Conflict UX

409のdataは次の形を基本とする。

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
