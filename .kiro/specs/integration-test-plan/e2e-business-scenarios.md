# SES Manager Pro 300 人規模 業務 E2E 実行仕様

本書は、300 人規模のデータ母集団上で、全 17 モジュールを横断する 7 業務シナリオを検証する。各シナリオは正常、拒否、障害からの回復の 3 分岐を持ち、**一意な E2E ID は計 21 件**である。

300 人はデータ母集団の大きさであり、同時接続数ではない。機能 E2E のブラウザ操作を 300 個並べても性能試験にはならない。同時負荷は `schedule-and-resources.md` の負荷 profile に従い、HTTP load runner と server/DB telemetry で別に測定する。

## 1. 共通実行契約

### 1.1 固定環境と actor

| 項目 | 固定値・規約 |
|---|---|
| DB | MySQL 8。全 migration と MySQL 向け `db/migration-dev/V100__seed_r3_scale_300.sql` を適用した `E2E-BASE-300` snapshot からケースごとに clone |
| Clock | `AS_OF=2026-08-17T09:00:00+09:00`、`Asia/Tokyo`。`当月` は使用せず `TEST_MONTH=2026-07` |
| Build | Module PASS 済みの同一 build SHA。途中で再 build しない |
| Actor | `ADMIN=s300.admin01`、`HR=s300.hr01`、`SALES_A=s300.sales01`、`SALES_B=s300.sales02`、`MANAGER=s300.mgr01`、`MEMBER=s300.member001` |
| 経理操作 | 現行の 5 ロールに専用経理ロールはないため、権限契約を満たす `ADMIN` を `FINANCE_OPERATOR` とする。存在しない `s300.acct01` は使用しない |
| 外部機能 | CloudSign、AI、メール、S13 portal、file storage は M-PASS で固定した mock version と応答 fixture を使用 |
| 識別子 | `RUN_ID=E2E-YYYYMMDD-NNN`。新規 entity の自然キー、メール、備考、外部 operation key に埋め込む |

seed validator は既存 `admin` を含む総アカウント 300 とロール別件数を検証する。現行 fixture の disabled 3 名（`s300.sales07`、`s300.hr05`、`s300.member200`）はログイン拒否が正しい。全 300 アカウントのログイン成功を Entry 条件にしてはならない。

### 1.2 ケース独立性と再現性

1. 各 E2E ID は `E2E-BASE-300` の独立 clone から開始する。シナリオ 2 の生成物をシナリオ 3 が暗黙に利用しない。
2. case fixture は natural key と `RUN_ID` で作成し、解決した DB ID を `fixture-manifest.json` に保存する。固定 ID を推測しない。
3. UI route、API、status、table、外部 callback は候補 build の `interface-contract.json` を正本とする。以下の path は候補 build で route inventory と一致した場合のみ使用する。
4. 失敗注入は QA profile の failpoint または Toxiproxy/mock で行う。本番 profile へテスト用 header や bypass を公開しない。
5. ブラウザ操作の忠実度（クリック・連打・ドラッグ、back/forward、再読込、IME、モバイル/タッチ、network 変調からの復帰）は `ui-real-user-simulation.md` の UI 実操作レイヤーが検証し、本書の business/DB oracle とは証跡と判定を分離する。E2E-07 の「代表 UI trace 1 本」制限は本書の範囲であり、UI レイヤーの instance 展開を制限しない。ランダム入力・操作の探索は `monkey-testing.md` が担う。

### 1.3 必須証跡

全ケースで `evidence/{BUILD_SHA}/{RUN_ID}/{E2E_ID}/` に次を保存する。

- `meta.json`: E2E ID、build SHA、migration/seed/fixture checksum、clock、actor、browser/API/load tool version。
- `steps.json`、主要 screenshot、`network.har`、API request/response、correlation ID 付き application log。
- `db-before.json`、`db-after.json`、ID chain と件数・金額・status の `assertions.json`。`ApiAuditFilter` の frozen 対象（現行は API の POST/PUT/DELETE と監査対象 download GET）は該当 `t_audit_log` も保存し、PATCH 等を無条件に監査済みとみなさない。
- PDF/XML/CSV/FB 等の原本、SHA-256、parser/schema/文字 encoding 検証結果。
- 障害・回復分岐では failpoint 発火証跡、初回失敗、rollback/補償、再試行、重複 0 の全て。

各ケース終了時に case DB を破棄し、`RUN_ID` の mock state、mailbox、object storage、cache、load session を reset する。cleanup log がないケースは未完了とする。

## 2. シナリオとモジュール coverage

| シナリオ | 対象モジュール | 正常 / 拒否 / 回復 ID |
|---|---|---|
| 1. 採用、要員化、営業帰属、歩合 | MOD-02, 03, 14 | `E2E-01-N`, `E2E-01-R`, `E2E-01-REC` |
| 2. CRM、案件、AI、提案、見積・承認・注文、契約、署名 | MOD-04, 05, 06, 15, 07 | `E2E-02-N`, `E2E-02-R`, `E2E-02-REC` |
| 3. 契約、勤怠、検収、締め、請求、消込 | MOD-07, 08, 15, 09 | `E2E-03-N`, `E2E-03-R`, `E2E-03-REC` |
| 4. BP 調達、S10 compliance、S12 capacity、KPI | MOD-10, 07, 14 | `E2E-04-N`, `E2E-04-R`, `E2E-04-REC` |
| 5. S13/S14 portal、JP PINT、会計・FB・文書 | MOD-10, 12, 09, 11, 15 | `E2E-05-N`, `E2E-05-R`, `E2E-05-REC` |
| 6. S17 AI feedback、データスコープ、監査 | MOD-13, 01 | `E2E-06-N`, `E2E-06-R`, `E2E-06-REC` |
| 7. 排他、冪等性、負荷時整合性 | MOD-01, 08 | `E2E-07-N`, `E2E-07-R`, `E2E-07-REC` |

coverage はモジュール名が表に 1 回出るだけでは達成とみなさない。各ケースの mapped requirement、route/action、DB invariant が traceability matrix に登録されて初めて設計 coverage に算入する。

Review 時点の current scope はシナリオ 1、2、3、7 の 12 ID である。シナリオ 4 は `FUTURE_GATE(S10/S12)`、5 は `FUTURE_GATE(S13/S14/S15/S16)`、6 は `FUTURE_GATE(S17)` として 9 ID を `BLOCKED(M-PASS)` に置く。full-plan の 21 ID から削除はしない。

## 3. 7 シナリオ・21 ケース

### 3.1 シナリオ 1: 採用から歩合まで

固定入力は候補者 `E2E-CAND-{RUN_ID}`、メール `e2e-cand-{RUN_ID}@example.invalid`、希望単価 800,000 円、Java skill level 3、歩合 rule `粗利/15.00%`、売価 800,000 円、原価 600,000 円とする。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-01-N` | 正常。`HR` が `/candidate/list` で候補者を登録し、定義順に `入社` まで遷移する。初期値を確認・補完してエンジニアを作成・紐付け、`SALES_A` を主担当にする。新規契約を生成し `/sales-performance?month=2026-07` を照会する。最後に `/payroll` で給与連携 status と従業員一覧を確認する（MOD-16 smoke）。 | 全 API 200。候補者、要員、担当、契約が各画面で同じ名称・単価を表示。歩合は `(800,000-600,000)×15%=30,000` 円。給与一覧に変換した要員が現れる（link 後のみ）。 | `t_candidate.current_stage='入社'` と `converted_engineer_id`、`t_candidate_activity` の順序、`t_engineer` 1、active `t_engineer_sales` 1、`t_contract.sales_user_id=SALES_A`。snapshot table への書込は期待せず、照会値を手計算と比較。給与側は `freee_employee_link` が1件。 | 共通証跡に加え、candidate→engineer→contract ID chain、歩合 calculation JSON、payroll status/employee 表示。 | case DB 破棄、通知/mailbox/cache reset。 |
| `E2E-01-R` | 拒否。`応募受付` から `入社` へ飛び越す操作と、disabled `s300.sales07` の主担当指定を行う。 | 各 API 400。画面に具体的な遷移/担当拒否理由。 | candidate stage 不変、engineer/assignment/contract 新規 0、active primary 数不変。 | 2 subcase の request/response、DB 差分 0、拒否監査。 | case DB 破棄、session reset。 |
| `E2E-01-REC` | 回復。エンジニア作成成功後、候補者紐付け request を network timeout にする。画面を再読込し、同じ自然キーの既存エンジニアを選び同じ operation key で紐付けを再実行する。 | timeout を成功表示しない。再試行は 200 で同じエンジニア詳細へ遷移。 | timeout 時は候補者 link 未更新、作成済みエンジニア 1。回復後は link 1、エンジニア 1、重複 activity 0。各 API の transaction 内に部分更新 0。 | proxy log、timeout screenshot、再試行 response、重複検査。 | case DB 破棄、proxy failpoint reset。 |

### 3.2 シナリオ 2: CRM から署名済み契約まで

固定入力は顧客 `E2E-CUST-{RUN_ID}`、商談 850,000 円、案件 800,000 円・140–180h・Java/Spring、checksum を固定した AI adapter fixture とそこから算出する `AI_SCORE`、見積 850,000 円、CloudSign mock document ID `CS-{RUN_ID}` とする。数値 88% を固定 oracle にしない。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-02-N` | 正常。`SALES_A` が CRM 商談→案件→AI matching→提案→見積を作り、`MANAGER` が全承認段階を順に処理する。注文・明細から契約ドラフトを作成し、CloudSign へ送信、署名 callback を受信する。成約通知はベルで未読が増え、そこからタスクを作成し `/todo` に反映する（MOD-17 smoke）。 | 商談、案件、提案、見積、注文、契約、文書の画面に同一 business chain を表示。最終文書は `SIGNED`。通知→タスクが1件で連動する。 | `t_opportunity→t_project.source_opportunity_id→t_proposal.source_opportunity_id→t_quotation→t_sales_order/line→t_contract→t_contract_document` が 1 本につながる。`t_approval_request/action/participant` の順序・actor が一致し、active contract/document は各 1。通知→`t_task` が業務キーで1件。 | ID chain JSON、AI mock payload、承認 timeline、署名 callback 検証、PDF checksum、通知→タスク trace。 | case DB、CloudSign/mock mailbox、保存 PDF を `RUN_ID` で削除。 |
| `E2E-02-R` | 拒否。`SALES_B` が `SALES_A` の商談 ID を指定して案件/見積を作る。併せて第 1 段階未承認のまま第 2 段階承認を送る。 | scope 違反は 404、承認順序違反は 409。相手の名称・金額を response に含めない。 | project/quotation/order/contract 新規 0。approval status/action 件数不変。 | tampered request、404/409 response、水平権限と状態機械 assertion。 | case DB 破棄、両 actor session reset。 |
| `E2E-02-REC` | 回復。CloudSign create 成功後、応答を timeout にする。外部 operation key を検索して同じ document を回収し、callback を再送する。 | 画面は不明状態を成功扱いせず「照会/再試行」を提示。回復後 `SIGNED`、通知 1 件。 | local contract document 1、`external_doc_id=CS-{RUN_ID}` 1、document version 1、重複署名依頼 0。契約状態は失われない。 | CloudSign request/search/callback log、timeout と回復 UI、件数 assertion。 | case DB、CloudSign mock、object storage reset。 |

### 3.3 シナリオ 3: 勤怠から入金消込まで

固定入力は `TEST_MONTH=2026-07`、有効契約、売価 800,000 円、原価 600,000 円、精算幅 140–180h、確定工数 140h、税率 10%、入金額 880,000 円、入金日 `2026-08-31` とする。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-03-N` | 正常。`MEMBER` が `/my/timesheet` で 140h を提出、`MANAGER` が確定する。検収要契約は検収を提出・承認し、`/monthly-closing` で `2026-07` を締める。`FINANCE_OPERATOR` が請求を 1 件生成し全額消込する。 | 締め後は編集不可。請求 PDF は税抜 800,000、税 80,000、税込 880,000。消込後残高 0、status `入金済`。 | `t_work_record.status='確定'`、必要な `t_acceptance` は承認済、`m_system_config['closing.confirmed-months']` に月 1 回、invoice/header-item-payment の合計一致、同契約・月の有効請求 1。 | 勤怠/検収/締め/請求/消込 response、金額 SQL、PDF text、監査。 | case DB 破棄、生成 PDF/mailbox/cache reset。 |
| `E2E-03-R` | 拒否。未確定 work record と未検収 contract を残して締め・請求を試す。締め後には同月勤怠 update/delete/import も試す。 | 締め/請求は 400 または 409。締め済み update/delete/import は全経路で拒否し、同じ message key を返す。 | readiness 不足時は締め月追加 0、invoice 0。締め済み fixture では work record checksum 不変。 | 各経路 response、closing summary、DB checksum before/after。 | case DB 破棄、upload temp file 削除。 |
| `E2E-03-REC` | 回復。invoice header INSERT 後・item INSERT 前に DB failpoint。解除後に同じ billing key で再実行し、続けて消込する。 | 初回は明確な失敗で invoice を一覧表示しない。再試行後、正常ケースと同じ金額で 1 件だけ表示。 | 初回 header/item/payment 0。再試行後 header 1、item 規定数、payment 1、残高 0。締めと確定実績は保持。 | failpoint/correlation log、rollback SQL、再試行と unique assertion。 | case DB 破棄、failpoint reset。 |

### 3.4 シナリオ 4: BP 調達、S10/S12、KPI

本シナリオ 3 ID は `FUTURE_GATE(S10/S12) / BLOCKED(M-PASS)` である。

固定入力は BP 企業 `E2E-BP-{RUN_ID}`、要員 `E2E-BPW-{RUN_ID}`、稼働可能日 `2026-09-01`、売価 800,000 円、原価 600,000 円、対象期間 `2026-09`〜`2027-02` とする。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-04-N` | 正常。`SALES_A` が review 済み BP 空き情報を取り込み、`MANAGER` が S10 compliance profile/snapshot を確定し、S12 capacity 計算を実行して dashboard/management accounting を照会する。 | BP、compliance、capacity、KPI の as-of と対象月が一致。fixture 契約の粗利 200,000 円、粗利率 25.00%。 | `t_bp_company/t_bp_availability` の source key 一意。候補 build で確定した compliance profile/current snapshot と operation ledger が一致。capacity 出力の母集団 ID と KPI 計算入力が一致。 | import review、snapshot/version、capacity input/output、KPI 手計算。 | case DB、import file、cache を reset。 |
| `E2E-04-R` | 拒否・境界。未 review、期限切れ、必須 compliance 文書不足の BP 要員を capacity/assignment 対象にする。 | 対象外理由を画面表示し、compliance gate は fail-closed。不正データを KPI に含めない。 | assignment/active snapshot 0。KPI 母集団に対象 BP ID なし。NULL 原価を 0 円扱いしない。 | 3 subcase response、finding、母集団 ID diff、監査。 | case DB 破棄、upload/mock reset。 |
| `E2E-04-REC` | 回復。compliance snapshot operation の worker snapshot 保存中に failpoint。解除後に同じ operation ID で再試行する。 | 初回は active pointer を進めず、再試行後に 1 version だけ公開。capacity は active snapshot のみ参照する。 | orphan snapshot 0、operation result 1、current version +1、同一 checksum の二重 active 0。 | operation ledger、snapshot/pointer SQL、retry response。 | case DB 破棄、failpoint/cache reset。 |

### 3.5 シナリオ 5: S13/S14 portal と JP PINT・会計 integration・文書

本シナリオ 3 ID は `FUTURE_GATE(S13/S14/S15/S16) / BLOCKED(M-PASS)` である。既存 BP、請求、文書の部分機能だけを実行して E2E PASS にしない。

固定入力は外部 BP actor `portal.bp01`（M-PASS fixture）、案件 `E2E-PORTAL-PRJ-{RUN_ID}`、`MEMBER` の Java level 3→4、経費 12,345 円、請求 880,000 円、BP 支払 600,000 円とする。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-05-N` | 正常。S13 外部 portal から匿名 skill sheet を閲覧・応募する。`MEMBER` は S14 change request で自分の skill と経費を申請し、承認者が最終承認する。`FINANCE_OPERATOR` は fixture 請求の JP PINT XML を生成し、請求/入金/BP 支払を canonical integration job から freee stub（または承認済み CSV fallback）へ連携し、成果物を文書保管する。 | portal に個人識別情報を出さない。skill は承認後だけ level 4。JP PINT schema 合格。freee 外部 ID と金額・税・日付が source と一致し、文書から原本を再取得可能。全銀 FB は候補 build で feature enabled の場合だけ追加確認する。 | portal application、self change request/approval と engineer skill、expense、JP PINT export log、integration job/outbox と external ID、`t_document/version/link` の business key/checksum が一致。 | anonymization diff、approval/skill PDF、XML validator、stub reconciliation、fallback parser、document checksum。 | case DB、portal/freee stub、receipt/XML/CSV/document file を `RUN_ID` で削除。 |
| `E2E-05-R` | 拒否。外部 BP が非公開案件/実名 skill sheet、`MEMBER` が別要員 profile/receipt、mapping 欠落または未承認 BP 支払の会計連携を要求する。 | 404/403、個人情報漏えい 0、会計送信 0。正常対象を黙って欠落させず行別理由を返す。 | application/change request/正本更新/integration job/document の不正な新規 0。外部 stub call 0、対象外 entity の access success log 0。 | 3 subcase response、PII scan、stub call count、DB 差分 0、security audit。 | case DB、portal/session/stub/storage reset。 |
| `E2E-05-REC` | 回復。会計外部取引作成後の応答 timeout と、文書 file 保存後・DB metadata commit 前の timeout を別 subcase で発生させ、operation key で照会・再実行する。 | 不明状態を成功表示しない。回復後は外部取引と各成果物が各 1、download 可。 | external transaction 1、job 1、orphan DB/file 0、document active version 1、同一入力 checksum 一致。 | stub/storage list、DB/外部/file reconciliation、timeout/retry log、checksum。 | case DB、stub/storage/failpoint reset。 |

### 3.6 シナリオ 6: S17 AI feedback と横断セキュリティ

本シナリオ 3 ID は `FUTURE_GATE(S17) / BLOCKED(M-PASS)` である。S17 部分を削って scope/audit だけを実行して E2E PASS にしない。

固定入力は artifact version `S17-ART-{RUN_ID}`、recommendation run/item 10 件、対応 proposal result（成約 4、見送り 6）、固定匿名 evaluation dataset、scope owner `SALES_A/SALES_B` とする。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-06-N` | 正常。10 recommendation item に proposal outcome event を 2 回ずつ送り、trace/outcome の冪等関連を確認する。`SALES_A/B` は、両者以外との共有関係を持たない排他的 fixture の customer/engineer/project/proposal/contract/invoice を照会し、最後に `ADMIN` が `/audit-log` を確認する。 | outcome timeline は各 1 件。AI が提案/契約状態を変更しない。2 営業は自分の排他的 fixture だけ表示。監査画面は管理者だけが閲覧し、全操作の actor/action/object/correlation ID を表示。 | `trace_id→run→item→proposal→outcome` が 10 本つながり、重複 outcome 0、active artifact version 不変。6 entity の expected owner set と表示が一致。更新 API と監査 row を correlation ID で照合。 | trace/outcome chain、20 event request、2 actor の ownership manifest/list diff、audit correlation join。 | case DB、outcome worker、両 session/cache reset。 |
| `E2E-06-R` | 拒否。`SALES_A` が `SALES_B` の排他的 6 entity detail/API と `/audit-log` を直接指定する。PII comment と、閾値未達 SHADOW artifact の promotion も送る。 | object は存在秘匿 404、audit page/API は 403、PII は mask/reject、promotion は拒否。評価合格だけでも管理者承認なしに自動 promotion しない。 | 業務更新 0、active artifact version 不変。拒否操作の security audit は管理者照会で確認可能。 | 6 entity matrix、404/403 response、mask canary、evaluation threshold、管理者 audit。 | case DB、session/evaluation worker reset。 |
| `E2E-06-REC` | 回復。outcome commit 応答を timeout にし同じ event を再送する。別 subcase で offline evaluation worker を中断・再開する。 | 再送後も outcome は各 1。途中 evaluation を完了表示/promotion 対象にせず、再開後に同じ dataset/artifact/checksum の result 1 へ収束。 | outcome business key ごと 1、evaluation result 1、active artifact version は自動変更されない。 | timeout/retry log、unique SQL、evaluation claim/result、active pointer。 | case DB、worker failpoint reset。 |

### 3.7 シナリオ 7: 排他、冪等性、負荷時整合性

このシナリオの browser は代表 UI trace 1 本に限定する。並行 request は k6、JMeter、Gatling 等の HTTP load runner を使用し、300 個の Playwright/browser process を性能測定の代用にしない。

| E2E ID | 分岐・操作 | 期待 UI/API | DB と不変条件 | 証跡 | cleanup |
|---|---|---|---|---|---|
| `E2E-07-N` | 正常。255 要員中 disabled の `s300.member200` を除く active 254 名から、共有担当を持たない 60 個の独立 fixture/account を割り当てる。60 VU、5 分 ramp、10 分 hold で `2026-07` の読取・保存・提出を実行する。300 は母集団であり concurrent VU は 60。 | 期待 4xx を除く error rate と latency は承認済み性能基準内。代表 UI で提出結果を確認。 | user-record 対応 1:1、他要員更新 0、重複 work record 0、deadlock/lock timeout 0、監査対象 API の actor 誤帰属 0。 | load script SHA、summary、server/JVM/DB metrics、slow query/lock report、代表 HAR。 | case DB 破棄、60 session、load agent、metrics label reset。 |
| `E2E-07-R` | 排他拒否。同じ `t_attendance_month` または M-PASS で指定した versioned resource を同じ version で 2 session から同時更新する。 | 1 request だけ成功し、もう 1 件は 409。後勝ちで上書きせず、画面は再読込を促す。 | version は +1 のみ、business action 1、audit success 1/conflict 1、lost update 0。 | barrier 時刻、2 response、version SQL、thread/correlation log。 | case DB 破棄、2 session reset。 |
| `E2E-07-REC` | 回復。20 個の異なる versioned `t_attendance_month` fixture を更新中、Toxiproxy で DB 接続を 5 秒遮断する。不明応答は対象 GET で現在 status/version を照合し、未反映のものだけ最新 version で安全に再送する。未実装の `Idempotency-Key` header は仮定しない。 | 遮断中は成功を偽装しない。回復後は各 fixture の確定結果を表示し、既に反映済みの遷移を再送しない。回復時間は性能基準内。 | 20 fixture ごとに許可遷移 1 回以下、partial transaction 0、lost update 0、監査対象成功 action 1 回以下、接続 pool/lock が基準時間内に復旧。 | proxy timeline、GET reconciliation/retry result、fixture 別 version SQL、pool/DB telemetry。 | case DB 破棄、proxy、session、metrics label reset。 |

## 4. E2E 完了条件

1. **Current completion** は current 12 ID が重複なく全件 `PASS` で、future 9 ID を理由付き `BLOCKED(M-PASS)` として報告した状態である。**Full-plan completion** は M-PASS 後に 21 `E2E-*` ID が全件 `PASS` した場合だけ宣言する。拒否・回復分岐や future を正常分岐へ合算・削除しない。
2. 全ケースで build/seed/fixture checksum と必須証跡が揃い、第三者が `E2E-BASE-300` から同じ期待値を再現できる。
3. 全 17 モジュールについて requirement→E2E ID→route/action→DB invariant→evidence の trace が 1 件以上あり（MOD-16 給与は E2E-01、MOD-17 タスク/通知は E2E-02 の sub-assertion、MOD-03〜15 はシナリオ 1〜7、MOD-01 は E2E-06/07）、重要な金額・権限・状態遷移は ITa/ITb にも下位ケースを持つ。
4. データ越境、金額/貸借不一致、二重請求・二重支払、署名/文書の orphan、rollback/補償不成立は 1 件でも P0 として試験を停止する。
5. cleanup が全件成功し、残存 case DB、session、mock state、外部 file が 0。
