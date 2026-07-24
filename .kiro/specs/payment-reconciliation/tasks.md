# Tasks — 入金消込の半自動化（FR-09）

外部API（freee）依存。突合ロジックはfreeeモックで単体テスト可能に。

- [x] F1. 入金保持（`t_bank_deposit`）＋freee入金取得
  - **Objective**: 銀行入金の取得と保存。
  - **実装ガイダンス**: design 1章。`FreeeIntegrationService` 拡張、タイムアウト/allowlist（A7-18）。テストDB二重維持。
  - **Demo**: freeeモックから入金が保存される。
  - **完了メモ**: `V50__payment_reconciliation.sql`で`t_bank_deposit`追加（`freee_deposit_id`一意制約で冪等）。`FreeeIntegrationService#bankDeposits(from,to)`をfreee会計API(`/api/1/deals?type=income`)向けに追加（既存の`get()`ヘルパー/RestTemplate/リフレッシュ機構を再利用）。H2側は`sql/engineer-schema-h2.sql`に追加（`AllMappersSchemaSweepTest`で担保）。

- [x] A. 突合ロジック
  - **Objective**: `PaymentReconciliationService`（自動/候補/保留）。
  - **実装ガイダンス**: design 2章。金額＋名義＋時期スコア、高信頼自動消込→`recalcPaymentStatus`、二重消込防止（CAS）。
  - **テスト要件**: `PaymentReconciliationServiceTest`（自動/候補/保留/二重防止/ステータス連動）。
  - **Demo**: 入金→請求へ自動/候補で消込。
  - **完了メモ**: `service/billing/PaymentReconciliationService` + `PaymentReconciliationServiceImpl`。候補は永続化せず`InvoiceMapper#selectOutstandingBalances()`から都度算出（残高の陳腐化を回避）。高信頼一致(金額＋名義)がちょうど1件のときのみ自動消込。`apply()`は`InvoiceService#addPayment`（既存の`recalcPaymentStatus`込み）へ委譲し、`t_bank_deposit`をFOR UPDATEで行ロック＋CAS更新して二重消込を防止。`PaymentReconciliationServiceImplTest`（12ケース）緑。

- [x] B. API＋経理画面
  - **Objective**: fetch/pending/apply と一覧UI（候補・保留・ワンクリック消込）。
  - **実装ガイダンス**: design 3章。管理者/マネージャー限定、監査記録。
  - **Demo**: 未消込→候補確定→入金済化。
  - **完了メモ**: `ReconciliationApiController`(`/api/reconciliation/{fetch,pending,{id}/apply}`) + `ReconciliationPageController`(`/reconciliation`)。新メニュー`reconciliation`を管理者/マネージャーのみへ`t_role_menu`シード（`MenuPermissionFilter`で他ロールは403）。監査ログは既存の`ApiAuditFilter`（POST/PUT/DELETE自動記録）を利用。画面: `templates/reconciliation/list.html` + `static/js/modules/reconciliation.js`（freee取得ボタン、候補一覧のワンクリック消込、候補なし入金の手動請求書ID割当）。

- [x] M. i18n・仕上げ（5ロケール、全量緑）。
  - **完了メモ**: `menu.reconciliation`・`error.reconciliation.*`を運用中の5ロケールファイル(ja既定/en/ko/zh/zh_CN)に追加。`mvn test`: 643 tests run, 0 failures（Docker未起動のためTestcontainers系3件は既存仕様どおりskip）。

## 完了条件
- 高信頼一致が自動消込され `recalcPaymentStatus` が反映、曖昧は候補提示、過入金は保留。
- 同一入金の二重消込が防止されるテストが緑。
</content>
