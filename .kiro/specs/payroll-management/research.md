# HFP-01 freee人事労務 給与・賞与参照連携 — 調査記録

## 1. 調査条件

- 調査日: 2026-08-12（Asia/Tokyo）
- 外部情報源: freee公式開発者サイト、freee公式GitHubのみ
- 人事労務OpenAPI固定commit: [`52c69a6819ef14979a31b342123df816cb72c742`](https://github.com/freee/freee-api-schema/tree/52c69a6819ef14979a31b342123df816cb72c742/hr/open-api-3)
- 公式実装参考commit: [`freee/freee-mcp@826e22555a9befe5a672e9bdfc23070676f41969`](https://github.com/freee/freee-mcp/tree/826e22555a9befe5a672e9bdfc23070676f41969)

実装開始時に上記commitと公式release noteの差分を確認する。新しいcommitがあるだけでは自動更新せず、本specの契約に影響する差分を記録してからfixtureと実装を同時に更新する。

## 2. 公式資料と確定事項

| ID | 公式資料 | このspecで確定した事項 |
|---|---|---|
| HFP-01-RES-01 | [OAuth概要](https://developer.freee.co.jp/reference/%E8%AA%8D%E5%8F%AF%E3%82%B3%E3%83%BC%E3%83%89) | Authorization Code Grant。認可は`accounts.secure.freee.co.jp/public_api/authorize`、tokenは`.../token`、revokeは`.../revoke`。`prompt=select_company`を使用する。refresh tokenは一回限りで、更新後の新tokenを保存する。 |
| HFP-01-RES-02 | [アクセストークン取得](https://developer.freee.co.jp/startguide/getting-access-token) | token responseの`company_id`をtokenと共に保存して各APIへ指定する。access tokenは6時間。stateは同一sessionで検証する。 |
| HFP-01-RES-03 | [事業所選択](https://developer.freee.co.jp/reference/select-company) | `prompt=select_company`が推奨方式。選択された1事業所だけへアクセスし、token responseの`company_id`を使用する。 |
| HFP-01-RES-04 | [人事労務API reference](https://developer.freee.co.jp/reference/hr) / [固定OpenAPI](https://raw.githubusercontent.com/freee/freee-api-schema/52c69a6819ef14979a31b342123df816cb72c742/hr/open-api-3/api-schema.json) | HR base URLは`https://api.freee.co.jp/hr`、schema versionは`2022-02-01`。responseへ未知の追加propertyが入る可能性がある。共通response headerは`X-Request-Id`、rate limitは1時間10000回。 |
| HFP-01-RES-05 | 固定OpenAPI `/api/v1/users/me` | userが属するcompaniesの`id`、`name`、`role`を返す。給与・賞与一覧は管理者権限が必要なため、選択会社のrole=`company_admin`を接続時に確認する。 |
| HFP-01-RES-06 | 固定OpenAPI `/api/v1/companies/{company_id}/employees` | 全期間の従業員を取得し、退職者も含む。`limit`は最大100、`offset`を使用する。responseは配列であり`total_count` wrapperではない。`with_no_payroll_calculation`で給与計算対象外を含められる。 |
| HFP-01-RES-07 | 固定OpenAPI `/api/v1/salaries/employee_payroll_statements` | 必須queryは`company_id/year/month`。`limit`最大100、`offset`。rootは`employee_payroll_statements`、件数は`total_count`。給与計算中は金額が`null`、配列が空になり得る。 |
| HFP-01-RES-08 | 固定OpenAPI `/api/v1/bonuses/employee_payroll_statements` | 賞与は給与と別endpoint。必須query、pagination、rootは給与と同様。 |
| HFP-01-RES-09 | 固定OpenAPI payroll serializers | 合計fieldは`gross_payment_amount`、`total_deduction_amount`、`net_payment_amount`。給与明細配列は`payments`、`deductions`、`deductions_employer_share`。賞与は`allowances`、`deductions`。金額はJSON stringでnullable。 |
| HFP-01-RES-10 | [権限とエラー](https://developer.freee.co.jp/reference/authority-and-error) / [40x checkpoint](https://developer.freee.co.jp/reference/40x-checkpoint) | app権限、plan、user権限、token状態を区別する。`expired_access_token`、`re_authorization_required`、`user_do_not_have_permission`等を同じ401として無限refreshしてはならない。未知codeも安全に4xx処理する。 |
| HFP-01-RES-11 | [refresh token 90日化](https://developer.freee.co.jp/news/6540) / [token有効期限](https://developer.freee.co.jp/reference/faq/token_lifetime) | refresh tokenは90日、同じtokenを二度使用できない。`invalid_grant`は再認可へ遷移する。 |
| HFP-01-RES-12 | [アプリ権限変更](https://developer.freee.co.jp/reference/access-token-permission) | アプリ権限変更はrefreshだけでは反映されず、再認可が必要。 |
| HFP-01-RES-13 | [人事労務Webhook](https://developer.freee.co.jp/reference/hr/webhook) | 公開されているeventは従業員作成・更新・削除であり、給与確定eventはない。本specではwebhook/schedulerを追加しない。 |
| HFP-01-RES-14 | [アプリ審査](https://developer.freee.co.jp/reference/app-review-process) | 必要権限は最小にし、事業所選択と接続解除の説明が必要。社内限定private appの扱いは運用時に確認する。 |

## 3. 既存実装との契約差分

| 項目 | 既存実装 | 公式契約 | 影響 |
|---|---|---|---|
| OAuth認可/token | `freee.api-base-url + /oauth/authorize|token` | accounts hostの`/public_api/authorize|token` | 実接続不可 |
| 認可query | 公式根拠のない`scope=read:hr employees:read payrolls:read` | app管理画面の権限＋認可、`prompt=select_company` | 認可契約不一致 |
| 事業所 | token responseの`company_id`を保存しない | `company_id`必須 | 従業員・給与・賞与を取得不可。freee会計の既存consumerも接続rowだけでは利用不可 |
| 接続状態 | row件数が1以上ならtrue | token/会社/再認可状態を確認 | 偽の「接続済み」 |
| 従業員 | `/hr/api/v1/employees`、必須queryなし | HR base + `/api/v1/companies/{company_id}/employees`または年月付きemployees | 実APIで400/404または不完全 |
| BP判定 | freee responseのtop-level`employment_type == BP` | freee enumに`BP`は存在しない | BP除外が成立しない。ローカルEngineerで判定すべき |
| 給与/賞与 | `/hr/api/v1/payroll-statements?...&type=` | salariesとbonusesの別endpoint | 実APIで取得不可 |
| response root | `statements` | `employee_payroll_statements` | HTTP 200でも空一覧になる |
| 合計field | `gross_amount/deductions/net_amount` | `gross_payment_amount/total_deduction_amount/net_payment_amount` | 0円として誤表示 |
| null | 欠落を`BigDecimal.ZERO`へ変換 | 計算中はnullが正常 | 計算中を0円と誤表示 |
| pagination | 1回だけ | `limit/offset`、最大100 | 51件目以降の欠落 |
| 明細 | DTOの`items`を設定しない | 区分別配列 | 合計だけで明細なし |
| 監査 | GETは通常監査対象外 | 給与参照は要監査 | 給与閲覧証跡なし |
| no-store | 明細APIだけ | 関連page/API全体 | 従業員・接続情報がcacheされ得る |
| 接続解除 | local rowの論理削除だけ | revoke endpointあり | provider側tokenが有効なまま |

## 4. 再利用する既存資産

- `FreeeOAuthController`の24byte SecureRandom state生成、sessionからの一回限り削除
- `FreeeIntegrationServiceImpl`のAES/GCM/NoPadding、12byteランダムIV、暗号文保存
- `FreeeConnectionMapper.selectLatestForUpdate()`と`REQUIRES_NEW` refresh transaction
- `saasRestTemplate`の既存timeout
- `FreeeIntegrationService.apiGet/apiPost`のS11向け公開contract
- `t_freee_connection`、`t_freee_employee_link`、一意制約、`payroll` menu
- `FreeePayrollApiController`、`PayrollPageController`、`templates/payroll/index.html`のroute/画面骨格
- `ApiResult`、`BusinessException`、`SES.api`、CSRF、Toast、既存menu権限
- `CashFlowForecastServiceImpl`の未接続・外部障害時の設定値fallback

## 5. 採用しない参考方式

- **公式OpenAPIの全量Java code generation**: この機能が使うのはusers/me、全期間employees、給与、賞与の少数GETだけで、生成物の量とschema更新差分が過大になる。固定fixtureを持つ小さなtyped contract adapterを採用する。
- **`freee-mcp`の直接依存**: 公式のTypeScript実装はendpoint確認と契約の読み方の参考に限定する。Java/Spring実行時依存にはしない。
- **community SDK**: 外部仕様の正本にせず、依存へ追加しない。
- **給与raw JSONの保存**: 再取得可能な機微情報を複製しない。offline履歴が必要になった場合は別specで法務・retention・暗号化・削除方針から決定する。
- **給与webhook/scheduler**: 給与確定eventが公式Webhookにないうえ、本specは都度参照で要件を満たす。

## 6. 実装開始時に再確認する外部条件

以下はsource codeから決められないため、`HFP-01-001`で確認し、結果を`review-ledger.md`へ記録する。

1. 使用するfreeeアプリがprivate/publicのどちらか。
2. 対象テスト事業所のplanで給与・賞与APIが利用できるか。
3. 認可ユーザーが対象事業所の`company_admin`か。
4. app権限が従業員・給与・賞与の読み取りに限定されているか。
5. テスト事業所に、架空の内部要員、未対応従業員、給与1件、賞与1件、計算中または同等fixtureが用意できるか。
6. 実装着手時の最新Flyway version。既存V21や適用済みmigrationを編集せず、必要なforward migration番号をここで確定する。

credentialが用意できない場合は値を対話やrepositoryへ貼らず、環境変数提供を依頼する。自動testの実装は継続できるがsandbox E2Eと全体完了は`BLOCKED`のままとする。
