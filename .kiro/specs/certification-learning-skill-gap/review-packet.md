# NF-03 Review packet（A1/A2 remediation handoff）

> 旧M完了宣言は独立Implementation Review（Head `0e3d9b69`）のP1-M-01/P1-M-02/P1-A2-01によりsuperseded。Plan Review R7はPASSを維持し、下記は修正後の独立再Review handoffである。

## Handoff status

| 項目 | 値 |
|---|---|
| Feature | `certification-learning-skill-gap` |
| Status | A1/A2 remediation完了、独立Implementation Review再Review待ち |
| Approved scope | NF-03 `APPROVED`。資格/期限/証憑、training、既存ExpenseRequest研修費、90/60/30通知、event-based as-of skill gap、rule gap＋AI course/skill候補、本人/manager/HR workflow |
| Out of scope | 外部LMS自動連携、AI自動評価/配置/採否・昇格・給与・不利益判断、NF-07保持年数の本番有効化 |
| Owner | `PROJECT_OWNER` |
| Decision | `DG-03-SCOPE-APPROVAL-20260828-01` / `DG-03-DEV-20260828` |
| Approved Base | `origin/main@76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| Worktree | `C:\work\ses-certification-learning-skill-gap` |
| Branch | `codex/certification-learning-skill-gap` |
| Normal checkout | `C:\work\ses-manager-pro`（変更なし） |
| Final local/remote HEAD | `ac99c73ba39ada1656ed8420ba18187c3f0651ad`（local/remote一致） |
| Latest origin/main | `a3454c086c6d17f94f96ced4175adec932f071b7` |
| merge-base | `a3454c086c6d17f94f96ced4175adec932f071b7` |
| PR / merge / branch delete | すべて未実施。独立再ReviewのPLAN/IMPLEMENTATION双方PASS後のみPR対象 |

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
| A1 remediation | `17d944f1` | 資格master/course master CRUD、資格verify/rejectの管理HTTP/UI、training approval操作 |
| A2 remediation | `50cb8f2d` | 本人catalog、証憑upload、status、withdraw/resubmit、plan submit/enroll UI |
| Expense compatibility | `66eda6f9` | V128で既存ExpenseRequestの`研修費`科目を許可 |
| MySQL smoke assertion | `f8a5b125`、`8c461dbc` | V128の正本DDLに対するMySQL smoke検証を固定し、`SHOW CREATE TABLE`検証へ修正 |
| remediation docs | `d2965f19` | 独立Review FAILとA1/A2修正範囲をtasks/matrix/remediation/packetへ記録 |
| remediation gate evidence | `80bfe417` | fast/performance再実行結果とMySQL再実行中の証拠をdocsへ同期 |
| remediation docs | このpacketを含むdocs commit | tasks/completion/remediation/Review packetを独立Review FAILと修正証拠に同期 |

変更ファイルはBaseからのdiffで再計算する。今回の追加はA1/A2のproduction HTTP/UI、V128 migration/H2/schema smoke、tests、spec/receipt docsであり、旧Mの完了宣言は独立Review FAILにより最終証拠として扱わない。

## Migration and schema

- `origin/main`の`V115__pwa_client_mutation_ledger.sql`は変更せず保持した。
- NF-03のmigrationは`V116`〜`V128`へ順延し、V115との重複を解消した。
- V116 certification master/record、V117 certification event/evidence type、V118 training、V119 skill-gap events、V120 assessment/decision、V121 lifecycle/PII permission、V122 expense relation/audit、V123 taxonomy alias、V124 AI artifact、V125 audit timestamps、V126 management menu/action、V127 self-service menu/action、V128既存ExpenseRequestの研修費科目許可。
- `MigrationScriptIntegrityTest` 28件、`AllMappersSchemaSweepTest` 188件、合計216件PASS。
- 修正後MySQL smokeは`FlywayCertificationLearningSkillGapSchemaSmokeTest`のV128検証1件PASS。V128追加前の3クラス6件実行では追加検証クエリの誤りによる1件FAILがあり、検証を`SHOW CREATE TABLE`へ修正して該当1件を再実行PASS（他5件は初回PASS）。

## Test gates

| gate | command / result | 判定 |
|---|---|---|
| fast（remediation後） | `mvn -q test` → 3060 run / 2 failures / 16 errors / 0 skipped。既存baseline・H2共有fixture・Windows loopback環境制約。NF-03対象report failure/error=0。 | feature regressionはPASS。全体は環境baseline付き |
| MySQL（remediation後） | `mvn -q test -Pmysql-tests` → Maven最終集計 `89 run / 0 failure / 1 error / 0 skipped`、exit 1。唯一のerrorは既存`FreeeConcurrentRefreshTest.<clinit>`のWindows loopback。NF-03 feature/migration reportはfailure/error/skipなし | feature evidenceはPASS、全体は既存環境error付き |
| performance（remediation後） | `mvn -q test -Pperformance-tests` → 1 run / 0 failure / 0 error / 0 skipped、p95=74ms | PASS |
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
| remediation management write | admin Browserで資格masterとcourse（AWS canonical skill）を登録し、管理detailから本人証憑のverifyを実行。再表示後に資格`ACTIVE`、証憑リンク、`APPROVED` plan、`PLANNED` enrollmentを確認 |
| remediation self-service | 本人Browserで資格catalog選択、DRAFT申請、証憑upload（1件）、cancel→resubmit、0円plan submit→`APPROVED`、course enrollment→`PLANNED`を確認。非0円planはapproval route未seedの400を確認 |

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
| A1/A2 write path | `17d944f1`、`50cb8f2d`、Browser remediation evidence。資格master/course HTTP/UI、verify/reject、本人upload/status/withdraw/resubmit/plan/enrollを実装済み |

## Residual unverified items

- fast/performance/MySQLはremediation後に再実行済み。fastは3060 run / 2 failures / 16 errors / 0 skipped、performanceは1 run / 0 failure / 0 error / 0 skipped（p95=74ms）、MySQLは89 run / 0 failure / 1 error / 0 skipped。既存baseline・環境制約の詳細を下記へ固定する。
- 証憑binary本文のdownload file採取は未取得。typed authorizationとexact pin/CLEAN/legal holdは検証済み。
- NF-07の`CERTIFICATION_PII` production retention/disposalは対象外。承認なしに本番有効化しない。
- 非0円training approvalのBrowser成功は未検証。既存approval route fixtureが未seedのため400であり、研修費INSERTのDB CHECK違反ではない。
- 外部AI provider接続はscope外。内部timeout/error/fallbackとcandidate-only境界は検証済み。

## Rollback

- 本番未適用を前提に、feature commitを最終HEADから逆順revertする。
- migrationはV128→V116を管理手順で逆順に戻す。V128は`chk_expense_category`を交通費・立替経費へ戻すdown相当を管理手順で扱い、適用済みproductionではDROPせず、backupとrelease rollback計画を使う。
- ReviewのPLAN/IMPLEMENTATION双方PASS前はPRを作成しない。

## 独立Implementation Review FAIL後の再実装handoff（2026-08-28）

独立ReviewのReviewed Headは`0e3d9b699773bb168b0221f6ef6fe8bdea8707be`、判定はPlan PASS / Implementation FAIL。P1-M-01/P1-M-02/P1-A2-01を`17d944f1`、`50cb8f2d`でHTTP/UIまで修正し、`66eda6f9`（V128）と`f8a5b125`（MySQL smoke assertion）を追加した。

| item | result |
|---|---|
| clean feature regression | `mvn -q clean test -Dtest=...`（A1/A2・F1/F2選択suite）exit 0 |
| MySQL/Flyway | V128 migration applied; `FlywayCertificationLearningGapSchemaSmokeTest` 1 test PASS。初回3-class/6-test runの追加assertion 1 failureは`SHOW CREATE TABLE`検証へ修正し、該当testを再実行PASS。 |
| Browser management | adminが資格master/courseを登録、本人証憑をdetailからverifyし、資格`ACTIVE`を確認 |
| Browser self-service | catalog select、DRAFT申請、証憑1件、cancel/resubmit、0円plan`APPROVED`、course enrollment`PLANNED`を確認 |
| known limitation | 非0円planはapproval route fixture未seedで400。V128により`研修費`のExpenseRequest INSERTは通過し、DB CHECK違反500ではない |
| remaining gate | 独立再Review、証憑binary本文採取 |

このpacketは旧Mの自己判定を再Review PASSとみなさず、上記remediationの独立再Reviewへ引き渡す。独立再ReviewのPLAN/IMPLEMENTATION双方PASS前はPRを作成しない。
