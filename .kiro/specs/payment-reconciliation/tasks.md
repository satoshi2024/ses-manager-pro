# Tasks — 入金消込の半自動化（FR-09）

外部API（freee）依存。突合ロジックはfreeeモックで単体テスト可能に。

- [ ] F1. 入金保持（`t_bank_deposit`）＋freee入金取得
  - **Objective**: 銀行入金の取得と保存。
  - **実装ガイダンス**: design 1章。`FreeeIntegrationService` 拡張、タイムアウト/allowlist（A7-18）。テストDB二重維持。
  - **Demo**: freeeモックから入金が保存される。

- [ ] A. 突合ロジック
  - **Objective**: `PaymentReconciliationService`（自動/候補/保留）。
  - **実装ガイダンス**: design 2章。金額＋名義＋時期スコア、高信頼自動消込→`recalcPaymentStatus`、二重消込防止（CAS）。
  - **テスト要件**: `PaymentReconciliationServiceTest`（自動/候補/保留/二重防止/ステータス連動）。
  - **Demo**: 入金→請求へ自動/候補で消込。

- [ ] B. API＋経理画面
  - **Objective**: fetch/pending/apply と一覧UI（候補・保留・ワンクリック消込）。
  - **実装ガイダンス**: design 3章。管理者/マネージャー限定、監査記録。
  - **Demo**: 未消込→候補確定→入金済化。

- [ ] M. i18n・仕上げ（5ロケール、全量緑）。

## 完了条件
- 高信頼一致が自動消込され `recalcPaymentStatus` が反映、曖昧は候補提示、過入金は保留。
- 同一入金の二重消込が防止されるテストが緑。
</content>
