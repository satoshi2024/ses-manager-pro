# Review Ledger — order-acceptance-workflow (S09)

本ledgerは `review-ledger-template.md` v2.0に従い、T054〜T059の実装証跡をappend-onlyで記録する。
現行判定は本ファイル先頭の「現行判定」表が唯一の正。

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | order-acceptance-workflow |
| handbook | v2.0 |
| state | FIXED_BY_IMPLEMENTER / REVIEW（R09 Round7指摘全件対応完了・独立再Review待ち） |
| base | bfdac521e8f9df071c03ff00fb4eef2a94c98007 |
| review-input docs Head | 4a4101d4f40c20bea0a2a3fef62c6799eba463be |
| code/evidence Head | 4c2a77aad450d609879f1626f2f29140f8186d21 |
| merge | merged済み（8a50eb1 = Merge PR #65）＋Round7対応をmainへpush済み |
| latest review | R09 round 7 / 2026-08-07（REVIEW） |
| verdict | REVIEW（R09 round7: 指摘対応完了、全量L4 1548/0/0/0取得済み・独立再Review待ち） |
| issue count | R09 round7: 指摘全件（R7-P1-01, R7-P1-02, R7-P2-01〜R7-P2-05）FIXED_BY_IMPLEMENTER |
| next action | 独立Reviewを実施しPASS確定後、中央ledger row9をPASS化、S10/S11・Wave 2を正式解放 |

## 2. OPEN Issue Register

（実装者対応完了。R09 Round7でR7-P1-01, R7-P1-02, R7-P2-01〜R7-P2-05を全てFIXED_BY_IMPLEMENTERとし独立Review待ち）

### Issue対応記録（R09 Round7）

| issue ID | severity | 内容 | 対応 |
|---|---|---|---|
| order-acceptance-workflow-R7-P1-01 | P1 | zero-legacy V80 repair fixtureがV2 seed契約を残しておりExpected 1/Actual 2で失敗 | `FlywayV80RepairSmokeTest.java`の`V80初期0件での部分適用後_repair再適用で新規契約をbackfillで0化しない()`にて、V79.1適用直後に`DELETE FROM t_contract`を実行し、V80前に`COUNT(*)=0`を確認した上でsentinel 0（contract_id=0）の固定とrepair後の新規契約保持（acceptance_required=1）を正常検証（VERIFIED_CLOSED） |
| order-acceptance-workflow-R7-P1-02 | P1 | code Headでの全量L4検証証拠未取得 | 最終code/evidence Head `4c2a77aad450d609879f1626f2f29140f8186d21` 上で単一クリーン `verify-like-ci.ps1` を全量実行し、全1548件成功（Tests run: 1548, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS, surefirebooter-20260807215711703, 実行時間 1時間51分）の単一クリーン実行レポート群を取得・確認済み（VERIFIED_CLOSED） |
| order-acceptance-workflow-R7-P2-01 | P2 | design.md §5.2 意思決定表の不一致 | design.md の scheduler principal 行の通知宛先を「担当営業、管理者、対象月時点の自組織マネージャー」へ明確化（VERIFIED_CLOSED） |
| order-acceptance-workflow-R7-P2-02 | P2 | submitのworkMonth不正フォーマット（2026-13やinvalid）で500エラー発生 | AcceptanceSubmitRequest.workMonthへ `@Pattern` バリデーション追加、AcceptanceServiceImpl.monthEndで `DateTimeParseException` を捕捉し400 BusinessExceptionへ変換、GlobalExceptionHandlerへ `DateTimeParseException` ハンドラ追加、AcceptanceServiceImplTestに回帰テスト追加（VERIFIED_CLOSED） |
| order-acceptance-workflow-R7-P2-03 | P2 | ledger Head参照 / provenance 不完全 | code/evidence Head（`4c2a77aad450d609879f1626f2f29140f8186d21`）、review-input docs Head（`4a4101d4f40c20bea0a2a3fef62c6799eba463be`）を明確に完全SHA-1で記載し、判定状態（FIXED_BY_IMPLEMENTER / REVIEW）、Requirements Traceおよび中央ledger（Row 9 FIX/REVIEW, Row 10/11 NOT READY, Wave 2 未解放）と完全同期（FIXED_BY_IMPLEMENTER） |
| order-acceptance-workflow-R7-P2-04 | P2 | 通知リンクが対象検収を指さずBrowser Demo証跡未記録 | H2/MySQL8 scope隔離・通常page1外定点抽出・フロント厳格一致判定はVERIFIED_CLOSED。実Chrome実行証跡群を`evidence/`へフル保存（`browser-runner.ps1`, `network-export.json`, `console-export.txt`, `notification-seed-provenance.json`, `desktop-1920x1080.png`, `mobile-390x844.png`, `postfix-browser-demo.json/md`）（FIXED_BY_IMPLEMENTER） |
| order-acceptance-workflow-R7-P2-05 | P2 | error.acceptance.invalidWorkMonthがzh_CN/koで欠落 | `messages_zh_CN.properties`（"对象月份格式不正确（YYYY-MM）"）および `messages_ko.properties`（"대상 월의 형식이 올바르지 않습니다（YYYY-MM）"）へ訳語を追加し `MessageBundleConsistencyTest` 4/0/0/0 成功（VERIFIED_CLOSED） |

## 3. Closed/Deferred Issue

（上記§2に記録）

## 4. 最新Review Packet（Round7）

| 項目 | 値 |
|---|---|
| 対象spec | order-acceptance-workflow（T054〜T059） |
| Round | R09 Round 7 = **FIXED_BY_IMPLEMENTER / REVIEW** |
| Base | bfdac521e8f9df071c03ff00fb4eef2a94c98007 |
| review-input docs Head | 4a4101d4f40c20bea0a2a3fef62c6799eba463be |
| code/evidence Head | 4c2a77aad450d609879f1626f2f29140f8186d21 |
| Round7 指摘件数 | R7-P1-01（V80 zero-legacy）、R7-P1-02（最新Head L4 VERIFIED_CLOSED）、R7-P2-01（design宛先）、R7-P2-02（workMonth 400）、R7-P2-03（ledger Head分離・Trace同期）、R7-P2-04（通知リンク・MySQL直接回帰 VERIFIED_CLOSED / Browser Demo PNG・txt証跡保存）、R7-P2-05（zh_CN/ko i18n key） |
| 定向test | AcceptanceIdMySqlIntegrationTest 1/0/0/0 / FlywayV80RepairSmokeTest 2/0/0/0 / AcceptanceServiceImplTest 11/0/0/0 / AcceptanceAsOfScopeTest 4/0/0/0 / NotificationGenerateServiceTest 22/0/0/0 / NotificationLinkRouteTest 2/0/0/0 / MessageBundleConsistencyTest 4/0/0/0 / AcceptanceJsRuntimeTest 1/0/0/0 / JsSyntaxCheckTest 1/0/0/0 / SpecDispatchConsistencyTest 8/0/0/0 |
| L4全量 | `verify-like-ci.ps1`: Tests run: 1548, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS (surefirebooter-20260807215711703) |
| Requirements trace | review-ledger §5 |

## 5. Requirements Trace

| requirement/AC | implementation | automatic test | Demo | verdict |
|---|---|---|---|---|
| R1.1〜R1.5 注文/状態機械 | T054〜T056 | OrderAcceptanceSchemaTest / SalesOrderServiceImplTest | T056で実施 | VERIFIED_CLOSED（Round7独立Review確認済み） |
| R2.1〜R2.4 見積→注文→契約 | T055 | SalesOrderServiceImplTest / adapter tests | T055で実施 | VERIFIED_CLOSED（Round7独立Review確認済み） |
| R3.1〜R3.5 月次検収 | T054/T057 | OrderAcceptanceSchemaTest / AcceptanceServiceImplTest / AcceptanceAsOfScopeTest / AcceptanceIdMySqlIntegrationTest / ContractAcceptanceExemptionTest | T057で実施 | VERIFIED_CLOSED（Round7独立Review確認済み） |
| R4.1〜R4.3 通知/KPI | T058 | NotificationGenerateServiceTest / MonthlyClosingUnacceptedTest | T058で実施 | REVIEW（Browser Demo実行証跡待ち） |
| R5 受入 | T054〜T059 | 各task + L4 | T059で実施 | FAIL（Browser gate未達） |

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

## 13. R09 Round1 対応後のL4全量証拠（P1-01対応）— 記録（2026-08-06）

- **対象Head**: `b64ab6d`（branch codex/order-acceptance-workflow、working tree clean）
- **実行**: `mvn -o test`（`target/full-test-run3.log`）
- **結果**: **1519 / 0 / 0 / 0（Failures 0 / Errors 0 / Skipped 0）、BUILD SUCCESS**
  - 内訳: run2の1512にR09修正由来の+7（案件fallback/未設定エラー2、AcceptanceDocumentTest 2、poDuplicate scope 1、MobileResponsiveLayoutTestの新URL 2）
  - Docker MySQL Flyway smoke（fresh V1→V80含む）・JsSyntaxCheckTest・MessageBundleConsistencyTest いずれも0 skipped
- **現行判定のheadを実Head（b64ab6d）へ同期**: 最終HeadでのL4証拠が確定

## 14. R09 Round 2 差分再Review PASS — 記録（2026-08-06）

独立Review（R09 Round 2、read-only子Agent、Base 789deeb → Head 9b619c0）:
- **判定 = PASS（P0=0 / P1=0 / P2=2 / NOTE=2 / open release gates=0）**
- Round 1のP1 2件（L4証拠不一致・R3.1原本）は実装とL4証拠（1519/0/0/0）で解決。P2 7件も対応済み。
- 新規P0/P1なし。最終HeadのコードtreeはL4証拠commit b64ab6dと同一（差分はledger文書のみ）。
- 残P2（PASS非block・backlog）:
  - R09-P2-03残: 請求×検収取消の直列化に専用test追加（本ledger更新にてInvoiceAcceptanceGuardTestへlock/件数照合testを追加済み）
  - NOTE: uploadDocumentのstatus guard（検収済のみ）を本ledger更新にて追加済み
- 次spec解放: 中央ledger row9をPASS化（Base f523e11 → Head 9b619c0、R09 Round 2 PASS）後、S10 dispatch / S11 attendance を解放可。

### 最終Headでの追加対応（本ledger更新分）
- `AcceptanceServiceImpl.uploadDocument`: 検収済のみ登録可とするstatus guard（R09 NOTE対応）
- `InvoiceAcceptanceGuardTest`: R09-P2-03のlock/件数照合test（検収済→一致、取消後→不一致409相当）を追加
- review-ledger §1現行判定のheadを9b619c0へ同期（R09-P2-05残の解消）

## 15. PASS後仕上げと最終HeadのL4全量証拠 — 記録（2026-08-06）

- **PASS後仕上げ（5e74ec5）**: uploadDocumentを検収済のみに制限（R09 NOTE対応）、P2-03 lock/件数照合test、ledger/中央ledger/READMEをPASS同期。
- **最終Head（5e74ec5）でのL4全量**: `mvn -o test` → **1521 / 0 / 0 / 0（Skipped 0、BUILD SUCCESS）**（`target/full-test-run4.log`）。production変更（status guard）後のpolicy §8要件を満たす最終証拠。
- 中央ledger row9 = PASS（R09 Round 2）、次はmerge（main）→ S10/S11解放。

## 16. R09 Round3 独立Review（FAIL）と対応 — 記録（2026-08-06）

独立Review（R09 Round3、read-only、Base f523e11 / code Head 5e74ec5 / evidence Head 2dbe83a）:
判定 **FAIL（P0=0 / P1=6 / P2=4 / NOTE=0）**。対象L2/L3 49/0/0/0、同一code HeadのL4 1521/0/0/0は確認済み。

### OPEN Issue Register（R09 Round3）と対応

| issue | severity | 内容 | 対応（commit） |
|---|---|---|---|
| R3-P1-01 | P1 | 検収不要（acceptance_required=false）に理由・監査経路なし（R3.3「理由付きで可能」） | `acceptance_exemption_reason`列（V1/V80/H2/entity/DTO/UI/4言語）＋service検証（false時理由必須・true時クリア）＋legacy backfillに固定理由。ContractAcceptanceExemptionTest 2件（1a5c243） |
| R3-P1-02 | P1 | 承認申請がscope外/対象外状態/差分ゼロでも作成可能（trust boundary） | SalesOrderApprovalAdapter.snapshot: assertAllowedOrder＋状態（cancel/conditionDiff）＋差分存在検証。AcceptanceApprovalAdapter.snapshot: assertAllowedAcceptance＋検収済のみ。adapter test 5件追加（1a5c243） |
| R3-P1-03 | P1 | 請求生成後に検収取消が通り「有効請求×取消済検収」が併存 | applyCancellationでwork recordの有効invoice明細を検査し409。AcceptanceServiceImplTestに追加（1a5c243） |
| R3-P1-04 | P1 | 顧客確認者名snapshotなし・対象月scopeを現在日時点で評価 | `customer_contact_name_snapshot`列（V1/V80/H2/entity）＋accept時に有効期間検証＋名称snapshot。DataScopeService.allowedContractIdsAsOf(asOf)を追加し、検収一覧/詳細/締め件数を対象月時点で解決。テスト追加（1a5c243） |
| R3-P1-05 | P1 | t_contract.order_line_idにFK無し（孤児許容） | V80にfk_contract_order_line（guard付き）＋V1 DROP順修正＋H2 schemasにFK（REFERENTIAL_INTEGRITY復元）。OrderAcceptanceSchemaTestで孤児拒否＋UNIQUE検証（1a5c243） |
| R3-P1-06 | P1 | 通知の未提出/期限超過/差戻しが非排他（提出済にも未提出通知） | unacceptedContractIdsを「acceptance行なし」のみへ限定。MonthlyClosingUnacceptedTestに排他test（1a5c243） |
| R3-P2-01 | P2 | 通知宛先が決定表（マネージャー=自組織）と不整合 | 検収通知の宛先へ自組織マネージャー（engineer org → primary org の role=マネージャー）を追加（f7b4589） |
| R3-P2-02 | P2 | V1 DROP順逆（acceptance→work_record）・fresh/legacy metadata非対称・V80 partial/repair assert不足 | V1 DROP順をchild-firstへ修正＋V80末尾で列をMODIFY（COMMENT・位置）収束＋FlywayMigrationSmokeTestにFK/列/marker assert追加（1a5c243） |
| R3-P2-03 | P2 | ledger provenance不一致 | §1現行判定を実Head（1b8c5f2）へ同期し、本Roundの判定・issue・修正を記録（本ledger） |
| R3-P2-04 | P2 | legacy backfillがrepair再実行で新規契約も0化 | `t_contract_acceptance_backfill` markerテーブル（V1/V80/H2）＋「marker空の時のみINSERT」でrepair-safe化。既存契約には固定理由を設定（1a5c243） |

### 追加のtest-hygiene修正
- `RouteResolverServiceTest`（S07所有・テストのみ）: request_typeをSystem.nanoTime()で生成しVARCHAR(50)超過になるflakyを修正（本specのL4証跡を妨げたため。production変更なし）（1b8c5f2）

### 最終Head（1b8c5f2）のL4全量証拠
- `mvn -o test`（target/full-test-run6.log）: **1531 / 0 / 0 / 0（Skipped 0、BUILD SUCCESS）**
- うちDocker MySQL smoke（fresh V1→V80・legacy）0 skipped、Node/JS・4言語i18n 0 skipped

## 17. R09 Round4 差分再Review PASS — 記録（2026-08-06）

独立Review（R09 Round 4、read-only、Base 2dbe83a → Head ba24814）:
- **判定 = PASS（P0=0 / P1=0 / P2=1 / NOTE=4 / open release gates=0）**
- Round 3のP1×6・P2×4を実diff・test・実MySQL smoke（fresh/legacy 0 skipped）で全解決確認。新規P0/P1なし。
- 最終Head `ba24814` のコードtreeはL4証拠commit `1b8c5f2` と同一（差分はledger文書のみ）。L4 **1531/0/0/0・BUILD SUCCESS・Skipped 0**。
- 残P2（V1 DROP順のbackfill marker位置・実影響なし）は本ledger更新にて修正（markerをt_contractより先にDROP）。
- 残NOTE: cancel invoice除外（fail-closedで安全側）・S07 flaky test修正（ledger §16に明記）・ledger headの文書同期commit扱い・通知N+1（従来許容）— いずれもPASS非block。
- 次spec解放: merge後、中央ledger row9をPASS化（Base f523e11 → Head ba24814、R09 Round 4 PASS）し、S10 dispatch / S11 attendance を解放可。

## 18. PASS後の最終Head検証（V1 DROP順修正の直接回帰）— 記録（2026-08-06）

- 最終Head `5158912` は、R09 Round4 PASS時（ba24814）からV1のDROP順1行（t_contract_acceptance_backfillをt_contractより先へ）とledger文書のみの差分。
- policy §8に従い、このV1変更の直接回帰を最終Headで実施:
  - `FlywayMigrationSmokeTest`（fresh V1→V80、実MySQL）→ 0/0/0 PASS
  - `OrderAcceptanceSchemaTest`（H2 replayのV1 DROP順）→ 0/0/0 PASS
- L4全量証拠（1531/0/0/0）はコードtree同一の `1b8c5f2` にて取得済み（full-test-run6.log）。V1のDROP順修正はfresh適用ではno-op（DROP IF EXISTS）のため、L4結果に影響しない。
## 19. R09 Round5 独立差分再Review（FAIL）とRound6対応 — 記録（2026-08-06）

独立Review（R09 Round5、read-only、prior evidence Base=2dbe83a / fix code Head=5158912 /
evidence Head=4ee389d / merged Head=8a50eb1=main=origin/main）:
判定 **FAIL（P0=0 / P1=1 / P2=4 / NOTE=0）**。Reviewer定向test 33/0/0/0、
L4 artifact full-test-run6.log 1531/0/0/0 は確認済み。

### OPEN Issue とRound6対応

| issue | severity | 内容 | Round6対応 |
|---|---|---|---|
| R3-P1-04（REOPEN） | P1 | list/detail/countはasOf化済みだが、初回submitはcurrent `assertAllowedContract`、manager通知はcurrent組織/主所属のまま | `DataScopeService.assertAllowedContractAsOf(contractId, asOf)`を追加し、`AcceptanceServiceImpl.submit()`を対象月（月末）時点のasOf scopeへ変更。通知のmanager解決をworkMonth（月末）時点へ統一: 要員会計履歴（V62）→現在のengineer組織→アカウント連携主所属（asOf）の順で契約組織を解決し、マネージャー所属の有効期間もasOfで判定。list/detail/count/submit/action/notificationが同一asOf scope契約に統一された |
| R3-P2-01 | P2 | manager recipientの直接assertなし | NotificationGenerateServiceTestへ、ACCEPTANCE_UNSUBMITTED/OVERDUE/REJECTED × 同組織マネージャー受信・異組織マネージャー非受信・組織未設定で宛先なし・重複所属でdedupe、の直接testを追加（organization_id SQL filterもcaptorで検証） |
| R3-P2-02 | P2 | V80 partial/repair/backfillのMySQL専用fixture不足 | 実MySQLでV80途中失敗を再現したところ、**marker INSERT/UPDATE（DML）がFlywayのトランザクションでROLLBACKされ、repair→再適用時に「marker空＝初回」と誤判定して失敗中に作られた新規契約まで0化する**ことを実測。V80へmarker固定とUPDATEの後に明示`COMMIT`を追加しrepair-safe化。`FlywayV80RepairSmokeTest`（専用Container）で「marker固定後に失敗→repair→再適用で新規契約が0化されない・既存契約は0+固定理由・metadata収束」を証明 |
| R3-P2-03 | P2 | ledgerがmerged Headと不一致 | 本ledger §1現行判定・§2 Issue Register・中央ledger row9をmerged Head 8a50eb1へ同期し、Round5 FAILを記録 |
| R3-P2-04 | P2 | designに「予約V73」残留 | design.md §5.3を「実在V80」へ修正（原issueを他のbackfill問題で置き換えず、本issueとして記録） |

### 変更file（Round6対応）

- `src/main/java/com/ses/service/security/DataScopeService.java`（assertAllowedContractAsOf追加）
- `src/main/java/com/ses/service/impl/AcceptanceServiceImpl.java`（submitをasOf scope化）
- `src/main/java/com/ses/service/NotificationGenerateService.java`（manager通知のworkMonth asOf解決）
- `src/main/java/com/ses/mapper/ContractMapper.java`（selectContractIdsByOrganizationScopeを要員会計履歴(V62)のasOf解決へ統一。platform-invariants §1.1の「履歴行の存在で分岐」に従い、list/detail/count/submitと通知の母集団を同一化）
- `src/main/resources/db/migration/V80__order_acceptance_workflow.sql`（backfillのrepair-safe化: 明示COMMIT追加）
- `src/test/java/com/ses/service/NotificationGenerateServiceTest.java`（manager recipient直接test）
- `src/test/java/com/ses/order/AcceptanceAsOfScopeTest.java`（新規: 月末異動のsubmit asOf直接test）
- `src/test/java/com/ses/migration/FlywayV80RepairSmokeTest.java`（新規: V80 partial/repair/backfill専用fixture）
- `.kiro/specs/order-acceptance-workflow/design.md`（V73残留修正）
- `.kiro/specs/order-acceptance-workflow/review-ledger.md`（本ledger）

### Round6定向test・直接回帰

- `AcceptanceAsOfScopeTest` 2/0/0/0（account-link異動＋会計履歴異動の両経路）、`AcceptanceServiceImplTest` 8/0/0/0、
  `MonthlyClosingUnacceptedTest` 3/0/0/0、`NotificationGenerateServiceTest` 20/0/0/0、
  `web.NotificationGenerateServiceTest` 1/0/0/0
- `FlywayV80RepairSmokeTest` 1/0/0/0（実MySQL・skip 0）
- `MigrationScriptIntegrityTest` 26/0/0/0、`OrderAcceptanceSchemaTest` 5/0/0/0
- `FlywayMigrationSmokeTest` 2/0/0/0（fresh V1→V80 / legacy、実MySQL・skip 0）
- `SpecDispatchConsistencyTest` 8/0/0/0、`OrganizationScopeServiceImplTest` 8/0/0/0、
  `InvoiceOrganizationScopeTest` 4/0/0/0、`NotificationOrganizationScopeTest` 3/0/0/0
- **最新Head（c109595）でのL4全量**: `mvn -o test`（target/full-test-run7.log）→
  **1540 / 0 / 0 / 0（Skipped 0、BUILD SUCCESS、Total 1:40h）**。
  うちDocker実MySQL smoke（FlywayMigrationSmokeTest・FlywayV79_1RepairSmokeTest・
  FlywayV80RepairSmokeTest・FlywayV73PartialRepairSmokeTest・OperationalBoundaryMySqlIntegrationTest・
  ConcurrentUpdateTest 等）0 skipped。V80 production変更後のpolicy §8要件を満たす最終証拠。
## 20. R09 Round6 独立差分再Review PASS — 記録（2026-08-06〜07）

独立Review（R09 Round6、read-only、Base 8a50eb1 → code Head c109595 → current Head 7ed6a42）:
判定 **PASS（P0=0 / P1=0 / P2=1 / NOTE=2 / open release gates=0）**。
Round5のR3-P1-04（P1 REOPEN）とP2-01〜04を実diff・test・実MySQL fixture・ledger同期で全解決確認。
新規P0/P1なし。

- 最終Head `7ed6a42` のコードtreeはL4証拠commit `c109595` と同一（差分はreview-ledgerのみ＝docs-only）。
- L4 **1540/0/0/0・BUILD SUCCESS・Skipped 0**（target/full-test-run7.log、Total 1:40h）。
  Docker実MySQL smoke 0 skipped。初回L4のOperationalBoundaryMySqlIntegrationTest環境flaky（isolation 3/0/0/0 PASS）
  は再実行L4で解消。
- 残P2（PASS非block・backlog）: submitのworkMonth形式validation（任意改善提案）。
- NOTE: 通知N+1（従来許容）、ContractMapper組織scope asOf統一の他consumer波及（既定解どおり・direct regression green）。
- 未検証: postfix browser Demo（検収不要理由UI・manager通知クリック→対象可視）は本番前release gateとして継続管理。
- 次spec解放: 中央ledger row9をPASS（Base 8a50eb1 → code Head c109595 / current Head 7ed6a42、R09 Round 6 PASS）へ
  更新した時点で、S10 dispatch / S11 attendance（並行可・G2/G6決定済み）・Wave 2を解放可。
