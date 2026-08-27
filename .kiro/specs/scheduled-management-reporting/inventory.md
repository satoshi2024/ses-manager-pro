# 既存集計 inventory（NF-10 / DG-10 decision pending）

## 1. 調査方針

report 専用の集計式を作らない。各 section は既存の正本 service/DTO を呼び出す adapter とし、report snapshot はその戻り値、入力の as-of、スコープ、freshness、正本の識別子を固定する。画面・既存 export・report は同じ section value contract を消費する前提である。

金額は既存実装の JPY / `BigDecimal` 口径を維持し、report 側で double 化、再計算、別の丸めを行わない。以下の cutoff と scope は候補であり、DG-10 の承認値ではない。

## 2. section 対応表

| report section | 現行の正本 service / DTO | 現行の利用経路 | cutoff / timezone 候補 | scope owner 候補 | snapshot / document / delivery |
|---|---|---|---|---|---|
| 売上・粗利（確定実績） | `DashboardService` / `DashboardSummaryDto`、金額口径は `MonthlyRevenueCalcService` / `MonthlyAmount` | `/api/dashboard/summary`、`dashboard/index.html`、月次売上 export | 対象 `YearMonth` の月初〜月末。確定 work record を実績判定に使う。report の現在日は `AccountingTimezoneResolver` と明示 clock から得る | 組織 scope と `DataScopeService` の交差を system principal に固定し、対象月 as-of で解決 | `sales`、`grossProfit`、`isActual`、source count/hash、dataAsOf を固定。PDF/XLSX/CSV は同じ snapshot を読む。`DocumentService` の GENERATED document と scope 検証が候補 |
| 売上着地予測 | Dashboard 内の forecast 系列、`DashboardSummaryDto`（独立 `RevenueForecastService` は存在しない） | Dashboard の forecast chart、既存 revenue forecast spec の系列 | 生成時点の open proposal と対象月。forecast と actual を別系列として表示し、月初/月末境界を明記 | actual と同じ report scope。提案の可視性は scoped engineer/contract の既存規約を再利用 | `forecast`、対象月、source version、pipeline count/amount、freshness を固定。現行式を report 側へ複製しない |
| 稼働率・Bench（実績/予測） | `UtilizationCalcService` / `UtilizationSummary`、将来系列は `UtilizationForecastService` / `UtilizationForecastDto` | `/api/dashboard/utilization-forecast`、Dashboard の予測表 | 月単位。契約の start/end、auto-renew、assume-renew を対象月に対して評価。timezone は期間境界解決に使用 | engineer/contract の `DataScopeService` と組織 scope。scheduler では request/session を使用せず明示 scope を渡す | working/bench/total/utilizationRate、actual/forecast、contract as-of、freshness を固定 |
| 資金繰り | `CashFlowForecastService` / `CashFlowForecastDto`。reconciliation は同 service と `MonthlyRevenueCalcService` | Dashboard の CF tab、cashflow export | `from` の `YearMonth` と月数、opening balance を入力として固定。請求 due date と支払月の timezone は tenant accounting timezone | 現行 `forecast` API に report scope 引数がないため要設計。会社全体のみ許可するか、scoped cashflow adapter を追加するかは DG-10/設計決定事項 | inflow/outflow/balance/alerts/reconciliation を固定。scope を曖昧にしたまま配布しない |
| 管理会計（実績/予測/予算） | `ManagementAccountingService` / `ManagementAccountingSummaryDto`、`ManagementAccountingContractRow` | `/api/management-accounting/summary`、`/export`、`/drilldown`、画面、budget UI | `YearMonth` の月初〜月末。確定は `MonthlyAccountingDimension` の月次 snapshot、forecast は current contract view。境界は tenant timezone | 組織 scope（対象月 as-of）∩ `DataScopeService`、filter は legal entity/org/cost center/customer/project/sales を明示保存 | actual/forecast source、summary/detail rows、budget/variance、dimension snapshot version を固定。既存 export と同一 value contract |
| 営業成績・commission | `SalesPerformanceService` / `SalesPerformanceSummary` 等、金額は `MonthlyRevenueCalcService` | `/api/sales-performance`、`/commission-rule`、営業成績画面 | 対象 `YearMonth`。契約/提案の月境界を固定し、commission config version も保存。現行 default now は report context に置換必須 | 現行 service は report 用の明示 DataScope が不足。営業別配布は recipient scope と混同せず、対象営業・組織 scope を別記録 | assigned/closed/win rate/sales/gross profit/commission と rule version を固定。未帰属行も削除せず明示 |
| AR エイジング | `InvoiceService` / `AgingReportDto`、詳細も同 service | `/api/invoices/aging`、`/aging/detail`、`/aging-export`、invoice UI | 明示 `asOf` 日。due date の bucket を asOf に対して評価し、期限未設定/未送付を別区分 | `OrganizationScopeService.allowedInvoiceIds(asOf)` ∩ sales customer scope。empty set は 0 rows | customer bucket、balance、asOf、source query snapshot を固定。既存 Excel export と同じ値を出力 |
| BP 支払 / 支払予定 | CashFlow の outflow、管理会計の cost 側データを正本とする | Dashboard CF、invoice BP payment API、管理会計 | 対象月と支払 site month。未払/支払済を as-of で区別 | 組織/取引先/権限 scope を明示。recipient-specific delivery に組織条件を暗黙追加しない | status、予定/実績、金額、as-of、freshness を固定。重複した別計算を作らない |
| 契約終了・更新見込み | `UtilizationForecastService` と Dashboard の rolloff 表示 | Dashboard forecast/rolloff UI | 対象月の契約 end date と後続契約・auto-renew。月末境界を含む | engineer/contract の対象月 scope | engineer/contract/project、rolloff、renewal decision、as-of を固定 |
| ServiceDesk / SLA | 現行 base に正本 service/DTO/API/画面なし | なし（NF-02 依存） | 未定義 | 未定義 | section を実装・配布対象に含めない。NF-02 PASS 後に再 inventory |

## 3. 横断対応（report section 以外）

| 項目 | 既存の正本 / 制約 | report で固定するもの |
|---|---|---|
| tenant timezone / clock | `AccountingTimezoneResolver`、`AccountingTenantContextHolder`。resolver は tenant config と `Asia/Tokyo` fallback を扱う | `timezoneId`、`periodStart/end`、`asOf`、`generatedAt` の offset、使用した clock/基準時刻。scheduler/worker は try-finally で context を解除 |
| time/history | platform invariant の inclusive start/end、as-of resolver、monthly closing snapshot | period の両端を inclusive と明記。速報/確定、closedThrough、correction/version を表示 |
| data scope | `DataScopeService`、`OrganizationScopeService`。query-level scope、empty set は 0 rows | `scopeOwnerType/id`、scope policy version、allowed-set hash、recipient scope check result。session user を scheduler の根拠にしない |
| document | `DocumentService` / `DocumentServiceImpl`。GENERATED、hash、CLEAN、version、legal hold、download audit | snapshot と document の source/business key、content hash、version、CLEAN/access policy。DB transaction 中に外部 I/O を閉じ込めない |
| notification / delivery | `NotificationService` → `NotificationOutboxService` → dispatcher。dedupe、retry、DLQ/FAILED、manual replay の基盤あり | delivery は outbox の idempotency key、recipient preview、scope decision、link expiry/re-auth、attempt/error audit を保存。デフォルトは添付ではなく期限付き link 候補 |
| backup / restore | `ops/backup` の full/checkpoint/binlog、別 recovery target restore、quiesce/cutover、drill evidence | snapshot/document/outbox が同じ backup/restore 検証範囲に入り、restore 後に hash、version、recipient ACL、未送信 delivery を検証 |

## 4. 発見した実装制約

1. Dashboard、SalesPerformance、Invoice の一部は `LocalDate.now()` / 現在月を内部で既定化している。report adapter は explicit tenant timezone、as-of、clock を渡せる境界が必要であり、session 依存 scheduler から直接呼び出してはならない。
2. `CashFlowForecastService.forecast` は現状 report scope を受け取らない。scope を偽装せず、全社限定または scoped adapter の承認を DG-10 の決定項目にする。
3. `SalesPerformanceService` は現状 report 用の DataScope 契約が不足している。既存の契約/提案の口径をコピーして補うのではなく、正本 service の scope 契約を先に定義する。
4. `UtilizationForecastService` の現行実装には Java 側の filtering がある。report では query-level scope と同じ結果になることを contract test で確認する。
5. 現行の `DocumentService` は hash、version、CLEAN、scope 検証、download audit を持つ。report document はこれを通し、独自 file table/storage bypass を作らない。
6. outbox dispatcher は既に ShedLock と retry/FAILED を持つ。report scheduler は別名 lock、idempotency、explicit system principal/context を設計し、既存通知の session/request context を再利用しない。
7. ServiceDesk/SLA は current base にないため、SLA 数値の仮実装・推定・空欄の自動配布を行わない。

## 5. DG-10 で確認する質問

- section ごとの速報/確定の判定と、section 部分失敗時に report を配布可能とするか。
- `CashFlow` と `SalesPerformance` の許容 scope（全社のみか、組織/営業/顧客までか）。
- report template/version を変更した際、旧 run の再生成を別 version とするか、version 差分をどこまで表示するか。
- recipient preview の承認者・有効期限・link 再認可の強度・delivery channel。
- snapshot/document/outbox の保持期間、削除・legal hold・restore 後の再送条件。
