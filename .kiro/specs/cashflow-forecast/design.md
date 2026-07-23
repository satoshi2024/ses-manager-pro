# Design — 資金繰り予測（FR-05）

親: ロードマップ FR-05。新規テーブル不要。再利用: `Invoice`/`InvoicePayment`/`BpPayment`/payroll・`MonthlyRevenueCalcService`・`SystemConfig`・`NotificationService`・Dashboard(Chart.js)。

## 1. 集計サービス `service/billing/CashFlowForecastService`

- `CashFlowForecastDto forecast(YearMonth from, int months, BigDecimal openingBalance)`:
  - 各月 `m` について:
    - 入金 = Σ `t_invoice`（`status != 入金済`）で `due_date` の月が `m` のものの残額（`total − 入金済合計`）。`InvoicePayment` で入金済を差し引く。
    - 支払 = Σ BP支払（`t_bp_payment` の支払予定月＝対象 `work_month`+サイト or 明示予定日）＋ 給与（payroll 月額。`FreeeEmployeeLink`/固定）＋ 固定費（`m_system_config` `cashflow.fixed-cost`）。
    - ネット = 入金 − 支払。累計残高 = 前月残高 + ネット（初月は openingBalance）。
  - 口径は `MonthlyRevenueCalcService` の対象判定に寄せる（売上=請求ベースと確定実績の整合。MI-09/A7-09の口径と矛盾させない）。
- 設定: `m_system_config` に `cashflow.opening-balance` / `cashflow.fixed-cost` / `cashflow.alert-threshold`。

## 2. 警告

- forecast 実行時、累計残高 < `cashflow.alert-threshold` の月を検出 → 日次/月次スケジューラ or 画面表示時に `NotificationService` で管理者へ（重複通知は既存 dedupe に倣う）。

## 3. API・画面

- `GET /api/cashflow/forecast?from=&months=` → `CashFlowForecastDto`（月次配列: month, inflow, outflow, net, balance, breakdown）。
- `dashboard` に CFタブ（`static/js/modules/dashboard.js` または新 `cashflow.js`）。棒（inflow/outflow）＋折れ線（balance）、閾値割れ月を赤。CSV出力。
- 権限: `SecurityConfig` 静的制約＋メニューで管理者/マネージャー（新メニュー `cashflow` を V45 で seed、または dashboard 内タブとして既存権限に載せる。※要確認）。

## 4. テスト
- `CashFlowForecastServiceTest`: 未入金の due_date 月計上、既入金除外、BP/給与/固定費の合算、累計残高、閾値割れ検出。ダッシュボード当月と口径整合（`MonthlyRevenueCalcService` と突合）。
</content>
