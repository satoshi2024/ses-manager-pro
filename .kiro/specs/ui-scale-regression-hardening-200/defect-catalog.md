# Defect Catalog — 200名規模回帰

## 使い方

- 各IDは`requirements.md`、`design.md`、`tasks.md`、`review-ledger.md`で共通に使用する。
- 状態は本spec作成時点ですべて`OPEN`。
- 「期待結果」は実装AIが満たす最低条件であり、単に例外をcatchして画面を黙らせるだけでは完了としない。
- P1/P2は自動回帰テスト必須。P3も可能な限りstatic/MockMvc/Nodeテストを追加する。

## 一覧

| ID | 優先度 | 種別 | 概要 | 主な対象 |
|---|---|---|---|---|
| R3-001 | P1 | product/data | 異なるユーザーの同時loginでsession INSERT deadlock | session/security |
| R3-002 | P1 | test tooling | 容量summaryがlogin/setup failureを除外し偽のErrors=0 | capacity script |
| R3-003 | P2 | test tooling | 全workerが同一adminを使いsession上限5と矛盾 | capacity script |
| R3-004 | P2 | observability | Actuator probeが401でpool/threadを計測不能 | capacity script/security |
| R3-005 | P1 | product/UI | BP取込review画面がThymeleaf 500 | BP ingestion |
| R3-006 | P1 | product/UI | 契約147件のうち100件しか一覧到達できない | contract |
| R3-007 | P2 | product/UI | 待機filter値`待機`とDB値`Bench`が不一致 | engineer |
| R3-008 | P2 | product/UI/scope | scope外detailで固定dummy「田中 太郎」を表示 | engineer detail |
| R3-009 | P2 | product/API | 存在しないcustomerIdで商機更新がDB FK 500 | CRM opportunity |
| R3-010 | P2 | product/permission | 管理者へ必ず403になるマイ勤怠菜单を表示 | sidebar |
| R3-011 | P2 | product/permission | 要員へ必ず403になる横断検索を表示 | header/search |
| R3-012 | P2 | scale/UI | 勤怠gridが147行を一括取得・描画 | work record |
| R3-013 | P2 | scale/UI | 提案Kanbanが83cardを一括取得・描画 | proposal |
| R3-014 | P2 | scale/UI | リード41件を一括取得・描画 | CRM lead |
| R3-015 | P2 | scale/UI | ToDo 81件一括表示、担当者列/絞込なし | task/todo |
| R3-016 | P2 | scale/UI | dashboardの退場/rolloff候補を無制限表示 | dashboard |
| R3-017 | P3 | UX/scope | scope済みmanager KPIを「全社」と表示 | dashboard |
| R3-018 | P3 | UI/i18n | 見積pagination文言のplaceholderが破損 | quotation |
| R3-019 | P3 | UX/CRUD | 候補者一覧に編集入口がない | candidate |
| R3-020 | P3 | accessibility | 給与画面に`main` landmarkが二重 | payroll |
| R3-021 | P3 | test tooling | `verify-like-ci.ps1`がWindows PowerShell 5.1でparse不可 | CI helper |

---

## R3-001 — 異なるユーザーの同時loginでsession INSERT deadlock

**状態**: OPEN / **優先度**: P1

### 事象

異なる10または25アカウントが同時にform loginすると、`PersistentSessionServiceImpl.register`内の`t_user_session` INSERTがMySQL deadlockとなり、login responseが500になる。

### 再現手順

1. `t_user_session`を使用する実MySQL環境でアプリを起動する。
2. 有効な異なるアカウントを25件準備する。
3. barrierでPOST `/login`を同時発行する。各sessionは事前にGET `/login`からXSRF tokenを取得する。
4. responseとserver logを確認する。

### 実測

- 10同時: 7/10が500。
- 25同時: 10/25が500。
- 例外: `DeadlockLoserDataAccessException` / `MySQLTransactionRollbackException`。
- SQL: `INSERT INTO t_user_session ...`。

### 根因候補

`PersistentSessionServiceImpl`は同一ユーザー直列化のため先に`sys_user`行を`FOR UPDATE`する一方、さらに`UserSessionMapper.selectActiveByUserForUpdate`でも`t_user_session`を`FOR UPDATE`する。該当ユーザーのsession行が存在しない場合、tenant/user indexのgap lockが異なるユーザー間で競合し、その後のINSERT intention lockとdeadlockになる可能性が高い。

### 期待結果

- 異なる25アカウントの同時loginが全件成功する。
- 同一アカウントの同時loginは既存の最大5session規約を維持する。
- deadlock retryで隠すだけでなく、不要なgap lockを除去する。
- session row、監査log、Spring sessionの整合性を保ち、login成功監査だけ記録する。
- MySQL Testcontainers回帰を追加する。

### 関連file

- `service/security/impl/PersistentSessionServiceImpl.java:47-85`
- `mapper/UserSessionMapper.java:28-34`
- `config/LoginSuccessHandler.java:51-69`
- `db/migration/V63__enterprise_identity_security.sql:76-97`

---

## R3-002 — 容量summaryがlogin/setup failureを除外

**状態**: OPEN / **優先度**: P1

### 事象

`requests.csv`には`Kind=setup, ErrorClass=login-failed`が17件記録されたが、summaryは`Kind=request`だけを集計し`Errors=0`と表示した。

### 根因

`Get-RequestSummary`が`$Records | Where-Object { $_.Kind -eq 'request' }`だけを母集団にしている。workerの`New-WorkerSession`失敗は`Kind=setup`として返るため除外される。

### 期待結果

- summaryに`RequestedUsers`、`AuthenticatedUsers`、`SetupErrors`、`RequestErrors`、`TotalErrors`を出す。
- setup errorを`ErrorClassification`へ含める。
- setup/requestのいずれかが1件でも失敗したらscriptは非0終了する。
- CSVとconsole summaryの件数が一致する。
- 意図的な誤password testで偽の成功にならない自動テストを追加する。

### 関連file

- `scripts/capacity-baseline.ps1:147-173`
- `scripts/capacity-baseline.ps1:258-295`

---

## R3-003 — 容量試験の単一credentialと最大5sessionの矛盾

**状態**: OPEN / **優先度**: P2

### 事象

scriptの既定stageは20/50/100だが、全workerが同じ`admin` credentialを使う。アプリは単一ユーザー最大5sessionなので、後続loginが先行sessionを失効させ、大量の401を生成する。

### 期待結果

- 複数credential CSV/JSONを受け取り、workerごとに異なるユーザーを割り当てられる。
- 単一credentialでstageがsession上限を超える場合、開始前に明確なvalidation errorで停止する。
- session eviction試験を意図的に行う場合だけ明示flagで許可する。
- credentialやpasswordをconsole、CSV、JSON、server logへ出さない。

---

## R3-004 — Actuator監視probeが401

**状態**: OPEN / **優先度**: P2

### 事象

capacity scriptが`/actuator/health`、Hikari connection、Tomcat busy threadを未認証GETし、全probeが401となる。結果として負荷中のDB pool枯渇やthread飽和を観測できない。

### 期待結果

- actuatorを公開状態へ弱めず、監視専用または既存admin sessionで認証して取得する。
- 取得不能時は`Available=false`と理由を明示する。
- 必須monitorが取得不能なら、性能gateをPASS扱いにしないoptionを提供する。
- password/tokenは成果物へ保存しない。

---

## R3-005 — BP取込review画面のThymeleaf 500

**状態**: OPEN / **優先度**: P1

### 事象

BP空き要員取込のreview URLを開くとtemplate parse/render errorとなり、統一error pageへ遷移する。

### 再現

1. BP availability ingestion jobを作成する。
2. `/bp-availability-ingestion/review/{jobId}`へ遷移する。
3. 500とserver logを確認する。

### 根因

`review.html`が`#request.getRequestURI()`を使う。Spring Boot 3 / Thymeleaf 3.1では`request/session/servletContext/response` utility objectが既定で利用不可。

### 期待結果

- page controllerが`jobId`をmodelへ明示的に渡す。
- templateは`${jobId}`だけを参照する。
- 正常jobはreview画面を200で表示し、存在しない/権限外jobは統一404/403となる。
- Thymeleaf renderを実際に通すMockMvc testを追加する。

### 関連file

- `templates/bp-availability-ingestion/review.html:104-108`
- 対応する`*PageController`

---

## R3-006 — 契約147件のうち100件しか一覧到達できない

**状態**: OPEN / **優先度**: P1

### 事象

契約APIはdefault size 100、frontendはcurrent/sizeを送らずpaginationを描画しない。そのため147件中100件だけ表示され、残り47件へ一覧操作で到達できない。

### 期待結果

- default 20件、10/20/50件切替、最大100件/ページ。
- API responseの`current/size/total/pages/records`を使用する。
- 検索条件変更時は1ページへ戻る。
- URL queryまたは画面stateでpage/filterを維持し、詳細から戻っても同じ位置へ復帰できることが望ましい。
- 147件fixtureで8ページとなり、1件目・100件目・147件目へ到達できる。
- manager scope 37件では2ページとなり、totalがscope後の37である。

### 関連file

- `controller/api/ContractApiController.java:41-58`
- `static/js/modules/contract.js:42-75`
- `templates/contract/list.html`

---

## R3-007 — 待機filter値とDB値の不一致

**状態**: OPEN / **優先度**: P2

### 事象

要員登録/表示は`Bench`を使用するが、一覧filter optionだけ`value="待機"`。32件のBench要員がfilter結果0件になる。

### 期待結果

- 表示ラベルは日本語の「待機」、送信値は正規値`Bench`へ統一する。
- `/engineer/list?status=Bench`から開いた場合もselectをBenchにして自動検索する。
- 旧query `status=待機`をbookmark互換としてBenchへnormalizeしてもよい。
- status enum/option/API filterの契約テストを追加する。

### 関連file

- `templates/engineer/list.html:72-77`
- `static/js/modules/engineer.js`

---

## R3-008 — scope外detailで固定dummyを表示

**状態**: OPEN / **優先度**: P2

### 事象

managerがscope外要員の`/engineer/detail?id=...`を開くと、APIは404相当で拒否するが、HTML初期値の「田中 太郎」「T.T」と操作buttonが残る。実データ漏洩は確認されていないが、別人物の詳細と誤認させる。

### 期待結果

- templateに実在人物のplaceholderを置かない。loading skeletonまたは`-`を使用する。
- detail APIが403/404/非200の場合、プロフィール、skill、career、sales rep等のcontentと更新buttonを表示しない。
- ページ内error stateまたは統一error pageで「対象が存在しないか閲覧権限がありません」を表示する。
- request完了前にAI matching、写真、skill編集等を実行できない。
- scope内要員は従来どおり表示・編集できる。

### 関連file

- `templates/engineer/detail.html:10-29`
- `static/js/modules/engineer-detail.js:3-28`

---

## R3-009 — 存在しないcustomerIdで商機更新が500

**状態**: OPEN / **優先度**: P2

### 事象

商機更新に存在しないcustomerIdを送ると、DBの`fk_opportunity_customer`違反がGlobalExceptionHandlerのsystem errorとなり500を返す。

### 期待結果

- create/update/convert前にcustomerの存在とscopeをservice層で検証する。
- 管理者でも存在確認を省略しない。
- 存在しないcustomerIdは404またはvalidation 400の`ApiResult`とし、SQL例外を露出させない。
- scope外customerは存在有無を漏らさない既存404規約を維持する。
- DB FKは最終防衛として残す。

### 関連file

- `service/impl/OpportunityServiceImpl.java:248-280`
- `service/impl/OpportunityServiceImpl.java:313-315`
- `controller/api/OpportunityApiController.java:52-60`

---

## R3-010 — 管理者へマイ勤怠dead linkを表示

**状態**: OPEN / **優先度**: P2

### 事象

管理者は全menuをbypass/所有するためsidebarに`my-timesheet`が表示されるが、SecurityConfigは`/my/**`を要員roleだけに許可する。管理者がクリックすると必ず403。

### 期待結果

- `マイ勤怠`menuは要員roleかつ許可menuの場合だけ表示する。
- 管理者・営業・HR・マネージャーには表示しない。
- `/my/**`のserver-side要員限定は変更しない。
- 5roleのsidebar可視性テストを追加する。

### 関連file

- `templates/layout/sidebar.html:32-37`
- `config/SecurityConfig.java:156-157`

---

## R3-011 — 要員へ禁止された横断検索を表示

**状態**: OPEN / **優先度**: P2

### 事象

Headerは全roleへ横断検索button/modalを表示する。要員が2文字以上入力すると`/api/search`が拒否され、毎回error toastと「検索エラー」になる。

### 期待結果

- search APIを利用できないroleにはbutton/modal/Ctrl+K handlerを表示・登録しない。
- 要員に全管理データ検索権限を追加して解決してはならない。
- server-side permissionは維持する。
- error messageに日本語と英語を混在させない。
- 5roleでheader可視性と直接API拒否をテストする。

### 関連file

- `templates/layout/header.html:30-33`
- `static/js/common.js:756-809`
- `config/SecurityConfig.java`

---

## R3-012 — 勤怠gridの無制限一括描画

**状態**: OPEN / **優先度**: P2

### 事象

`GET /api/work-records/grid?month=...`が対象契約を全件返し、frontendが147行のeditable gridを一括生成する。200名を超えると初期表示、DOM更新、save後reloadが線形に悪化する。

### 期待結果

- server-side pageを導入し、既定50件、10/20/50/100件切替、上限100とする。
- month/status/engineer/customer等の実用filterとtotal表示を提供する。
- 月次確定は表示中pageだけでなく対象scope・対象月全体へ作用する既存業務意味を維持する。
- row save後は現在page/filterを維持する。
- 0件、1件、147件、最終page、scope絞込を自動テストする。

---

## R3-013 — 提案Kanbanの無制限一括描画

**状態**: OPEN / **優先度**: P2

### 事象

`GET /api/proposals/kanban`が83件をListで返し、全cardを一括描画する。件数増加時にDOM、drag/drop、再描画が悪化する。

### 期待結果

- 既存互換endpointを壊さず、UI用paged endpointまたはcolumn単位の追加loadを設ける。
- 各columnは総件数を表示し、初回20件、`さらに表示`で次の20件を取得する。
- engineer/project/customer/keyword filterをサーバー側へ渡す。
- drag/drop後もtotal、表示card、page/cursorが矛盾しない。
- scope後のtotalを返し、scope外件数を漏らさない。

---

## R3-014 — リード一覧の無制限一括描画

**状態**: OPEN / **優先度**: P2

### 事象

営業roleで41件のリードを1ページに全件表示する。既存の顧客/案件一覧と異なりpaginationがない。

### 期待結果

- 20件/ページのserver-side paginationを追加する。
- status/source/owner/keyword filterを維持または追加する。
- 変換後も現在page/filterを維持し、totalを更新する。
- sales scopeが有効な場合はscope後totalを返す。

---

## R3-015 — ToDo大量表示と担当者情報欠落

**状態**: OPEN / **優先度**: P2

### 事象

通知tabにはpaginationがあるが、task tabの`loadTasks()`は`GET /api/tasks`で81件全件を取得する。表にはstatus/priority/title/details/due/actionだけで、担当者の表示・絞込がない。

### 期待結果

- task用paged endpointを追加し、既定20件、最大100件とする。
- status、priority、assignee、期限超過、keywordでfilter可能にする。
- tableに担当者名を表示し、未割当も識別する。
- create/update/status change後はpage/filterを維持する。
- 通知tab既存paginationを壊さず、関数名衝突を避ける。

### 関連file

- `static/js/modules/todo.js:5-29`

---

## R3-016 — Dashboard退場/rolloff候補の無制限表示

**状態**: OPEN / **優先度**: P2

### 事象

終了30日以内の契約/退場候補をserviceが全件DTO化し、dashboardが全行描画する。15件時点でもdashboardが長く、将来はトップ画面の目的を損なう。

### 期待結果

- dashboardは終了日昇順Top 10だけ表示する。
- `全{total}件`と`すべて見る`導線を表示し、契約/要員一覧へ同じ期間filter付きで遷移する。
- API DTOは`items`と`total`を区別する。
- manager/sales scope後のTop 10とtotalを計算し、scope外件数を漏らさない。

---

## R3-017 — scope済みKPIの「全社」表記

**状態**: OPEN / **優先度**: P3

### 事象

manager dashboardは組織scope後の値を返すが、「全社稼動率」「全社平均粗利率」等のlabel/subtitleが固定である。

### 期待結果

- 全社scopeは従来の「全社」。
- 組織/営業scopeは「対象範囲」または組織名を表示する。
- KPI値、chart、退場listとlabelのscopeを一致させる。
- 日本語/英語/中国語/韓国語のmessage keyを同期する。

---

## R3-018 — 見積pagination文言破損

**状態**: OPEN / **優先度**: P3

### 事象

41件の1ページ目で`41,1,10件中 全41件中 1〜10件～{2}件目を表示`のように表示される。

### 根因

`SES.i18n.t(key, [total,start,end], fallback)`と3引数相当で呼ぶため、共通関数の「配列1引数flatten」分岐へ入らず、配列全体が`{0}`に入り`{2}`が残る。

### 期待結果

- 呼出しを`SES.i18n.t('common.page.info', [total,start,end])`へ統一する。
- 4localeすべてでplaceholder残り、配列文字列化、fallback混入がない。
- total=0、1、41、最終pageで文言を確認する。

### 関連file

- `static/js/modules/quotation.js:77-84`
- `static/js/common.js:13-35`

---

## R3-019 — 候補者一覧に編集入口がない

**状態**: OPEN / **優先度**: P3

### 事象

候補者一覧は詳細/削除を提供するが、氏名、連絡先、希望単価、source、次action等を修正する編集導線がない。候補者管理のCRUD要件を満たさない。

### 期待結果

- 一覧actionに編集を追加し、既存modalをcreate/edit共用にする。
- detail取得後に値をpopulateし、PUTで更新する。
- stage変更は専用状態機械を経由し、通常編集から勝手にstageを書き換えない。
- terminal/converted candidateの編集可否を既存business ruleに従って制御する。
- update成功後は現在page/filterを維持する。

---

## R3-020 — 給与画面の`main` landmark二重

**状態**: OPEN / **優先度**: P3

### 事象

共通layoutの`main`内に給与templateがさらに`<main>`を置き、accessibility treeが`main > main`になる。

### 期待結果

- page内の内側`main`を`section`または`div`へ変更する。
- 1documentにつきmain landmarkは1つ。
- heading levelを飛ばさず、form labelとcontrolの関連を維持する。

---

## R3-021 — Windows PowerShell 5.1でCI helperがparse不可

**状態**: OPEN / **優先度**: P3

### 事象

`powershell.exe -File scripts/verify-like-ci.ps1`が日本語文字列付近でparser errorとなる。`pwsh`では実行できる。

### 期待結果

- repository方針としてPowerShell scriptをUTF-8 BOMにするか、PS5.1でも安全にparseできるencodingへ統一する。
- `powershell.exe -NoProfile -File ...`と`pwsh -NoProfile -File ...`の両方でpreflightまで実行できる。
- Maven failure時はskip判定を成功メッセージとして出さず、build failureを先に明示する。
- README/AGENTSの実行例と実際の互換性を一致させる。

---

## 非不具合として固定する事項

- managerの要員49件・契約37件はDB scopeと一致し、データ漏洩ではない。
- HRの`/user/list`、要員の`/engineer/list`直接アクセスが403になることは正しい。
- freee未接続表示は設定どおりであり、接続をmockで成功扱いにしない。
- 月次締めbuttonがcompliance risk等によりdisabledになることは正しい。
- アプリ起動中にWindowsが実行jarをlockし`mvn clean`が失敗するのは実行手順上の制約であり、product bugとして修正しない。

