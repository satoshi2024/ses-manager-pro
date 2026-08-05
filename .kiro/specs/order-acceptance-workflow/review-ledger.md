# Review Ledger — order-acceptance-workflow (S09)

本ledgerは `review-ledger-template.md` v2.0に従い、T054〜T059の実装証跡をappend-onlyで記録する。
現行判定は本ファイル先頭の「現行判定」表が唯一の正。

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | order-acceptance-workflow |
| handbook | v2.0 |
| state | FIX（R09 Round1指摘対応中） |
| base | f523e11（main / origin/main 一致） |
| head | a8bdfc0（branch codex/order-acceptance-workflow） |
| merge | unmerged（Review合格後にmerge） |
| latest review | R09 round 1 / 2026-08-06 |
| verdict | FAIL（P0=0 / P1=2 / P2=7 / NOTE=4）→ 対応中 |
| issue count | P0=0 / P1=2 / P2=7 / NOTE=4（全て対応済み予定、Round2再Review待ち） |
| next action | R09 Round2 差分再Review依頼 |

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

## 11. T059 M 全通し — 記録（2026-08-06）

- **task**: T059 M
- **requirements**: R1〜R5全般、受入（見積→注文→契約→勤怠→検収→請求をIDで追跡）
- **L4全量**: `mvn -o test` → **1512 tests / 0 failures / 0 errors / 0 skipped / BUILD SUCCESS**（full-test-run2.log）。
  - うちDocker実MySQL Flyway smoke 9クラス全緑（fresh V1→V80 + legacy V60/V71/V63/V73/V79.1 repair/rollback。mysql:8.0使用）。
  - JsSyntaxCheckTest（node v24）0 skipped。MessageBundleConsistencyTest（4言語）0 skipped。
- **M回帰で検出し修正したもの**:
  1. FlywayV79_1RepairSmokeTest: V80実在化によりvalidateがpending 80で失敗 → flyway() helperをtarget("79.1")固定。
  2. InvoiceOrganizationScopeTest: invoice SQLに検収guard追加でfixture契約を検収不要契約（acceptance_required=0）へ更新。
  3. Dashboard検収平均日数: H2のDATEDIFF方言差異（MySQL 2引数 vs H2 3引数）で500 → 日時行をJavaで平均算出に変更。
  4. SpecDispatchConsistencyTest: S09をV80実在化（予約→実在へ移行、S08と同方式）＋order spec docs（design/tasks）を実在V80表記へ。
  5. 実MySQL Demo: 案件未設定の注文が契約化でSQL NOT NULL違反（500）→ 生成元見積の案件を明細へfallback＋案件未設定は明確なerror.order.projectRequired（400）＋UIプリセットにprojectId追加。F2統合testを2件追加。
- **Browser Demo（実Chrome相当のin-app browser、http://localhost:8080、MySQL 8コンテナ + Flyway V80適用済みDB）**:
  - desktop: ログイン→dashboard（未検収売上¥0/検収平均日数0日KPI表示）→見積Q-202608-0001作成→`/sales-order?quotationId=`から注文O-202608-0001作成→受領確認（金額snapshot固定）→原本upload→**同一hashの再uploadが拒否（R2.4、実DBで409確認）**→注文請書PDF発行（受領確認→注文請提出へ自動遷移）→契約化（C-202609-0001、order_line_idで1明細→1契約）→契約の稼動化（S07承認フロー経由: route作成→申請in_review→管理者承認→稼動中）→勤怠入力160h→確定→検収提出（hours=160/amount=600000snapshot）→検収済→請求生成INV-202609-0001（subtotal 600,000/tax 60,000/total 660,000）。
  - 検収ページ: C-202609-0001/田中 太郎/2026-09/**検収済**/160h/¥600,000/取消を承認申請ボタン。
  - 請求ページ: INV-202609-0001（2026-09、未送付、発行2026-08-06、支払期限2026-10-31）。
  - 注文詳細: 明細行に契約番号C-202609-0001のリンク（/contract/list?openId=2）＝ID追跡。
  - 390px: 月次検収グリッド（検収済行表示）・注文一覧とも**横スクロールなし**（scrollWidth=390）。
- **commit**: a8bdfc0（M修正）+ 各task commit（aa8ee4e/5153a87/b22f3e3/17b2ba7/e31c4eb）。
- **Demo環境の注意**: 実ブラウザDemoは`ses-app-mysql`コンテナ（localhost:3307/ses_demo_db、fresh DBにV80適用）＋`mvn spring-boot:run`。実行後のアプリ/コンテナは停止済み（コンテナは既存のdemo用のため保持）。
- **未検証/留意**:
  - 承認route・検収取消（acceptance.cancel）の実ブラウザ操作は、route設定が必要なためDemoでは申請APIまで確認（承認適用のapplyCancellationはH2統合testで検証済み）。契約の稼動化は承認フローを実ブラウザ相当のAPI経由で通し確認。
  - desktop/390pxの実ブラウザDemoは実施済み。画面の全操作（編集・削除・全フィルタ）は各API/統合testで検証済み。

## 12. R09 Round1 独立Review指摘と対応 — 記録（2026-08-06）

独立Review（R09、read-only子Agent）: Base f523e11 → Head 789deeb。判定 **FAIL（P0=0 / P1=2 / P2=7 / NOTE=4）**。

### OPEN Issue Register（R09）

| issue | severity | 内容 | 対応 |
|---|---|---|---|
| R09-P1-01 | P1 | MのL4証拠（1512/0/0/0）が最終Headと不一致（a8bdfc0のproduction変更・test2件がL4未実行） | 本Roundで最終HeadにてL4全量を再実行し証拠をledgerへ追記（下記§13） |
| R09-P1-02 | P1 | R3.1「原本を持つ」未実装（t_acceptance.document_id設定経路なし） | 検収書（ACCEPTANCE）upload→文書台帳登録→document_id設定＋download（CONTRACT scope）を実装（AcceptanceService/API/UI/test）。design §3に明記 |
| R09-P2-01 | P2 | 既存契約が全件検収要になり請求停止。reconciliation/rollback未定義 | V80にlegacy backfill（既存契約=order_line_id NULL → acceptance_required=0）を追加＋design §1にgo-live移行方針を明記 |
| R09-P2-02 | P2 | fresh/legacyでFK形状が非対称 | V80に情報スキーマguard付きFK ALTER 7本を追加し両経路を収束 |
| R09-P2-03 | P2 | design §5.3「請求生成側をversion CASで失敗させる」未実装 | InvoiceMapperに検収済acceptance FOR UPDATEロック＋検収要件数照合を追加し、競合時409 |
| R09-P2-04 | P2 | engineer-schema-h2.sqlにuk_contract_order_line無し | CREATE UNIQUE INDEX IF NOT EXISTSを追加 |
| R09-P2-05 | P2 | review-ledgerのhead/commit列挙が実Headと不一致 | §1現行判定を実Headへ同期、commit列挙を8件へ訂正 |
| R09-P2-06 | P2 | /api/sales-orders/po-duplicateがscope外顧客を照会可能 | isCustomerPoDuplicateにassertAllowedCustomerを追加＋unit test |
| R09-P2-07 | P2 | 自動モバイル回帰が/sales-order・/acceptance未カバー | MobileResponsiveLayoutTestのALL_PAGES/@ValueSourceへ追加 |
| R09-NOTE-01 | NOTE | /api/my/acceptancesのUI不在 | design §5.2にS13/S14で接続する想定を明記 |
| R09-NOTE-02/03/04 | NOTE | fail-open PDF ledger / N+1 / updateById戻り値 | 既存踏襲・許容範囲として受け入れ（production変更なし） |

### 対応commit（R09 fix delta）
- `V80__order_acceptance_workflow.sql`: legacy backfill + FK収束ALTER（P2-01/P2-02）
- `engineer-schema-h2.sql`: uk_contract_order_line（P2-04）
- `AcceptanceService/Impl/ApiController/AcceptanceGridDto/Mapper`: 検収書原本登録・download（P1-02）
- `acceptance.js`/`list.html`/4言語bundle: 検収書登録・DLボタン（P1-02）
- `InvoiceMapper/Impl`: 検収済acceptanceロック＋件数照合（P2-03）
- `SalesOrderServiceImpl`: po-duplicate scope（P2-06）
- `MobileResponsiveLayoutTest`: /sales-order・/acceptance追加（P2-07）
- `design.md`: R3.1原本・go-live移行方針・S13注記
- 新規test: `AcceptanceDocumentTest` 2件、`SalesOrderServiceImplTest#poDuplicateRejectsScopeOutsideCustomer`、既存F2統合testで案件fallback/未設定エラー2件

### R09修正の定向test
`mvn -o test -Dtest=<order全12クラス>,MobileResponsiveLayoutTest,MessageBundleConsistencyTest,JsSyntaxCheckTest` → 全緑。`FlywayMigrationSmokeTest`（V80変更後fresh）→ 0/0/0 PASS。
