# Design — 支払期限・適格請求書対応(invoice-compliance)

**前提: WS-A(billing-integrity)マージ後の main/統合ブランチから着手する。**
編集対象:

- `src/main/resources/db/migration/V13__invoice_due_date.sql`(新規)
- `src/test/resources/application-test.yml`(schema-locations へ V13 追記)
- `src/main/java/com/ses/entity/Invoice.java`
- `src/main/java/com/ses/service/impl/InvoiceServiceImpl.java`
- `src/main/java/com/ses/service/NotificationGenerateService.java`(実装クラス側。現物の
  クラス構成を実装時に確認 — 生成メソッド群が並ぶサービスに1メソッド追加する)
- `src/main/java/com/ses/controller/api/InvoiceApiController.java`(R6 実施時のみ)
- `src/main/resources/templates/invoice/print.html`
- `src/main/java/com/ses/service/impl/InvoicePdfServiceImpl.java`
- `src/main/resources/static/js/modules/invoice.js` / `common.js`(iconColorMap 1行)
- テスト: `InvoiceServiceImplTest` / `NotificationGenerateServiceTest` / `InvoicePdfServiceImplTest`

## 1. マイグレーション(R1)

`V13__invoice_due_date.sql`:

```sql
-- 支払期限
ALTER TABLE t_invoice ADD COLUMN due_date DATE NULL COMMENT '支払期限';

-- システム設定: 支払期限ルールと自社情報(適格請求書)
-- ※ INSERT の列構成・カテゴリ値は V9__system_config_and_menu.sql の既存シードに合わせること
INSERT INTO m_system_config (...) VALUES
  ('billing.payment-due-rule', 'next-month-end', ...),
  ('company.name', '', ...),
  ('company.invoice-registration-number', '', ...),
  ('company.address', '', ...);
```

H2(MySQLモード)で `ALTER TABLE ... ADD COLUMN ... COMMENT` が通ることを確認する
(通らない場合は COMMENT を外す)。`application-test.yml` の `schema-locations` 末尾に追記。

## 2. 期限の自動設定(R2)

`InvoiceServiceImpl.generate` 内、`issuedDate` 設定の直後:

```java
invoice.setDueDate(calcDueDate(billingMonth,
        systemConfigService.getString("billing.payment-due-rule", "next-month-end")));
```

```java
/** 請求月とルールから支払期限を算出する(不正ルールは翌月末) */
static LocalDate calcDueDate(String billingMonth, String rule) {
    YearMonth ym = YearMonth.parse(billingMonth);
    int plus = "next-next-month-end".equals(rule) ? 2 : 1;
    return ym.plusMonths(plus).atEndOfMonth();
}
```

(`SystemConfigService` の文字列アクセサ名は現物を確認して合わせる。)

## 3. 期限超過通知(R3)

`NotificationGenerateService` の既存生成メソッド群(契約終了予定・長期Bench等)と同じ形式で
`generateInvoiceOverdue()` を追加し、スケジューラの日次実行フローに組み込む
(既存メソッドがどこから呼ばれているかを確認し、同じ場所に追加する)。

```
対象: invoiceMapper.selectList(status != '入金済' AND due_date IS NOT NULL AND due_date < 今日)
      ※ MyBatis-Plus 経由なので deleted_flag=0 は自動
各件: 顧客名を customerMapper で解決(IN 一括)し、
      publish("INVOICE_OVERDUE",
              "支払期限超過",
              "請求書 " + invoiceNo + "(" + customerName + ")が支払期限を" + days + "日超過しています",
              "/invoice/list",
              "invoice-overdue:" + id + ":" + LocalDate.now())
```

`common.js` の `iconColorMap` に `'INVOICE_OVERDUE': 'text-accent-red'` を追加。
通知アイコン(`NotificationDto.icon`)の決定ロジックが型→アイコンのマップを持つ場合は
そちらにも `INVOICE_OVERDUE` を追加する(現物確認)。

## 4. 一覧の期限列(R4)+ フィルタ(R6)

invoice.js `loadInvoices` の描画に「支払期限」列を追加(`SES.escapeHtml`)。
超過判定はクライアント側: `status !== '入金済' && dueDate && dueDate < 今日` → `text-danger fw-bold`。
R6 を実施する場合: `InvoiceApiController.list` に `overdue` boolean パラメータ →
`query.ne("status", "入金済").isNotNull("due_date").lt("due_date", LocalDate.now())`。

## 5. 適格請求書の記載(R5)

- 設定の取得: `systemConfigService.getString("company.name", "")` 等。
  `InvoiceDetailDto` に発行者情報を持たせる(`companyName` / `registrationNumber` /
  `companyAddress` / `taxRate` フィールド追加)か、`detail()` で詰める。
  print.html / PDF の両方が同じ DTO から描画できるようにする。
- `invoice/print.html`: ヘッダー部に発行者名・住所・「登録番号: T…」(空なら `th:if` で非表示)。
  合計欄を「小計(10%対象) / 消費税(10%) / 合計」の3段にし、適用税率を
  `billing.tax-rate` から表示(0.10 → 「10%」)。
- `InvoicePdfServiceImpl`: 同じ項目を追加。既存のPDF描画実装(フォント・レイアウト)の
  流儀に従い、追記位置は現物のレイアウトを見て決める。

## 6. テスト設計

- `calcDueDate` の単体テスト: next-month-end / next-next-month-end / 不正値 / 2月末(2026-01→2026-02-28)。
- `generate` 後に due_date が設定されること。
- `NotificationGenerateServiceTest`: 超過1件で通知1件・dedupeKey 形式・入金済/期限内/取消済みは対象外。
  同日2回実行で通知が増えないこと。
- `InvoicePdfServiceImplTest`: 登録番号設定あり/なしでの出力(既存テストの検証方式を踏襲)。
