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
| staffing demand | `t_project_position` / `ProjectPosition` | `skills_json`、`start_date`/`end_date`（inclusive）、allocation、status | 読み取りsource。skill JSONを黙って正本化しない |
| staffing plan | `t_allocation_plan` / `AllocationPlan` | position期間、actual/plan、approval、version | skill gapの需要そのものに置換しない |
| self-service workflow | `t_engineer_change_request` / `EngineerChangeRequest` | profile/skill/career変更申請、target version、attachment document、approval | 資格取得やlearning planの承認境界を参考にする。既存request typeを無理に拡張しない |
| document | `t_document`、`t_document_version`、`t_document_link` / `Document`、`DocumentVersion`、`DocumentLink` | versioned file、scan status、retention/legal hold、scopeの根拠となるlink | 再利用。資格証憑typeとtarget scopeはDG-03決定後に追加 |
| approval | `m_approval_route`、approval request等 / `ApprovalEngineService`、adapter registry | request時route snapshot、金額range inclusive、version CAS、申請者自己承認拒否 | 再利用。request typeと対象adapterだけ追加候補 |
| config | `m_system_config` / `SystemConfig` | 管理画面で変更する各種閾値 | 学習費用閾値を追加するか、route設定だけで表現するかは未決 |

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
| `t_training_enrollment` | plan/courseの申込・開始・完了・cancel、result、certificate link、actual cost | 契約training historyへ直接insertしない |
| `t_skill_tag_alias`（必要な場合のみ） | synonym解決の監査可能なmap | 既存`SkillTagResolver`の未知自動作成だけで同義語を表現しない |
| `t_skill_gap_snapshot`（必要な場合のみ） | 月次close等で再現性が必要な場合のimmutable snapshot | defaultはcurrent sourceのas-of計算。snapshotをsource of truthにしない |

F1のmigration番号は、実装開始時にそのbranchの最新migrationを再確認し、platform-invariantsのlatest+1規約で決める。新specはH2 replay listへMySQL migrationを追加しない。

## 5. 未解決のDG-03

- 資格番号の法務上の分類、暗号化方式、表示可能なrole、export可否。
- `DocumentLink.target_type`を資格取得record単位にするか、既存ENGINEER linkで足りるか。record単位を採るならFileScopeValidationServiceの解決ルールが必要。
- `t_project_position.skills_json`をいつ・誰がcanonical `m_skill_tag`へ解決するか。未知skillを新masterへ自動登録してよいか。
- 需要の過去時点をpositionの現行rowだけで再現できない場合にsnapshot/versionを導入するか。
- 学習費用のthreshold、threshold等値の扱い（候補はinclusive）、承認者chain、0円/実費精算の扱い。
- AIの利用停止、timeout、低信頼、候補拒否時のUIとaudit。人の評価・配置確定をAIに委譲しないことは不変条件。
