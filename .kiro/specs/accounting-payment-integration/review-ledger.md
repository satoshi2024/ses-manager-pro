# Review Ledger — 会計・支払連携 (accounting-payment-integration / S15)

## 1. 概要
- **Spec**: `accounting-payment-integration` (S15)
- **Wave**: Wave 3
- **Migration 予約**: `V106`
- **対象タスク**: T094 〜 T101

---

## 2. タスク実行・検証記録

| Task | Requirements | 変更ファイル | 実行Test | Demo / 検証結果 | Commit | リスク・未検証事項 (Release Gate) |
|---|---|---|---|---|---|---|
| **T094** | R1.1, R1.2, R1.3, 前提節, G4, G9 | `canonical-mapping.md`, `review-ledger.md` | L0 (静的検査) | Official Fixture / API 仕様確定、未確認マスタ確認状態整理 | — | 実 freee 契約プラン/company_id/本番マスタID は本番 Release Gate として管理 |
| **T095** | R1.1, R1.2, R1.3, R4.1, R4.2, R4.4, design §1, §6.1, §6.2, §6.3 | `V106__accounting_payment_integration.sql`, `V1__create_tables.sql`, `schema-accounting-integration-h2.sql`, `application-test.yml`, `ActionPermissionResolver.java`, `IntegrationConnection*`, `ExternalMapping*`, `IntegrationJob*` | L1〜L3 (`IntegrationConnectionAndJobTest` 5/5 PASS) | テナント/法人分離、AES-GCM暗号化、Token Race 1回更新、マッピング未検証ガード、Job CAS claim、冪等性 | — | — |
| **T096** | R1.1, R1.2, R1.3, R2.1, R2.2, R3.1, R4.1, R4.2, R4.3, R4.4, design §2, §6.1, §6.2, §6.3, platform-invariants §5.1, §7 | `Canonical*`, `AccountingProvider*`, `FreeeAccountingProvider.java`, `CsvAccountingExportProvider.java`, `FreeeAccountingProviderTest.java` | L2〜L3 (`FreeeAccountingProviderTest` 9/9 PASS) | 200/400/401/403/429/500/Timeout分類、金額不一致時failed、Validation非retry、秘密情報非ログ出力、CSV数式注入対策・正常負数 | — | — |
| **T097** | R1.1, R1.2, R1.3, R4.2, R4.3, design §4, §6.1, §6.2, platform-invariants §6 | `AccountingIntegrationPageController.java`, `AccountingIntegrationApiController.java`, `integration.html`, `accounting-integration.js`, `sidebar.html`, `messages*.properties`, `AccountingIntegrationApiAndPageTest.java` | L2〜L3 (`AccountingIntegrationApiAndPageTest` 6/6 PASS) | 財務permission(管理者/マネージャー可、営業/HR/要員403)、トークン非露出、マッピング未検証時送信ブロック、マッピングCRUD/verify、ジョブretry/cancel | — | — |
| **T098** | R2.1, R2.2, R4.1, R4.2, R4.4, design §4, §6.1, §6.3, platform-invariants §3.3 | `SalesInvoiceIntegrationService.java`, `SalesInvoiceIntegrationServiceImpl.java`, `AccountingIntegrationApiController.java`, `SalesInvoiceIntegrationTest.java` | L2〜L3 (`SalesInvoiceIntegrationTest` 5/5 PASS) | 10回同時実行冪等性(1Job)、freee取引登録/取消、金額不一致時FAILED判定、締め済み月更新拒否 | — | — |
| **T099** | R3.1, R3.2, R3.3, R3.4, R4.1, R4.2, design §4, §6.1, §6.3, G9, platform-invariants §3.3 | `PurchaseExpensePaymentIntegrationService.java`, `PurchaseExpensePaymentIntegrationServiceImpl.java`, `AccountingIntegrationApiController.java`, `PurchaseExpenseIntegrationTest.java` | L2〜L3 (`PurchaseExpenseIntegrationTest` 5/5 PASS) | 口座変更未承認時振込/連携ブロック、仕入登録、外部決済情報照合(ID+金額+日付一致で内部paid更新/不一致時拒否)、締め月保護 | — | — |
| **T100** | R3.3, R4.1, R4.2, R4.4, design §5, §6.1, §6.3, platform-invariants §3.3 | `AccountingReconciliationService.java`, `AccountingReconciliationServiceImpl.java`, `MonthlyClosingServiceImpl.java`, `AccountingIntegrationApiController.java`, `integration.html`, `accounting-integration.js`, `AccountingReconciliationTest.java` | L2〜L3 (`AccountingReconciliationTest` 4/4 PASS, `MonthlyClosingServiceImplTest` 12/12 PASS) | MATCHED/INTERNAL_ONLY/AMOUNT_MISMATCH/EXTERNAL_ONLY分類、外部のみ取引の内部自動作成禁止、理由付き除外設定と締め可否遷移、締め時ガード | — | — |
| **T101** | 全 requirements (R1.1〜R4.4), G4, platform-invariants §1〜§8 | `V106__accounting_payment_integration.sql`, `SpecDispatchConsistencyTest.java`, `design.md`, `tasks.md`, `review-ledger.md` | L4全量回帰 (`mvn test` 2365/2365 PASS, BUILD SUCCESS, skip 0) | 全量回帰・障害耐性・暗号化・セキュリティ・ロール権限・マイグレーション整合性検証完了 | — | — |

---

## 3. Decision Gate 記録
- **G4 (freee会計)**: 会計確定・総勘定元帳の SoR は freee、SES 業務明細の SoR は本システム。OAuth 2.0 API ＋ CSV フォールバック。
- **G9 (経費精算)**: 本システムで申請・承認、freee で会計確定（S14 で推奨既定確定済み）。
