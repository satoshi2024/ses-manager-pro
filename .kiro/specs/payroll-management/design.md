# HFP-01 freee人事労務 給与・賞与参照連携 — 設計

## 1. 設計原則

1. **公式契約優先**: `research.md`の固定OpenAPIと公式OAuth文書にないfield/pathを推測しない。
2. **既存資産を外科的に修正**: OAuth state、AES-GCM、refresh行ロック、`saasRestTemplate`、table、routeを再利用する。
3. **給与情報を複製しない**: 明細はrequest内のDTOとbrowser DOMにだけ存在し、DB/file/session/cacheへ保存しない。
4. **fail closed / fail loud**: 権限、事業所、schema、paginationの不整合は空一覧や0円へ変換しない。
5. **共有freee基盤を巻き込まない**: S11勤怠、S15会計の公開contractは維持し、HFP-01に必要なbase URL分離と回帰testだけを行う。

## 2. 変更境界

### 2.1 再利用し、書き直さないもの

- `FreeeOAuthController.authorize`: SecureRandom 24byte state生成の考え方
- `FreeeIntegrationServiceImpl.encrypt/decrypt/key`: AES-GCMランダムIV暗号化。algorithm変更や汎用KMS化は別spec
- `FreeeConnectionMapper.selectLatestForUpdate`: refresh直列化
- `FreeeIntegrationServiceImpl.refresh`: `REQUIRES_NEW`境界とrotationの原子更新
- `FreeeIntegrationService.apiGet/apiPost`: S11 consumer向け公開signature
- `FreeeIntegrationServiceImpl.bankDeposits`: S15対象のため、本specで会計endpoint/responseを是正しない
- `t_freee_connection`、`t_freee_employee_link`、`payroll` menu、route名
- `ApiResult`、`BusinessException`、`SES.api`、CSRF、共通Toast/layout

既存private `get(String)`は給与・従業員経路から外すが、S15 consumerを本specで無理に移行しない。新旧transportを丸ごと置換する大規模refactorは禁止する。

### 2.2 追加・変更する主なfile

| 層 | file | 変更 |
|---|---|---|
| config | `application.yml`, `application-prod.yml` | OAuth/HR/API baseの分離、設定説明。秘密値は環境変数のみ |
| entity/schema | `FreeeConnection`, `FreeeEmployeeLink`, forward Flyway、H2 schemas | 接続状態とlinkの事業所境界 |
| external contract | `service/freee/FreeeHrContractAdapter`および`dto/freee/hr/*`（新規） | 必要な4種類のresponseだけをtyped parse・normalize |
| service | `FreeeIntegrationService`, `FreeeIntegrationServiceImpl` | OAuth、会社検証、HR GET、pagination、mapping、給与/賞与変換 |
| DTO | `dto/payroll/*` | status、従業員番号/状態、区分付き明細、nullable金額 |
| controller | `FreeeOAuthController`, `FreeePayrollApiController`, `PayrollPageController` | error callback、role、no-store、監査、選択肢API |
| security/audit | `SecurityConfig`, 必要最小限の給与監査component | 静的境界と機微GET監査 |
| UI | `templates/payroll/index.html`, `static/js/modules/payroll.js` | inline JS分離、接続/対応付け/給与/賞与/明細UI |
| downstream | `CashFlowForecastServiceImpl` | actual会社負担優先、全null時fallback |
| test | `src/test/java/...`, `src/test/resources/freee/*` | contract/error/security/concurrency/schema/E2E証跡 |

新しい汎用HTTP framework、SDK、scheduler、給与tableは追加しない。

## 3. 設定とURL

既存`freee.api-base-url`はS11/S15が使用する共通resource baseとして維持し、意味を変更しない。次を追加する。

```yaml
freee:
  client-id: ${FREEE_CLIENT_ID:}
  client-secret: ${FREEE_CLIENT_SECRET:}
  redirect-uri: ${FREEE_REDIRECT_URI:http://localhost:8080/integrations/freee/callback}
  api-base-url: ${FREEE_API_BASE_URL:https://api.freee.co.jp}
  hr-api-base-url: ${FREEE_HR_API_BASE_URL:https://api.freee.co.jp/hr}
  oauth-base-url: ${FREEE_OAUTH_BASE_URL:https://accounts.secure.freee.co.jp/public_api}
  token-encryption-key: ${FREEE_TOKEN_ENCRYPTION_KEY:change-me-change-me-change-me-1234}
```

- OAuth: `${oauth-base-url}/authorize`、`${oauth-base-url}/token`、`${oauth-base-url}/revoke`
- HR: `${hr-api-base-url}/api/v1/...`
- 会計: `${api-base-url}/api/1/...`（既存のまま）
- test profileではMock server URLへのoverrideを許可する。
- prodでは設定されたURLがHTTPSかつ公式hostであることを検証する。client ID/secretが未設定ならアプリ全体を起動不能にせず`MISCONFIGURED`とし、認可開始時に日本語で拒否する。client IDを設定した場合はsecret、redirect URI、暗号鍵を一式検証する。
- prodのredirect URIはHTTPSかつ固定値とする。URL、secret、tokenをlogへ出さない。

## 4. データモデル

### 4.1 `t_freee_connection`

既存columnを維持し、forward migrationで次を追加する。

| column | type | 規則 |
|---|---|---|
| `connection_status` | `VARCHAR(32) NOT NULL DEFAULT 'CONNECTED'` | `CONNECTED`または`REAUTH_REQUIRED`を永続化。`DISCONNECTED`はactive rowなし、`MISCONFIGURED`は設定/row内容から導出 |

既存rowはdefaultが入るが、`company_id`、token、有効期限が欠ける場合はserviceが`MISCONFIGURED`と判定する。provider error本文やtoken error詳細は保存しない。

### 4.2 `t_freee_employee_link`

freee従業員IDは事業所内のIDであり、接続先変更時の誤対応を防ぐため次を追加する。

| column | type | 規則 |
|---|---|---|
| `freee_company_id` | `BIGINT NULL` | 新規・再確認時は必須。legacy rowは接続companyを確定できる場合だけbackfillし、確定不能ならNULLのまま「要再確認」として利用しない |

- `uk_freee_link_engineer(engineer_id)`は維持する。
- `uk_freee_link_employee(freee_employee_id)`は削除し、`uk_freee_link_company_employee(freee_company_id, freee_employee_id)`へ置換する。
- queryは常に現在の`company_id`を含める。NULLまたは別会社のlegacy linkを給与表示へ使用しない。
- 別会社へ接続してもlinkを自動削除・自動転用しない。管理者/HRの明示的な再対応付けで同じengineer rowを現在会社へ更新する。

### 4.3 Migration規約

- 適用済み`V21__freee_payroll_integration.sql`は編集しない。
- 着手時に`db/migration`のlatestを再確認し、その+1を使用する。本設計はversion番号を予約しない。
  - **実装時の確定（2026-08-14）**: latestは`V102`。`V103`〜`V108`はS12〜S17の予約番号のため使えない
    （`SpecDispatchConsistencyTest`が実在を禁止）。HFP-01は既存の`V66_1`/`V74_1`/`V79_1`と同じ
    **`V102_2`（Flyway表記 V102.2）**を採番した。`V102_1`もS10のR10受理契約で禁止のため使用しない。
- 現在のbaselineではfreee tableはV1に含まれないため、V1へ重複追加しない。着手時に構成が変わっていた場合はroot `AGENTS.md`を優先する。
- `schema-freee-payroll-h2.sql`と`engineer-schema-h2.sql`を同時更新し、MySQL smokeでindex名、backfill、NULL legacy、soft deleteを確認する。
- 本番rollbackはmigration downではなく、menu無効化・token revoke・forward fixとする。

## 5. 接続状態機械

```text
active rowなし ----------------------------------------------> DISCONNECTED
設定不足 / company_id・token欠落 ----------------------------> MISCONFIGURED
OAuth成功 + company一致 + company_admin ---------------------> CONNECTED
expired access token + refresh成功 ---------------------------> CONNECTED
invalid_grant / re_authorization_required --------------------> REAUTH_REQUIRED
通常の権限不足 / plan不足 ------------------------------------> CONNECTED（操作は失敗し次アクション表示）
revoke完了または既失効 + local delete ------------------------> DISCONNECTED
revoke timeout/5xx -------------------------------------------> 元状態を維持
```

`FreeeConnectionStatusDto`は`status`、`connected`、`companyName`、`action`だけを返し、company ID、期限、tokenを返さない。`connected()`は`status == CONNECTED`から導出し、CashFlow/S11/S15のboolean contractを維持する。

## 6. OAuth詳細

### 6.1 認可開始

1. `client-id/secret/redirect-uri/key`を検証する。
2. SecureRandom 24byte以上のstateと発行時刻をsessionへ保存する。
3. `response_type=code&client_id=...&redirect_uri=...&state=...&prompt=select_company`を構築する。
4. 公式根拠のない`scope`を付けない。

### 6.2 Callback

1. sessionのstateを先にremoveし、比較はconstant-time相当とする。
2. state欠落、不一致、10分超、二回目、`error` callbackではtoken交換しない。
3. codeをtoken endpointへform POSTする。token endpointのPOSTはtimeoutや応答不明時に自動再送しない（code二重消費を避ける）。
4. token responseにaccess token、refresh token、expires_in、company_idがあることを検証する。
5. 取得したaccess tokenをDB保存前にHR `GET /api/v1/users/me`へ使用し、選択companyの存在、名称、`company_admin`を確認する。
6. すべて成功した場合だけ既存connectionを更新し、token二つ、期限、company ID/name、`CONNECTED`を同一transactionで保存する。新しい接続に失敗しても既存の正常connectionを上書きしない。
7. callback queryは即時に`/payroll?connected=1`または固定error codeへredirectし、code/state/provider messageをredirect先へ含めない。

### 6.3 Refresh

- `selectLatestForUpdate()`を使い、refresh requestと新token保存を`REQUIRES_NEW`で直列化する。
- lock取得後にDBを再読込し、別threadが既に更新して有効期限に余裕がある場合は外部refreshせずreturnする。
- refresh token responseの新refresh tokenは必須として扱う。欠落時に旧tokenを再利用しない。
- `invalid_grant`は同じtransactionまたは独立した確実な更新で`REAUTH_REQUIRED`へ記録する。
- refresh中の例外message/logにformやtokenを含めない。

### 6.4 Revoke

- access tokenとrefresh tokenを公式revoke endpointへ個別に失効要求し、2xxまたは「既に無効」と判定可能なresponseを成功扱いする。
- 一方でもtimeout/5xx/応答不明ならlocal rowを削除せず、再実行を案内する。
- 二つの失効が完了後、connectionを論理削除する。linkは削除しないが、現在company以外のlinkは利用されない。

## 7. HR contract adapter

### 7.1 構造

`FreeeHrContractAdapter`はHTTP/tokenを所有せず、次だけを担当する小さな純粋adapterとする。

- `usersMe(JsonNode)`
- `companyEmployees(JsonNode)`
- `salaryPage(JsonNode)`
- `bonusPage(JsonNode)`
- relative path/queryの安全な構築
- 公式DTOから本システムDTOへの正規化に必要なtyped fieldの抽出

DTOは`@JsonIgnoreProperties(ignoreUnknown = true)`等で未知の追加propertyを許容する。ただしadapterはroot、ID、`total_count`、配列型を明示検証する。JSON property名をservice内の文字列で散在させない。

### 7.2 HR GET transport

`FreeeIntegrationServiceImpl`へprivateな`hrGet(path)`を追加し、既存`executeWithRetry`へ明示的なbase URLを渡せる形に最小変更する。

```text
public apiGet(path) -> executeWithRetry(apiBase, path, ... )       // S11/S15互換
private hrGet(path) -> executeWithRetry(hrApiBase, path, ... )    // HFP-01
OAuth token/revoke -> oauthBaseへ直接form POST
```

full URLを利用者inputから受け取らない。queryは`UriComponentsBuilder`で組み立て、company/year/month/typeを文字列連結しない。

## 8. Pagination algorithm

### 8.1 全期間従業員

```text
limit = 100, offset = 0, seenIds = empty
loop (最大1000 page):
  page = GET /api/v1/companies/{companyId}/employees
             ?with_no_payroll_calculation=true&limit=100&offset={offset}
  page内ID重複または既出ID -> contract error
  append
  page.size < 100 -> finish
  offset += page.size
上限到達 -> contract error
```

responseはraw arrayである。`total_count`があると仮定しない。100件ちょうどの場合はoffset=100の空pageを1回取得して終了する。

### 8.2 給与・賞与

```text
limit = 100, offset = 0, expectedTotal = unknown, seenIds = empty
loop (最大1000 page):
  page = GET endpoint?company_id=...&year=...&month=...&limit=100&offset=...
  初回total_countをexpectedTotalに設定、以後一致を要求
  page内/既出ID重複 -> contract error
  append
  append.size == expectedTotal -> finish
  page.empty または append.size > expectedTotal -> contract error
  offset += page.size
```

0件は`total_count=0`かつ空配列の場合だけ正常。HTTP 200でroot欠落を0件扱いしない。

## 9. 従業員対応付け

### 9.1 一覧

- freee側: ID、`num`、`display_name`、`entry_date`、`retire_date`、`payroll_calculation`だけをDTOへ移す。
- 本システム側候補: `deleted_flag=0 AND employment_type <> 'BP'`のID、氏名、雇用形態だけを給与専用APIで返す。`/api/engineers?size=1000`への依存を廃止する。
- linkは現在company IDを含むものだけ`LINKED`。NULL/別companyは`RECONFIRM_REQUIRED`として金額取得には使用しない。

### 9.2 確定

1. 現在connectionが`CONNECTED`であること。
2. engineerとemployee IDが存在すること。
3. engineerが削除済みでなく、`employment_type != BP`であること。
4. employeeが現在companyの取得一覧に存在すること。
5. DB unique conflictを事前確認し、最終的にはunique constraintで競合を防ぎ409へ変換する。
6. `freee_company_id`、employee ID、confirmedAt/Byを保存する。引数`userId`と`SecurityUtils.currentUserId()`を二重に持たず、controllerから認証主体を一貫して渡す設計に整理する。

解除はengineer IDと現在companyを確認して対象linkだけを削除する。他company/他engineerをIDOR条件で削除しない。

## 10. 給与・賞与の正規化

### 10.1 出力DTO

`PayrollStatementDto`は次の形へ整理する。

```text
engineerId: Long
engineerName: String
employeeId: String
employeeNumber: String
year/month/type
payDate: LocalDate?
fixed: Boolean?
calculationStatus: String
grossAmount/deductionAmount/netAmount: BigDecimal?  // nullを保持
employerShareAmount: BigDecimal?                    // salaryのみ
items: List<PayrollItemDto>

PayrollItemDto:
  category: PAYMENT | DEDUCTION | EMPLOYER_SHARE | ALLOWANCE
  name: String
  amount: BigDecimal?
```

同名itemもlistの別要素として保持する。`Map<String, BigDecimal>`は削除または互換性を確認したうえで置換し、同名上書きを残さない。

### 10.2 変換規則

| 出力 | 給与 | 賞与 |
|---|---|---|
| gross | `gross_payment_amount` | `gross_payment_amount` |
| deduction | `total_deduction_amount` | `total_deduction_amount` |
| net | `net_payment_amount` | `net_payment_amount` |
| employer share | `total_deduction_employer_share` | null |
| payment items | `payments` -> PAYMENT | `allowances` -> ALLOWANCE |
| deduction items | `deductions` -> DEDUCTION | `deductions` -> DEDUCTION |
| employer items | `deductions_employer_share` -> EMPLOYER_SHARE | なし |

- JSON string金額はtrim後に`BigDecimal`へ変換する。nullはnull、空文字や非数値はcontract errorとする。
- `calc_status=calculating`または金額nullは計算中として表示し、合計値の加算対象にしない。
- responseを現在companyの有効link mapとinner joinし、内部要員がBPへ変更済み・削除済みなら除外する。
- 返却順は内部要員氏名、employee IDの安定sortとする。

## 11. Error matrix

| provider状況 | retry | 内部状態 | 利用者向けaction | log |
|---|---:|---|---|---|
| 400 / invalid parameter | 0 | 維持 | 入力年月または設定を確認 | status、X-Request-Id |
| 401 `expired_access_token` | refresh 1回 + 元GET 1回 | 成功ならCONNECTED | 自動回復。再失敗なら再接続 | tokenなし |
| 401 `re_authorization_required` | 0 | REAUTH_REQUIRED | 管理者が再接続 | status、request ID |
| token `invalid_grant` | 0 | REAUTH_REQUIRED | 管理者が再接続 | tokenなし |
| 401 `user_do_not_have_permission` | 0 | CONNECTED | freee事業所管理者権限を確認 | status、request ID |
| 401 plan limit | 0 | CONNECTED | freee契約planを確認 | status、request ID |
| 403 app permission | 0 | CONNECTED | app権限を確認し、変更後は再認可 | status、request ID |
| 403 過度アクセス | 0 | CONNECTED | 10分程度待って再実行 | status、request ID |
| 404 | 0 | 維持 | 対象事業所/対象月を確認 | status、request ID |
| 429 | 最大3回 | 維持 | rate reset後に再実行 | 待機時間、request ID |
| GET 500/502/503/504 | 最大2回 | 維持 | 時間を置いて再実行 | status、request ID |
| GET connect/read timeout | 最大2回 | 維持 | 時間を置いて再実行 | 種別のみ |
| token/revoke応答不明 | 自動retry 0 | 元状態 | 再操作。解除完了と表示しない | 秘密なし |
| HTTP 200 schema不整合 | 0 | 維持 | 管理者へprovider仕様差分を通知 | field名、request ID。raw bodyなし |

backoff待機はtestで実sleepに依存しないよう、既存実装への最小限の`Sleeper`/clock注入または同等のdeterministic seamを設ける。本番の総待機時間には上限を持たせる。

## 12. Security、cache、監査、privacy

### 12.1 認可

`SecurityConfig`へ一般ruleより前に次を追加する。

```text
/integrations/freee/** -> 管理者
/payroll/**             -> 管理者, HR
/api/payroll/**         -> 管理者, HR
```

各controllerの`@PreAuthorize`も維持する。menu permissionは第三層であり、唯一の境界にしない。OAuth callbackも有効な管理者sessionを要求する。

### 12.2 no-store

- `PayrollPageController`、`FreeePayrollApiController`の全GET、OAuth callback/redirectへ`Cache-Control: no-store`を付ける。
- 共通helperまたはcontroller単位で漏れなく付与し、endpointごとの付け忘れをMockMvc testで列挙する。
- raw provider responseをapplication cacheへ入れない。

### 12.3 監査

給与用の小さな監査helperを作る場合も、既存`AuditLogService`だけを永続化先として再利用する。給与/賞与参照は返却直前に監査記録が成功した場合だけdataを返す。

| 操作 | applicationCode例 | URI/記録内容 |
|---|---|---|
| 従業員一覧 | `PAYROLL_EMPLOYEE_VIEW` | 固定URI、結果status。氏名/IDなし |
| 給与参照 | `PAYROLL_SALARY_VIEW_202608` | 固定URI、年月/typeをallowlistから生成 |
| 賞与参照 | `PAYROLL_BONUS_VIEW_202608` | 同上 |
| link/unlink | `PAYROLL_LINK` / `PAYROLL_UNLINK` | engineer/freee IDをURIやcodeへ含めない |
| OAuth connect/reconnect | `FREEE_CONNECT` | 成否のみ |
| revoke/disconnect | `FREEE_DISCONNECT` | 成否のみ |

- requestのraw query stringを監査へ保存しない。
- filterとcontrollerで同じ操作を二重記録しない。設計時に責務を一箇所へ決め、testで1 request = 1 audit rowを確認する。
- amount、氏名、employee/company ID、token、code、state、provider bodyをlog/監査へ出さない。

### 12.4 Privacy test

testはHTTP response後に給与用永続tableが増えていないこと、`t_audit_log`に禁止値がないこと、captured logにfixture token/氏名/金額がないことをassertする。sandbox証跡は件数、HTTP status、内部task ID、時刻だけを残し、response bodyや画面captureを保存しない。

## 13. UI設計

inline scriptを`static/js/modules/payroll.js`へ移し、既存module規約に合わせる。

1. **接続card**: status badge、事業所名、action。管理者だけ接続/再接続/解除button。解除は確認dialog＋CSRF付きDELETE。
2. **対応付けcard**: freee従業員select/tableと内部要員select。freee employee ID生入力を廃止。未対応、対応済み、要再確認、退職済み、給与対象外を区別。
3. **明細card**: 年、月、給与/賞与select。取得buttonを二重送信不可にし、loadingを表示。
4. **一覧**: 内部要員名、従業員番号、支払日、確定/計算状態、3合計。nullは`—`または「計算中」。0は`0円`。
5. **詳細**: row展開またはmodalで区分別item list。同名itemをすべて表示。
6. **状態**: 0件、未対応、provider障害、権限不足、再認可を異なるmessage/actionで表示。
7. **アクセシビリティ**: label/for、button type、focus、`aria-live`、table header、keyboard展開、単一mainを維持。desktopと390pxで横scrollまたはcard化し、操作buttonが欠落しない。

新しいmessage keyは既存5bundleの整合testを通す。金額、氏名、tokenをconsoleへ出さない。

## 14. CashFlowとの境界

`CashFlowForecastServiceImpl.getEstimatedPayroll()`は次の優先順位とする。

1. 直近月の対応付け済み内部要員の確定/利用可能な`grossAmount`合計。
2. 各給与に`employerShareAmount`がある場合は実額合計を加える。取得できない明細がある場合の扱いを混在させず、その月全体で公式実額が完全な場合だけ実額を使用する。
3. 会社負担実額が不完全なら既存`cashflow.payroll-employer-burden-rate`をgrossへ適用する。
4. 直近月0件/全件計算中なら2か月前を試す。
5. 2か月前も0件/全件計算中/外部障害なら`cashflow.payroll-estimate`へfallbackする。

給与0円が正式値であるcaseと、利用可能金額0件を区別する。既存public DTO/APIを不要に破壊しない。

## 15. Test設計

### 15.1 Fixture

`src/test/resources/freee/`へ、固定OpenAPI commitに対応する架空データを置く。

- token success / invalid_grant
- users-me company_admin / self_only / company mismatch
- employees 0/1/100/101/200、退職、給与対象外
- salary calculated / calculating / duplicate item name / invalid amount
- bonus calculated
- response root欠落、途中空page、repeat page、total_count変化
- 401 codes、403、429 headers、5xx

fixture headerまたはREADMEへsource commitと匿名化済みを記載する。sandbox responseをコピーしない。

### 15.2 自動test matrix

| test | 核心assert |
|---|---|
| OAuth contract | exact URL/query、state一回、error callback、token POST一回、company/role検証、旧接続保護 |
| Refresh concurrency | 同一refresh token外部使用1回、rotation保存、invalid_grant状態遷移 |
| Revoke | access/refresh成功、既失効、一部timeoutでlocal保持 |
| HR adapter contract | official root/field、未知field許容、必須欠落失敗、null保持、同名item保持 |
| Pagination | 0/1/100/101/200、途中空、反復、total不整合、有限終了 |
| Mapping | company境界、一意競合、BP、削除済み、legacy NULL、明示再確認 |
| API/security | role 5種＋未認証、CSRF、no-store全endpoint、validation |
| Audit/privacy | 1 request 1 row、年月/type/status、禁止値0、log secret 0 |
| UI contract | type select、接続/reconnect/disconnect、null表示、ARIA、JS syntax |
| CashFlow | actual employer share、率fallback、全null推定値、給与0円 |
| Migration | empty DB、V21からupgrade、legacy NULL/backfill、一意index、H2同期 |
| Regression | S11 `FreeeIntegrationServiceApiTest`、S15/PaymentReconciliation、全suite |

Mockが核心parserやquery builderを迂回するtestを完成証拠にしない。retry testで実時間sleepをさせない。固定IDや他testの共有H2データへ依存しない。

### 15.3 Sandbox E2E

架空データだけのfreeeテスト事業所で次を実行する。

1. 管理者で接続し、選択事業所名と`CONNECTED`を確認。
2. HRでは接続buttonがなく、従業員/明細参照が可能なことを確認。
3. 内部要員とfreee従業員を対応付け、未対応従業員の給与が返らないことを確認。
4. 給与1件、賞与1件、可能なら計算中1件を確認。
5. access token期限切れ相当を安全な方法で発生させ、refresh後に同じ操作が成功することを確認。
6. 接続解除後にprovider APIが利用できず、再接続で復旧することを確認。
7. 営業、マネージャー、要員、未認証の直接URL/API拒否を確認。

証跡は`review-ledger.md`と必要なら`.kiro/specs/payroll-management/evidence/`へ保存する。ただしtoken、code、state、氏名、外部ID、金額、raw body、給与画面screenshotは禁止する。credential未提供は`BLOCKED`でありPASSではない。

## 16. Release、失敗判定、rollback

### 16.1 Release gate

- `HFP-01-AC01`〜`HFP-01-AC15`すべてPASS
- `verify-like-ci` failure/error/skip 0
- 実MySQL migration smoke PASS
- sandbox E2E PASS
- merge前独立Reviewが`REVIEWABLE`、merge済みcommitのmerge delta/共有consumer/main回帰を確認した独立Reviewが`PASS`
- P0/P1 0、未管理acceptance 0。P2/NOTEの延期は発注者承認、owner、期限、release影響付き
- audit/log/repository secret scan 0

### 16.2 失敗判定

以下の一つでもあれば未完了またはFAILとする。

- official endpointを呼ぶtestだけで、sandboxを未実行
- Dockerなしのskipをmigration PASS扱い
- 給与計算中nullを0表示
- page先頭100件だけでpagination完了扱い
- freee responseを保存または証跡へ貼付
- row存在だけで接続済み
- UI非表示だけでrole境界を主張
- S11/S15 regression未実行
- checklistを埋めたが実行command、test件数、証跡がない

### 16.3 Rollback

1. 問題発生時は`payroll` menu権限を外し、新規接続を停止する。
2. tokenをfreee側でrevokeし、local connectionを論理削除する。
3. 給与明細は保存していないためdata rollbackは不要。linkは削除せず利用停止し、誤対応だけ明示解除する。
4. 適用済みFlywayを編集・down migrationしない。追加column/indexは後方互換を維持し、必要ならforward fixを作る。
5. code rollback時もS11/S15の共有OAuth/token contractが旧不正hostへ戻らないよう、差分を一括revertせずcomponent単位で判断する。

## 17. Traceability

| Requirement | 主Task | Acceptance |
|---|---|---|
| HFP-01-R01 | HFP-01-001, HFP-01-004 | HFP-01-AC01, HFP-01-AC07, HFP-01-AC09 |
| HFP-01-R02 | HFP-01-003 | HFP-01-AC01〜HFP-01-AC03 |
| HFP-01-R03 | HFP-01-002, HFP-01-003 | HFP-01-AC03〜HFP-01-AC05 |
| HFP-01-R04 | HFP-01-002, HFP-01-005 | HFP-01-AC06, HFP-01-AC08 |
| HFP-01-R05 | HFP-01-004, HFP-01-006 | HFP-01-AC07, HFP-01-AC08 |
| HFP-01-R06 | HFP-01-004 | HFP-01-AC06, HFP-01-AC09 |
| HFP-01-R07 | HFP-01-004 | HFP-01-AC04, HFP-01-AC10 |
| HFP-01-R08 | HFP-01-007 | HFP-01-AC11, HFP-01-AC12 |
| HFP-01-R09 | HFP-01-007 | HFP-01-AC12, HFP-01-AC15 |
| HFP-01-R10 | HFP-01-008 | HFP-01-AC13 |
| HFP-01-R11 | HFP-01-009 | HFP-01-AC14 |
| HFP-01-R12 | HFP-01-001, HFP-01-010, HFP-01-011 | HFP-01-AC14, HFP-01-AC15 |
