# SES Manager Pro 結合テスト 欠陥台帳（Defect Catalog）

本台帳は、SES Manager Pro 結合テスト（ITa/ITb/E2E/UI実操作/モンキー/性能/Security）において検出された欠陥を一元管理する正本台帳である。

---

## 1. 欠陥サマリ

| 欠陥ID | Severity | 対象モジュール | 関連用例ID | 状態 | 担当Owner | 概要 |
|---|:---:|---|---|:---:|:---:|---|
| D-20260816-001 | P2 | MOD-10 S12 / StaffingHeatmapService | `StaffingPerformanceTest` | OPEN | 開発（S12） | 代表データ量（200要員×50position×24月）需給集計時のN+1多表クエリによるp95遅延超過（実測402s > 10s閾値） |
| D-20260817-002 | P2 | MOD-07 / ContractApiController | `E2E-07-R / MOD07-07 / PILOT-05` | OPEN | 開発（契約管理） | `ContractSaveDto` に `version` 属性が欠落しており、更新APIでMyBatis-Plus楽観ロックがバイパスされる。行ロック（`selectByIdForUpdate`）により直列化されるが、409競合検知が行われず後勝ち上書きとなる。 |
| D-20260817-003 | P2 | MOD-03 / ActionPermissionResolver | `MOD03-18` | OPEN | 開発（セキュリティ・認証） | `ActionPermissionResolver` に `bp-affiliations` のルートマッピングが欠落しており、`MenuPermissionFilter` により全ロール（管理者含む）で `/api/bp-affiliations/**` が 403 拒否される。 |

---

## 2. 欠陥詳細記録

### D-20260816-001: StaffingHeatmapService 需給集計のN+1クエリによる性能gate未達（p95 402s）

- **欠陥ID**: `D-20260816-001`
- **Severity**: `P2`（性能gate未達 / S12未受入範囲）
- **対象モジュール**: `MOD-10 S12 / StaffingHeatmapService`
- **関連用例ID**: `StaffingPerformanceTest.データ量増加でp95とheap増加を実測`
- **検出Build SHA**: `f00360f95d3875b30d0f343ed9cc47e76d72b803`
- **RUN_ID**: `CI-20260816-001`
- **状態**: `OPEN`
- **担当Owner**: 開発（S12担当）
- **関連**: 未受入 S12、`disabled-tests/staffing/StaffingPerformanceTest.java`（S12改修完了後にテスト配置復元し回帰検証要）
- **再現手順**:
  1. 顧客1件、案件1件、要員200件、position50件、配置計画300件を投入。
  2. `StaffingHeatmapService.heatmap(LocalDate.of(2026, 8, 1))` を実行。
  3. レイテンシを5回測定し、p95を算出。
- **期待結果**:
  - `p95 < 10,000ms`（10秒未満）
  - セル数 ≤ 800
  - heap増加 < 256MB
- **実際の結果**:
  - 5回測定値: `[88214ms, 169877ms, 241060ms, 328255ms, 402086ms]`
  - `p95 = 402,086ms`（約402秒）となり、断言 `assertTrue(p95 < 10_000)` に違反。
- **Root Cause**:
  - `StaffingHeatmapServiceImpl` 内の需給集計ロジックにおいて、要員（200件）× 月（24か月）に対してループ内で個別テーブル（`m_work_calendar`, `t_leave_request`, `t_allocation_plan` 等）の `selectList` を発行する N+1 クエリ構造が存在し、データ量増加に伴いクエリ回数・実行時間が急増している。
- **証跡**:
  - `target/surefire-reports/TEST-com.ses.staffing.StaffingPerformanceTest.xml`
- **対応方針 / 回帰条件**:
  - 開発側で月次一括バッチ取得/インメモリ集計等に最適化後、`disabled-tests/staffing/StaffingPerformanceTest.java` を `src/test/java/com/ses/staffing/` へ戻して CI skip 0 かつ p95 < 10s 合格を確認する。

---

### D-20260817-002: ContractSaveDto の version 属性欠落による契約更新時楽観ロック409バイパス

- **欠陥ID**: `D-20260817-002`
- **Severity**: `P2`（行ロック `selectByIdForUpdate` によりデータ破損は防がれ直列化されるが、409 楽観ロック競合検知が動作せず後勝ち上書きとなる）
- **対象モジュール**: `MOD-07 / ContractApiController`
- **関連用例ID**: `E2E-07-R / MOD07-07 / PILOT-05`
- **検出Build SHA**: `f00360f95d3875b30d0f343ed9cc47e76d72b803`
- **RUN_ID**: `E2E-20260816-001`
- **状態**: `OPEN`
- **担当Owner**: 開発（契約管理）
- **ロック実装証跡**:
  - `ContractServiceImpl.java` L227: `Contract old = this.baseMapper.selectByIdForUpdate(contract.getId());`（行ロック実装を確認）
  - `Contract.java`: `@Version private Integer version;`（MyBatis-Plus楽観ロック注釈あり）
  - `ContractSaveDto.java`: `version` 属性が未定義（L1-73）
- **再現手順**:
  1. 契約 `id: X`（初期 `version: 0`）を用意。
  2. 2 つのクライアント A と B が同時に同一の `version: 0` を前提として `PUT /api/contracts/X`（異なる単価/備考）を送信。
  3. `ContractApiController.update` が DTO を `Contract` にコピーする際、DTO に `version` がないため `contract.version` が null となる。
- **期待結果（E2E-07-R / 計画書定義）**:
  - 一方の更新が成功（HTTP 200、version +1）。
  - もう一方の更新は旧版指定により拒否（HTTP 409 `OptimisticLockingFailureException`）。
- **実際の結果**:
  - 行ロックによりトランザクションは直列化され両リクエストとも HTTP 200 で終了。
  - MyBatis-Plus に `version` が渡されないため `version` のインクリメントおよび不一致チェックがスキップされ、後からコミットしたリクエストで上書き（Last Write Wins）される。
- **対応方針 / 回帰条件**:
  - `ContractSaveDto` に `@NotNull Integer version` を追加し、`ContractApiController.update` 経由で MyBatis-Plus の楽観ロックまたは明示的な版番号不一致チェック（409 返却）を有効化する。

---

### D-20260817-003: ActionPermissionResolver の bp-affiliations ルート欠落による 403 遮断

- **欠陥ID**: `D-20260817-003`
- **Severity**: `P2`（要員BP所属履歴 API `/api/bp-affiliations/**` へのアクセスが全ロールで遮断される）
- **対象モジュール**: `MOD-03 / ActionPermissionResolver`
- **関連用例ID**: `MOD03-18`
- **検出Build SHA**: `f00360f95d3875b30d0f343ed9cc47e76d72b803`
- **RUN_ID**: `E2E-20260816-001`
- **状態**: `OPEN`
- **担当Owner**: 開発（セキュリティ・認可）
- **再現手順**:
  1. `管理者` または `営業` ロールでログイン。
  2. `GET /api/bp-affiliations/engineer/{id}` を実行。
- **期待結果**:
  - HTTP 200 で所属履歴リストが返却される。
- **実際の結果**:
  - `ActionPermissionResolver.resolve()` が `null` を返し、`MenuPermissionFilter` により HTTP 403 `{"code":403,"message":"このactionへのアクセス権限がありません"}` で遮断される。
- **Root Cause**:
  - `ActionPermissionResolver.java` の `RESOURCE_ALIASES` および `resolve()` 内に `bp-affiliations` のパスプレフィックスおよび action 導出定義が存在しない。
- **対応方針 / 回帰条件**:
  - `ActionPermissionResolver` に `Map.entry("bp-affiliations", "engineer")` または専用アクションを登録し、`MOD03-18` が PASS することを確認する。

