# Design — 売上着地予測（revenue-forecast）

対象コード: `DashboardServiceImpl` / `DashboardSummaryDto` / `dashboard.js` / `SystemConfigService`（読むだけ）/
マイグレーション（config シードのみ）

## 1. マイグレーション（`V30__revenue_forecast_config.sql`※）

※ 番号は実装時点の最新+1。全体調整は `customer-feature-proposals/README.md` 参照。

```sql
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('forecast.enabled',                    'true', '売上着地予測の表示(true/false)'),
  ('forecast.win-rate.screening',         '20',   '受注確率: 書類選考中(%)'),
  ('forecast.win-rate.first-interview',   '40',   '受注確率: 一次面接(%)'),
  ('forecast.win-rate.second-interview',  '60',   '受注確率: 二次面接(%)'),
  ('forecast.win-rate.awaiting',          '80',   '受注確率: 結果待ち(%)');
```

DDL なし・H2 スキーマ同期不要（`m_system_config` は既存。シードは H2 リプレイに V9 系が
シード込みで載っている方式を確認し、必要ならテストデータ側で setup する——予測ロジックは
純関数化するためテストは config 値を直接渡す設計とし、H2 シード依存を避ける）。

## 2. 計算ロジック（`DashboardServiceImpl` 内 or 小クラス）

集計は既存 `getSummary` の月ループへ追加する:

```java
// forecast.enabled のとき: オープン提案を1回ロードし、月ごとに加重合計を作る
List<Proposal> open = proposalMapper.selectList(new QueryWrapper<Proposal>()
        .in("status", 書類選考中, 一次面接, 二次面接, 結果待ち));
YearMonth assumedStart = YearMonth.from(LocalDate.now()).plusMonths(1); // ドラフト規約と同一
long pipelinePerMonth = open.stream()
        .filter(p -> p.getProposedUnitPrice() != null)
        .mapToLong(p -> p.getProposedUnitPrice()
                .multiply(rateFor(p.getStatus()))            // % (BigDecimal)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValue())
        .sum();
// 月 m の予測 = 既存 calc(m).sales + (m >= assumedStart ? pipelinePerMonth : 0)
```

- ステージ→確率の解決 `rateFor(status)` は `StatusConstants` の提案ステータス定数を switch し
  `systemConfigService.getDecimal("forecast.win-rate.…", 既定値)`。未知ステータスは 0。
- **端数**: 提案ごとに円未満切り捨て（DOWN）で合算（合算後に丸めない——件数内訳と一致させるため）。
- `forecast.enabled=false` → DTO の `forecast` を null（チャート系列自体を出さない）。
- 純関数化: 計算本体は `computePipelinePerMonth(List<Proposal>, Map<String,BigDecimal> rates)`
  の static/パッケージメソッドに切り出し、単体テストは DB・config 不要で書く。
- 内訳 tooltip 用に `forecastPipelineCount`（オープン提案件数）と `forecastPipelineAmount`
  （加重合計/月）も DTO へ入れる（全月共通値のためスカラーでよい）。

## 3. DTO / API（後方互換）

`DashboardSummaryDto.RevenueChartDto` へ追加（既存フィールド不変）:

```java
private List<Long> forecast;          // 無効時 null。有効時は labels と同じ長さ
private Integer forecastPipelineCount;    // 〃
private Long forecastPipelineAmount;      // 〃(月あたり加重合計)
```

## 4. フロントエンド（`dashboard.js`）

- `chartsData.revenue.forecast` が非 null のとき、Chart.js の dataset を追加:
  `borderDash: [6, 4]`・塗りなし・既存 sales 系列と同系色の薄色。ラベル
  `dashboard.chart.forecast_label`。
- tooltip: 予測系列は「確定ベース ¥X + パイプライン ¥Y（N件加重）」。
- チャート下部に注記（`dashboard.forecast.note`）: 開始月仮定・確率設定・参考値である旨。
- `system-config.js` の `unitNoteFor` に `forecast.win-rate.*` の百分率注記を追加
  （既存 `systemConfig.note.commissionRate` を共用してよい）。

## 5. i18n

`dashboard.chart.forecast_label` / `dashboard.forecast.note` — 4言語。

## 6. レーン分割

小規模のため単一レーン逐次（1→2）。**競合注意**: `DashboardServiceImpl`/`dashboard.js` は
`dashboard-improvements` spec（未着手）と交差する——同時に走らせない。

## 7. テスト方針

| 対象 | ケース |
|---|---|
| computePipelinePerMonth | 4ステージ加重合算 / NULL単価=0 / 未知ステータス=0 / 提案ごと切り捨て |
| getSummary | assumedStart 前の月は加算なし・以降は加算 / enabled=false で forecast=null / 既存 sales/profit/isActual 系列が予測有効時も不変（回帰） |
| DTO | 後方互換: 既存フィールドの JSON 形が不変（既存 DashboardApi テストがグリーンのまま） |
