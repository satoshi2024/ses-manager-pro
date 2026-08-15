# SES Manager Pro モジュール間結合テスト（ITb）実行仕様

本書は、モジュール間の状態遷移、権限制御、トランザクション境界を検証する ITb の実行仕様である。単なる画面導通をケースとして数えず、1 ケースは「固定した前提、1 つの判定可能な期待結果、DB 不変条件、証跡、後始末」を持つものとする。

## 1. ケース数と合格判定

- 連携族は 12、ケースは各族 3 件（正常、拒否または境界、障害時ロールバック）の **計 36 件** とする。
- ケース数の正本は、本書に記載された一意な `ITB-*` ID の機械集計結果である。「400+」など、ID と対応しない概数は使用しない。
- `PASS` は、UI 表示だけでなく HTTP 応答、業務テーブル、監査ログ、不変条件の全てが期待値と一致した場合に限る。`BLOCKED`、`NOT RUN`、証跡欠落は `PASS` に含めない。
- S10〜S17 の未マージ機能は見込みの URL やテーブル名で実行しない。候補 build の Module PASS（後述）で確定したインターフェース契約を正本とする。

## 2. 共通前提、固定データ、証跡

### 2.1 固定テストデータ

| キー | 固定値 |
|---|---|
| `BASELINE` | MySQL 8 に全 Flyway migration と `db/migration-dev/V100__seed_r3_scale_300.sql` を適用した読取専用 snapshot |
| `TEST_MONTH` | `2026-07` |
| `AS_OF` | `2026-08-17T09:00:00+09:00`、タイムゾーン `Asia/Tokyo` |
| `ADMIN` | `s300.admin01` |
| `HR` | `s300.hr01` |
| `SALES_A/B` | `s300.sales01` / `s300.sales02` |
| `MANAGER` | `s300.mgr01` |
| `MEMBER` | `s300.member001`（`s300.eng001` ではない） |
| `FINANCE_OPERATOR` | 専用経理ロールが実装されるまでは、権限契約を満たす `s300.admin01` |
| `RUN_ID` | `ITB-YYYYMMDD-NNN`。新規データのメール、外部キー、備考へ必ず付与 |

`V100` は H2 用データではなく MySQL 向け dev migration である。実行前に seed manifest を生成し、既存 `admin` を含む総アカウント 300、ロール別件数、意図した active/disabled を照合する。現行 seed では `s300.sales07`、`s300.hr05`、`s300.member200` は disabled であり、ログイン成功ではなく拒否が期待値である。

各ケースは `BASELINE` から独立 DB を複製して実行する。ケース間で自動採番 ID や前ケースの更新結果を引き継がない。自然キーと `RUN_ID` から実 ID を解決し、`fixture-manifest.json` に保存する。

### 2.2 Module PASS とインターフェース契約

各連携元・先モジュールについて、次を満たす Module PASS（`M-PASS`）がない限り当該 ITb を開始しない。

1. 候補 build SHA、migration checksum、画面 route、API の method/path/request/response、業務ステータス、更新テーブル、トランザクション境界が `interface-contract.json` に固定されている。
2. 対象モジュールの smoke、権限、主要正常系、主要入力拒否が合格し、未解決 P0/P1 が 0 件である。
3. CloudSign、メール、AI、外部ポータルなどの mock は契約 version、固定応答、失敗応答、reset API を持つ。
4. 障害ケース用 failpoint は QA profile または Toxiproxy 等に限定し、本番 profile では起動不能である。

現行実装の契約では、候補者履歴は `t_candidate_activity`、月次締めは `m_system_config` の `closing.confirmed-months`、利用者 session は `t_user_session` を使用する。また、営業歩合は `t_sales_commission_snapshot` へ保存せず、`t_contract.sales_user_id` と確定実績から都度計算する。候補 build がこれを変更する場合は migration と契約差分の承認を要する。

### 2.3 必須証跡と cleanup

全ケースの証跡を `evidence/{BUILD_SHA}/{RUN_ID}/{CASE_ID}/` に保存する。

- `meta.json`: case ID、build SHA、DB/migration/seed checksum、fixture manifest checksum、実行者、ブラウザまたは API client version、開始・終了時刻。
- `steps.json`、主要画面 screenshot、`network.har` または API request/response、correlation ID 付き application log。
- `db-before.json`、`db-after.json`、対象行数と金額・状態の不変条件を評価した `assertions.json`。
- `ApiAuditFilter` の frozen 対象（現行は API の POST/PUT/DELETE と監査対象 download GET）では `t_audit_log` 抽出結果。PATCH 等を無条件に監査済みとみなさない。ダウンロードを伴う場合は原本、SHA-256、構文検証結果。

ケース終了時はケース DB を破棄し、外部 mock、mailbox、object storage、cache を `RUN_ID` で reset する。cleanup の失敗は環境不具合として記録し、次ケースを開始しない。

## 3. 12 連携族・36 ケース

実行 scope は readiness と分離する。Review 時点では 3.7〜3.10（S12〜S15/S17 を必要とする 4 族、12 ケース）は `FUTURE_GATE` であり、現行 completion 分母は残る 8 族、24 ケースである。3.4 の S10 compliance、3.6 の S16 JP PINT は当該ケース内の追加 instance として `BLOCKED(M-PASS)` に分離する。full-plan 分母は常に 36 であり、future を削除して「36 件完了」と報告しない。

### 3.1 MOD-02 ↔ MOD-03: 候補者からエンジニアへの変換

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-02-03-N01` | 正常 | `HR` が候補者 `ITB-CAND-{RUN_ID}` を順序どおり `応募受付→…→入社` にし、初期値取得後にエンジニアを登録して紐付ける。 | API 200。`t_candidate.current_stage='入社'`、`converted_engineer_id` が新規 `t_engineer.id` を指す。`t_candidate_activity` に遷移が順序どおり存在し、コピー対象項目は契約表と一致する。候補者とエンジニアは各 1 行。 | 候補者・エンジニア詳細 screenshot、3 テーブルの before/after、監査行。 |
| `ITB-02-03-R01` | 拒否 | `応募受付` の候補者を入社へ飛び越させる、または未入社のまま紐付けを試す。 | API 400。画面に業務エラー。`current_stage`、`converted_engineer_id`、`t_engineer` 件数に変化なし。 | 400 response、0 差分 SQL、拒否監査。 |
| `ITB-02-03-F01` | 障害・回復 | エンジニア登録は成功させ、別 HTTP transaction である候補者 link 更新を failpoint で失敗させる。解除後は新たなエンジニアを POST せず、同じ既存 engineer ID を link する。 | 初回はエンジニア 1、`converted_engineer_id=NULL`（link transaction 内の部分更新 0）。再実行後もエンジニア 1、link 1、重複 activity 0。複数 HTTP をまたぐ全 rollback は期待しない。未紐付け要員の検知・補償を要件化する場合は別ケースとする。 | 初回 link rollback SQL、既存 engineer ID を使った再試行 response、件数 assertion、correlation log。 |

### 3.2 MOD-03 ↔ MOD-14: 主担当営業と歩合帰属

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-03-14-N01` | 正常 | `MEMBER` の主担当を `SALES_A` から `SALES_B` へ変更する。既存契約は `sales_user_id=SALES_A`、変更後に新規契約を 1 件生成する。 | `t_engineer_sales` は active primary が常に 1 行。旧行は `primary_flag=0, released_at=NULL` の active 副担当、新行は `primary_flag=1, released_at=NULL`。既存契約売上・歩合は `SALES_A` のまま、新規契約のみ `SALES_B` に帰属する。担当要員数は現主担当に反映する。 | assignment 履歴、2 契約、`/sales-performance?month=2026-07` 応答と計算明細。 |
| `ITB-03-14-R01` | 拒否・境界 | disabled の `s300.sales07` または非営業ユーザーを主担当に指定する。 | API 400。active primary、契約帰属、歩合結果に変化なし。 | 拒否 response、role/status SQL、前後差分 0。 |
| `ITB-03-14-F01` | 障害・回復 | 旧 primary の demote 後・新 assignment 保存前で failpoint。解除後に再試行する。 | 初回 rollback 後は旧 `SALES_A` が primary のまま 1 行。再試行後は旧 `SALES_A` が active 副担当、新 `SALES_B` が active primary で、両行の `released_at=NULL`。primary 欠落や 2 primary を commit しない。 | transaction log、`primary_flag/released_at` と active primary 件数 assertion。 |

### 3.3 MOD-04 ↔ MOD-05 ↔ MOD-06: 商談、案件、提案

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-04-05-06-N01` | 正常 | `SALES_A` が商談 `ITB-OPP-{RUN_ID}`（850,000 円）から案件（800,000 円、140–180h、Java）を作成する。frozen adapter fixture から期待候補・`AI_SCORE` を算出して matching を実行し、その後に明示的な別 request で提案する。 | `t_project.source_opportunity_id` と `t_proposal.source_opportunity_id` が同一商談を指す。提案の `project_id/engineer_id/ai_match_score=AI_SCORE` と UI card が fixture oracle と一致する。固定 88% や存在しない `t_ai_match_score` を期待しない。 | 商談→案件→提案の ID chain、adapter/fixture checksum、AI request/response、oracle、card screenshot。 |
| `ITB-04-05-06-R01` | 拒否 | `SALES_B` が `SALES_A` の非公開商談 ID を指定して案件または提案を作成する。 | 契約で定めた 404（存在秘匿）を返す。3 テーブルに新規行 0、商談情報の response 漏えい 0。 | 404 response、cross-scope SQL、security audit。 |
| `ITB-04-05-06-F01` | 障害・回復 | matching request を AI adapter timeout にし、解除後に同一 fixture で matching を再実行する。成功結果を確認してから提案作成 request を 1 回送る。 | matching と提案作成は別 HTTP transaction である。timeout 中は提案操作自体が未実行で `t_proposal` 差分 0。再実行の matching は fixture oracle と一致し、その後の明示操作で提案 1 行、通知 1 件以下。 | mock log、初回 timeout、proposal 差分 0、再 matching response、提案 request/件数。 |

### 3.4 MOD-06 ↔ MOD-07: 提案成約と契約ドラフト

提案→契約ドラフトは現行 scope、S10 compliance instance は `BLOCKED(M-PASS/S10)` とする。S10 が未受入の間、ドラフト成功だけで compliance PASS と報告しない。

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-06-07-N01` | 正常 | 有効案件・要員・主担当を持つ提案を `成約` に変更する。 | `t_proposal.status='成約'`、`closed_at`、`t_proposal_history` が更新され、`t_contract` は 1 行だけ `準備中` で生成される。単価、案件、要員、顧客、現主担当 `sales_user_id` を引き継ぐ。S10 判定は確定した compliance API を明示的に実行して証跡化する。 | status API、4 テーブル before/after、契約画面、通知。 |
| `ITB-06-07-R01` | 拒否・境界 | project が削除済み、または主担当が disabled の提案を成約させる。 | project 不在は成約全体を 4xx rollback。主担当だけ無効な場合は契約を未帰属 `sales_user_id=NULL` とする現契約に従い、担当設定通知を 1 件発行する。両条件を subcase として別 result 行に残す。 | subcase 別 response、契約件数、通知宛先 assertion。 |
| `ITB-06-07-F01` | 障害・回復 | 提案更新後・契約 INSERT 前で failpoint。解除して同じ提案を再試行する。 | 初回は提案、履歴、契約、通知を全 rollback。再試行後は契約 1 行、履歴 1 行、通知 1 件。 | rollback log、proposal/contract/notification 件数。 |

### 3.5 MOD-07 ↔ MOD-08: 契約、勤怠、承認、月次締め

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-07-08-N01` | 正常 | `TEST_MONTH` に有効な契約で `MEMBER` が勤怠を保存・提出し、`MANAGER` が確定して月次締めする。 | `t_work_record.contract_id` が契約を指し、最終状態は `確定`。締めは `m_system_config['closing.confirmed-months']` に `2026-07` を 1 回だけ保持する。締め後の UI は編集不可。 | 勤怠 grid、承認/締め response、work record と config JSON。 |
| `ITB-07-08-R01` | 拒否・境界 | 契約期間外日付の入力、および未確定実績を残した状態の締めを試す。 | 各操作は 400。期間外行を作らず、`2026-07` を締め済みにしない。拒否理由は項目単位に表示する。 | 2 subcase の response、0 差分 SQL、summary screenshot。 |
| `ITB-07-08-F01` | 障害・回復 | 締め config 更新と月次 accounting snapshot 生成の間で failpoint。 | 初回は config と生成済み source 行を同一 transaction で rollback。再試行後に締め月 1 要素、fixture の source key 集合と snapshot source key 集合が完全一致し、各 unique key は 1 行。snapshot を単一 row/version と仮定しない。 | config/snapshot source-key set の before-after、unique assertion、transaction log。 |

### 3.6 MOD-08 ↔ MOD-09: 確定実績、請求、入金、JP PINT

請求・入金は現行 scope、JP PINT 検証は `BLOCKED(M-PASS/S16)` の追加 instance とする。S16 未実装時は XML assertion を現行 instance の FAIL にせず、future 分母に残す。

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-08-09-N01` | 正常 | `2026-07`、売価 800,000 円、精算幅 140–180h、確定 140h、税率 10% の fixture から請求を生成し 880,000 円を消込する。M-PASS 後だけ同じ請求から JP PINT を生成する。 | 現行 instance は `t_invoice` 税抜 800,000、税 80,000、税込 880,000、明細合計一致、入金後 status `入金済`、残高 0。S16 instance は確定 schema/code list に合格し、export log と原本 checksum が一致する。 | 金額 assertion、PDF text、支払行、各 SHA-256。S16 instance のみ XML validation/export log。 |
| `ITB-08-09-R01` | 境界 | 139h59m、140h00m、180h00m、180h01m を独立 fixture で生成する。 | 下限・上限ちょうどでは控除/超過 0。1 分外側だけ契約の丸め規約で調整される。期待額は事前計算 fixture と 1 円単位で一致し、浮動小数誤差 0。 | 4 subcase の計算表、invoice item SQL、PDF 抽出値。 |
| `ITB-08-09-F01` | 障害・回復 | invoice header INSERT 後・item INSERT 前に failpoint。解除して同じ billing key で再実行する。 | 初回は header/item/export log 全 rollback。再試行後は同一契約・対象月の有効請求 1、明細一式 1 組。 | unique key assertion、rollback/retry log。 |

### 3.7 MOD-09 ↔ MOD-11: 請求・BP 支払と会計/支払 integration

本族は `FUTURE_GATE(S15) / BLOCKED(M-PASS)` である。現行 freee 実装は OAuth/給与連携であり、下記の会計 canonical job/outbox が実装済みであるとは扱わない。

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-09-11-N01` | 正常 | `ITB-08-09-N01` と同額の請求・入金と、承認済 BP 支払 fixture から canonical integration job/outbox を作り、freee stub へ送信する。feature flag が CSV fallback の場合は同じ canonical payload から出力する。 | freee を会計正として system 内に独自総勘定元帳を作らない。source type/id、idempotency key、payload hash が一意で、外部 ID、金額、税、取引日が source と一致する。成功確認後だけ job/payment を連携済みにする。全銀 FB は候補 build で feature enabled の場合だけ追加 subcase とし、必須成果物にしない。 | job/outbox、stub request/response、外部 ID reconciliation、fallback CSV parser。FB 有効時のみ byte/record 検査。 |
| `ITB-09-11-R01` | 拒否・境界 | account/tax mapping 欠落、freee plan/API 403、未承認または銀行口座未確認 BP 支払、既連携 source を送る。 | mapping 欠落・403 は理由付き FAILED/要対応で source 金額を変更しない。未承認支払は送信 0。重複 source は同じ idempotency result を返し、外部取引を増やさない。 | 4 subcase の job result、stub call count、source/外部取引件数、監査。 |
| `ITB-09-11-F01` | 障害・回復 | 外部送信時に timeout、429、500 を順に注入し、lease/claim 期限後に backoff retry する。 | claim 中の job を二重 worker が送らない。外部が作成済みで応答 timeout の場合は idempotency key で照会し、external transaction 1 件へ収束する。retry exhaustion 後は DLQ/手動再開可能で、支払を誤って paid にしない。 | worker claim/lease、3 failure response、retry timeline、stub idempotency call、重複 0 assertion。 |

### 3.8 MOD-10 ↔ MOD-14: BP 空き情報、キャパシティ、KPI

本族は S12 capacity を必要とするため `FUTURE_GATE(S12) / BLOCKED(M-PASS)` である。既存 BP 一覧と dashboard の存在だけで本族を PASS にしない。

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-10-14-N01` | 正常 | `2026-09-01` 稼働可の BP 要員を原価 600,000 円で確定し、6 か月 capacity 計算を実行する。 | `t_bp_availability` の review 済み行だけが母集団に入り、売価 800,000 円 fixture の粗利は 200,000 円、粗利率 25.00%。ダッシュボードと管理会計 API の as-of/month が一致する。 | 入力行、集計 API、KPI screenshot、手計算 assertion。 |
| `ITB-10-14-R01` | 境界 | review 未済、期限切れ、原価 NULL、母集団 0 の各入力を計算する。 | 未確定行は除外し、0 除算せず `0` または `N/A` を画面契約どおり返す。NULL を 0 円として利益を水増ししない。 | 4 subcase 集計 response、母集団 ID 一覧。 |
| `ITB-10-14-F01` | 障害・回復 | capacity 計算中に cache 更新失敗を発生させる。 | 永続結果と cache version が不一致のまま配信されない。再試行後に同一 source checksum から 1 version を公開する。 | cache key/version、DB version、再描画時刻。 |

### 3.9 MOD-12 ↔ MOD-03: 要員セルフサービス申請とスキル正本

本族は `FUTURE_GATE(S14) / BLOCKED(M-PASS)` である。

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-12-03-N01` | 正常 | `MEMBER` が自分の Java skill level 3→4 と資格追加を change request として申請し、M-PASS で定めた管理者/承認者が最終承認する。 | 申請中は正本 `t_engineer_skill` を変更しない。最終承認 transaction で Engineer/Skill/Career service が正本を level 4 にし、target version と approval action を保存する。営業詳細と新規 skill sheet は承認後だけ level 4。 | 申請/承認画面、request/target version、skill before/after、PDF text/checksum。 |
| `ITB-12-03-R01` | 拒否 | `MEMBER` が別要員 ID を URL/body に指定して申請し、別要員の receipt/file key を参照する。 | 契約で定めた 404、申請・正本更新 0、他要員の氏名・スキル・receipt 漏えい 0。file download も同じ ACL で拒否する。 | tampered request、404 response、水平権限/receipt ACL、監査。 |
| `ITB-12-03-F01` | 競合・回復 | 申請後、最終承認前に管理側が同じ skill の target version を更新する。旧 version の申請を承認し、その後最新 version から再申請する。 | 旧申請の最終承認は 409、正本を静かに上書きしない。再申請は新 target version を保持し、承認後に 1 回だけ反映する。approval action と正本更新は同一 transaction。 | 競合 response、target version 履歴、再申請/承認、lost update 0 assertion。 |

### 3.10 MOD-06 ↔ MOD-13: 推薦 trace、feedback/outcome、offline evaluation

本族は `FUTURE_GATE(S17) / BLOCKED(M-PASS)` である。

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-06-13-N01` | 正常 | artifact version を固定した recommendation run/item 10 件から提案を作り、成約 4、見送り 6 の state event を outcome handler へ 2 回ずつ送る。 | `trace_id→run→item→proposal→outcome` が切れず、`UNIQUE(item_id,outcome_type,source_type,source_id)` により outcome は各 1 件。feedback 未判断を却下扱いしない。outcome は相関であり AI の功績と断定しない。active artifact version は変化しない。 | trace chain、20 event request、10 outcome、artifact version before/after。 |
| `ITB-06-13-R01` | 拒否・境界 | item に紐付かない outcome、PII を含む feedback comment、固定匿名 dataset で閾値未達の SHADOW version promotion を送る。 | orphan outcome は 400、comment は mask/reject、閾値未達 promotion は拒否。評価合格だけでも自動 promotion せず、管理者承認なしでは active version 不変。 | 3 subcase response、mask canary、evaluation metric/threshold、active pointer。 |
| `ITB-06-13-F01` | 障害・回復 | outcome 永続化の commit 応答を timeout にし、同じ state event を再送する。別 subcase で offline evaluation worker を中断・再開する。 | 再送後も feedback/outcome は business key ごとに 1 件。evaluation は同一 dataset/artifact/checksum に 1 result へ収束し、途中 result を promotion 対象にしない。active version は自動変更されない。 | timeout/retry log、unique key SQL、evaluation claim/result、active pointer。 |

### 3.11 MOD-15 ↔ MOD-07 ↔ MOD-09: 承認、見積、注文、契約、検収、請求、文書

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-15-07-09-N01` | 正常 | 850,000 円見積を申請・全段承認し、注文と明細、契約を生成する。`2026-07` 実績を検収して請求し、成果物を文書保管する。 | `t_quotation→t_sales_order/t_sales_order_line→t_contract→t_work_record→t_acceptance→t_invoice` の source chain が切れない。`t_approval_request/action/participant` は承認者・順序を保持し、`t_document/version/link` は原本 checksum と業務 ID を保持する。 | 全 ID chain、承認 timeline、PDF/文書 checksum、請求金額。 |
| `ITB-15-07-09-R01` | 拒否 | 第 1 段階未承認で第 2 段階承認、未検収で請求、別組織文書の閲覧を試す。 | 順序違反 409、未検収請求 400/409、他組織文書 404。対象 status、請求件数、access log に不正な成功記録なし。 | 3 subcase response、状態機械 assertion、security audit。 |
| `ITB-15-07-09-F01` | 障害・回復 | 最終承認 action 保存後・target status 更新前、および文書 metadata 保存後・file 保存前で failpoint。 | 承認と target は同一結果へ収束し、二重 action 0。文書は orphan DB/file 0。再試行後に有効 version 1。 | 2 failpoint の rollback/補償 log、orphan scan。 |

### 3.12 MOD-01 ↔ ALL: 認証、メニュー権限、データスコープ、session、監査

| ID | 分岐 | 前提・操作 | 期待 UI/API・DB・不変条件 | 個別証跡 |
|---|---|---|---|---|
| `ITB-01-ALL-N01` | 正常 | 5 ロール×全 route/action inventory を生成テストする。DataScope は 25 営業それぞれに、他の担当・副担当・組織共有を一切持たない排他的 customer/engineer/project/proposal/contract/invoice fixture を 1 組ずつ作り、25 actor×25 owner を検査する。既存 V100 行は実際の関連から expected set を独立計算する。UI は各ロール代表 1 名で menu を確認する。 | route/action 期待表の許可は成功し、非許可は非表示か拒否。排他的 fixture は対角 25 組だけ許可し、非対角 600 組×6 entity は list 件数 0、detail は契約どおり 404。既存 seed は副担当・複数 owner を含む expected set と一致する。管理者 bypass は維持。 | fixture ownership manifest、生成 matrix CSV、代表 screenshot、API result、scope SQL。 |
| `ITB-01-ALL-R01` | 拒否 | disabled 3 アカウントの login、CSRF 欠落更新、営業による `/audit-log` と他 owner object、要員による管理 API を試す。 | login 拒否、CSRF 403、権限外 page/API 403 または存在秘匿 404 を route 契約どおり返す。DB 更新 0。監査ログは管理者だけが照会できる。 | login/403/404 response、menu screenshot、DB 差分 0、監査。 |
| `ITB-01-ALL-F01` | 障害・回復 | (a) 通常の role-menu 更新で `ApiAuditFilter` の監査保存を失敗させる。(b) break-glass 操作で required audit を失敗させる。(c) role-menu 更新前から存在する営業 session で次 request を送る。 | 現契約どおり、(a) 業務更新は commit し warn/監視 alert を残す。(b) required audit 失敗は 503 かつ break-glass 業務を実行しない。(c) session 自体は active のままだが menu cache invalidation 後の次 request は新権限で即時拒否する。一般監査失敗を全 API の fail-closed と誤記しない。 | 3 subcase の role-menu/audit/session before-after、warn/alert、break-glass 503、旧 cookie response。 |

## 4. ITb 完了条件

1. **Current completion** は current 24 ID が重複なく全て `PASS` で、future 12 ID と S10/S16 instance を件数・理由付き `BLOCKED(M-PASS)` として報告した状態である。**Full-plan completion** は M-PASS 後に全 `ITB-*` 36 ID と全追加 instance が `PASS` した場合だけ宣言する。36 未満、future の隠蔽、ID 重複、証跡欠落があれば full-plan 未完了。
2. 各ケースの cleanup が成功し、orphan file、未 reset mock、残存 case DB が 0。
3. P0/P1 が 0。データ越境、金額不一致、二重計上、rollback 不成立は 1 件でも即時停止条件とする。
4. 修正後は、当該ケースだけでなく同じ連携族 3 件、上流・下流の関連連携族、該当 E2E を再実行する。
