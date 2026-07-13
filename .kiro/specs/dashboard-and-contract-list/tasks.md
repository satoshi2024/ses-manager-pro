# Implementation Plan — ダッシュボード実データ化・契約一覧改善(dashboard-and-contract-list)

1〜3(ダッシュボード)と 4〜5(契約一覧)は独立しており、どちらからでも着手できる。
`ContractServiceImpl` は WS-B の担当のため**編集禁止**。

- [x] 1. 月次集計の一括取得化とヘルパー抽出
  - **Objective**: R3-1。月次ループの反復クエリを1回化し、R1 が使う集計ヘルパーを用意する。
  - **実装ガイダンス**: design.md 2章。`work_month IN (...)` 一括取得 → `groupingBy` → `calcMonthlyAmount` ヘルパー抽出。出力仕様は不変。
  - **テスト要件**: 既存 `DashboardServiceImplTest` が無修正でグリーン(仕様不変の証明)。
  - **Demo**: ダッシュボードの売上チャートが修正前と同じ値を表示する。

- [x] 2. KPI トレンドの実データ化
  - **Objective**: R1。`+0.0%` のモックを前月比の実値または null(非表示)にする。
  - **実装ガイダンス**: design.md 1章。revenue/profit は `calcMonthlyAmount` で当月・前月を計算、utilization は null。dashboard.js は null でバッジ非表示、符号で色切替。
  - **テスト要件**: (a) 前月100万/当月120万 → `+20.0%`、(b) 減少 → `-x.x%`、(c) 前月0 → null、(d) utilizationTrend が null。
  - **Demo**: 2ヶ月分の確定実績を投入 → ダッシュボードKPIに実際の増減率が表示され、稼動率のトレンドバッジは出ない。

- [x] 3. 退場予定リストの実データ化(スキル・提案数)
  - **Objective**: R2, R3-2。`N/A`/`0` のモックを実データにし、N+1 を解消する。
  - **実装ガイダンス**: design.md 2章。`selectBatchIds` 一括取得、`EngineerSkillMapper` に代表スキル @Select、提案数は IN + groupingBy。`DashboardServiceImpl` へ Mapper 注入追加。
  - **テスト要件**: (a) スキル3件(level 2,4,4)の要員 → level4 で id 最小のタグ名、(b) スキルなし → `-`、(c) 進行中提案2件+見送り1件 → proposals=2。
  - **Demo**: 30日以内終了の契約を持つ要員に、実際の主要スキルと提案数が表示される。

- [x] 4. getProfitAnalysis のソート修正
  - **Objective**: R4。O(n²) の線形探索ソートを解消する。
  - **実装ガイダンス**: design.md 3章。DTO に startDate を保持し `Comparator.nullsLast` でソート。
  - **テスト要件**: startDate null 混在データで並び順が現行仕様(降順・null末尾)と一致。
  - **Demo**: 利益分析画面の並び順が修正前と同一。

- [x] 5. 契約一覧の検索API + 名称解決 + 検索UI
  - **Objective**: R5, R6。契約一覧を「ID の生表示・検索不能」から実用レベルにする。
  - **実装ガイダンス**: design.md 4章。`ContractListDto`(新規)+ `ContractMapper.selectPageWithNames`(`<script>` 動的SQL、LEFT JOIN、カラム名は V1 マイグレーションで要確認)→ `ContractApiController.page` 差し替え → contract/list.html に検索フォーム(engineer/list.html のUIパターン踏襲)→ contract.js の `renderContracts` を名称表示に(全表示値 `SES.escapeHtml`)。
  - **テスト要件**: `ContractApiControllerTest` — (a) パラメータなしで全件(後方互換)、(b) status+customerId 複合で絞れる、(c) contractNo 部分一致、(d) engineerName が解決される、(e) 削除済み要員の契約は engineerName=null で返る。
  - **Demo**: 契約一覧に要員名・顧客名・案件名が表示され、「稼動中」+顧客で絞り込みできる。削除済み要員の契約は「(削除済み)」表示。`mvn test` 全件グリーン。
