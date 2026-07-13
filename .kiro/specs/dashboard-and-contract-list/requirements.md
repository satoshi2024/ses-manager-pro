# Requirements — ダッシュボード実データ化・契約一覧改善(dashboard-and-contract-list)

ダッシュボードに残るモック値の実データ化と性能改善、および契約一覧の検索・名称表示の実装。
対象コード: `DashboardServiceImpl` / `ContractApiController` / `ContractMapper` /
`dto/dashboard/*` / `dto/contract/*`(新規) / `dashboard.js` / `contract.js` / `contract/list.html`。

## R1. KPI トレンドの実データ化

現状: `DashboardServiceImpl.getSummary` の `utilizationTrend` / `revenueTrend` / `profitTrend` が
`"+0.0%"` / `"-0.0%"` のハードコード。

受入基準:
1. `revenueTrend` / `profitTrend` は「当月 vs 前月」の増減率(%)を返す。
   月次金額は既存のチャート計算と同じロジック(確定実績があれば実績、なければ契約ベース見込み)を用いる。
2. `utilizationTrend` は前月比較のデータソース(ステータス履歴)が存在しないため、
   モック文字列をやめて **null** を返し、フロント(dashboard.js)は null のとき
   トレンドバッジを非表示にする(嘘の数字を出さない)。
3. フォーマットは符号付き小数1桁 + `%`(例 `+12.3%` / `-4.0%`)。前月が 0 の場合は `null`(表示なし)。
4. 増減率の計算ロジックは単体テストで検証される。

## R2. 退場予定リストの実データ化

現状: `skill("N/A")` / `proposals(0)` がハードコード。

受入基準:
1. `skill` には該当要員の代表スキル(`t_engineer_skill` を `skill_level` 降順・同点なら `id` 昇順で
   1件、`m_skill_tag` の `tag_name`)が入る。スキル未登録の要員は `"-"`。
2. `proposals` には該当要員の進行中提案数(`t_proposal` の `status NOT IN ('成約','見送り')`)が入る。
3. どちらも要員ごとの N+1 クエリではなく、対象要員 ID 一括の IN 句クエリで取得する。

## R3. getSummary の性能改善

現状: 月次ループ内で毎月 `workRecordMapper.selectList`(最大12回)、
退場予定リストで契約ごとに `engineerMapper.selectById` / `projectMapper.selectById`(N+1)。

受入基準:
1. 確定実績は対象期間の全月分を **1回のクエリ**(`work_month IN (...)` または BETWEEN)で取得し、
   メモリ上で月別に集計する。
2. 退場予定リストの要員・案件は ID 一括の `selectByIds`(`selectBatchIds`)で取得する。
3. 既存テスト(`DashboardServiceImplTest`)がグリーンのまま(出力仕様は不変)。

## R4. getProfitAnalysis の O(n²) ソート解消

現状: ソート比較のたびに契約リストを線形探索している(契約1000件で百万回オーダー)。

受入基準:
1. DTO 構築時に `startDate` を保持(または `Map<contractNo, LocalDate>` を事前構築)し、
   ソートは O(n log n) になる。
2. 出力(並び順・内容)は現状と同一。

## R5. 契約一覧APIの検索条件

現状: `GET /api/contracts` は分页のみで検索条件が一切なく、要員一覧(氏名/ステータス/雇用形態/
スキル絞り込み)と非対称。

受入基準:
1. `GET /api/contracts` が以下の任意パラメータを受け付ける(すべて AND 結合):
   - `status`(完全一致)
   - `customerId` / `engineerId` / `projectId`(完全一致)
   - `contractNo`(部分一致)
   - `endDateFrom` / `endDateTo`(契約終了日の範囲。終了日 NULL の契約は範囲指定時は除外)
2. パラメータなしの呼び出しは従来と同じ結果を返す(後方互換)。
3. 並び順は `id` 降順に統一する。

## R6. 契約一覧の名称表示

現状: contract.js が要員・顧客・案件を `${c.engineerId} (ID)` のように **ID を生表示**しており、
一覧が実用に耐えない。

受入基準:
1. 一覧APIは名称解決済みの DTO(`ContractListDto`: 契約全項目 + `engineerName` /
   `customerName` / `projectName`)のページを返す。JOIN はサーバー側で行う
   (論理削除済みマスタは LEFT JOIN で名称 NULL → フロントで「(削除済み)」表示)。
2. contract.js の一覧描画は氏名・顧客名・案件名を表示する。**すべて `SES.escapeHtml` を通す**。
3. contract/list.html に検索フォーム(ステータス select、顧客 select、契約No テキスト、
   終了日 from/to)を追加し、R5 のパラメータで再検索できる。
4. 既存の登録/編集モーダル・削除フローは無変更で動作する。
