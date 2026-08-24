# Architecture, Code Integrity & Transaction Review (Commit: 54edfd2b)

---

## 1. レイヤリング構造と責務分離

- **PageController (`com.ses.controller.page`)**: Thymeleaf ビュー名のみを返却し、ビジネスロジックを持たない設計を遵守。
- **ApiController (`com.ses.controller.api`)**: 全て `ApiResult<T>` (`code: 200`) でラップし、例外は `GlobalExceptionHandler` で一元 JSON 変換。
- **アーキテクチャ欠陥 [ACC-ARCH-P1-001]**:
  - `ManagementAccountingApiController:140` (`importBudgetCsv`), `RoleMenuApiController:50` (`update`), `SystemConfigApiController:63` (`update`), `UserApiController:118,151` において、**`@Transactional` が `@RestController` メソッドに直接付与されている**。
  - コントローラ層で MultipartFile のバイト配列展開・CSV パース・JSON デシリアライズを行っている間、DB コネクションを不必要に保持し続け、HikariCP プール (最大 20) の枯渇リスクを引き起こす。
  - **推奨対策**: コントローラから `@Transactional` を削除し、専用の `@Service` メソッドへトランザクション境界を移行すること。

---

## 2. トランザクション境界とロールバック仕様

- **トランザクション欠陥 [ACC-ARCH-P1-002]**:
  - `MonthlyClosingServiceImpl.confirmClosing:253`, `reopenClosing:280`
  - `EngineerSalesServiceImpl.assign:52`, `setPrimary:87`, `release:100`
  - `AccountingReconciliationServiceImpl:565`
  - `PurchaseExpensePaymentIntegrationServiceImpl:78,162,187`
  - `SalesInvoiceIntegrationServiceImpl:55,126`
  - 上記の重要サービスメソッドにおいて、`@Transactional` に `rollbackFor = Exception.class` が指定されていない（デフォルトは `RuntimeException` と `Error` のみロールバック）。
  - Jackson による JSON シリアライズ処理 (`JsonProcessingException`) や I/O 系のチェック例外が発生した場合に部分コミットが発生するリスクが存在する。

---

## 3. 金額精度・端数処理・税計算 (インボイス制度対応)

- **超過・控除精算計算 (`SettlementCalculator.java:20-48`)**:
  - 中間時間単価の計算において 10 桁精度 (`divide(hours, 10, RoundingMode.HALF_UP)`) を保持し、最終金額算出時に `setScale(0, RoundingMode.DOWN)`（切り捨て）を適用。極めて高い計算精度を維持。
- **インボイス制度消費税計算 (`InvoiceServiceImpl.java:145-156`)**:
  - 請求書単位で小計に対して `subtotal.multiply(taxRate).setScale(0, RoundingMode.DOWN)` を適用。明細ごとの端数積上げを排除し、日本の適格請求書等保存方式に完全準拠。
- **工数精度 (`V39__unify_work_hour_precision.sql`)**:
  - `t_work_record.actual_hours` および `t_work_record_daily.worked_hours` を `DECIMAL(6,2)` に統一。

---

## 4. 楽観的ロック (`@Version`) と並行性制御

- **楽観的ロック欠陥 [ACC-ARCH-P1-003]**:
  - `Contract`, `Invoice`, `BpPayment`, `Acceptance`, `ApprovalRequest`, `OrganizationUnit` には `@Version` が付与されているが、**`Customer`, `Engineer`, `WorkRecord`, `Proposal`, `Quotation`, `SalesOrder` には `@Version` が存在しない**（ラストライトウィン状態）。
- **非アトミック更新欠陥 [ACC-ARCH-P2-002]**:
  - `InvoiceServiceImpl.recalcPaymentStatus:406-411` において、`setSql("version = version + 1")` を用いた直接更新が行われており、`WHERE` 句にバージョン条件が含まれていないため、並行更新時の競合検知がバイパスされる。
