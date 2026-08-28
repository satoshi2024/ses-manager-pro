# Tasks（NF-03 candidate）

> `[x]` はObjective、implementation evidence、required test、manual Demoをすべて満たしたTaskだけに付ける。NF-03が `CANDIDATE` の間はF1以降を開始しない。

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

## F1. 資格/course/plan/enrollment DDL（承認後のみ）

- [ ] **Task F1-1: 資格masterと取得recordのDDL/entityを追加する**
  - Objective: 資格名、issuer、code、期限規則、engineer取得状態、番号参照、versionを正規化する。
  - Implementation: 最新migration+1、V1/H2専用schema/entity/mapperを同期し、PII field、issuer/code/nameのnormalized identity、continuity group、current_flag、expiry rule versionを実装する。適用済みmigrationは編集しない。
  - Test: empty DB/MySQL migration、H2 context、duplicate取得、code NULL、issuer別code、名称alias/merge、renew、nullable/期限、PII DTO非漏えい。
  - Demo: HRがmasterを登録し、本人申請がpendingで保存され、承認前activeにならないことを確認する。

- [ ] **Task F1-2: certification eventと証憑参照を追加する**
  - Objective: submit/verify/correct/cancelのappend-only履歴とDocumentLink参照を定義する。
  - Implementation: `CERTIFICATION_EVIDENCE`＋`CERTIFICATION_RECORD` typed linkだけを使い、generic `ENGINEER` linkが混在してもrestricted policyを優先する。eventへexact document version ID/hashを記録し、DocumentService/FileScopeValidationService/FileReferenceProviderの既存境界に接続する。raw file pathを持たない。**`FileScopeValidationService`に`CERTIFICATION_EVIDENCE`専用分岐を`document-archive`より前に追加**し、empty-link許可・admin bypass・generic OR-unionを資格証憑では禁止する（design §3.6）。
  - Test: duplicate event、correct/cancel理由、scope外、未scan、unknown file、legal hold、mixed link OR-union迂回拒否、exact version/CLEAN検証、**empty-link、ENGINEER-only mixed link、admin bypass、version/hash不一致**。
  - Demo: CLEAN証憑だけが対象取得recordのdetail/downloadに出て、scope外roleでは404/403相当で漏えいしないことを確認する。

- [ ] **Task F1-3: course、plan、enrollment DDL/entityを追加する**
  - Objective: course catalog、canonical target skill、learning goal、enrollment/result/certificateを正規化する。
  - Implementation: `m_skill_tag` FKを使い、course-skill/plan-skill joinを作る。planのplanned cost snapshotと`t_training_enrollment_expense`を持つが、actual cost/paymentは`t_expense_request`へ委譲する。`t_training_history`は再利用しない。
  - Test: JPY/期間/capacity、NULL/0、税込、state version、cancel/correct、certificate document reference、training historyとの分離、expense relationの重複防止。
  - Demo: HRがcourseを登録し、本人/上長がplan/enrollmentを作り、完了前後のdetailが状態機械どおりに変わることを確認する。

- [ ] **Task F1-4: supply/demandのeffective historyとgap snapshot DDLを追加する**
  - Objective: current-onlyの既存skill/positionを過去as-ofへ遡及適用せず、source versionとsnapshotで再現可能にする。
  - Implementation: `t_engineer_skill_event`、`t_project_skill_event`、`t_project_position_event`、`t_skill_gap_snapshot`を追加候補として確定し、current projectionと同一transactionで履歴を登録する。履歴欠落期間は`historical_data_unavailable`とする。**必須フック:** `EngineerSkillServiceImpl.replaceSkills`、`ProjectSkillServiceImpl.replaceSkills`、`PositionServiceImpl.create`/`update`/`changeStatus`/`delete`を同一Taskの変更対象に含める。`delete`は物理削除前にposition eventへclose/cancelled snapshotを記録し、過去as-ofをcurrent補完しない（inventory §5.4、design §3.4）。
  - Test: effective period overlap、訂正・supersedes、project/position precedence、feature開始日前のas-of、snapshot hash/version、MySQL concurrency、**既存PUT skills後にeventが残り過去as-ofがcurrentに置換されないこと**、**position delete後のas-ofがhistorical_data_unavailableまたは明示cancelledでcurrent補完しないこと**。
  - Demo: 現在値を変更しても過去snapshotのgap結果が変わらず、履歴のない過去指定は安全にdegradedとなることを確認する。

- [ ] **Task F1-5: 評価proposal・人の確定・決定監査DDLを追加する**
  - Objective: 本人自己評価、上長提案、HR確定、AI候補、人の最終決定を別recordで監査する。
  - Implementation: `t_engineer_skill_assessment`と`t_learning_decision_event`を追加候補とし、AI candidateから公式skill/placementへの直接FK・遷移を作らない。
  - Test: SELF/MANAGER/HR_FINALの分離、actor/reason/effective period、AI accept/reject、adverse-use flag、AI-only finalization拒否。
  - Demo: AI候補をacceptしてもassessment/配置が変わらず、人の確定操作と監査event後だけ公式projectionへ反映可能なことを確認する。

## F2. service（承認後のみ）

- [ ] **Task F2-1: 資格履歴・期限・通知判定serviceを実装する**
  - Objective: 90/60/30境界、取消、訂正、重複取得、idempotent通知を実現する。
  - Test: 90/60/30当日・前後、Asia/Tokyo Clock、expiry date変更を伴わない訂正で再送しないこと、expired/cancelled/superseded、renew、再実行。
  - Demo: expiry dateを固定し、各境界で本人/上長recipientへ一度だけ通知されることを確認する。

- [ ] **Task F2-2: training plan/enrollment/approval serviceを実装する**
  - Objective: state transitionと費用threshold、既存approval engine、自己承認拒否を接続する。
  - Implementation: DG-03で選択した経費締め境界を実装する。選択肢Aなら`ExpenseRequestServiceImpl`へ`MonthlyClosingService.assertOpenForUpdate`を接続、選択肢Bなら研修専用wrapper＋既存経費更新拒否（design §3.7）。
  - Test: NULL/0/threshold−1/threshold/threshold＋1、予定額snapshotと実費差額、追加expense approval、締め済み月、route不在、申請者自己承認、CAS競合、approval後completion、**締め済み月のamount/関連/支払変更拒否（経費正本経由）**。
  - Demo: threshold等値の申請がapprovalへ進み、申請者がapproveできず、承認後のみcompletionできることを確認する。

- [ ] **Task F2-3: as-of skill gap serviceを実装する**
  - Objective: project/position期間、skill level、evidence count、unknown/synonym、0件を説明可能に比較する。
  - Implementation: F1-4のeventフック完了後にのみ有効。`SkillTagResolver`の未知自動作成を需要計算へ使わない。
  - Test: supply/demand effective as-of、履歴欠落、同義tag、未知skill、PROJECT/POSITION/COMBINED precedence、案件期間両端、0件、snapshot replay、AI停止時rule fallback、**replaceSkills後のevent残存**、**position delete後のas-of**。
  - Demo: AI providerを停止してもgapが表示され、AIはcourse候補のみで評価/配置を確定しないことを確認する。

- [ ] **Task F2-4: 期限schedulerと通知母集団を実装する**
  - Objective: 複数JVM再実行でもsemantic expiry noticeを重複発行せず、退職・休職・account未link・manager変更を正しく扱う。
  - Implementation: 注入Clock（design §3.8のTenantClock正本）、`CertificationNotificationPopulationResolver`（lifecycle case優先、design §3.8）、recipient user ID、`t_notification.dedupe_key` unique、outbox claimを使用する。version番号だけをdedupe keyにしない。
  - Test: same key duplicate、expiry date変更、退職完了/休職/復職、account未link、manager変更、二重scheduler claim、Asia/Tokyo境界（JVM default非依存）。
  - Demo: 同一境界を2 schedulerから同時実行して通知1件、復職時はREINSTATEMENT 1件、旧managerへ再送なしを確認する。

- [ ] **Task F2-5: 本人/上長/HR評価とAI候補契約を実装する**
  - Objective: AIがskill評価・配置・採否・不利益判断を確定できないことをservice/API/監査で保証する。
  - Implementation: SELF/MANAGER/HR_FINALを別DTO・権限・eventへ分離し、人のactor/reasonを必須化する。AI timeout/error/低信頼はcandidateだけdegradedにし、rule gapを維持する。
  - Test: AI-only transition拒否、human accept/reject、adverse source禁止、allowlist、timeout/circuit、監査event、**SELF/MANAGER assessmentがstaffing/sales API・画面に出ないこと**（design §3.9/§4.6）。
  - Demo: AI timeoutと候補acceptの両方で、公式skill/配置/採否が自動変更されないことを確認する。

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
