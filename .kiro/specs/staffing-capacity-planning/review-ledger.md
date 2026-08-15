# Review Ledger — 要員配置・需給計画（S12 staffing-capacity-planning）

## 現行判定

- **T075 F1: 完了**（commit `a691f77e`）
- **T076 F2: 完了**（commit `ec880114`）
- **T077 A1: 完了**（commit `6e0ddfc9`）
- **T078 B1: 完了**（commit `f0e7a222`）
- **T079 B2: 完了（REVIEW待ち）** 2026-08-16
- T080: 未着手

## READINESS（着手時）

- handbook v2.0 / test-execution-policy-s03-s17（通常TaskはL1〜L3定向・直接回帰）
- base commit: `85dfd7bf`（main merge済み。S10 R10最終Review「T066 technical PASS・S10 PASS・S12 READY」記録merge後）
- dependency: S11 PASS（R11 T074）、S10 PASS（R10最終Review・PR #77 merge済み。中央台帳row10の反映はS10側の更新待ちとして記録）
- migration: common latest=V102_3 / 本spec予約=V103（衝突なし）/ 永久欠番 V59・V72・V82・V99 / migration-dev V100はcommon再利用禁止
- environments: Java 17 + bundled Maven 3.9.6、H2（test）、Docker 29.6.2可用（MySQL smoke実実行済み）
- ownership: 主担当が全ファイル所有（子Agent不使用）
- working tree: `half-finished-production-readiness/execution-ledger.md` のみ他specのdirty（非干渉）

## TASK CONTRACT T075（F1. position/allocation/scenario DDL）

- requirements/AC: R1.1/R1.2（position状態機械）、R2.1/R2.2（配賦率100%・例外承認）、R3.3（scenario isolation）、R5（受入: 50+50許可/60+50拒否/1日重複拒否/隣接OK・同日NG）
- 決定表はdesign.md §5の確定済み3表をそのまま実装（読み替えなし）
- 計画window: 最大24か月（StaffingClock）。open end（end_date NULL）= window末。日単位で過配賦判定
- 例外承認: ApprovalEngineServiceへ `staffing.overallocation` を登録（AllocationApprovalAdapter）。
  保存時は承認requestを自動発行し、確定（confirm）時は承認approvedを要求。adapterのapplyApprovedは
  confirm()単一経路のためno-op（確定時のロック付き再検証を迂回させない）
- 競合: 確定/変更は対象要員の期間行をFOR UPDATEでロックしてから再検証（design §5.4）

### 実装の具体化（specの文言が曖昧だった箇所、判定・根拠）

1. `t_staffing_scenario_allocation.dates`（design §1の列名のみ明記）:
   **ISO日付のJSON配列（昇順・重複なし・[base_date, base_date+24か月]）** として実装。
   日単位のFTE換算（design §5.2）と整合するliteral reading。serviceがsort+dedupで正規化。
2. allocation_type×position_idのDB整合CHECK: **MySQLではCHECK+FK同一列併用不可（Error 3823相当）** のため
   V1/V103にはCHECKを置かず、V103のBEFORE INSERT trigger（trg_allocation_plan_type_guard）で担保。
   H2はCHECKで担保（V102_1の重担保方式と同じ。実MySQL smokeで検証済み）。
3. position削除ガード: 充足済みは削除不可（status=充足以外は論理削除可）。

## 変更file（T075）

| 種別 | file |
|---|---|
| migration | `db/migration/V103__staffing_capacity_planning.sql`（新規）、`V1__create_tables.sql`（t_project_position/t_allocation_plan/t_staffing_scenario/t_staffing_scenario_allocation追加＋t_proposal/t_contractへposition_id） |
| H2同期 | `sql/schema-staffing-h2.sql`（新規）、`sql/engineer-schema-h2.sql`（staffing section追加＋position_id）、`application-test.yml`（schema-locationsへ追加） |
| entity | ProjectPosition/AllocationPlan/StaffingScenario/StaffingScenarioAllocation（新規）、Proposal.positionId/Contract.positionId |
| mapper | ProjectPositionMapper/AllocationPlanMapper/StaffingScenarioMapper/StaffingScenarioAllocationMapper（新規） |
| service | `service/staffing/StaffingClock`、PositionService(+Impl)、AllocationPlanService(+Impl)、StaffingScenarioService(+Impl)、AllocationApprovalAdapter |
| message | 4バンドルへ `error.staffing.*` 25キー追加 |
| test | `FlywayStaffingSchemaSmokeTest`（Docker実MySQL fresh V1→V103＋trigger/CHECK/FK動作）、PositionServiceTest、AllocationPlanServiceTest、StaffingScenarioServiceTest |

## Test evidence（T075）

| 実行 | 結果 |
|---|---|
| staffing 3クラス（27件）＋AllMappersSchemaSweepTest（133件）＋MigrationScriptIntegrityTest（27件）＋MessageBundleConsistencyTest（4件） | 191/0/0/0 PASS |
| 直接回帰: ProposalServiceImplTest(8)・ContractServiceImplTest(48)・LeaveApprovalFlowIntegrationTest(2)・AttendanceSchemaTest(6) | 64/0/0/0 PASS |
| FlywayStaffingSchemaSmokeTest（Testcontainers MySQL 8.0・fresh full migrate） | 1/0/0/0 PASS |
| V1共有回帰: FlywayMigrationSmokeTest(2)・FlywayAttendanceSchemaSmokeTest(3)・FlywayDispatchComplianceSchemaSmokeTest(1)・FlywayG2GateSchemaSmokeTest(2) | 8/0/0/0 PASS |
| `git diff --check` | exit 0 |

受入条件の実測（AllocationPlanServiceTest）:
- 50%+50%完全重複 → 確定OK（=100%）
- 60%+50%完全重複 → `error.staffing.overAllocation` 拒否（=110%）
- 60%+50%重複なし（隣接: 前end_date翌日=次start_date）→ 確定OK
- 60%+50%が1日だけ重複（同日境界: 前end_date=次start_date）→ 拒否
- 例外承認（exception_reason＋承認approved）→ 確定OK / 承認前・却下後は拒否
- scenario操作後もt_allocation_plan不変（R3.3 isolation）

## Demo（T075）

F1はservice層のみ（UIはT077 A1の所有）のため、Demoは受入条件を直接assertする上記テスト実行を
証跡とする。UI Demo（兼務配置画面での拒否表示）はT077で実施する。

## 逸脱

- platform-invariantsからの逸脱はなし。design.md §5決定表は確定済み表をそのまま実装。

## Risk

- H2とMySQLの整合担保方式の違い（H2 CHECK / MySQL trigger）はV102_1と同一の既知パターン。
- 承認route（staffing.overallocation）は管理者が承認設定画面で作成するまで例外配置を保存できない
  （fail-closed。leave.requestと同一挙動）。

## Commit

- T075: `a691f77e` feat(staffing): T075 F1 position/allocation/scenario DDL（V103・過配賦日単位判定・例外承認・scenario isolation）
- T076: `ec880114` feat(staffing): T076 F2 proposal/contract/availability統合（actual同期・需給集計・更新/退職/休暇反映）
- T077: `6e0ddfc9` feat(staffing): T077 A1 position board/allocation timeline（D&D・UI rollback・同時配置CAS）
- T078: `f0e7a222` feat(staffing): T078 B1 需給heatmap/KPI（server aggregate・全社=内訳合計・HR mask）
- T079: （本ledger更新と同じcommitに含める）

---

## TASK CONTRACT T079（B2. scenario compare）

- requirements/AC: R3.2（仮配置scenarioで2案の稼働率・粗利を比較）、R3.3（保存者と共有範囲・契約/提案を自動作成しない）、R5
- 決定表はdesign.md §5の確定済み3表をそのまま実装（読み替えなし）

### 実装内容

- **比較**（`StaffingScenarioCompareService`）:
  - シナリオ別・月別（24か月）の供給FTE・稼働率・要員数・粗利を返す
  - 供給FTE = percent × 対象月内の日数/月内稼働日数（日単位のFTE換算・design §5.2と同一口径）
  - 稼働率 = 供給FTE / シナリオ内要員数 × 100
  - 粗利 = （position単価下限 − 要員希望単価）× FTE。**HRでmask（null）**（design §5.3）
  - 共有scenarioの閲覧時は**閲覧者のscope（DataScope/組織scope）で要員をfilter**
    （scenario経由のscope迂回を防ぐ。design §5.3）
- **API**: `/api/analytics/staffing-scenarios`（CRUD・仮配置upsert/delete・visibleAllocations・compare）
- **UI**: `/analytics/staffing-scenario-compare`（scenario作成/共有/比較テーブル）
- **isolation**: 本serviceはt_staffing_scenario系のみ読み書きし、実データへ書き込む経路を持たない
  （T075のStaffingScenarioServiceImplと同一契約・R3.3）

### 実装の具体化（判定・根拠）

1. 粗利の単価はposition.unit_price_min（下限保守）を採用し、原価は要員expected_unit_price。
2. scenarioの比較windowは基準日から24か月（heatmapと同一のhorizon）。
3. visibleAllocationsはscenario内の要員を閲覧者scopeでfilterして返す（管理画面の漏洩防止）。

## 変更file（T079）

| 種別 | file |
|---|---|
| service | `service/staffing/StaffingScenarioCompareService`(+Impl) |
| controller | `StaffingScenarioApiController`（/api/analytics配下）、`AnalyticsPageController`（page追加） |
| UI | `templates/analytics/staffing-scenario-compare.html`、`static/js/modules/staffing-scenario.js`、analytics/index.htmlにリンク |
| message | 4バンドルへ staffing.scenario.* 追加 |
| test | `StaffingScenarioCompareTest`（4件）、`StaffingScenarioApiControllerTest`（3件） |

## Test evidence（T079）

| 実行 | 結果 |
|---|---|
| 直接回帰: staffing 12クラス（67件）＋Contract/Proposal(56)＋AllMappers(133)＋MigrationIntegrity(27)＋MessageBundle(4)＋JsSyntaxCheck(1) | 286/0/0/0 PASS |
| `git diff --check` | exit 0（コミット前に実施） |

受入条件の実測:
- scenario操作（作成/仮配置/比較/削除）の前後でt_allocation_plan・契約・提案のハッシュが不変（R3.3）
- 供給FTE（3日/22営業日×100%=0.14）・稼働率（14.0%）・粗利（(80万-60万)×0.14=28000）の実測一致
- 共有scenarioは参照可・編集はownerのみ、非共有は他ユーザー不可視（scenarioForbidden）
- CSRFなしの更新系は403、HRの比較に粗利がmask
- API経由の作成一覧比較削除が一気通貫で動作

## Demo（T079）

- 2scenarioの稼働率/粗利差と実データ不変（ハッシュ比較）はtestで実証済み。
- 実ブラウザDemoはM task（T080）で実施する。

## Risk

- 粗利の単価はpositionの下限（保守）基準であり、実契約の売上単価とは別物
  （シナリオは仮定ベースの比較ツールである旨をledgerで管理）。

---

## TASK CONTRACT T078（B1. 需給heatmap/KPI）

- requirements/AC: R3.1（月別×skill/role/location別の需要・供給・不足・余剰・bench cost表示）、R5
- 決定表はdesign.md §5の確定済み3表をそのまま実装（読み替えなし）

### 実装内容

- **集計**（`StaffingHeatmapService`）:
  - 需要FTE = position（required_count × allocation_percent）を月内稼働日数（法人既定=平日）比で按分
  - 供給FTE = `StaffingCapacityService.supply` と同一口径（actual+plan。社内/待機は案件需給に寄与しない）
  - 不足=max(0, 需要-供給)、余剰=max(0, 供給-需要)、bench cost = (1.0 − 供給FTE) × 希望単価
  - 全社合計はrole次元の内訳合計から構築（各次元は分割性を持つためΣ=全社）
  - 24か月上限・window超過は拒否（design §4/§5.4）
  - グループ帰属の分割性: 供給は要員の「主要スキル」（proficiency降順・id昇順の先頭）／
    配置positionのrole/location、需要はpositionのrole/location/skills_json先頭
  - **HR mask**: benchCost・drilldownの単価をHRロールでnull（design §5.3）
  - scope: DataScopeService.allowedEngineerIds/allowedProjectIdsをSQL境界で適用
- **API**: `/api/analytics/staffing-heatmap`（既存analytics menuのapi_prefix配下・新規権限seed不要）＋drilldown
- **UI**: `/analytics/staffing-heatmap`（analytics indexからリンク）、staffing-heatmap.js
  （次元タブ・不足セルクリックでdrilldownモーダル）

### 実装の具体化（判定・根拠）

1. bench costの単価はengineer.expectedUnitPrice（希望単価）を使用（社内原価テーブルは
   人事領域のため、需給計画のKPIとして希望単価ベースと明記）。
2. skill次元の分割性: 要員が複数スキルを持つ場合も主要スキル1件へ帰属（Σ=全社を保証）。
3. 応答はグループ×月のグリッドのみ（engineer×dayの直積を作らない。セル数上限testで固定）。

## 変更file（T078）

| 種別 | file |
|---|---|
| service | `service/staffing/StaffingHeatmapService`(+Impl) |
| dto | `dto/staffing/HeatmapDto`、`dto/staffing/ShortfallDrilldownDto` |
| controller | `StaffingHeatmapApiController`（/api/analytics配下）、`AnalyticsPageController`（page追加） |
| UI | `templates/analytics/staffing-heatmap.html`、`static/js/modules/staffing-heatmap.js`、analytics/index.htmlにリンク |
| 修正 | `StaffingCapacityServiceImpl.sumPlanFte` にallocation_type='案件'フィルタ追加（社内/待機は案件需給に寄与しない。T076の実装方針を明示化） |
| message | 4バンドルへ staffing.heatmap.* / common.reload 追加 |
| test | `StaffingHeatmapServiceTest`（5件）、`StaffingHeatmapApiControllerTest`（4件） |

## Test evidence（T078）

| 実行 | 結果 |
|---|---|
| 直接回帰: staffing 10クラス（59件）＋Contract/Proposal(56)＋AllMappers(133)＋MigrationIntegrity(27)＋MessageBundle(4)＋JsSyntaxCheck(1) | 279/0/0/0 PASS |
| `git diff --check` | exit 0（コミット前に実施） |

受入条件の実測:
- 全社合計=内訳合計（skill/role/locationの3次元すべてで需要・供給・bench cost一致）
- 需要FTE = 2人×100%×22/22日=2.00、期間比で2人×100%×11/22日=1.00
- 24か月を超える要求・from>toは拒否（horizonExceeded/invalidPeriod）
- HRにはbenchCostがmask（null）、管理者には表示
- 50要員+24か月でセル数≤300（engineer×dayに比例しない）
- drilldownは需要（position）と供給（engineer）を返す

## Demo（T078）

- 全社合計と内訳合計の一致・Java需要不足のdrilldownはservice/API testで実証済み。
- 実ブラウザDemo（desktop/390px・heatmap表示・drilldown操作）はM task（T080）で実施する。

## Risk

- 需要側のskill名はposition.skills_json（自由入力）とm_skill_tag（要員側）の名寄せが完全には
  一致しない可能性がある。不足の検出はrole/location次元が確実（両側ともposition属性で一致）。
- bench costは希望単価ベース（人事原価と異なる旨をUI凡例に明記せず、ledgerで管理）。

---

## TASK CONTRACT T077（A1. position board/allocation timeline）

- requirements/AC: R1.1/R1.2（board表示・状態遷移UI）、R2.1/R2.2（timeline表示・過配賦拒否）、R5
- 決定表はdesign.md §5の確定済み3表をそのまま実装（読み替えなし）

### 実装内容

- **API**（既存menuのapi_prefix配下で新規権限seed不要・ActionPermissionResolver解決済み）:
  - `ProjectPositionApiController` `/api/projects/{id}/positions`（CRUD・status遷移）＋`/api/projects/{id}/board`
  - `AllocationApiController` `/api/engineers/{id}/allocations`（saveDraft/confirm/discard/revise）
  - scope: DataScopeService.assertAllowedProject/assertAllowedEngineer（404変換）
- **表示**: `StaffingBoardService`（entityを直接公開せず表示名・承認状態付きDTOをserver aggregateで返す。
  `AllocationCardDto`・`PositionBoardDto`）
- **UI**: project/detail.htmlにポジションボード（position列＋配置カード＋充足人数＋社内/待機列）、
  engineer/detail.htmlに「配置計画」タブ（タイムライン＋追加/編集/確定/破棄）。
  `static/js/modules/staffing.js`（共有モーダル・board D&D・timeline）
- **D&D**: planカードの列間移動＝配置の上書き保存。API失敗時はカードを元の列へ戻す（UI rollback・design §3）。
  actual（実契約由来）カードはドラッグ不可。
- **競合**: 確定はserviceのFOR UPDATE＋再検証＋状態CAS（design §5.4）。同一配置への同時確定は
  状態CASで片方だけ成功（`AllocationConcurrentConfirmTest`の2スレッドREQUIRES_NEWで実証）

### 実装の具体化（判定・根拠）

1. 新規URI prefixを作らず既存の `/api/engineers`・`/api/projects` 配下に配置
   （新規prefixはActionPermissionResolver未登録で全role 403になるため・CRM-R2-P1-01と同じ事故の回避）。
2. `@Valid`はpathから設定されるprojectId/engineerIdと衝突するため、position entityの
   projectId/statusの必須検証をservice側に移し、allocation APIはservice検証のみ。
3. H2はSELECT FOR UPDATEの並行ロックを再現しないため、同時配置の決定的testは
   「同一配置への同時確定（状態CAS競合）」と「後着が先行コミットを読んで過配賦拒否」の2本で構成。

## 変更file（T077）

| 種別 | file |
|---|---|
| controller | `ProjectPositionApiController`、`AllocationApiController` |
| service | `service/staffing/StaffingBoardService`(+Impl) |
| dto | `dto/staffing/PositionBoardDto`、`dto/staffing/AllocationCardDto` |
| UI | `templates/project/detail.html`（board＋position modal）、`templates/engineer/detail.html`（配置計画タブ＋allocation modal）、`static/js/modules/staffing.js`（新規）、`project-detail.js`/`engineer-detail.js`（init hook） |
| entity | `ProjectPosition`（projectId/statusの@Valid必須検証をservice側へ） |
| message | 4バンドルへ staffing.* UIキー追加 |
| test | `StaffingApiControllerTest`（4件・CSRF/CRUD/状態遷移/過配賦/scope）、`StaffingApiScopeTest`（2件・DataScope mock 404）、`AllocationConcurrentConfirmTest`（2件・同時確定/後着拒否） |

## Test evidence（T077）

| 実行 | 結果 |
|---|---|
| 直接回帰: staffing 7クラス（49件）＋Contract/Proposal(56)＋AllMappers(133)＋MigrationIntegrity(27)＋MessageBundle(4)＋JsSyntaxCheck(1) | 270/0/0/0 PASS |
| `git diff --check` | exit 0（コミット前に実施） |

受入条件の実測:
- CSRFトークンなしの更新系POSTは403
- ポジションCRUD・状態遷移（候補選定）・board表示
- 配置の保存→確定→破棄、60%+50%の過配賦はAPIで拒否（HTTP 400）
- 同一配置への同時確定は状態CASで片方だけ成功、後着は先行コミットを読んで過配賦拒否
- DataScope拒否は404へ変換（営業・mock決定的test）

## Demo（T077）

- 兼務配置（50%+50%許可）と過配賦拒否（60%+50%拒否）はAPI/service testで実証済み。
- D&DのUI rollbackはstaffing.jsのrollbackCard実装＋API失敗経路をコードで担保し、
  desktop/390pxの実ブラウザDemoはM task（T080）で実施する（browser evidenceはMで固定）。

## Risk

- H2はSELECT FOR UPDATEの実ロックを再現しない（MySQLで有効）。同時配置の防衛は
  FOR UPDATE＋再検証＋状態CASの3層で、MySQL smoke（V103）とCIで担保。
- boardのD&DはHTML5 Drag&Drop API（タッチデバイスでは配置ボタン/モーダル操作を推奨）。

---

## TASK CONTRACT T076（F2. proposal/contract/availability統合）

- requirements/AC: R1.3（position充足自動更新＝actualで導出）、R2.3（実契約をactual allocationとして表示）、
  R2.4（退職/休暇/契約終了/更新decisionをcapacityへ反映）、R5（plan/actual二重計上0）
- 決定表はdesign.md §5の確定済み3表をそのまま実装（読み替えなし）

### 実装内容

- **proposal/contractのposition link**: ProposalServiceImpl.save/updateByIdでポジションは案件配下の
  実在ポジションに限定（`error.staffing.positionProjectMismatch`）。ContractServiceImpl.validateでも
  同様に検証。createDraftFromProposalは提案のposition_idをドラフトへ引き継ぐ。
- **actual allocation同期**（`StaffingContractSyncService`）: 契約の変更経路（saveWithBusinessRules/
  updateWithBusinessRules/changeStatus/removeById）から呼び、準備中/稼動中→actual行をupsert
  （配賦率100・status=確定）、終了/解約/ポジション解除/削除→破棄。冪等（同一契約1行）。
  同一engineer+positionの確定planはactual成立でsupersede（破棄・FOR UPDATE付き）。
- **需給集計**（`StaffingCapacityService`）:
  - plan/actualの排他はSQLのWHERE句（source_contract_id IS NULL/NOT NULL）で実施（design §5.4）。
  - 月別FTE = 月内の稼働可能日数（m_work_calendar day_type='通常'・承認済休暇控除後、無い場合は法人既定=平日）
    に対する在籍日数比 × 配賦率。cap=配賦率（休暇で契約FTEを自動変更しない。design §5.2）。
  - 更新済契約（autoRenew=1∧assumeRenew∧renewalDecision≠'END'）は終了日以降もactualとして延長
    （UtilizationCalcService.isActiveInMonthと同一規則）。
  - 退場予定（engineer.status='退場予定'）は最終契約終了日以降の供給と稼働可能日数を0にする。
  - 稼働率はUtilizationCalcServiceへ委譲しdashboard KPIと同一値（design §5.1）。

### 実装の具体化（specの文言が曖昧だった箇所、判定・根拠）

1. actual行の配賦率は100%（契約＝フルコミットメント）とし、過配賦の100%判定はplan確定時のみ
   （actualは事実の反映であり拒否しない。過剰実績は集計にそのまま表れ、plan確定側が抑制される）。
2. 退職日は明示列が無いため「退場予定＋最終契約終了日」で反映（DashboardServiceImplの退場予定
   リストと同じ契約終了日ベース）。
3. 休暇の日割りは LeaveMinutesCalculator と同じ種別振り分け（全日=1/日、半休=0.5/日、時間休=min(1, 分/480)）。
4. 更新継続の判定は契約単位で行うため、actual行の期間重なり判定をJava側で行う
   （対象は要員1人分の行のみ。全engineer×全dayの直積にはならない。SQLはactual/planの排他と
   engineer境界だけを担う）。

## 変更file（T076）

| 種別 | file |
|---|---|
| service | `service/staffing/StaffingContractSyncService`(+Impl)、`service/staffing/StaffingCapacityService`(+Impl・EngineerMonthSupply record) |
| hook | `ContractServiceImpl`（position検証＋syncActual/removeActual hook×4経路＋DraftSource.positionId）、`ProposalServiceImpl`（position検証） |
| message | 4バンドルへ `error.staffing.positionProjectMismatch` 追加 |
| H2同期 | `schema-staffing-h2.sql` へ共有H2のt_contract補完（renewed_from_contract_id。V12はH2 replay対象外のため） |
| test | `StaffingContractSyncTest`（8件）、`StaffingCapacityServiceTest`（6件） |

## Test evidence（T076）

| 実行 | 結果 |
|---|---|
| 直接回帰（clean build）: ContractServiceImplTest(48)・ProposalServiceImplTest(8)・LeaveApprovalFlowIntegrationTest(2)・DashboardServiceImplTest(17)・SalesPerformanceServiceImplTest(9)・staffing 4クラス(35)・AllMappersSchemaSweepTest(133)・MigrationScriptIntegrityTest(27)・MessageBundleConsistencyTest(4) | 289/0/0/0 PASS |
| `git diff --check` | exit 0（コミット前に実施） |

受入条件の実測:
- 契約作成（準備中）→ actual行1件（確定・100%）。再同期でも1件（冪等）
- 契約更新でactualの期間/ポジション追従、終了/解約/削除で破棄
- 同一engineer+positionの確定planはactual成立でsupersede（plan/actual二重計上なし）
- 更新済契約（CONTINUE）は終了日以降もactual計上、ENDで停止
- 退場予定は最終契約終了日以降、供給0・稼働可能日数0
- 承認済休暇2日で稼働可能日数-2、契約FTEは100%のまま
- 稼働率はUtilizationCalcServiceと同一口径（dashboard KPI一致）
- 提案のposition_idが契約ドラフトへ引き継がれactualが作られる

## Demo（T076）

F2もservice層のため、Demoは受入条件を直接assertする上記テスト実行を証跡とする。
UI Demo（提案→契約でposition充足の表示）はT077で実施する。

## Risk

- MyBatisの1次キャッシュ（SqlSession cache）: 同一transaction内で同一パラメータの再実行は
  キャッシュが返るため、休暇挿入後の再集計testではclearCache()で対応（productionのsupply()は
  月/要員ごとに異なるパラメータで呼ばれるため影響なし）。
- 共有H2のt_contractにrenewed_from_contract_idを補完（既存replay対象外のV12相当）。
  engineer-schema-h2.sqlでは元々定義済みだったためH2形状の収束先は同一。`
