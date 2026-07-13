# Implementation Plan — 支払期限・適格請求書対応(invoice-compliance)

**着手条件: WS-A(billing-integrity)がマージ済みであること。**
1 → 2 → 3 → 4 → 5 の順に実施(1 が全タスクの前提)。

- [x] 1. V13 マイグレーションとエンティティ
  - **Objective**: R1。due_date 列と設定キーのシード。
  - **実装ガイダンス**: design.md 1章。INSERT の列構成は V9 の既存シードを必ず確認して合わせる。`application-test.yml` の schema-locations へ追記。`Invoice` エンティティに `dueDate` 追加。
  - **テスト要件**: `mvn test` で H2 への V13 適用がエラーにならない(既存全テストグリーン)。
  - **Demo**: MySQL でアプリ起動 → Flyway が V13 を適用し、システム設定画面に新キーが表示される。

- [x] 2. 支払期限の自動設定
  - **Objective**: R2。生成時の due_date 自動計算。
  - **実装ガイダンス**: design.md 2章。`calcDueDate` は static で切り出して単体テスト可能に。
  - **テスト要件**: (a) 2026-07 + next-month-end → 2026-08-31、(b) next-next-month-end → 2026-09-30、(c) 不正ルール値 → 翌月末、(d) 2026-01 + next-month-end → 2026-02-28(月末処理)、(e) generate 後の invoice.due_date が設定済み。
  - **Demo**: 請求書を生成 → 詳細に支払期限が入っている。

- [x] 3. 期限超過通知
  - **Objective**: R3。日次バッチでの超過検知と通知発行。
  - **実装ガイダンス**: design.md 3章。既存の通知生成メソッド群と同じ構造・呼び出し位置に追加。顧客名は IN 一括解決。common.js の iconColorMap(と icon 決定ロジックがあればそちらも)に INVOICE_OVERDUE 追加。
  - **テスト要件**: `NotificationGenerateServiceTest` — 超過1件→通知1件(メッセージに請求書番号と超過日数)、入金済/期限内/due_date NULL/取消済みは対象外、同日2回実行で増えない(dedupe)。
  - **Demo**: due_date を昨日に更新した未入金請求書を用意 → 管理者で通知バッチ手動実行(`/api/notifications/generate`)→ ベルに赤アイコンの超過通知。

- [x] 4. 一覧の期限列・超過ハイライト(+ overdue フィルタ R6)
  - **Objective**: R4 + R6(実施済み)。
  - **実装ガイダンス**: design.md 4章。表示値は `SES.escapeHtml`。超過判定はクライアント側。
    R6: `GET /api/invoices` に `overdue` パラメータ追加 + 一覧に「期限超過のみ」チェックボックス。
  - **テスト要件**: `InvoiceApiControllerTest` に overdue=true で due_date 条件が付与される/未指定では付かないケースを追加。
  - **Demo**: 一覧で超過請求書の期限が赤字になる。「期限超過のみ」で絞り込める。

- [x] 5. 適格請求書の記載事項
  - **Objective**: R5。登録番号・税率区分を含む請求書出力。
  - **実装ガイダンス**: design.md 5章。`InvoiceDetailDto` に発行者情報+taxRate を追加し print.html / PDF の両方で使う。登録番号空なら行ごと非表示。税率は `billing.tax-rate` から。
  - **テスト要件**: (a) detail() が設定値を返す、(b) PDF テストで登録番号あり/なし両方の生成が成功、(c) 税率 0.08 設定時に「8%」表記になる。
  - **Demo**: システム設定で自社名と T番号を設定 → 印刷画面と PDF に「登録番号: T…」「10%対象/消費税」が表示される。番号を空にすると行が消える。`mvn test` 全件グリーン。
