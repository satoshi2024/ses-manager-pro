# Review Ledger — 会計・支払連携 (accounting-payment-integration / S15)

## 1. 概要・現行判定

- **Spec**: `accounting-payment-integration` (S15)
- **Wave**: Wave 3
- **Migration 正式採番**: `V106`（Consolidated baseline V1反映済み）および `V106.1`（forward repair）
  - ※ `V107` は S16 (`jp-pint-digital-invoice`) 予約済みのため使用しない。
- **現行総合判定**: **Stage A SpecHead FAIL / Stage B 未着手 (P0: 0, P1: 8 OPEN, P2: 1 OPEN)**
- **SpecHead Base**: `f8b81e77`
- **対象タスク**: 歴史的タスク T094〜T101 / Stage B 是正タスク R4-T01〜R4-T08

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
- **G4 (freee会計)**: 会計確定・総勘定元帳の SoR は freee、SES 業務明細の SoR は本システム。OAuth 2.0 API ＋ CSV フォールバック。
- **G9 (経費精算)**: 本システムで申請・承認、freee で会計確定（S14 で推奨既定確定済み）。

---

## 4. Round 3/4 独立 Review 結果 & R4 是正追跡 (SpecHead: Base `f8b81e77`)

- **現行判定**: **Stage A SpecHead FAIL / Stage B 未着手**（P0: 0, P1: 8 OPEN, P2: 1 OPEN）
- **確認結果**: S15 direct regression 50/50 PASS, MySQL/Flyway smoke 5/5 PASS, Fast suite 2381/2381 PASS (コード実装済み、Stage A Spec 確定後に Stage B 実装・検証へ進む)。

### 4.1 OPEN 指摘項目追跡表

| Issue ID | 重要度 | 状態 | 要件番号 | 決定表参照 | 是正仕様・内容 | 対象タスク / 検証行 |
|---|---|---|---|---|---|---|
| `accounting-payment-integration-R1-P1-01` | P1 | **VERIFIED_CLOSED** | R3.1, R4.1, R6 | design §3 | Worker の全5ジョブ種別ディスパッチ、未知種別ハンドリング、stale 回収 | `AccountingIntegrationWorkerTest` |
| `accounting-payment-integration-R1-P1-02` | P1 | **OPEN** | R4.2, R4.3 | design §3.1, §3.2 | `requirements.md` と `design.md` の取消権限完全同期（売上・入金のみ RUNNING 取消許可、BP・経費は 400 拒否）。in-flight 取消時の `CANCELLED_EXTERNALLY_CREATED` event 記録 + 補償 `SALES_INVOICE_CANCEL` enqueue の同一 Tx 原子実行。stale 回収個別 CAS と event 同一 Tx 記録 | `R4-T05` (`SalesInvoiceIntegrationTest#cancelJob_inFlightAtomicCompensation`) |
| `accounting-payment-integration-R1-P1-03` | P1 | **OPEN** | R1.4, R4.1, R4.3 | design §4 | DB トランザクション外で HTTP を呼ぶ 3段階リース・CAS 状態機械による 401 トークンリフレッシュ直列化。タイムアウト未知結果の全件 pagination (最大50ページ) 照合 | `R4-T02` (`FreeeAccountingProviderTest#forceRefreshToken_multiNode_3StepLease_httpOutsideTx`) |
| `accounting-payment-integration-R1-P1-04` | P1 | **OPEN** | R1.1 | design §1.1, §1.2 | S16予約衝突を回避した `V106.1` forward migration。`legal_entity_key` + `active_slot` による soft-delete 一意性保証、実 `iv:cipher` legacy 復号テスト、重複レコード退避 (`m_integration_connection_backup_v106_1`) とロールバック手順 | `R4-T01` (`IntegrationConnectionAndJobTest#uniqueConstraint_allowsSoftDeleteRecreation`) |
| `accounting-payment-integration-R1-P1-05` | P1 | **OPEN** | R1.2, R1.3 | design §2, canonical-mapping §2 | 全10種別 (`CUSTOMER_PARTNER`, `BP_PARTNER`, `ACCOUNT_SALES`, `ACCOUNT_PURCHASE`, `ACCOUNT_EXPENSE`, `TAX_SALES_10`, `TAX_PURCHASE_10`, `TAX_EXPENSE_10`, `SECTION`, `COST_CENTER`) の正規識別子実在照合（税区分は数値 `tax_code: 34`/`21`、取引先/勘定科目/部門は数値 `id`）、未知種別 fail-closed、allow-list snapshot 保存、deal `partner_id` 反映 | `R4-T03` (`FreeeAccountingProviderTest#verifyAllTenObjectTypes_failClosedOnUnknown`) |
| `accounting-payment-integration-R1-P1-06` | P1 | **OPEN** | R5.4 | design §5 | Consumer Inventory 全機能（list/detail/count/export/preview/reconciliation/notification/retry/cancel）に SQL スコープを適用。マネージャーは自組織のみ、空組織集合時は DB レベルで 0 件返却 | `R4-T04` (`AccountingIntegrationApiAndPageTest#managerScope_emptyOrgs_returnsZeroRows`) |
| `accounting-payment-integration-R1-P1-07` | P1 | **OPEN** | R2.2, R2.3 | design §1.1, §3.2 | `t_integration_job.payload_snapshot` 列追加。取消 enqueue 時点で `externalDealId`, `cancelReasonCode` を snapshot に固定し Worker は snapshot のみを取消 | `R4-T05` (`SalesInvoiceIntegrationTest#cancelJob_executesStrictlyFromSnapshot`) |
| `accounting-payment-integration-R1-P1-08` | P1 | **OPEN** | R3.2, R3.3, R3.4 | design §6.1 | 時間・asOf 決定表（§6.1）に基づく JST 業務日付固定（BP Canonical の `work_month` 末日固定）、支払同期の金額・日付双方非 NULL 厳格一致、経費 snapshot ハッシュ検証と `ExpenseRequest` CAS 更新 (`承認済` -> `会計連携済`) | `R4-T06` (`PurchaseExpenseIntegrationTest#bpPurchase_deterministicBusinessDate_and_expenseCas`) |
| `accounting-payment-integration-R1-P1-09` | P1 | **OPEN** | R5.1, R5.2, R5.3 | design §6.2 | 売上・仕入・入金・経費の 4 母集団完全照合。入金は `{externalDealId}:{paymentId}` / 決済金額突合。freee 取引一覧全件 pagination 取得。未接続・トークンなし・API 障害・50 ページ上限到達時の fail-closed (`readyForClosing=false`)。SUCCEEDED ジョブの実金額突合 | `R4-T07` (`AccountingReconciliationTest#reconciliation_fourPopulations_paginationFailClosed`) |
| `accounting-payment-integration-R1-P1-10` | P1 | **OPEN** | R4.5 | design §7 | 生レスポンス・生例外の完全遮断。定型エラーコード (`VALIDATION_ERROR`, `UNAUTHORIZED`, `PLAN_LIMITATION`, `RATE_LIMITED`, `SERVER_ERROR`) と局所化キーのみ保存・ログ出力 | `R4-T03` (`FreeeAccountingProviderTest#errorHandling_sanitizedCodesAndNoPii`) |
| `accounting-payment-integration-R1-P1-11` | P1 | **OPEN** | R6 | design §8 | `messages*.properties` (ja/en/zh/ko) を単一翻訳源とし、HTML / JS の全可視文言を `t(key)` 化。取消理由を機械可読コードで送信・保存 | `R4-T04` (`AccountingIntegrationApiAndPageTest#i18n_fourLanguages_stableReasonCodes`) |
| `accounting-payment-integration-R4-P1-01` | P1 | **OPEN** | handbook §8, §12 | tasks §1, §2 | 歴史的タスク（T094〜T101）と未実装の Stage B 是正タスク（R4-T01〜R4-T08, 全て `[ ]`）を明確に分離し、タスク台帳の不整合を解消 | `tasks.md`, `review-ledger.md`, `spec-execution-ledger.md` |
| `accounting-payment-integration-R4-P2-01` | P2 | **OPEN** | shared-standards | decision-log, canonical-mapping | `decision-log.md` のテンプレート code fence を閉じ、`canonical-mapping.md` の用語を「multi-node 3段階リース・CAS Token Refresh」に統一 | `decision-log.md`, `canonical-mapping.md` |
