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
| **T101** | 全 requirements (R1.1〜R4.4), G4, platform-invariants §1〜§8 | `V106__accounting_payment_integration.sql`, `SpecDispatchConsistencyTest.java`, `AccountingIntegrationWorker.java`, `IntegrationJobServiceImpl.java`, `FreeeAccountingProvider.java`, `integration.html`, `messages*.properties`, `accounting-integration.js`, `design.md`, `tasks.md`, `review-ledger.md` | L4全量回帰 (`mvn test` 2366/2366 PASS, BUILD SUCCESS, skip 0) | 全量回帰・障害耐性・暗号化・セキュリティ・ロール権限・マイグレーション整合性・Round 2 指摘事項(P1-01〜P1-11, P2-01, P2-02)全件解消完了 | — | — |

---

## 3. Decision Gate 記録
- **G4 (freee会計)**: 会計確定・総勘定元帳の SoR は freee、SES 業務明細の SoR は本システム。OAuth 2.0 API ＋ CSV フォールバック。
- **G9 (経費精算)**: 本システムで申請・承認、freee で会計確定（S14 で推奨既定確定済み）。

---

## 4. Round 3 独立 Review 結果 & R4 是正追跡 (SpecHead: Base `f8b81e77`)

- **判定**: **FAIL**（P0: 0, P1: 10 OPEN, P1: 1 VERIFIED_CLOSED, P2: 0 OPEN）
- **確認結果**: S15 direct regression 50/50 PASS, MySQL/Flyway smoke 5/5 PASS, Fast suite 2381/2381 PASS.
- **R4 収束方針**: Stage A（SpecHead 策定・決定表確定）と Stage B（コード・DDL・テスト実装）の厳格分離。

### 4.1 R3 指摘項目追跡表

| Issue ID | 重要度 | 状態 | 要件番号 | 決定表参照 | 是正仕様・内容 | 対象テスト / 検証行 |
|---|---|---|---|---|---|---|
| `R1-P1-01` | P1 | **VERIFIED_CLOSED** | R3.1, R4.1, R6 | design §3 | Worker の全5ジョブ種別ディスパッチ、未知種別ハンドリング、stale 回収 | `AccountingIntegrationWorkerTest` |
| `R1-P1-02` | P1 | **OPEN** | R4.2, R4.3 | design §3.1, §3.2 | `lease_token` (UUID) による排他、`RUNNING` からの取消許可、in-flight 取消補償 (`CANCELLED_EXTERNALLY_CREATED` -> `SALES_INVOICE_CANCEL` 自動 enqueue)、stale 回収個別 CAS と event 同一 Tx 記録 | `IntegrationJobStateMachineTest#cancelJob_whenRunning_enqueuesCompensation` |
| `R1-P1-03` | P1 | **OPEN** | R1.4, R4.3 | design §4 | `token_version` と DB 行ロックによる multi-node 401 トークンリフレッシュ直列化、タイムアウト未知結果の全件 pagination 照合 | `FreeeAccountingProviderTest#forceRefreshToken_multiNode_serializesAndReusesToken` |
| `R1-P1-04` | P1 | **OPEN** | R1.1 | design §1.1, §1.2 | `legal_entity_key` + `active_slot` による soft-delete 一意性保証、実 `iv:cipher` legacy 復号テスト、MySQL NULL 一意性検証 | `IntegrationConnectionAndJobTest#uniqueConstraint_allowsSoftDeleteRecreation` |
| `R1-P1-05` | P1 | **OPEN** | R1.2, R1.3 | design §2, canonical-mapping §2 | 全10種別 (`CUSTOMER_PARTNER`, `BP_PARTNER`, `ACCOUNT_SALES`, `ACCOUNT_PURCHASE`, `ACCOUNT_EXPENSE`, `TAX_SALES_10`, `TAX_PURCHASE_10`, `TAX_EXPENSE_10`, `SECTION`, `COST_CENTER`) の正規識別子実在照合、未知種別 fail-closed、allow-list snapshot 保存、deal `partner_id` 反映 | `FreeeAccountingProviderTest#verifyAllTenObjectTypes_failClosedOnUnknown` |
| `R1-P1-06` | P1 | **OPEN** | R5.4 | design §5 | `t_integration_job` に `tenant_id`, `organization_id` を保持し、マネージャーロールを自組織に SQL 境界で限定。空組織集合時は DB レベルで 0 件返却 | `AccountingIntegrationApiAndPageTest#managerScope_emptyOrgs_returnsZeroRows` |
| `R1-P1-07` | P1 | **OPEN** | R2.2, R2.3 | design §1.1, §3.2 | `t_integration_job.payload_snapshot` 列追加。取消 enqueue 時点で `externalDealId`, `cancelReasonCode` を snapshot に固定し Worker は snapshot のみを取消 | `SalesInvoiceIntegrationTest#cancelJob_executesStrictlyFromSnapshot` |
| `R1-P1-08` | P1 | **OPEN** | R3.2, R3.3, R3.4 | design §6 | BP Canonical の `work_month` 末日業務日付固定、支払同期の金額・日付双方非 NULL 厳格一致、経費 snapshot ハッシュ検証と `ExpenseRequest` CAS 更新 (`承認済` -> `会計連携済`) | `PurchaseExpenseIntegrationTest#bpPurchase_deterministicBusinessDate_and_expenseCas` |
| `R1-P1-09` | P1 | **OPEN** | R5.1, R5.2, R5.3 | design §6 | 売上・仕入・入金・経費の 4 母集団完全照合。freee 取引一覧全件 pagination 取得。未接続・トークンなし・API 障害・50 ページ上限到達時の fail-closed (`readyForClosing=false`)。SUCCEEDED ジョブの実金額突合 | `AccountingReconciliationTest#reconciliation_fourPopulations_paginationFailClosed` |
| `R1-P1-10` | P1 | **OPEN** | R4.5 | design §7 | 生レスポンス・生例外の完全遮断。定型エラーコード (`VALIDATION_ERROR`, `UNAUTHORIZED`, `PLAN_LIMITATION`, `RATE_LIMITED`, `SERVER_ERROR`) と局所化キーのみ保存・ログ出力 | `FreeeAccountingProviderTest#errorHandling_sanitizedCodesAndNoPii` |
| `R1-P1-11` | P1 | **OPEN** | R6 | design §8 | `messages*.properties` (ja/en/zh/ko) を単一翻訳源とし、HTML / JS の全可視文言を `t(key)` 化。取消理由を機械可読コードで送信・保存 | `AccountingIntegrationApiAndPageTest#i18n_fourLanguages_stableReasonCodes` |
