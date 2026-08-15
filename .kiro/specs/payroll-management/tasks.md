# HFP-01 freee人事労務 給与・賞与参照連携 — 実装Task

## 0. 実行規約

- Task IDは変更・再採番しない。新規Taskが必要なら`HFP-01-012`以降を末尾へ追記し、元Taskを残す。
- 上から依存順に実行する。並列化は同じfile/contractを変更しないTaskだけに限定する。
- 各Taskは **失敗再現test → 最小実装 → 対象test → Demo → 証跡記入** の順で行う。
- `Objective`、`Test`、`Demo`、`完了証拠`のすべてを満たすまで`[x]`にしない。外部credential/Docker/browser不足は`BLOCKED`であって完了ではない。
- 実行結果は`review-ledger.md`へ追記する。給与金額、氏名、freee employee/company ID、token、認可code/state、raw response、給与画面screenshotを証跡に残さない。
- business sourceを変更する前にroot `AGENTS.md`、本spec全file、対象source/testを再読する。
- 既存dirty差分、S11/S15、無関係なstyle/refactorを触らない。

---

## HFP-01-001 公式契約・外部条件・失敗baselineを固定する

- [x] **HFP-01-001 — 契約preflightとcontract fixture**
- **Requirements**: HFP-01-R01, HFP-01-R12
- **依存**: なし
- **Objective**: 実装者の推測を排除し、現行実装が公式契約testに失敗することを先に証明する。
- **実装ガイダンス**:
  - `research.md`のOpenAPI commitと公式release noteを再確認し、変更があればURL、commit、影響fieldだけを同fileへ追記する。
  - private/public app、対象plan、`company_admin`、最小app権限、sandbox架空dataの有無を確認し、値を出さず`review-ledger.md`へ`READY/BLOCKED`を記録する。
  - `src/test/resources/freee/README.md`と匿名fixtureを追加する。source commit、人工データであること、禁止情報を明記する。
  - OAuth URL、users/me、employees、salary、bonus、null計算中、invalid schema、error responseの現行失敗testを追加する。production codeはこのTaskでは変更しない。
- **Test**:
  - testが旧OAuth host、旧payroll path、旧root/field、company_id欠落をそれぞれ別assertで再現すること。
  - fixtureに実token、実氏名、実給与、外部IDがないことをsecret/禁止値scanで確認する。
- **Demo**: 公式OpenAPIの該当endpoint/fieldと失敗test名を1対1で提示し、現行testが意図した理由でredになることを確認する。
- **完了証拠**: OpenAPI commit、fixture一覧、red test command/失敗method、外部条件READY/BLOCKEDをledgerへ記録。
- **失敗/ロールバック判定**: 公式資料とfixtureが矛盾、またはsandbox実データをfixtureへ保存した場合はFAIL。追加した人工fixture/testだけを戻し、秘密漏えい時はcredentialを即時失効する。

## HFP-01-002 事業所境界と接続状態のschemaを追加する

- [x] **HFP-01-002 — connection/link forward migration**
- **Requirements**: HFP-01-R03, HFP-01-R04, HFP-01-R12
- **依存**: HFP-01-001
- **Objective**: 再認可状態と事業所内employee IDを正しく表現し、別事業所へのlink誤用をDB境界で防ぐ。
- **実装ガイダンス**:
  - 着手時のlatest+1で新規Flywayを作り、V21を編集しない。
  - `t_freee_connection.connection_status`を追加する。
  - `t_freee_employee_link.freee_company_id`を追加し、確実な既存companyだけbackfillする。旧employee単独uniqueをcompany+employee複合uniqueへ置換する。
  - `FreeeConnection`、`FreeeEmployeeLink`、`schema-freee-payroll-h2.sql`、`engineer-schema-h2.sql`を同期する。
  - NULL legacy linkをactive給与linkとして扱わない前提をentity/service testで固定する。
- **Test**:
  - H2 schema test、empty MySQL migration、V21適用済み相当からのupgrade、index名/複合unique、legacy NULL/backfill、soft deleteを確認する。
  - 同じemployee IDを別companyへ登録可、同じcompanyでは不可、engineer重複は常に不可を実DBでassertする。
- **Demo**: schema metadata queryでcolumn/default/indexを表示し、個人データを含まないfixtureで3つのunique caseを実行する。
- **完了証拠**: migration filename/checksum、H2/MySQL test class/method、実行件数、skip 0をledgerへ記録。
- **失敗/ロールバック判定**: DockerなしはMySQL gate BLOCKED。適用済みmigrationは編集/downせず、menu無効化とforward fixを用いる。

## HFP-01-003 OAuth、会社検証、refresh、revokeを公式契約へ合わせる

- [x] **HFP-01-003 — OAuth/connection lifecycle**
- **Requirements**: HFP-01-R02, HFP-01-R03
- **依存**: HFP-01-001, HFP-01-002
- **Objective**: 選択事業所と管理者権限が検証された接続だけを`CONNECTED`にし、失効と解除を安全に処理する。
- **実装ガイダンス**:
  - `application*.yml`と`FreeeIntegrationServiceImpl`でOAuth/HR/API base URLをdesign §3どおり分離する。S11/S15の`api-base-url`意味は維持する。
  - 認可URLを公式host＋`prompt=select_company`へ修正し、独自`scope`を削除する。
  - `FreeeOAuthController.callback`でstate TTL/一回性/拒否callbackを扱う。code/state/provider messageをredirectへ載せない。
  - token保存前にaccess tokenで`/api/v1/users/me`を呼び、tokenのcompany IDとcompany_admin事業所を確認する。失敗時は既存正常接続を上書きしない。
  - status DTO/状態機械、lock後再確認、refresh token必須rotation、`REAUTH_REQUIRED`を実装する。
  - revokeはaccess/refresh双方を扱い、一時障害ではlocal rowを残す。
- **Test**:
  - exact URL/query/form、state正常/欠落/不一致/期限/再送/拒否、company mismatch/self_only、既存接続保護。
  - 並行refreshで外部refresh 1回、rotation保存、invalid_grant、401 refresh上限1回。
  - revoke成功/既失効/片方timeout、prod URL/secret/key validation、秘密log 0。
- **Demo**: Mock serverでauthorize→callback→status→forced refresh→revokeを通し、状態遷移を秘密なしで表示する。
- **完了証拠**: test method一覧、外部call回数、状態遷移、captured log scan結果をledgerへ記録。
- **失敗/ロールバック判定**: 同一refresh tokenを2回使用、認可失敗で旧接続破壊、revoke失敗で解除表示、秘密出力のいずれかはFAIL。旧不正OAuth URLへ戻すrollbackは禁止する。

## HFP-01-004 Typed HR contract、pagination、error matrixを実装する

- [x] **HFP-01-004 — HR contract adapter and transport**
- **Requirements**: HFP-01-R01, HFP-01-R05, HFP-01-R06, HFP-01-R07
- **依存**: HFP-01-001, HFP-01-003
- **Objective**: 公式の少数endpointだけをtypedに読み、100件超、schema drift、外部障害を決定的に処理する。
- **実装ガイダンス**:
  - `service/freee/FreeeHrContractAdapter`と`dto/freee/hr/*`を追加し、未知field許容＋必須root/ID/total検証を行う。
  - `hrGet`とbase URL付き`executeWithRetry`を最小変更で追加する。public`apiGet/apiPost` signatureを変更しない。
  - employeesのraw array pagination、salary/bonusの`total_count` paginationをdesign §8どおり実装する。ID重複、途中空、反復、上限を検知する。
  - error bodyのcodeを分類し、design §11のretry/actionへ変換する。backoff testで実sleepしないseamを用意する。
  - error時はstatus、X-Request-Id、内部correlation IDだけをlogし、raw body/tokenを出さない。
- **Test**:
  - 0/1/100/101/200、100件ちょうどの追加空page、duplicate ID、途中空、total変化、root欠落、未知field、invalid amount。
  - error matrix全行のcall回数・状態・BusinessException code/message key、総待機上限。
  - URL encodingと必須company/year/month/limit/offsetをrequest matcherでassertする。
- **Demo**: 101件fixtureで2page取得し、page requestがoffset 0/100の2回、重複/欠落0であることを示す。反復fixtureが有限時間で失敗することも示す。
- **完了証拠**: fixture→test method trace、request回数、retry回数、実行時間、log scanをledgerへ記録。
- **失敗/ロールバック判定**: HTTP 200不正schemaを空扱い、nullを0扱い、無限loop、POST自動retry、public shared API破壊はFAIL。adapterとHR private経路だけを選択的に戻す。

## HFP-01-005 会社境界付き従業員対応付けを完成する

- [x] **HFP-01-005 — employee mapping**
- **Requirements**: HFP-01-R04
- **依存**: HFP-01-002, HFP-01-004
- **Objective**: 現在会社のfreee従業員と非BP内部要員を、人の確認を伴う1対1関係として安全に管理する。
- **実装ガイダンス**:
  - 全期間employeesから必要最小fieldだけを`FreeeEmployeeDto`へ移す。
  - 給与専用の内部要員候補API/service queryを追加し、`/api/engineers?size=1000`依存と生employee ID入力を廃止する。
  - link/unlinkは現在companyを必須にし、BP、削除済み、存在しないemployee、company違い、unique競合を拒否する。
  - legacy NULL/別company linkは`RECONFIRM_REQUIRED`として表示し、給与へ使用しない。氏名一致で自動更新しない。
  - `confirmedBy`の取得方法を一つにし、引数を無視する実装を残さない。
- **Test**:
  - current/other/null company、BP直接指定、BPへ変更済み、退職、給与対象外、duplicate、並行link、unlink対象company。
  - responseに銀行/住所/家族/生年月日fieldがないこと。
- **Demo**: 非BP要員link→表示→unlink、BP候補非表示＋直接API拒否、legacy link再確認を架空dataで実行する。
- **完了証拠**: API request/result status、DB link件数（ID値なし）、競合test、privacy field testをledgerへ記録。
- **失敗/ロールバック判定**: freee`employment_type == BP`依存、氏名自動確定、別company link転用、OR条件で他link削除はFAIL。linkは自動一括削除せず、誤rowだけ明示解除する。

## HFP-01-006 給与・賞与変換と対応付けfilterを完成する

- [x] **HFP-01-006 — salary/bonus read model**
- **Requirements**: HFP-01-R05
- **依存**: HFP-01-004, HFP-01-005
- **Objective**: 公式fieldを正しいnullable金額・区分付き明細へ変換し、対応付け済み内部要員だけへ返す。
- **実装ガイダンス**:
  - typeをallowlistし、給与/賞与の別endpointを選択する。
  - `PayrollStatementDto`/`PayrollItemDto`をdesign §10へ合わせる。既存consumerを検索してから`items Map`を置換する。
  - string→BigDecimalを厳密変換し、nullを保持する。同名itemをlistで保持する。
  - current companyの有効link＋非BP/non-deleted Engineerとinner joinし、未対応従業員の金額を返さない。
  - salaryの会社負担、bonusのallowanceを正しいcategoryへ変換する。
- **Test**:
  - salary/bonus各fixture、計算中null、正式0円、同名item 2件、invalid amount、未対応、BP変更済み、別company link、安定sort。
  - response JSONにraw provider objectや不要PIIが混入しないこと。
- **Demo**: 架空の対応済み1件＋未対応1件で、対応済みだけの給与/賞与合計・全itemと、計算中の`—`相当DTOを確認する。
- **完了証拠**: input件数/output件数、field mapping test、null/0差異、禁止field scanをledgerへ記録。
- **失敗/ロールバック判定**: `statements/gross_amount/deductions/net_amount`旧field残存、null→0、Map上書き、未対応金額返却はFAIL。給与永続tableを追加した場合は設計逸脱として差分を撤回する。

## HFP-01-007 静的権限、no-store、機微GET監査を完成する

- [x] **HFP-01-007 — security, cache and audit**
- **Requirements**: HFP-01-R08, HFP-01-R09
- **依存**: HFP-01-003, HFP-01-005, HFP-01-006
- **Objective**: UIに依存しないrole境界、CSRF、cache禁止、内容を漏らさない1操作1監査を成立させる。
- **実装ガイダンス**:
  - `SecurityConfig`へOAuth/payroll page/APIの静的ruleを正しい順序で追加し、controller method securityも維持する。
  - page/API/status/employees/statements/callbackの全responseへno-storeを付ける。
  - 給与監査を既存`AuditLogService`へ統合し、design §12.3の固定applicationCodeを使用する。raw queryやIDを記録しない。
  - `ApiAuditFilter`との二重記録を防ぎ、link/unlink/OAuth/給与GETを各1rowにする。
  - 機微明細は監査記録不能時に返さない。provider失敗時の監査も元errorを秘密なしで記録する。
- **Test**:
  - 管理者/HR/営業/マネージャー/要員/未認証でpage、API、OAuthをmatrix testする。
  - PUT/DELETEはCSRFなし403、ありでroleに応じ成功。
  - 全GET response header、1 request 1 audit row、年月/type/status、監査DB/log禁止値0。
- **Demo**: 6主体の直接URL/API、CSRF有無、browser cache header、監査rowを架空dataで確認する。
- **完了証拠**: role matrix表、MockMvc method、header、audit row count、禁止値scanをledgerへ記録。
- **失敗/ロールバック判定**: menu非表示だけ、GET監査欠落/重複、raw query/金額/氏名/外部ID/token保存、no-store漏れはFAIL。監査DB変更は不要で、既存audit tableを再利用する。

## HFP-01-008 給与画面を操作可能な完成形へする

- [x] **HFP-01-008 — payroll UI and accessibility**
- **Requirements**: HFP-01-R10
- **依存**: HFP-01-005, HFP-01-006, HFP-01-007
- **Objective**: 管理者/HRが接続状態、対応付け、給与/賞与、計算中、障害を誤認せず操作できる画面にする。
- **実装ガイダンス**:
  - inline JSを`static/js/modules/payroll.js`へ移し、`SES.api`、CSRF、Toast、escape規約を使う。
  - status四状態、管理者だけの接続/再接続/解除、解除確認dialogを実装する。
  - freee従業員/内部要員をselectで対応付け、生ID通常入力を廃止する。
  - salary/bonus select、年月、loading二重送信防止、0件、計算中、再認可、権限/plan/rate障害を区別する。
  - 合計と区分別item詳細を表示し、nullと0を区別する。390px、keyboard、label、ARIAを満たす。
  - 新規message keyは全bundleを同期する。console/logへ給与内容を出さない。
- **Test**:
  - Thymeleaf/MockMvc UI contract、JS syntax、message bundle consistency、XSS escape、null/0 formatter、role別button。
  - 既存`PayrollLandmarkA11yTest`を拡張し、単一main、region、aria-live、label/forを確認する。
- **Demo**: desktop/390pxで接続、link/unlink、給与、賞与、item展開、0件、計算中、再認可、解除をkeyboardでも通す。
- **完了証拠**: viewport、操作checklist、console error 0、network status/header。給与screenshotや表示値は保存しない。
- **失敗/ロールバック判定**: 生ID入力必須、null=0円、賞与導線なし、HRへ接続button、mobileで操作不能、innerHTML未escapeはFAIL。template/JSだけを選択的に戻せるよう同一Task差分に限定する。

## HFP-01-009 CashFlowとS11/S15互換性を閉じる

- [x] **HFP-01-009 — downstream compatibility**
- **Requirements**: HFP-01-R11
- **依存**: HFP-01-003, HFP-01-004, HFP-01-006
- **Objective**: 正しい給与DTOをCashFlowへ反映しつつ、共有freee基盤の既存consumerを壊さない。
- **実装ガイダンス**:
  - `CashFlowForecastServiceImpl.getEstimatedPayroll()`をdesign §14の優先順位へ修正する。
  - actual会社負担が全件揃う月だけ実額を使い、不完全なら既存率を使う。全null/0件と正式0円を区別する。
  - S11 `FreeeAttendanceProvider`、S15 `bankDeposits/PaymentReconciliation`のpath、method、業務変換を変更しない。
  - OAuth base分離による既存Mock URL期待値だけを正当な範囲で更新する。
- **Test**:
  - CashFlow: actual負担、率fallback、全null、0件、正式0円、前月→2か月前→設定値。
  - `FreeeIntegrationServiceApiTest`、`FreeeAttendanceProviderTest`、`PaymentReconciliationServiceImplTest`および関連対象suite。
- **Demo**: freee未接続、給与計算中、確定給与の3caseでCashFlowが設定値/設定値/実額となり、契約・請求・勤怠画面が利用可能なことを確認する。
- **完了証拠**: downstream test method、既存public signature diffなし、3case結果、S11/S15 test件数をledgerへ記録。
- **失敗/ロールバック判定**: S11/S15仕様をついでに変更、全nullを0円、会社負担二重加算、shared method破壊はFAIL。本Task差分だけを戻し、OAuth公式host修正は戻さない。

## HFP-01-010 全自動gateとschema/privacy回帰を通す

- [x] **HFP-01-010 — automated release gates**
- **Requirements**: HFP-01-R12
- **依存**: HFP-01-002〜HFP-01-009
- **Objective**: 局所testだけの偽greenを排除し、CI、実MySQL、privacy、secretのrelease条件を満たす。
- **実装ガイダンス**:
  - 全Taskのcheckboxと証跡をsource/testから再照合し、未実施をcheckしない。
  - 対象suite、JS syntax、migration smoke、`verify-like-ci.ps1`を実行する。
  - repository/diff/test report/log/audit DBを禁止情報patternでscanする。fixtureの架空token patternは実secretと区別する。
  - official OpenAPI固定commitに対するcontract testを再実行する。
- **Test**:
  - `mvn`対象test、Docker実MySQL smoke、`scripts/verify-like-ci.ps1`。
  - failure 0、error 0、CI相当でskip 0。実行順序・timezone/localeはpom設定を変更しない。
- **Demo**: clean processで同じcommandを再実行し、結果が再現することを確認する。
- **完了証拠**: command、commit/diff hash、suite/test件数、failure/error/skip、所要時間、secret scan 0をledgerへ記録。生logを貼りすぎない。
- **失敗/ロールバック判定**: Docker test skip、Node test skip、既存test failureを「無関係」として未調査、秘密検出はBLOCKED/FAIL。`Assumptions`でskipを増やして通さない。

## HFP-01-011 freee sandbox E2Eと独立Reviewへ引き渡す

- [ ] **HFP-01-011 — sandbox, evidence and handoff**
- **Requirements**: HFP-01-R12
- **依存**: HFP-01-010
- **Objective**: mockでは検証できないapp権限、実OAuth、公式response、token rotation、revokeを架空sandboxで確認し、独立Review可能な証跡を完成する。
- **実装ガイダンス**:
  - credentialは環境変数/secret storeから供給し、対話、shell output、history、repositoryへ表示しない。
  - design §15.3の接続→会社→employees→link→salary→bonus→refresh→revoke→reconnectとrole拒否を実行する。
  - provider request IDが必要なら秘密を含まない形でledgerへ記録する。response body、外部ID、氏名、金額、screenshotは保存しない。
  - 全Acceptanceをtraceし、`review-conversation.md`を独立AIへ渡す。実装AI自身の説明をReview証拠にしない。
- **Test**:
  - sandbox全step成功、監査1操作1row、no-store、DB非永続化、revoke後API拒否、reconnect復旧。
  - merge前の独立Reviewで`REVIEWABLE`、P0/P1=0、未管理acceptance=0。P2/NOTEの延期は発注者承認、owner、期限、release影響付き。
- **Demo**: 管理者とHRで一気通貫、禁止4主体の直接URL/API、desktop/390pxを実行する。
- **完了証拠**: 実行日時、app種別、架空tenant確認、stepごとのPASS/status、監査件数、DB table/row非増加、review verdict。秘密/給与値なし。
- **失敗/ロールバック判定**: credential未提供、実給与を含むtenantしかない、sandbox API不利用、revoke確認不能、独立Review未実施は`BLOCKED`。接続をrevokeし、menuを無効化して再実行条件を記録する。

## merge前の実装引渡し条件

以下をすべて満たした場合だけ本specを完了とする。

1. HFP-01-001〜HFP-01-011がすべて`[x]`で、各行にledger証跡がある。
2. HFP-01-AC01〜HFP-01-AC15相当の証拠を独立Reviewが確認し、verdictが`REVIEWABLE`。
3. 未解決P0/P1、未管理acceptance、BLOCKED gate、test skip、secret/給与証跡が0。延期P2/NOTEは発注者承認、owner、期限、release影響がある。
4. S11/S15を含む全回帰、実MySQL、sandbox、desktop/390pxが完了している。

## 本specの最終PASS

上記を満たしてmergeした後、merge済みcommitとmerge deltaを独立Reviewし、main上の共有consumer回帰を含めてHFP-01-AC01〜HFP-01-AC15がすべてPASSした場合だけ本specを`PASS`とする。merge前の`REVIEWABLE`を最終PASSとして転記しない。
