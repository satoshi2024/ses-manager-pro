# Design — 200名規模 UI・同時実行回帰ハードニング

## 1. 設計原則

1. 既存role/scope/security boundaryを弱めない。禁止UIは隠すが、server-side拒否も残す。
2. 一覧のtotalは必ずscope/filter適用後にDBが計算する。取得後Java filterでtotalを補正しない。
3. 既存endpointを他機能が利用している場合は破壊的response変更を避け、新しいpaged endpointを追加する。
4. template初期値に実在データ風dummyを置かない。
5. P1/P2は再現を失敗させるテストを先に追加し、修正後にgreen化する。
6. MySQL lock/Flyway方言はH2だけで証明しない。
7. UI文言は4localeのkey集合を同期する。

## 2. R3-001 session deadlock

### 2.1 現在のlock flow

```text
POST /login
  -> LoginSuccessHandler
     -> PersistentSessionService.register (@Transactional)
        -> SELECT sys_user ... FOR UPDATE
        -> SELECT t_user_session ... FOR UPDATE
        -> revoke over-limit rows
        -> INSERT t_user_session
```

`sys_user`の1行lockですでに同一userのregisterは直列化されている。空の`t_user_session`をさらに`FOR UPDATE`すると、異なるuserでもindex gapを共有し得る。

### 2.2 変更方針

- `PersistentSessionServiceImpl.register`は`selectByIdForUpdate(userId)`をmutexとして維持する。
- active session取得は非locking `selectActiveByUser(...)`へ変更する。
- 同じtransaction内で上限計算、revoke、insertを行う。
- `selectActiveByUserForUpdate`は使用箇所が無ければ削除する。残す場合は用途とgap lock riskをコメントする。
- deadlock一般retryを主解決にしない。外部要因deadlockへの限定retryを追加する場合も最大回数、監査重複、transaction boundaryをテストする。
- 新規migrationは原則不要。性能上index変更が必要ならMySQL `EXPLAIN`をledgerへ添付し、V1/H2/Testcontainersを同期する。

### 2.3 テスト

- H2/service:
  - 同一user 6sessionでactive 5、最古revoke。
  - 失効済み/期限切れsessionを上限へ含めない。
  - disabled userはsession発行拒否。
- MySQL Testcontainers:
  - 25 userを作り、`CountDownLatch`/`CyclicBarrier`で同時に`register`または完全login。
  - 全future成功、active session各1、deadlock 0。
  - 同一user並行6loginも最終active 5。
- Web/security:
  - login成功監査と失敗監査の重複がない。

主対象:

- `src/main/java/com/ses/service/security/impl/PersistentSessionServiceImpl.java`
- `src/main/java/com/ses/mapper/UserSessionMapper.java`
- `src/test/java/com/ses/**/PersistentSession*Test.java`
- `src/test/java/com/ses/**/ConcurrentLogin*SmokeTest.java`

## 3. R3-002〜004 capacity harness

### 3.1 parameter

`scripts/capacity-baseline.ps1`へ次を追加する。

```powershell
[string]$CredentialFile = ''
[int]$ExpectedMaxConcurrentSessions = 5
[switch]$AllowSingleCredentialSessionEviction
[switch]$RequireMetrics
[ValidateSet('steady','login-spike','session-eviction')]
[string]$Scenario = 'steady'
```

credential fileはCSV `username,password`を基本とする。読み込み後はworker数以上あることを検証し、outputへコピーしない。単一`-Username/-Password`は後方互換として残す。

### 3.2 worker/result model

全recordを次のkindで統一する。

- `setup`: login page/CSRF/form login/session cookie
- `request`: 業務API
- `monitor`: Actuator/MySQL/process

summary例:

```text
RequestedUsers=25
AuthenticatedUsers=25
SetupErrors=0
Requests=2027
RequestErrors=0
TotalErrors=0
```

`Get-RequestSummary`は`setup`を除外しない。latency percentileは業務requestだけで算出してよいが、error gateは全kindを対象にする。

### 3.3 scenario

- `steady`: credentialを順次または短いintervalでloginし、全session準備後に同じbarrierから業務loop開始。
- `login-spike`: workerがbarrierから同時loginし、session登録の耐性を測る。
- `session-eviction`: 単一credentialを使い、最大session失効規約だけを測る。明示flag必須。

### 3.4 monitor

- 監視用WebSessionを1つ作り、admin credentialでActuatorへ認証付きrequestを送る。
- Actuator authorizationが別規約なら専用credential parameterを使う。
- 401/403/404/transport errorは`Available=false`とする。
- `-RequireMetrics`時はHikari/Tomcatのどれかが取得不能なら非0終了する。
- Actuatorを`permitAll`へ変更してはならない。

### 3.5 script test

PowerShell/Pesterが無い環境でも、最低限JUnitまたは専用fixture serverからscriptを起動して以下を検証する。

- 誤password -> setup error 1、exit non-zero。
- single credential + stage 10 + max 5 -> preflight failure。
- 10 credential -> worker割当が一意。
- summary.csvとconsole/exit codeが一致。
- secret patternが成果物に無い。

## 4. R3-005 BP review

`BpAvailabilityIngestionPageController`のreview methodを次の形へ寄せる。

```java
@GetMapping("/review/{jobId}")
public String review(@PathVariable Long jobId, Model model) {
    // 必要なら存在/scopeをpage到達前に検証
    model.addAttribute("jobId", jobId);
    return "bp-availability-ingestion/review";
}
```

template:

```html
<script th:inline="javascript">
  const JOB_ID = /*[[${jobId}]]*/ null;
</script>
```

`#request`を別のrequest utilityへ置換しない。path parsingをJSへ移さない。

## 5. R3-006 契約pagination

### 5.1 API

- `ContractApiController.page` default sizeを20へ変更。
- `PageUtils.safePage(current, size, 20)`を使用。
- 画面用の上限は100としたいため、共通`PageUtils.MAX_PAGE_SIZE=1000`を全体変更せず、controllerで`Math.min(size, 100)`した値を渡すか画面専用helperを追加する。
- mapper queryはscope/filterをpage queryへ含める既存方式を維持する。

### 5.2 frontend state

`contract.js`に以下を持つ。

```javascript
let contractCurrentPage = 1;
let contractPageSize = 20;
let contractLastPage = 1;
```

- requestへ`current`/`size`を追加。
- `renderContracts(page.records)`と`renderContractPagination(page)`を分離。
- filter submitは`loadContracts(1)`。
- CRUD後は`loadContracts(contractCurrentPage)`。
- 空pageになった場合だけ`Math.max(1, current - 1)`を再取得。
- `common.page.info`を配列1引数で使用する。

## 6. R3-007/008 要員UI

### 6.1 status

- 一覧filterのoption valueを`Bench`へ変更。
- `URLSearchParams`から`status`を読み、`待機`を`Bench`へlegacy normalizeする。
- API/service/entityの正規値は変更しない。

### 6.2 detail loading/error state

- `detail.html`のname/initial/profile初期値を空またはskeletonへ変更。
- main detail containerへ`aria-busy=true`を付け、成功時に解除する。
- 全action buttonを初期disabled/hiddenとし、`renderEngineerDetail`成功後にenableする。
- `loadEngineerDetail`の`success code != 200`と`error(xhr)`を同じ`renderEngineerLoadError`へ集約する。
- error stateはstatus 403/404とmessageを安全に表示し、取得済みの部分dataを残さない。
- `currentEngineerId`は成功後のみ設定する。

## 7. R3-009 商機customer validation

`OpportunityServiceImpl`に共通methodを追加する。

```java
private Customer requireVisibleCustomer(Long customerId) {
    if (customerId == null) return null; // DTO要件に応じ必須validationは別途
    Customer customer = customerMapper.selectById(customerId);
    if (customer == null) throw BusinessException.of(404, "error.customer.notFound");
    assertCustomerScope(customerId);
    return customer;
}
```

- create/update/convertのcustomer参照をこのmethodへ統一する。
- scope leakを避ける必要がある場合は、scope checkと存在確認の順序/messageを`CrmScopeService`規約に合わせ、外部responseは同じ404へnormalizeする。
- controllerでSQL exceptionを個別catchしない。

## 8. R3-010/011 navigation

### 8.1 sidebar

`my-timesheet`はmenu permissionだけでなくSpring Security dialectで要員roleを条件にする。

```html
<li sec:authorize="hasRole('要員')"
    th:if="${allowedMenus != null and allowedMenus.contains('my-timesheet')}">
```

### 8.2 header/search

- `GlobalControllerAdvice`へ`globalSearchAllowed` booleanを追加するか、headerで`sec:authorize="hasAnyRole('管理者','営業','HR','マネージャー')"`を使う。
- buttonとmodalを同じ条件で除外する。
- `common.js`はinput不存在なら既にreturnするため、DOMを出さなければshortcut/requestも発生しないことをテストする。
- server-side `/api/search`権限は変更しない。

## 9. R3-012〜016 段階取得

### 9.1 共通page contract

画面用paged APIはMyBatis-Plus `Page<T>`を`ApiResult`で返す。

```json
{
  "code": 200,
  "data": {
    "records": [],
    "current": 1,
    "size": 20,
    "total": 147,
    "pages": 8
  }
}
```

page/filter/scopeはquery実行前に適用する。既存の非paged endpointがbatch/job/他画面で使われる場合は残す。

### 9.2 勤怠

- `GET /api/work-records/grid`へ`current`/`size`と必要filterを追加し、`Page<WorkRecordGridDto>`を返す。
- response変更の影響が大きい場合は`/grid/page`を新設し、UIだけ切替える。
- 月次確定endpointはpage parameterを受けず月全体を処理する。
- default 50、max 100。

### 9.3 提案Kanban

- 互換性のため`GET /api/proposals/kanban`のList responseは残す。
- `GET /api/proposals/kanban/page?status=&current=&size=&keyword=...`を追加する。
- frontendはcolumnごとにcurrent/total/loading stateを持ち、初回/追加loadする。
- status変更時はfrom/to columnのcountを更新し、必要なら該当columnを再取得する。

### 9.4 リード

- `LeadApiController`に`current`/`size`を追加し`Page`化するか、互換endpoint `/page`を追加する。
- default 20、max 100。
- conversion後にpage countを再計算する。

### 9.5 ToDo

- 既存`GET /api/tasks`は他consumer互換のため残し、`GET /api/tasks/page`を追加する。
- `TaskListDto`にassignee display nameを含め、N+1を避けてbatch join/mapper queryで取得する。
- frontendの通知pagination関数とtask pagination関数を別名にする。

### 9.6 Dashboard

- serviceはscope後の全候補から終了日順Top 10をDTOへ入れ、別fieldにtotalを持つ。
- DTO例: `rolloffEngineers`, `rolloffTotal`。
- `すべて見る`は`/contract/list?endDateFrom=...&endDateTo=...`等、実際に一覧filterが解釈するqueryを生成する。

## 10. R3-017 scope label

- controller/adviceまたはDashboard DTOへ`scopeType`と安全な`scopeDisplayName`を渡す。
- templateは固定「全社」を埋め込まずmessage keyを切り替える。
- `scopeType`は少なくとも`COMPANY`/`LIMITED`。将来のorganization名表示が難しい場合は「対象範囲」を使用する。
- 数値計算ロジック自体は変更しない。

## 11. R3-018 見積i18n

`quotation.js`の呼出しだけを次へ変更する。

```javascript
SES.i18n.t('common.page.info', [pageData.total, start, end])
```

共通`t`関数のsignatureをこの1件のために変更しない。static asset testまたはNode VM testで`{0..2}`が残らないことを確認する。

## 12. R3-019 候補者edit

- `candidate.js`のrow actionへ編集buttonを追加。
- `openCandidateModal(id)`はdetail GET後にcreate modalへpopulateする。
- saveはID有無でPOST/PUTを選ぶ。
- stage fieldは通常edit payloadから除外し、既存stage transition endpointだけで変更する。
- `CandidateApiController`にPUTが無い場合は`@PutMapping("/{id}")`を追加し、serviceの参照整合性/terminal ruleを再利用する。

## 13. R3-020 payroll landmark

`templates/payroll/index.html`のinner `<main>`を`<section aria-labelledby="payroll-title">`へ変更する。layout/baseのmainだけをdocument landmarkとして残す。`h1 id="payroll-title"`を維持する。

## 14. R3-021 PowerShell互換

- `.ps1`をUTF-8 BOMへ統一し、`.editorconfig`に`[*.ps1] charset = utf-8-bom`を追加する案を第一候補とする。
- repository toolingがBOMを保持できない場合は、scriptの実行時string literalをASCIIへ寄せる。単にREADMEで`pwsh`専用へ変更するだけでは、AGENTSのWindows PowerShell実行例と矛盾するため不可。
- Maven終了codeを得た直後、非0ならbuild failureを明示する。skip scanは診断用に行ってもよいが「CIと同じ範囲を検証」と成功表示しない。

## 15. テストfile計画

既存classへ無理に詰め込まず、責務ごとに次を追加/拡張する。

| 対象 | 推奨test |
|---|---|
| session MySQL | `ConcurrentLoginSessionSmokeTest` |
| session H2 | `PersistentSessionServiceImplTest`拡張 |
| capacity script | `CapacityBaselineScriptTest` |
| BP template | `BpAvailabilityIngestionPageControllerTest` |
| contract page/API | `ContractPaginationTest`、browser Demo |
| engineer status/detail | `EngineerStatusFilterTest`、`EngineerDetailScopeUiTest` |
| opportunity ref | `OpportunityWriteReferenceValidationTest` |
| role navigation | `RoleNavigationVisibilityTest` |
| paged APIs | module別controller/service test |
| i18n | `FrontendI18nContractTest`またはNode test |
| accessibility | `MobileResponsiveLayoutTest`または新規static HTML test |

Testcontainers classはDocker無しでskipされる既存方式に合わせるが、CIはskip 0 gateを維持する。

## 16. 変更禁止・回帰注意

- CSRFを無効化しない。
- 管理者bypassを理由に`/my/**`を管理者へ許可しない。
- 要員へ`/api/search`を許可しない。
- DataScope/OrganizationScopeをpage取得後filterへ戻さない。
- `PageUtils.MAX_PAGE_SIZE`を全体100へ変更し、export/batchへ副作用を出さない。
- Kanban status machine、候補者stage machine、月次締め範囲をpagination都合で変更しない。
- test dataをFlyway production seedへ入れない。

