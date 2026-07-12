# Implementation Plan — 稼動分析・帳票出力(P7)

- [x] 1. `AnalyticsService` の実装(テスト駆動)
  - **Objective**: 稼動率推移と Bench 一覧の集計ロジック。
  - **実装ガイダンス**: design.md 2章。1クエリ取得+メモリ集計、N+1 禁止。先に境界テスト(月末開始/終了契約、分母0、Bench 経過日数)。
  - **テスト要件**: `AnalyticsServiceImplTest`(H2)。
  - **Demo**: `mvn test -Dtest=AnalyticsServiceImplTest` パス。

- [x] 2. 稼動分析画面
  - **Objective**: `/analytics` にグラフ + Bench 一覧。
  - **実装ガイダンス**: design.md 4章。メニューシード SQL 追加。`AnalyticsApiController` + `analytics/index.html` + `modules/analytics.js`。
  - **Demo**: サイドバー「稼動分析」→ 12ヶ月推移グラフと Bench 一覧(30/60日超の色分け)が表示される。

- [x] 3. POI 導入と `ExcelExportService`(テスト駆動)
  - **Objective**: 3種類の Excel 生成。
  - **実装ガイダンス**: design.md 1章・3章。SXSSF + 共通スタイルヘルパー。読み戻し検証テスト。
  - **テスト要件**: ヘッダー行・件数・金額書式の検証。
  - **Demo**: `mvn test -Dtest=ExcelExportServiceTest` パス。

- [x] 4. 出力 API と画面ボタン
  - **Objective**: 要員/契約/月次売上の Excel ダウンロード。
  - **実装ガイダンス**: `ExportApiController`(既存 api_prefix 配下にパスを置き権限を引き継ぐ)。各一覧に「Excel出力」ボタン(`window.location.href` 方式、現在の検索条件を反映)。
  - **テスト要件**: `@WebMvcTest` で Content-Type / Content-Disposition。
  - **Demo**: 要員一覧でステータス絞り込み→Excel出力→絞り込み済みデータの xlsx が開ける(日本語ファイル名)。

- [x] 5. 月次売上レポートの実績/見込み区分(P5 導入済みの場合)
  - **Objective**: revenue-export に「実績/見込み」列を付ける。
  - **実装ガイダンス**: P5 の実績切替ロジック(`DashboardServiceImpl`)を再利用。
  - **Demo**: 確定実績のある月に「実績」、無い月に「見込み」と出力される。
