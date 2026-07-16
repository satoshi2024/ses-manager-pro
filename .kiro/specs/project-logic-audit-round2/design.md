# Design — 全体ロジック再監査・第2次是正

## 1. 実装方針

既存の Spring MVC + Thymeleaf + jQuery + MyBatis-Plus 構成を維持する。
一括リファクタリングは行わず、次の順序で依存関係を解消する。

1. 権限・IDOR・system field 書換えを先に止める。
2. validation、状態機械、DB unique 制約を追加する。
3. route と未完成 UI を閉じる。
4. 外部連携、メール、HTTP/audit semantics を完成させる。

AI の mock/rule 切替は明示的な既存仕様のため、本 spec では変更しない。

## 2. API 入力モデル

### 2.1 DTO 規則

write API は entity を直接受けず、area ごとの request DTO を使用する。

共通規則:

- Create DTO: 業務入力 field のみ。ID と system field は持たない。
- Update DTO: 更新可能な業務入力 field のみ。ID は URL path から取得する。
- status、履歴、作成者等に専用 service がある場合、通常 Update DTO に含めない。
- Controller は DTO validation と path parameter の受取りだけを行い、
  関係 validation と transaction は Service に置く。

優先対象:

- `SalesActivityCreateRequest` / `SalesActivityUpdateRequest`
- `EngineerCreateRequest` / `EngineerUpdateRequest`
- `ProjectCreateRequest` / `ProjectUpdateRequest`
- `CustomerCreateRequest` / `CustomerUpdateRequest`
- `ProposalCreateRequest`
- `ContractCreateRequest` / `ContractUpdateRequest`
- `EmailTemplateCreateRequest` / `EmailTemplateUpdateRequest`
- `SkillTagCreateRequest` / `SkillTagUpdateRequest`

移行中も response DTO/既存 JSON field 名は維持し、frontend への不要な破壊変更を避ける。

### 2.2 SalesActivity ownership

`SalesActivityService` に次を追加する。

```java
SalesActivity getOwnedOrThrow(Long customerId, Long activityId);
SalesActivity create(Long customerId, SalesActivityCreateRequest request);
SalesActivity update(Long customerId, Long activityId, SalesActivityUpdateRequest request);
void complete(Long customerId, Long activityId);
void delete(Long customerId, Long activityId);
```

`getOwnedOrThrow` は `id + customer_id` の複合条件で取得し、0 件なら
`BusinessException.of(404, "error.salesActivity.notFound")` とする。
Controller 内の `setCustomerId` だけに依存しない。

## 3. 権限・通知設計

### 3.1 menu prefix

新規 migration:

- `V22__fix_contract_document_menu_prefix.sql`

変更:

- `contract-document.path_prefix` を `/contract-document` に変更する。
- API prefix は `/api/contract-documents` を維持する。
- 管理者の `t_role_menu` mapping を追加する。
- 営業、HR、マネージャーの既存 mapping は維持する。

追加 UI:

- `ContractDocumentPageController`
- `templates/contract-document/list.html`
- `static/js/modules/contract-document.js`
- sidebar の `contract-document` menu

`MenuPermissionFilter` は起動後に全 menu の `pathPrefix/apiPrefix` を検査し、
同一 prefix が複数 menu に存在する場合は error log を出す。
permission integration test では同長 prefix が 0 件であることを assert する。

### 3.2 notification visibility

新規 migration:

- `V23__add_notification_menu_scope.sql`

DB:

```sql
ALTER TABLE t_notification
  ADD COLUMN menu_key VARCHAR(50) NULL,
  ADD INDEX idx_notification_menu_key (menu_key);
```

post-baseline 機能のため V1 へは逆輸入せず、H2 test schema と
`application-test.yml` の schema 初期化対象を同期する。

`NotificationService.publish` は次へ変更する。

```java
void publish(
    String type,
    String title,
    String message,
    String linkUrl,
    String dedupeKey,
    String menuKey
);
```

`NotificationMapper` の一覧、count、mark-all-read は current role を受け取り、
以下を可視条件とする。

```text
menu_key IS NULL
OR current role = 管理者
OR t_role_menu に current role + menu_key の mapping が存在する
```

既読 INSERT も可視通知だけを対象にする。

## 4. validation・状態機械・DB 制約

### 4.1 数値と日付

DTO へ次を設定する。

- 金額、経験年数、人数、工数: `@PositiveOrZero`
- commission rate: `@DecimalMin("0.0")` + `@DecimalMax("100.0")`
- activity type/title/date: `@NotBlank/@NotNull`
- email: `@Email`
- remarks/content: DB column 長に合わせた `@Size`

`WorkRecordServiceImpl.saveHours` は `YearMonth.parse(workMonth)` を行い、
対象月 `[monthStart, monthEnd]` と契約 `[startDate, endDate or infinity]` が
重なることを確認する。`status=解約` は拒否する。

`InvoiceService.generate` も `YearMonth` を service 境界で解析し、
customer の存在を明示的に確認する。

skill replace は次の順序にする。

1. parent が存在することを確認。
2. null skillId、重複、存在しない skill を検証。
3. transaction 内で既存 relation を削除。
4. parent ID を server 側で設定して batch insert。

### 4.2 contract status

`ContractUpdateRequest` から status を除外する。
新規 endpoint:

```http
PUT /api/contracts/{id}/status
Content-Type: application/json

{ "status": "稼動中" }
```

`ContractStatusUpdateRequest` は `@NotBlank status` のみを持つ。

許可遷移:

```text
準備中 -> 稼動中, 解約
稼動中 -> 終了, 解約
終了   -> なし
解約   -> なし
```

`ContractService.changeStatus` は contract update、要員状態再計算、
必要な通知を同一 transaction で行う。

### 4.3 active proposal uniqueness

同一 `engineer_id + project_id` について、status が
`書類選考中/一次面接/二次面接/結果待ち` の record を 1 件に制限する。

新規 migration `V24__add_active_assignment_and_proposal_unique_keys.sql` で
generated column を使用する。

```text
t_proposal.active_engineer_id
t_proposal.active_project_id
t_engineer_sales.active_engineer_id
t_engineer_sales.active_sales_user_id
t_engineer_sales.active_primary_engineer_id
```

terminal/解除済み record は generated column を NULL にし、履歴と再作成を許可する。
適用前確認 SQL を migration comment に残し、重複 data を自動削除しない。

Service は `DuplicateKeyException` を次へ変換する。

- active proposal duplicate: `error.proposal.activeDuplicate`
- active assignment duplicate: `error.engineerSales.duplicate`
- active primary duplicate: `error.engineerSales.primaryConflict`

## 5. route 設計

canonical route:

| 対象 | URI |
|---|---|
| 要員詳細 | `/engineer/detail?id={id}` |
| 契約一覧 | `/contract/list` |
| 案件一覧 | `/project/list` |
| 顧客詳細 | `/customer/{id}` |
| 候補者詳細 | `/candidate/detail?id={id}` |
| 電子契約書 | `/contract-document` |
| 給与情報 | `/payroll` |

修正対象:

- `NotificationGenerateService`
- `availability-calendar.js`
- notification publisher 各所
- `ProjectPageController`

`project/detail.html` は現時点で存在せず、project CRUD は list modal で完結しているため、
本 spec では新規 detail page を作らず、死んだ mapping を削除する。

route test は page controller の mapping と通知生成結果の両方を検証する。

## 6. 電子契約書

### 6.1 CloudSign client

`CloudSignClientImpl` は `AppConfig` の `RestTemplate` を DI し、直接 `new` しない。
send/status の両方で次を設定する。

- `Authorization: Bearer <token>`
- `Accept: application/json`
- JSON request 時は `Content-Type: application/json`

`cloudsign.enabled=false`、base URL 空、token 空のいずれかは
`error.contract.document.providerNotConfigured` とする。

status response の signed PDF/certificate URL または bytes を client result へ詰め、
service は `uploads/contracts/{contractId}` 配下へ保存する。
保存後に SHA-256、completedAt、lastSyncedAt を更新する。

### 6.2 API/UI

追加:

```http
GET /api/contract-documents/{id}/pdf
GET /api/contract-documents/{id}/signed-pdf
GET /api/contract-documents/{id}/certificate
```

file path を response に直接返さず、認証済み download endpoint 経由にする。
電子契約書一覧 page は契約、template、宛先、状態、download/send/sync 操作を提供する。

## 7. freee 給与連携

追加:

- `PayrollPageController`
- `templates/payroll/list.html`
- `static/js/modules/payroll.js`

画面は以下を提供する。

1. 接続状態と OAuth 接続/解除。
2. freee 社員一覧。
3. SES 要員との紐付け/解除。
4. 年月、明細種別で給与明細を検索。

`FreeeOAuthController` と `FreeePayrollApiController` は
`SecurityUtils.currentUserId()` を service へ渡す。

token 利用:

1. `tokenExpiresAt <= now + 60 seconds` なら request 前に refresh。
2. provider が 401 を返した場合だけ refresh 後に 1 回再試行。
3. refresh 失敗時は connection を勝手に削除せず、再接続が必要な business error を返す。
4. token 更新は transaction とし、同時 refresh は connection row lock または
   optimistic update で 1 回に集約する。

prod の `freee.token-encryption-key` は 32 byte 以上を必須とし、
default key を削除する。

## 8. メール送信

新規 migration:

- `V25__create_mail_delivery.sql`

`t_mail_delivery`:

- id
- dedupe_key
- template_id
- recipient
- subject
- body
- status (`QUEUED/SENDING/SENT/FAILED/DRY_RUN`)
- retry_count
- last_error
- requested_by
- sent_at
- created_at/updated_at

`MailService.queueWithTemplate` は template/recipient を同期検証し、
delivery record を作成して `MailDispatchResult` を返す。

```json
{
  "deliveryId": 123,
  "status": "QUEUED"
}
```

SMTP 未設定時は `DRY_RUN` record とし、画面にも dry-run と表示する。
async worker は delivery ID を受け取り、成功/失敗状態を更新する。
dedupe key は proposal ID + template ID + recipient + request token から生成する。

## 9. HTTP・例外・監査

### 9.1 HTTP status

`ApiResult` JSON shape は維持する。
`GlobalExceptionHandler` は `ResponseEntity<ApiResult<?>>` を返す。

mapping:

| 種別 | HTTP/code |
|---|---:|
| validation | 400 |
| unauthenticated | 401 |
| unauthorized | 403 |
| not found | 404 |
| state/duplicate conflict | 409 |
| unexpected | 500 |

`BusinessException` は最低限 `code/messageKey/args` を維持し、
各呼出し箇所で 404/409 を明示する。既存のすべてを一度に enum 化しない。

`SES.api._fetch` は HTTP status にかかわらず JSON body を解析可能な場合は解析し、
`result.message` を toast に表示する。
jQuery 共通 handler も responseJSON.message を優先する。

### 9.2 audit

新規 migration:

- `V26__extend_audit_log_result.sql`

追加 field:

- `application_code`
- `success_flag`

`ApiAuditFilter` 単独では final body code を安全に取得しにくいため、
更新系 API の完了後に final response を取得できる interceptor/filter 構成へ変更する。
403 のように Controller 到達前に拒否された request も記録する。

保存内容:

- username
- method
- URI
- final HTTP status
- application code
- success flag
- timestamp

request/response body、query 内の token、password、Webhook URL は保存しない。

## 10. test・migration・rollout

test 順序:

1. 問題を再現する failing test を追加。
2. service/controller/migration を実装。
3. 対象 test class を実行。
4. 全 test を実行。
5. Docker 使用可能時は MySQL 8 migration smoke test。
6. Demo を実行して task を `[x]` にする。

DB migration 適用前:

- active proposal duplicate
- active engineer-sales duplicate
- multiple active primary

を検出する SQL を実行する。重複がある場合は migration を失敗させ、
業務判断なしに record を削除・統合しない。

既存未 commit file と重なる変更は、開始前に `git diff` を確認して手動統合する。

