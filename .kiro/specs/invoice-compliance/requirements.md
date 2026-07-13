# Requirements — 支払期限・適格請求書対応(invoice-compliance)

**フェーズ2: WS-A(billing-integrity)完了後に着手すること**(同じ請求書まわりのファイルを編集するため)。

請求書に支払期限の概念を導入し、期限超過の検知・通知を実装する。
あわせて日本の適格請求書等保存方式(インボイス制度)の必須記載事項に対応する。
新規マイグレーション: `V13__invoice_due_date.sql`。

対象コード: `InvoiceServiceImpl` / `entity/Invoice` / `NotificationGenerateService` /
`SystemConfigService`(キー追加のみ) / `invoice/print.html` / `InvoicePdfServiceImpl` / `invoice.js`。

## R1. 支払期限カラムの追加

受入基準:
1. `V13__invoice_due_date.sql` で `t_invoice` に `due_date DATE NULL COMMENT '支払期限'` を追加する。
2. マイグレーションは `src/test/resources/application-test.yml` の
   `spring.sql.init.schema-locations` にも追記され、H2(MySQLモード)でエラーなく適用される。
3. `entity/Invoice` に `dueDate` フィールドが追加される。
4. 既存データ(due_date NULL)は期限超過判定の対象外として扱われる。

## R2. 支払期限の自動設定

受入基準:
1. 請求書生成(`InvoiceServiceImpl.generate`)時に、システム設定キー
   `billing.payment-due-rule` に従って `due_date` が自動設定される。
2. ルール値は「請求月の翌月末」をデフォルトとする(`next-month-end`)。
   設定値が `next-next-month-end`(翌々月末)の場合はそれに従う。不正値はデフォルト扱い。
3. 例: billingMonth=2026-07、ルール=next-month-end → due_date=2026-08-31。
4. 設定キーは `V13` マイグレーションで `m_system_config` にシードする
   (既存のシード形式・カテゴリ分類は `V9__system_config_and_menu.sql` を踏襲)。

## R3. 期限超過の検知と通知

受入基準:
1. 日次の通知バッチ(既存 `NotificationScheduler` → `NotificationGenerateService`)に
   「期限超過請求書」の生成処理が追加される: `status != '入金済'` かつ `deleted_flag=0` かつ
   `due_date < 今日` の請求書ごとに通知を1件発行する。
2. 通知タイプは `INVOICE_OVERDUE`、メッセージは
   「請求書 {invoiceNo}({顧客名})が支払期限を{n}日超過しています」、リンクは `/invoice/list`。
3. `dedupeKey` = `invoice-overdue:{invoiceId}:{今日}` 形式で同日重複を防ぐ
   (既存の dedupe 実装を踏襲)。
4. `common.js` の `iconColorMap` に `INVOICE_OVERDUE: 'text-accent-red'` を追加する。

## R4. 一覧画面での期限表示

受入基準:
1. 請求書一覧(invoice.js)に「支払期限」列が追加される。
2. 期限超過(未入金かつ due_date < 今日)の行は期限が赤字表示になる。
3. due_date が NULL の既存請求書は `-` 表示。

## R5. 適格請求書(インボイス制度)の記載事項

現状: `invoice/print.html` / PDF には登録番号・税率区分の記載がなく、
適格請求書の要件(①発行者の氏名/名称と**登録番号** ②取引年月日 ③取引内容
④**税率ごとに区分した合計額と適用税率** ⑤**税率ごとの消費税額** ⑥受領者の氏名/名称)を満たさない。

受入基準:
1. システム設定に自社情報キーを追加(V13 でシード):
   `company.name`(自社名)、`company.invoice-registration-number`(登録番号 T+13桁)、
   `company.address`(住所)。
2. `invoice/print.html` と `InvoicePdfServiceImpl` の出力に以下が表示される:
   発行者名 + 登録番号、対象月(取引期間)、明細、税抜合計、「10%対象 {小計} / 消費税 {税額}」
   の税率区分表記、税込合計、宛先(顧客名+「御中」)。
3. 登録番号が未設定(空)の場合は登録番号行を出力しない(制度未登録の事業者でも使える)。
4. 税率はハードコードせず既存の `billing.tax-rate` 設定値から表示する
   (`InvoiceServiceImpl.generate` と同じソース)。

## R6. 期限のフィルタ(任意・工数が許せば)

受入基準:
1. `GET /api/invoices` に `overdue=true` パラメータを追加し、期限超過のみに絞れる。
2. 一覧画面に「期限超過のみ」チェックボックス。
