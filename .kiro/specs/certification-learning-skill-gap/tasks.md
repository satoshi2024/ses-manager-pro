# Tasks（NF-03 approved）

> `[x]` は Objective、implementation evidence、required test、manual Demo をすべて満たした Task だけに付ける。F1 は **PLAN Review PASS**（Gate 0 Head）後に開始する。

## 0. 準備・inventory

- [x] **Task 0: 既存資産inventoryと承認前specを作成する**
  - Objective: 既存の資格/skill/career/training/demand/document/approval/self-service資産を確認し、重複masterを防ぐcandidate requirements/design/planを作る。
  - Implementation: `inventory.md`、`requirements.md`、`design.md`、`plan.md`、`tasks.md`、`completion-matrix.md`を作成する。PII、DocumentLink、taxonomy、as-of、費用approval、AI/humanのdecision tableを含める。
  - Test: production source、migration、test、seed dataに変更がなく、`git diff --check`が成功する。NF-03 traceabilityが`CANDIDATE`であることを再確認する。
  - Demo: 専用worktreeのbranch/status/remote/baseを表示し、文書のsource inventory、重複回避、未解決DG-03、Task完了対応表を確認する。

- [x] **Task 0R: PLAN Review指摘をcandidate specへ反映する**
  - Objective: approved scopeを推測せず、as-of、DocumentLink、経費正本、資格state、scheduler、評価境界、traceabilityを実装前契約として具体化する。
  - Implementation: supply/demandのeffective eventとsnapshot、typed restricted document resolver、既存ExpenseRequest連携、semantic notification key、lifecycle対象母集団、SELF/MANAGER/HR assessment、R7 traceを追加する。Ownerによるscope/Decision/Baseの実承認は実装AIが代行しない。
  - Test: spec内のdecision tableとtasks/matrixの要件IDが一致し、存在しない要件IDを参照しない。production source、migration、test、seed dataは変更しない。
  - Demo: Review finding P1-01〜P1-07/P2-01/P2-02をremediation表で1行ずつ確認し、承認前の外部blockerが明示されていることを確認する。
  - 判定: 文書補正Taskとして完了。Ownerの実承認とtraceability `APPROVED` は別gateであり、PLAN PASSを意味しない。

- [x] **Task 0R-2: R2 PLAN Review指摘をcandidate specへ反映する**
  - Objective: P1-08〜P1-10、P2-03〜P2-06を実装前契約として具体化し、共有書込み境界・FileScope・経費締め・Clock・lifecycle・SELF可視をtasksへ接続する。
  - Implementation: `inventory.md` §5.1/§5.4/§5.5、`design.md` §3.4/§3.6/§3.7/§3.8/§3.9/§4.2/§4.5/§4.6、`tasks.md` F1-2/F1-4/F2-2/B1、`review-remediation.md` R2表、`README.md`/`completion-matrix.md` を更新する。production source、migration、test、seed dataは変更しない。
  - Test: spec内のファイル参照が実在し、P1-01は外部blockerのまま、R8残留参照なし、`git diff --check`成功。
  - Demo: R2 finding（P1-08〜10、P2-03〜06）をremediation表で1行ずつ確認し、F1禁止が継続していることを確認する。
  - 判定: 文書補正Task。PLAN PASS・F1着手許可ではない。

- [x] **Task 0R-3: R3 PLAN Review指摘をcandidate specへ反映する**
  - Objective: P2-03残件（matrix/plan Gate 0/design §3.4手順3）とP2-08（`PositionServiceImpl.delete`）を閉じ、P2-06のSELF非表示testをtasksへ接続する。
  - Implementation: `completion-matrix.md`、`plan.md`、`design.md` §3.4、`inventory.md` §5.4、`tasks.md` F1-4/F2-3/F2-5/A1、`review-remediation.md` R3表を更新。production変更なし。
  - Test: P2-03/P2-08の1行対応、PROJECT正本がevent表記で一貫、`git diff --check`成功。
  - Demo: R3 findingをremediation表で確認し、P1-01外部blockerとF1禁止が継続していることを確認する。
  - 判定: 文書補正Task。PLAN PASS・F1着手許可ではない。

- [x] **Task 0R-4: 開発段階 Owner ポリシーを文書化する**
  - Objective: 実名をrepositoryに記録せず、OwnerRef=`PROJECT_OWNER`で責任主体を管理する開発ポリシーをGate表現へ統一する。
  - Implementation: `owner-policy.md`を新設し、README、plan、review-remediation、completion-matrix、中央traceabilityのOwner/ Gate文言を「OwnerRefが必要」へ揃える。DecisionId=`DG-03-DEV-20260828`。production変更なし。
  - Test: 実名がspec/commitに追加されていないこと、OwnerRef定数の一致、`git diff --check`成功、NF-03が`CANDIDATE`のままであること。
  - Demo: owner-policyと中央台帳のOwnerRefが一致し、`APPROVED`へ遷移していないことを確認する。
  - 判定: Ownerポリシー確定。approved scope・Base・DG-03実値の承認は Task 0G。

- [x] **Task 0G: 開発開始 Decision を記録し Base を取り込む（Gate 0）**
  - Objective: OwnerRef=`PROJECT_OWNER` の下で approved scope、DG-03 実値、承認 Base `76e45340` を記録し、traceability を `APPROVED` へ遷移、`origin/main` を merge する。
  - Implementation: `approval-decision.md` 新設、中央 traceability・README/design/requirements/plan/completion-matrix/review-remediation/owner-policy/inventory を approved へ更新。`origin/main@76e45340` merge。NF-03 production 実装はまだ開始しない。
  - Test: DecisionId `DG-03-SCOPE-APPROVAL-20260828-01`、Base SHA、6 DG 実値、経費 A、実名非記録、`git diff --check`、traceability `APPROVED`、migration latest `V114` 確認。
  - Demo: approval-decision と中央台帳 NF-03 が一致し、旧 merge-base `455fc92e` が承認 Base として記録されていないことを確認する。
  - 判定: Gate 0 文書＋Base merge 完了。F1 は PLAN Review PASS 後。

## F1. 資格/course/plan/enrollment DDL（PLAN PASS 後）

- [x] **Task F1-1: 資格masterと取得recordのDDL/entityを追加する**
  - Objective: 資格名、issuer、code、期限規則、engineer取得状態、番号参照、versionを正規化する。
  - Implementation: mainのV115を保持し、NF-03最新 migration **V116+**、V1/H2専用schema/entity/mapperを同期する。PII field（DG-03-1: AES-256-GCM **または** token のいずれか一つに列形を固定）、issuer/code/nameのnormalized identity、continuity group、current_flag、expiry rule versionを実装する。適用済みmigrationは編集しない。
  - Test: empty DB/MySQL migration、H2 context、duplicate取得、code NULL、issuer別code、名称alias/merge、renew、nullable/期限、PII DTO非漏えい。
  - Demo: HRがmasterを登録し、本人申請がpendingで保存され、承認前activeにならないことを確認する。

- [x] **Task F1-2: certification eventと証憑参照を追加する**
  - Objective: submit/verify/correct/cancelのappend-only履歴とDocumentLink参照を定義する。
  - Implementation: `CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` typed linkだけを使い、generic `ENGINEER` linkが混在してもrestricted policyを優先する。eventへexact document version ID/hashを記録し、DocumentService/FileScopeValidationService/FileReferenceProviderの既存境界に接続する。raw file pathを持たない。**`FileScopeValidationService`に`CERTIFICATION_EVIDENCE`専用分岐を`document-archive`より前に追加**し、empty-link許可・admin bypass・generic OR-unionを資格証憑では禁止する（design §3.6）。
  - Test: duplicate event、correct/cancel理由、scope外、未scan、unknown file、legal hold、mixed link OR-union迂回拒否、exact version/CLEAN検証、**empty-link、ENGINEER-only mixed link、admin bypass、version/hash不一致**。
  - Demo: CLEAN証憑だけが対象取得recordのdetail/downloadに出て、scope外roleでは404/403相当で漏えいしないことを確認する。

- [x] **Task F1-3: course、plan、enrollment DDL/entityを追加する**
  - Objective: course catalog、canonical target skill、learning goal、enrollment/result/certificateを正規化する。
  - Implementation: `m_skill_tag` FKを使い、course-skill/plan-skill joinを作る。planのplanned cost snapshotと`t_training_enrollment_expense`を持つが、actual cost/paymentは`t_expense_request`へ委譲する。`t_training_history`は再利用しない。
  - Test: JPY/期間/capacity、NULL/0、税込、state version、cancel/correct、certificate document reference、training historyとの分離、expense relationの重複防止。
  - Demo: HRがcourseを登録し、本人/上長がplan/enrollmentを作り、完了前後のdetailが状態機械どおりに変わることを確認する。

- [x] **Task F1-4: supply/demandのeffective historyとgap snapshot DDLを追加する**
  - Objective: current-onlyの既存skill/positionを過去as-ofへ遡及適用せず、source versionとsnapshotで再現可能にする。
  - Implementation: `t_engineer_skill_event`、`t_project_skill_event`、`t_project_position_event`、`t_skill_gap_snapshot`を追加候補として確定し、current projectionと同一transactionで履歴を登録する。履歴欠落期間は`historical_data_unavailable`とする。**必須フック:** `EngineerSkillServiceImpl.replaceSkills`、`ProjectSkillServiceImpl.replaceSkills`、`PositionServiceImpl.create`/`update`/`changeStatus`/`delete`を同一Taskの変更対象に含める。`delete`は物理削除前にposition eventへclose/cancelled snapshotを記録し、過去as-ofをcurrent補完しない（inventory §5.4、design §3.4）。
  - Test: effective period overlap、訂正・supersedes、project/position precedence、feature開始日前のas-of、snapshot hash/version、MySQL concurrency、**既存PUT skills後にeventが残り過去as-ofがcurrentに置換されないこと**、**position delete後のas-ofがhistorical_data_unavailableまたは明示cancelledでcurrent補完しないこと**。
  - Demo: 現在値を変更しても過去snapshotのgap結果が変わらず、履歴のない過去指定は安全にdegradedとなることを確認する。

- [x] **Task F1-5: 評価proposal・人の確定・決定監査DDLを追加する**
  - Objective: 本人自己評価、上長提案、HR確定、AI候補、人の最終決定を別recordで監査する。
  - Implementation: `t_engineer_skill_assessment`と`t_learning_decision_event`を追加候補とし、AI candidateから公式skill/placementへの直接FK・遷移を作らない。
  - Test: SELF/MANAGER/HR_FINALの分離、actor/reason/effective period、AI accept/reject、adverse-use flag、AI-only finalization拒否。
  - Demo: AI候補をacceptしてもassessment/配置が変わらず、人の確定操作と監査event後だけ公式projectionへ反映可能なことを確認する。

## F2. service（承認後のみ）

- [x] **Task F2-1: 資格履歴・期限・通知判定serviceを実装する**
  - Objective: 90/60/30境界、取消、訂正、重複取得、idempotent通知を実現する。
  - Implementation: `ff8165ea`。V121でevent idempotency keyと`certification.pii.view` seedを追加。`EngineerCertificationServiceImpl`へverify/reject/correct/cancel/renewのrow lock＋version CAS、append-only event、duplicate acquisition拒否を実装し、`CertificationExpiryService`／`CertificationExpiryNotificationService`で期限当日を含む90/60/30のsemantic keyを判定する。`CertificationEvidenceValidator`と`FileScopeValidationService`でtyped link、exact version/hash、CLEAN、legal holdをfail closedにする。共通ClockをAsia/Tokyoへ固定した。
  - Test: `CertificationExpiryServiceTest`（90/60/30当日・前後、expires_on当日、revisionを含まないsemantic key、cancelled/recipient未解決）、`EngineerCertificationLifecycleServiceTest`（verify/correct/cancel/renew、理由、重複、CAS）、`CertificationEvidenceValidatorTest`（typed link、版/hash、scan、generic link）、`FileScopeValidationServiceTest`（legal hold）、`AppConfigClockTest`、既存`EngineerCertificationServiceTest`。対象Mavenテスト全件PASS。
  - Demo: 固定日`2026-08-28`でexpiryを90/60/30日に切り替え、当日だけ候補となり前後日は候補外、訂正でrevisionだけ変えてもsemantic keyが同一、期限日当日はACTIVE・翌日からEXPIRED、cancel/renew/CAS/証憑否定系が確認できることをテストで実演した。

- [x] **Task F2-2: training plan/enrollment/approval serviceを実装する**
  - Objective: state transitionと費用threshold、既存approval engine、自己承認拒否を接続する。
  - Implementation: `d74ae5af`。**選択肢 A（DG-03-5）**として既存`ExpenseRequestService`を費用・承認・支払の正本にし、`TrainingPlanServiceImpl`がplan/enrollmentの状態とappend-only eventだけを所有する。`ExpenseRequestServiceImpl`、会計送信、BP支払連携の全更新経路から`MonthlyClosingService.assertOpenForUpdate`を呼び、planには予定額snapshotとexpense request/approval requestの関連を保持する。NULL/負数/小数、0円理由、自己承認、row lock＋version CAS、実費差額、締め済み月をfail closedにした。
  - Test: `TrainingPlanServiceTest`でNULL/0/threshold−1/threshold/threshold＋1、予定額snapshotと実費差額、追加expense approval、締め済み月、route不在、申請者自己承認、CAS競合、approval後completionを確認。`ExpenseRequestFlowIntegrationTest`等の既存経費回帰も実行し、**締め済み月のamount/関連/支払変更拒否（経費正本経由）**を確認した。
  - Demo: threshold等値の申請が既存expense approvalへ進み、申請者がapproveできず、expense承認・会計/支払条件を満たした後のみcompletionできることをテストで実演した。0円は理由付きで即時承認され、正の費用はplan費用snapshotを実費で上書きしないことも確認した。

- [x] **Task F2-3: as-of skill gap serviceを実装する**
  - Objective: project/position期間、skill level、evidence count、unknown/synonym、0件を説明可能に比較する。
  - Implementation: `SkillGapServiceImpl`（commit `f5e69182`）は`t_engineer_skill_event`、`t_project_skill_event`、`t_project_position_event`だけを読み、current projectionから過去を補完しない。feature開始日前・履歴欠落は`historical_data_unavailable`、PROJECT/ POSITION/ COMBINEDのsource precedenceと案件期間inclusiveを結果へ残す。V123の`t_skill_tag_alias`と`SkillGapTaxonomyResolver`は承認済みsynonymだけをcanonical IDへ解決し、未知skillをmasterへ自動作成しない。`t_skill_gap_snapshot`へsource version/taxonomy version/result hash/jsonを保存し、replayはhash検証後にsnapshotだけから復元する。BP要員化のskill writeも`EngineerSkillService.replaceSkills`へ集約し、event insert迂回を防止した。
  - Test: `SkillGapServiceImplTest`（supply/demand effective as-of、履歴欠落、PROJECT/POSITION/COMBINED precedence、案件期間両端、0件、DELETE当日/翌日、snapshot replay）、`SkillGapTaxonomyResolverTest`（同義tag、未知skill、自動master化なし）、`EngineerSkillServiceImplTest`/`ProjectSkillServiceImplTest`（replaceSkills後のevent残存）、`FlywayCertificationLearningSkillGapSchemaSmokeTest`（V123 MySQL schema）、`MigrationScriptIntegrityTest`を実行した。AIはこのTaskのgap計算へ依存させず、AI停止時もrule結果を維持する契約をF2-5/B2へ接続した。
  - Demo: 固定ClockとV123 event fixtureで、feature開始日前はcurrent rowが存在してもunavailable、DELETE当日は削除状態を需要に戻さず翌日もcurrent fallbackなし、同義tagはcanonical表示、未知skillはunknown gap、COMBINEDはPROJECTを優先しposition-onlyを追加、replayはsource queryなしで同一hashを返すことをテストで実演した。

- [x] **Task F2-4: 期限schedulerと通知母集団を実装する**
  - Objective: 複数JVM再実行でもsemantic expiry noticeを重複発行せず、退職・休職・account未link・manager変更を正しく扱う。
  - Implementation: `8c8997cb`。注入`Clock`の日付で`CertificationExpiryNotificationScheduler`を実行し、`CertificationNotificationPopulationResolver`がNF-01 lifecycle caseをEngineer.statusより優先してdispatch時点の本人/現manager/HRを解決する。退職完了・休職・account無効/未link・manager変更をfail closedで扱い、復職当日は`CERT_REINSTATEMENT:recordId:date:recipientId`を一度だけ発行する。通常expiry keyはrecord revisionを含めず、既存`t_notification.dedupe_key` uniqueと`t_notification_outbox` unique/claimへ渡す。
  - Test: `CertificationNotificationPopulationResolverTest`（通常、退職完了、休職、復職、account未link、as-of manager）、`CertificationExpiryNotificationSchedulerTest`（二重実行の同一semantic入力、復職key）、既存`NotificationServiceImplTest`/`NotificationOutboxServiceTest`（DB/outbox DuplicateKey収束）を実行した。Asia/Tokyo固定ClockでJVM default timezoneに依存しない。
  - Demo: 同じ資格境界をschedulerから再実行しても同じkeyだけが通知正本へ渡り、DB uniqueで2件目を収束させること、復職日はREINSTATEMENT keyだけを発行し、退職後の旧managerと本人へ通常通知しないことをテストで実演した。

- [x] **Task F2-5: 本人/上長/HR評価とAI候補契約を実装する**
  - Objective: AIがskill評価・配置・採否・不利益判断を確定できないことをservice/API/監査で保証する。
  - Implementation: `78bbfcef`＋V125補正commit。`SkillAssessmentService`はSELF/MANAGER/HR_FINALを別operationとして受け、SELF/MANAGERはPROPOSED＋decision eventだけ、HR_FINALだけが`EngineerSkillService.replaceSkills`経由で公式projectionを更新する。人のactor/reasonを必須化し、AI相当のassessment typeを入力させない。`AiLearningCandidateService`は`LEARNING_CANDIDATE` artifact/runの`aiRunId`、rule gap snapshot ID、as-of、allowlist、期限を候補へ保持し、AIはcourse ID候補だけをallowlist内で返す。timeout/error・run監査欠落はDEGRADEDへfallbackし、rule gapを維持する。accept/rejectは期限内の人のdecision eventだけを追加し、評価・配置・採否・adverse stateを変更しない。V125でF1 training relation table 3表の`updated_at`をMyBatis生成列・MySQL・H2へ同期する。
  - Test: `SkillAssessmentServiceImplTest`（SELF/MANAGERの公式projection非変更、本人/manager/HR権限、AI相当level・理由なし拒否、HR_FINALのみ共通skill service更新）、`AiLearningCandidateServiceImplTest`（AI停止、provider error、timeout、allowlist/run ID、human accept/reject、期限切れ、RULE_ONLY accept拒否、監査event）と`MigrationScriptIntegrityTest`、`MessageBundleConsistencyTest`、`AllMappersSchemaSweepTest`、`FlywayCertificationLearningSkillGapSchemaSmokeTest`を実行した。SELF/MANAGERは`EngineerSkill` currentへ書かず、staffing/sales/exportは同projectionのみを読む契約をservice境界で固定した。
  - Demo: AI timeout/errorでも同じrule gapとas-ofを返し、AI成功候補はallowlist内courseだけを返す。候補のaccept/rejectは`source_id=aiRunId`の人の監査eventを残すが、公式skill/配置/採否を変更しない。期限後・AI停止時のcandidate受諾とAI-only確定は拒否されることをテストで実演した。

## A1. HR/manager UI（承認後のみ）

- [ ] **Task A1: HR/manager資格・training・gap list/detailを実装する**
  - Objective: role別に同じpopulationをlist/detail/count/exportで表示する。
  - Test: manager org∩DataScope、HR、admin、scope外、退職/休職の閲覧と通知除外、番号mask、empty state、390px、safePage、**SELF assessmentがstaffing board/heatmapに表示されないこと**。
  - Demo: 同一filterでlist/detail/exportの対象IDが一致し、document/PII fieldだけpolicyどおり差異があることを確認する。

## A2. 本人申請・学習計画（承認後のみ）

- [ ] **Task A2: 本人の取得申請とlearning plan/enrollment UIを実装する**
  - Objective: account linkから本人を解決し、証憑upload、plan、status、withdraw/resubmitを提供する。
  - Test: 他人engineerId改変、attachment scope、approval待ち、cancel/correct、本人export。
  - Demo: URL/APIに他人IDを渡しても本人以外を参照できず、CLEAN証憑とplan状態がdetailに反映されることを確認する。

## B1. 通知・承認・document（承認後のみ）

- [ ] **Task B1: 期限通知、費用approval、証憑download/exportを実装する**
  - Objective: recipient user ID、approval route、DocumentLink/FileScopeValidationServiceをつなぐ。
  - Test: 90/60/30、semantic key重複、複数JVM claim、threshold−1/等値/＋1、NULL/0/実費差額、締め済み月、自己承認、scan/legal hold/unknown、mixed link、**empty-link、ENGINEER-only、admin bypass、version/hash不一致**、同一scope。
  - Demo: scheduler再実行後も通知/eventが重複せず、download/exportがUIと同じscopeで拒否されることを確認する。

## B2. 需要連携・AI候補（承認後のみ）

- [ ] **Task B2: staffing as-ofとAI候補を接続する**
  - Objective: project position/project skillとcanonical taxonomyを期間指定で比較し、AIをcandidate-onlyで表示する。
  - Test: staffing as-of、履歴欠落、PROJECT/POSITION/COMBINED precedence、inclusive境界、同義tag、未知skill、0件、snapshot replay、AI停止/error/timeout、AIによる最終配置なし。
  - Demo: 需要期間を変えると結果が変わり、未知skillが隠れず、AI結果を無効にしてもrule gapが残ることを確認する。

## M. 完了・Review handoff（承認後のみ）

- [ ] **Task M: mandatory test/DemoとReview packetを完成する**
  - Objective: 全受入条件とpopulation一致を証拠化し、完了対応表とremote HEADをReviewへ渡す。
  - Test: fast suite、MySQL suite、performance/CI相当、scope/PII/document/approval/AI回帰、migration/H2 smoke。
  - Demo: 資格期限90/60/30、取消/訂正、重複、証憑scope、as-of/synonym/unknown/period/0件、threshold/自己承認、AI停止、list/detail/export/本人/上長/HRの全シナリオを実演する。
  - Deliverable: M完了後にだけ `review-packet.md` を作成し、ReviewのPLAN/IMPLEMENTATION双方PASS後にPR作成工程へ引き渡す。
