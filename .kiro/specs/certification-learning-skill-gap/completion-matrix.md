# 完了対応表（NF-03 approved）

## Status

| 項目 | 値 |
|---|---|
| traceability | `APPROVED` |
| Start Head | `c29001e3` |
| F1 Head | `2f7bbac0`（独立 F1 Implementation Review PASS） |
| F1 判定 | **PASS**（P1-F1-01/02 VERIFIED_CLOSED。P2-F1-01〜17 は F2 で扱う） |
| 承認 Base | `76e45340` |
| migration | main V115（PWA）＋NF-03 V116〜V128（V126 A1、V127 A2、V128 既存ExpenseRequestの研修費科目許可） |
| F2 | **完了**（F2-1〜F2-5実装・単体回帰済み。独立Review未実施） |
| A1/A2 remediation | **完了**（独立Review P1-M-01/P1-M-02/P1-A2-01をHTTP/UIまで修正。再Review待ち） |
| B1/B2/M | **旧実装・旧自己判定を再Review対象として保留**。旧MのPASS宣言は独立Review FAILによりsuperseded |

## F1 Task対応

| Task | commit | migration | 主要変更 | test | Demo | status |
|---|---|---|---|---|---|---|
| F1-1 | `ccaa77cc` | V116 | master/record、AES-256-GCM CNF1、crypto service | CryptoServiceTest、EngineerCertificationServiceTest、FlywayCertificationF11SchemaSmokeTest | DRAFT 申請＋暗号化保存 | [x] |
| F1-2 | `391f0907` | V117 | t_certification_event、CERTIFICATION_EVIDENCE FileScope | FileScopeValidationServiceTest（13件） | typed link/CLEAN/version/hash 否定系 | [x] |
| F1-3 | `5dd9d8c7` | V118 | course/plan/enrollment DDL+entity | MigrationScriptIntegrityTest | DDL shape（MySQL smoke は F1-5 統合） | [x] |
| F1-4 | `24577fd4`〜`3784a6a0` | V119 | skill/position events、service フック、`EffectiveIntervalSupport` | EngineerSkill/ProjectSkill/Position interval tests | replaceSkills で interval 閉鎖・supersedes | [x] |
| F1-5 | `e7d3b36d` | V120 | assessment/decision event DDL | FlywayCertificationLearningSkillGapSchemaSmokeTest | V116-V120 MySQL smoke | [x] |
| F2-1 | `ff8165ea` | V121 | 資格record lifecycle、event冪等、期限90/60/30、証憑pin/legal hold、PII permission seed | CertificationExpiryServiceTest、EngineerCertificationLifecycleServiceTest、CertificationEvidenceValidatorTest、FileScopeValidationServiceTest、AppConfigClockTest、EngineerCertificationServiceTest | 固定Clockで当日境界、訂正・取消・renew・CAS・証憑否定系を確認 | [x] |
| F2-2 | `d74ae5af` | V122 | training plan/enrollment lifecycle、既存ExpenseRequest/Approval正本、締めガード、plan event監査 | TrainingPlanServiceTest、ExpenseRequestFlowIntegrationTest、RouteResolverServiceTest | NULL/0/threshold±1、自己承認拒否、CAS、実費差額、締め済み月の経費正本更新拒否、承認後completionを確認 | [x] |
| F2-3 | `f5e69182` | V123 | event-only as-of supply/demand、taxonomy alias、unknown、PROJECT/POSITION/COMBINED precedence、snapshot replay、BP write-path集約 | SkillGapServiceImplTest、SkillGapTaxonomyResolverTest、EngineerSkillServiceImplTest、ProjectSkillServiceImplTest、FlywayCertificationLearningSkillGapSchemaSmokeTest | feature開始日前のunavailable、期間inclusive、DELETE当日/翌日、同義/未知、0件、replay、V123 MySQL schema、共通event writer経由を確認 | [x] |
| F2-4 | `8c8997cb` | —（既存V4/V79通知正本） | lifecycle-aware expiry scheduler、通知母集団、復職semantic key、multi-node unique/claim | CertificationNotificationPopulationResolverTest、CertificationExpiryNotificationSchedulerTest、NotificationServiceImplTest、NotificationOutboxServiceTest | 退職/休職/復職/account未link/manager変更、二重実行、DB/outbox DuplicateKey収束、Tokyo Clockを確認 | [x] |
| F2-5 | `78bbfcef`＋`9972dfc9` | V124〜V125 | SELF/MANAGER/HR_FINAL、公式projection境界、AI candidate-only、run/allowlist/期限、human accept/reject監査、training relation監査timestamp補正 | SkillAssessmentServiceImplTest、AiLearningCandidateServiceImplTest、MigrationScriptIntegrityTest、MessageBundleConsistencyTest、AllMappersSchemaSweepTest、FlywayCertificationLearningSkillGapSchemaSmokeTest | AI停止/error/timeoutでもrule gap維持、allowlist/run ID、期限内human accept/reject、期限切れ/AI-only拒否、公式skill/配置/採否非変更、MyBatis生成列とMySQL/H2 schema一致を確認 | [x] |
| A1 | `f219905f`＋`17d944f1` | V126 | HR/manager/adminの資格・training・gap list/detail/count/export、資格master CRUD、資格verify/reject、course master CRUD、org∩DataScope、lifecycle、PII mask/export omit、menu/action、390px/empty | CertificationLearningGapQueryServiceImplTest、CertificationLearningGapApiControllerTest、CertificationLearningGapWriteApiControllerTest、CertificationMasterServiceImplTest、TrainingCourseMasterServiceImplTest、CertificationLearningGapUiContractTest | 同一filterでlist/detail/count/exportのID一致、PII permission境界、SELF/MANAGER非混入、資格master/course登録、verify後ACTIVE、退職/休職、safePage、empty/390pxを確認 | [x] |
| A2 | `5a5d8571`＋`50cb8f2d`＋`66eda6f9` | V127＋V128 | account link本人scope、資格申請/cancel/correct/resubmit、typed CLEAN証憑upload、learning plan/enrollment、本人export、本人menu、既存ExpenseRequestの研修費科目 | CertificationLearningGapSelfServiceImplTest、MyCertificationLearningGapUiContractTest、TrainingPlanServiceTest、MigrationScriptIntegrityTest、ComplianceGateMenuPermissionTest | tampered engineerId拒否、他人record拒否、証憑target type/CLEAN、plan本人ID強制、0円plan、raw番号/storage key非返却、Browserのupload/cancel/resubmit/APPROVED/enrollmentを確認 | [x] |
| B1 | `151346ed` | 既存V121/V122/V126/V127の正本接続（追加DDLなし） | notification scheduler回帰、ExpenseRequest/Approval委譲、typed証憑download、version/hash/CLEAN/legal hold、A1 detail link | CertificationEvidenceAccessServiceTest、CertificationLearningGapTrainingApprovalServiceTest、CertificationExpiryNotificationSchedulerTest、FileScopeValidationServiceTest、TrainingPlanServiceTest、ExpenseRequestFlowIntegrationTest | semantic key/DB dedupe、threshold/NULL/0/差額/締め/自己承認、empty/ENGINEER-only/mixed/admin/version/hash/scan/legal holdを確認。実BrowserはMで確認 | [x] |
| B2 | `0168e8ea` | 追加DDLなし（既存V119/V123/V124/V126を再利用） | staffing as-of/period/source、rule gap snapshot、active course allowlist、AI candidate-only、UI接続 | CertificationLearningGapAiServiceImplTest、SkillGapServiceImplTest、SkillGapTaxonomyResolverTest、AiLearningCandidateServiceImplTest、MigrationScriptIntegrityTest | PROJECT/POSITION/COMBINED、inclusive期間、履歴欠落/unknown/synonym/0件、snapshot、AI停止/error/timeout、公式projection非変更を確認。BrowserはMで確認 | [x] |
| M | `4ba1738c`＋旧最終docs（superseded） | main V115保持、NF-03 V116〜V128。旧M判定は独立Review FAILで保留 | 旧全体gateは最終PASS証拠として扱わない。修正後clean選択suite、fast/performance全体、V128 MySQL smokeを実行。全MySQL集計は下記追補へ固定 | 管理master/course、本人upload/cancel/resubmit、0円plan/enrollment、verify後ACTIVEをBrowser確認 | [ ] 再Review PASS待ち |

## F1 Implementation Review受領・F2持越し契約

| 項目 | 受領値 |
|---|---|
| Plan Review | **PASS**（R7） |
| F1 Implementation Review | **PASS** |
| F1本体 Head | `2f7bbac0` |
| 旧A1〜B2実装完了時点のworktree local/remote Head | `86f1749813669964938611b2d9412282d2aa09fe`（旧Review対象） |
| V110 smoke期待値同期 | `4ba1738c4e5afe6ad3839afe1e681a9621326846`（旧V127同期。V128は`8c461dbc`で追加検証） |
| F2〜B2 | `完了`（旧実装。A1/A2のP1 remediationを追加済み、独立再Review未実施） |
| 現行migration | V128（main V115はPWA） |
| F2/A1/A2 migration | V121〜V128 |
| PR/merge/branch削除 | 禁止。独立再ReviewのImplementation PASS後のみPR対象 |

## Review持越し項目（F2契約へ接続）

| 持越し | 対応Task | 必須契約・証拠 | status |
|---|---|---|---|
| `TYPE_DELETE`／DELETE当日as-of | F2-3、M | delete前にcancel/close eventを記録し、DELETE当日をeffective intervalに含める。削除後のcurrent rowを過去補完に使わない。DELETE当日・前日・翌日のas-of testを残す | [x] `SkillGapServiceImplTest`、H2/MySQL schema smoke |
| feature開始日前のposition update | F2-3、M | feature開始日前に有効なposition historyがない場合は`historical_data_unavailable`。現行positionを過去へ遡及適用しない。開始日前update fixtureをMySQLで確認 | [x] `SkillGapServiceImplTest`、MySQL Flyway smoke |
| legal hold | F2-1、M | `CERTIFICATION_EVIDENCE`のdownload/export/disposalをlegal hold中はfail closed。DocumentServiceとFileScopeValidationServiceの両方でholdを再検証 | [x] `CertificationEvidenceAccessServiceTest`、`FileScopeValidationServiceTest` |
| 証憑version pin | F2-1、M | certification eventの`document_version_id`・hashと要求版を完全一致させ、CLEAN以外/version mismatch/hash mismatchを拒否 | [x] `CertificationEvidenceValidatorTest`、`CertificationEvidenceAccessServiceTest` |
| production `certification.pii.view` permission seed | F2-1、M | production migration/seedでpermission group/actionを登録し、role別full/masked/omitを実APIで確認。未seed時はfull revealをfail closed | [x] V121/V126 MySQL smoke、A1 permission/API regression |
| BP/別write pathのevent insert迂回防止 | F2-3、M | `EngineerSkillServiceImpl`、`ProjectSkillServiceImpl`、`PositionServiceImpl`の全create/update/status/delete経路を共通event writerへ集約し、直接mapper更新を回帰検出 | [x] skill/project/position service回帰、`AllMappersSchemaSweepTest` |
| PR前の最新`origin/main`取り込み・migration衝突再確認 | M／PR前gate | `origin/main@a3454c08`をmerge済み。main V115（PWA）を保持し、NF-03をV116〜V128へ順延した。M開始時に再fetchして追加衝突、schema/H2同期、Base..Head差分を再確認する | [x] M開始・終了時fetch、merge-base一致、migration integrity |

## Mで解消した旧未検証項目

- 資格 API/UI、90/60/30判定、cancel/correct/renew、証憑validator/download境界、training approval、as-of、AI fallback、list/detail/count/export/self APIは対象回帰testとBrowser Demoで確認した。
- migration/H2は`MigrationScriptIntegrityTest` 28件＋`AllMappersSchemaSweepTest` 188件、MySQLは修正後のfeature smoke 6件および全MySQL profileを実行した。各ゲートの最終集計は下記追補へ固定する。
- A1〜B2の画面接続はdesktop/390pxの実Browserでlist/detail/empty/CSV遷移/AI fallbackを確認し、role/population/PIIの残りはAPI/UI contractとservice回帰で確認した。

## 最終時点の残余未検証・環境制約

- Windowsのloopback・既存fixture制約により、plain `mvn -q test`の既存baseline失敗2件・error 16件、MySQL全体の環境/既存テスト結果は下記追補に記録する。NF-03対象reportには失敗/error/skipがない。Docker app経由のBrowser Demoは完了した。
- 証憑のbinary本文をBrowser downloadファイルとして採取するE2Eは未取得。typed link、exact version/hash、CLEAN、legal hold、ENGINEER-only/mixed/empty/admin境界はservice回帰とFileScope回帰で確認し、Browserではdownload遷移を確認した。
- `CERTIFICATION_PII`の本番保持年数・自動破棄は承認範囲外のNF-07であり、NF-07承認までは本番有効化しない。
- 実MySQL上のAI accept/rejectを含む人の確定操作は対象service/API回帰で確認したが、AI外部provider接続はscope外。AIはcandidate-only、HR_FINALのみ公式projectionという境界を維持している。

## Rollback

main V115は別featureのため変更せず、NF-03 migration V125〜V116を逆順で管理されたdown手順（未本番適用前提）とする。本番適用後はDROPせず、DB backupとリリースrollback計画に従う。コード/seedは各commitを逆順revertする。

## 独立Implementation Review FAIL後の再実装証拠（2026-08-28）

独立ReviewはHead `0e3d9b699773bb168b0221f6ef6fe8bdea8707be`に対してPlan PASS・Implementation FAILと判定し、P1-M-01（資格master登録・verify/reject HTTP欠落）、P1-M-02（training course master HTTP/UI欠落）、P1-A2-01（本人UIの証憑upload・status・withdraw/resubmit・plan submit/enroll欠落）を指摘した。旧Mの`[x]`および旧packetの完了宣言はsupersededとし、以下を再実装した。

| remediation | commit | evidence | status |
|---|---|---|---|
| A1-R 資格master/course master、資格verify/reject HTTP/UI | `17d944f1` | 管理API/UI、`CertificationLearningGapWriteApiControllerTest`、`CertificationMasterServiceImplTest`、`TrainingCourseMasterServiceImplTest`、UI contract、Browserでmaster/course登録とverify後`ACTIVE` | [x] 実装済み・独立再Review待ち |
| A2-R 本人catalog、証憑upload、status、withdraw/resubmit、plan/enroll UI | `50cb8f2d` | `MyCertificationLearningGapUiContractTest`、self-service回帰、Browserで証憑1件、cancel/resubmit、0円plan`APPROVED`、enrollment`PLANNED` | [x] 実装済み・独立再Review待ち |
| ExpenseRequest互換 | `66eda6f9`、`f8a5b125`、`8c461dbc` | V128、H2 schema、MySQL V128 smoke、正本DDLの`研修費`CHECK検証。`8c461dbc`で`SHOW CREATE TABLE`検証へ修正 | [x] |

今回のclean選択suiteはexit 0、V128適用後の`FlywayCertificationLearningSkillGapSchemaSmokeTest`は1件PASS。remediation後のfastは3060 run / 2 failures / 16 errors / 0 skipped、performanceは1 run / 0 failure / 0 error / 0 skipped（p95=74ms）、MySQLは89 run / 0 failure / 1 error / 0 skipped（唯一のerrorは既存`FreeeConcurrentRefreshTest.<clinit>`のWindows loopback）である。非0円Browser planは、既存approval route fixtureが未seedのため400（route未設定）まで確認し、DB 500ではないことを記録した。独立再Reviewは未実施である。

remediation後の全体ゲートは、fast `3060 run / 2 failures / 16 errors / 0 skipped`、performance `1 run / 0 failure / 0 error / 0 skipped（p95=74ms）`、MySQL `89 run / 0 failure / 1 error / 0 skipped`（exit 1）である。MySQL唯一のerrorは既存`FreeeConcurrentRefreshTest.<clinit>`のWindows loopbackで、NF-03 feature/migration reportはfailure/error/skipなし。いずれも独立再Reviewを代替せず、Task Mは再Review PASSまで未完了とする。

### 再実装後のrollback

未本番適用を前提に、`f8a5b125`、`66eda6f9`、`50cb8f2d`、`17d944f1`を逆順revertする。V128適用済み環境では直接DROPせず、研修費科目追加を含むrelease rollback手順・backupを使用する。旧V127以前へ戻す必要がある場合は、V128のdown相当（`chk_expense_category`を交通費・立替経費へ戻す）を管理migrationとして実施し、既存研修費データの扱いを先に決定する。
