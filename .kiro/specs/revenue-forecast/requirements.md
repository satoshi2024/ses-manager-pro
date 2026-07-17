# Requirements — 売上着地予測（revenue-forecast）

`customer-feature-proposals` P5 の spec 化。提案パイプラインをステージ別受注確率で加重し、
確定契約ベースの既存「見込み」に重ねて表示する。

**確定済みの設計判断**:

- 予測は**表示専用の別系列**とし、実績・見込みの既存口径（`MonthlyRevenueCalcService`）・
  営業成績・Excel帳票には一切加算しない（経営の参考値と会計的な見込みを混ぜない）。
- オープン提案の開始月仮定は「翌月1日」（成約→ドラフトの既存規約 `LocalDate.now().plusMonths(1).withDayOfMonth(1)` と同一）。
- 寄与期間は開始月から**予測対象の全チャート月**とする（SES 契約は継続前提のため。
  端数の厳密さより一貫した単純規則を優先し、画面に仮定を注記する）。

## R1. 受注確率の設定

1. THE ステージ別受注確率 SHALL `m_system_config` で管理する: `forecast.win-rate.screening`（書類選考中・既定20）/ `.first-interview`（一次面接・既定40）/ `.second-interview`（二次面接・既定60）/ `.awaiting`（結果待ち・既定80）——単位は %。
2. THE 機能全体の有効/無効 SHALL `forecast.enabled`（既定 true）で切り替えられる。
3. THE 設定 SHALL 既存のシステム設定画面から編集でき、% 単位である旨を説明欄・画面注記で明示する（commission.rate と同じ百分率規約）。

## R2. 予測値の算出

1. THE 月次予測売上 SHALL `確定ベース見込み（既存共通口径の calc 結果）+ Σ(オープン提案の提示単価 × ステージ確率 ÷ 100)` とする。オープン提案 = status が 書類選考中/一次面接/二次面接/結果待ち。
2. THE 各オープン提案 SHALL 仮定開始月（計算実行時点の翌月）以降のチャート月にのみ寄与する（当月・過去月には寄与しない）。
3. WHEN 提案の `proposed_unit_price` が NULL の場合、THE 当該提案 SHALL 寄与 0 として扱う。
4. THE 粗利予測 SHALL 行わない（原価未確定のため。売上のみ）。

## R3. ダッシュボード表示

1. THE 月次売上チャート SHALL 予測系列を点線で重ねて表示する（`forecast.enabled=false` またはオープン提案 0 件の月は既存表示のまま）。
2. THE 予測系列の tooltip SHALL 内訳（確定ベース + パイプライン加重分）と件数を表示する。
3. THE チャート下部 SHALL 仮定の注記（開始月=翌月・確率は設定値・参考値であり実績/見込みに不算入）を表示する。
4. THE API SHALL 既存 `DashboardSummaryDto` の revenue チャートに `forecast`（List<Long>、無効時 null）を追加する後方互換の形とする（既存フィールドの意味・値は不変）。

## R4. テスト規約

1. 加重計算（確率・NULL単価・寄与開始月の境界）・enabled=false で null・既存系列が不変であることを単体テストで検証する。
