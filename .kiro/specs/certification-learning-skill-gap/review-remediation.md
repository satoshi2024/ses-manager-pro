# PLAN Review指摘対応表

> 現行判定: Plan Review R7はPASSを維持。独立Implementation Review（Head `0e3d9b69`）はP1-M-01/P1-M-02/P1-A2-01でFAIL。下記の旧M完了記録はsupersededであり、A1/A2 remediation後の独立再Reviewを待つ。

## 判定の前提

直近 Review（独立 Plan Review、Head `4e171f19`）: 総合 **FAIL**（Implementation 未着手）/ Plan **PASS** / Implementation **NOT STARTED**。F1 許可（開工対話）。

## R1 Review（Task 0R）指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | `CANDIDATE`維持。Gate は**責任主体を一意に識別できる OwnerRef**（開発段階 `PROJECT_OWNER`）と approved scope・DG-03 実値・Base SHA・承認 commit の記録を要求。個人の実名は記録しない | `owner-policy.md`、`README.md`、`plan.md` §変更許可ゲート | **P1-01a（OwnerRef）:** VERIFIED_CLOSED。**P1-01b（scope/Base/DG-03実値/`APPROVED`）:** OPEN |
| NF03-PLAN-P1-02 | supplyは`t_engineer_skill_event`/`t_project_skill_event`、demandは`t_project_position_event`を追加候補とし、current projectionを過去へ遡及適用しない。PROJECT/POSITION/COMBINED precedence、履歴欠落、monthly snapshotを定義 | `inventory.md` §5.1、`design.md` §3.4/§4.4、F1-4/F2-3 | Ownerがevent/snapshot migration scopeとbackfill開始日を承認 |
| NF03-PLAN-P1-03 | `CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` typed linkだけを認可根拠とし、generic `ENGINEER` linkを作らない。mixed-link時はrestricted priority、eventのexact version/hash、CLEAN、FileScopeValidationServiceを必須化 | `inventory.md` §5.2、`design.md` §3.6/§4.2、F1-2/B1 | legal-document側の正式enum・resolver契約を承認し、実装・E2Eで証明 |
| NF03-PLAN-P1-04 | plan planned costは申請snapshot、actual cost/payment/accountingは既存`t_expense_request`/outboxの正本。enrollmentはrelationだけを持つ。NULL/0、税込、差額再承認、締め済み月、支払所有者を定義 | `inventory.md` §2/§5.3、`requirements.md` R2、`design.md` §3.7/§4.5、F1-3/F2-2 | `m_approval_route.min_amount`、zero-cost、tolerance、reopen権限をOwner/Financeが承認 |
| NF03-PLAN-P1-05 | `CORRECTED`をcurrent statusから除外。訂正はrevision/event、EXPIREDはas-of導出、renewはcontinuity groupの新record、current_flag unique、expiry rule version snapshot、row lock＋CASを定義 | `requirements.md` R1、`design.md` §3.5/§5.3、F1-1/F2-1 | state/unique/CASをMySQL並行testで実装証明 |
| NF03-PLAN-P1-06 | semantic keyをrecord revisionではなくrecord＋effective expiry＋threshold＋recipientで構成。注入Clock、lifecycle/active account母集団、退職/休職/復職、manager変更、DB unique/outbox claimを定義 | `requirements.md` R4/R6、`design.md` §3.8/§4.4/§5.1、F2-4 | tenant timezone、復職通知、複数JVM結果をOwner承認後に実測 |
| NF03-PLAN-P1-07 | SELF/MANAGER/HR_FINALを`t_engineer_skill_assessment`で分離し、`t_learning_decision_event`でsource、human actor、reason、snapshot、adverse-useを監査。AI acceptはlearning suggestionだけ | `requirements.md` R7、`design.md` §3.9/§4.6、F1-5/F2-5 | 異議申立て、HR/legal workflow、利用禁止範囲をOwner/HRが承認 |
| NF03-PLAN-P2-01 | 未定義のR8参照を削除し、AI・人の確定境界を正式なR7として追加。tasks/matrixをR3/R7へ同期 | `requirements.md` R7、`tasks.md`、`completion-matrix.md` | re-reviewでID整合を再確認 |
| NF03-PLAN-P2-02 | `issuer_key`、`external_code_key`、`name_key`、NULL codeでも非NULLの`identity_key`、alias、merge reviewを定義。同じskill masterへ資格を登録しない | `design.md` §2/§3.5、`inventory.md` 新設候補 | issuer/name normalizationとmerge権限をOwner承認 |

## R2 Review（Task 0R-2）指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | **OPEN**（OwnerRef ポリシーは確定、scope/Base/DG-03 実値は未承認） | 中央台帳 NF-03 `CANDIDATE` | approved scope・Base・DG-03 実値 → `APPROVED` |
| NF03-PLAN-P1-02〜07 | **SPEC_ADDRESSED / 未承認**（R1と同内容。R2で再確認） | 各§参照 | Owner承認＋実装証明 |
| NF03-PLAN-P2-01 | **VERIFIED_CLOSED（spec）** | R7正規化、R8残留なし | — |
| NF03-PLAN-R2-P1-08 | 既存`replaceSkills`/position更新をF1-4/F2-3の必須変更対象にファイル名付きで追加。物理delete→insertとeventの同一txを明記 | `inventory.md` §5.4、`design.md` §3.4、`tasks.md` F1-4/F2-3 | engineer-skill-career/staffing共有境界のOwner承認、実装test |
| NF03-PLAN-R2-P1-09 | `FileScopeValidationService`へ`CERTIFICATION_EVIDENCE`専用分岐（`document-archive`より前）。empty-link・admin bypass・ENGINEER-only mixed link拒否をF1-2/B1 testに列挙 | `inventory.md` §5.5、`design.md` §3.6/§4.2、`tasks.md` F1-2/B1 | enum承認、E2E否定系 |
| NF03-PLAN-R2-P1-10 | 経費締めを`ExpenseRequestServiceImpl`共有化（選択肢A推奨）または研修wrapper（選択肢B）としてdesign §3.7に明記。F2-2 testに締め済み月拒否を固定 | `design.md` §3.7/§4.5、`tasks.md` F2-2 | Owner/FinanceがA/BをDG-03で選択 |
| NF03-PLAN-R2-P2-03 | READMEをTask 0+0R+0R-2完了に更新。migrationは着手時latest+1再確認。inventory §5.1のPROJECT正本をevent表記へ統一 | `README.md`、`inventory.md` §5.1 | F1着手時の実採番 |
| NF03-PLAN-R2-P2-04 | Clock正本を`TenantClock`候補＋Asia/Tokyoへ固定。`AppConfig.systemDefaultZone`非依存をdesign §3.8に明記 | `design.md` §3.8、`tasks.md` F2-4 | tenant TZ設定のOwner承認 |
| NF03-PLAN-R2-P2-05 | `CertificationNotificationPopulationResolver`候補。NF-01 lifecycle case優先、通知除外と履歴閲覧を分離 | `design.md` §3.8 | lifecycle状態式のOwner承認 |
| NF03-PLAN-R2-P2-06 | SELF/MANAGERをstaffing/sales/exportへ出さない。HR_FINALのみ公式projection。decision table §4.6に追加 | `design.md` §3.9/§4.6、`tasks.md` F2-5/A1 | 異議申立てはOwner/HR |

## R3 Review（Task 0R-3）指摘との対応

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| NF03-PLAN-P1-01 | **OPEN**（OwnerRef 確定、実値未承認） | 中央台帳 `CANDIDATE` | approved scope・Base・DG-03 実値 |
| NF03-PLAN-R2-P2-03 | **CLOSED（spec）** | README/completion-matrix/plan Gate 0を0R-3まで反映。design §3.4手順3を`t_project_skill_event`/`t_project_position_event`表記へ統一 | — |
| NF03-PLAN-R3-P2-08 | `PositionServiceImpl.delete`をinventory §5.4・F1-4/F2-3フック表に追加。delete前close/cancelled event、as-ofはcurrent補完禁止 | `inventory.md` §5.4、`tasks.md` F1-4/F2-3、`plan.md` | 実装test（F1以降） |
| NF03-PLAN-R2-P2-06（残） | F2-5/A1 testにSELFがstaffing/salesに出ないことを明記 | `tasks.md` F2-5/A1 | 実装test |

## R4 Owner ポリシー（Task 0R-4）

| finding | 補正内容 | 参照 | 残るgate |
|---|---|---|---|
| 開発段階 Owner 表現 | OwnerRef=`PROJECT_OWNER`、実名非記録、承認証跡フィールドを `owner-policy.md` に確定。Gate 文言を OwnerRef へ統一 | `owner-policy.md`、README、plan、completion-matrix、中央 traceability DG-03 | **NF-03 は `CANDIDATE` 維持**。approved scope・Base・DG-03 実値承認後に `APPROVED` |

## R5 Review（Head `34f20724`）— P1-01 分割判定

| P1-01 部分 | Status | 証跡 |
|---|---|---|
| P1-01a 責任主体識別（OwnerRef） | **VERIFIED_CLOSED** | OwnerRef=`PROJECT_OWNER`、OwnerType=`ROLE`、DecisionId=`DG-03-DEV-20260828`、決定日=2026-08-28、承認 commit=`34f20724`。`owner-policy.md`、中央台帳、traceability DG-03 と一致。実名の追記なし |
| P1-01b approved scope / 承認 Base SHA / DG-03 業務実値 / `APPROVED` | **OPEN** | Status=`CANDIDATE`。scope・DG-03（6項目＋経費締め A/B）未確定。技術比較 base `455fc92e` のみで承認 Base SHA 未記録 |

**Review 評価ルール（開発段階）:** 個人の実名は要求せず、欠如を PLAN FAIL 理由にしない。責任主体は OwnerRef、承認証跡は DecisionId・決定日・OwnerRef・対象 scope・Base SHA・承認 commit。

`DG-03-DEV-20260828` は Owner 識別ポリシーの Decision。業務 Decision は `DG-03-SCOPE-APPROVAL-20260828-01`（approval-decision.md）。

## R6 実装側自己判定（Gate 0 / Task 0G）— 参考

**Reviewed Head:** `03545127`（Gate 0 承認 commit）

実装対話での自己判定。**独立 Review は本節を採用せず**、下記 R7（Head `4e171f19`）を正とする。

## R7 独立 Plan Review（Head `4e171f19`）— PLAN PASS

**Reviewed Head:** `4e171f196e861a3fd849db3aa9f98c1981a6d747`（remote と一致）

**Verdict:** 総合 Review **FAIL**（Implementation 未着手）/ Plan **PASS** / Implementation **NOT STARTED** / F1 **許可**（開工対話で。本 Review 対話では実装しない）

**検証:** merge parents `2abd4efc` + `76e45340`、merge-base `76e45340`、`git diff --check origin/main...HEAD` = 0、`gh pr list` 空。

### P1-01（閉鎖）

| 部分 | Status |
|---|---|
| P1-01a OwnerRef | VERIFIED_CLOSED |
| P1-01b scope/Base/DG-03/`APPROVED` | VERIFIED_CLOSED |

### P1-02〜P1-10 / P2

**APPROVED（spec）** または **VERIFIED_CLOSED（spec）**。REGRESSED なし。実装証明は F1〜M。

### 残リスク（PLAN FAIL ではない）

- `CERTIFICATION_PII` production 保持は NF-07。開発継続可、本番有効化は停止。
- DG-03-1 は AES-256-GCM **または** token — **F1-1 で列形を一つに固定**すること。
- FileScope empty-link/admin bypass は現行コードに残存。F1-2 で `CERTIFICATION_EVIDENCE` 専用分岐を `document-archive` より前に実装（契約維持）。
- merge後のlatest Flywayはmain V115（PWA）＋NF-03 V116〜V125。NF-03は **V116+**。

### Next wave

- **F1 開始: YES**（開工対話）
- **PR: NOT CREATED**（M + Implementation Review PASS まで）

## 再Reviewの開始条件（Implementation）

1. F1〜M の task が completion-matrix に evidence 付きで `[x]` 記録される。
2. mandatory test / Demo / CI gate が実行され、結果が review-packet に記録される。
3. その後 Implementation Review を開始する。PASS 後にのみ PR 作成。

## 現時点の証拠境界

- 確認済み: 独立 Plan Review PASS（Head `4e171f19`）、Gate 0 Decision、traceability `APPROVED`、Base merge、spec 静的整合、`git diff --check`。
- 未確認: NF-03 production implementation、Maven/MySQL、scheduler E2E、Document download E2E、browser Demo。
- Implementation Review は F1〜M 完了後に開始する。

## F1 Implementation Review受領・F2持越し（2026-08-28）

独立ReviewのF1 Implementation **PASS**を正式に受領した。Plan Review R7も**PASS**であり、F2着手が許可された。mainのV115（PWA）を保持してNF-03をV116〜V125へ順延した。F1本体Headは`2f7bbac0`、F2完了時点の実装Headは`f2ce7a99`、現行migrationはV125（F2はV121〜V125）である。A1/A2/B1/B2/M、PR、merge、branch削除は引き続き禁止する。

### 持越し項目のF2接続

| 持越し | F2契約 | 対応箇所 | 状態 |
|---|---|---|---|
| `TYPE_DELETE`／DELETE当日as-of | delete前cancel/close event、DELETE当日をeffective intervalに含め、削除後current fallbackを禁止 | `completion-matrix.md`、`SkillGapServiceImplTest`、H2/MySQL schema smoke | VERIFIED_CLOSED |
| feature開始日前position update | history欠落時`historical_data_unavailable`、現行positionの過去補完禁止 | `completion-matrix.md`、`SkillGapServiceImplTest`、MySQL Flyway smoke | VERIFIED_CLOSED |
| legal hold | certification evidenceのdownload/export/disposalをhold中fail closed、DocumentService/FileScope双方で再検証 | `completion-matrix.md`、`CertificationEvidenceAccessServiceTest`、`FileScopeValidationServiceTest` | VERIFIED_CLOSED |
| 証憑version pin | event記録のdocument version ID/hashと要求版を完全一致、CLEAN必須 | `completion-matrix.md`、`CertificationEvidenceValidatorTest`、`CertificationEvidenceAccessServiceTest` | VERIFIED_CLOSED |
| production `certification.pii.view` permission seed | production seed、未seed時full reveal fail closed、role別実API確認 | `completion-matrix.md`、V121/V126 MySQL smoke、A1 permission/API regression | VERIFIED_CLOSED |
| BP/別write pathのevent insert迂回防止 | skill/project/positionの全write pathを共通event writerへ集約し、直接mapper更新を検出 | `completion-matrix.md`、skill/project/position service回帰、`AllMappersSchemaSweepTest` | VERIFIED_CLOSED |
| PR前最新`origin/main`取り込み・migration衝突 | `origin/main@a3454c08`をA1前にmergeし、main V115（PWA）を保持してNF-03 V116〜V128へ順延。M開始・終了時に再fetchして追加migration/schema/H2衝突を再確認 | `completion-matrix.md`、M/PR前gate | VERIFIED_CLOSED |

この表の項目は未追跡のまま落とさず、各F2 TaskまたはMの明示的gateで証拠を付けた。main V115＋NF-03 V116〜V128の構成、最新`origin/main`とのmerge-base、migration/H2/MySQL smokeを確認済みである。旧Mの完了宣言は独立Implementation Review FAILによりsupersededであり、現行statusはA1/A2 remediation後の独立再Review待ちである。

## A1/A2 implementation receipt（2026-08-28）

F2 Implementation PASS（P0/P1=0）を受領後、A1→A2の連続実装を行った。A1は`f219905f`、A2は`5a5d8571`として個別にremoteへpush済みで、現行NF-03 migrationはV127である。A1はmanager org∩DataScope、HR/admin、lifecycle、PII mask/export omit、list/detail/count/export共通母集団、SELF/MANAGER非混入を実装した。A2はaccount link本人ID強制、他人record拒否、typed `CERTIFICATION_RECORD`証憑、CLEAN版metadata、plan/enrollment状態操作、本人exportを実装した。

| wave | evidence | result |
|---|---|---|
| A1 | `CertificationLearningGapQueryServiceImplTest`、`CertificationLearningGapApiControllerTest`、`CertificationLearningGapUiContractTest`、`ComplianceGateMenuPermissionTest`、`ActionPermissionResolverTest` | targeted 22 tests PASS。実BrowserはMで確認 |
| A2 | `CertificationLearningGapSelfServiceImplTest`、`MyCertificationLearningGapUiContractTest`、`MigrationScriptIntegrityTest`ほか | targeted suite PASS。approval待ち・download E2E・実BrowserはB1/Mで確認 |

A2の本人APIは`engineerId`をbody/pathからscope根拠として使わず、`EngineerAccountLinkService`の現在linkだけを正本にする。証憑はDocumentService登録時に`CERTIFICATION_RECORD` targetを付け、返却値からstorage keyとraw資格番号を除外する。A1/A2ともMでdesktop/390px Browser Demoと全受入条件の横断回帰を行う。

### B1 receipt

`151346ed`でB1を実装しremoteへpushした。training approvalはA1と同一のvisible populationを先に確認して既存`TrainingPlanService`へ委譲し、費用・承認・支払の正本を増やしていない。証憑downloadは管理側と本人側の両APIでtyped `CERTIFICATION_RECORD` link、指定version、CLEAN、version ID/hash、legal hold、FileScopeをfail closedで検証する。`CertificationEvidenceAccessServiceTest`と`CertificationLearningGapTrainingApprovalServiceTest`、F2の通知/FileScope/費用回帰がPASSし、empty-link、ENGINEER-only、mixed-link、admin bypass、版/hash不一致を回帰した。UIのdownload/approval操作と実BrowserはMで確認済み。

### B2 receipt

`0168e8ea`でB2を実装しremoteへpushした。A1と同じquery serviceでmanager/HR/adminのvisible populationを確認し、`SkillGapService`のevent-only as-of結果を先に確定する。AIにはgap skillに紐づくactive course allowlistだけを渡し、RULE_ONLY/DEGRADEDでもrule gap/snapshot/as-ofを維持する。AI応答に配置・評価・採否の確定値を持たせず、UIにもcandidate-only境界を表示した。B2 targeted suiteとMの実Browser・全gateはPASSまたは既知環境制約を記録済み。

## M完了・最終handoff（2026-08-28）

MはA1〜B2の実装完了後、同一code HEAD `4ba1738c4e5afe6ad3839afe1e681a9621326846`を対象に完了した。最終docs pin commitを含むremote HEADはReview packetと`git rev-parse HEAD`の照合値を正本とする。

| 項目 | 証拠・結果 |
|---|---|
| Approved scope / Owner / Base | NF-03 `APPROVED`、OwnerRef=`PROJECT_OWNER`、DecisionId=`DG-03-SCOPE-APPROVAL-20260828-01`、Base=`76e45340a23cfee964fac778b7b4d856fa2c9e7b` |
| worktree / branch | `C:\work\ses-certification-learning-skill-gap` / `codex/certification-learning-skill-gap`。通常checkoutは変更なし。最終fetch時にworktree clean、local/remote HEAD一致。 |
| main取り込み | `origin/main=a3454c086c6d17f94f96ced4175adec932f071b7`、merge-baseも同SHA。main V115を保持し、NF-03はV116〜V128へ順延。migration integrityとMySQL/H2 smoke PASS。 |
| fast | `mvn -q test`: 3051 run / 2 failures / 11 errors / 0 skipped。既存baselineおよびWindows loopback失敗のみ。NF-03対象reportはfailure/error 0。 |
| MySQL | 旧M時点の履歴値とremediation後の再実行はいずれも89 run / 0 failure / 1 error / 0 skipped。唯一のerrorは既存`FreeeConcurrentRefreshTest.<clinit>`のWindows loopback。 |
| performance | remediation後`mvn -q test -Pperformance-tests`: 1 run / 0 failure / 0 error / 0 skipped、p95=74ms。 |
| H2/migration | `MigrationScriptIntegrityTest` 28件、`AllMappersSchemaSweepTest` 188件、合計216件PASS。修正後MySQL feature smoke 6件PASS。 |
| feature regression | 資格lifecycle/PII/通知、DocumentLink/FileScope、training/Expense/Approval、skill-gap taxonomy/as-of、AI candidate、A1/A2/B1/B2 service/API/UI contractを全件PASS。 |
| Browser Demo | Docker appでdesktop/390px。管理list 255件、detail、empty 0件、検索、CSV download遷移、AI fallback（`historical_data_unavailable`、candidateなし、candidate-only説明）を確認。 |
| Review/PR | 独立Implementation Review待ち。PR、merge、branch削除は実施していない。 |

### 必須シナリオの証拠対応

| シナリオ | 証拠 |
|---|---|
| 90/60/30当日・前後、Asia/Tokyo、cancel/correct/renew、重複 | `CertificationExpiryServiceTest`、`EngineerCertificationLifecycleServiceTest`、`CertificationExpiryNotificationSchedulerTest`、`AppConfigClockTest`、`EngineerCertificationServiceTest` |
| 証憑scope/version/hash/CLEAN/legal hold、empty/ENGINEER-only/mixed/admin | `CertificationEvidenceValidatorTest`、`CertificationEvidenceAccessServiceTest`、`FileScopeValidationServiceTest`、B1 download API |
| 費用NULL/0/threshold±1、自己承認拒否、CAS、予定/実費差額、締め済み月 | `TrainingPlanServiceTest`、`ExpenseRequestFlowIntegrationTest`、`CertificationLearningGapTrainingApprovalServiceTest`。既存ExpenseRequest/MonthlyClosing正本へ委譲 |
| as-of、DELETE当日、開始日前、PROJECT/POSITION/COMBINED、期間両端、同義/未知/0件、snapshot replay | `SkillGapServiceImplTest`、`SkillGapTaxonomyResolverTest`、skill/project/position service回帰、Flyway schema smoke。未知skillの自動master化なし |
| AI停止/error/timeout、rule gap維持、candidate-only、公式projection境界 | `CertificationLearningGapAiServiceImplTest`、`AiLearningCandidateServiceImplTest`、`SkillAssessmentServiceImplTest`。AI-only finalizationを拒否し、HR_FINALのみ公式projectionへ出さない |
| list/detail/count/export/self/manager/HR population | `CertificationLearningGapQueryServiceImplTest`、`CertificationLearningGapApiControllerTest`、`CertificationLearningGapSelfServiceImplTest`、UI contract、permission回帰、Browser list/detail/empty |

### 残余リスクとrollback

- Windows loopback環境の既存テスト失敗は本機能のfailureではないが、CI/Linuxまたはloopback修復環境で再実行する。
- 証憑binary本文のdownloadファイル採取は未取得。認可と版固定のfail-closed証拠は取得済み。
- NF-07の`CERTIFICATION_PII`保持年数・破棄は未承認であり、本番有効化しない。
- rollbackは本番未適用を前提に、feature commitを`4ba1738c`からDocsを含む最終HEADまで逆順revertし、migrationはV127→V116を管理手順で戻す。本番適用後はDROPせずbackup/release rollback計画を使用する。

## 独立Implementation Review（Head `0e3d9b69`）— FAIL受領と再実装（2026-08-28）

独立ReviewはPlan **PASS**、Implementation **FAIL**と判定した。P1は次の3件であり、PR・merge・branch削除は実施されていない。

| finding | 指摘 | remediation | status |
|---|---|---|---|
| P1-M-01 | 資格master登録と証憑verify/rejectのproduction HTTP/UI経路がない | `17d944f1`で`CertificationMasterService`のCRUD/deactivate、`EngineerCertificationService`のverify/rejectを管理APIへ接続し、master/course管理画面とdetailのverify/reject操作を追加 | CLOSED pending independent re-review |
| P1-M-02 | training course masterの登録・更新・skill target管理のHTTP/UIがない | `17d944f1`で`TrainingCourseMasterService`、canonical skill検証・relation置換、管理API/UIを追加 | CLOSED pending independent re-review |
| P1-A2-01 | 本人画面に証憑upload、withdraw/resubmit、plan submit/enrollがない | `50cb8f2d`でcatalog select、multipart upload、detail status action、cancel/resubmit、plan submit/withdraw/resubmit、enrollment操作を接続 | CLOSED pending independent re-review |

`66eda6f9`は既存`ExpenseRequestService`の`研修費`カテゴリをV128で許可し、学習planの費用正本を新設しない互換修正。`f8a5b125`はMySQL smoke検証を追加し、`8c461dbc`で`SHOW CREATE TABLE`を用いる正本DDL確認へ修正した。

### 再実装の検証

- clean後のA1/A2・F1/F2選択suiteはexit 0。`FlywayCertificationLearningGapSchemaSmokeTest`はV128を含め1件PASS。
- Docker Browserでadminが資格masterとcourse（canonical AWS target）を登録し、本人が資格master select、DRAFT申請、証憑upload（1件）、cancel/resubmit、0円plan submit（`APPROVED`）、course enrollment（`PLANNED`）を実行した。
- admin detailでverifyを実行し、資格が`ACTIVE`、証憑リンク、0円plan/enrollmentが表示されることを確認した。
- 非0円planは既存approval route fixture未seedのため、`研修費` INSERT後にroute未設定の400となった。V128により以前のDB CHECK違反500は解消しており、未seed routeは環境制約として再Reviewへ明記する。
- P2のtraining approvalボタン、資格master/course UI、detail title二重escapeも同じremediationに含めた。

### 現時点の未検証と次gate

remediation後のfast/performance/MySQLは再実行済み（fast 3060 run / 2 failures / 16 errors / 0 skipped、performance 1 run / 0 failure / 0 error / 0 skipped、p95=74ms、MySQL 89 run / 0 failure / 1 error / 0 skipped）。MySQLの唯一のerrorは既存`FreeeConcurrentRefreshTest.<clinit>`のWindows loopbackであり、NF-03 feature/migration reportはfailure/error/skipなし。証憑binary本文のBrowser採取と独立Implementation再Reviewは未実施であり、再Reviewでは今回のcommit以降のHTTP/UI write path、V128、既存F1/F2回帰、role/population/PII/DocumentLink境界を同一clean Headで再確認する。PASS前はPRを作成しない。

## 再実装後の最終handoff（2026-08-28）

| 項目 | 値 |
|---|---|
| final local/remote Head | `97443937d95fd551b9a06ea33ebdb77127320bc1`（一致） |
| approved Base / merge-base | `76e45340a23cfee964fac778b7b4d856fa2c9e7b` / `a3454c086c6d17f94f96ced4175adec932f071b7` |
| migration | main V115を保持、NF-03 V116〜V128。V128は既存ExpenseRequestの`研修費`CHECKを許可 |
| test gates | fast 3060 run / 2 failures / 16 errors / 0 skipped、MySQL 89 run / 0 failure / 1 error / 0 skipped、performance 1 run / 0 failure / 0 error / 0 skipped（p95=74ms） |
| MySQL known error | 既存`FreeeConcurrentRefreshTest.<clinit>`のWindows loopback。NF-03 feature/migration reportはfailure/error/skipなし |
| Browser | admin master/course登録、本人catalog・証憑upload・cancel/resubmit、0円plan APPROVED、enrollment PLANNED、admin verify後ACTIVEを確認 |
| remaining | 独立Implementation再Review、証憑binary本文採取。PR/merge/branch削除は未実施 |
