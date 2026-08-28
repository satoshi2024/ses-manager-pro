# NF-03 Review packet（M完了）

## Handoff status

| 項目 | 値 |
|---|---|
| Feature | `certification-learning-skill-gap` |
| Status | A1→A2→B1→B2→M完了、独立Implementation Review待ち |
| Approved scope | NF-03 `APPROVED`。資格/期限/証憑、training、既存ExpenseRequest研修費、90/60/30通知、event-based as-of skill gap、rule gap＋AI course/skill候補、本人/manager/HR workflow |
| Out of scope | 外部LMS自動連携、AI自動評価/配置/採否・昇格・給与・不利益判断、NF-07保持年数の本番有効化 |
| Owner | `PROJECT_OWNER` |
| Decision | `DG-03-SCOPE-APPROVAL-20260828-01` / `DG-03-DEV-20260828` |
| Approved Base | `origin/main@76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| Worktree | `C:\work\ses-certification-learning-skill-gap` |
| Branch | `codex/certification-learning-skill-gap` |
| Normal checkout | `C:\work\ses-manager-pro`（変更なし） |
| Final local/remote HEAD | `FINAL_DOCS_HEAD_TO_BE_PINNED` |
| Latest origin/main | `a3454c086c6d17f94f96ced4175adec932f071b7` |
| merge-base | `a3454c086c6d17f94f96ced4175adec932f071b7` |
| PR / merge / branch delete | すべて未実施。ReviewのPLAN/IMPLEMENTATION双方PASS後のみPR対象 |

## Commit and change inventory

主要milestone commitは以下のとおり。完全な変更ファイル一覧は、review時に次のコマンドでBaseから再現できる。

```text
git log --oneline --decorate 76e45340a23cfee964fac778b7b4d856fa2c9e7b..HEAD
git diff --name-status 76e45340a23cfee964fac778b7b4d856fa2c9e7b..HEAD
git diff --stat 76e45340a23cfee964fac778b7b4d856fa2c9e7b..HEAD
```

| wave | commit | 内容 |
|---|---|---|
| main merge | `7650247bea5a7ac62c4afef3a8500e7002313171` | main V115とのmigration conflict解消、main最新取り込み |
| A1 | `f219905f` | HR/manager/admin list/detail/count/export、共通population、PII mask、permission、UI |
| A1/A2 docs | `c1dbed5e` | A1/A2 receipt・completion証拠 |
| A2 | `5a5d8571` | 本人scope、資格申請/証憑upload、learning plan/enrollment、本人export/UI |
| B1 docs | `3578a6d1` | B1 receipt・持越し契約 |
| B1 | `151346ed` | approval委譲、typed evidence download、version/hash/CLEAN/legal hold/FileScope接続 |
| B2 | `0168e8ea` | staffing as-of、rule gap、AI candidate-only接続 |
| B2 docs | `86f17498` | B2 receipt・completion証拠 |
| M code/test fix | `4ba1738c4e5afe6ad3839afe1e681a9621326846` | V127へ同期したV110 latest migration assertion |
| M docs | `FINAL_DOCS_HEAD_TO_BE_PINNED` | tasks/completion/remediation/Review packet最終固定 |

変更ファイルは225件（コード、migration、H2 schema、tests、templates/static、spec/receipt docs）。Mでは既存本体を変更せず、最終証拠docsを追加・更新した。

## Migration and schema

- `origin/main`の`V115__pwa_client_mutation_ledger.sql`は変更せず保持した。
- NF-03のmigrationは`V116`〜`V127`へ順延し、V115との重複を解消した。
- V116 certification master/record、V117 certification event/evidence type、V118 training、V119 skill-gap events、V120 assessment/decision、V121 lifecycle/PII permission、V122 expense relation/audit、V123 taxonomy alias、V124 AI artifact、V125 audit timestamps、V126 management menu/action、V127 self-service menu/action。
- `MigrationScriptIntegrityTest` 28件、`AllMappersSchemaSweepTest` 188件、合計216件PASS。
- 修正後MySQL smokeは`FlywayCertificationLearningSkillGapSchemaSmokeTest`、`FlywaySelfServiceSchemaSmokeTest`、`FlywayV110AdminBoundaryUpgradeSmokeTest`の6件PASS。latest assertionは127へ同期済み。

## Test gates

| gate | command / result | 判定 |
|---|---|---|
| fast | `mvn -q test` → 3051 run / 2 failures / 11 errors / 0 skipped。既存baselineとWindows loopback環境エラーのみ。NF-03対象report failure/error=0。 | feature regressionはPASS。全体は環境baseline付き |
| MySQL | `mvn -q test -Pmysql-tests` → 89 run / 0 failure / 1 error / 0 skipped。唯一のerrorは既存`FreeeConcurrentRefreshTest`のWindows loopback。NF-03 migration/feature reportは全件PASS。 | featureはPASS、環境errorを明記 |
| performance | `mvn -q test -Pperformance-tests` → 1 run / 0 failure / 0 error / 0 skipped、p95=44ms | PASS |
| feature regression | 資格lifecycle、expiry、PII、Document/FileScope、Expense/Approval、skill-gap、AI、A1/A2/B1/B2 API/service/UI contractの選択suiteは全report 0 failure/error/skip | PASS |
| H2/migration | 28 + 188 = 216件 | PASS |
| post-fix MySQL feature smoke | 6件 | PASS |

### Feature regression classes

`CertificationLearningGapApiControllerTest`、`CertificationEvidenceValidatorTest`、`CertificationExpiryNotificationSchedulerTest`、`CertificationExpiryServiceTest`、`CertificationNotificationPopulationResolverTest`、`CertificationNumberCryptoServiceTest`、`EngineerCertificationLifecycleServiceTest`、`EngineerCertificationServiceTest`、`CertificationEvidenceAccessServiceTest`、`CertificationLearningGapAiServiceImplTest`、`CertificationLearningGapQueryServiceImplTest`、`CertificationLearningGapSelfServiceImplTest`、`CertificationLearningGapTrainingApprovalServiceTest`、`SkillGapServiceImplTest`、`SkillGapTaxonomyResolverTest`、`FileScopeValidationServiceTest`、`AiLearningCandidateServiceImplTest`、`TrainingPlanServiceTest`、`CertificationLearningGapUiContractTest`、`MyCertificationLearningGapUiContractTest`等を実行し、全件PASS。

## Browser Demo evidence

一時Docker network/container上で現在branchをbuildして実施し、終了後にapp/mysql/networkを停止・削除した。既存の8080/8081プロセス・containerは変更していない。

| scenario | observation |
|---|---|
| desktop management list | `/certification-learning-skill-gap`をadminで表示。資格・学習・スキルギャップ画面、filters、10 rows、total 255件を確認 |
| detail | first row detail modalを開き、資格/training/gap empty stateを確認 |
| empty/filter | 存在しない要員で`対象データがありません`、対象0件。阿部検索で2件 |
| 390px | 390x844でfiltersが2列にreflowし、tableは横方向アクセス可能 |
| export | CSV操作でbrowser download navigation（`net::ERR_ABORTED`）を確認。Browser内でbinary file本文は採取していない |
| AI fallback | project ID=1でAI候補を起動。`historical_data_unavailable`、as-of `2026-08-28`、`AI停止または履歴不足`、candidate-only説明を確認 |

## Acceptance coverage

| acceptance | evidence |
|---|---|
| 期限90/60/30当日・前後、Tokyo timezone | `CertificationExpiryServiceTest`、scheduler/population tests、`AppConfigClockTest` |
| cancel/correct/renew、duplicate | `EngineerCertificationLifecycleServiceTest`、`EngineerCertificationServiceTest` |
| evidence typed scope、version/hash/CLEAN、legal hold、empty/mixed/ENGINEER-only/admin | `CertificationEvidenceValidatorTest`、`CertificationEvidenceAccessServiceTest`、`FileScopeValidationServiceTest` |
| as-of、DELETE当日、開始日前、source precedence、期間両端、synonym/unknown/0件、snapshot replay | `SkillGapServiceImplTest`、`SkillGapTaxonomyResolverTest`、skill/project/position write-path回帰、schema smoke |
| threshold−1/threshold/threshold＋1、NULL/0、予定額/実費差額、自己承認拒否、CAS、締め済み月 | `TrainingPlanServiceTest`、`ExpenseRequestFlowIntegrationTest`、training approval回帰 |
| AI stop/error/timeout、rule gap維持、AI-only finalization拒否、SELF/MANAGER非公式、HR_FINAL公式projection | `CertificationLearningGapAiServiceImplTest`、`AiLearningCandidateServiceImplTest`、`SkillAssessmentServiceImplTest` |
| list/detail/count/export/self/manager/HR population一致 | A1/A2 API/service/UI contracts、permission回帰、Browser list/detail/empty |

## Residual unverified items

- Windows loopback failureの解消後にfast/MySQL全体を再実行すること。現時点でfeature対象reportは全件PASS。
- 証憑binary本文のdownload file採取は未取得。typed authorizationとexact pin/CLEAN/legal holdは検証済み。
- NF-07の`CERTIFICATION_PII` production retention/disposalは対象外。承認なしに本番有効化しない。
- 外部AI provider接続はscope外。内部timeout/error/fallbackとcandidate-only境界は検証済み。

## Rollback

- 本番未適用を前提に、feature commitを最終HEADから逆順revertする。
- migrationはV127→V116を管理手順で逆順に戻す。適用済みproductionではDROPせず、backupとrelease rollback計画を使う。
- ReviewのPLAN/IMPLEMENTATION双方PASS前はPRを作成しない。
