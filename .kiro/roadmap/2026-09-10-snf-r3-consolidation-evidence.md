# S-NF R3 統合証跡（2026-09-10/11）

## 固定入力

| 役割 | branch | SHA |
|---|---|---|
| R2 凍結基線 | `codex/snf-integration-gate` | `4ef697205118833cb15761391cd4abc7cd9c2861` |
| NF05/08/09/10 + 混合 Review salvage | `codex/snf-main-dirty-salvage-20260910` | `1d9f95f26ae3ba73ed54d3673d5d9952bb37b209` |
| NF02/NF03 最新修正 | `codex/snf-r3-nf02-nf03` | `6d4aa8759d05090fa738cefbb911ee031d35d2fd` |
| 共同祖先 | `main` tip at start | `996289c00983bfccd0b75c4d3bdd3dcc26904136` |

- R3 branch: `codex/snf-r3-consolidation`
- R3 worktree: `C:\work\ses-snf-r3-consolidation`
- 起点 SHA: `4ef697205118833cb15761391cd4abc7cd9c2861`
- main / R2 worktree / 両ソースブランチは未変更（検証済み）

## 統合順序と merge commit

1. salvage を `--no-ff` で統合  
   - merge commit: `b776c7928fc3551511753e1462dc958c649694d5`  
   - parents: `4ef69720` + `1d9f95f2`
2. NF02/NF03 を `--no-ff` で統合（V156–V172 を V162–V178 へ順延）  
   - merge commit: `df4b42d69a92315f16673feb562a386925e21da4`  
   - parents: `b776c792` + `6d4aa875`
3. 以降の tenant/fixture/コンパイル整合コミット群（既存 R3 作業、HEAD 直前は `92289f9a`）
4. 最終 R3 integration commit（本証跡と V162/V179/portal/fixture 仕上げ）

## 衝突ファイルと意味論処理

### Phase 2: salvage 対 R2（重複 41 ファイル）

代表的な意味論:

| 領域 | 方針 |
|---|---|
| NF05 Integration Hub / legal entity | salvage 側の write boundary・readiness・backfill audit を優先 |
| NF08 AI scope / provider / ExecutionContext / redaction | salvage の CopilotExecutionContext・scope/gateway・Legacy 境界を優先 |
| NF09 actor attribution / digital invoice | salvage の ExecutionActorContext・invoice 境界を優先 |
| NF10 report delivery / reconciliation | salvage の ReportDelivery・Scheduler・snapshot hardening を優先 |
| CertificationContinuityGroup | salvage/NF03 の複合 PK 形（旧 R2 の単一 identity 形ではない）を採用 |
| Service Desk | R2 既存 SLA 修復と salvage 側更新を組合せ |

### Phase 3: NF02/NF03 対 post-salvage（重複 135 ファイル）

優先規則:

- **tenant ownership / DataScope / CAS / SLA / WorkRecord / Contract / Training / Certification** → NF02/NF03 最新を優先
- **NF05/08/09/10 専属機能** → salvage を優先
- 公共ファイルは両論理を合成（ours/theirs 一括採択なし）

重点ファイルの扱い:

| ファイル | 処理 |
|---|---|
| `V1__create_tables.sql` | 双方の列追加を合成。重複 ADD を避ける |
| `V147__customer_success_service_desk.sql` | Service Desk 基線を保持し NF02 SLA 追加と整合 |
| `application.yml` / `application-test.yml` | tenant・AI・test schema 設定を合成 |
| `engineer-schema-h2.sql` | tenant/ownership 列を H2 へ反映 |
| `ContractMapper` / `ContractServiceImpl` | NF02 tenant ownership API を優先しつつ salvage 副作用を保持 |
| `WorkRecordMapper` / `WorkRecordServiceImpl` | NF02 tenant CAS / ForUpdate 経路を優先 |
| `DashboardServiceImpl` / `InvoiceServiceImpl` / `DataScopeServiceImpl` | tenant 境界を合成 |
| `NotificationOutbox*` | NF02 tenant retry + NF10 report replay API を併存 |
| `AiRecommendationRun*` | V161(NF08 scope metadata) + V162/V173(NF02/03 tenant records) を連携 |
| `scripts/test-suites/mysql-shard-*.txt` | タグ付き MySQL テストの和集合・重複なし |
| `PortalBpServiceImpl` | NF02 tenant 境界 + NF05 legal entity 刻印を合成 |
| `DocumentServiceImpl` × portal 提出 | NF09 `validateDocumentType` を維持し `BP_SUBMISSION` を V179 で正本追加 |

## Migration V153–V179 対照表

| R3 版 | 由来 | 内容 |
|---|---|---|
| V153 | 共通（同一） | NF02 SLA breach timestamps |
| V154 | 共通（同一） | certification continuity group |
| V155 | 共通（同一） | NF03 training budget amendment |
| V156 | salvage | NF09/NF10 actor/report snapshot |
| V157 | salvage | NF05 external API legal entity |
| V158 | salvage | NF05 legal entity backfill audit |
| V159 | salvage | NF10 report reconciliation |
| V160 | salvage | NF05 legal write boundary |
| V161 | salvage | NF08 AI recommendation scope metadata |
| V162 | NF02 旧 V156 | nf02_nf03_boundary_repair（`tenant_id` は MODIFY） |
| V163 | NF02 旧 V157 | tenant attachment/notification boundary |
| V164 | NF02 旧 V158 | certification master optimistic lock |
| V165 | NF02 旧 V159 | approval tenant isolation |
| V166 | NF02 旧 V160 | task notification tenant retry |
| V167 | NF02 旧 V161 | expense accounting job tenant scope |
| V168 | NF02 旧 V162 | notification outbox tenant scope |
| V169 | NF02 旧 V163 | explicit customer/engineer ownership |
| V170 | NF02 旧 V164 | ownership repair operations |
| V171 | NF02 旧 V165 | bp availability tenant scope |
| V172 | NF02 旧 V166 | engineer account link tenant repair |
| V173 | NF02 旧 V167 | AI recommendation tenant records |
| V174 | NF02 旧 V168 | ownership repair authority CAS |
| V175 | NF02 旧 V169 | resume/candidate/skill CAS |
| V176 | NF02 旧 V170 | project/candidate tenant evidence |
| V177 | NF02 旧 V171 | contract tenant SLA notification boundary |
| V178 | NF02 旧 V172 | contract reference ownership repair |
| V179 | R3 新規 | NF09 × BP portal: `BP_SUBMISSION` document type シード |

### 順延後の依存調整

- **V162 × V161**: salvage V161 が `t_ai_recommendation_run.tenant_id` を先行追加するため、順延後 V162 の `ADD COLUMN tenant_id` は **重複**。  
  → V162 を `MODIFY COLUMN tenant_id VARCHAR(100) NULL` + `idx_ai_run_tenant_created` に変更。  
  - NF08: 推測 backfill しない（NULL 許容維持）  
  - NF02: 幅を VARCHAR(100) に揃え ownership 連携（V173）を可能にする  
- V158/V160 の `legal_entity_id` は information_schema ガード付きのため実質重複ではない。
- 既定マッピング以外の版番号入替は行っていない（版は一意）。
- V179 は portal `BP_SUBMISSION` と NF09 document-type 検証の突合用。業務意味は既存 portal 提出種別の正本化のみ。

## 改名したテスト / 参照

| 旧（NF02/NF03） | 新（R3） |
|---|---|
| `Nf02Nf03V156MigrationContractTest` | `Nf02Nf03V162MigrationContractTest` |
| `Nf02Nf03V157MigrationContractTest` | `Nf02Nf03V163MigrationContractTest` |
| `Nf02Nf03V170EvidenceConflictMySqlTest` | `Nf02Nf03V176EvidenceConflictMySqlTest` |
| `Nf02Nf03V170TenantIsolationMySqlTest` | `Nf02Nf03V176TenantIsolationMySqlTest` |
| `Nf02Nf03V172ContractReferenceOwnershipMySqlTest` | `Nf02Nf03V178ContractReferenceOwnershipMySqlTest` |

あわせて migration ファイル名、SQL ファイル名アサーション、resource path、shard リスト、ドキュメント参照を V162–V178 に同期。

## 既知問題の再判定（74.3 / ApplicationContext）

NF02/NF03 worktree 報告:

- `FlywayCertificationLearningSkillGapSchemaSmokeTest` に `resolved migration not applied: 74.3`
- `WorkRecordTenantIsolationMySqlTest` の ApplicationContext 失敗

R3 再実行結果:

1. **直接原因は V162 の `Duplicate column name 'tenant_id'`**（V161 との衝突）。  
   Flyway 失敗後に共有コンテナ履歴が半端になり、後続テストが Validate/半適用状態へ連鎖した。
2. V162 修正後、同テスト群は **PASS**。`74.3` エラーは再現せず。  
   → **migration シーケンス回帰（V161/V162 重複）が主因**であり、単純な環境問題として無視すべきではない。  
   `ignoreMigrationPatterns` / `outOfOrder=true` による隠蔽は未実施。
3. `WorkRecordTenantIsolationMySqlTest` の ApplicationContext 失敗も同一 V162 Flyway 失敗が原因。修正後 PASS。

追加修正（最終仕上げ）:

- smoke が R2 旧 continuity スキーマ（`updated_at` / `uk_cert_continuity_group_ident`）を要求していたため、V154 複合 PK 形へアサーションと INSERT を整合。
- `ReportDeliveryServiceImplTest` の `selectById` stub を tenant 付き `selectOne` に修正。
- portal 空き要員: `PortalBpServiceImpl.createAvailability` で NF05 legal entity を刻印。
- portal 提出物: V179 + H2 schema に `BP_SUBMISSION` を追加（NF09 `validateDocumentType` と突合）。
- portal fixture: `tenant_id` / `LoginUser` DataScope 境界を明示。
- `SecurityUtils.currentTenantId` が `PortalLoginUser` を認識するよう拡張。

## 検証結果

### 事前ゲート

| 項目 | 結果 |
|---|---|
| `git diff --check` | PASS |
| Maven `test-compile` | PASS |
| `MigrationScriptIntegrityTest` | PASS |
| `Nf02Nf03V162/V163MigrationContractTest` | PASS |
| `FlywayMigrationSmokeTest` | PASS |
| `MySqlTestShardInventoryTest` | PASS |

### Targeted H2 / MockMvc

- Service Desk / SLA: PASS
- Certification / Learning / Training: PASS
- Contract / WorkRecord / MonthlyClosing: PASS（H2）
- tenant ownership / repair / CAS: PASS
- Integration Hub / legal entity: PASS
- AI scope / provider / feedback / redaction: PASS
- Digital Invoice / actor attribution / Report Delivery: PASS
- Portal Admin DataScope / Portal BP review・提出: PASS（最終仕上げ後）

### MySQL profile（Docker 利用可）

対象:

- `FlywayCertificationLearningSkillGapSchemaSmokeTest`
- `WorkRecordTenantIsolationMySqlTest`
- `Nf02Nf03V178ContractReferenceOwnershipMySqlTest`
- `MySqlTestShardInventoryTest`

結果: **PASS**（BUILD SUCCESS。V162 修正後に `74.3` は再現せず）

### Fast suite

- `mvn test`: **Tests run: 3880, Failures: 0, Errors: 0, Skipped: 0**
- `BUILD SUCCESS`（約 10:23）

## 環境 blocker

- Docker Desktop 利用可（MySQL Testcontainers 実行済み）→ MySQL targeted は **PASS**（BLOCKED ではない）
- main / ソースブランチ / R2 worktree への変更・push・PR・production approval は行っていない

## 未解決の Review finding

- ソースブランチ側に残る個別 Review 指摘のうち、本 R3 で未着手のものは各 NF remediation ブランチ側の継続課題として残置
- `FlywaySelfServiceSchemaSmokeTest` 内の既存 `outOfOrder(true)` は main 由来（NF-10 歴史 fixture）であり、本 R3 では新規追加・拡張していない

## Phase 2 重複ファイル一覧（41）

```
.kiro/specs/integration-hub-public-api/openapi-candidate.yaml
src/main/java/com/ses/controller/api/ManagementReportDeliveryApiController.java
src/main/java/com/ses/entity/CertificationContinuityGroup.java
src/main/java/com/ses/mapper/CertificationContinuityGroupMapper.java
src/main/java/com/ses/service/ai/copilot/CopilotExecutionContext.java
src/main/java/com/ses/service/ai/copilot/CopilotExecutionContextFactory.java
src/main/java/com/ses/service/ai/copilot/CopilotQueryService.java
src/main/java/com/ses/service/ai/copilot/CopilotRunService.java
src/main/java/com/ses/service/ai/copilot/gateway/CashFlowForecastCatalogAdapter.java
src/main/java/com/ses/service/ai/copilot/gateway/CatalogAdapterSupport.java
src/main/java/com/ses/service/ai/copilot/gateway/CatalogQueryAdapter.java
src/main/java/com/ses/service/ai/copilot/gateway/CatalogQueryGateway.java
src/main/java/com/ses/service/ai/copilot/gateway/DashboardProfitAnalysisCatalogAdapter.java
src/main/java/com/ses/service/ai/copilot/gateway/DashboardSummaryCatalogAdapter.java
src/main/java/com/ses/service/ai/copilot/gateway/DashboardUtilizationForecastCatalogAdapter.java
src/main/java/com/ses/service/ai/copilot/gateway/ManagementAccountingSummaryCatalogAdapter.java
src/main/java/com/ses/service/ai/copilot/parameter/TypedParameterBinder.java
src/main/java/com/ses/service/ai/copilot/scope/CopilotScopeResolver.java
src/main/java/com/ses/service/certification/EngineerCertificationServiceImpl.java
src/main/java/com/ses/service/integrationhub/ExternalApiReadService.java
src/main/java/com/ses/service/report/impl/ReportDeliveryServiceImpl.java
src/main/java/com/ses/service/scheduler/ManagementReportScheduler.java
src/main/java/com/ses/service/servicedesk/impl/ServiceRequestServiceImpl.java
src/test/java/com/ses/migration/FlywayCertificationLearningSkillGapSchemaSmokeTest.java
src/test/java/com/ses/migration/FlywayCustomerSuccessServiceDeskConcurrencyTest.java
src/test/java/com/ses/migration/IntegrationHubMPenetrationTest.java
src/test/java/com/ses/migration/IntegrationHubOpenApiContractTest.java
src/test/java/com/ses/report/ManagementReportSchedulerTest.java
src/test/java/com/ses/report/ReportDeliveryServiceImplTest.java
src/test/java/com/ses/report/ReportDeliveryTransactionIntegrationTest.java
src/test/java/com/ses/service/ai/copilot/CopilotFeatureGateTest.java
src/test/java/com/ses/service/ai/copilot/CopilotQueryServiceTest.java
src/test/java/com/ses/service/ai/copilot/gateway/CatalogQueryGatewayTest.java
src/test/java/com/ses/service/ai/copilot/gateway/CopilotMetricContractTest.java
src/test/java/com/ses/service/ai/copilot/parameter/TypedParameterBinderTest.java
src/test/java/com/ses/service/ai/copilot/scope/CopilotScopeResolverTest.java
src/test/java/com/ses/service/integrationhub/ExternalApiReadServiceTest.java
src/test/java/com/ses/service/integrationhub/ExternalApiReadSnapshotIntegrationTest.java
src/test/java/com/ses/testsupport/TestIsolationAuditTest.java
src/test/resources/sql/schema-certification-learning-skill-gap-h2.sql
src/test/resources/sql/schema-service-desk-h2.sql
```

## Phase 3 重複ファイル数

- 重複 135 ファイル（和集合合成。完全一覧は統合時の merge-tree / name-status で確認）
- 主要カテゴリ: controller/api、entity/mapper、Contract/WorkRecord/DataScope/Invoice/Dashboard、NotificationOutbox、AI recommendation、Service Desk、migration V153–V155、application*.yml、H2 schema、mysql-shard-3.txt
