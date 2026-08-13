# HFP-01 freee人事労務 給与・賞与参照連携 — 要件定義

## 0. 文書情報

- Spec ID: `HFP-01`
- 状態: 実装待ち（既存の骨格は存在するが、公式API契約と不整合のため本番利用不可）
- 対象: freee人事労務とのOAuth接続、従業員対応付け、給与・賞与明細の読み取り専用参照
- 正本: 本文、`design.md`、`tasks.md`、`research.md`
- 完了判定: `HFP-01-AC01`〜`HFP-01-AC15`をすべて満たし、独立ReviewがPASSした場合のみ

## 1. 背景と現状判定

日本の給与計算、税、社会保険、年末調整は自社実装せず、freee人事労務を正本とする。SES Manager Proは、対象事業所と要員の対応付け、および権限を持つ利用者による給与・賞与明細の一時参照だけを提供する。

既存実装にはOAuth state検証、AES-GCMによるtoken暗号化、refresh時の行ロック、接続・対応付けテーブル、API、画面が存在する。一方、OAuth host、給与・賞与endpoint、必須`company_id`、response root、金額field、paginationが公式契約と一致していない。したがって既存checkboxや画面の存在を完成証拠として扱わない。

## 2. 用語

- **事業所**: freeeの`company_id`で識別される接続先。HFP-01では1システムにつき同時に1事業所だけを接続する。
- **内部要員**: `t_engineer.employment_type <> 'BP'`の要員。
- **BP**: `t_engineer.employment_type = 'BP'`。freeeの`employment_type`には`BP`という値がないため、BP判定は必ず本システム側で行う。
- **対応付け**: `t_freee_employee_link`による内部要員1件とfreee従業員1件の明示的な1対1関係。
- **明細**: freeeからリクエスト時に取得する給与または賞与情報。DBへ永続化しない。
- **再認可必要**: refresh token失効、アプリ権限変更等により、管理者がOAuth認可をやり直す必要がある状態。

## 3. 機能要件

### HFP-01-R01 公式契約を唯一の外部仕様正本にする

1. 実装とcontract testは、`research.md`に固定したfreee公式人事労務OpenAPI commitを根拠にする。
2. 公式仕様にないendpoint、query、scope文字列、response fieldを推測してはならない。
3. freeeの後方互換変更として未知の追加fieldは許容するが、必須rootや必須識別子が欠落したHTTP 200を空データとして扱ってはならない。

### HFP-01-R02 OAuth認可と事業所確定

1. OAuth Authorization Code Grantを使用し、認可URLは`https://accounts.secure.freee.co.jp/public_api/authorize`、token URLは`https://accounts.secure.freee.co.jp/public_api/token`を既定値とする。
2. 認可URLに`prompt=select_company`、`response_type=code`、`client_id`、`redirect_uri`、十分なランダム性を持つ一回限りの`state`を含める。公式に根拠のない`scope` queryを付けない。
3. callbackは同一sessionの`state`を一定時間内かつ一回だけ受理する。state不一致、欠落、再送、freee側の認可拒否ではtoken交換を行わない。
4. token responseの`company_id`を保存し、`GET /api/v1/users/me`のcompaniesから同じ事業所の名称とroleを検証する。給与・賞与APIに必要な`company_admin`でなければ接続完了にしない。
5. `client_secret`、access token、refresh token、認可code、stateは画面、通常ログ、監査ログ、例外messageへ出さない。

### HFP-01-R03 接続状態、refresh、接続解除

1. 接続状態は少なくとも`DISCONNECTED`、`CONNECTED`、`REAUTH_REQUIRED`、`MISCONFIGURED`を区別し、日本語の次アクションを返す。
2. `CONNECTED`は単なる接続rowの存在ではなく、`company_id`、暗号化token、有効期限、状態の整合を満たす場合だけとする。
3. 既存のAES-GCMランダムIV暗号化と`SELECT ... FOR UPDATE`によるrefresh競合防止を維持する。refresh tokenは更新のたびに返された新値へ原子的に置換し、同じrefresh tokenを並行・再試行で二度使わない。
4. `invalid_grant`、`re_authorization_required`等は`REAUTH_REQUIRED`へ遷移し、無限refreshしない。
5. 接続解除はfreee公式revoke endpointへの失効要求が成功、または既に無効と確認できた後にだけローカルtokenを削除する。一時的なprovider障害では「解除済み」と表示せず、再実行可能な状態を保つ。

### HFP-01-R04 従業員取得と明示的な1対1対応付け

1. 対象事業所の全期間従業員一覧`GET /api/v1/companies/{company_id}/employees`を公式pagination契約に従って取得する。
2. 画面にはfreee従業員ID、従業員番号、表示名、在退職情報、給与計算対象、対応付け状態を表示する。銀行口座、住所、家族、生年月日等、この画面に不要な情報を要求・表示しない。
3. 対応付けは管理者またはHRの明示操作だけで確定する。氏名一致だけの自動確定は禁止する。現行データモデルに共通社員番号がないため、自動確定機能を追加しない。
4. 1要員対1freee従業員の一意制約を維持し、競合は409で安全に失敗する。
5. 本システム側のBPは候補要員に表示せず、直接API指定も拒否する。既存対応付け先がBPへ変更された場合も明細から除外し、要確認状態として検知する。
6. freee未登録・退職済み・給与計算対象外を理由に既存対応付けを自動解除しない。

### HFP-01-R05 給与・賞与明細の読み取り専用参照

1. 給与は`GET /api/v1/salaries/employee_payroll_statements`、賞与は`GET /api/v1/bonuses/employee_payroll_statements`を別endpointとして呼ぶ。
2. `company_id`、`year`、`month`を必須指定し、typeは`salary`または`bonus`だけを受理する。
3. response rootは`employee_payroll_statements`、金額は文字列から`BigDecimal`へ厳密変換する。
4. 共通表示項目はfreee従業員ID、対応する内部要員ID・氏名、従業員番号、支払日、確定状態、計算状態、総支給額、控除合計、差引支給額とする。
5. 給与では`payments`、`deductions`、`deductions_employer_share`を区分を失わない明細配列として表示する。賞与では`allowances`、`deductions`を表示する。同名項目をMapで上書きしてはならない。
6. 給与計算中に合法的に返る`null`金額と空配列は0円へ変換せず、「計算中」または「未確定」と表示する。不正な数値文字列はprovider契約エラーとして失敗させる。
7. 給与・賞与一覧APIの返却対象は、有効な対応付けを持つ内部要員だけとする。未対応freee従業員の金額を画面・APIへ返さない。
8. 本システムからfreeeの給与、賞与、備考、従業員情報を更新するAPIは提供しない。

### HFP-01-R06 Paginationとschema drift検知

1. `limit=100`と`offset`で全ページを取得する。給与・賞与は`total_count`に到達するまで、全期間従業員は返却件数が100未満になるまで取得する。
2. 0件、1件、100件、101件、200件を欠落・重複なく処理する。
3. page途中で空配列、同一IDの反復、`total_count`との不整合、必須root欠落を検知した場合は無限loopせず、502相当のprovider契約エラーにする。
4. paginationの最大反復回数を有限にし、上限到達を空データとして扱わない。

### HFP-01-R07 外部エラーと可用性

1. 外部呼出しは既存`saasRestTemplate`の接続・読み取りtimeoutを使用し、無制限待機しない。
2. 401はerror codeを分類する。access token期限切れだけrefreshを1回行って元のGETを1回再実行し、それでも401なら停止する。
3. 429は`Retry-After`またはrate-limit headerを尊重し、上限付きbackoffで最大3回までとする。安全なGETの500/502/503/504およびtimeoutは最大2回までとし、POST token/revokeを無条件再送しない。
4. 400、403、404、plan制限、user権限不足、app権限不足はretryしない。管理者が「パラメータ修正」「freee事業所管理者で再接続」「アプリ権限設定」「時間を置いて再実行」を選べる日本語messageへ分類する。
5. provider response本文を利用者へそのまま返さない。障害調査用にはHTTP status、公式`X-Request-Id`、内部correlation IDだけを秘密情報なしで記録する。
6. freee障害は給与画面と給与を利用する補助集計だけを失敗または既存設定値へfallbackさせ、契約、請求、勤怠等の主要業務を停止させない。

### HFP-01-R08 認証、認可、CSRF、cache

1. `/payroll/**`と`/api/payroll/**`は管理者・HRだけ、`/integrations/freee/**`は管理者だけに静的SecurityConfigとmethod securityの両方で制限する。営業、マネージャー、要員は直接URL/APIでも403、未認証APIは401とする。
2. PUT、DELETE等は既存Cookie/`X-XSRF-TOKEN`方式のCSRF保護を維持する。
3. 給与page、status、従業員、明細、OAuth callbackを含む関連responseへ`Cache-Control: no-store`を設定する。必要に応じて`Pragma: no-cache`、`Expires: 0`も付ける。
4. UI非表示だけを認可境界にしてはならない。

### HFP-01-R09 個人情報、永続化、監査

1. 給与金額、項目明細、口座、扶養、住所、freee raw responseをDB、file、session、cacheへ永続化しない。
2. 永続化してよいのは接続先事業所、暗号化tokenと期限・接続状態、従業員対応付け、秘密を含まない監査情報だけとする。
3. 給与・賞与参照GETは通常GET監査除外の例外として監査し、利用者、操作、年月、type、成功/失敗statusを記録する。金額、氏名、freee employee/company ID、token、raw query、raw bodyを記録しない。
4. 従業員一覧参照、対応付け、解除、OAuth接続・解除も操作種別と結果を監査する。
5. test fixtureとDemo証跡は架空データまたは完全匿名化データだけを使用し、実token・実給与・認可codeをrepositoryへ保存しない。

### HFP-01-R10 操作可能な画面

1. 管理者は未設定、接続済み、再認可必要、設定不備を識別し、接続、再接続、接続解除を実行できる。HRには接続操作を表示しない。
2. 対応付けはfreee従業員と内部要員を選択式で行い、生IDの手入力を通常操作にしない。競合・BP・退職等の理由を日本語で表示する。
3. 給与・賞与type、年月を選択し、loading、0件、計算中、部分的な外部障害、再認可必要を区別する。
4. 一覧から支給・控除・会社負担の区分別明細を確認でき、keyboard操作、label、`aria-live`、390px幅で利用できる。
5. 金額表示は円、3桁区切りとし、未取得/nullを0円表示しない。

### HFP-01-R11 既存機能との互換性

1. `FreeeIntegrationService`のOAuth/token暗号化/refresh行ロックと`apiGet`/`apiPost`を再利用し、同等機能を二重実装しない。
2. S11のfreee勤怠provisional mappingとS15のfreee会計入金取得はHFP-01の対象外とし、endpoint形状や業務意味をついでに修正しない。OAuth host分離後も公開method contractと既存testを壊さない。
3. `CashFlowForecastServiceImpl`は確定済み給与の総支給額を用い、公式の会社負担合計が取得できる場合はそれを優先し、欠落時だけ既存設定率を使用する。利用可能な金額が0件なら0円ではなく既存推定値へfallbackする。
4. 新しいJava SDK、OpenAPI generator、scheduler、webhook、給与保存tableを導入しない。必要endpointだけを既存構成へ追加する。

### HFP-01-R12 Test・sandbox・release gate

1. 固定した公式schemaに対応する匿名fixtureでOAuth、従業員、給与、賞与、pagination、null、error matrixのcontract testを持つ。
2. role/CSRF/no-store/監査、refresh同時実行、token rotation、schema drift、log secret非出力を自動testで実証する。
3. schema変更時は過去migrationを編集せず、着手時の最新番号+1のforward migration、H2 schema、実MySQL Flyway smokeを同期する。
4. freeeテスト事業所で、接続→事業所確認→従業員取得→対応付け→給与→賞与→強制refresh→接続解除→再接続を実行する。
5. sandbox credentialがない場合、決定的mock testまで進めてよいが、HFP-01全体をPASSまたは完了にしてはならず`BLOCKED`とする。
6. 最終`verify-like-ci`はfailure 0、error 0、CI相当環境でskip 0とする。

## 4. 非目標

- 給与計算、税・社会保険計算、年末調整の自社実装
- freeeへの給与・賞与金額、備考、従業員情報の書き込み
- 給与明細・口座・扶養・住所の保存、履歴warehouse、CSV/PDF export
- BP支払（既存`t_bp_payment`を継続利用）
- freee勤怠同期（S11）、freee会計入金消込（S15）の仕様是正
- 複数freee事業所の同時接続、S1〜S17の機能拡張
- 給与webhook/scheduler（公式人事労務Webhookに給与確定eventはない）
- 公式契約にない自動従業員matching

## 5. 受入基準

- **HFP-01-AC01**: 認可URL、token URL、revoke URL、HR base URLが公式契約と完全一致し、独自`scope`を送らない。
- **HFP-01-AC02**: state不一致・欠落・再送・認可拒否の各caseでtoken endpoint呼出し0回、正常caseで1回である。
- **HFP-01-AC03**: token responseの`company_id`と`/users/me`の`company_admin`事業所が一致した場合だけ`CONNECTED`になり、事業所名が表示される。
- **HFP-01-AC04**: 並行refreshで同一refresh tokenの外部使用は1回だけ、新refresh tokenが原子的に保存される。失効時は`REAUTH_REQUIRED`になる。
- **HFP-01-AC05**: 接続解除の成功/既失効/一時障害を区別し、一時障害でlocal rowを消さない。
- **HFP-01-AC06**: 従業員0/1/100/101/200件を欠落・重複なく取得し、BPの対応付けはUI/API/既存link検査の全経路で拒否または除外される。
- **HFP-01-AC07**: 公式fixtureの給与・賞与を正しいroot/fieldから変換し、同名明細を失わず、計算中nullを0へ変換しない。
- **HFP-01-AC08**: 明細APIは対応付け済み内部要員だけを返し、未対応freee従業員の金額を返さない。
- **HFP-01-AC09**: root欠落、途中空page、反復page、invalid amountを空結果にせず、有限時間内にprovider契約エラーとして終了する。
- **HFP-01-AC10**: 401/403/429/5xx/timeoutのretry回数と日本語の次アクションがR07のmatrixどおりで、token/raw bodyがlogへ出ない。
- **HFP-01-AC11**: 管理者・HRの許可操作が成功し、営業・マネージャー・要員・未認証のpage/API/OAuth境界がR08どおりである。更新系はCSRFなしで403になる。
- **HFP-01-AC12**: 給与関連responseが`no-store`で、監査DB/logに操作・年月・type・結果だけが残り、給与金額、氏名、外部ID、tokenが残らない。
- **HFP-01-AC13**: desktopと390pxで接続状態、対応付け、給与、賞与、計算中、0件、再認可、解除のDemoが操作可能である。
- **HFP-01-AC14**: S11/S15の既存対象test、CashFlow対象test、MySQL migration smoke、全testがgreenである。
- **HFP-01-AC15**: freeeテスト事業所E2E後のmerge前独立Reviewが`REVIEWABLE`、merge済みcommitのmerge delta/共有consumer/main回帰を確認した独立Reviewが`PASS`であり、未解決P0/P1、未管理acceptance、未実施必須gate、秘密を含む証跡が0件である。P2/NOTEを延期する場合は発注者承認、owner、期限、release影響を記録する。
