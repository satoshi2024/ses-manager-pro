# Design — 債権管理（ar-management）

対象コード: `InvoiceServiceImpl` / `InvoiceApiController` / `InvoiceMapper` / 新 `InvoicePayment` エンティティ一式 /
`ExcelExportService` / `MailService`（既存流用） / `invoice.js` / `templates/invoice/list.html`

## 1. マイグレーション（`V28__ar_management.sql`※）

※ 番号は実装時点の最新+1 に読み替える。マージ順の全体調整は
`customer-feature-proposals/README.md` の「実装全体計画」を参照。

```sql
CREATE TABLE t_invoice_payment (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id   BIGINT NOT NULL COMMENT '対象請求書',
  paid_date    DATE NOT NULL COMMENT '入金日',
  amount       DECIMAL(12,0) NOT NULL COMMENT '入金額(円)',
  fee          DECIMAL(12,0) NOT NULL DEFAULT 0 COMMENT '振込手数料(円・当方負担の目減り)',
  remarks      VARCHAR(300),
  created_by   BIGINT,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (invoice_id) REFERENCES t_invoice(id) ON DELETE RESTRICT
) COMMENT='請求書入金';

ALTER TABLE t_invoice MODIFY status ENUM('未送付','送付済','一部入金','入金済') DEFAULT '未送付' COMMENT 'ステータス';
-- m_customer.contact_email が無い場合のみ（実装時確認）:
-- ALTER TABLE m_customer ADD COLUMN contact_email VARCHAR(255) NULL COMMENT '担当者メール';
```

- `t_invoice_payment` は物理行（deleted_flag なし。取消=削除、監査は操作ログで担保）。
- H2 同期: `engineer-schema-h2.sql` に同テーブル追加＋`t_invoice` の status は H2 では VARCHAR のため
  ENUM 変更の H2 変種は不要（値追加のみ）。リプレイ用 `sql/schema-ar-management-h2.sql`
  （CREATE TABLE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS）を `application-test.yml` へ登録。

## 2. サービス層

`InvoiceService` へ追加（impl は `InvoiceServiceImpl`）:

```java
InvoicePayment addPayment(Long invoiceId, InvoicePayment payment);  // R1-1,2,3,5,6 過入金拒否
void deletePayment(Long invoiceId, Long paymentId);                 // R1-4,6
List<InvoicePayment> listPayments(Long invoiceId);
AgingReportDto aging(LocalDate asOf);                               // R2
MailDispatchResult sendReminder(Long invoiceId, Long templateId);   // R3
```

- 消込再計算は private `recalcPaymentStatus(Invoice invoice)` に集約:
  `paidTotal = Σ(amount+fee)` → `>= total` なら 入金済+`paid_date=最終入金日` /
  `> 0` なら 一部入金+`paid_date=null` / `== 0` なら 送付済+`paid_date=null`。
  addPayment / deletePayment の双方から同一メソッドを呼ぶ（ステータス判定の二重定義禁止）。
- 過入金拒否: `既存合計 + 新規(amount+fee) > total` なら `error.invoice.overPayment`。
- 手動ステータス API（`changeStatus`）の `ALLOWED` から 入金済 系遷移を除去:
  `未送付→送付済`, `送付済→未送付` のみ残す。`入金済`/`一部入金` は入金行からのみ
  （既存テストの期待値更新が必要。入金済→送付済 の手動巻き戻しも廃止し、入金行削除で表現する）。
- 督促: `sendReminder` は 送付済/一部入金 かつ due_date < today のみ許可。変数 Map
  （customerName/invoiceNo/total/balance/dueDate/overdueDays）を組んで
  `MailService.sendWithTemplate(templateId, params, to)` を呼ぶ。宛先未設定は
  `error.invoice.customerEmailMissing`。

### AgingReportDto（`dto/invoice/`）

```java
class AgingReportDto {
  LocalDate asOf;
  List<Row> rows;            // 顧客ごと
  Row total;                 // 合計行
  class Row { Long customerId; String customerName;
              BigDecimal notDue, d1to30, d31to60, d61to90, d91plus, noDueDate, unsent; }
}
```

集計 SQL は `InvoiceMapper` に `@Select`（請求書×入金合計の LEFT JOIN、`deleted_flag=0`）で
残高付き一覧を返し、区分振り分けは Java 側で行う（境界テストを書きやすくするため）。

## 3. API（`InvoiceApiController` 追記）

- `GET  /api/invoices/{id}/payments` / `POST 同` / `DELETE /api/invoices/{id}/payments/{paymentId}`
- `GET  /api/invoices/aging?asOf=YYYY-MM-DD`（省略時=今日）
- `POST /api/invoices/{id}/reminder`（body: templateId）
- すべて `ApiResult<T>`。既存 `invoice` メニューの `api_prefix` 配下のため権限追加不要。

## 4. フロントエンド

`templates/invoice/list.html` / `static/js/modules/invoice.js`:

1. 請求書行に「入金」ボタン → `#paymentModal`（履歴テーブル＋追加フォーム＋行削除。
   削除は SweetAlert2 確認）。残高と「あと¥N円で入金済」を表示。
2. 新タブ「エイジング」: 基準日 date input＋マトリクステーブル。セルクリックで
   ドリルダウン（同タブ内の下部テーブル）。「Excel出力」ボタン。
3. 行に「督促メール」ボタン（期限超過行のみ活性）→ テンプレート選択モーダル → 送信 → Toast。
4. ステータスバッジに 一部入金（黄色系）を追加。

## 5. Excel（`ExcelExportService.exportAging`）

ヘッダー: 顧客名 / 未請求 / 期限内 / 1-30日 / 31-60日 / 61-90日 / 91日以上 / 期限未設定 / 残高計。
`sanitize`・`#,##0` 書式は既存規約どおり。エンドポイント `GET /api/invoices/aging-export?asOf=`。

## 6. i18n

`invoice.payment.*`（モーダル一式）/ `invoice.aging.*`（タブ・区分見出し）/
`invoice.reminder.*` / `error.invoice.overPayment` / `error.invoice.paymentOnVoided` /
`error.invoice.customerEmailMissing` / `invoice.status.partiallyPaid` — 4言語。

## 7. レーン分割

- **レーンA（消込コア）**: V28・エンティティ/Mapper・`addPayment/deletePayment/recalc`・
  状態機械変更・payments API・入金モーダル。
- **レーンB（エイジング+Excel）**: `aging()`・DTO・API・タブUI・`exportAging`。A の
  「残高=total−Σ(amount+fee)」定義に依存するため **A のエンティティ確定後に着手**（F相当を A が兼ねる）。
- **レーンC（督促メール）**: `sendReminder`・API・UI・i18n。A 完了後。
- 推奨: A → (B ∥ C) → 統合。

## 8. テスト方針

| 対象 | ケース |
|---|---|
| recalc | 部分→一部入金 / 全額→入金済+paid_date / 手数料込みで到達 / 行削除で巻き戻り(送付済・paid_date null) |
| addPayment | 過入金拒否 / 取消済み請求書拒否 / 未送付への入金（許可: 先行入金は実務であるため。ステータスは一部入金へ） |
| changeStatus | 入金済への手動遷移が拒否される（既存テスト期待値更新） |
| aging | 境界: 経過0日=期限内 / 1日・30日=1-30 / 31日=31-60 / due_date null / 入金済除外 / 旧データ(入金行なし入金済)除外 |
| reminder | 変数差し込み / 宛先なしエラー / 期限内は拒否 |
| H2 | `FlywayMigrationSmokeTest` に t_invoice_payment のカラム assert 追加 |
