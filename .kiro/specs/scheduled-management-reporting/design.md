# 承認済み設計（NF-10 / DG-10）

## 1. 目標アーキテクチャ

`template/version → run → section snapshot → DocumentService → notification outbox / link delivery` の流れで実装する。report adapter は既存の正本 service/DTO を呼び、集計式を持たない。scheduler は session/request に依存せず、保存済みの system principal、`Asia/Tokyo`、scope owner、as-of context を明示して実行する。

実装対象テーブルは `m_report_template`、`m_report_template_version`、`m_report_schedule`、`t_report_run`、`t_report_section_snapshot`、`t_report_delivery`。snapshot/documentは7年間保持する。通常retryは同一runのsection snapshotを一意制約で再利用し、明示的な再生成だけ新run/versionを作る。

## 2. section adapter の責務

| adapter | 呼び出す正本 | 禁止事項 | snapshot に残す識別子 |
|---|---|---|---|
| Revenue adapter | `DashboardService` + `MonthlyRevenueCalcService` / dashboard DTO | 売上・粗利の式の再実装、現在 DB 値で旧 run を再生成 | service class、DTO、対象 month、source rows/hash |
| Utilization adapter | `UtilizationCalcService` / `UtilizationForecastService` | Bench/稼働率の別式、session の自己 scope | month、actual/forecast、contract as-of、scope hash |
| CashFlow adapter | `CashFlowForecastService` | scopeを全社/許可組織以外へ拡張、money の double 化 | from、months、opening-balance source、reconciliation as-of、scope |
| Management accounting adapter | `ManagementAccountingService` | `MonthlyAccountingDimension` の snapshot を current org で上書き | month、actual/forecast source、dimension version |
| Sales performance adapter | `SalesPerformanceService` + `MonthlyRevenueCalcService` | commission/win rate の再計算、session self の利用 | month、rule config version、unattributed row |
| AR adapter | `InvoiceService.aging(asOf)` | aging bucket の再実装、scope 外 customer の列挙 | asOf、scope query hash、bucket version |
| ServiceDesk adapter | 未存在 | NF-02 PASS前の推定値・仮section | 実装対象外 |

## 3. 不変性・時刻・スコープ

### 3.1 time / as-of decision table

| 対象 | business time | as-of / cutoff | 承認済み規則 |
|---|---|---|---|
| 月次 section | tenant timezone の月初〜月末（両端含む） | monthly closing 状態と確定 work record | 速報/確定の表示、closedThrough を保存 |
| forecast | 生成時点の pipeline/contract view | `generatedAt` と forecast source cutoff | forecast を actual と別系列で保存 |
| AR | tenant timezone の明示日 | invoice/payment の as-of | overdue bucket を asOf 固定 |
| schedule | scheduler firing instant を UTC で受け、tenant timezone に変換 | 保存済み schedule version | DST/月末境界を test |

`AccountingTimezoneResolver` と `AccountingTenantContextHolder` を利用し、ハードコードした `Asia/Tokyo` を report ロジックへ埋め込まない。context は try-finally で解除する。

### 3.2 subject × operation × visibility decision table

| subject | operation | visibility owner | 承認済み制御 |
|---|---|---|---|
| report template/version | create/update/publish | 管理者/マネージャーの承認済み scope | version CAS、変更監査 |
| run/snapshot | generate/view/regenerate | run に固定した scope owner | current user/DB 値で再解決しない |
| document | download/open | document scope + recipient scope | `DocumentService`、CLEAN、link expiry/re-auth |
| delivery | enqueue/retry/replay | delivery recipient と保存済み scope | preview、idempotency、DLQ manual authorization |
| notification | list/open | recipient-specific scope | recipient notification に暗黙の組織 scope を足さない |

### 3.3 state / competition decision table

| 対象 | 承認済み状態 | 競合制御 |
|---|---|---|
| schedule/run | due、claimed、running、succeeded、partial、failed、retryable | ShedLock、run unique key、state CAS。partial/failedはdelivery不可 |
| section | pending、succeeded、stale、failed | section key unique、attempt audit |
| generation | pending、generating、generated、failed、retrying | idempotency key + content/source hash。同一runのsnapshot重複不可 |
| delivery | pending、processing、sent、retry、failed/DLQ | outbox claim、dedupe、manual replay authorization |

## 4. 承認済みの実装判断

1. report利用者は管理者/マネージャー。管理者は全社、マネージャーは許可された組織scope。schedule有効化は管理者のみ。
2. 対象は月次。timezoneは`Asia/Tokyo`。速報は未締め＋dataAsOf/freshness、確定版は月次締め完了後のみ。
3. snapshot/document保持は7年。過去runはimmutable。template・現在DB・現在権限の変更で過去runを変化させない。
4. 通常generation retryは同一run・同一snapshot、明示的再生成は新version。section一つでも失敗したrunは`PARTIAL`/`FAILED`として配布停止。
5. recipient previewを生成前に必須とし、generation/downloadの両方でscope検証。権限喪失、組織異動、link期限切れはdownload拒否、download時は再認証。
6. deliveryはnotification outbox経由のアプリ内通知＋期限付きlink。メール添付なし。PDF/XLSX/CSVは同一snapshotから生成。
7. ServiceDesk/SLAはNF-02 PASSまで対象外。report独自SQL・集計式・丸めは禁止。

F1は承認Base `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`へ統合済みの専用branchで、最新migration番号、V1/H2 schema、MySQL smoke、shape testをそろえて開始する。
