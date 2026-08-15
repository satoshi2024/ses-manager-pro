# Review Ledger — 要員配置・需給計画（S12 staffing-capacity-planning）

## 現行判定

- **T075 F1: 完了（REVIEW待ち）** 2026-08-15
- T076〜T080: 未着手

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

- T075: （本ledger更新と同じcommitに含める）
