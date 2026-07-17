# Implementation Plan — 金銭フロー整合性・契約ライフサイクル補完（money-flow-consistency）

レーン構成: **(A ∥ C) → B → D → M**。D は A・B 両方の完了が前提
（`SalesPerformanceServiceImpl` は B と、契約検索まわりは A とファイルが交差するため）。
i18n 4ファイルは A/C/D すべてが追記するため、並行時はキー追加のみとしマージ順を決めること。
詳細は design.md の「実装レーン分割」を参照。

- [x] 0. spec ドキュメント
  - **Objective**: 本ディレクトリの requirements / design / tasks 3ファイル（2026-07-16 調査結果の整理）。
  - **Demo**: レビュー可能な状態でコミットされている。

- [x] A1. 解約日つき状態遷移 API（R2）
  - **Objective**: `changeStatus` に解約日を追加し、解約時に `end_date` を確定させる。
  - **実装ガイダンス**: design.md R2。`StatusChangeRequest.cancelDate` 追加、
    `ContractService(Impl)#changeStatus` 拡張、`ContractApiController` 引き渡し、
    エラーメッセージキー2本×4言語。
  - **テスト要件**: `ContractServiceImplTest` に 解約日必須 / 開始日前拒否 / `end_date` 上書き /
    終了遷移では不変 の4ケース追加。
  - **Demo**: curl で `PUT /api/contracts/{id}/status` に `{"status":"解約","cancelDate":"..."}` を送り、
    `end_date` が更新されること、日付なしが 4xx になることを確認。

- [x] A2. 契約編集・状態変更 UI（R1 + R6契約フォーム注記）
  - **Objective**: 契約一覧から編集と状態遷移ができ、成約由来ドラフトを稼動化できるようにする。
  - **実装ガイダンス**: design.md R1。`GET /api/contracts/{id}` は実装済みなので流用。
    モーダル共用化（`#cont-id` hidden）に加え、**現状モーダルに無い入力欄
    （contractType / settlementHoursMin/Max / fractionRule / autoRenew）を新設**
    — これが無いと「PUT は全項目送信」が成立しない。fractionRule 欄の直下に
    R6 の注記（`contract.fractionRule.note`）を表示。状態変更ドロップダウン
    （解約のみ日付入力ダイアログ）、i18n キー×4言語。
  - **テスト要件**: 既存 Contract 系テストがグリーンのまま。detail API の単体テスト追加。
  - **Demo**: ブラウザで (1) 提案カンバンで成約→生成されたドラフトを編集し原価を設定→稼動中へ遷移
    →勤怠グリッドに出現→工数入力→月次確定→請求書生成まで一気通貫で確認。
    (2) 稼動中契約を解約（日付入力）し、一覧の終了日が解約日になることを確認。
    (3) 編集でインセンティブ上書きを空に戻し、営業成績が既定規則に戻ることを確認。

- [x] B1. 共通集計サービス（R3）
  - **Objective**: `MonthlyRevenueCalcService` を新設し、契約単位フォールバック＋準備中除外の
    共通口径を実装する。
  - **実装ガイダンス**: design.md R3。`resolveContractAmount` も併せて公開。
  - **テスト要件**: `MonthlyRevenueCalcServiceTest`（design.md 記載の5ケース以上）。
  - **Demo**: `mvn test -Dtest=MonthlyRevenueCalcServiceTest` グリーン。

- [x] B2. Dashboard / Excel帳票 / 営業成績の共通口径への置換（R3）
  - **Objective**: 3モジュールの独自集計を共通サービス委譲に置き換え、KPI・チャート・帳票・
    営業成績合計の数値を一致させる。
  - **実装ガイダンス**: design.md R3「呼び出し元の置換」。`calcMonthlyAmount` と
    `buildMonthlyRevenueRows` は削除。KPI 当月予想売上もチャート当月値と同一ソース化。
  - **テスト要件**: `DashboardServiceImplTest` の期待値更新。営業成績側は
    `resolveContractAmount` 経由でも既存テストが通ること。
  - **Demo**: 確定実績が一部契約にのみある月を作り、(1) Dashboard チャート当月値、
    (2) KPI 当月予想売上、(3) 月次売上Excel、(4) 営業成績の全営業＋未帰属合計（D完了後）が
    同一金額であることを確認。準備中ドラフトが見込みに含まれないことを確認。

- [x] C1. reopen の手動BP階層保護と confirm 時の金額同期（R4）
  - **Objective**: 月次解除で手動登録の多段BP階層を黙って消さない。再確定時の1階層目金額ずれを解消。
  - **実装ガイダンス**: design.md R4。拒否条件は「未払かつ (layer_order>1 or parent_payment_id
    not null)」の存在。confirm 側は未払1階層目のみ金額更新、支払済は通知。
  - **テスト要件**: `WorkRecordServiceImplTest` に 拒否 / 1階層のみ削除成功 / confirm 金額更新 の
    3ケース追加。
  - **Demo**: 手動で2階層目を登録した月を reopen してエラーメッセージを確認。
    1階層のみの月は reopen→工数修正→再確定で BP金額が追従することを確認。

- [x] C2. 細部整備（R6勤怠側注記 + R7）
  - **Objective**: 月末判定の方言依存解消、未請求クエリの deleted_flag、DBコメント単位修正、
    金額表示の記号重複、勤怠グリッドの fraction_rule 注記、設定単位注記。
  - **実装ガイダンス**: design.md R6/R7。月末判定は Java 側で `#{monthEnd}` を渡す方式を第一候補。
    マイグレーションは `VNN` = 実装時点の最新+1（2026-07-16 時点で V22 まで存在）。**V1 は変更しない**。
    契約フォーム側の注記は A2 の担当（本タスクでは触らない）。
  - **テスト要件**: `FlywayMigrationSmokeTest`（Docker あり環境）で VNN が空DBから通ること。
    H2 の `@SpringBootTest` 群がグリーンのまま。
  - **Demo**: 2月の勤怠グリッドが正しく契約を列挙する。契約一覧の金額表示が `¥950,000` 形式。
    勤怠グリッドに端数ルール注記が出る。

- [x] C3. 請求書への適用税率の保存（R8）
  - **Objective**: 税率改定後も過去請求書の表示税率と保存済み税額が矛盾しないようにする。
  - **実装ガイダンス**: design.md R8。`t_invoice.tax_rate` 追加（C2 と同じ VNN マイグレーションに同居可）、
    `Invoice.taxRate`、`generate()` でセット、`detail()` は保存値優先・NULL は設定へフォールバック。
    `engineer-schema-h2.sql` と H2 リプレイ用 ALTER スクリプトの同期を忘れないこと。
  - **テスト要件**: 生成時保存 / NULL 行フォールバック / 設定変更が既存請求書表示に影響しない の3ケース。
  - **Demo**: 請求書を1枚生成→システム設定で税率を変更→当該請求書の印刷画面・PDF の税率表示が
    生成時のままであること、新規生成分は新税率になることを確認。

- [x] D1. 営業成績の未帰属行（R5）※ **A2・B2 両方の完了後**
  - **Objective**: `sales_user_id` NULL の契約売上を「未帰属」行で可視化し、全社売上と突合可能にする。
  - **実装ガイダンス**: design.md R5。契約検索に salesUserId「未設定」オプションを追加
    （`ContractApiController` / `ContractMapper.selectPageWithNames` / `contract.js` /
    `contract/list.html` — レーンAの担当ファイルに触れるため A 完了後）し、未帰属行からリンク。
  - **テスト要件**: `SalesPerformanceServiceImplTest` に未帰属集計ケース追加
    （全行合計 = 共通口径の全社売上）。
  - **Demo**: 担当営業なしの稼動契約がある月で、営業成績に未帰属行が出て、
    リンク先の契約一覧で当該契約に担当を設定すると未帰属行から消えることを確認。

- [x] M. 統合回帰
  - **Objective**: 全レーン統合後の回帰確認と README ディスパッチ表の更新。
  - **テスト要件**: `mvn test` 全件グリーン（Docker あり環境では smoke test 含む）。
  - **Demo**: A2 の一気通貫 Demo を再実行し、Dashboard・Excel・営業成績の金額一致（B2 Demo）を
    最終確認。`.kiro/specs/README.md` の状態列を更新。
