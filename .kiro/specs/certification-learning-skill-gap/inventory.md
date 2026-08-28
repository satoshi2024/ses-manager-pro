# 既存資産inventory（NF-03準備）

## 1. 調査条件と結論

調査対象は `origin/main` の `455fc92e` をbaseとする専用worktreeです。NF-03は `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md` で `CANDIDATE` のため、production変更は行っていません。

結論は次のとおりです。

1. skillの正本は既存の `m_skill_tag`、engineerへの保有skillは `t_engineer_skill`、案件要求skillは `t_project_skill` を引き続き使用する。資格feature用のskill masterは作らない。
2. careerの正本は既存の `t_engineer_career` を使用する。研修履歴をcareerへ複製しない。
3. `t_training_history` は契約・法定dispatchのappend-only履歴であり、course、learning plan、enrollmentの正本として再利用しない。
4. 証憑は既存Document domain（`t_document`、`t_document_version`、`t_document_link`）と `DocumentService`、`FileScopeValidationService` を使用する。資格機能専用のfile path、ACL、download実装は作らない。
5. 承認は既存Approval Engineとroute snapshotを使用する。資格・学習用の独自承認処理や自己承認例外は作らない。
6. staffing需要は現状 `t_project_position.skills_json` のfree textで保持されるため、`m_skill_tag`へのas-of解決、同義語、未知skillの扱いを設計で確定する必要がある。需要masterを重複作成しない。

## 2. domain別table/entity inventory

| 領域 | 既存table / entity | 既存の正本・役割 | NF-03での扱い |
|---|---|---|---|
| skill master | `m_skill_tag` / `SkillTag` | `skill_name`一意、`category`は言語/FW/DB/クラウド/OS/ツール/その他 | 再利用。正式taxonomyの変更は既存masterのmigration/管理APIで行う |
| engineer skill | `t_engineer_skill` / `EngineerSkill` | engineer×skill、proficiency（初級/中級/上級）、experienceYears | 再利用。gapのcurrent levelとevidenceのsource |
| project skill | `t_project_skill` / `ProjectSkill` | project×skill、requiredLevel、must flag | 再利用可能な案件正規skill。positionのfree textとは出所を明示して統合 |
| engineer career | `t_engineer_career` / `EngineerCareer` | 期間、project、role、tech stack、description等 | 再利用。研修の完了結果をcareerへ自動複製しない |
| training history | `t_training_history` / `TrainingHistory` | `contract_id`必須の契約・dispatch履歴、event/correctionを含むappend-only | course/enrollmentの正本にはしない。必要なら結果から明示的に参照 |
| career consulting | `t_career_consulting_history` | career面談等の履歴 | learning planとは別用途。重複しない |
| expense/accounting | `t_expense_request` / `ExpenseRequest`、`ExpenseRequestService` | JPY経費申請、approval、領収書Document、会計outbox、支払状態。`accounting_job_id`で連携を冪等化 | 研修費の金額・支払・会計状態の正本として再利用。enrollmentへactual costを複製しない |
| lifecycle | `m_lifecycle_template`、`t_lifecycle_case`、`t_lifecycle_task`、`t_lifecycle_event` | 入社・休職・復職・退社等の有効なライフサイクルと監査 | 退職・休職・復職の通知対象判定に参照。資格履歴を削除・取消する理由にはしない |
| notification | `t_notification` / `Notification`、`t_notification_outbox` / `NotificationOutbox` | `recipient_user_id`、global unique `dedupe_key`、outbox claim | 既存uniqueとoutboxを再利用。資格のsemantic keyをversion番号だけで作らない |
| staffing demand | `t_project_position` / `ProjectPosition` | `skills_json`、`start_date`/`end_date`（inclusive）、allocation、status | 読み取りsource。skill JSONを黙って正本化しない |
| staffing plan | `t_allocation_plan` / `AllocationPlan` | position期間、actual/plan、approval、version | skill gapの需要そのものに置換しない |
| self-service workflow | `t_engineer_change_request` / `EngineerChangeRequest` | profile/skill/career変更申請、target version、attachment document、approval | 資格取得やlearning planの承認境界を参考にする。既存request typeを無理に拡張しない |
| document | `t_document`、`t_document_version`、`t_document_link` / `Document`、`DocumentVersion`、`DocumentLink` | versioned file、scan status、retention/legal hold、scopeの根拠となるlink | 再利用。資格証憑typeとtarget scopeはDG-03決定後に追加 |
| approval | `m_approval_route`、approval request等 / `ApprovalEngineService`、adapter registry | request時route snapshot、金額range inclusive、version CAS、申請者自己承認拒否 | 再利用。request typeと対象adapterだけ追加候補 |
| config | `m_system_config` / `SystemConfig` | 管理画面で変更する各種閾値 | 学習費用thresholdは`m_approval_route.min_amount`を正本候補とし、同じ値を`m_system_config`へ重複保存しない |

### 既存の無関係なqualification

`t_compliance_reviewer_qualification` と compliance gateのqualification APIは、外部reviewerの適格性管理です。engineerの保有資格master・期限・証憑とは意味が異なるため、資格機能のtableとして再利用しません。

## 3. API / service / UI inventory

### skill・career

| 機能 | API / service | population・注意点 |
|---|---|---|
| engineer list/detail | `EngineerApiController` の `/api/engineers`、`/api/engineers/{id}` | listはstatus、employment、skillIds（AND）、sales、risk、account link等。管理scopeはorg∩DataScopeを基準とする |
| engineer options | `/api/engineers/options` | picker用。資格・学習側もIDを直接信用せず、current userの解決結果とscopeを再検証する |
| engineer skill | `EngineerSkillApiController` の `/api/engineers/{engineerId}/skills` | parent engineerのDataScopeを検証。replaceはskill ID存在、重複排除、transaction処理 |
| project skill | `ProjectSkillApiController` の `/api/projects/{projectId}/skills` | project scopeを検証。案件の正規skill sourceとして使用可能 |
| skill tag | `SkillTagApiController` の `/api/skill-tags` | listとHR/manager/adminの管理API。`SkillTagResolver` はtrim/fullwidth/uppercase正規化後、未知名を`未分類`で作成するため、需要入力にそのまま適用しない |
| career | `EngineerCareerApiController` の `/api/engineers/{engineerId}/careers` | parent scope、所有権、期間妥当性を検証。期間降順で表示 |
| skill sheet | `SkillSheetApiController` のPDF/XLSX、`MyProfileApiController` の本人skill-sheet | skill/careerを統合表示。資格証憑・番号を追加する場合は同じscopeとmaskingを再現する |

### exportとpopulationの既存差分

`ExportApiController` のExcelはengineer listと同じorg∩DataScopeを使います。一方、`CsvApiController` のengineer CSVは現状DataScope中心です。NF-03で「list/detail/exportの母集団一致」を完了条件にするには、資格・gapの新APIだけでなく、共通のeffective populationを使うか、既存CSVの差分を明示的に修正する必要があります。承認前は修正しません。

### training・本人申請・文書・承認

| 機能 | 既存資産 | NF-03での再利用境界 |
|---|---|---|
| training history | `TrainingHistory` entity/mapperとV1/V84の`t_training_history` | 契約に紐づく法定/dispatch履歴。course enrollmentへ流用しない |
| 本人profile | `MyProfileApiController` の `/api/my/profile`、skill-options、skill-sheet | engineer account linkから本人を解決し、engineerId入力を受けないパターンを踏襲 |
| 本人change request | `MyChangeRequestApiController` の `/api/my/change-requests` | approval前はmasterを変えず、attachmentはDocumentService経由。資格専用request typeはDG確定後に追加 |
| HR/manager review | `EngineerChangeRequestApiController` の `/api/engineer-change-requests` | managerはorg∩DataScope、HRは既存HR scope。番号のmaskingは別途定義 |
| documents | `DocumentApiController`、`DocumentService`、`FileScopeValidationService` | list/detail/download/exportで同一scope。scan statusはCLEAN必須、unknownはfail closed |
| approvals | `ApprovalEngineService`、`ApprovalTargetAdapterRegistry`、approval API | request時snapshot、amount boundary inclusive、申請者自己承認禁止、candidate不在時fail closed |

### staffing・AI

| 機能 | 既存資産 | NF-03での扱い |
|---|---|---|
| staffing demand | `StaffingCapacityService`、`StaffingHeatmapService`、`StaffingBoardService` | positionのstart/endはinclusive。target period内の需要をas-ofで読む |
| AI | `AiApiController`、`AiRestController`、mock provider | rule-based gapをprimaryにし、AIは候補・説明のみ。PII/証憑/番号をprompt allowlistに含めない |
| notification | `NotificationGenerateService`、通知API | 90/60/30通知はrecipient user IDを正本にする。org scopeを後付けしない |

## 4. 新設候補と非重複ルール

承認後のF1で初めてmigration/entityを確定する。現時点の候補は以下ですが、いずれも未承認です。

| 候補 | 必要理由 | 既存との重複を避ける制約 |
|---|---|---|
| `m_certification` | issuer、qualification code、name、expiry ruleのmaster | compliance qualificationを流用しないが、skill masterも持たない |
| `t_engineer_certification` | engineerの取得状態、取得日、期限、番号参照、current state | `t_engineer_skill`へ資格をskillとして二重登録しない |
| `t_certification_event` | submit/verify/correct/cancel等のappend-only change history | `t_training_history`やchange requestへ履歴を混在させない |
| `m_training_course` | provider、期間、費用、capacity、active | `t_training_history`をcourse masterにしない |
| `t_training_course_skill` | courseが対象とするcanonical skill | `skills_json`を新たなskill masterにしない |
| `t_learning_plan` | engineer/creator、goal period、criteria、approval、state | career consultation履歴をplanに変換しない |
| `t_learning_plan_skill` | planのgoal skillとtarget level | `m_skill_tag`のIDを参照し、名称を保存して正本化しない |
| `t_training_enrollment` | plan/courseの申込・開始・完了・cancel、result、certificate link、planned cost snapshot | 契約training historyへ直接insertしない。actual costは経費正本から導出 |
| `t_training_enrollment_expense` | enrollmentと既存経費の関連 | 金額・支払状態を所有せず、既存`t_expense_request`を参照 |
| `t_skill_tag_alias` | synonym解決の監査可能なmap | 既存`SkillTagResolver`の未知自動作成だけで同義語を表現しない |
| `m_certification_alias` | 資格名表記揺れのcanonical map | `m_certification`の別masterを作らず、merge履歴を残す |
| `t_engineer_skill_event` / `t_project_skill_event` | supply・project skillのeffective history | current projectionを過去へ遡及適用しない |
| `t_project_position_event` | staffing positionのas-of snapshot | 現行positionだけで過去需要を推測しない |
| `t_engineer_skill_assessment` | 本人/上長/HRの評価proposal・確定を分離 | `t_engineer_skill`のcurrent値へAI/本人が直接書かない |
| `t_learning_decision_event` | 人の確定・利用目的・不利益利用監査 | AI candidateだけでは確定・配置・不利益判断できない |
| `t_skill_gap_snapshot` | monthly close/export等の再現用immutable snapshot | source of truthではなく、interactive current/as-of計算と区別 |

F1のmigration番号は、実装開始時にそのbranchの最新migrationを再確認し、platform-invariantsのlatest+1規約で決める。新specはH2 replay listへMySQL migrationを追加しない。

## 5. Review指摘を受けたcandidate方針

以下は実装候補を曖昧なまま残さないために本spec上で固定した方針です。ただし中央traceabilityが `CANDIDATE` のため、Owner承認までは実装契約になりません。

### 5.1 as-of sourceの完全性とprecedence

- `t_engineer_skill`と`t_project_skill`はcurrent projectionとして残し、書込みtransactionごとにそれぞれのappend-only eventへeffective rowを記録する。履歴がない期間をcurrent rowで補完しない。
- `t_project_position`もupdateごとにposition eventを記録する。feature有効化前の期間にeventが存在しない場合は `historical_data_unavailable` を返し、現在値を過去へ遡及適用しない。
- `PROJECT`分析のas-of正本は`t_project_skill_event`（current projectionは`t_project_skill`）。`POSITION`分析のas-of正本は`t_project_position_event`の`skills_json`。`COMBINED`では同一project・canonical skillについてproject skill eventを優先し、position側の追加skillだけを加える。source IDとprecedenceを結果へ残す。
- 月次締め・export・再現要求は`t_skill_gap_snapshot`を必須とし、interactive queryは指定as-ofのeffective eventを読む。snapshotはsource of truthではない。

### 5.2 DocumentLinkのrestricted scope

資格証憑は専用document type `CERTIFICATION_EVIDENCE` と `target_type=CERTIFICATION_RECORD` のDocumentLinkだけで認可する。`ENGINEER` linkを補助的に付けない。既存の複数link OR-unionは一般文書に限り、資格証憑ではrestricted policyが優先し、generic linkは認可根拠にしない。eventへ対象document version ID/hashを保存し、requested versionが完全一致しCLEANである場合だけdownload/exportする。

### 5.3 費用・状態・scheduler・人の確定

- 学習planのplanned costは申請時snapshot、actual costと支払状態は既存`t_expense_request`／会計outboxが正本。enrollmentにactual costを保存しない。
- 資格の`CORRECTED`はcurrent statusではなくevent type。訂正後のcurrent stateはACTIVE/EXPIRED/CANCELLED等を独立に導出し、renewはcontinuity groupを持つ新recordとする。
- 期限通知keyはsemantic expiry date＋threshold＋recipientを使い、無関係なrevision番号を含めない。Asia/Tokyoの注入Clock、退職・休職・account未link、manager変更、複数JVM unique競合を対象母集団表とtestで固定する。
- 本人自己評価、上長提案、HR確定を別assessmentとして保存する。AIは候補・説明だけを返し、human decision eventなしに`t_engineer_skill`、配置、採否、adverse decisionを変更しない。

### 5.4 既存skill/position書込み経路（as-of eventフック対象）

NF-03のas-of eventは、新APIだけでなく**既存の全置換・更新経路**と同一transactionで履歴を残す。現状は物理delete→insertのため、event DDLだけでは履歴が消える。

| 経路 | 実装ファイル | 呼出元（代表） | F1-4/F2-3での必須フック |
|---|---|---|---|
| engineer skill全置換 | `EngineerSkillServiceImpl.replaceSkills` | `EngineerSkillApiController`、`EngineerChangeRequestApprovalAdapter`、`ResumeIngestionServiceImpl` | supply event append。delete前のeffective close＋insert後のopen event |
| project skill全置換 | `ProjectSkillServiceImpl.replaceSkills` | `ProjectSkillApiController`、`ProjectServiceImpl`、`ProjectIngestionServiceImpl` | project skill event append（同上） |
| position更新 | `PositionServiceImpl.create` / `update` / `changeStatus` | staffing API | position eventへ`skills_json`・期間・statusをsnapshot |

engineer-skill-career / staffing-capacity-planning との**共有境界**としてOwner承認が必要。承認後は上記3サービスを変更対象に含め、新規専用APIだけにeventを閉じ込めない。

### 5.5 FileScope・経費の既存ギャップ（計画上の穴）

| 領域 | 現状（base `455fc92e`） | NF-03での必須補正 |
|---|---|---|
| 資格証憑download | `FileScopeValidationService`は`RECEIPT`等の専用分岐の後、`document-archive`経路でlink空なら非管理者を許可、管理者はlink検査をbypass | `CERTIFICATION_EVIDENCE`を`document-archive`より前の専用分岐へ。empty-link・admin bypass・generic `ENGINEER` linkをgrantに使わない |
| 経費締め | `ExpenseRequestServiceImpl`はamount≤0を拒否するが`MonthlyClosingService.assertOpenForUpdate`未接続 | 研修費を含む全経費の締め境界を共有化（design §3.7のOwner選択） |

## 6. 承認が必要なDG-03

- 資格番号の法務上の分類、暗号化方式、表示可能なrole、export可否。
- `CERTIFICATION_EVIDENCE`と`CERTIFICATION_RECORD`の正式enum、専用typed resolverの組織横断契約。
- `t_project_position.skills_json`のcanonical解決を行う承認role。未知skillを新masterへ自動登録しない方針。
- 上記as-of event/snapshot schemaを採用するmigration scopeとbackfill開始日。
- 学習費用threshold、threshold等値の扱い（candidateはinclusive）、承認者chain、0円/実費精算の扱い。NULLは申請不可、金額は税込JPY候補。
- self/manager/HR評価の最終確定role、異議申立て、staffing・評価・採否・不利益利用の禁止境界。
- AIの利用停止、timeout、低信頼、候補拒否時のUIとaudit。人の評価・配置確定をAIに委譲しないことは不変条件。
