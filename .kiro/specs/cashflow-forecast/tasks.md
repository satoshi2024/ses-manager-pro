# Tasks — 資金繰り予測（FR-05）

新規テーブル不要（設定は `m_system_config`）。

- [ ] F1. 設定キー（V45 or 既存systemconfig）
  - **Objective**: `cashflow.opening-balance`/`fixed-cost`/`alert-threshold` を追加、system-config 画面で編集可能に。
  - **Demo**: 画面で3値を保存できる。

- [ ] A. 集計サービス
  - **Objective**: `CashFlowForecastService.forecast`。
  - **実装ガイダンス**: design 1章。入金=請求残額を due_date 月へ、支払=BP+給与+固定費、累計残高。口径は `MonthlyRevenueCalcService` 整合。
  - **テスト要件**: `CashFlowForecastServiceTest`（計上ルール/既入金除外/累計/閾値割れ/口径突合）。
  - **Demo**: curl で6ヶ月CFが返る。

- [ ] B. API＋ダッシュボードUI
  - **Objective**: `/api/cashflow/forecast` とCFタブ（棒＋折れ線・閾値強調・CSV）。
  - **実装ガイダンス**: design 3章。Chart.js。管理者/マネージャー限定。
  - **テスト要件**: 権限外403。
  - **Demo**: ダッシュボードでCFグラフとマイナス月の警告。

- [ ] C. 警告通知
  - **Objective**: 閾値割れ月を `NotificationService` で管理者へ。
  - **テスト要件**: 閾値割れで通知生成、dedupe。
  - **Demo**: 閾値を高くすると通知が出る。

- [ ] M. i18n・仕上げ（5ロケール、全量緑）。

## 完了条件
- 6ヶ月のCF（入金/支払/ネット/残高）が算出され、マイナス月が強調＋通知される。
- 当月の売上口径がダッシュボードKPIと突合するテストが緑。
</content>
