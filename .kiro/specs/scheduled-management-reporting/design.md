# 設計ドラフト（DG-10 決定前）

## 1. 目標アーキテクチャ

`template/version → run → section snapshot → DocumentService → notification outbox / link delivery` の流れを候補とする。report adapter は既存の正本 service/DTO を呼び、集計式を持たない。scheduler は session/request に依存せず、保存済みの system principal、tenant timezone、scope owner、as-of context を明示して実行する。

候補テーブルは `m_report_template`、`m_report_template_version`、`m_report_schedule`、`t_report_run`、`t_report_section_snapshot`、`t_report_delivery`。これは NF-10 の候補であり、DG-10/F1 の承認前に migration を作成しない。

## 2. section adapter の責務

| adapter | 呼び出す正本 | 禁止事項 | snapshot に残す識別子 |
|---|---|---|---|
| Revenue adapter | `DashboardService` + `MonthlyRevenueCalcService` / dashboard DTO | 売上・粗利の式の再実装、現在 DB 値で旧 run を再生成 | service class、DTO、対象 month、source rows/hash |
| Utilization adapter | `UtilizationCalcService` / `UtilizationForecastService` | Bench/稼働率の別式、session の自己 scope | month、actual/forecast、contract as-of、scope hash |
| CashFlow adapter | `CashFlowForecastService` | scope 未定のまま個人/顧客 report を許可、money の double 化 | from、months、opening-balance source、reconciliation as-of |
| Management accounting adapter | `ManagementAccountingService` | `MonthlyAccountingDimension` の snapshot を current org で上書き | month、actual/forecast source、dimension version |
| Sales performance adapter | `SalesPerformanceService` + `MonthlyRevenueCalcService` | commission/win rate の再計算、session self の利用 | month、rule config version、unattributed row |
| AR adapter | `InvoiceService.aging(asOf)` | aging bucket の再実装、scope 外 customer の列挙 | asOf、scope query hash、bucket version |
| ServiceDesk adapter | 未存在 | NF-02 前の推定値・仮 section | `UNAVAILABLE / DEPENDENCY_NOT_ACCEPTED` のみ検討 |

## 3. 不変性・時刻・スコープ

### 3.1 time / as-of decision table

| 対象 | business time | as-of / cutoff | 候補となる決定 |
|---|---|---|---|
| 月次 section | tenant timezone の月初〜月末（両端含む） | monthly closing 状態と確定 work record | 速報/確定の表示、closedThrough を保存 |
| forecast | 生成時点の pipeline/contract view | `generatedAt` と forecast source cutoff | forecast を actual と別系列で保存 |
| AR | tenant timezone の明示日 | invoice/payment の as-of | overdue bucket を asOf 固定 |
| schedule | scheduler firing instant を UTC で受け、tenant timezone に変換 | 保存済み schedule version | DST/月末境界を test |

`AccountingTimezoneResolver` と `AccountingTenantContextHolder` を利用し、ハードコードした `Asia/Tokyo` を report ロジックへ埋め込まない。context は try-finally で解除する。

### 3.2 subject × operation × visibility decision table

| subject | operation | visibility owner | 候補制御 |
|---|---|---|---|
| report template/version | create/update/publish | 管理者/マネージャーの承認済み scope | version CAS、変更監査 |
| run/snapshot | generate/view/regenerate | run に固定した scope owner | current user/DB 値で再解決しない |
| document | download/open | document scope + recipient scope | `DocumentService`、CLEAN、link expiry/re-auth |
| delivery | enqueue/retry/replay | delivery recipient と保存済み scope | preview、idempotency、DLQ manual authorization |
| notification | list/open | recipient-specific scope | recipient notification に暗黙の組織 scope を足さない |

### 3.3 state / competition decision table

| 対象 | 状態候補 | 競合制御 |
|---|---|---|
| schedule/run | due、claimed、running、succeeded、partial、failed、retryable | ShedLock、run unique key、state CAS |
| section | pending、succeeded、stale、failed | section key unique、attempt audit |
| generation | pending、generating、generated、failed、retrying | idempotency key + content/source hash |
| delivery | pending、processing、sent、retry、failed/DLQ | outbox claim、dedupe、manual replay authorization |

## 4. DDL/実装前の未決定事項

1. tenant key の実際の report scope と、organization/sales/customer の組み合わせ。
2. snapshot の retention、legal hold、削除、restore 後の再送。
3. partial section を含む document を生成・配布できるか。
4. generation retry は同一 run の attempt とするか、新 run/version とするか。
5. recipient preview の承認者、delivery channel、link TTL と再認可方法。
6. CashFlow/SalesPerformance に必要な explicit scope adapter の境界。

これらは DG-10 の判断なしに default を置かない。F1 は決定後に最新 migration 番号、V1/H2 schema、MySQL smoke、shape test をそろえて開始する。
