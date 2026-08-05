# Review Ledger — order-acceptance-workflow (S09)

本ledgerは `review-ledger-template.md` v2.0に従い、T054〜T059の実装証跡をappend-onlyで記録する。
現行判定は本ファイル先頭の「現行判定」表が唯一の正。

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | order-acceptance-workflow |
| handbook | v2.0 |
| state | IN PROGRESS |
| base | f523e11（main / origin/main 一致） |
| head | （T054 commit後に更新） |
| merge | unmerged |
| latest review | —（未開始） |
| verdict | — |
| issue count | — |
| next action | T055 F2 見積→注文→契約 |

## 2. OPEN Issue Register

（現時点なし）

## 3. Closed/Deferred Issue

（なし）

## 4. 最新Review Packet

（Review開始時に記入。T059 M完了後に確定）

## 5. Requirements Trace

| requirement/AC | implementation | automatic test | Demo | verdict |
|---|---|---|---|---|
| R1.1〜R1.5 注文/状態機械 | T054〜T056 | OrderAcceptanceSchemaTest / SalesOrderServiceImplTest | T056で実施 | 実装中 |
| R2.1〜R2.4 見積→注文→契約 | T055 | （T055で追加） | T055で実施 | 実装中 |
| R3.1〜R3.5 月次検収 | T054/T057 | OrderAcceptanceSchemaTest | T057で実施 | 実装中 |
| R4.1〜R4.3 通知/KPI | T058 | （T058で追加） | T058で実施 | 実装中 |
| R5 受入 | T054〜T059 | 各task | T059で実施 | 実装中 |

## 6. T054 F1 注文/明細/検収DDL — 記録（2026-08-05）

- **task**: T054 F1
- **requirements**: R1.1〜R1.5（DDL部分）、R3.1、R5（UNIQUE/NOT NULL）
- **変更file**:
  - `src/main/resources/db/migration/V80__order_acceptance_workflow.sql`（新規）
  - `src/main/resources/db/migration/V1__create_tables.sql`（baseline同期）
  - `src/test/resources/sql/schema-order-acceptance-h2.sql`（新規・H2 replay）
  - `src/test/resources/application-test.yml`（schema-locations追加）
  - `src/test/resources/sql/engineer-schema-h2.sql`（t_contract列・新テーブル同期）
  - `src/main/java/com/ses/entity/{SalesOrder,SalesOrderLine,Acceptance}.java`（新規）
  - `src/main/java/com/ses/entity/Contract.java`（orderLineId / acceptanceRequired）
  - `src/main/java/com/ses/mapper/{SalesOrderMapper,SalesOrderLineMapper,AcceptanceMapper}.java`（新規）
  - `src/main/java/com/ses/common/constant/StatusConstants.java`（注文/検収状態）
  - `src/main/java/com/ses/service/SalesOrderService.java` + `impl/SalesOrderServiceImpl.java`（採番・状態機械）
  - `src/main/java/com/ses/service/security/ActionPermissionResolver.java`（sales-orders/acceptances）
  - `src/main/java/com/ses/service/ContractService.java` + `impl/ContractServiceImpl.java`（orderLineId引継ぎ）
  - `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java`（V80 assert）
  - `src/test/java/com/ses/order/{OrderAcceptanceSchemaTest,SalesOrderServiceImplTest}.java`（新規）
- **DDL/H2/MySQL同期**: V1統合baseline + V80増分（information_schema guard付きADD）+ H2 `schema-order-acceptance-h2.sql` + `engineer-schema-h2.sql` + MySQL smoke assert を同一taskで同期。
- **test**: `OrderAcceptanceSchemaTest` 5/0/0、`SalesOrderServiceImplTest` 5/0/0、`MigrationScriptIntegrityTest` 26/0/0。直接回帰（L3）: ActionPermissionResolverTest/MessageBundleConsistencyTest/NotificationLinkRouteTest/MobileResponsiveLayoutTest/MenuPermissionFilterTest/RoleNavigationVisibilityTest/GlobalControllerAdvicePermissionTest/CsrfProtectionTest 55/0/0。Docker必須のFlyway実MySQL smokeはCI/Mで実行（ローカル自動skip）。
- **Demo**: 未実施（A1画面実装後のT056で実施）。F1の状態遷移・UNIQUE・NOT NULLは自動testで検証済み。
- **commit**: （T054 commit hashを記入）
- **risk/備考**:
  - t_acceptance の「work record version」は t_work_record にversion列が無いため、`work_record_updated_at`（DATETIME snapshot）で実装（design §5.1の意図を充足。後続B1で差戻し→再提出時に再snapshot）。
  - V1はfresh DBで最初に実行されるため、V1の新テーブルFKはV1内テーブル(m_customer/t_contract)のみ。V73以降のテーブル(t_customer_contact/t_document/t_quotation)へのFKはV80側にのみ定義（fresh/legacyで形状が僅かに非対称だがUNIQUE制約は両経路で同一。SmokeTestは列/索引でassert）。

## 7. T055 F2 見積→注文→契約 — 記録（2026-08-05）

- **task**: T055 F2
- **requirements**: R2.1（見積→注文draft引継ぎ）、R2.2（注文→契約draft冪等）、R2.3（差分表示・承認対象）、R5（二重clickで重複契約なし）
- **変更file**:
  - `src/main/java/com/ses/service/impl/SalesOrderApprovalAdapter.java`（新規: order.cancel / order.conditionDiff）
  - `src/main/java/com/ses/service/impl/SalesOrderServiceImpl.java`（computeDiffsをBigDecimal.compareTo比較に修正、hasApprovedConditionDiff）
  - `src/test/java/com/ses/order/SalesOrderQuotationContractIntegrationTest.java`（新規）
  - `src/test/java/com/ses/order/SalesOrderApprovalAdapterTest.java`（新規）
- **実装**:
  - createDraftFromQuotation: 顧客・要員・案件・単価・精算幅を引継ぎ、同一見積からは冪等に1件。
  - createContractDrafts: 1明細→1契約。order_line_id UNIQUE＋既存チェックで二重契約化防止。全明細契約化後に注文を「契約化」へ状態CAS遷移。
  - computeDiffs: 見積/契約との単価・精算幅差分（BigDecimalはscale違いでも金額等価としてcompareTo比較）。
  - 条件差分がある注文は承認済みでない限り契約化不可（order.conditionDiff承認が監査証跡）。
  - SalesOrderApprovalAdapter: order.cancel（承認適用でapplyCancellation）、order.conditionDiff（状態不変の監査証跡）。
- **test**: `SalesOrderQuotationContractIntegrationTest` 5/0/0（条件引継ぎ・差分ブロック・契約化冪等・取消競合・承認適用取消）、`SalesOrderApprovalAdapterTest` 2/0/0。
- **Demo**: UI未実装のため未実施。二重clickの契約2件防止は order_line_id UNIQUE + 冪等testで検証済み。実ブラウザDemoはT056/A1で実施。
- **commit**: （T055 commit hashを記入）
- **risk**: 条件差分の承認routeは管理者設定（approval spec）が前提。route未設定時は承認engineが設定不足通知を出す（既存挙動）。

## 8. T056 A1 注文画面/注文請PDF/archive — 記録（2026-08-05）

- **task**: T056 A1
- **requirements**: R1.4（原本/注文請書をarchive保存・検索）、R2.4（PO重複は警告・同一hashは拒否）、R5（download scope）
- **変更file**:
  - `controller/page/SalesOrderPageController.java`（新規: /sales-order）
  - `controller/api/SalesOrderApiController.java`（新規: CRUD/状態/原本upload/注文請PDF/download/承認申請/契約化）
  - `service/SalesOrderPdfService.java` + `impl/SalesOrderPdfServiceImpl.java`（新規: 注文請書PDF・ORDER_ACKNOWLEDGEMENT登録）
  - `service/SalesOrderService.java` + `impl/SalesOrderServiceImpl.java`（uploadSourceDocument / generateAcknowledgementPdf / downloadDocument）
  - `mapper/DocumentMapper.java`（findDocumentIdBySha256AndType: 同一hash拒否）
  - `service/impl/DocumentServiceImpl.java`（SALES_ORDERリンクのscope適用: assertDocumentAccessAllowed / applyDataScopeFilter）
  - `service/security/impl/FileScopeValidationService.java`（SALES_ORDERリンクの顧客DataScope）
  - `templates/sales-order/list.html` + `static/js/modules/sales-order.js`（新規）
  - `templates/layout/sidebar.html`（sales-order / acceptanceメニュー）
  - `common/constant/NotificationLinks.java`（SALES_ORDER）
  - 4言語message bundle（menu.salesOrder / menu.acceptance / salesOrder.* / error.order.*）
- **test**: `SalesOrderApiControllerTest` 4/0/0（PO警告・hash拒否409・download・detail）、`SalesOrderDocumentScopeTest` 3/0/0（document ACL / FileScopeValidationService / applyDataScopeFilterが注文scope）、`SalesOrderPdfServiceImplTest` 1/0/0（PDF生成）。直接回帰: JsSyntaxCheckTest / NotificationLinkRouteTest / MessageBundleConsistencyTest / MobileResponsiveLayoutTest / RoleNavigationVisibilityTest 全緑。
- **Demo**: 実ブラウザDemoは未実施（ローカル起動のMySQLが必要。T059 Mで実施予定）。PO重複警告・同一hash拒否・原本→注文請の発行フローはAPI testで検証済み。
- **commit**: （T056 commit hashを記入）
- **risk**: 原本uploadはPDF/画像(10MB以内)のみ許可。scan失敗はfail-closed（DocumentService既存挙動）。

## 9. T057 B1 月次検収service/UI — 記録（2026-08-05）

- **task**: T057 B1
- **requirements**: R3.1（契約×月の検収・work record・提出日・顧客確認者・結果・差戻し理由）、R3.2（状態機械）、R3.4（検収取消承認）、R3.5（内部代行入力）、R5
- **変更file**:
  - `service/AcceptanceService.java` + `impl/AcceptanceServiceImpl.java`（新規: submit/accept/reject/resubmit/applyCancellation）
  - `service/impl/AcceptanceApprovalAdapter.java`（新規: acceptance.cancel）
  - `mapper/AcceptanceMapper.java`（グリッドLEFT JOIN・FOR UPDATE・countUnacceptedForClosing等）
  - `controller/api/AcceptanceApiController.java`（新規） / `controller/api/MyAcceptanceApiController.java`（新規: 要員向け状態のみ・金額非表示）
  - `controller/page/AcceptancePageController.java`（新規）
  - `service/impl/WorkRecordServiceImpl.java`（R3.4ガード: saveHours/saveDaily/reopenMonthで検収済を拒否）
  - `templates/acceptance/list.html` + `static/js/modules/acceptance.js`（新規）
  - `common/constant/StatusConstants.java`（勤怠・検収状態定数） / `common/constant/NotificationLinks.java`（ACCEPTANCE）
  - 4言語message bundle（acceptance.* / error.acceptance.*）
- **test**: `AcceptanceServiceImplTest` 6/0/0（提出snapshot・snapshot不変・状態機械・差戻し理由必須・検収不要契約拒否・承認適用取消・R3.4再openガード）、`AcceptanceApprovalAdapterTest` 1/0/0。直接回帰: WorkRecordServiceImplTest / WorkRecordReopenSecurityTest / JsSyntaxCheckTest / NotificationLinkRouteTest / MessageBundleConsistencyTest / MobileResponsiveLayoutTest 全緑。
- **Demo**: 実ブラウザDemoはT059 Mで実施。状態遷移・二重提出・snapshot不変・再openガードは自動testで検証済み。
- **commit**: （T057 commit hashを記入）
- **risk**: work recordの「version」はt_work_recordにversion列が無いため、`work_record_updated_at`（更新日時snapshot）で実装（T054の備考と同様）。

## 10. T058 B2 請求/月次締め/通知統合 — 記録（2026-08-05）

- **task**: T058 B2
- **requirements**: R3.3（未検収契約から請求不可・検収不要契約は例外）、R4.1（注文未受領/注文請未返送/検収未提出・期限超過・差戻し通知）、R4.2（月次締めchecklistへ未検収件数）、R4.3（dashboard未検収売上・検収平均日数）、R5
- **変更file**:
  - `mapper/InvoiceMapper.java`（selectUnbilledWorkRecords / Scoped / All に acceptance_required=0 OR EXISTS(検収済) をWHERE句として追加。memory filter禁止）
  - `service/impl/MonthlyClosingServiceImpl.java` + `dto/closing/MonthlyClosingSummaryDto.java`（(g)未検収件数。閲覧者scopeで集計）
  - `static/js/modules/monthly-closing.js`（未検収カード）
  - `service/NotificationGenerateService.java`（orderReceiptPending/orderAckPending/acceptanceUnsubmitted/acceptanceOverdue/acceptanceRejected + generateAllへ組込み）
  - `service/impl/NotificationServiceImpl.java`（menuKeyForType: sales-order/acceptance）
  - `service/impl/DashboardServiceImpl.java` + `dto/dashboard/DashboardSummaryDto.java`（未検収売上・検収平均日数KPI）
  - `templates/dashboard/index.html` + `static/js/modules/dashboard.js`（KPIカード2枚）
  - 4言語message bundle（notification.msg.* / dashboard.kpi.* / closing.item.unaccepted）
- **test**: `InvoiceAcceptanceGuardTest` 3/0/0（未検収0件・検収後生成・検収不要契約は生成可）、`MonthlyClosingUnacceptedTest` 2/0/0（checklist未検収件数・scope適用・通知発行）。直接回帰: InvoiceServiceImplTest 41/0/0 / InvoiceApiControllerTest / MonthlyClosingServiceImplTest 12/0/0 / DashboardServiceImplTest / NotificationGenerateServiceTest（両パッケージ）/ JsSyntaxCheckTest / NotificationLinkRouteTest / MessageBundleConsistencyTest 全緑。
- **Demo**: 実ブラウザDemoはT059 Mで実施。
- **commit**: （T058 commit hashを記入）
- **risk**: 通知の宛先は「契約sales_user_id（有効営業）∪管理者」。顧客レベルの担当営業が契約を持たない場合は管理者のみへ通知（設計§5.2のscheduler行）。
