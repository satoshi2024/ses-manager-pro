# Tasks — 将来稼働率・Bench予測（FR-07）

新規テーブル不要。既存ダッシュボード拡張。

- [x] A. 予測サービス
  - **Objective**: `forecast(months)` で月次稼働/Bench/稼働率＋ロールオフ一覧。
  - **実装ガイダンス**: design 1章。有効契約判定を当月ダッシュボードと共用（口径一致）。autoRenew見込みは設定。
  - **テスト要件**: `UtilizationForecastServiceTest`（endDate跨ぎ/null/autoRenew/当月一致/ロールオフ/スコープ）。
  - **Demo**: curl で3ヶ月予測。

- [x] B. API＋ダッシュボードUI
  - **Objective**: `/api/dashboard/utilization-forecast` と折れ線＋ロールオフ表。
  - **実装ガイダンス**: design 2章。FR-06 へリンク。
  - **Demo**: ダッシュボードで将来稼働率とロールオフ予定要員が見える。

- [x] M. i18n・仕上げ（5ロケール、全量緑）。

## 完了条件
- 今後3ヶ月の稼働率/Bench見込みが算出され、当月値がダッシュボードKPIと一致するテストが緑。
- 月別ロールオフ予定要員一覧が出て、更新カレンダー（FR-06）と相互リンクする。
</content>
