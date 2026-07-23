# Design — 入金消込の半自動化（FR-09）

親: ロードマップ FR-09。再利用: `FreeeIntegrationService`・`Invoice`/`InvoicePayment`・`InvoiceServiceImpl.recalcPaymentStatus`・`ar-management`・`AuditLog`。外部API依存のため検証コスト高め。

## 1. 入金取得

- `FreeeIntegrationService` に銀行明細（walletable/deals の入金）取得を追加（`type` allowlist・タイムアウト付き RestTemplate は A7-18 の是正に準拠）。
- 取得した入金は一時保持（`t_bank_deposit` を新設 or メモリ処理＋消込結果のみ永続。推奨: `t_bank_deposit` に生入金を保存し突合状態を持つ）。

## 2. 突合ロジック `service/billing/PaymentReconciliationService`

- 各入金 `d` に対し `t_invoice`（`status != 入金済`）から候補算出:
  - スコア = 金額一致（残額 == 入金額: 高）＋ 名義一致（`Customer` 名の正規化 == 振込名義: 中）＋ 時期近傍（due_date ± N日: 低）。
  - 高信頼（金額一致＋名義一致）→ 自動: `t_invoice_payment` 登録 → `recalcPaymentStatus`（既存）。
  - 曖昧 → `候補提示`（人が確定）。
  - 過入金/不足/未マッチ → `保留`。
- 冪等: 同一入金の二重消込防止（入金IDで一意、CAS）。金額同期・締めとの整合は既存規約（A7-05のCAS/締め保護）に反しないこと。

## 3. API・画面

- `POST /api/reconciliation/fetch`（freeeから入金取得＆突合実行）、`GET /api/reconciliation/pending`（未消込/候補/保留）、`POST /api/reconciliation/{depositId}/apply`（手動割当確定）。
- 経理画面 `templates/reconciliation/list.html` ＋ `reconciliation.js`: 未消込入金・候補（信頼度順）・保留、ワンクリック消込。
- 権限: 管理者/マネージャー（新メニュー `reconciliation`）。監査ログ記録。

## 4. テスト
- `PaymentReconciliationServiceTest`: 金額＋名義一致=自動消込、金額のみ=候補、過入金=保留、二重消込防止、`recalcPaymentStatus` 連動。freee呼び出しはモック。
</content>
