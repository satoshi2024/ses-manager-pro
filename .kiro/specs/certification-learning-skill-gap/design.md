# 設計（NF-03 candidate）

> **承認前ガード:** traceabilityは `CANDIDATE`。以下は実装の候補であり、未確定のDG-03を勝手に決定したものではない。F1以降はOwnerが承認したdecisionを反映してから着手する。

## 1. 再利用する境界

- skill: `m_skill_tag`をcanonical IDとし、`t_engineer_skill`／`t_project_skill`を既存sourceとして使用する。
- demand: `t_project_position.skills_json`は現行の入力sourceとして読み、canonical解決結果を新しいmasterへ無断で登録しない。
- document: `DocumentService`と`DocumentLink`を使用し、FileReferenceProviderとFileScopeValidationServiceの両方へ登録できる設計にする。
- approval: `ApprovalTargetAdapter`、registry、既存engineを使用し、request時route snapshot、current version CAS、申請者自己承認拒否を維持する。
- self service: `/api/my/**`のaccount-link解決、change-requestのtarget version/fingerprint、本人scopeを踏襲する。

## 2. 新設候補の論理モデル

最終DDLは承認後に既存migration最新+1で確定する。候補の責務は次のとおり。

| logical table | 必須責務 | 主要な候補属性 | integrity |
|---|---|---|---|
| `m_certification` | 資格master・expiry rule | issuer_key、external_code_key、name_key、identity_key、expiry_type、expiry_months、rule_version、active | tenant＋identity_key unique。code NULLもname identityで一意、表記揺れはalias/merge review |
| `m_certification_alias` | 資格名表記揺れ・merge候補 | certification_id、issuer/name alias、normalized_key、valid period、approved_by | aliasから別masterを作らず、mergeはappend-only eventと人の承認が必要 |
| `t_engineer_certification` | engineerの取得record/current state | engineer_id、certification_id、continuity_group_id、acquired_on、expires_on、expiry_rule_version、certificate_number_ref、record_state、current_flag、revision、version | current_flag=1のcontinuity groupをrow lock＋uniqueで一意化、期限はLocalDate、scope owner固定 |
| `t_certification_event` | append-only state/correction history | certification_record_id、event_type、supersedes_event_id、reason、actor、occurred_at、effective fields、evidence_document_version_id、evidence_hash | event id unique、update/delete禁止、correct/cancel reason必須。CORRECTEDはeventのみ |
| `m_training_course` | course/provider/catalog | provider、name、cost_jpy、period、capacity、active | JPY BigDecimal、capacity非負、期間inclusive |
| `t_training_course_skill` | courseとcanonical skillの関連 | course_id、skill_id、target_level、required_flag | `(course_id,skill_id)` unique、名称保存を正本にしない |
| `t_learning_plan` | goal、deadline、criteria、approval/state | engineer_id、created_by、period、status、approval_request_id、version | creatorのscope、state CAS、criteria必須 |
| `t_learning_plan_skill` | plan target skill | plan_id、skill_id、target_level、target_date | `(plan_id,skill_id)` unique、`m_skill_tag` FK |
| `t_training_enrollment` | plan/courseの実施record | plan_id、course_id、status、started_on、completed_on、score、certificate_document_id、planned_cost_snapshot | state transition、completion条件、DocumentLink必須。actual costは既存経費から導出 |
| `t_training_enrollment_expense` | enrollmentと既存経費の関連 | enrollment_id、expense_request_id、relation_reason | 金額・支払状態を所有せず、既存`t_expense_request`を参照 |
| `t_engineer_skill_event` / `t_project_skill_event` | supply・project skillのeffective history | source row、skill、level、effective_from/to、supersedes、actor、reason、event id | current projectionを過去へ遡及適用しない |
| `t_project_position_event` | staffing positionのas-of snapshot | position fields、skills_json、effective period、source version、event id | 現行positionだけで過去需要を推測しない |
| `t_engineer_skill_assessment` | 本人/上長/HRの評価proposal・確定を分離 | assessment_type、proposed level、state、actor、effective period、reason、version | `t_engineer_skill`のcurrent値へAI/本人が直接書かない |
| `t_learning_decision_event` | 人の確定・利用目的・不利益利用監査 | decision domain、source type/id、human actor、adverse flag、reason、snapshot hash | AI candidateだけでは確定・配置・不利益判断できない |
| `t_skill_tag_alias` | synonym map | normalized_alias、canonical_skill_id、valid period、approved_by | alias unique、変更履歴、unknown自動master化禁止 |
| `t_skill_gap_snapshot` | 再現用snapshot | as_of、demand_version、supply_version、taxonomy_version、result_hash、created_at | monthly close/exportでは必須、immutable。source of truthではない |

番号のraw値を `certificate_number_ref` に格納する方法（暗号化、token、vault reference）はDG-03承認値に従う。ログ、通知、AI payloadには出さない。

## 3. ルール処理

### 3.1 資格

取得申請はpendingで保存し、証憑がCLEANかつscope内で確認された後に、既存approval route（必要な場合）を通してactiveへ遷移する。cancel/correctは旧recordを物理削除せずeventを追加し、effective stateを再計算する。active取得のduplicateはDB uniqueとservice validationの両方で拒否する。

90/60/30通知は `expires_on - configured_days` をLocalDateで計算し、境界日を含む。既送通知は `record_id + semantic_expiry_date + threshold_days + recipient_user_id` のidempotency keyで抑止し、無関係なrevision番号を含めない。expiry dateが変わる訂正・更新だけを新しいsemantic keyとして扱い、同一expiryへの再実行は既存uniqueでdedupeする。

### 3.2 skill gap

rule-based calculatorを常に実行し、次のsourceをcanonical IDへ解決する。

1. `PROJECT`は`t_project_skill_event`、`POSITION`は`t_project_position_event`を正本とする。
2. `COMBINED`は同一project・canonical skillについて`t_project_skill_event`を優先し、position側の追加skillだけを加える。source IDとprecedenceを返す。
3. engineerの`t_engineer_skill_event`とcareer evidenceをas-ofで評価する。

同義語は承認済みaliasだけを利用し、未知skillは`unknown`の結果として返す。現在の`SkillTagResolver`が未知名を`未分類`で作成する挙動は、需要計算の暗黙master化に使わない。AIが有効ならrule結果をinputに候補courseを作り、AIが停止または失敗した場合は候補部分だけ空にしてgap結果を返す。

### 3.3 費用承認

金額は税込JPY `BigDecimal`。planのplanned costは申請時snapshotで、NULLは申請不可、0円は監査eventのみで許可する。actual cost・支払・会計連携は既存`t_expense_request`／会計outboxを正本とし、enrollmentには保存しない。thresholdは`learning.plan`用`m_approval_route.min_amount`を正本とし、minはinclusive、別の`m_system_config`閾値を併存させない。既存approval engineのrequestType、amount snapshot、route snapshotに委譲する。threshold/approverが未設定またはcandidate不在ならfail closed、申請者と承認者の同一判定は拒否する。approved budgetを超える実費は差額を上書きせず、追加expense approvalまたはplan amendmentを要求する。締め済みwork monthは`MonthlyClosingService.assertOpenForUpdate`で拒否し、reopenは既存workflowだけから行う。

### 3.4 as-of source、effective dating、snapshot

既存の`t_engineer_skill`と`t_project_skill`はcurrent projectionとして維持し、同じtransactionでappend-onlyのskill eventを登録する。eventは`effective_from`、`effective_to`、source version、actor、reason、`supersedes_event_id`を持ち、重なる有効期間はrow lockとservice validationで拒否する。`t_project_position`も変更ごとのposition eventに`skills_json`とrequired fieldsをsnapshotする。

as-of queryは**eventのみ**を読み、current projectionへの黙ったfallbackを禁止する（既存書込みフックは`inventory.md` §5.4 / F1-4参照）。

as-of queryは次の順で実行する。

1. requestが指定した`as_of`またはtarget periodをLocalDateとして確定する。
2. supply/demandのeffective eventだけをSQLで抽出し、履歴のない過去をcurrent projectionで補完しない。
3. `PROJECT`はproject skill、`POSITION`はposition skill、`COMBINED`はproject skillを同一canonical skillの優先sourceとしてposition-only skillを追加する。
4. unknown、alias、source ID、precedence、使用versionをresultへ残す。
5. monthly close、export、replayは`snapshot`のsource version/hashを再利用し、snapshotがなければreplay不可として明示する。

feature有効化より前の期間にeventがない場合は`historical_data_unavailable`を返す。これは現在値を過去に遡及適用するより安全なfail-closedであり、backfillを行う場合は開始日・元データ・actor・hashを別途承認する。

### 3.5 資格のnatural identity、renew、訂正

資格masterは入力をtrim、全角正規化、uppercase化した`issuer_key`、`external_code_key`、`name_key`を作り、`identity_key`（codeがある場合はissuer+code、ない場合はissuer+nameのhash）をNOT NULLでuniqueにする。issuer別の同じcodeは別master候補としてmerge reviewへ送り、code NULLの行もname identityで重複を防ぐ。名称aliasは`m_certification_alias`で解決し、silent mergeはしない。

取得recordは`record_state`（DRAFT/SUBMITTED/VERIFIED/ACTIVE/CANCELLED/SUPERSEDED）と`current_flag`を持つ。`EXPIRED`は`as_of > expires_on`から導出し、`CORRECTED`はstateにしない。訂正は同一recordのrevision/eventを追加し、訂正後もACTIVEまたはEXPIRED等を独立に判定する。renewは同じ`continuity_group_id`の新recordとして作り、旧recordをSUPERSEDEDにして履歴を保持する。current_flag=1の行はtenant・engineer・certification・continuity group単位でuniqueにし、cancel/renew/correctは同一group row lock＋version CASで直列化する。

`expires_on`当日はAsia/Tokyoの終日まで有効である。masterのexpiry rule更新は既存recordへ遡及せず、recordの`expiry_rule_version`を使って取得時の計算を再現する。

### 3.6 証憑のtyped resolverとmixed-link policy

`CERTIFICATION_EVIDENCE`文書は`CERTIFICATION_RECORD` linkのみを認可根拠とする。既存DocumentLinkの一般文書向けOR-unionを資格証憑へ適用しない。資格証憑文書に誤って`ENGINEER`等のgeneric linkが混在していても、restricted policyを先に評価し、generic linkはgrantに使わない。資格証憑の登録serviceはgeneric linkを作らず、typed linkがなければverified/download可能状態にしない。

eventには`evidence_document_id`、`evidence_document_version_id`、version hashを保存し、download/export時にDocumentLinkのrecord owner、要求version、tenant、hash、scan status=CLEAN、retention/legal hold、menu＋DataScopeを再検証する。version不一致、unknown stored name、未scan、REJECTED、linkなしはfail closedする。

**FileScopeValidationServiceの必須補正（P1-09）:** 現状の`document-archive`経路は、非管理者でlinkが空なら許可し、管理者はlink検査をbypassする。`CERTIFICATION_EVIDENCE`は`RECEIPT`/`CHANGE_REQUEST_ATTACHMENT`と同様に、**`document-archive`判定より前**の専用分岐とする。

1. `documentTypeOf`が`CERTIFICATION_EVIDENCE`のとき、typed `CERTIFICATION_RECORD` linkが**1件以上**あり、要求record ownerがDataScope内であること。link空・generic `ENGINEER`のみ・未知`target_type`は403。
2. 管理者ロールでも資格証憑はadmin bypassを使わない（restricted policy優先）。
3. eventに保存した`evidence_document_version_id`/hashと要求versionが一致し、scan=CLEANでないと拒否。
4. mixed link（`ENGINEER`＋`CERTIFICATION_RECORD`）ではgeneric linkをgrantに使わず、typed linkだけで判定する。

否定系test（F1-2/B1）: empty-link、ENGINEER-only mixed link、admin bypass、storedName直指定、version/hash不一致、scan未完了。

### 3.7 研修費用と既存経費正本

`t_learning_plan.planned_cost_jpy`はplan申請時の見積snapshot、`t_training_enrollment_expense`はenrollmentと既存`t_expense_request`の関連だけを持つ。金額、approval状態、accounting job、paid stateは`t_expense_request`と既存ExpenseAccounting outboxが所有する。plan approved snapshotはcourse price変更やactual expenseで上書きしない。

amountは税込JPYでNULL不可、0円planは`ZERO_COST_CONFIRMED` eventと人の確認だけを残し、既存expense requestは作成しない。actualのexpenseは既存ExpenseRequestが要求する正のamountを使う。approved plan budgetを超えるactualは、差額expenseの既存approvalまたは新plan amendmentを経由し、無承認でenrollmentへ加算しない。expense日付のwork monthが締め済みなら`MonthlyClosingService.assertOpenForUpdate`で関連・金額・支払状態の変更を拒否する。

**経費締めの共有境界（P1-10、Owner/FinanceがDG-03で選択）:** 現状`ExpenseRequestServiceImpl`（create/update/submit/paid遷移）は`assertOpenForUpdate`を呼ばない。NF-03だけenrollment側で締めると「実費正本は経費」と矛盾する。承認後は次のいずれかを採用し、tasksにファイル名を固定する。

| 選択肢 | 変更対象 | 効果 |
|---|---|---|
| **A（推奨）** | `ExpenseRequestServiceImpl`のamount/日付/関連/支払変更パス全体 | 研修費を含む全経費で締め済み月を拒否。S14経費と単一正本 |
| **B** | 研修専用wrapper＋既存`/api/my/expenses`の更新拒否を別Task | 経費本体は据え置き。二重経路の監査コスト増 |

いずれもF2-2/B1のtestに「締め済み月のamount/関連/支払変更拒否」を含める。reopenは既存月次締めworkflowのみ。

### 3.8 scheduler、timezone、通知対象

通知判定は注入`Clock`を使用する。**Clock正本（P2-04）:** `AppConfig.clock()`の`Clock.systemDefaultZone()`に依存しない。資格期限・90/60/30・as-of LocalDateは次の単一Beanから取得する。

| 項目 | 候補正本 | 備考 |
|---|---|---|
| tenant timezone設定 | `m_system_config`のtenant TZ（存在時） | 未設定時は`Asia/Tokyo` |
| Spring `Clock` Bean | `TenantClock`（新規）または既存`Clock`を`ZoneId.of("Asia/Tokyo")`固定へ変更 | platform-invariantsのTokyo直書き禁止に従い、zoneはBean/設定から解決 |
| テスト | `@MockBean Clock`または固定`Clock.fixed` | JVM default zoneに依存しない |

`StaffingClock`等の個別Jackson TZ設定と混在させず、資格/通知/as-of gapは上記Clockを共有する。

semantic keyは`CERT_EXPIRY:recordId:effectiveExpiryDate:thresholdDays:recipientUserId`とし、record revision、表示文言、無関係な訂正を含めない。expiry dateが変わったときだけ新semantic keyを許可する。

既存`t_notification.dedupe_key`のDB uniqueが複数JVMの最終防衛線になる。insertのduplicateは対象keyを再読して`DEDUPED`として扱い、例外を成功に握りつぶさない。outboxを使う場合はcommit後にclaimし、claim競合とlease回復をテストする。

通知recipientはdispatch時点で解決してuser IDを保存する。退社完了・休職中・無効accountの本人には通常90/60/30を送らず、manager変更後に旧managerへ再送しない。managerはdispatch時点の有効org/DataScope内、HRは既存HR scope内だけを対象にする。account未linkは本人recipientを作らず、manager/HRへの通知可否を同じscope policyで判定する。復職時は過去の90/60/30をbackfillせず、残日数に対する`REINSTATEMENT`を一回だけsemantic keyで発行する。

**通知対象母集団resolver（P2-05）:** dispatch時点で単一の`CertificationNotificationPopulationResolver`（仮称）が判定する。`Engineer.status`単独とNF-01 lifecycle caseの優先を混在させない。

| 条件 | 正本（優先順） | 期限通知recipient | 履歴閲覧（list/detail） |
|---|---|---|---|
| 退社完了 | NF-01 `t_lifecycle_case`で`RESIGNATION`が完了、またはaccount無効化済み | 本人・旧managerへ送らない | role scopeに従い保持可（R4） |
| 休職中 | NF-01 `LEAVE` caseが進行中または休職確定 | 本人へ90/60/30を送らない。manager/HRはscope内のみ | 本人・HRは閲覧可、通知とは分離 |
| 復職 | `LEAVE` case完了＋有効account | 過去分再送せず`REINSTATEMENT` 1件 | 通常populationへ復帰 |
| account未link | `EngineerAccountLink`なし | 本人recipientを作らない | engineer scopeのみ |

`Engineer.status`（Bench等）は表示用。通知除外の最終判定はlifecycle effective state＋account linkとする。

### 3.9 本人評価・上長提案・HR確定

`t_engineer_skill_assessment`はSELF、MANAGER、HR_FINALを別recordとして保存する。SELFは本人提案、MANAGERはレビュー/提案、HR_FINALだけが承認されたeffective skill projectionへ反映可能である。learning planではgoal skill、deadline、attainment criteriaについて本人提出と上長合意を別versionで保持し、合意前に公式skillへ反映しない。

AI候補は既存AI logまたはcandidate recordへprovider/model、生成時刻、as-of、taxonomy version、allowlist、candidate hash、human accept/reject、期限を記録する。AI candidateのacceptはlearning suggestionを作るだけで、assessment、placement、採否、昇格、給与、不利益判断のstate transitionを呼べない。人が確定した場合は`t_learning_decision_event`へdecision domain、source type/id、human actor、reason、snapshot hash、adverse-use flagをappendする。adverse decisionではAI candidateをsole sourceにせず、既存HR/legal workflowと人の明示確定を要求する。

**SELF/MANAGER評価の可視境界（P2-06）:** SELF/MANAGERは公式skill projection・配置・給与・採否に使わない。HR_FINALだけが`t_engineer_skill` currentへ反映可能。

| assessment type | list/detail | export | staffing/sales画面 | 公式projectionへ |
|---|---|---|---|---|
| SELF | 本人・上長（レビュー用）・HR | 本人exportのみ（mask policy） | **出さない**（候補ラベル義務なし） | 不可 |
| MANAGER | 上長・HR | org∩DataScope内のみ | **出さない** | 不可（HR_FINAL待ち） |
| HR_FINAL | HR/admin | HR scope | 公式skillとして既存画面へ | 可（human actor必須） |

SELFをstaffing gap・配置候補・commission計算の入力に使うことは禁止。異議申立てフローはOwner/HR承認後に別specへ委譲可。

## 4. Decision tables

### 4.1 資格番号PII分類

| data | candidate classification | list/detail | export | AI/log | unresolved decision |
|---|---|---|---|---|---|
| 資格番号raw | 個人情報・restricted（特定個人情報該当性は法務確認） | engineer本人と`certification.pii.view`を持つHR/adminのみfull候補、それ以外mask | rawはomit、maskedもscope内のみ | deny | Owner/法務承認、暗号化/token方式、retention |
| 資格番号masked | derived restricted | scope内のみ | scope内のみ | deny | full valueを再構成できないmask方式 |
| issuer/code/name | business data（番号と結合時はrestricted） | scope内 | scope内 | allowlist候補 | 外部コードの公開性 |
| evidence metadata | business＋個人関連情報 | link scope内 | metadataのみ候補 | deny raw file | export項目、保管期間 |

### 4.2 証憑DocumentLinkとscope

| operation | candidate link | required checks | deny condition | unresolved decision |
|---|---|---|---|---|
| upload/register | `target_type=CERTIFICATION_RECORD`、target_id=取得record | owner engineer scope、`CERTIFICATION_EVIDENCE`、DocumentService、CLEAN、FileReferenceProvider登録 | `ENGINEER` linkだけ、unknown target、未scan、scope外 | 正式enumはOwner承認対象 |
| list/detail | recordに紐づくtyped DocumentLink | same effective population、restricted policy、legal hold/retention、record state | generic linkだけ、linkなし、mixed linkでrestrictedを迂回 | 複数証憑のprimary ruleはlatest verified exact version |
| download | typed linkからownerとexact versionを解決 | FileScopeValidationService専用分岐（`document-archive`より前）、eventのdocument_version_id/hashと完全一致、scan=CLEAN、menu＋DataScope | stored name unknown、CLEAN以外、scope外、version不一致、**link空、ENGINEER-only、admin bypass** | resolverの正式実装はOwner承認対象 |
| export | raw file・storage key・document IDは出さずmetadata/link statusだけ | UIと同一population/scope | UIで見えないrecord/file | Ownerが監査用IDを別途許可するか |

### 4.3 skill taxonomy・同義語・未知skill

| input | resolve rule候補 | result | master mutation | audit |
|---|---|---|---|---|
| canonical skill ID | `m_skill_tag`存在確認 | canonical | none | source ID |
| canonical name | trim/fullwidth/uppercase＋exact | canonical | none | normalized input |
| approved synonym | `t_skill_tag_alias`のvalid期間内map | canonical | none | alias ID/version |
| unknown demand tag | no implicit `resolveOrCreate` | unknown gap（説明可能） | default禁止 | raw＋normalized＋as-of |
| unknown self-entered skill | candidate suggestionまたはHR review | pending/unresolved | HR承認後のみmaster候補 | actor/reason |

### 4.4 demand as-of・案件期間

| query | candidate as-of rule | interval | no-data result | unresolved decision |
|---|---|---|---|---|
| point-in-time | `start <= as_of <= end`のeffective event | 両端inclusive | `historical_data_unavailable`（current fallback禁止） | backfill開始日だけOwner承認 |
| target period | `position.start <= period.to` かつ `position.end >= period.from` | overlap inclusive | empty gap rows、0件 | period中のrequired level changeはevent分割 |
| project skill | `t_project_skill_event`とtarget periodのintersection | project source | 0 required skills | PROJECTでは正規project skillを優先 |
| staffing position | `t_project_position_event`のstatus/as-ofとskills_json | position period inclusive | unresolved/unknownを明示 | POSITIONではpositionを正本 |
| COMBINED | 同一project・canonical skillはproject skill優先、position-only追加 | source ID/precedenceを保存 | source欠落をcurrent補完しない | precedenceは本candidateで固定、Owner承認対象 |
| monthly close/replay | `t_skill_gap_snapshot`をsnapshot時点のsource/taxonomy versionで再現 | close period | snapshotなしはreplay不可 | snapshot導入migrationはOwner承認対象 |

### 4.5 費用承認

| amount/state | approval candidate | applicant self-approval | failure behavior | unresolved decision |
|---|---|---|---|---|
| NULL | 申請不可、amount_snapshotへ変換しない | N/A | validation error、fail closed | — |
| 0円 | plan監査eventのみ、expense requestを作らない | N/A | zero-cost reason必須 | zero-cost evidence policy |
| threshold−1 | approval不要候補、監査eventを残す | N/A | route設定不在ならfail closed | `m_approval_route.min_amount`を正本候補 |
| thresholdと等しい | approval required | deny | route snapshot | `m_approval_route.min_amount` inclusive |
| threshold＋1 | approval required | deny | candidate不在はfail closed | chain（org manager→finance/admin候補） |
| approved budget超のactual | 差額expenseまたはplan amendmentを新規approval | requesterは承認不可 | approved snapshotを上書きしない | tolerance=0候補 |
| closed work month | expense金額/関連/支払状態変更不可 | N/A | `assertOpenForUpdate`拒否（`ExpenseRequestServiceImpl`共有化が前提。design §3.7選択肢A/B） | reopen権限/理由 |

### 4.6 AI候補と人の確定境界

| decision | rule-based | AI | human | audit/output |
|---|---|---|---|---|
| gap detection | primary・always available | supplement不可欠ではない | review可能 | source/as-of/unknownを保存 |
| course suggestion | deterministic matching | candidate ranking/explanation | accept/reject/edit | provider status、model、prompt allowlist |
| skill level evaluation | existing evidence・policy | suggest only | self proposal→manager proposal→HR final | assessment type、actor、reason、effective date |
| placement/assignment | existing business approval | prohibited | authorized workflow | no AI final action |
| adverse personnel decision | not applicable | prohibited as sole source | explicit human/legal policy process | `adverse_use_flag`、AI source禁止 |
| AI unavailable | return gap | empty/degraded | can continue | timeout/error/auditを保存、gapは欠落させない |
| SELF/MANAGER assessment表示 | 公式skillとは別record | suggest only | HR/manager review | staffing/sales/exportへ出さない。公式はHR_FINALのみ |

## 5. 必須のdecision/time/scope/state tables

### 5.1 time・as-of table

| object | time field | effective rule | correction |
|---|---|---|---|
| certification | acquired/expires/effective version | LocalDate、expiry boundary inclusive、expiry rule version snapshot | event追加＋old version参照 |
| demand | position/project skill effective events | null endはwindow end、両端inclusive | history欠落はunavailable、snapshotでreplay |
| engineer skill | skill eventのeffective period | as-of日で有効なeventのみ | current projectionとeventを同一transactionで更新 |
| learning | plan target/enrollment period | target period外はgap対象外候補 | cancel/correctはCAS＋event |
| notification | threshold date＋recipient | 90/60/30当日含む候補 | idempotency keyで再送制御 |

### 5.2 actor×operation×population table

| actor | list/detail/gap | create/update | evidence download | export |
|---|---|---|---|---|
| engineer | self only、退職/休職の履歴は本人policyに従い保持 | self plan/acquisition request | self own typed link if CLEAN | same self population、番号policy |
| manager | org∩DataScope（effective date） | managed plan/review | typed link scope、番号mask | same population、番号mask/omit |
| HR | existing HR scope | master/verification/review、PII permission | scope内、typed link、PII policy | same population、PII policy |
| admin | all | all admin operations | all allowed by legal hold/scope | same population、PII policy |
| sales/other | existing DataScope only if feature allowed | no certification finalization | no raw number、link scope | no scope expansion |
| scheduler/AI | no user population expansion | notification/candidate only | no download | no export |

### 5.3 state machine×conflict table

| aggregate | normal states | allowed conflict handling | forbidden shortcut |
|---|---|---|---|
| certification | DRAFT→SUBMITTED→VERIFIED/REJECTED→ACTIVE/CANCELLED/SUPERSEDED; EXPIREDはas-of導出 | revision/CAS、continuity group unique、append-only event | `CORRECTED`をcurrent statusにする、delete active evidence、direct active |
| learning plan | DRAFT→SUBMITTED→APPROVED/REJECTED→IN_PROGRESS→COMPLETED/CANCELLED | approval snapshot＋target version CAS | completion before approval |
| enrollment | PLANNED→STARTED→COMPLETED/CANCELLED | one active enrollment per policy、CAS | duplicate completion/event |
| document | RECEIVED→SCANNING→CLEAN/REJECTED＋legal hold | DocumentService/version/scan check | raw path、unknown file allow |
| AI candidate | GENERATED→ACCEPTED/REJECTED/EXPIRED | human actor and reason | AI transition to evaluation/placement |

## 6. UI/API response shaping

list/detail/exportは同じeffective population queryを共有し、field-level maskingだけをresponse DTOで適用する。detailからlistへ別scopeで補完しない。本人APIはaccount linkからengineerを解決し、manager/HR APIはserver-side DataScopeとorg intersectionを適用する。CSV/XLSX/PDF/downloadはUIの表示可否と同じsource query・DocumentLink scopeを使用する。

## 7. migration・test同期（承認後）

新規migrationは実装時の最新+1とし、適用済みmigrationは編集しない。V1、専用H2 schema、関連entityの同期、MySQL smoke testを同じTaskで扱う。新specのMySQL migrationをtest `schema-locations` replayへ追加しない。F1/F2のmandatory testは`tasks.md`に固定し、Demo evidenceをcompletion matrixへ記録する。
