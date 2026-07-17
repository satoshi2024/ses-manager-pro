# Requirements — 金銭フロー整合性・契約ライフサイクル補完（money-flow-consistency）

この spec は 2026-07-16 に実施した「モジュール間不完全性・金銭ロジック横断調査」の結果をまとめ、
是正方針を定義するもの。対象は資金フロー全体:

`提案(成約) → 契約 → 月次実績(勤怠) → 精算 → 請求/BP支払 → Dashboard/営業成績/Excel帳票`

## 調査サマリ（発見事項一覧）

| # | 発見事項 | 深刻度 | 対応 | 既存specとの関係 |
|---|---|---|---|---|
| 1 | 契約の編集・状態遷移 API（`PUT /api/contracts/{id}`、`PUT /api/contracts/{id}/status`）に**フロントエンド呼び出し元が存在しない**。成約由来ドラフト（原価0円）を編集も稼動化もできず、勤怠→精算→請求の資金フローがUI層で断絶 | **重大** | R1 | 未対応（`engineer-sales-commission` B は「契約編集で上書き設定を保存できる」を Demo としたが、編集UI自体が未実装のまま） |
| 2 | 「解約」遷移が終了日を確定しないため、解約後も元の `end_date`（NULLなら無期限）まで売上・粗利・インセンティブが計上され続ける | **重大** | R2 | 未対応 |
| 3 | 月次売上・粗利の集計口径が3系統あり互いに矛盾: (a) Dashboard/Excel帳票=月一括フォールバック・**契約ステータス無視**（準備中ドラフト原価0円が粗利100%で見込みに混入、解約も混入）、(b) 営業成績=契約単位フォールバック・準備中のみ除外、(c) 勤怠グリッド=稼動中/終了のみ | **重大** | R3 | 口径差自体は `engineer-sales-commission` R3-4 で既知と注記済み。ただし **(a) のステータス無視・月一括方式による計上漏れ/水増しは未認識の欠陥** |
| 4 | Dashboard 内部でも KPI「当月予想売上」（稼動中契約単価合計）とチャート当月値・トレンド%（実績優先の `calcMonthlyAmount`）の算出基準が異なり、同一画面内で数値が食い違う | 中 | R3 | 未対応 |
| 5 | `ExportApiController.buildMonthlyRevenueRows` が `DashboardServiceImpl.calcMonthlyAmount` をコピー実装しており、片側修正で帳票と画面が乖離する構造 | 中 | R3 | 未対応 |
| 6 | `reopenMonth` が対象月の「未払」BP支払を**手動登録した多段階層（2次請・3次請、支払先会社名含む）ごと無言で物理削除**し、再確定時は1階層目しか再生成されない | 中 | R4 | `billing-integrity` R1 は「支払済」の保護のみ。未払の手動階層は保護対象外のまま |
| 7 | `sales_user_id` が NULL の契約が営業成績画面のどこにも現れず、「未帰属」行がないため Dashboard 全社売上と突合できない | 中 | R5 | `engineer-sales-commission` R3-2 は退職者の帰属維持のみ規定 |
| 8 | `t_contract.fraction_rule`（端数処理ルール）は入力・保存・4言語ラベル・グリッド転送まで揃っているが、`SettlementCalculator` は常に1円未満切り捨てで**一切参照しない**。UI上その旨の注記もない | 小 | R6 | `work-record-billing` design で「本フェーズは解釈しない」と意図的留保済み。注記欠如のみ是正 |
| 9 | `selectMonthlyGrid` の `c.start_date <= CONCAT(#{workMonth}, '-31')` は2月・小の月で不正日付文字列を生成し MySQL の暗黙変換に依存 | 小 | R7 | 未対応 |
| 10 | `selectUnbilledWorkRecords` が `t_contract.deleted_flag` を確認しない（同ファイルの `selectMonthlyGrid` は確認）。現状は「実績を持つ契約は削除不可」ガードで間接的に守られているのみ | 小 | R7 | 未対応 |
| 11 | DBコメントの単価単位が「万円」のまま（`proposed_unit_price` / `expected_unit_price` / `unit_price_min/max`）。実装・シードデータ・UIはすべて円 | 小 | R7 | `business-logic-integrity-hardening` R2 はコード側の円統一のみ実施済み。コメント未修正 |
| 12 | 契約一覧の金額表示が `¥950,000円` と通貨記号が重複 | 小 | R7 | 未対応 |
| 13 | 請求書に**適用税率が保存されない**。`InvoiceServiceImpl.detail()` が表示用税率を現在の `billing.tax-rate` 設定から取得するため、税率変更後は過去の請求書のPDF・印刷画面(`InvoicePdfServiceImpl` / `invoice/print.html`)で「表示税率」と「保存済み税額」が矛盾する（適格請求書の記載事項として不正確） | 中 | R8 | `invoice-compliance` は登録番号・税率**表示**のみ実装。税率の発行時点固定は未対応 |
| 14 | BP支払に消費税の概念がない（顧客請求は税込計算するが、支払側 `t_bp_payment.amount` は原価精算額そのままで税処理なし） | — | **対象外**（下記参照） | 未対応 |

**対象外と整理した事項**: #14 のBP支払側の消費税・インボイス対応（協力会社からの適格請求書受領・仕入税額控除）は、
支払業務モデル自体の設計（税抜/税込の持ち方、免税事業者の経過措置）を要するため本 spec の対象外とし、
将来の独立 spec（例: `bp-payment-tax`）へ委ねる。ここに記録するのは「未認識の欠陥」ではなく「意図した留保」とするため。
また #7 に関連する「成約件数(契約 `created_at` 口径)と成約率(提案 `closed_at` 口径)の帰属月ズレ」は
`engineer-sales-commission` R3-4 で既知の口径注記として文書化済みのため、本 spec では扱わない。

**問題なしを確認済みの領域**（参考）: 請求書の二重請求防止（`work_record_id UNIQUE`・確定後編集ガード・請求済み reopen 拒否）、
請求書番号採番の並行リトライ、入金済請求書の取消拒否、支払済BP支払の金額編集拒否、消費税計算（小計×税率・切り捨て）、
精算計算そのもの（`SettlementCalculator` の上下割按分）。

## Requirements

### R1. 契約ライフサイクル UI の補完（編集・状態遷移）

現状: `contract.js` は一覧取得・新規作成・削除・Excel出力のみ。`PUT /api/contracts/{id}`・
`PUT /api/contracts/{id}/status`・`POST /api/contracts/generate-renewals` は呼び出し元ゼロ。
その結果、成約由来ドラフト（`createDraftFromProposal` が `costPrice=0`・`準備中` で生成、
「後で編集」前提）と自動更新ドラフトが永久に編集・稼動化できない。

#### Acceptance Criteria
1. THE 契約一覧 SHALL 各行に「編集」操作を持ち、既存モーダルに全項目（顧客・案件・要員・期間・売上単価・原価・精算幅・端数ルール・自動更新・担当営業・インセンティブ上書き）をプリセットして `PUT /api/contracts/{id}` で保存できる。
2. THE 編集ペイロード SHALL 全項目を常に送信する（`salesUserId`/`commissionBaseType`/`commissionRate` の `FieldStrategy.ALWAYS` は全項目送信を前提とするため。「既定に戻す」= 空選択で NULL 送信が実際に機能すること）。
3. THE 契約一覧 SHALL 各行に状態遷移操作を持ち、`ALLOWED_STATUS_TRANSITIONS`（準備中→稼動中/解約、稼動中→終了/解約）で許可される遷移先のみ提示し、`PUT /api/contracts/{id}/status` を呼ぶ。
4. WHEN 提案成約由来のドラフトを編集→稼動中へ遷移した場合、THE 当該契約 SHALL 勤怠グリッド（`selectMonthlyGrid`）に現れ、工数入力→精算→請求まで到達できる（E2E確認）。
5. THE ステータス変更 SHALL 不正遷移時に API の 409 メッセージをそのままトースト表示する。

### R2. 解約時の終了日確定

現状: `ContractServiceImpl.changeStatus` は解約時に `end_date` を変更しないため、
解約済み契約が元の契約期間いっぱい（`end_date` NULL なら無期限）売上・粗利・インセンティブ計上され続ける。

#### Acceptance Criteria
1. WHEN 契約を「解約」へ遷移させる場合、THE システム SHALL 解約日（実質終了日）の指定を必須とし、`end_date` を当該日で上書きする。
2. THE 解約日 SHALL 契約開始日以降であること（違反時 `BusinessException`）。
3. WHEN 解約日以降の月を集計する場合、THE 月次売上・粗利・インセンティブ SHALL 当該契約を含まない。
4. THE 既存の「終了」遷移 SHALL 現行どおり `end_date` を変更しない（自然満了）。

### R3. 月次売上・粗利の集計口径統一

現状の3口径のうち、Dashboard/Excel の月一括フォールバック（当月に確定実績が1件でもあれば
実績のみ合計し、実績のない稼動中契約を丸ごと無視。逆に実績ゼロの月は全契約をステータス不問で合算）が
最も誤差が大きい。営業成績の「契約単位フォールバック」を全社共通口径とする。

#### Acceptance Criteria
1. THE 月次金額計算 SHALL 共通サービス（例: `MonthlyRevenueCalcService`）へ集約され、Dashboard（チャート・トレンド）・月次売上Excel帳票・営業成績が同一ロジックを使用する。
2. THE 共通口径 SHALL 契約単位フォールバックとする: 対象月に確定済 `t_work_record` があればその `billing_amount`/`payment_amount`、なければ契約の `selling_price`/`cost_price`。
3. THE 集計対象契約 SHALL 「準備中を除外し、契約期間が対象月と重なるもの」とする（解約は R2 により `end_date` で自然に打ち切られる）。
4. THE Dashboard KPI「当月予想売上」 SHALL チャートの当月値と同一の計算結果を表示する（KPI とチャートで数値が一致すること）。
5. THE 月次売上Excel帳票の「実績/見込み」区分 SHALL 月一括ではなく「当該月の金額のうち実績由来が1件以上あれば実績（混在時は注記）」等、共通サービスが返す区分に従う。
6. THE `ExportApiController` SHALL 集計ロジックの独自実装を持たず共通サービスへ委譲する。
7. THE 口径変更 SHALL 単体テストで検証される（実績あり月に実績なし稼動中契約が計上されること、準備中ドラフトが見込みに混入しないこと、解約契約が解約日以降計上されないこと）。

### R4. 月次解除(reopen)による手動BP階層の保護

現状: `reopenMonth` は対象月の未払BP支払を全削除する。`billing-integrity` R1 で支払済は保護されたが、
`multi-tier-bp-management` で手動登録した未払の多段階層（支払先会社名・金額・階層構造）も削除され、
再確定時は自動生成の1階層目しか復元されない。

#### Acceptance Criteria
1. WHEN 対象月の実績に紐づく未払BP支払のうち、自動生成された1階層目以外（`layer_order > 1`、または `parent_payment_id IS NOT NULL`、または子階層を持つ行）が存在する場合、THE reopen SHALL `BusinessException` で拒否され、メッセージに該当実績・階層数が含まれる（手動データの黙殺的破壊をしない）。
2. WHEN 自動生成の1階層目のみが存在する場合、THE reopen SHALL 現行どおり成功し当該行のみ削除する。
3. WHEN 再確定(`confirmMonth`)時に既存BP支払が存在する場合（入力中段階での手動登録）、THE システム SHALL 1階層目の金額が最新の `payment_amount` と不一致なら通知または更新を行い、黙って旧金額を放置しない（設計で方式を確定）。

### R5. 営業成績の「未帰属」行による突合可能化

#### Acceptance Criteria
1. THE 営業成績画面 SHALL `sales_user_id IS NULL` の稼動契約を「未帰属」行として合算表示する（担当要員数・成約率は「—」、インセンティブは0）。
2. THE 未帰属行を含めた売上合計 SHALL 同一口径（R3）での全社売上と一致する。
3. THE 画面 SHALL 未帰属行から契約一覧（担当営業未設定での絞り込み）へ遷移でき、R1 の編集UIで帰属を設定して解消できる。

### R6. fraction_rule の非適用の明示

#### Acceptance Criteria
1. THE 勤怠グリッドおよび契約フォーム SHALL 端数処理ルールが自由記述メモであり精算計算には適用されない（計算は常に1円未満切り捨て）旨を注記表示する。
2. THE 自動適用化 SHALL 本 spec の対象外とする（`work-record-billing` design の留保を維持。将来 enum 化する場合は別 spec）。

### R7. 細部整備（低リスク項目の一括是正）

#### Acceptance Criteria
1. `selectMonthlyGrid` の月末判定 SHALL `CONCAT(#{workMonth}, '-31')` をやめ、`LAST_DAY(STR_TO_DATE(CONCAT(#{workMonth}, '-01'), '%Y-%m-%d'))` 相当（H2互換に留意）へ置換する。
2. `selectUnbilledWorkRecords` SHALL `c.deleted_flag = 0` を条件に加える（縦深防御）。
3. 新規マイグレーション SHALL `proposed_unit_price` / `expected_unit_price` / `unit_price_min` / `unit_price_max` のカラムコメントを「万円」→「円」へ修正する（`ALTER TABLE ... MODIFY` はデータ非破壊、V1 ベースラインは変更しない）。
4. 契約一覧の金額表示 SHALL `¥` と `円` の重複をやめ `¥950,000` 形式へ統一する。
5. システム設定画面 SHALL `billing.tax-rate`（小数: 0.10）と `commission.rate`（百分率: 5.0）の単位の違いを説明欄で明示する。

### R8. 請求書への適用税率の保存

現状: `t_invoice` は `subtotal`/`tax`/`total` を保存するが税率を保存しない。
`InvoiceServiceImpl.detail()` は表示用税率を**現在の** `billing.tax-rate` 設定から詰めるため、
税率改定後に過去請求書の PDF（`InvoicePdfServiceImpl`）・印刷画面（`invoice/print.html`）が
「新税率のラベル＋旧税率で計算済みの税額」という矛盾した記載になる（適格請求書の記載事項として不正確）。

#### Acceptance Criteria
1. THE `t_invoice` SHALL 生成時に適用した税率を保持する（新規カラム `tax_rate`、既存行は NULL 許容）。
2. WHEN 請求書詳細・PDF・印刷画面が税率を表示する場合、THE システム SHALL 保存済み税率を優先し、NULL（本対応以前の既存行）の場合のみ現在設定へフォールバックする。
3. THE 税額計算そのもの SHALL 変更しない（生成時に使ったのと同じ値を保存するだけ）。
4. THE スキーマ変更 SHALL 新規マイグレーション＋`engineer-schema-h2.sql` 同期＋H2 リプレイ設定の更新を伴う（CLAUDE.md のテストスキーマ二重管理規約に従う）。
