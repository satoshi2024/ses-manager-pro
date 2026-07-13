# Design — ダッシュボード実データ化・契約一覧改善(dashboard-and-contract-list)

マイグレーション不要。編集対象(これ以外に触れないこと):

- `src/main/java/com/ses/service/impl/DashboardServiceImpl.java`
- `src/main/java/com/ses/dto/dashboard/DashboardSummaryDto.java`(trend 型変更が必要な場合)
- `src/main/java/com/ses/dto/dashboard/ContractProfitDto.java`(startDate 保持用フィールド追加可)
- `src/main/java/com/ses/controller/api/ContractApiController.java`
- `src/main/java/com/ses/mapper/ContractMapper.java`(一覧用 @Select の**メソッド追加のみ**。
  ※ WS-B も採番用メソッドを追加する — マージ時は両方残す)
- `src/main/java/com/ses/mapper/EngineerSkillMapper.java`(代表スキル一括取得の @Select 追加)
- `src/main/java/com/ses/dto/contract/ContractListDto.java`(新規)
- `src/main/resources/static/js/modules/dashboard.js` / `contract.js`
- `src/main/resources/templates/contract/list.html`
- テスト: `DashboardServiceImplTest` / `ContractApiControllerTest`

**注意**: `ContractServiceImpl` は WS-B の編集対象のため**触らない**。
一覧クエリは Mapper 直呼び(コントローラー→Mapper)ではなく、既存流儀に合わせて
`ContractService` に新メソッドを足したくなるが、本WSでは衝突回避のため
`ContractApiController` に `ContractMapper` を直接注入して呼んでよい
(リポジトリ内に AutocompleteApiController 等の前例あり)。

## 1. KPI トレンド(R1)

`getSummary` のチャート計算部を先に実行し、`targetMonths` に「当月」「前月」が含まれる場合は
その値を再利用。含まれない場合(年度指定で過去年度を見ている場合)は当月・前月の2ヶ月分を
同じ月次集計ヘルパーで追加計算する。月次集計はプライベートメソッドに抽出する:

```java
/** 指定月の売上・粗利(確定実績優先、なければ契約ベース見込み)を返す */
private long[] calcMonthlyAmount(YearMonth ym, List<Contract> allContracts, Map<String, List<WorkRecord>> confirmedByMonth)
```

増減率: `rate = (current - previous) / |previous| * 100`。`previous == 0` は null。
フォーマット: `String.format("%+.1f%%", rate)`。
`utilizationTrend` はビルダーで null を渡す。`DashboardSummaryDto.KpiDto` の型は String のままでよい。

dashboard.js: トレンド表示要素へ値を入れる箇所で `null`/`undefined` のとき要素を
`d-none` にする(バッジごと非表示)。値がある場合は先頭文字 `+`/`-` で色クラスを切り替える
(既存の表示パターンを踏襲)。

## 2. 退場予定リスト(R2)と N+1 解消(R3)

- 確定実績の一括取得: ループ前に
  `workRecordMapper.selectList(new QueryWrapper<WorkRecord>().in("work_month", monthStrs).eq("status", "確定"))`
  → `Map<String, List<WorkRecord>> confirmedByMonth = groupingBy(getWorkMonth)`。
- 退場予定: `retiringContracts` から engineerIds / projectIds を集めて
  `engineerMapper.selectBatchIds` / `projectMapper.selectBatchIds` → Map 化して参照。
- 代表スキル: `EngineerSkillMapper` に @Select 追加:

```java
@Select("""
    <script>
    SELECT es.engineer_id AS engineerId, st.tag_name AS tagName, es.skill_level AS skillLevel
    FROM t_engineer_skill es
    JOIN m_skill_tag st ON es.skill_id = st.id
    WHERE es.engineer_id IN
    <foreach collection="engineerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
    ORDER BY es.engineer_id, es.skill_level DESC, es.id ASC
    </script>
    """)
List<EngineerSkillDetailDto> selectTopSkillCandidates(@Param("engineerIds") List<Long> engineerIds);
```

(既存 `EngineerSkillDetailDto` のフィールド名は実装時に確認し、合わなければ専用の軽量 DTO を
`dto/dashboard/` に新設する。)Java 側で engineer_id ごとの先頭1件を採用。
- 進行中提案数: `proposalMapper.selectList(engineer_id IN (...) AND status NOT IN ('成約','見送り'))`
  → `groupingBy(engineerId, counting())`。`DashboardServiceImpl` に `ProposalMapper` /
  `EngineerSkillMapper` を注入追加する。

## 3. getProfitAnalysis(R4)

ループで DTO を作る際に `record`(または並行リスト)へ `startDate` を保持し、

```java
result.sort(Comparator.comparing(ContractProfitDto::getStartDate,
        Comparator.nullsLast(Comparator.reverseOrder())));
```

`ContractProfitDto` に `startDate` フィールドを追加する(JSON に増えるが害はない。
気になる場合は `@JsonIgnore`)。

## 4. 契約一覧の検索 + 名称解決(R5, R6)

新規 `dto/contract/ContractListDto.java`: `Contract` の主要表示項目
(id, contractNo, engineerId, customerId, projectId, contractType, startDate, endDate,
sellingPrice, costPrice, status) + `engineerName` / `customerName` / `projectName`。

`ContractMapper` に @Select(動的条件は `<script>`):

```java
@Select("""
    <script>
    SELECT c.id, c.contract_no AS contractNo, c.engineer_id AS engineerId,
           c.customer_id AS customerId, c.project_id AS projectId,
           c.contract_type AS contractType, c.start_date AS startDate, c.end_date AS endDate,
           c.selling_price AS sellingPrice, c.cost_price AS costPrice, c.status,
           e.full_name AS engineerName, cu.company_name AS customerName, p.project_name AS projectName
    FROM t_contract c
    LEFT JOIN t_engineer e ON c.engineer_id = e.id AND e.deleted_flag = 0
    LEFT JOIN m_customer cu ON c.customer_id = cu.id AND cu.deleted_flag = 0
    LEFT JOIN t_project p ON c.project_id = p.id AND p.deleted_flag = 0
    WHERE c.deleted_flag = 0
      <if test="status != null and status != ''">AND c.status = #{status}</if>
      <if test="customerId != null">AND c.customer_id = #{customerId}</if>
      <if test="engineerId != null">AND c.engineer_id = #{engineerId}</if>
      <if test="projectId != null">AND c.project_id = #{projectId}</if>
      <if test="contractNo != null and contractNo != ''">AND c.contract_no LIKE CONCAT('%', #{contractNo}, '%')</if>
      <if test="endDateFrom != null">AND c.end_date &gt;= #{endDateFrom}</if>
      <if test="endDateTo != null">AND c.end_date &lt;= #{endDateTo}</if>
    ORDER BY c.id DESC
    </script>
    """)
Page<ContractListDto> selectPageWithNames(Page<ContractListDto> page, @Param("status") String status,
        @Param("customerId") Long customerId, @Param("engineerId") Long engineerId,
        @Param("projectId") Long projectId, @Param("contractNo") String contractNo,
        @Param("endDateFrom") LocalDate endDateFrom, @Param("endDateTo") LocalDate endDateTo);
```

(MyBatis-Plus の分页インターセプターは第1引数 `Page` の Mapper メソッドに自動適用される。
テーブル/カラム名は `V1__create_tables.sql` を実装時に必ず確認して合わせること。)

`ContractApiController.page` は上記に委譲し `ApiResult<Page<ContractListDto>>` を返す。
日付パラメータは `@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate`。

contract.js / contract/list.html:
- テーブル上部に検索フォーム(既存の要員一覧 `engineer/list.html` の検索UIパターンを踏襲):
  ステータス select(全部/準備中/稼動中/終了/解約 — 既存ステータス値は実装時に確認)、
  顧客 select(`loadSelectOptions` の顧客リストを再利用)、契約No テキスト、終了日 from/to。
- `loadContracts()` がフォーム値をクエリパラメータに載せる。
- `renderContracts` は `engineerName ?? '(削除済み)'` 等を **`SES.escapeHtml` を通して**表示。

## 5. テスト設計

- `DashboardServiceImplTest`: トレンド計算(前月比 +/-/前月ゼロ→null)、代表スキル選定
  (skill_level 同点時の id 昇順)、提案数集計のケースを追加。既存ケースは仕様不変でグリーン維持。
- `ContractApiControllerTest`: 検索パラメータの組合せ(status のみ/複合/該当なし)、
  名称解決(engineerName が入る)、削除済み要員の契約で engineerName=null。
