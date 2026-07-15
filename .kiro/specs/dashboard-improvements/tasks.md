# Implementation Plan

本タスクリストは `requirements.md` / `design.md` の内容をテスト駆動・段階的統合で実装するための作業手順である。各タスクは前のタスクの成果物の上に積み上げる形で構成し、孤立した（どこにも統合されない）コードが生まれないようにしている。

前提確認: `pom.xml` には `spring-boot-starter-test` と `spring-security-test` が既に依存として含まれているが、`src/test` ディレクトリは未作成のため、Task 1 で最初のテストクラスを作成しつつ動作確認を行う。

---

- [x] 1. テスト基盤のセットアップと動作確認
  - **Objective**: JUnit5 + Mockitoベースのテストが実行可能な状態を確立する。
  - **実装ガイダンス**:
    - `src/test/java/com/ses/` 配下にテストソースルートを作成する。
    - 動作確認用に `src/test/java/com/ses/SesManagerApplicationTests.java`（Spring Bootの `@SpringBootTest` によるコンテキストロードテスト、既存の `SesManagerApplication` を対象）を作成する。
    - `mvn test` （または `./apache-maven-3.9.6/bin/mvn test`）を実行し、テストが正常に実行される（グリーン）ことを確認する。
  - **テスト要件**: このタスク自体がテスト基盤の検証。既存の起動設定（`application.yml`のDB接続等）でコンテキストロードが失敗する場合はテスト用プロファイル（`application-test.yml`、H2インメモリDB等）の追加を検討する。
  - **Demo**: `mvn test` コマンドを実行し、テストが1件以上グリーンで完了する。以降のタスクでこの上にテストを積み上げていく。

---

## 利益分析ページ（Requirement 1）

- [x] 2. `ContractProfitDto` の作成と `getProfitAnalysis()` のテスト駆動実装
  - **Objective**: 契約ごとの粗利計算ロジックをテストで固めてから実装する（design.md 1.3, 1.4節）。
  - **実装ガイダンス**:
    - `dto/dashboard/ContractProfitDto.java` を新規作成（design.md記載のフィールド: contractNo, engineerName, projectName, sellingPrice, costPrice, grossProfitAmount, grossProfitRate）。
    - `src/test/java/com/ses/service/impl/DashboardServiceImplTest.java` を新規作成。`@ExtendWith(MockitoExtension.class)` を用い、`ContractMapper`/`EngineerMapper`/`ProjectMapper` を `@Mock` 化する。
    - 先にテストを書く: 売上単価0円の契約で `grossProfitRate` が `"N/A"` になること（AC 1.4）、複数契約が `start_date` 降順で返ること（AC 1.5）、契約0件時に空リストを返すこと（AC 1.6の前提データ）、通常の売上/原価から粗利額・粗利率が正しく計算されること（AC 1.2, 1.3）。
    - テストが失敗する状態を確認した後、`DashboardService` interfaceに `getProfitAnalysis()` を追加し、`DashboardServiceImpl` に design.md 1.4節のロジックを実装してテストをグリーンにする。
  - **テスト要件**: 上記4パターンを含むユニットテスト。`mvn test` でグリーンになることを確認。
  - **Demo**: `mvn test` で `DashboardServiceImplTest` が全てパスする。まだAPI/画面には繋がっていないが、粗利計算ロジックの正しさが保証された状態。

- [x] 3. 利益分析API `/api/dashboard/profit-analysis` の追加と結線
  - **Objective**: Task 2で実装したServiceメソッドをHTTP経由で呼び出せるようにする。
  - **実装ガイダンス**:
    - `DashboardApiController` に `GET /profit-analysis` を追加（design.md 1.2節）。
    - `src/test/java/com/ses/controller/api/DashboardApiControllerTest.java` を新規作成し、`@WebMvcTest(DashboardApiController.class)` + `MockMvc` で `DashboardService` をモック化してテストする。`/api/dashboard/profit-analysis` が `ApiResult` 形式で200を返すことを検証。
  - **テスト要件**: コントローラーテスト1件以上。
  - **Demo**: アプリを起動し、`curl http://localhost:8080/api/dashboard/profit-analysis`（またはPostman/ブラウザ）でJSON形式の契約粗利一覧が返ることを確認できる。

- [x] 4. 利益分析ページ（`/dashboard/profit`）の画面実装と結線
  - **Objective**: Task 3のAPIを実際の画面から呼び出し、ユーザーが操作できる状態にする（500エラーの解消）。
  - **実装ガイダンス**:
    - `LoginPageController` に `GET /dashboard/profit` マッピングを追加し `"dashboard/profit"` を返す（design.md 1.1節）。
    - `templates/dashboard/profit.html` を新規作成（design.md 1.5節のテンプレート）。
    - `static/js/modules/dashboard-profit.js` を新規作成し、`/api/dashboard/profit-analysis` を呼び出してテーブル行を生成する。0件時は「契約データがありません」を表示（AC 1.6）、通信エラー時は `Toast.error(...)` を表示。
  - **テスト要件**: 追加のバックエンドテストは不要（Task 2,3で担保済み）。手動確認で十分。
  - **Demo**: ブラウザで `http://localhost:8080/dashboard/profit` にアクセスすると500エラーが出ず、契約ごとの粗利一覧テーブルが表示される。ダッシュボード画面の「利益分析を見る」リンクからも正しく遷移できる。

---

## 年度別売上・粗利集計（Requirement 2, 3）

- [x] 5. 会計年度計算ロジックのテスト駆動実装
  - **Objective**: `year`パラメータに応じた月次集計ロジックをテストで固めてから既存メソッドに組み込む（design.md 2.1, 2.2節）。
  - **実装ガイダンス**:
    - `DashboardServiceImplTest` に以下のテストケースを追加: `year`指定時に4月～翌年3月の12ヶ月分のラベル・データが返ること（AC 2.6）、`year`未指定時に既存の直近6ヶ月トレーリング挙動が維持されること（AC 2.2、既存の回帰確認）、指定年度内で対象契約が存在しない月は0を返すこと（AC 2.7）、契約が存在する月は計算結果（0を含む）をそのまま返すこと（AC 2.8）、月ごとの契約有効判定が開始日・終了日の境界条件を正しく扱うこと（AC 2.5）。
    - テストが失敗することを確認した後、`DashboardService.getSummary()` を `getSummary(Integer year)` にシグネチャ変更し、`buildFiscalYearMonths(int)` / `buildTrailingMonths(int)` のプライベートメソッドを実装してテストをグリーンにする（design.md 2.2節のコード例に従う）。
    - `DashboardService`インターフェースのシグネチャ変更に伴い、既存の呼び出し元（`DashboardApiController`）は一旦 `getSummary(null)` を渡す形で暫定的にコンパイルを通す（Task 6で正式にパラメータを繋ぐ）。
  - **テスト要件**: 上記5パターンを含むユニットテスト。既存の6ヶ月トレーリング挙動のテストは既存動作の回帰確認として重要。
  - **Demo**: `mvn test` で年度別集計ロジックのテストが全てパスする。既存の `/api/dashboard/summary`（年度指定なし）の挙動が変わっていないこともテストで保証される。

- [x] 6. `/api/dashboard/summary` への `year` パラメータ結線
  - **Objective**: Task 5のロジックをHTTP経由で年度指定できるようにする。
  - **実装ガイダンス**:
    - `DashboardApiController.getSummary()` に `@RequestParam(required = false) Integer year` を追加し、`dashboardService.getSummary(year)` を呼ぶ（design.md 2.1節）。
    - `DashboardApiControllerTest` に `/api/dashboard/summary?year=2026` と `year`省略時の両パターンのテストを追加。
  - **テスト要件**: コントローラーテスト2件（year指定あり/なし）。
  - **Demo**: `curl http://localhost:8080/api/dashboard/summary?year=2025` と `year=2026` で異なる月次データ（`charts.revenue.labels`が4月～翌3月）が返ることを確認できる。`year`を省略した場合は従来通り直近6ヶ月が返る。

- [x] 7. ダッシュボード画面の年度セレクトボックス連携
  - **Objective**: 画面上のセレクトボックス操作でグラフがリロードなしに切り替わるようにする（AC 3.1〜3.4）。
  - **実装ガイダンス**:
    - `templates/dashboard/index.html` の年度 `<select>` に `id="fiscal-year-selector"` を付与。
    - `static/js/modules/dashboard.js` を design.md 2.3節に従って改修:
      - `getCurrentFiscalYear()` で現在の会計年度を算出。
      - ページロード時に `populateFiscalYearSelector()` でオプションを生成し、現在年度を選択状態にして `loadDashboardData(currentFiscalYear)` を呼ぶ（AC 3.3）。
      - `#fiscal-year-selector` の `change` イベントで `loadDashboardData($(this).val())` を呼ぶ（AC 3.1）。
      - `renderCharts()` 内で既存の `revenueChart` の `Chart` インスタンスを変数に保持し、再描画前に `destroy()` してから再生成する（AC 3.2、フルリロードなし）。
      - AJAX失敗時は既存の描画内容を変更せず `Toast.error(...)` のみ表示する（AC 3.4）。
  - **テスト要件**: フロントエンドの自動テストは対象外（design.mdのTesting Strategy方針に従い手動確認）。
  - **Demo**: ダッシュボード画面で年度セレクトボックスを切り替えると、ページがリロードされずに柱状グラフの月次データが切り替わる。開発者ツールのNetworkタブで `year`付きのAJAXリクエストが発生していることを確認できる。ネットワークをオフラインにしてセレクトボックスを変更すると、エラートーストが出るが直前のグラフ表示は消えない。

---

## レポート出力（印刷）機能（Requirement 4）

- [x] 8. 印刷用CSSとボタンの結線
  - **Objective**: 「レポート出力」ボタンを押すとブラウザの印刷プレビューが開き、資料として読める形で出力される（AC 4.1〜4.3）。
  - **実装ガイダンス**:
    - `templates/dashboard/index.html` のレポート出力ボタンに `id="btn-print-report"` と `no-print` クラスを付与。
    - `templates/layout/sidebar.html` の `<aside>` と `templates/layout/header.html` の `<header>` に `no-print` クラスを付与（design.md 2.3節・4.2節）。
    - `static/js/modules/dashboard.js` に `$('#btn-print-report').on('click', function() { window.print(); });` を追加。
    - `static/css/common.css` に design.md 4.2節の `@media print` ブロックを追加（白背景+黒文字化、`.sidebar`/`.top-header`/`.footer`/`.no-print`の非表示、カード・テーブルの配色上書き、`page-break-inside: avoid`）。
  - **テスト要件**: 自動テスト対象外。手動確認。
  - **Demo**: ダッシュボード画面で「レポート出力」ボタンを押すとブラウザの印刷プレビューが開く。プレビュー上でサイドバー・ヘッダー・ボタン自体が非表示になり、背景が白・文字が黒になった状態でKPIカード・グラフ・退場予定リストが読める形で表示される。

---

## 通知機能（Requirement 5, 6）

- [x] 9. `NotificationDto` の作成と通知抽出ロジックのテスト駆動実装
  - **Objective**: 退場予定エンジニア通知・AIマッチング完了通知の抽出・ソート・件数制限ロジックをテストで固めてから実装する（design.md 5.2, 5.3節）。
  - **実装ガイダンス**:
    - `dto/notification/NotificationDto.java` を新規作成（type, icon, message, date, sortDate）。
    - `service/NotificationService.java`（interface）を新規作成。
    - `src/test/java/com/ses/service/impl/NotificationServiceImplTest.java` を新規作成。`ContractMapper`/`EngineerMapper`/`AiLogMapper` を `@Mock` 化する。
    - 先にテストを書く: 終了日が現在日から30日以内かつ稼動中の契約に対して `RETIRING_ENGINEER` 通知が生成されること（AC 5.2）、30日を超える契約や稼動中でない契約が対象外になること、`request_type='マッチング'`かつ直近24時間以内の `AiLog` に対して `AI_MATCHING` 通知が生成されること（AC 5.3）、24時間を超えるログが対象外になること、両条件とも0件の場合は空リストが返ること（AC 5.4）、通知が10件を超える場合は `sortDate` 降順で最新10件のみ返ること（AC 5.5, 5.6）。
    - テストが失敗することを確認した後、`NotificationServiceImpl` を design.md 5.2節のロジックで実装しテストをグリーンにする。
  - **テスト要件**: 上記6パターンを含むユニットテスト。
  - **Demo**: `mvn test` で `NotificationServiceImplTest` が全てパスする。通知の抽出・ソート・件数制限ロジックの正しさが保証された状態。

- [x] 10. 通知API `/api/notifications` の追加
  - **Objective**: Task 9のServiceをHTTP経由で呼び出せるようにする。
  - **実装ガイダンス**:
    - `controller/api/NotificationApiController.java` を新規作成（design.md 5.1節、`GET /api/notifications`）。
    - `src/test/java/com/ses/controller/api/NotificationApiControllerTest.java` を新規作成し、`@WebMvcTest` + `MockMvc` で `NotificationService` をモック化してテストする。
  - **テスト要件**: コントローラーテスト1件以上。
  - **Demo**: アプリ起動後、`curl http://localhost:8080/api/notifications` でJSON形式の通知一覧が返ることを確認できる（既存データに応じて空配列または実データ）。

- [x] 11. ヘッダー通知ドロップダウンの動的表示への置き換え
  - **Objective**: ハードコードされた通知2件を撤廃し、実データに基づく動的表示に置き換える（AC 6.1〜6.5）。
  - **実装ガイダンス**:
    - `templates/layout/header.html` のハードコードされた `<li>` 2件を、design.md 5.4節の `<li id="notification-list">`（初期状態は「読み込み中...」）に置き換える。
    - `static/js/common.js` に `SES.notification` モジュール（`load()`, `render()`, `renderError()`）を design.md 5.4節に従って追加し、既存の `DOMContentLoaded` 初期化ブロック内で `SES.notification.load()` を呼ぶ。
    - `render()` は通知0件時に「新しい通知はありません」をコンテナ内に表示（ドロップダウン自体は表示維持、AC 6.3）、`type`に応じたアイコン色分岐（AC 6.4）、`renderError()` は通信失敗時に「通知を読み込めませんでした」を表示（AC 6.5）。
  - **テスト要件**: 自動テスト対象外。手動確認。
  - **Demo**: 任意のページ（ダッシュボードに限らず）を開くとヘッダーの通知ベルのドロップダウンに実データ（退場予定エンジニアやAIマッチング完了ログがあればその内容、無ければ「新しい通知はありません」）が表示される。退場予定通知とAIマッチング通知でアイコンが異なることが確認できる。

---

## 最終確認

- [x] 12. 全機能の統合確認と既存機能の回帰確認
  - **Objective**: 4つの課題が全て解消され、既存機能に影響がないことを確認する。
  - **実装ガイダンス**:
    - `mvn test` を実行し、Task 1〜11で作成した全テストがグリーンであることを確認する。
    - 手動確認チェックリスト（design.md Testing Strategy節を参照）を一通り実施する:
      1. `/dashboard/profit` に500エラーが出ず、契約ごとの粗利一覧が表示される。
      2. ダッシュボードの年度セレクトボックスを切り替えるとグラフがリロードなしで切り替わる。
      3. 「レポート出力」ボタンで印刷プレビューが開き、白背景+黒文字でサイドバー/ヘッダー/ボタンが非表示になる。
      4. ヘッダー通知ドロップダウンが実データで表示され、退場予定・AIマッチングのアイコンが区別できる。
    - ダッシュボード画面のKPIカード・ステータス分布グラフ・退場予定リストなど、本specの対象外だった既存機能が壊れていないことを目視確認する。
  - **テスト要件**: 既存テスト全体のグリーン確認（回帰テスト）。
  - **Demo**: アプリケーションを起動し、ユーザーが最初に報告した4つの問題（500エラー、グラフ切り替え無反応、レポート出力無反応、通知が偽データ）が全て解消されていることを一連の操作で確認できる。
