# Design — 将来稼働率・Bench予測（FR-07）

親: ロードマップ FR-07。再利用: `DashboardServiceImpl`（稼働率算定）・`Contract`（`endDate`/`autoRenew`）・`Engineer`・`engineer-availability-visualization`・`EngineerSales`（担当営業）。新規テーブル不要。

## 1. 予測サービス

- `DashboardServiceImpl`（または新 `UtilizationForecastService`）に `forecast(int months)`:
  - 各月 `m`（今月+1..+N）について、要員ごとに「`m` に有効な契約があるか」を判定。
    - 有効 = `startDate ≤ m末` かつ（`endDate` が `m` 以降 or null）。
    - `autoRenew=1` の契約は設定により「更新見込みで継続」とみなすオプション（既定: 継続とみなす／`m_system_config` `forecast.assume-renew`）。
  - 稼働見込み数／Bench見込み数／稼働率見込み（=稼働/(稼働+Bench)）を月次算出。
  - 判定関数は当月について既存ダッシュボードの稼働率と一致すること（同じ有効契約判定を共用。A7-09の口径分裂を再発させない）。
- ロールオフ一覧: 対象月に契約が切れてBench化する要員（要員・契約・終了日・`EngineerSales` の主担当）を列挙。

## 2. API・画面

- `GET /api/dashboard/utilization-forecast?months=3` → 月次配列（month, workingCount, benchCount, rate）＋ロールオフ一覧。
- ダッシュボードに「稼働率予測」折れ線＋「ロールオフ予定要員」テーブル（`dashboard.js`）。FR-06 の更新カレンダーへリンク。
- `DataScopeService` 尊重。

## 3. テスト
- `UtilizationForecastServiceTest`: 有効契約判定（endDate跨ぎ/null/autoRenew）、当月値がダッシュボードと一致、ロールオフ抽出、スコープ。
</content>
