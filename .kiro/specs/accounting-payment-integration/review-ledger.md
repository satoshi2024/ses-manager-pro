# Review Ledger — 会計・支払連携 (accounting-payment-integration / S15)

## 1. 概要・現行判定

- **Spec**: `accounting-payment-integration` (S15)
- **Wave**: Wave 3
- **Migration 正式採番**: `V106`（Consolidated baseline V1反映済み）および `V106.1`（`V106_1__accounting_integration_snapshot_and_slot.sql` による forward repair）。`V107` は S16 (`jp-pint-digital-invoice`) 予約済みのため使用しない。
- **現行総合判定**: **Stage B PASS (CONDITIONAL PASS on freee production release gate `GATE-S15-FREEE-PROD`)**
- **Stage A SpecHead Review Head**: `e0d8a96f` / **SpecHead Base**: `f8b81e77`
- **S15 独立再Review (FixHead `3ee44a9a`) 指摘**: P1 10件 + P2 1件 → 本Fixサイクルで全て是正し VERIFIED_CLOSED
- **対象タスク**: 歴史的タスク T094〜T101 / Stage B 是正タスク R4-T01〜R4-T08 / 再Review 是正 (R1-P1-02..11, R1-P1-04, R4-P1-01, R4-P2-02)

---

## 2. 歴史的タスク実行記録 (初期ベースライン)

| Task | Requirements | 変更ファイル | 実行Test | Demo / 検証結果 | Commit | リスク・未検証事項 (Release Gate) |
|---|---|---|---|---|---|---|
| **T094** | R1.1, R1.2, R1.3, 前提節, G4, G9 | `canonical-mapping.md`, `review-ledger.md` | L0 (静的検査) | Official Fixture / API 仕様確定 | — | 実 freee 契約プラン/company_id/本番マスタID は本番 Release Gate |
| **T095** | R1.1〜R1.3, R4.1, R4.2, R4.4, design §1, §6 | `V106__accounting_payment_integration.sql`, `V1__create_tables.sql`, `schema-accounting-integration-h2.sql`, `application-test.yml`, `ActionPermissionResolver.java`, `IntegrationConnection*`, `ExternalMapping*`, `IntegrationJob*` | `IntegrationConnectionAndJobTest` 5/5 | テナント/法人分離、AES-GCM、Token Race、マッピング未検証ガード、Job CAS claim、冪等性 | — | — |
| **T096** | R1.1〜R1.3, R2.1, R2.2, R3.1, R4.1〜R4.4, design §2, §6, platform-invariants §5.1, §7 | `Canonical*`, `AccountingProvider*`, `FreeeAccountingProvider.java`, `CsvAccountingExportProvider.java`, `FreeeAccountingProviderTest.java` | `FreeeAccountingProviderTest` 9/9 | 200/400/401/403/429/500/Timeout分類、PII非ログ、CSV数式注入対策 | — | — |
| **T097** | R1.1〜R1.3, R4.2, R4.3, design §4, §6, platform-invariants §6 | `AccountingIntegrationPageController.java`, `AccountingIntegrationApiController.java`, `integration.html`, `accounting-integration.js`, `sidebar.html`, `messages*.properties`, `AccountingIntegrationApiAndPageTest.java` | `AccountingIntegrationApiAndPageTest` 6/6 | 財務permission、トークン非露出、マッピング未検証時送信ブロック、ジョブretry/cancel | — | — |
| **T098** | R2.1, R2.2, R4.1, R4.2, R4.4, design §4, §6, platform-invariants §3.3 | `SalesInvoiceIntegrationService*`, `AccountingIntegrationApiController.java`, `SalesInvoiceIntegrationTest.java` | `SalesInvoiceIntegrationTest` 5/5 | 10回同時実行冪等性、金額不一致FAILED、締め済み月更新拒否 | — | — |
| **T099** | R3.1〜R3.4, R4.1, R4.2, design §4, §6, G9, platform-invariants §3.3 | `PurchaseExpensePaymentIntegrationService*`, `AccountingIntegrationApiController.java`, `PurchaseExpenseIntegrationTest.java` | `PurchaseExpenseIntegrationTest` 5/5 | 口座変更未承認時ブロック、仕入登録、決済情報照合(ID+金額+日付)、締め月保護 | — | — |
| **T100** | R3.3, R4.1, R4.2, R4.4, design §5, §6, platform-invariants §3.3 | `AccountingReconciliationService*`, `MonthlyClosingServiceImpl.java`, `AccountingIntegrationApiController.java`, `integration.html`, `accounting-integration.js`, `AccountingReconciliationTest.java` | `AccountingReconciliationTest` 4/4, `MonthlyClosingServiceImplTest` 12/12 | MATCHED/INTERNAL_ONLY/AMOUNT_MISMATCH/EXTERNAL_ONLY分類、除外設定と締め可否、締めガード | — | — |
| **T101** | 全 requirements, G4, platform-invariants §1〜§8 | `V106__accounting_payment_integration.sql`, `SpecDispatchConsistencyTest.java`, `AccountingIntegrationWorker.java`, `IntegrationJobServiceImpl.java`, `FreeeAccountingProvider.java`, `integration.html`, `messages*.properties`, `accounting-integration.js`, `design.md`, `tasks.md`, `review-ledger.md` | L4全量回帰 (`mvn test` 2366/2366 PASS, skip 0) | 全量回帰・障害耐性・暗号化・セキュリティ・ロール権限・マイグレーション整合性 | — | — |

---

## 3. Decision Gate 記録
- **G4 (freee会計)**: 会計確定・総勘定元帳の SoR は freee、SES 業務明細の SoR は本システム。OAuth 2.0 API ＋ CSV フォールバック。開発時は公式仕様準拠の `PROVISIONAL`、実契約プラン・実マスタIDは本番 Release Gate (`GATE-S15-FREEE-PROD`)。
- **G9 (経費精算)**: 本システムで申請・承認、freee で会計確定（S14 で推奨既定確定済み）。

---

## 4. S15 独立再Review 是正記録 (Base `3ee44a9a` → Fix Head)

S15 Stage B 独立再Review (`accounting-payment-integration-R1-P1-02..11`, `R4-P1-01`, `R4-P2-02`) の全指摘を是正。いずれも **VERIFIED_CLOSED**。

| Issue ID | 要件/設計 | 是正内容 | 検証テスト (全て実DB/実ChromeでPASS) |
|---|---|---|---|
| `accounting-payment-integration-R1-P1-02` | R4.3, design §3.2 | 結果反映を別 transactional coordinator bean (`SalesInvoiceTransactionCoordinator`) へ分離し、本番経路も Spring プロキシ経由で原子実行。補償ジョブ INSERT 失敗時にイベント・補償双方 rollback を実DBで検証 | `SalesInvoiceIntegrationTest#inFlightCancel_compensationJobFailure_rollsBackTransaction` |
| `accounting-payment-integration-R1-P1-03` | R1.4, design §4 | `getTokenSnapshot` を単一SELECT (暗号文+token_version) から復号する原子読取へ変更し、実際に Authorization へ使用した token の version を 401 CAS へ渡す。未知結果照合は `ref_number + amount + company_id` の3項目完全一致時のみ成功 (fail-closed)。stale connection object テスト・金額/company 不一致テストを追加 | `FreeeAccountingProviderTest#unauthorized401_usesSnapshotVersion_notStaleObjectVersion`, `unknownOutcome_strictMatch_failClosedOnAmountOrCompanyMismatch`, `unknownOutcome_verifyDealCreatedByRefNumber_pagination` |
| `accounting-payment-integration-R1-P1-04` | R1.1, design §1.2 | `sql/runbook/v106_1-rollback.sql` (information_schema ガード付き procedure runbook) を新規作成。`FlywayV106_1RollbackAndRepairSmokeTest` を実MySQLで書き換え: fresh 拒否 SIGNAL / V106 legacy 形状 (旧UNIQUE+NULL法人重複2件) → V106.1 backfill → runbook 実行による全11列 DROP・旧UNIQUE復元・全行復元・backup 削除・history 掃除 → partial 中断点 (新UNIQUE前で中断) → repair 再適用の5形状を検証。shard-3 に登録済み | `FlywayV106_1RollbackAndRepairSmokeTest` (mysql tag, 実MySQL 8) |
| `accounting-payment-integration-R1-P1-06` | R5.4, design §5.1, §5.2 | 全 consumer を scope-aware 化: ジョブ list/detail/retry/cancel に tenant predicate + 空集合 `1=0`、connections/mappings を許可法人・許可接続で SQL フィルタ、preview (売上/BP/経費) を組織導出 SQL (cost_center 優先 + work_record→contract→sales_user→t_user_organization asOf、経費は accounting_history asOf で UNKNOWN fail-closed) による最初のSQL適用、reconciliation 4母集団を同一述語の scoped mapper query へ統一。same-org cross-tenant / 非空 allowed set で他組織・実在する権限外 detail 404 / retry・cancel / reconciliation を MockMvc で検証 | `AccountingIntegrationApiAndPageTest#managerScope_nonEmptyAllowed_otherOrgAndCrossTenantHidden`, `#jobRetryAndCancel` (他テナント 404) |
| `accounting-payment-integration-R1-P1-07` | R2.2, R2.3, design §6.1 | 全5 job type (売上/取消/BP/経費/支払) の Worker で `payload_snapshot` 必須 (NULL → `LEGACY_SNAPSHOT_MISSING` fail-closed)・`SHA-256(payload_snapshot) == payload_hash` 再検証 (改変 → `PAYLOAD_HASH_MISMATCH`)・業務テーブル再読込全廃を共通化。支払 Worker は snapshot の `externalDealId`/`expectedAmount` のみ使用 (最新 purchase job 再検索廃止) を実DBで検証 | `SalesInvoiceIntegrationTest#workers_snapshotRequiredAndHashVerified`, `PurchaseExpenseIntegrationTest#worker_snapshotRequiredAndHashVerified`, `#paymentWorker_usesSnapshotOnly_notLatestJob` |
| `accounting-payment-integration-R1-P1-08` | R3.2〜R3.4, design §6.1 | `AccountingTenantContextHolder` に zone を追加し `runWithTenant(tenantId, zoneId, runnable)` を try-finally 保証。Worker/Scheduler へ `AccountingTimezoneResolver.resolve(tenantId)` を接続し、非 default テナント処理後の ThreadLocal リークなしを検証。BP 業務日付は `work_month` 末日固定 (createdAt/固定値 fallback 廃止、NULL workMonth は 400)、NULL 金額/日付は enqueue 時に fail-closed (専用 spy テスト)、経費 CAS 失敗コードは `CAS_CONFLICT`。月次照合の既定月はテナント zone 基準 | `PurchaseExpenseIntegrationTest#tenantTimezoneResolution_contextClearedAfterWorker_nullRejected`, `AccountingNullGuardTest`, `PurchaseExpenseIntegrationTest#bpPurchase_deterministicBusinessDate_and_expenseCas` |
| `accounting-payment-integration-R1-P1-09` | R5.1〜R5.3, design §6.2 | `fetchPayments` を `PaymentFetchResult {payments, pageCapReached, duplicateDealId, fetchFailed, errorCode}` へ変更し50ページ上限・重複ID・途中障害を fail-closed 通知。SUCCEEDED ジョブの外部 deal 不存在は `INTERNAL_ONLY` (外部金額コピー廃止)。入金は金額+日付で未消費の外部決済を 1:1 消費 (重複候補は `PAYMENT_AMBIGUOUS`)。101件目 (offset=100)・50ページ上限・重複ID・外部削除を実DBで検証 | `AccountingReconciliationTest#reconciliation_pagination_secondPageMatched`, `#reconciliation_duplicateDealId_failClosed`, `#reconciliation_pageCap50Pages_failClosed`, `#reconciliation_succeededJobExternalDealDeleted_internalOnly` |
| `accounting-payment-integration-R1-P1-10` | R4.5, design §7 | 外部例外分類を `handleApiException` へ集約し、raw body / `e.getMessage()` を一切ログ・DTO・job へ渡さない (固定文言 + 定型 error code のみ)。fetchDealPayment / validateConnection / verifyMaster / fetchPayments / リフレッシュ失敗の各経路から raw message を除去し、ログキャプチャテストで PII 不在を検証 | `FreeeAccountingProviderTest#errorHandling_sanitizedCodesAndNoPii`, `#secretLogCapture_tokenNeverLogged` |
| `accounting-payment-integration-R1-P1-11` | R6, design §8 | `messages*.properties` (ja/en/zh/ko) を単一翻訳源とし、`integration.html` の `#i18n-data` コンテナ (Thymeleaf 解決済み data 属性) → JS `t(key)` で全可視文言をローカライズ。job detail の残存日本語・プレビュー列・空表示を t() 化。取消理由は機械可読コード (`REASON_CLIENT_CANCEL` 既定、未知入力は `REASON_OTHER` へ正規化) を保存・送信。HTML の div 構造破壊を修復。CookieLocaleResolver 経由で各ロケールの具体的文言と DB reason code を assert | `AccountingIntegrationApiAndPageTest#i18n_fourLanguages_stableReasonCodes` |
| `accounting-payment-integration-R4-P1-01` | handbook §8/§12, R4-T08 | Browser Demo (`AccountingIntegrationBrowserDemoTest`) を置換: 実データ描画 (ジョブ5件・マッピング行・接続カード)・4母集団 MATCHED + 締可 badge・freee stub による 401 自動復旧 (OAuth 更新→リプレイ)・マネージャー境界 (自組織ジョブ可視/全社共通ジョブ不可視/照合 要確認)・desktop/390px で console error 0 を DOM アサート。`@Tag("browser")` を除去し fast suite (L4) で常時実行。同一 Head の L4 (`mvn test` 2411/2411 PASS, skip 0) を実施し ledger 整合 | `AccountingIntegrationBrowserDemoTest` + `evidence/browser/*` (desktop-01..06, mobile390-01..05, summary.json) |
| `accounting-payment-integration-R4-P2-02` | shared-standards 差分最小化 | `pom.xml` の Lombok scope (`optional`) と maven-compiler-plugin/annotationProcessor 設定を base (`a82f51c9`) 状態へ復元 (S15 差分から除外)。`mvn clean test` 相当の全 compile 回帰を L4 で確認済み | L4 fast suite 2411/2411 |

---

## 5. 是正サイクルの実行テスト記録 (Fix Head)

| ゲート | 結果 |
|---|---|
| Direct regression (accounting/integration 8クラス) | 76/76 PASS (0 fail / 0 error / 0 skip) |
| Browser Demo (実Chrome desktop+390px+manager) | 1/1 PASS (console error 0, 4母集団締可, 401復旧, マネージャー境界) |
| MySQL (V106.1 rollback/repair 5形状, 実MySQL 8) | 1/1 PASS |
| L4 fast suite (`mvn test`) | **2411/2411 PASS (0 fail / 0 error / 0 skip), BUILD SUCCESS** |
| shard inventory | `FlywayV106_1RollbackAndRepairSmokeTest` を mysql-shard-3.txt へ登録済み |

- **Base commit**: `3ee44a9a` (S15 Stage B 前回 FixHead)
- **Fix Head**: 本サイクルの commit (下記 Commit 欄)
- **Rollback 手順**: 本変更は feature フラグなしのロジック修正。`git revert <fix head>` で base へ復帰。DB マイグレーション変更なし (V106/V106.1 不変)。V106.1 適用済み環境のロールバックは `sql/runbook/v106_1-rollback.sql` を実行。

---

## 6. 未検証環境・本番前条件 (Release Gate)

- **`GATE-S15-FREEE-PROD`**: 実 freee 契約プラン、本番 company_id、本番 OAuth クライアント認証情報、本番実マスタID への接続は本番前条件として分離管理。CI 上での完了判定は **`CONDITIONAL PASS`**。
- 実2 JVM による 401 競合の最終確認は本番相当環境でのリリース前検証とする (3段階リース・CAS は単体/結合で検証済み)。
