# Implementation Plan — 月次実績工数・請求・支払(P5)

前提: P2 完了推奨(契約ステータスが正しく運用されていること)。

- [ ] 1. DDL とエンティティ・Mapper の作成
  - **Objective**: 4テーブル(work_record / invoice / invoice_item / bp_payment)+ メニューシード。
  - **実装ガイダンス**: `sql/005_create_work_record_billing.sql`(design.md 1章)+ H2 テストスキーマ + エンティティ4つ + Mapper(`selectMonthlyGrid` の JOIN 注釈 SQL 含む)。
  - **テスト要件**: H2 で selectMonthlyGrid が当月稼動中契約のみ返すこと(期間境界含む)。
  - **Demo**: `mvn test` グリーン。

- [ ] 2. `SettlementCalculator`(テスト駆動)
  - **Objective**: 精算計算を純粋関数で固める。
  - **実装ガイダンス**: design.md 2章。先にテスト(範囲内/超過/控除/固定/境界/切り捨て)。
  - **Demo**: `mvn test -Dtest=SettlementCalculatorTest` パス。

- [ ] 3. 実績入力サービス + API
  - **Objective**: 月次グリッド取得・実績 upsert(精算込み)・月次確定/解除。
  - **実装ガイダンス**: design.md 3章・4章前半。確定時に BP 契約(要員 employment_type=BP)分の `t_bp_payment` を生成。確定済み編集は `BusinessException`。reopen は `SecurityConfig` で `/api/work-records/reopen` を `hasRole("管理者")`。
  - **テスト要件**: upsert・ロック・BP支払生成・確定の冪等性。
  - **Demo**: `curl` で実績保存 → billing_amount が精算計算済みで返る。確定後の PUT はエラー。

- [ ] 4. 勤怠・実績画面
  - **Objective**: `/work-record` の月次グリッド画面。
  - **実装ガイダンス**: design.md 5章。`WorkRecordPageController` + `work-record/list.html` + `modules/work-record.js`。サイドバー追加(menu_key=work-record)。
  - **Demo**: 月を選び実績を入力→請求/支払額が即表示→「月次確定」で readonly 化。HR ロールでもアクセス可、営業から menu を外すと 403。

- [ ] 5. 請求書生成サービス(テスト駆動)
  - **Objective**: 顧客×月からの請求書生成・採番・二重請求防止・状態遷移。
  - **実装ガイダンス**: design.md 3章 `InvoiceService`。`work_record_id UNIQUE` 制約が最後の砦、サービス層でも事前チェック。
  - **テスト要件**: 集計・税計算・採番連番・二重請求拒否・0件エラー。
  - **Demo**: `mvn test -Dtest=InvoiceServiceImplTest` パス。

- [ ] 6. 請求・支払画面 + 印刷ビュー
  - **Objective**: `/invoice`(請求書タブ + BP支払タブ)と `/invoice/{id}/print`。
  - **実装ガイダンス**: design.md 5章。print.html は base 非継承の自己完結 A4。状態変更は一覧行のドロップダウン+確認。
  - **Demo**: 確定済み実績から請求書を生成 → 印刷ビューで明細・税・合計が正しい → 「入金済」にすると入金日が記録される。BP支払を支払済にできる。

- [ ] 7. ダッシュボードの実績ベース化
  - **Objective**: 確定実績のある月は実績値、無い月は見込み値でグラフ表示。
  - **実装ガイダンス**: `DashboardServiceImpl` の月次ループで当月確定実績の有無を判定して切替。`dashboard.js` のツールチップに「実績/見込み」。既存 `DashboardServiceImplTest` を壊さず拡張。
  - **テスト要件**: 実績あり月/なし月の混在ケース。
  - **Demo**: 実績確定済みの月だけグラフ値が精算後金額に変わる。
