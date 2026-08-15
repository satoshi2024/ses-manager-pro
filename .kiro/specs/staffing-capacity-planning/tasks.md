# Implementation Plan — 要員配置・需給計画

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T075〜T079はL1〜L3の定向test・直接回帰、T080でL4全量を実行する。
> 性能testは対象datasetと変更contractに限定し、各Taskで全量負荷testを反復しない。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §5「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
>
> **Migration**: 本specの正式migrationは **V103**（実装済み。V1統合baseline・H2・MySQL smokeに同期済み）。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

- [x] F1. position/allocation/scenario DDL
  - **Objective**: 案件に役割・必須skill・単価帯・稼働率を持つ複数positionを登録でき、
    要員の期間別allocationを計画できる。同一期間の配賦率合計が100%を超える配置は拒否され、
    例外は理由と承認が必須になる。scenarioは実データを変更しない。
  - **実装ガイダンス**: **V103**/V1/H2(`sql/schema-staffing-h2.sql`)/MySQL smoke、状態/区間/競合service。
    区間は`start_date`/`end_date`とも**inclusive**、open endは計画window末（design §5.2）。
    `position_id IS NULL`は**社内/待機**を表す業務値（design §5.1）。未割当と混同しない。
    **過配賦の判定は日単位**。月平均で判定しない。
  - **テスト要件**: L1〜L3。**50+50許可 / 60+50拒否 / 60+50重複なしは許可 / 60+50が1日だけ重複で拒否**、
    隣接（前end_dateの翌日が次start_date）は重複なし、同日（前end_date＝次start_date）は重複あり、
    例外承認時の許可、scenario isolation。
  - **Demo**: 兼務50%+50%を登録し、60%+50%が拒否されることを確認。
    1日だけ重複する60%+50%も拒否されることを確認。

- [x] F2. proposal/contract/availability統合
  - **Objective**: 提案と契約がpositionへ紐付き、契約成立でpositionの充足人数が自動更新される。
    退職予定・休暇・契約終了・更新decisionがcapacityへ反映され、同一契約がplanとactualで二重計上されない。
  - **実装ガイダンス**: position link、actual allocation、renewal/leave/retirement。
    **`source_contract_id IS NOT NULL`がactual**。需給集計SQLで**WHERE句として**排他する（design §5.4）。
    memory filterにしない。
    **稼働率は`UtilizationCalcService`を使う**（design §5.1）。独自に再定義しない。
  - **テスト要件**: L2〜L3。**plan/actualの二重計上0**、更新済契約の反映、退職の反映、
    休暇が稼働可能日数を減らすが契約FTEを変えないこと、scope、
    稼働率がdashboard KPIと一致すること。
  - **Demo**: 提案→契約でposition充足。同じ契約がplanとactualで二重に数えられないことを提示。

- [x] A1. position board/allocation timeline
  - **Objective**: 案件詳細のposition boardと要員timelineで配置を確認・変更でき、
    過配賦になる操作はその場で拒否される。D&Dが失敗したらカードが元に戻る。
  - **実装ガイダンス**: project/engineer画面、**drag操作は失敗時にUI rollback**（design §3）。
    配置確定transaction内で対象要員の期間行をロックする（design §5.4）。
    読んでから書くまでの競合を防ぐ。
  - **テスト要件**: L1〜L3。API/CSRF、**同一要員への同時配置で片方が失敗すること**、
    D&D失敗時のUI rollback、mobile 390px。
  - **Demo**: 兼務配置と過配賦拒否。D&D中にAPIを失敗させカードが元位置へ戻ることを確認。

- [x] B1. 需給heatmap/KPI
  - **Objective**: 月別のskill/role/location別の需要・供給・不足・余剰・bench costが見え、
    不足からdrilldownできる。全社合計と内訳の合計が一致する。
  - **実装ガイダンス**: skill/role/location/月aggregate、bench cost。
    planning horizonは最大24か月、超過要求は拒否（design §4/§5.4）。
    **全engineer×全dayの直積をJava memoryへ作らない**。server aggregateで返す。
    bench cost・単価帯はHRからmask（design §5.3）。
  - **テスト要件**: L2〜L3。FTE口径、**全社=内訳合計**、24か月上限と超過時の拒否、
    HRからbench costがmaskされること、大量データでheapが増えないこと。
  - **Demo**: Java需要不足をdrilldown。全社合計と内訳合計が一致することを提示。

- [x] B2. scenario compare
  - **Objective**: 仮配置のscenarioを作って2案の稼働率・粗利を比較でき、
    scenario操作が実契約・実提案・実配置計画を一切変更しない。
  - **実装ガイダンス**: clone/仮配置/比較/共有、**本データ非更新**。
    共有scenarioでも閲覧者のscopeを超えて要員を見せない（design §5.3）。
    scenario経由のscope迂回を防ぐ。
  - **テスト要件**: L2〜L3。**scenario操作後に`t_allocation_plan`・契約・提案が不変であること**、
    owner/共有の区別、共有scenario内の要員一覧が閲覧者scopeでfilterされること。
  - **Demo**: 2scenarioの稼働率/粗利差。scenario操作前後で実データのハッシュが変わらないことを提示。

- [x] M. 回帰/性能
  - **Objective**: position作成から需給更新までが一気通貫で動き、代表データ量でp95とheapが実測される。
    既存のproject/proposal/contract/analytics機能が壊れていない。
  - **テスト要件**: L4。`mvn test`全量、MySQL、代表データ量でp95/heap実測、
    既存analytics availability calendar/contract ganttの回帰、Node/JS syntax、
    desktop/390px browser Demo、`git diff --check`。
  - **Demo**: position作成→配置→提案→契約→需給更新。p95とheapの実測値を提示。
  - **実装ガイダンス**: `design.md`§5決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
