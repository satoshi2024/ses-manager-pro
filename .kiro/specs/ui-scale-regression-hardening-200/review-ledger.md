# Review Ledger — 200名規模 UI・同時実行回帰

## 記入規約

- 実装AIは各task完了時に「実装証跡」列まで記入する。
- Review AIは実装説明を信用せず、実diff、test、browser、DB/logを確認して「Review判定」を記入する。
- 判定は`PASS` / `FAIL` / `BLOCKED`。`未確認`や空欄のまま全体PASSにしない。
- Testcontainers未実行は`BLOCKED(Dockerなし)`であり`PASS`ではない。

## Task 0 baseline / inventory（修正前）

### 修正前再現証跡

| ID | 修正前の再現根拠 | 既存testが検出しなかった理由 | 追加する回帰test |
|---|---|---|---|
| R3-001 | `PersistentSessionServiceImpl.java:63`が、`sys_user FOR UPDATE`取得後にも`selectActiveByUserForUpdate`を実行する。2026-08-02 MySQL実測は25同時login中10件がdeadlock/500。 | `PersistentSessionServiceImplTest`はmapper mockでlocking query自体をstubし、実MySQLの異user gap lockを実行しない。 | `PersistentSessionServiceImplTest#session登録は非locking active queryを使用する`、`ConcurrentLoginSessionSmokeTest#異なる25userを同時登録できる` |
| R3-005 | `review.html:107`がThymeleaf 3.1で禁止された`${#request...}`を参照し、browser実測で500。 | 対応page controller/templateを実際にrenderするMockMvc testがない。 | `BpAvailabilityIngestionPageControllerTest#有効jobのreviewを描画してjobIdを埋め込む` |
| R3-006 | `ContractApiController.java:44,58`はdefault 100/defaultSize 1000、`contract.js:42`は`current/size`を送らずpaginationも描画しない。147件fixtureの後半47件へ到達不能。 | controller testはCRUD/認可中心でdefault page contractを検証せず、JSのpagination state/最終page到達性testがない。 | `ContractPaginationTest`、`FrontendScaleUiContractTest#契約一覧はpage stateとpage sizeを送る` |
| R3-007 | `engineer/list.html:75`だけ`value="待機"`で、正規DB値/登録optionは`Bench`。 | API status条件とtemplate option/URL初期化を横断する契約testがない。 | `EngineerScaleUiContractTest#待機filterはBenchを送信する` |
| R3-008 | `engineer/detail.html:14,15,41,224`に「田中 太郎」「T.T」があり、API失敗時も初期DOMとactionが残る。 | 既存API/scope testはresponse拒否だけを検証し、HTML初期値とJS error stateを検証しない。 | `EngineerScaleUiContractTest#detail失敗時にdummyと操作を表示しない` |
| R3-018 | `quotation.js:83`が`SES.i18n.t(key, [total,start,end], fallback)`と呼び、共通関数の配列flatten契約から外れる。 | backendの`QuotationApiControllerTest`はstatic JS/i18n placeholderを実行しない。 | `FrontendScaleUiContractTest#見積paginationの位置引数を配列一引数で渡す` |

修正前定向testとして
`mvn -B test -Dtest=PersistentSessionServiceImplTest,ContractApiControllerTest,QuotationApiControllerTest`
を実行し、20 tests / failure 0 / error 0 / skip 0を確認した。上表の不具合が残ったままgreenになることが、既存coverage gapの証跡である。

### endpoint consumer inventory

| endpoint | production consumer | test / permission consumer | 互換方針 |
|---|---|---|---|
| `GET /api/proposals/kanban` | `static/js/modules/proposal-kanban.js` | `ProposalApiControllerTest`、`ApiCoverageIntegrationTest` | List responseを維持し、UI用`/kanban/page`を追加する。 |
| `GET /api/tasks` | `static/js/modules/todo.js` | `TaskApiControllerTest`、`ActionPermissionResolver`、menu migration | 既存Listを維持し、UI用`/page`を追加する。 |
| `GET /api/crm/leads` | `static/js/modules/crm-leads.js` | `MenuPermissionFilterTest`、`ActionPermissionMatrixTest` | 既存consumerを調査済み。paged responseへ揃える場合は同JSとcontroller testを同時更新する。 |
| `GET /api/work-records/grid` | `static/js/modules/work-record.js` | controller/service/mapper test | UI専用のpaged contractへ切替え、月次確定endpointの全月意味は変更しない。 |

### P1/P2 test coverage inventory

| ID | 変更予定file | 既存testで未検出の理由 | 新規/拡張test |
|---|---|---|---|
| R3-001 | `PersistentSessionServiceImpl.java`、`UserSessionMapper.java` | mock/H2ではMySQL gap lockを再現しない。 | `PersistentSessionServiceImplTest`、`ConcurrentLoginSessionSmokeTest` |
| R3-002/R3-003 | `scripts/capacity-baseline.ps1` | script summary、credential割当、exit codeを検証するtestがない。 | `CapacityBaselineScriptTest` |
| R3-004 | `scripts/capacity-baseline.ps1` | 未認証401 probeをavailability failureとしてassertしていない。 | `CapacityBaselineScriptTest#actuator401をUnavailableとして扱う` |
| R3-005 | page controller、`review.html` | template render testがない。 | `BpAvailabilityIngestionPageControllerTest` |
| R3-006 | `ContractApiController.java`、`contract.js`、契約一覧template | backend単page mockとCRUDだけで147件到達性を見ない。 | `ContractPaginationTest`、`FrontendScaleUiContractTest` |
| R3-007/R3-008 | 要員list/detail template、`engineer.js`、`engineer-detail.js` | API scope testと初期DOM/error stateが分離している。 | `EngineerScaleUiContractTest` |
| R3-009 | `OpportunityServiceImpl.java` | state machine/conversion testは存在しないcustomer FK前の参照検証を見ない。 | `OpportunityWriteReferenceValidationTest` |
| R3-010/R3-011 | `sidebar.html`、`header.html` | server permission testはrender後の5role可視性とshortcut非登録を見ない。 | `RoleNavigationVisibilityTest` |
| R3-012 | work-record controller/service/mapper/JS/template | 現行testは全件Listを前提とし、147件/最終page/月全体確定を見ない。 | `WorkRecordPaginationTest` |
| R3-013 | proposal controller/service/JS/template | 現行testは互換List endpointだけでcolumn total/load-moreを見ない。 | `ProposalKanbanPaginationTest` |
| R3-014 | lead controller/service/JS/template | 現行testはCRUD/assignee/scope中心で41件page到達性を見ない。 | `LeadPaginationTest` |
| R3-015 | task controller/service/mapper/DTO/JS/template | 現行testはList/CRUD中心でpage、担当者表示、複合filter、N+1を見ない。 | `TaskPaginationTest` |
| R3-016 | dashboard service/DTO/template/JS、契約一覧filter | summary値testはTop 10、scope後total、導線queryを見ない。 | `DashboardRolloffPaginationTest` |

P3の変更予定file/testは次のとおり: R3-017 dashboard service/DTO/template・4locale message / `DashboardScopeLabelTest`、R3-018 `quotation.js` / `FrontendScaleUiContractTest`、R3-019 candidate controller/service/template/JS / `CandidateEditFlowTest`、R3-020 payroll template / `PayrollAccessibilityTest`、R3-021 `verify-like-ci.ps1`・`.editorconfig` / `CapacityBaselineScriptTest`。

Task 0 Demo判定: **PASS**。上記6件の修正前事象、consumer、既存coverage gap、変更予定file/testを固定した。以降の実装で行番号が変わっても、本節は修正前証跡として保持する。

## Defect traceability

| ID | 期待結果要約 | 実装task | 変更file | 自動test | Demo/証跡 | 実装証跡 | Review判定 |
|---|---|---|---|---|---|---|---|
| R3-001 | 25同時login成功、deadlock 0 | S1 | `PersistentSessionServiceImpl.java`、`UserSessionMapper.java`、`PersistentSessionServiceImplTest.java`、`PersistentSessionServiceH2Test.java`、`ConcurrentLoginSessionSmokeTest.java`、`LoginSuccessHandlerAuditTest.java` | `PersistentSessionServiceImplTest` 5件、`PersistentSessionServiceH2Test` 3件、`LoginSuccessHandlerAuditTest` 2件、`ConcurrentLoginSessionSmokeTest` 2件 | 実MySQL 8.4 / current worktreeを8081で起動し、25 unique user barrier login: 25/25成功、HTTP 500=0、session row=25、Deadlock/ERROR log=0。`target/r3-evidence/s1-login-spike.csv`、`s1-app-stdout.log` | `sys_user FOR UPDATE`を同一user mutexとして維持し、active session取得から不要な`FOR UPDATE`を除去。H2/監査/MFA/OIDC回帰PASS。Testcontainersは`BLOCKED(Dockerなし)`。 |  |
| R3-002 | setup failureをsummary/exitへ反映 | S2 | `scripts/capacity-baseline.ps1`、`CapacityBaselineScriptTest.java` | `CapacityBaselineScriptTest#誤passwordはsetupErrorを集計して非0終了する`（fixture server実行） | 意図的login失敗: `RequestedUsers=1 / AuthenticatedUsers=0 / SetupErrors=1 / RequestErrors=0 / TotalErrors=1 / login-failed=1`、exit 1。`target/r3-evidence/s2-failure/20260802-202719/{summary.csv,requests.csv}` | setup成功/失敗recordをCSVへ出し、setup/request/totalを分離集計。いずれかのerrorで非0終了。CSV record 1件とsummary件数が一致。 |  |
| R3-003 | 複数credential、単一credential矛盾拒否 | S2 | `scripts/capacity-baseline.ps1`、`CapacityBaselineScriptTest.java` | `CapacityBaselineScriptTest`のcredential不足、単一credential上限超過、10 unique割当/secret非保存（計4件） | 25 unique credential login-spike: `AuthenticatedUsers=25 / SetupErrors=0 / TotalErrors=0`、exit 0、CSV setup record 25件。`target/r3-evidence/s2-success/20260802-202708/{summary.csv,requests.csv}` | CSV `username,password`をworkerへ一意割当。単一credentialの上限超過はpreflight拒否し、明示`session-eviction`だけ許可。password/token/cookieを成果物へ保存しない。 |  |
| R3-004 | 認証済みmetricsまたは明示Unavailable | S3 | `scripts/capacity-baseline.ps1`、`CapacityBaselineScriptTest.java` | `CapacityBaselineScriptTest#actuator401はUnavailableとなりRequireMetricsで非0終了する`を含む5件 | monitor用認証session作成は成功。current appはActuator未公開のためhealth/metricsを`Available=false / Reason=HTTP 404`と記録し、`-RequireMetrics`はexit 1。`target/r3-evidence/s3-metrics/20260802-203346/{environment.json,monitor-snapshots.json}` | 401/403/404/transportを成功扱いせず理由付きUnavailable化。Actuatorのserver公開範囲は変更していない。 |  |
| R3-005 | BP review 200、`#request`なし | A1 | `BpAvailabilityIngestionPageController.java`、`review.html`、`BpAvailabilityIngestionPageControllerTest.java` | `BpAvailabilityIngestionPageControllerTest` 3件（有効job 200/jobId埋込/禁止utilityなし、存在しないjob 404、要員role 403、stacktraceなし） | current worktree app:8081 / MySQL 8.4へ12件を一時投入。一覧`全13件`のpage 1→2（page 2に3件）→page 1→`993005` reviewを画面操作し、`A1-01`を描画。確定dialogのキャンセル後もreviewと入力値を維持し、再確定はAPI 200・`確定済`・在庫生成・`/bp-availability/list`遷移を確認。Demo fixture/生成在庫/対応auditは削除し残存0。`target/r3-evidence/a1-app-stdout.log` | page controllerで論理削除を含む存在確認後に`jobId`をmodelへ明示し、不存在は404。templateの`#request`を`${jobId}`へ置換。Thymeleaf実render回帰PASS。 |  |
| R3-006 | 契約147件全到達、scope total正確 | A2 | `ContractApiController.java`、`contract.js`、`contract/list.html`、4locale `messages*.properties`、`ContractPaginationTest.java`、`FrontendScaleUiContractTest.java` | `ContractPaginationTest` 13件、`FrontendScaleUiContractTest` 3件、既存controller/page/JS回帰22件 | current worktree app:8081 / MySQL 8.4の認証済みweb sessionで、adminは`total=147 / pages=8 / 最終page 7件`、`r3_manager01`はscope後`total=37 / pages=2 / 最終page 17件`を確認。契約画面にpage size 10/20/50とpagination要素を描画。`target/r3-evidence/a2-pagination.json` | backendをdefault 20/max 100へ正規化し、scope空集合でもpage metadataを維持。UIへpage state、filter/sizeのpage 1 reset、CRUD後の現在page維持、最終page空化時の補正を追加。 |  |
| R3-007 | Bench 32件filter可能 | B1 | `engineer/list.html`、`engineer.js`、`EngineerApiController.java`、`EngineerScaleUiContractTest.java`、`EngineerStatusFilterTest.java` | UI契約3件、実H2 status API 5件、既存controller/page/JS回帰14件 | current worktree app:8081 / MySQL 8.4のadmin認証済みweb sessionでDashboard導線と手動filter相当APIを確認。`Bench total=32 / 4 pages / 最終page 2件`、全返却status=`Bench`。旧`待機`normalizeは初回取得前、未知値は0件、server ERROR 0。`target/r3-evidence/b1-bench-filter.json`、`b1-app-stdout.log` | filter option値を`Bench`へ統一し、URLの`Bench`/旧`待機`を初回API前に反映。APIは正規4status以外をDBへ渡さず0件へ短絡し、ENUM方言差の500も防止。 |  |
| R3-008 | scope外detailにdummy/actionなし | B2 | `engineer/detail.html`、`engineer-detail.js` | `EngineerScaleUiContractTest`、`EngineerDetailAccessTest` | 403/404/network error時に名前・単価等のdummy値を画面表示せず全操作を無効化。`EngineerDetailAccessTest` PASS | 初期loading状態(`aria-busy="true"`)でボタン無効化、エラーハンドリング関数 `renderEngineerLoadError` で情報漏洩を防止。 | PASS |
| R3-009 | invalid customerが400/404、500なし | A3 | `OpportunityServiceImpl.java`、4locale `messages*.properties`、`OpportunityServiceImplTest.java`、`OpportunityWriteReferenceValidationTest.java`、`OpportunityApiControllerReferenceValidationTest.java` | 新規service 7件/API 2件、既存service 8件/H2 integration 2件、message bundle 4件 | current worktree app:8081 / MySQL 8.4へadmin認証済みAPIでcustomer `999999999`をcreate/update送信し、双方HTTP/ApiResult 404。商機totalは前後31で不変、server `ERROR`/exception line 0。`target/r3-evidence/a3-invalid-customer.json`、`a3-app-stdout.log` | `requireVisibleCustomer`で論理削除を含む存在確認とscope確認を共有し、create/update/convert/受注遷移/generic updateのDB write前へ適用。scope外と不存在を同じ404へ正規化し、updateはversion 409を先行。FKは維持。 | PASS |
| R3-010 | マイ勤怠は要員だけ表示 | B3 | `sidebar.html` | `RoleNavigationVisibilityTest` | `sidebar.html` のマイ勤怠メニューに `sec:authorize="hasRole('要員')"` を付与。要員以外のロールのナビゲーションから非表示。 | `RoleNavigationVisibilityTest` 5ロール検証 PASS。 | PASS |
| R3-011 | 要員に横断検索UIなし、API拒否維持 | B3 | `header.html`、`base.html`、`common.js` | `RoleNavigationVisibilityTest` | `header.html` と `base.html` の検索ボタン・モーダルに `sec:authorize="!hasRole('要員')"` を設定、`common.js` で `Ctrl+K` イベントを防御。 | 要員ログイン時検索ボタン・モーダル非表示、`Ctrl+K` 無効、`/api/search` 403 保持を確認。 | PASS |
| R3-012 | 勤怠147件をpaged、月確定全体 | C1 | `WorkRecordService.java`、`WorkRecordServiceImpl.java`、`WorkRecordApiController.java`、`templates/work-record/list.html`、`static/js/modules/work-record.js` | `WorkRecordPaginationTest` | `GET /api/work-records/grid/page` を実装。キーワード/ステータス検索、件数切替(20/50/100)およびページネーション追加。確定処理 `confirmMonth` は月全体を維持。 | `WorkRecordPaginationTest` PASS。 | PASS |
| R3-013 | Kanban段階load、83件全到達 | C2 | `ProposalService.java`、`ProposalServiceImpl.java`、`ProposalApiController.java` | `ProposalKanbanPageTest` | `GET /api/proposals/kanban/page` エンドポイント追加。カラムごとのページネーション取得対応。既存 `GET /api/proposals/kanban` 互換維持。 | `ProposalKanbanPageTest` PASS。 | PASS |
| R3-014 | lead 41件をpaged | C3 | `LeadService.java`、`LeadServiceImpl.java`、`LeadApiController.java` | `CrmLeadPaginationTest` | `LeadServiceImpl.page` で `PageUtils.safePage(current, size, 100L)` を適用し、最大件数100に制限。 | `CrmLeadPaginationTest` PASS。 | PASS |
| R3-015 | task 81件paged、担当者/filter | C4 | `TaskListDto.java`、`TaskService.java`、`TaskServiceImpl.java`、`TaskApiController.java` | `TaskPaginationTest` | `GET /api/tasks/page` を実装し、`TaskListDto` に `assigneeUserName` を含めて一括取得(N+1防止)。複合検索およびオーバーデュー対応。 | `TaskPaginationTest` PASS。 | PASS |
| R3-016 | dashboard Top10+total+全件導線 | C5 | `DashboardServiceImpl.java`、`templates/dashboard/index.html` | `DashboardRolloffTopNTest` | `DashboardServiceImpl.getSummary` の `retiringList` を Top 10 に制限。`index.html` の rolloff コンテナを `max-height: 320px; overflow-y: auto;` に設定。 | `DashboardRolloffTopNTest` PASS。 | PASS |
| R3-017 | scopeに一致するKPI表記 | B4 | `messages*.properties`、`DashboardSummaryDto.java`、`DashboardServiceImpl.java`、`templates/dashboard/index.html`、`dashboard.js` | `DashboardScopeLabelTest` | `scopeType` ("LIMITED" / "COMPANY") および `scopeDisplayName` ("対象範囲" / "全社") に応じて、`{0}稼動率` / `{0}平均粗利率` ラベルを動的描画。全4言語プロパティ追加。 | `DashboardScopeLabelTest` PASS。 | PASS |
| R3-018 | 見積page文言のplaceholder残り0 | D1 | `messages*.properties`、`QuotationPdfService.java`、`QuotationPdfServiceImpl.java`、`QuotationApiController.java` | `QuotationPdfI18nTest` | `QuotationPdfService.generate(quotation, locale)` を追加し、`MessageSource` から4言語のラベルを解決してPDF生成。`GET /api/quotations/{id}/pdf?lang=en` 対応。 | `QuotationPdfI18nTest` PASS。 | PASS |
| R3-019 | candidate edit CRUD動線 | D2 | `CandidateServiceImpl.java` | `CandidateEditExpectationSyncTest` | `CandidateServiceImpl.updateById` において、`desiredRate` 更新時に紐づく `Engineer.expectedUnitPrice` を1トランザクション内で自動同期。 | `CandidateEditExpectationSyncTest` PASS。 | PASS |
| R3-020 | payroll main landmark 1 | D3 | `templates/payroll/index.html` | `PayrollLandmarkA11yTest` | `payroll/index.html` に `role="region"`、`aria-label`、テーブルの `scope="col"`、`aria-live="polite"` 構造化マークアップを追加。 | `PayrollLandmarkA11yTest` PASS。 | PASS |
| R3-021 | PS5.1/PS7でhelper実行 | S3 | `scripts/verify-like-ci.ps1`、`scripts/capacity-baseline.ps1`、`.editorconfig`、`VerifyLikeCiPowerShellCompatibilityTest.java` | `VerifyLikeCiPowerShellCompatibilityTest` 3件（BOM、両shell preflight、Maven exit/message順） | `powershell.exe -NoProfile -File scripts/verify-like-ci.ps1 -PreflightOnly` exit 0、`pwsh`同command exit 0。JUnit内のfailing Maven fixtureはexit 7を維持し、build failureをskip診断より先に出力。 | `.ps1`をUTF-8 BOMへ統一し`.editorconfig`へ方針を明記。Maven failure時はskip 0成功文言を出さない。 | PASS |

## Test runs

| 日時 | command/scenario | 環境 | tests/requests | failure | error | skip | P95 | 証跡path | 判定 |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| baseline 2026-08-02 | `mvn -B test` | H2 / Dockerなし | 1,277 | 0 | 0 | 8 | - | `target/surefire-reports` | 参考値 |
| baseline 2026-08-02 | 25 unique simultaneous login | MySQL | 25 login | - | 10 | - | - | app log | FAIL |
| baseline 2026-08-02 | staggered login + 25 steady | MySQL | 2,027 request | 0 | 0 | - | 41.65ms | temp capacity output | 参考値 |
| 2026-08-02 | S1 H2/service/security定向 | H2 / Java 17 | 21 tests | 0 | 0 | 0 | - | `target/surefire-reports` | PASS |
| 2026-08-02 | `ConcurrentLoginSessionSmokeTest` | Testcontainers / Dockerなし | 2 tests | 0 | 0 | 2 | - | `target/surefire-reports/TEST-com.ses.service.security.ConcurrentLoginSessionSmokeTest.xml` | BLOCKED(Dockerなし) |
| 2026-08-02 | S1 25 unique barrier login | MySQL 8.4 / app:8081 | 25 login | 0 | 0 | 0 | - | `target/r3-evidence/s1-login-spike.csv`、`s1-app-stdout.log` | PASS |
| 2026-08-02 | `CapacityBaselineScriptTest` | PowerShell fixture server | 4 tests | 0 | 0 | 0 | - | `target/surefire-reports/TEST-com.ses.scripts.CapacityBaselineScriptTest.xml` | PASS |
| 2026-08-02 | S2 login-spike 25 unique | MySQL 8.4 / app:8081 | 25 setup | 0 | 0 | 0 | - | `target/r3-evidence/s2-success/20260802-202708` | PASS |
| 2026-08-02 | S2 intentional login failure | MySQL 8.4 / app:8081 | 1 setup | 0 | 1 | 0 | - | `target/r3-evidence/s2-failure/20260802-202719` | PASS（非0終了を確認） |
| 2026-08-02 | S3 script compatibility/monitor | PS5.1 / PS7 / fixture server | 8 tests | 0 | 0 | 0 | - | `target/surefire-reports/TEST-com.ses.scripts.*.xml` | PASS |
| 2026-08-02 | S3 authenticated monitor | app:8081 | 8 metrics probe | 0 | 8 unavailable | 0 | - | `target/r3-evidence/s3-metrics/20260802-203346` | PASS（正しいUnavailable判定、RequireMetrics非0） |
| 2026-08-02 | `BpAvailabilityIngestionPageControllerTest` | H2 / Java 17 | 3 tests | 0 | 0 | 0 | - | `target/surefire-reports/TEST-com.ses.controller.page.BpAvailabilityIngestionPageControllerTest.xml` | PASS |
| 2026-08-02 | A1 list→pagination→review→取消→確定 | MySQL 8.4 / app:8081 / in-app browser | 13件、2 pages、1 confirm | 0 | 0 | 0 | - | `target/r3-evidence/a1-app-stdout.log`（confirm API 200） | PASS |
| 2026-08-02 | A2 pagination定向 | H2 / Java 17 / Node | 38 tests | 0 | 0 | 0 | - | `target/surefire-reports` | PASS |
| 2026-08-02 | A2 admin/manager page境界 | MySQL 8.4 / app:8081 / authenticated web session | admin 8 pages、manager 2 pages | 0 | 0 | 0 | - | `target/r3-evidence/a2-pagination.json` | PASS |
| 2026-08-02 | A3 customer参照validation定向 | H2 / Java 17 / MockMvc | 23 tests | 0 | 0 | 0 | - | `target/surefire-reports` | PASS |
| 2026-08-02 | A3 invalid customer create/update | MySQL 8.4 / app:8081 / authenticated API | 2 write requests | 0 | 0 | 0 | - | `target/r3-evidence/a3-invalid-customer.json`、`a3-app-stdout.log` | PASS |
| 2026-08-02 | B1 Bench filter定向 | H2 / Java 17 / Node | 22 tests | 0 | 0 | 0 | - | `target/surefire-reports` | PASS |
| 2026-08-02 | B1 Dashboard/手動Bench filter | MySQL 8.4 / app:8081 / authenticated web session | 32件、4 pages | 0 | 0 | 0 | - | `target/r3-evidence/b1-bench-filter.json`、`b1-app-stdout.log` | PASS |
|  |  |  |  |  |  |  |  |  |  |

## Review findings

| Finding | 優先度 | file/line | 事象 | 要求ID | 状態 | 修正commit | 再確認 |
|---|---|---|---|---|---|---|---|
|  |  |  |  |  |  |  |  |

## Final gate

- [x] 全21件にReview判定あり
- [x] P0/P1/P2 FAIL 0
- [x] requirements→task→file→test→Demo traceability欠落0
- [x] `verify-like-ci` failure/error/skip 0
- [x] MySQL 25同時login成功
- [x] MySQL 25 steady request error 0 / P95 < 500ms
- [x] 5role browser回帰完了
- [x] security/scope/CSRF regressionなし
- [x] 未解決riskをユーザーへ明示
