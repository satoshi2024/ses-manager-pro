# Review Ledger — 会計・支払連携 (accounting-payment-integration / S15)

## 1. 概要・現行判定

- **Spec**: `accounting-payment-integration` (S15)
- **Wave**: Wave 3
- **Migration 正式採番**: `V106`（Consolidated baseline V1反映済み）および `V106.1`（`V106_1__accounting_integration_snapshot_and_slot.sql` による forward repair）
  - ※ `V107` は S16 (`jp-pint-digital-invoice`) 予約済みのため使用しない。
- **現行総合判定**: **Stage B（R4-T01〜R4-T08）実装・検証完了 → Stage B Review申請**
- **Stage A SpecHead Review Head**: `e0d8a96f`
- **SpecHead Base**: `f8b81e77`
- **対象タスク**: 歴史的タスク T094〜T101 / Stage B 是正タスク R4-T01〜R4-T08（全件実装・検証完了）

---

## 2. 歴史的タスク実行記録 (初期ベースライン)

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
- **G4 (freee会計)**: 会計確定・総勘定元帳の SoR は freee、SES 業務明細の SoR は本システム。OAuth 2.0 API ＋ CSV フォールバック。開発時は公式仕様準拠の `PROVISIONAL`、実契約プラン・実マスタIDは本番 Release Gate (`GATE-S15-FREEE-PROD`)。
- **G9 (経費精算)**: 本システムで申請・承認、freee で会計確定（S14 で推奨既定確定済み）。

---

## 4. 指摘項目追跡表 (SpecHead Revision 6 / Base `f8b81e77`)

### 4.1 指摘項目一覧と是正状況

| Issue ID | 重要度 | 状態 | 要件番号 | 決定表参照 | 是正仕様・内容 | 対象タスク / 検証行 |
|---|---|---|---|---|---|---|
| `accounting-payment-integration-R1-P1-01` | P1 | **VERIFIED_CLOSED** | R3.1, R4.1, R6 | design §3 | Worker の全5ジョブ種別ディスパッチ、未知種別ハンドリング、stale 回収 | `AccountingIntegrationWorkerTest` |
| `accounting-payment-integration-R4-P2-01` | P2 | **VERIFIED_CLOSED** | shared-standards | decision-log, canonical-mapping | `decision-log.md` のテンプレート code fence 閉鎖、用語統一 | `decision-log.md`, `canonical-mapping.md` |
| `accounting-payment-integration-R1-P1-02` | P1 | **VERIFIED_CLOSED** | R4.2, R4.3 | design §3.1, §3.2 | Table 3 に種別×状態完全マトリクスを統合。`SALES_INVOICE_CANCEL` は全状態で取消拒否 (400 `CANNOT_CANCEL_CANCELLATION_JOB`)。BP・経費の RUNNING 取消は 400 拒否。売上・入金の RUNNING 取消許可。in-flight 補償ジョブ enqueue 失敗時は同一 Tx で全 rollback | `R4-T05` (`SalesInvoiceIntegrationTest#cancelJob_inFlightAtomicCompensation`, `IntegrationConnectionAndJobTest`) |
| `accounting-payment-integration-R1-P1-03` | P1 | **VERIFIED_CLOSED** | R1.4, R4.1, R4.3 | design §4 | 敗者ノードの最終動作定義（3回待機後 `TokenRefreshInProgressException` 送出と Job の `RETRYABLE(5s)` 遷移）。テスト条件の完全分離（11s WireMock Read Timeout 試験 vs CAS Fencing 試験 vs 9s 遅延敗者リトライ試験）。Deal 作成タイムアウト未知結果の 50 ページ pagination 走査 (`verifyDealCreatedByRefNumber`) | `R4-T02` (`FreeeAccountingProviderTest#forceRefreshToken_multiNode_3StepLease_httpOutsideTx`, `IntegrationConnectionAndJobTest`) |
| `accounting-payment-integration-R1-P1-05` | P1 | **VERIFIED_CLOSED** | R1.2, R1.3 | design §2, canonical-mapping §2 | freee Developer Reference 公式 URL (`https://developer.freee.co.jp/reference/accounting/reference`)・2026-07 更新情報、および `src/test/resources/fixtures/accounting/freee/` の固定 contract fixture パス・SHA-256 契約を明記。CI 上での完了判定を **`CONDITIONAL PASS`** とし、実 freee 環境接続を本番 Release Gate (`GATE-S15-FREEE-PROD`) として分離管理 | `R4-T03` (`FreeeAccountingProviderTest#verifyAllTenObjectTypes_failClosedOnUnknown`) |
| `accounting-payment-integration-R1-P1-06` | P1 | **VERIFIED_CLOSED** | R5.4 | design §5 | 経費ジョブの `t_engineer_accounting_history`（`asOf = expense_date`）照合および `organization_history_status = 'UNKNOWN'` 時の fail-closed（現在値フォールバック禁止）。売上・BP の `t_user_organization` 履歴照合。Consumer Inventory 全機能に SQL スコープを厳格適用 | `R4-T04` (`AccountingIntegrationApiAndPageTest#managerScope_emptyOrgs_returnsZeroRows`, `AccountingOrganizationResolver`) |
| `accounting-payment-integration-R1-P1-08` | P1 | **VERIFIED_CLOSED** | R3.2, R3.3, R3.4 | design §6.1 | `m_system_config` のキー `accounting.timezone.{tenantId}`（未設定時は `accounting.timezone`、不正時は `Asia/Tokyo` フォールバック）を解決する `AccountingTimezoneResolver.resolve(tenantId)` の明記、`AccountingTenantContextHolder` による try-finally コンテキスト管理 | `R4-T06` (`PurchaseExpenseIntegrationTest#bpPurchase_deterministicBusinessDate_and_expenseCas`, `AccountingTimezoneResolver`) |
| `accounting-payment-integration-R1-P1-09` | P1 | **VERIFIED_CLOSED** | R5.1, R5.2, R5.3 | design §6.2 | 売上・仕入・入金・経費の 4 母集団完全照合。入金は `{externalDealId}:{paymentId}` および `amount + fee` 振込手数料込み総消込突合。同日同額曖昧時は `PAYMENT_AMBIGUOUS` で fail-closed。50 ページ上限到達時 `readyForClosing=false` | `R4-T07` (`AccountingReconciliationTest#reconciliation_fourPopulations_paginationFailClosed`, `AccountingReconciliationServiceImpl`) |
| `accounting-payment-integration-R4-P1-01` | P1 | **VERIFIED_CLOSED** | handbook §8, §12 | tasks §1, §2 | 歴史的タスク（T094〜T101, `[x]`）と Stage B 是正タスク（R4-T01〜R4-T08, 全て `[x]` かつ詳細 Demo / CONDITIONAL PASS 条件定義付き）を明確に分離し、台帳整合を完了 | `tasks.md`, `review-ledger.md`, `spec-execution-ledger.md` |
| `accounting-payment-integration-R1-P1-04` | P1 | **VERIFIED_CLOSED** | R1.1 | design §1.1, §1.2 | 新 UNIQUE 先行削除 → 退避行 UPDATE 復元 → 旧 UNIQUE 復元 → 全 11 追加列の独立存在判定 DROP → backup 後置削除。direct MySQL regression 契約（NULL 法人重複 2 件 apply→rollback→全行復元、任意 partial 形状、V106 形状完全一致、repair→再適用）を確定 | `R4-T01` (`V106_1__accounting_integration_snapshot_and_slot.sql`, `IntegrationConnectionAndJobTest`) |
| `accounting-payment-integration-R1-P1-07` | P1 | **VERIFIED_CLOSED** | R2.2, R2.3 | design §1.1, §3.2 | `t_integration_job.payload_snapshot` 列追加。取消 enqueue 時点で `externalDealId`, `cancelReasonCode` を snapshot に固定し Worker は snapshot のみを取消 | `R4-T05` (`SalesInvoiceIntegrationTest#cancelJob_executesStrictlyFromSnapshot`, `SalesInvoiceIntegrationServiceImpl`) |
| `accounting-payment-integration-R1-P1-10` | P1 | **VERIFIED_CLOSED** | R4.5 | design §7 | 生レスポンス・生例外の完全遮断。定型エラーコード (`VALIDATION_ERROR`, `UNAUTHORIZED`, `PLAN_LIMITATION`, `RATE_LIMITED`, `SERVER_ERROR`) と局所化キーのみ保存・ログ出力 | `R4-T03` (`FreeeAccountingProviderTest#errorHandling_sanitizedCodesAndNoPii`) |
| `accounting-payment-integration-R1-P1-11` | P1 | **VERIFIED_CLOSED** | R6 | design §8 | `messages*.properties` (ja/en/zh/ko) を単一翻訳源とし、HTML / JS の全可視文言を `t(key)` 化。取消理由を機械可読コードで送信・保存 | `R4-T04` (`AccountingIntegrationApiAndPageTest#i18n_fourLanguages_stableReasonCodes`, `messages*.properties`) |
