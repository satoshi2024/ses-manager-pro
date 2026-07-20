# モジュール間連携バグ監査（第4回 / X-01〜X-13）

- 監査日: 2026-07-20
- 対象: `main@dd24526`（クリーンなワークツリー）
- 対象範囲: 画面間ナビゲーション契約、フロント⇔API契約、モジュール境界（月次締め⇔勤怠⇔請求、単価改定⇔精算、通知⇔各画面、権限UI⇔API）、レイアウトフラグメント契約
- 検証方法: 静的照合＋**ローカル実機検証**（MySQL上でアプリを起動し、admin ログイン済みセッションで対象ページのHTTPステータス・レンダリングHTMLを確認。検証後アプリは停止済み）
- 既出指摘（2026-07-19監査 LOGIC/UI、R-01〜R-17、N-01〜N-09）は対象外。本書はすべて**新規指摘**。
- 修正は行っていない（指摘のみ）。

## 優先度定義

- **P1**: 機能・ページ全体が動作しない、環境が起動しない。
- **P2**: 特定操作でデータ誤り・誤誘導・操作不能が発生する。
- **P3**: 表記・整合性・保守性の問題。計画的に是正。

---

## X-01 [P1] 営業成績ページのJSが一切読み込まれず、画面全体が機能しない

- 対象ファイル／行:
  - `src/main/resources/templates/sales-performance/list.html:60` — `<th:block layout:fragment="scripts">`
  - `src/main/resources/templates/layout/base.html:117` — レイアウト側のスロットは `layout:fragment="page-js"` のみ
  - `src/main/resources/static/js/sales-performance.js:17,30` — `axios.get(...)` を使用（axiosはどのページでも読み込まれていない）
- 証拠（実機確認済み）: ログイン済みセッションで `GET /sales-performance` は HTTP 200 だが、**レンダリング済みHTMLに `sales-performance.js` の script タグも `msgRuleNote` 定数も存在しない**（grep 0件）。Thymeleaf Layout Dialect は、レイアウト側に同名の `layout:fragment` が無い子フラグメントを破棄するため、`scripts` フラグメントは決して出力されない。
- 影響: 対象月フィルタ・成績表・コミッション規則注記のすべてが動作せず、静的プレースホルダー「データがありません」が出続ける。**営業成績（engineer-sales-commission spec）の主要画面が機能していない。** さらにフラグメント名を直しても、`axios` が未ロードのため `ReferenceError` で即死する二重の障害がある。
- 対応方法:
  1. `list.html:60` の `layout:fragment="scripts"` を `layout:fragment="page-js"` へ変更する（他ページと同一規約）。
  2. `sales-performance.js` の `axios.get` を、リポジトリ規約に合わせて `$.ajax` または `SES.api.get` へ書き換える（axios導入はしない。`loadCommissionRule` / `loadPerformance` の2箇所。`res.data.data` の解包は `SES.api.get` なら不要になる点に注意）。
  3. `commission-rule` の `rule.baseType`（粗利/売上）は生のDB値表示になっているため、`SES.i18n.e(...)` 相当の翻訳を併せて検討（任意）。
- 期待結果: /sales-performance で対象月の成績が表示され、月変更で再取得される。
- 追加すべきテスト: **全ページ共通の「page-js インクルード検査」**を追加する（`@SpringBootTest`＋MockMvc で各ページをレンダリングし、`templates/<area>` に対応する `modules/*.js` の script タグがHTMLに含まれることをアサート）。これは X-02・将来の同種ミスの恒久ガードになる。

## X-02 [P1] AIマッチング画面のJSが読み込まれず、チャットが「入力中...」のまま停止する

- 対象ファイル／行:
  - `src/main/resources/templates/ai/matching.html:156-158` — `layout:fragment="scripts"`（X-01と同一原因）
  - `src/main/resources/templates/ai/matching.html:124` — `<button class="btn btn-primary btn-sm px-4" id="saveSettingsBtn">th:text="#{common.btn.saveSettings}">設定を保存</button>` — **`th:text` 属性がタグの外（要素本文）に書かれている**
  - `src/main/resources/static/js/modules/ai.js` — `?engineerId=` クエリを読む処理が無い（`#contextEngineer` セレクトのみ参照）
  - `src/main/resources/static/js/modules/analytics.js:226` — Bench一覧から `/ai/matching?engineerId=N` へ遷移させている
- 証拠（実機確認済み）: `GET /ai/matching` は HTTP 200 だが `modules/ai.js` の script タグが出力HTMLに存在しない。また、レンダリング済みHTMLに文字列 `th:text="#{common.btn.saveSettings}"` が**そのまま本文として出力**されている。
- 影響: ウェルカムメッセージがタイピングアニメーションのまま止まり、送信ボタン・設定保存・コンテキスト選択の全操作が無反応。設定パネルのボタンには生の `th:text=...` テキストが表示される。Bench一覧の「AIマッチング」ボタンの遷移先も実質死んでいる。
- 対応方法:
  1. `matching.html:156` を `layout:fragment="page-js"` へ変更する。
  2. `matching.html:124` を `<button class="btn btn-primary btn-sm px-4" id="saveSettingsBtn" th:text="#{common.btn.saveSettings}">設定を保存</button>` へ修正する。
  3. ai.js の初期化で `new URLSearchParams(location.search).get('engineerId')` を読み、要員セレクトのロード後に該当IDを選択状態にする（analytics.js:226 のナビゲーション契約を成立させる）。
  4. `matching.html:2` の `data-bs-theme="dark"` ハードコードは利用者のテーマ設定より優先されてしまうため削除する（base.html＋FOUC防止スクリプトに委ねる）。
- 期待結果: AI画面でウェルカム表示→入力→送信が機能し、Bench一覧から遷移した場合は対象要員が選択済みになる。
- 追加すべきテスト: X-01のページ共通テストに `/ai/matching` を含める。`th:text` 位置ミスは「レンダリングHTMLに `th:text=` という文字列が含まれない」ことの全ページ共通アサーションで検出できる。

## X-03 [P1] 稼働率カレンダーページが HTTP 500（存在しないフラグメント署名を参照）＋どこからもリンクされていない

- 対象ファイル／行:
  - `src/main/resources/templates/analytics/availability-calendar.html:3` — `th:replace="~{layout/base :: head('稼働率カレンダー', ~{::link}, ~{::style})}"`
  - 同 `:21` — `th:replace="~{layout/base :: body(~{::div}, ~{::script})}"`
  - `src/main/resources/templates/layout/base.html` — `th:fragment` 宣言は**一切無い**（Layout Dialect の decorate 型テンプレート）
  - `src/main/java/com/ses/controller/page/AnalyticsPageController.java:16-19` — `/analytics/availability-calendar` ルートは存在
- 証拠（実機確認済み）: ログイン済みセッションで `GET /analytics/availability-calendar` は **HTTP 500**（統一エラーページ表示）。また `templates/**` を横断検索してもこのページへのリンク・サイドバー項目が存在せず、孤児ページになっている（テストも0件）。
- 影響: engineer-availability-visualization spec の主要UI（タイムライン可視化）が完全に利用不能。API 側 `/api/analytics/availability-timeline` は実装済みなのに、届く画面が無い。
- 対応方法:
  1. テンプレートを本リポジトリの規約へ全面書き換える: `layout:decorate="~{layout/base}"`、コンテンツは `layout:fragment="content"`、CSSは `layout:fragment="page-css"`、JSは `layout:fragment="page-js"`。SB-Admin由来のクラス（`text-gray-800`/`font-weight-bold`）と `.filter-section{background:#fff}` のハードコード白背景はダークテーマ変数へ置換する。
  2. base.html が既に Chart.js を読むため、テンプレート内の重複 `chart.js` 読み込みを削除し、`chartjs-adapter-date-fns` のみ `page-js` で追加する。未使用の flatpickr CSS は削除する。
  3. `analytics/index.html` のヘッダー（または sidebar の analytics 配下）へ導線リンクを追加する（`analytics` メニューの `path_prefix` 配下のため権限配線は不要）。
  4. スキル・営業担当セレクトが「JSで投入」とコメントされたまま空実装である点も、`/api/skill-tags`・`/api/engineers/sales-user-options` で埋める。
- 期待結果: ページが200で描画され、タイムラインが表示され、メニューから到達できる。
- 追加すべきテスト: `MobileResponsiveLayoutTest.ALL_PAGES`（または X-01 の新テスト）へ `/analytics/availability-calendar` を追加し、200であることをアサートする（現状の500はこのテストが無かったため見逃された）。

## X-04 [P1] Flyway validate 有効化により、既存ローカルDBでアプリが起動不能（実際に発生）

- 対象ファイル／行:
  - `src/main/resources/application.yml` — `flyway.validate-on-migrate: false` が削除された（round3対応 N-08-2 の仕様判断）
  - `src/main/resources/db/migration/V37__*.sql` — 過去に適用後、内容が編集された履歴がある
- 証拠（実機確認済み）: 本監査でアプリを起動したところ、`FlywayValidateException: Migration checksum mismatch for migration version 37 (Applied: 1002349388 / Resolved: 1880191174)` で**起動失敗**した。CLAUDE.md 記載の `flyway repair` 実行後は正常起動した。
- 影響: 編集前の V37（や V15）を適用済みの**すべての既存環境**（開発者ローカル・検証環境）が、pull 後にアプリを起動できなくなる。新規参加者は影響なし（空DBから）。
- 対応方法（いずれか、推奨順）:
  1. README / CLAUDE.md の起動手順に「既存DBで checksum mismatch が出た場合は `mvn org.flywaydb:flyway-maven-plugin:10.10.0:repair ...` を実行」という**移行手順として明記**し、チームに周知する（CLAUDE.mdには既に記載があるが、起動失敗メッセージから辿れる場所に置く）。
  2. もしくは適用済み環境向けの補正をリポジトリ側で完結させる（例: 編集済みマイグレーションのchecksumを既知値として `flyway.ignore-migration-patterns` で許容するのではなく、以後「適用済みマイグレーションは編集しない」規約をCLAUDE.mdの禁止事項として明文化し、必要な変更は新規Vxxで行う）。
- 期待結果: pull 直後の `mvn spring-boot:run` が、新規DB・既存DBのどちらでも documented な手順内で起動する。
- 追加すべきテスト: `FlywayMigrationSmokeTest` は空DB起点のみ検証している。「旧checksumで適用済み→現行ファイルでvalidate」という移行シナリオは自動化が難しいため、少なくとも運用ドキュメントの受け入れ確認をタスク化する。

## X-05 [P2] 月次締めのドリルダウンリンクが渡すクエリパラメータを、勤怠・請求画面が無視する

- 対象ファイル／行:
  - リンク生成側: `src/main/resources/static/js/modules/monthly-closing.js:86,89`（`/work-record?month=`）、`:103`（`/invoice?month=&customerId=`）、`:112`（`/invoice?tab=bp-payment&month=`）、`:115`（`/invoice?invoiceId=`）
  - 受け側: `src/main/resources/static/js/modules/work-record.js:1-6` — `$(document).ready` で常に**現在月**をセット（`location.search` を読まない）
  - 受け側: `src/main/resources/static/js/modules/invoice.js:1-37,516-569` — 両方の `DOMContentLoaded` に `URLSearchParams` 参照が無い。`tab=bp-payment` のタブ切替も、`invoiceId` のハイライトも無い
- 発生条件・影響: 前月の締め作業中に「未入力の修正画面」リンクを押すと、勤怠グリッドは**今月**を表示する。締め対象月と違う月に工数を入力してしまう実害リスクがある。請求側も、顧客・月・対象請求書のコンテキストが全て失われ、利用者が再検索を強いられる。
- 対応方法:
  1. work-record.js: 初期化で `const m = new URLSearchParams(location.search).get('month'); $('#workMonth').val(m || currentMonth);` としてから `loadWorkRecords()`。
  2. invoice.js: 初期化で `month`→`#billingMonth`、`tab=bp-payment`→`new bootstrap.Tab(document.getElementById('bp-payment-tab')).show()`＋`#bpWorkMonth` へ month を設定して `loadBpPayments()`、`invoiceId`→ロード後に該当行へ `scrollIntoView`＋ハイライト（該当がなければ無視）。
  3. `customerId` は請求書生成モーダルの顧客初期値（X-06/M-05のセレクト化とセットで）に使う。
- 期待結果: 締め画面の各リンクが「その月・その顧客・その請求書」の文脈を保ったまま遷移する。
- 追加すべきテスト: JSDOMまたはブラウザテストで、`/work-record?month=2026-06` を開くとグリッドAPIへ `month=2026-06` が発行されることを検証。

## X-06 [P2] ダッシュボードKPIカードの `/engineer/list?status=...` リンクを要員一覧が無視する

- 対象ファイル／行:
  - リンク側: `src/main/resources/templates/dashboard/index.html:66`（`?status=Bench`）、`:159`（`?status=退場予定`）
  - 受け側: `src/main/resources/static/js/modules/engineer.js:8-31` — 初期化は `prefillCandidateId` 系のみ読み、`status` を読まない（検索は `#searchStatus` の画面値のみ）
- 影響: 「現在のBench数 → リストを見る」を押しても全要員一覧が表示され、KPIの数字と画面の件数が一致しない（Bench=DB上の正規列挙値のため、パラメータ値自体は正しい）。
- 対応方法: engineer.js の `$(document).ready` で `const st = new URLSearchParams(location.search).get('status'); if (st) $('#searchStatus').val(st);` を `loadEngineers()` 呼び出し前に行う。`#searchStatus` に存在しない値は無視されるためガード不要。
- 期待結果: KPIカードから遷移すると該当ステータスで絞り込まれた一覧が表示される。
- 追加すべきテスト: ブラウザテストで `?status=Bench` 遷移後の一覧APIリクエストに `status=Bench` が含まれること。

## X-07 [P2] 単価改定が既存の未確定勤怠の金額を再計算せず、旧単価のまま確定・請求へ流れる

- 対象ファイル／行:
  - `src/main/java/com/ses/service/impl/ContractServiceImpl.java:353-433` — `revisePrice` は履歴upsert＋`t_contract`現在単価の更新＋「確定済み」実績がある場合の警告のみ
  - `src/main/java/com/ses/service/impl/WorkRecordServiceImpl.java:206-230` — `confirmMonth` はステータスを `確定` へ更新するだけで金額を再計算しない（`approve`:514-538 も同様）
  - `src/main/java/com/ses/service/impl/InvoiceServiceImpl.java:91-92,120` — 請求は `work_record.billing_amount` の保存値をそのまま合算
- 発生条件・影響: (1) 7月分の工数を入力（旧単価で `billing_amount` 算出）→ (2) 適用開始月=7月で単価改定 → (3) 月次確定/承認 → (4) 請求書生成、の順に操作すると、**改定後単価が請求へ一切反映されない**。`revisePrice` の警告は「確定済み」実績のみ対象のため、この経路（未確定実績あり）では警告すら出ない。単価改定モジュールと勤怠・請求モジュールの境界不整合。
- 対応方法:
  1. `revisePrice` のトランザクション内で、`work_month >= applyFromMonth` かつ `status IN ('入力中','差戻し')` の `WorkRecord` を取得し、`ContractPriceResolver` で解決した単価により `billing_amount`/`payment_amount` を再計算・更新する（`SettlementCalculator.calc` を `saveHoursInternal` と同条件で適用。請求済みガードは既存の `selectActiveInvoiceNosByWorkRecordIds` を流用）。
  2. `提出済` はセルフサービス勤怠の承認待ちのため、金額のみ再計算して良いか仕様判断する（推奨: 金額列のみ更新し、ステータスは変更しない）。
  3. 確定済み（警告対象）は現行どおり手動 reopen→再入力の運用とし、警告文へ「未確定分は自動再計算済み」の説明を加える。
- 期待結果: 改定登録後、当月以降の未確定実績の請求額・支払額が新単価で表示され、そのまま確定・請求しても金額が一致する。
- 追加すべきテスト: 「工数入力→当月適用の改定→confirmMonth→請求生成」で請求小計が新単価ベースであることのサービス統合テスト。改定前に請求済みの月が変更されないことも併せて検証。

## X-08 [P2] 権限タブの既定選択が「管理者」のままで、保存が必ず403になる

- 対象ファイル／行:
  - `src/main/resources/templates/user/list.html:99-104` — ロールセレクトの先頭（既定選択）が `管理者`
  - `src/main/java/com/ses/controller/api/RoleMenuApiController.java:52` — 管理者ロールの置換は `403 error.roleMenu.adminUnchangeable`（R-15対応で追加）
  - `src/main/resources/static/js/modules/user.js:270-309` — 管理者選択時もスイッチ編集・保存ボタンが有効なまま
- 影響: 権限タブを開いた直後の状態（管理者選択）でスイッチを操作して保存すると必ずエラーになる。サーバー仕様（管理者は常時全メニュー・変更不可）がUIに表現されておらず、R-15修正で**UI⇔APIの不整合が新たに生じた**。
- 対応方法:
  1. `#permissionRole` の既定選択を `営業` にする（管理者optionは残す）。
  2. user.js の `loadRoleMenus`/`renderRoleMenuCheckboxes` で、管理者選択時は全スイッチを `checked`＋`disabled` にし、保存ボタンを無効化、注記「管理者は常に全メニューへアクセスできます（変更不可）」を表示する（i18nキー新設、4ロケール）。
- 期待結果: 権限タブの表示と実効権限・API仕様が全ロールで一致し、無効操作がUI上でできない。
- 追加すべきテスト: DOMテストで管理者選択時に保存ボタンが disabled であること。

## X-09 [P2] ガントチャートが空データ時にモック契約を実データのように表示する（ほか終了日補完・件数上限）

- 対象ファイル／行: `src/main/resources/static/js/modules/contract-gantt.js:17`（`list = getMockData()`）、`:30-31`（`startDate||'2026-04-01'`, `endDate||'2026-09-30'`）、`:6`（`/api/contracts` を無指定sizeで取得 → 既定100件）、`:54`（`language:'ja'` 固定）、`:98-104`（`getMockData` 本体）
- 影響: 契約0件の環境で「田中 太郎 @ メガバンク」等の**架空の契約バー**が表示され、実データと誤認される（`tasks.length===0` の空状態表示は到達不能なデッドコード）。終了日未設定（継続中）の契約は固定日 2026-09-30 で終わるように見え、101件目以降の契約は黙って消える。
- 対応方法:
  1. `getMockData()` と17行目のフォールバックを削除し、0件時は既存の空状態メッセージ分岐へ落とす。
  2. 終了日NULLは「表示範囲の末尾まで伸ばす」規約にする（例: `endDate || SES.util.getLocalDateString(表示末尾日)`、ツールチップには「継続中」と表示。availability-calendar.js:84-97 と同じ規約）。開始日NULLはバー非表示＋警告が安全。
  3. 取得を `/api/contracts?size=1000`（他画面と同一パターン）にするか、表示対象（準備中/稼動中）に絞るクエリを付ける。
  4. `language` は `SES.i18n.lang` から渡す。
- 期待結果: 実契約のみが表示され、0件時は空状態、継続中契約は表示期間いっぱいのバーになる。
- 追加すべきテスト: 契約0件でモック名がDOMに現れないこと。終了日NULL契約のバー終端が固定日でないこと。

## X-10 [P2] メールテンプレート一覧がAPI失敗時にモックデータを表示する

- 対象ファイル／行: `src/main/resources/static/js/modules/email-template.js:20,25`（失敗時 `renderTemplates(getMockData())`）、`:159`（`getMockData` 本体）
- 影響: API エラー（権限・一時障害）時に架空のテンプレートが並び、実在すると誤認して提案メール送信画面（proposal-kanban.js の `openMailModal`）との不整合を起こす。編集・削除しようとすると存在しないIDへのリクエストになる。
- 対応方法: 両フォールバックを削除し、`renderTemplates([])` 相当の空状態＋`Toast.error`（既存キー `common.msg.fetchFail`）へ置き換える。`getMockData()` を削除する。
- 期待結果: 失敗時はエラー表示のみで、架空データが画面に出ない。
- 追加すべきテスト: APIモックを500にした場合にモックテンプレート名がDOMへ現れないこと。

## X-11 [P3] 通知タイプの増加に ToDo 画面のフィルタ・アイコン定義が追随していない

- 対象ファイル／行:
  - `src/main/resources/templates/todo/list.html:23-31` — フィルタは旧6種（CONTRACT_END/PROPOSAL_STALE/BENCH_LONG/PROJECT_URGENT/RETIRING_ENGINEER/AI_MATCHING）のみ
  - `src/main/resources/static/js/modules/todo.js:41-48`・`src/main/resources/static/js/common.js:331-340` — iconColorMap も新タイプ未定義
  - 発行側（バックエンド）は現在 12種: TIMESHEET_SUBMITTED / TIMESHEET_REJECTED / INVOICE_OVERDUE / MAIL_FAILED / FOLLOW_UP / CONTRACT_RENEWAL_DRAFT / CONTRACT_DRAFT / BP_AMOUNT_MISMATCH / CONTRACT_END / PROPOSAL_STALE / PROJECT_URGENT / BENCH_LONG
- 影響: 勤怠提出・督促失敗・BP金額不一致などの新しめの通知を種別で絞り込めない。逆に現在発行されない RETIRING_ENGINEER / AI_MATCHING がフィルタに残る。色分けは青フォールバックになるだけで実害小。
- 対応方法: 通知タイプの一覧を1箇所へ集約する（推奨: `GET /api/notifications/types` を追加してサーバー定義から返す。次善: 共有JS定数）。todo/list.html の option を動的生成へ変更し、todo.js/common.js の iconColorMap を全タイプ分へ更新、不要タイプを削除する。ラベルは `todo.filter.type.*` キーを4ロケールへ追加。
- 期待結果: 発行されうる全タイプがフィルタに並び、発行されないタイプが残らない。
- 追加すべきテスト: 発行側タイプ一覧（Javaの publish 呼び出し）とフィルタ定義の突合テスト（定数集約後はコンパイルで担保）。

## X-12 [P3] common.js の共通エラーメッセージが日本語ハードコード（全画面へ波及）

- 対象ファイル／行: `src/main/resources/static/js/common.js:101,105,112`（`SES.api._fetch` の 403/500/汎用）、`:543,551,566-572`（ajaxSetup のセッション切れ・400系フォールバック文言）、`:388`（通知 `renderError`）
- 影響: en/zh-CN/ko ロケールでも「アクセス権限がありません。」等が日本語のまま表示される。個別モジュールは `SES.i18n.t` へ移行済みのため、共通層だけが取り残されている。
- 対応方法: 既存キー（`common.msg.networkError`/`common.msg.saveFail` 等）と新設キー（session-expiry、HTTPコード別フォールバック）へ置き換える。common.js は i18n.js より後に読み込まれるため `SES.i18n.t` を安全に使える。
- 期待結果: 全ロケールでエラートーストが翻訳される。
- 追加すべきテスト: `grep -nP '[ぁ-んァ-ヶ一-龠]'` を common.js のコメント以外行へ適用するlintをJS検査（JsSyntaxCheckTest系）へ追加。

## X-13 [P3] カンバンカードのタップが「詳細画面へ遷移します(ID: n)」というスタブトーストのまま

- 対象ファイル／行: `src/main/resources/static/js/modules/proposal-kanban.js:287-298`（`viewProposalDetail` がMVPデモ用トースト）、カード側 `onclick`（`:194`）
- 影響: カードをタップ（クリック）しても何も起きず、トーストが出るだけ。モバイルではカード操作＝タップが主動線のため目立つ。提案詳細画面自体が存在しない。
- 対応方法（どちらか）: (a) 提案編集モーダル（提案単価・ステータス・メール履歴の表示）を実装して開く。(b) 当面は `onclick` とスタブ関数を削除してタップを無効化し、誤タップによるトースト連発を止める。
- 期待結果: タップ操作が意味のある挙動（詳細表示）になるか、少なくとも誤解を生む表示が消える。
- 追加すべきテスト: (a) の場合、モーダル表示のDOMテスト。

---

## 参考: 仕様書ドリフト

- `.kiro/specs/README.md` は `engineer-sales-commission` を「全レーン完了」と記載しているが、X-01のとおり営業成績画面は一度も動作していない状態でmainに存在する。`tasks.md` のDemo手順（ブラウザ確認）が実施されていれば検出できたはずであり、**「Demo未実施のままチェック済みにしない」規約の徹底**と、X-01で提案したページ共通レンダリングテストの導入を推奨する。`engineer-availability-visualization`（X-03）も同様。
