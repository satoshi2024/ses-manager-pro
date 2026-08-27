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
| `m_certification` | 資格master・expiry rule | issuer、external_code、name、expiry_type、expiry_months、active | tenant＋external_code unique、expiry ruleの範囲検証 |
| `t_engineer_certification` | engineerの取得record/current state | engineer_id、certification_id、acquired_on、expires_on、certificate_number_ref、status、version | activeな重複取得unique、期限はLocalDate、scope owner固定 |
| `t_certification_event` | append-only state/correction history | certification_record_id、event_type、supersedes_event_id、reason、actor、occurred_at、payload hash | event id unique、update/delete禁止、correct/cancel reason必須 |
| `m_training_course` | course/provider/catalog | provider、name、cost_jpy、period、capacity、active | JPY BigDecimal、capacity非負、期間inclusive |
| `t_training_course_skill` | courseとcanonical skillの関連 | course_id、skill_id、target_level、required_flag | `(course_id,skill_id)` unique、名称保存を正本にしない |
| `t_learning_plan` | goal、deadline、criteria、approval/state | engineer_id、created_by、period、status、approval_request_id、version | creatorのscope、state CAS、criteria必須 |
| `t_learning_plan_skill` | plan target skill | plan_id、skill_id、target_level、target_date | `(plan_id,skill_id)` unique、`m_skill_tag` FK |
| `t_training_enrollment` | plan/courseの実施record | plan_id、course_id、status、started_on、completed_on、score、actual_cost_jpy、certificate_document_id | state transition、completion条件、DocumentLink必須 |
| `t_skill_tag_alias`（optional） | synonym map | normalized_alias、canonical_skill_id、valid period、approved_by | alias unique、変更履歴、unknown自動master化禁止 |
| `t_skill_gap_snapshot`（optional） | 再現用snapshot | as_of、demand_version、engineer_skill_version、result、created_at | immutable。source of truthではない |

番号のraw値を `certificate_number_ref` に格納する方法（暗号化、token、vault reference）はDG-03承認値に従う。ログ、通知、AI payloadには出さない。

## 3. ルール処理

### 3.1 資格

取得申請はpendingで保存し、証憑がCLEANかつscope内で確認された後に、既存approval route（必要な場合）を通してactiveへ遷移する。cancel/correctは旧recordを物理削除せずeventを追加し、effective stateを再計算する。active取得のduplicateはDB uniqueとservice validationの両方で拒否する。

90/60/30通知は `expires_on - configured_days` をLocalDateで計算し、境界日を含む。既送通知は対象record version、threshold、recipient、periodのidempotency keyで抑止する。設定変更、訂正、取消は新versionとして再計算する。

### 3.2 skill gap

rule-based calculatorを常に実行し、次のsourceをcanonical IDへ解決する。

1. target period内の `t_project_skill`。
2. staffing positionの `skills_json`（start/end inclusive、status、as-of ruleを適用）。
3. engineerのas-of skill/career evidence。

同義語は承認済みaliasだけを利用し、未知skillは`unknown`の結果として返す。現在の`SkillTagResolver`が未知名を`未分類`で作成する挙動は、需要計算の暗黙master化に使わない。AIが有効ならrule結果をinputに候補courseを作り、AIが停止または失敗した場合は候補部分だけ空にしてgap結果を返す。

### 3.3 費用承認

金額はJPY `BigDecimal`、threshold境界はinclusiveを候補とする。実際の承認判定は既存approval engineのrequestType、amount snapshot、route snapshotに委譲する。threshold/approverが未設定またはcandidate不在ならfail closed、申請者と承認者の同一判定は拒否する。閾値を `m_system_config` で持つか、approval routeのmin/maxを設定画面の正本とするかはDG-03で決める。

## 4. Decision tables

### 4.1 資格番号PII分類

| data | candidate classification | list/detail | export | AI/log | unresolved decision |
|---|---|---|---|---|---|
| 資格番号raw | 個人情報・restricted（特定個人情報該当性は法務確認） | engineer本人と権限付HR/adminのみfull候補、それ以外mask | default omitまたはmask | deny | full reveal role、暗号化/token方式、retention |
| 資格番号masked | derived restricted | scope内のみ | scope内のみ | deny | mask形式、検索可否 |
| issuer/code/name | business data（番号と結合時はrestricted） | scope内 | scope内 | allowlist候補 | 外部コードの公開性 |
| evidence metadata | business＋個人関連情報 | link scope内 | metadataのみ候補 | deny raw file | export項目、保管期間 |

### 4.2 証憑DocumentLinkとscope

| operation | candidate link | required checks | deny condition | unresolved decision |
|---|---|---|---|---|
| upload/register | `target_type=ENGINEER_CERTIFICATION`、target_id=取得record候補 | owner engineer scope、DocumentService、CLEAN、FileReferenceProvider登録 | unknown target、未scan、scope外 | 既存ENGINEER linkで代替するか、record resolver追加か |
| list/detail | recordに紐づくDocumentLink union | same effective population、legal hold/retention、record state | linkなしを証憑verifiedにしない | 複数証憑のprimary rule |
| download | linkからownerを解決 | FileScopeValidationService、scan=CLEAN、menu＋DataScope | stored name unknown、CLEAN以外、scope外 | target typeの正式enum/resolve実装 |
| export | raw fileは出さずmetadata/link status候補 | UIと同一population/scope | UIで見えないrecord/file | exportにdocument IDを含めるか |

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
| point-in-time | `start_date <= as_of <= end_date`、null endはopen | 両端inclusive | empty demand＋diagnostic | position history/versionの要否 |
| target period | `position.start <= period.to` かつ `position.end >= period.from` | overlap inclusive | empty gap rows、0件 | period中のrequired level change |
| project skill | project validityとtarget periodのintersection | existing project source | 0 required skills | source precedence |
| staffing position | status/as-ofとskills_jsonを評価 | position period inclusive | unresolved/unknownを明示 | free textをcanonicalizeする責任者 |
| monthly close/replay | immutable snapshotがある場合のみsnapshot優先候補 | close period | snapshotなしはcurrent read不可候補 | snapshot導入時期 |

### 4.5 費用承認

| amount/state | approval candidate | applicant self-approval | failure behavior | unresolved decision |
|---|---|---|---|---|
| 0円またはthreshold未満 | no approval候補（監査eventは残す） | N/A | config invalidならfail closed候補 | 0円のevidence/approval要否 |
| thresholdと等しい | approval required候補 | deny | route snapshot | threshold source |
| threshold超 | approval required | deny | candidate不在はfail closed | chain（org manager→finance/admin候補） |
| approved | completion transition可 | requesterは承認不可 | version CAS | route変更後の既存request |
| rejected/withdrawn | completion不可 | requesterの再申請条件 | explicit transition | resubmit/reset方針 |

### 4.6 AI候補と人の確定境界

| decision | rule-based | AI | human | audit/output |
|---|---|---|---|---|
| gap detection | primary・always available | supplement不可欠ではない | review可能 | source/as-of/unknownを保存 |
| course suggestion | deterministic matching | candidate ranking/explanation | accept/reject/edit | provider status、model、prompt allowlist |
| skill level evaluation | existing evidence・policy | suggest only | engineer/manager/HRが確定 | actor、reason、effective date |
| placement/assignment | existing business approval | prohibited | authorized workflow | no AI final action |
| adverse personnel decision | not applicable | prohibited | explicit human policy process | AI result cannot be sole basis |
| AI unavailable | return gap | empty/degraded | can continue | outage/error not swallowed |

## 5. 必須のdecision/time/scope/state tables

### 5.1 time・as-of table

| object | time field | effective rule | correction |
|---|---|---|---|
| certification | acquired/expires/effective version | LocalDate、expiry boundary inclusive | event追加＋old version参照 |
| demand | position start/end、project skill validity | null endはwindow end、両端inclusive | source version/snapshot decision required |
| engineer skill | current record＋career period | as-of日で有効なrecord | approval後のeffective dateを保存 |
| learning | plan target/enrollment period | target period外はgap対象外候補 | cancel/correctはCAS＋event |
| notification | threshold date＋recipient | 90/60/30当日含む候補 | idempotency keyで再送制御 |

### 5.2 actor×operation×population table

| actor | list/detail/gap | create/update | evidence download | export |
|---|---|---|---|---|
| engineer | self only | self plan/acquisition request | self own link if CLEAN | self own masked/full policy pending |
| manager | org∩DataScope | managed plan/review | linked engineer scope、番号mask | same population、番号mask/omit |
| HR | existing HR scope | master/verification/review | scope内、PII policy | same population、PII policy |
| admin | all | all admin operations | all allowed by legal hold/scope | same population、PII policy |
| sales/other | existing DataScope only if feature allowed | no certification finalization | no raw number、link scope | no scope expansion |
| scheduler/AI | no user population expansion | notification/candidate only | no download | no export |

### 5.3 state machine×conflict table

| aggregate | normal states | allowed conflict handling | forbidden shortcut |
|---|---|---|---|
| certification | DRAFT→SUBMITTED→VERIFIED/REJECTED→ACTIVE→EXPIRED/CANCELLED/CORRECTED | version CAS、duplicate unique、append-only event | delete active evidence or direct active by client |
| learning plan | DRAFT→SUBMITTED→APPROVED/REJECTED→IN_PROGRESS→COMPLETED/CANCELLED | approval snapshot＋target version CAS | completion before approval |
| enrollment | PLANNED→STARTED→COMPLETED/CANCELLED | one active enrollment per policy、CAS | duplicate completion/event |
| document | RECEIVED→SCANNING→CLEAN/REJECTED＋legal hold | DocumentService/version/scan check | raw path、unknown file allow |
| AI candidate | GENERATED→ACCEPTED/REJECTED/EXPIRED | human actor and reason | AI transition to evaluation/placement |

## 6. UI/API response shaping

list/detail/exportは同じeffective population queryを共有し、field-level maskingだけをresponse DTOで適用する。detailからlistへ別scopeで補完しない。本人APIはaccount linkからengineerを解決し、manager/HR APIはserver-side DataScopeとorg intersectionを適用する。CSV/XLSX/PDF/downloadはUIの表示可否と同じsource query・DocumentLink scopeを使用する。

## 7. migration・test同期（承認後）

新規migrationは実装時の最新+1とし、適用済みmigrationは編集しない。V1、専用H2 schema、関連entityの同期、MySQL smoke testを同じTaskで扱う。新specのMySQL migrationをtest `schema-locations` replayへ追加しない。F1/F2のmandatory testは`tasks.md`に固定し、Demo evidenceをcompletion matrixへ記録する。
