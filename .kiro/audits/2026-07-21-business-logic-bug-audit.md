# 第5回監査 業務ロジックバグ（B-01〜B-08）— 対応方法と期待効果

- 監査日: 2026-07-21
- 対象: `main@dd24526`（作業ツリー未コミット分の X-01〜04 / M-01〜02 修正を含む）
- 対象範囲: サービス層の業務ロジック（請求・入金・BP・月次締め・集計口径・営業成績・契約/提案/見積/候補者のライフサイクル・通知生成・データスコープ・単価改定連動）を横断で静的照合
- 方針: **モジュール間（業務と業務の連動）とモジュール内部（業務そのもの）の新規バグのみ**。過去監査（LOGIC/UI/R/N/X/M 系）で既出の指摘は除外
- 本書は**指摘のみ**（修正はしていない）。各項目は「対応方法」と「期待効果」に絞って記載

## 優先度

- **P2**: 特定条件で誤った通知・誤表示・誤操作が確実に発生。
- **P3**: 整合性・保守性・効率の問題。計画的に是正。

## 監査で「問題なし」を確認した主な領域（重複調査の防止）

以下は精読の結果、ロジックが正しく保護されていることを確認した（前回までの是正が有効）:
精算計算（`SettlementCalculator` 幅精算の上限/下限別単価は仕様どおり）、入金消込（過入金拒否・手数料込み判定・ステータス再計算の一元化）、契約状態機械（更新経路は `status` を null 化し迂回不可、解約は `end_date` を解約日で上書き）、月次締めゲート（`FOR UPDATE` ロック＋締めJSON破損時 fail-closed）、請求生成（`w.status='確定'` のみ対象・請求明細の一意制約で二重請求防止）、提案の `closed_at` 設定（営業成績の成約率が機能）、通知の null 宛先（全体通知フォールバックで NPE なし）、データスコープ（未帰属契約で顧客権限を拡大しない R3R-34）。

---

## B-01 [P2] 期限超過通知が「未送付」請求書も対象にし、送っていない請求書を督促扱いにする

- 対象: `src/main/java/com/ses/service/NotificationGenerateService.java:59-72`（`invoiceOverdue`）
- 現象・影響: 抽出条件が `status != '入金済' AND due_date < today` のみで、**未送付（顧客へ未発行）の請求書も「支払期限超過（INVOICE_OVERDUE）」通知に含まれる**。同じ「売掛」でもエイジング表（`InvoiceServiceImpl.aging:354`）は未送付を `unsent`（未請求）列へ別掲し、督促ボタン（`invoice.js:73`）も `['送付済','一部入金']` のときだけ表示する。つまり**通知だけが未送付を督促対象にしており、3者で未送付の扱いが食い違う**。担当営業へ「送っていない請求書が期限超過」という誤った督促通知が毎日届く。
- 対応方法: 抽出条件を送付済・一部入金に限定する。`qw.in("status", "送付済", "一部入金").isNotNull("due_date").lt("due_date", today)` へ変更する（エイジング表・UI督促ボタンと同じ「送付済かつ未回収」の口径に揃える）。
- 期待効果: 未送付請求書には期限超過通知が出なくなり、通知・エイジング・督促ボタンの「期限超過」の定義が一致する。実際に送付済で未回収の請求書のみが督促対象になる。

## B-02 [P2] 稼働率カレンダーのスキル絞り込みがスキル名でなく数値IDを表示する（X-03 修正の残存不具合）

- 対象: `src/main/resources/static/js/modules/availability-calendar.js`（`fetch('/api/skill-tags')` のオプション生成、`opt.textContent = s.tagName || s.name || s.id`）
- 現象・影響: `/api/skill-tags` が返す `SkillTag` のフィールドは `skillName` のみ（`entity/SkillTag.java:39`。`tagName`/`name` は存在しない）。既存の `engineer.js:62` も `skill.skillName` を使用。現コードは `tagName`→`name`→`id` と全てフォールスルーし、**スキルセレクトに「Java」ではなく「17」等の数値IDが並ぶ**。X-03 の絞り込み機能が実質使えない。
- 対応方法: `opt.textContent = s.skillName || s.id;` に修正する（1行）。同関数の営業担当セレクト `u.name || u.realName` は `SalesUserOptionDto.realName` へフォールスルーするため修正不要。
- 期待効果: スキルセレクトにスキル名が表示され、稼働率カレンダーのスキル絞り込みが正しく機能する。
- 補足: `PageRenderingTest` は 200・JS含有のみ検査（MockMvcはJSを実行しない）ため本件を検出しない。修正時はブラウザでオプション文言を確認すること。

## B-03 [P3] 候補者のステージ変更に遷移検証が無く、逆行・入社後の巻き戻しで要員紐付けが宙に浮く

- 対象: `src/main/java/com/ses/service/impl/CandidateServiceImpl.java:66-95`（`changeStage`）、`:123-170`（`linkConvertedEngineer`）
- 現象・影響: `changeStage` は `validateStage`（許可値集合への所属チェック）だけで、**状態遷移マップが無い**。提案（`ProposalServiceImpl.ALLOWED`）・契約（`ContractServiceImpl`）・見積（`QuotationServiceImpl`）が厳密な遷移マップを持つのと非対称。このため「入社」「不採用」「内定辞退」等の終端ステージから「応募」へ任意に巻き戻せる。さらに `linkConvertedEngineer` は入社ステージ必須だが、**紐付け後にステージを入社から戻しても `converted_engineer_id` は残る**ため、「入社ではないのに要員へ紐付いた候補者」という不整合が生じる（要員側からの逆参照や重複紐付けチェックの前提が崩れる）。
- 対応方法:
  1. `changeStage` に `ALLOWED_STAGE_TRANSITIONS`（例: 応募→書類選考→一次→最終→内定→入社、各段階から不採用/内定辞退へ）を定義し、許可外遷移を 400 で拒否する。訂正運用が必要なら「1つ前へ戻す」だけを許可する等、明示的に設計する。
  2. 入社→他ステージへ戻す遷移は、`converted_engineer_id` が設定済みの場合は拒否する（または紐付け解除を伴う専用APIに限定する）。
- 期待効果: 候補者のステージが他モジュールと同じ厳密さで管理され、終端からの不自然な巻き戻しと、要員紐付けの宙吊りが発生しなくなる。

## B-04 [P3] 契約損益分析が全ステータス契約を混在集計し、要員・案件を1件ずつ引く（口径＋N+1）

- 対象: `src/main/java/com/ses/service/impl/DashboardServiceImpl.java:334-371`（`getProfitAnalysis`）
- 現象・影響:
  1. **口径**: `contractMapper.selectList(new QueryWrapper<>())` で**準備中・終了・解約を含む全契約**を対象にし、契約マスタの `sellingPrice/costPrice` から粗利を出す。稼働実績を一度も生まない準備中ドラフトや、途中解約した契約も同じ粒度で「契約損益」に並び、ダッシュボードKPIの当月粗利（共通口径サービス・確定実績ベース）とは母集団も金額根拠も異なる。画面は「契約損益」としか説明せず、利用者はどの契約が実損益に寄与しているか判別できない。
  2. **N+1**: ループ内で `engineerMapper.selectById` / `projectMapper.selectById` を契約ごとに実行（`:340-341`）。契約数に比例してクエリが増える。
- 対応方法:
  1. 対象を意味のあるステータス（例: `稼動中`・`終了`、必要なら解約も別表記）に絞るか、画面に「契約マスタ単価ベースの理論粗利であり、確定実績とは異なる」旨を明記して口径を宣言する。
  2. 要員・案件は `selectBatchIds` で一括取得して Map 参照へ変更する（`getSummary` の退場予定リスト実装が既にこのパターン）。
- 期待効果: 損益分析が実際に稼働する契約の粗利を示し（または口径が明示され）、契約件数が増えてもクエリ数が一定になる。

## B-05 [P3] BP支払レイヤーの部分更新で備考（remarks）が消える

- 対象: `src/main/java/com/ses/service/impl/BpPaymentServiceImpl.java:116-142`（`updateLayer`）
- 現象・影響: `updateLayer` は `.set("remarks", bpPayment.getRemarks())` を**常に**実行する（金額と違い条件付き `set` になっていない `:139`）。金額だけを更新して remarks を送らない呼び出し（remarks=null）が来ると、**既存の備考が null 上書きされて消える**。金額は `.set(bpPayment.getAmount() != null, "amount", ...)` と条件付きなのに remarks だけ無条件で、意図が非対称。
- 対応方法: remarks も条件付きに揃える（`.set(bpPayment.getRemarks() != null, "remarks", bpPayment.getRemarks())`）。もしくは呼び出し側が常に全項目を送る契約なら、その前提をコメントで明記する。
- 期待効果: 金額だけの更新で備考が失われなくなり、部分更新の安全性が金額・備考で揃う。

## B-06 [P3] 月次締め記録の保存が config 行を二重書き込みし、キャッシュを迂回する

- 対象: `src/main/java/com/ses/service/impl/MonthlyClosingServiceImpl.java:238-252`（`saveRecordsToJson`）
- 現象・影響: 同一トランザクション内で (1) `systemConfigMapper.insert/updateById(config)` の直接書き込みと、(2) `systemConfigService.put(CONFIG_KEY, json, ...)` の二重書き込みを行う。(1) は `SystemConfigService` のキャッシュを更新せず、(2) が最終的にDB＋キャッシュを整えるため機能上は成立しているが、**同じ行を2回書く冗長実装**で、直接書き込みは意味がない。加えて全体を `catch (Exception)` で包み、失敗時に**メッセージキーでなく日本語直書きの 500**（`"締め記録の保存に失敗しました"`）を投げる（`GlobalExceptionHandler` の i18n 解決に乗らない）。
- 対応方法: (1) の直接 mapper 書き込みを削除し、`systemConfigService.put(CONFIG_KEY, json, "...")` のみにする（`put` が selectById→insert/update とキャッシュ更新を担う。`closing.confirmed-months` は SCHEMAS 許可済み `SystemConfigServiceImpl:77`）。500 のメッセージをキー（例: `error.closing.saveFailed`）へ置換し4ロケールへ定義する。
- 期待効果: 締め記録の保存経路が1本化されキャッシュと常に整合し、保存失敗メッセージが多言語化される。

## B-07 [P3] 一部の API レスポンス・警告文がメッセージキーでなく日本語直書き

- 対象:
  - `src/main/java/com/ses/controller/api/ContractApiController.java:107,132` — `ApiResult.success("警告: 粗利がマイナスです", ...)`（新規/更新の粗利マイナス警告）
  - `src/main/java/com/ses/controller/api/CsvApiController.java:119,125` — `"ファイルが空です"` / `"CSVインポートに失敗しました"`
  - `src/main/java/com/ses/controller/api/NotificationApiController.java:42` — `"認証されていません"`
- 現象・影響: これらは `BusinessException` のキー方式（`GlobalExceptionHandler` が i18n 解決）を通らず、**en/zh-CN/ko ロケールでも日本語のまま**フロントのトースト・警告に表示される。個別モジュールの多くが `SES.i18n`／メッセージキーへ移行済みの中で、これらだけ取り残されている。
- 対応方法: 各文言を `messages*.properties`（4ロケール）へキー定義し、コントローラは `ApiResult.success(messageSource.getMessage(key,...), ...)` またはクライアント側でキー→文言変換する方式へ統一する。粗利マイナス警告は「警告フラグ＋キー」を返し、表示文言はフロントで解決する形が望ましい。
- 期待効果: 全ロケールで警告・エラー文言が翻訳され、API 応答文言の多言語対応が全体で揃う。

## B-08 [P3] 営業成績の「成約件数」が同月内に解約された契約も1件として数える

- 対象: `src/main/java/com/ses/service/impl/SalesPerformanceServiceImpl.java:128-133`（`closedContractCount` の加算条件）
- 現象・影響: 成約件数は「当月に `created_at` があり、更新契約でない（`renewed_from_contract_id == null`）」契約を数えるが、**その後（同月内でも）解約された契約を除外しない**。提案成約→ドラフト自動生成直後に解約されたケースでも成約1件として計上され、実際には成立しなかった案件が営業成績の成約数を水増しする。成約率（提案口径）とは別系統のため、成約件数だけが過大になり得る。
- 対応方法: 集計時に契約の現在ステータスを考慮する（例: `解約` を除外、または「当月末時点で解約でない」を条件に加える）。仕様として「成約時点の事実を数える（後の解約は問わない）」なら、その旨を画面注記とコード comment に明記して意図を固定する。
- 期待効果: 成約件数が実際に成立した契約のみを反映する（または口径が明示され）、営業成績の数値の意味が一意になる。

---

## 補足（さらに低優先・任意）

- `NotificationGenerateService.benchLong:128` は `e.getCreatedAt().toLocalDate()` を無防備に呼ぶ。`created_at` は自動採番のため実運用では非 null だが、契約なし・`created_at` null の要員が1件でもあると `benchLong` 全体が NPE で中断する。null ガード（`e.getCreatedAt() != null` フォールバック）を入れると堅牢。
- `ContractRenewalServiceImpl.generateRenewalDrafts:39-74` は全候補を1トランザクションで処理する。1件の検証失敗でバッチ全体（生成済み分＋通知）がロールバックする。日次バッチの回復性を上げるなら契約単位のトランザクション分割を検討。
- `DashboardServiceImpl` の `statusChart` は要員 `status` が「退場予定」でも契約終了日ベースの retiring に該当しない要員をどの区分にも数えないため、4区分の合計が総数と一致しないことがある（既存コメント `:153` の設計に伴う副作用）。円グラフの母数と一致させるなら「その他」区分の追加を検討。
