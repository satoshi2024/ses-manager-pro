# 既存集計 inventory（NF-10 / DG-10 APPROVED）

## 1. 調査方針

report 専用の集計式を作らない。各 section は既存の正本 service/DTO を呼び出す adapter とし、report snapshot はその戻り値、入力の as-of、スコープ、freshness、正本の識別子を固定する。画面・既存 export・report は同じ section value contract を消費する前提である。

金額は既存実装の JPY / `BigDecimal` 口径を維持し、report 側で double 化、再計算、別の丸めを行わない。以下は2026-08-28に承認されたcutoff、scope、delivery境界を既存資産へ対応付けたものである。

## 2. section 対応表

| report section | 現行の正本 service / DTO | 現行の利用経路 | 承認済み cutoff / timezone | 承認済み scope owner | snapshot / document / delivery |
|---|---|---|---|---|---|
| 売上・粗利（確定実績） | `DashboardService` / `DashboardSummaryDto`、金額口径は `MonthlyRevenueCalcService` / `MonthlyAmount` | `/api/dashboard/summary`、`dashboard/index.html`、月次売上 export | 対象 `YearMonth` の月初〜月末。月次締め完了後のみ確定版を生成し、report timezoneは`Asia/Tokyo` | 管理者は全社、マネージャーは許可組織。system principalの保存済みscopeを対象月as-ofで解決 | `sales`、`grossProfit`、`isActual`、source count/hash、dataAsOfを固定。PDF/XLSX/CSVは同一snapshotを読む。`DocumentService`のGENERATED documentとscope検証を使用 |
| 売上着地予測 | Dashboard 内の forecast 系列、`DashboardSummaryDto`（独立 `RevenueForecastService` は存在しない） | Dashboard の forecast chart、既存 revenue forecast spec の系列 | 生成時点のopen proposalと対象月。forecastとactualを別系列で表示し、`Asia/Tokyo`の月初/月末境界を明記 | actualと同じ管理者全社/マネージャー許可組織scope | `forecast`、対象月、source version、pipeline count/amount、freshnessを固定。現行式をreport側へ複製しない |
| 稼働率・Bench（実績/予測） | `UtilizationCalcService` / `UtilizationSummary`、将来系列は `UtilizationForecastService` / `UtilizationForecastDto` | `/api/dashboard/utilization-forecast`、Dashboard の予測表 | 月単位。契約の start/end、auto-renew、assume-renewを対象月に対して評価。timezoneは`Asia/Tokyo` | engineer/contractの管理者全社/マネージャー許可組織scope。schedulerはrequest/sessionを使用しない | working/bench/total/utilizationRate、actual/forecast、contract as-of、freshnessを固定 |
| 資金繰り | `CashFlowForecastService` / `CashFlowForecastDto`。reconciliationは同serviceと`MonthlyRevenueCalcService` | DashboardのCF tab、cashflow export | `from`の`YearMonth`と月数、opening balanceを入力として固定。請求due dateと支払月は`Asia/Tokyo` | 管理者は全社、マネージャーは許可組織。現行APIにscope引数がないため、実装時に明示scope adapterを追加する | inflow/outflow/balance/alerts/reconciliationを固定。scope未確定のまま配布しない |
| 管理会計（実績/予測/予算） | `ManagementAccountingService` / `ManagementAccountingSummaryDto`、`ManagementAccountingContractRow` | `/api/management-accounting/summary`、`/export`、`/drilldown`、画面、budget UI | `YearMonth`の月初〜月末。確定は`MonthlyAccountingDimension`の月次snapshot、forecastはcurrent contract view。境界は`Asia/Tokyo` | 管理者は全社、マネージャーは許可組織。filter（legal entity/org/cost center/customer/project/sales）を保存 | actual/forecast source、summary/detail rows、budget/variance、dimension snapshot versionを固定。既存exportと同一value contract |
| 営業成績・commission | `SalesPerformanceService` / `SalesPerformanceSummary` 等、金額は `MonthlyRevenueCalcService` | `/api/sales-performance`、`/commission-rule`、営業成績画面 | 対象`YearMonth`。契約/提案の月境界は`Asia/Tokyo`で固定し、commission config versionも保存 | 管理者は全社、マネージャーは許可組織。現行serviceに明示scopeがないため、実装時にscope境界をadapterへ追加 | assigned/closed/win rate/sales/gross profit/commissionとrule versionを固定。未帰属行も削除せず明示 |
| AR エイジング | `InvoiceService` / `AgingReportDto`、詳細も同 service | `/api/invoices/aging`、`/aging/detail`、`/aging-export`、invoice UI | 明示`asOf`日。due dateのbucketをasOfに対して評価し、期限未設定/未送付を別区分。timezoneは`Asia/Tokyo` | 管理者は全社、マネージャーは許可組織。`OrganizationScopeService.allowedInvoiceIds(asOf)`とsales customer scopeを交差し、empty setは0 rows | customer bucket、balance、asOf、source query snapshotを固定。既存Excel exportと同じ値を出力 |
| BP 支払 / 支払予定 | CashFlowのoutflow、管理会計のcost側データを正本とする | Dashboard CF、invoice BP payment API、管理会計 | 対象月と支払site month。未払/支払済をas-ofで区別。timezoneは`Asia/Tokyo` | 管理者は全社、マネージャーは許可組織。recipient-specific deliveryに組織条件を暗黙追加しない | status、予定/実績、金額、as-of、freshnessを固定。重複した別計算を作らない |
| 契約終了・更新見込み | `UtilizationForecastService` と Dashboard の rolloff 表示 | Dashboard forecast/rolloff UI | 対象月の契約end dateと後続契約・auto-renew。`Asia/Tokyo`の月末境界を含む | 管理者は全社、マネージャーは許可組織 | engineer/contract/project、rolloff、renewal decision、as-ofを固定 |
| ServiceDesk / SLA | 現行 base に正本 service/DTO/API/画面なし | なし（NF-02依存） | 対象外 | 対象外 | NF-02 PASS後に再inventoryし、承認を得るまで実装・配布対象に含めない |

## 3. 横断対応（report section 以外）

| 項目 | 既存の正本 / 制約 | report で固定するもの |
|---|---|---|
| tenant timezone / clock | `AccountingTimezoneResolver`、`AccountingTenantContextHolder`。本reportの承認timezoneは`Asia/Tokyo` | `timezoneId`、`periodStart/end`、`asOf`、`generatedAt`のoffset、使用したclock/基準時刻。scheduler/workerはtry-finallyでcontextを解除 |
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

## 5. 承認済み境界の実装確認

- sectionごとの速報/確定は、未締めを速報、月次締め完了後を確定とする。
- Cash FlowとSalesPerformanceも管理者全社/マネージャー許可組織scopeを持ち、現行APIに不足する境界はadapterで補う。
- template変更時の明示的再生成は新version、通常retryは同一run・同一snapshotとする。
- recipient previewは生成前必須、deliveryはoutbox経由のアプリ内通知＋期限付きlink、期限切れ・権限喪失・組織異動は拒否しdownload時再認証する。
- snapshot/documentは7年保持し、restore後にhash/version/ACL/outbox未送信状態を検証する。
