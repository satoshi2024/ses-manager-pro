# G2 gate decision delta — R19-P1-01 / T066

> 状態: `ACCEPTED_FOR_IMPLEMENTATION / R22_SCHEMA_REWORK_IN_PROGRESS`
>
> 発注者の2026-08-11 docs-only着手指示を、実装時の推測が残らない粒度へ具体化した正本候補である。
> R10はHead `3f7cc518e928c02f8eba7c08f368beb5d8f33526`を独立Reviewし、`ACCEPTED_FOR_IMPLEMENTATION`を明示した。
> 以後の実装は本deltaの決定とR22修正契約に限定する。R19-P1-01を実装担当自身がcloseしない。
> T066は未完了、S10は`IN PROGRESS / FAIL`、S12は`NOT READY`を維持する。

## 0. Decision IDと優先順位

| decision ID | 確定する論点 |
|---|---|
| `G2-SCOPE-01` | tenant mapping、workplace assignment、contractからのworkplace解決、G0との境界 |
| `G2-LIFECYCLE-01` | `DRAFT -> PROVISIONAL_REVIEWED -> ACTIVE -> SUPERSEDED`、freeze、CAS |
| `G2-DYNAMIC-REVIEW-01` | 動的reviewer type、requirement group、AND/OR/minimum評価 |
| `G2-EVENT-01` | approval/external review/status event reducer、append-only、asOf |
| `G2-HASH-01` | mapping/policy/gateの3 hashとcanonical payload |
| `G2-ACTIVE-01` | tenant ACTIVEとworkplace delivery authorizationの分離、ACTIVE transaction |
| `G2-DELIVERY-01` | formal generate、preview、delivery snapshot、過去download |
| `G2-SECURITY-01` | `/compliance-gate` UI/API/action permission、evidence DTO |
| `G2-HISTORY-01` | `GATE-T066-HISTORY`のtracked P2 / production release gateへの分離 |
| `G2-MIGRATION-01` | common V99/V101、migration-dev V100、S10 V102、S12〜S17 V103〜V108 |
| `G2-BROWSER-01` | Phase A previewとPhase B formal deliveryのbrowser証跡 |
| `G2-IDEMPOTENCY-01` | state-changing operation共通ledger、key scope、request hash、PROCESSING/SUCCEEDED/FAILED、retry結果 |
| `G2-EFFECTIVE-PERIOD-01` | mapping inclusive period、future/expired version、SUPERSEDED時のgate hash規則 |
| `G2-DELIVERY-IMMUTABILITY-01` | 交付時のimmutable FULL/MASK/LIMITED renditionとsnapshot入力 |
| `G2-CREDENTIAL-CRYPTO-01` | credential専用暗号化、key version/rotation、prod fail-closed |
| `G2-SOURCE-FREEZE-01` | mapping sourceのDRAFT INSERT/UPDATE/DELETEとfreeze後direct INSERT/UPDATE/DELETE拒否 |

本書は本spec固有の決定であり、`platform-invariants.md`を再定義しない。相違するのは
`t_compliance_responsible_assignment`の期間だけである。

### 0.1 逸脱と根拠

`platform-invariants.md` §1は通常の有効期間を両端inclusiveとする。本assignmentだけは発注者決定により
`[effective_from, effective_to)`の半開区間とする。交代時の`old.effective_to == new.effective_from`を重複なしで
許可し、同一瞬間に有効な責任者を1人へ決定できるためである。その他の既定解はそのまま適用する。

## 1. Scope、tenant、G0

### 1.1 scopeの正本

- compliance mappingはtenant scopeである。
- `COMPLIANCE_RESPONSIBLE` assignmentはworkplace scopeである。
- mapping ACTIVEはtenant-levelのmapping/source/review policyが有効であることだけを表す。
- 本番generate/deliveryのworkplaceはrequest値や`t_contract`の曖昧な値から決めない。serverがtenant境界内で
  `t_contract_compliance_profile.contract_id = :contractId`を読み、その`workplace_id`を唯一の正として解決する。
- contract/workplaceの双方をNULL可能にした二重scopeは作らない。assignmentに`contract_id`は置かず、
  `workplace_id`はNOT NULLとする。
- workplace Aのassignment/approvalはworkplace Bをauthorizationしない。
- assignment交代後、旧actorのapprovalは新規deliveryへ流用しない。新actor本人のapprovalまでfail-closedとする。
- 過去に正常交付されたdeliveryは、assignment交代、mappingのSUPERSEDED化、external reviewの期限切れ・REVOKE後も
  当時の原版をdownloadできる。ただし既存document ACL、tenant/data/organization/file scope、scan=CLEANは毎回検証する。

### 1.2 現行独立DBでのtenant ID

- G0の現行正本は顧客ごとの独立DBであり、共有DB SaaSの全表tenant化は延期中である。
- 本機能のdeployment tenant IDはserver設定`app.security.oidc.tenant-id`（環境変数`OIDC_TENANT_ID`、
  未指定時`default`）から取得する。request body/query/headerの`tenantId`は受理せず、server値で上書きする。
- 現行G0の独立DBではtenantのtimezoneはdeployment設定`spring.jackson.time-zone`から取得する（現行値は設定ファイルの
  `Asia/Tokyo`）。これはコードへの直書きではなくtenant/deployment設定であり、欠落・空・不正時はJVM defaultへfallbackしない。
  共有DB化でtenant別timezoneが必要になった場合はG0再決定と共通timezone resolverを先行させ、S10で先取りしない。
- G2の9 domain table + 共通operation ledgerとdelivery更新は将来互換の`tenant_id VARCHAR(100) NOT NULL`を持つが、`m_tenant`は作成せず
  tenant FKも追加しない。これはS10だけで共有DB architectureを再開しないための意図的境界である。
- SQLは全て`tenant_id = :deploymentTenantId`をpredicateの先頭へ置く。child IDだけで取得せず、mapping、group、event、
  assignment、deliveryをtenantとの組で解決する。許可集合が空ならSQLで0件とする。
- 現行`m_workplace`は論理`tenant_id`を持つが`m_tenant` FKはなく、`sys_user`はtenant FK/tenant columnを持たない。
  assignment保存時は、workplaceを`id + deploymentTenantId`で解決し、userは同じ独立DB内のactive userであることと
  role eligibilityを検証する。共有DB向けuser複合FKをS10で先取りしない。
- tenant A/B fixtureは、異なる論理tenantのG2行がSQL境界で相互参照できないことを示す防御的testであり、
  共有DB production対応完了を意味しない。共有DB化にはG0再決定と全表/認証/job/cache/file/backupの再設計が必要である。

## 2. Lifecycle、freeze、assignment

### 2.1 mapping lifecycle

| current | transition | caller | 必須条件 | result |
|---|---|---|---|---|
| `DRAFT` | `DRAFT -> PROVISIONAL_REVIEWED` | 管理者 | source completeness、mapping 96 stable ID、非空かつ整合したreview policy、L0、独立Review証跡、mapping/policy hash再計算一致 | mapping/source/policyをfreezeしstatus event INSERT |
| `PROVISIONAL_REVIEWED` | `-> ACTIVE` | 管理者 | §8のACTIVE transaction、mapping effective period内（inclusive） | tenant mapping ACTIVE、旧ACTIVEがあればSUPERSEDED |
| `PROVISIONAL_REVIEWED` | `-> SUPERSEDED` | 管理者 | reason、expected version、gate不要 | 未使用versionを終了しstatus event INSERT。gate hashはNULL |
| `ACTIVE` | `-> SUPERSEDED` | ACTIVE transaction | 新version ACTIVE化または明示終了、expected version | active_slotをNULL、status event INSERT |
| `SUPERSEDED` | なし | — | terminal | 再ACTIVE化・編集不可。新versionを作る |

- `DRAFT`中だけmapping本体、source、review requirement group/typeを編集できる。
- mapping versionの`effective_from/effective_to`はplatform既定どおりDATEの両端inclusive、`effective_to=NULL`は無期限とする。
  transaction開始時に1回だけ確定したasOf instantを`spring.jackson.time-zone`のdeployment ZoneIdへ変換し、そのlocal dateをeffective period比較へ使う。
  propertyが欠落・空・不正ならJVM defaultへfallbackせず、G2 gate operationを`409 GATE_TIMEZONE_UNAVAILABLE`でfail-closedする。
- mapping version periodはPROVISIONAL_REVIEWED以降不変である。通常は同一tenant/mapping_codeのDRAFT/PROVISIONAL同士の期間重複を
  `new_from <= existing_to AND existing_from <= new_to`で拒否する。ただし、現在ACTIVEで`effective_to=NULL`の版と、
  `effective_from > asOf local date`の将来DRAFT/PROVISIONAL版の組だけは、法改定のscheduleとして重複保存を許可する。
  同一mapping_codeに将来DRAFT/PROVISIONAL候補を2件以上作ること、将来候補同士の重複、将来候補のeffective_from以前のACTIVE化は拒否する。
  future candidateは`future_slot=1`を設定し、`UNIQUE(tenant_id,mapping_code,future_slot)`の競合で異なるidempotency keyの同時作成を1件へ収束させる。
- `PROVISIONAL_REVIEWED -> ACTIVE`はtransactionのasOfが`effective_from <= asOf`かつ
  (`effective_to IS NULL`または`asOf <= effective_to`)を満たす場合だけ許可する。future versionはeffective_from前にACTIVE化できず、
  満了後のACTIVEはformal generate/deliveryの対象にならずfail-closedとする。
- future versionは旧ACTIVEと同時にPROVISIONALで存在できる。旧ACTIVEの`effective_to=NULL`とmapping_hashは変更しない。
  effective date当日以降に新versionをACTIVE化したtransactionだけが旧ACTIVEをSUPERSEDEDにする。
  current `active_slot`は現在状態の一意性だけを示し、asOf過去日のmapping選択はstatus event reducerで行う。
  status eventの`occurred_at <= asOf`かつeffective period内のACTIVEを1件だけ選び、同時刻はevent ID順で解決する。
  自動schedulerによる状態変更は行わず、空白期間は交付を拒否する。
- `PROVISIONAL_REVIEWED`以降はmapping/source/policyをINSERT/UPDATE/DELETEしない。変更は新しいmapping versionを作る。
- 既存versionの`mapping_hash`/`review_policy_hash`を書き換えない。
- reviewer type masterの表示名変更・disabled化はfreeze済みpolicy snapshotと過去eventを変更しない。
- disabled typeは新しいDRAFT policyと新しいexternal review登録で選択できない。
- type codeは一度でもpolicy/eventから参照された後は変更不可。参照済みtypeは物理削除せずdisabled化する。
- 過去reviewを無効化する操作はmaster変更ではなくexternal review `REVOKED` eventで表す。

### 2.2 assignment期間と競合

- `effective_from`はinclusive、`effective_to`はexclusive、NULLは無限未来である。
- asOfで有効な条件は`effective_from <= :asOf AND (effective_to IS NULL OR :asOf < effective_to)`。
- overlapは`new_from < existing_to AND existing_from < new_to`。NULL endは無限未来として比較する。
- `old.effective_to == new.effective_from`の隣接は許可する。
- 同一tenant/workplace/asOfで有効なassignmentは1件だけである。
- assignment作成/終了は`m_workplace`の対象行を`SELECT ... FOR UPDATE`してanchor lockを取得し、その後に
  tenant/workplace全assignmentへoverlap SQLを実行する。`active_slot` UNIQUEだけへ依存しない。
- `active_slot=1`は`effective_to IS NULL`のassignmentだけ、それ以外はNULLとする。
  `UNIQUE(tenant_id, workplace_id, active_slot)`はopen-ended二重登録をDBでも拒否する。
- assignment userはactiveかつ内部roleが`管理者`/`HR`/`マネージャー`のいずれかでなければならない。
- 管理者が作成・終了する。終了は既存行の期間/statusを管理対象として更新し、`ended_by/end_reason/version`を必須にする。
  assignment自体はevent tableではないためCAS更新を許すが、過去approval eventのactor snapshotは変更しない。

## 3. Dynamic external reviewer type / policy

### 3.1 固定してよいもの、固定してはいけないもの

- reviewer typeはtenant管理の動的masterである。「社会保険労務士」「弁護士」「税理士」等は入力例にすぎず、
  Java enum/static Set、DB CHECK、固定select option、業務seedへ置かない。
- `m_system_config`はtenant/FK/version/policy group/snapshotを持たないため本用途へ使用しない。
- 専門家個人masterは最低必須範囲に含めない。実review登録時に管理者がtype、氏名、所属、資格・登録情報、
  reviewed_at、valid_until、evidence document/versionを記録する。
- 架空資格、架空専門家、仮review、仮approval、仮actorのseed/backfillは禁止する。

### 3.2 policy reducer

1. requirement group同士はANDである。
2. 同一group内の許容reviewer typeはORである。
3. 各groupは`minimum_distinct_reviewers >= 1`を持つ。
4. external review eventはgroupを明示し、別groupへ自動流用しない。
5. group内で§6.3のreviewer identity hashが同じreviewerは、event件数に関係なく1人と数える。
6. policyが空、groupが空、group内typeが空、minimumが1未満、snapshot/hash不整合ならfail-closedである。
7. group rowをminimumの唯一の保存先とし、同一tenant/mapping/group codeをUNIQUEにする。
   type join rowへminimumを重複保存しない。
8. 例: 同一groupへtype A/B、minimum=1ならAまたはBの1名で成立する。
9. 例: group Aにtype A/minimum=1、group Bにtype B/minimum=1なら両groupの成立が必要である。

## 4. 9 domain table contract + cross-cutting operation ledger（V102候補、現時点ではDDLを作らない）

共通規則: PKは`BIGINT id`、時刻は`DATETIME(6)`、hashは`CHAR(64)` lowercase hex、actor/user/workplace/documentは
既存PKへFKを張る。tenant parentが存在しないためtenant FKは作らない。tenant境界はNOT NULL、複合UNIQUE/index、
service再解決、SQL predicateで強制する。各parentは`UNIQUE(tenant_id,id)`を持ち、G2 childは可能な限り
`(tenant_id,parent_id)`複合FKを使用する。

### 4.0 `t_compliance_operation_ledger`（state-changing operation共通制御table）

9つのG2 domain tableとは別に、state-changing operationの再送結果を固定する共通control tableを1つ持つ。
既存V84の`t_compliance_snapshot_operation`はsnapshot専用契約なので流用・変更しない。

columns:
`tenant_id`, `operation_id`, `operation_type`, `idempotency_key`, `request_hash`, `state`, `retryable_flag`,
`attempt_count`, `started_at`, `lease_until`, `finished_at`, `result_reference_type`, `result_reference_id`,
`result_reference_version`, `result_summary_canonical`, `result_http_status`, `result_hash`, `failure_code`,
`correlation_id`, `expires_at`, `version`。

claim INSERTはserver-side mapperが`state=PROCESSING`、`retryable_flag=0`、`attempt_count=1`、`version=0`、
`deleted_flag=0`を固定し、`finished_at`、`failure_code`、`result_reference_*`、`result_summary_canonical`、
`result_http_status`、`result_hash`を全てNULLで開始する。MySQL BEFORE INSERT triggerはentity/requestから渡された
初期state・retryable・attempt・version・結果列を信用せず、同じclaim行列をDB境界でも拒否する。
`SUCCEEDED`は`finished_at`非NULL、`failure_code` NULL、`result_summary_canonical`・`result_http_status`・`result_hash`
全て非NULL、`PROCESSING`は`finished_at`・`failure_code`・result/reference全列NULL、`FAILED`は`finished_at`・
`failure_code`非NULLかつresult/reference全列NULLとする。`completeFailureCas`は`finished_at`と`failure_code`を必須にし、
result/referenceをNULLで保存する。`result_summary_canonical`と`result_reference_*`は成功時だけ設定する。
現行運用では全rowの`expires_at`をNULL（永久保持）とする。保持期間短縮、purge、key再利用は別decisionなしに許可しない。

`operation_id`はserverがledger claim前に生成するUUIDv4文字列（canonical lowercase、36文字）であり、同一operationのeventへ保存する。

- `operation_type`は次のaction codeだけを許可する: `MAPPING_DRAFT_UPSERT`, `MAPPING_PROVISIONAL_REVIEW`,
  `ASSIGNMENT_CREATE`, `ASSIGNMENT_END`, `MAPPING_ACTIVE`, `MAPPING_SUPERSEDE`, `INTERNAL_APPROVAL`,
  `EXTERNAL_REVIEW`, `EXTERNAL_REVIEW_REVOKE`, `DELIVERY_GENERATE`, `REVIEWER_TYPE_CREATE`,
  `REVIEWER_TYPE_UPDATE`, `REVIEWER_TYPE_DISABLE`, `REVIEW_REQUIREMENT_UPDATE`。専門家typeや業務dataをenum化するものではない。
- `state`は`PROCESSING`、`SUCCEEDED`、`FAILED`の3値。`PROCESSING` leaseは5分。同keyの同時再送は別operationを作らず、
  leaseが有効な間は`409 IDEMPOTENCY_IN_PROGRESS`を返す。stale leaseだけがtarget rowを再確認して同じoperationをCAS再開する。
  callerは409を成功結果と解釈せず、lease終了または元requestの完了後に同じkey/request hashで再送する。元operationがSUCCEEDEDなら再送は
  保存済みallow-list resultを`200`で返し、別operation/eventは作らない。
- `UNIQUE(tenant_id,operation_type,idempotency_key)`、`UNIQUE(tenant_id,operation_id)`、index
  `(tenant_id,state,lease_until)`、`(tenant_id,result_reference_type,result_reference_id)`を持つ。
- `request_hash`はserver再解決後のtenant/workplace/target、operation type、allow-list body、expected version、
  effective periodをcanonicalizeしたSHA-256であり、client supplied tenant/workplace/actor/hashを含めて信用しない。
- successのresultはraw HTTP body/PIIでは保存しない。`result_summary_canonical`はID、status、version、effective period、
  rendition/document version ID/hashだけを持つallow-list JSON（表示名、credential、storage path、raw responseは含めない）とし、
  `result_hash`はそのUTF-8 canonical bytesのSHA-256とする。`result_reference_*`からimmutable event/versionまたはassignment
  operation resultを再解決し、summary/hash/http statusを一致させて同じ200結果を再構成する。後続のassignment終了などでmutable rowが
  変わっても成功時resultを変えない。ACTIVEはoperation_idで旧・新status eventの2件を束ねる。
- validation/auth/gateの決定的失敗は`FAILED,retryable_flag=0,failure_code`をcommitし、同key同requestは同じfailureを返す。
  transient/internal failureはtransaction内savepointでdomain変更だけをrollbackして`FAILED,retryable_flag=1`をCAS commitし、
  同key再送は`FAILED -> PROCESSING`をCASして再実行する。transaction自体がcommit前に落ちた場合はclaimもrollbackされ、次回retryが新しいclaimを行う。
  同key異payloadは常に`409 IDEMPOTENCY_KEY_REUSED`、retryable=0の同key再送は`409 IDEMPOTENCY_RETRY_NOT_ALLOWED`である。
- `PROCESSING`以外のrowはCASでのみ更新し、operation ledger rowは永久保持する。将来purgeする場合は別decision、権限分離、
  purge operation ID、監査eventが必要であり、現decisionではkey再利用を許可しない。
- claim→domain mutation→result reference/hash保存→`SUCCEEDED`/`FAILED`を同一business transaction境界で確定し、
  commit後のresponse喪失再送はdomainを再実行せず、保存resultを200で返す。cache invalidationはafterCommitのみである。
- operation ledger rowをresult eventまたはstate rowへ`operation_id`で関連付ける。approval/external/status eventにも
  `operation_id`を保存し、同じoperationが生成したevent/resultを追跡可能にする。

### 4.1 `m_compliance_mapping_version`

| column | NULL / 意味 |
|---|---|
| `tenant_id`, `mapping_code`, `mapping_version` | NOT NULL。request値を使わない |
| `mapping_hash`, `review_policy_hash` | NOT NULL。DRAFTでも現在payloadから再計算。freeze後不変 |
| `effective_from`, `effective_to` | DATE、from NOT NULL、to NULLは無期限。通常のplatform inclusive期間 |
| `status` | NOT NULL。4状態のみ |
| `active_slot` | ACTIVEだけ1、それ以外NULL |
| `future_slot` | future DRAFT/PROVISIONAL候補を作成したtransactionで1。作成時のserver asOfで`effective_from`が将来であることを確認し、時刻経過だけでは再計算・解放しない。対象候補がACTIVEまたはSUPERSEDEDへ成功遷移する同一transactionでだけNULL化し、それ以外は1を維持 |
| `activated_at`, `activated_by` | ACTIVE/SUPERSEDEDの旧ACTIVEだけ値を保持。DRAFT/PROVISIONALはNULL |
| `version` | NOT NULL、current rowのCAS |
| actor/time | `created_by/at`, `updated_by/at` |

制約/index: `UNIQUE(tenant_id,mapping_version)`、`UNIQUE(tenant_id,mapping_code,active_slot)`、
`UNIQUE(tenant_id,mapping_code,future_slot)`、`UNIQUE(tenant_id,id)`、index `(tenant_id,mapping_code,status,effective_from,effective_to)`、
FK `activated_by -> sys_user.id`。ACTIVE時だけactive_slot=1、未遷移のfuture DRAFT/PROVISIONAL候補だけfuture_slot=1となることをserviceとDB CHECK/triggerの双方で検証する。DB CHECK/triggerはdeployment時刻を再計算せず、statusとslotの許可された遷移だけを検証する。
future candidate作成は同一tenant/mapping_codeの`future_slot=1` UNIQUEを競合境界とし、異なるclient idempotency keyでも一方だけがINSERT成功、他方は409でdomain/event/cacheを変更しない。

future slotの解放契約は明示的である。effective date到来だけでは解放せず、対象候補の`PROVISIONAL -> ACTIVE`、または未使用候補の`PROVISIONAL -> SUPERSEDED`を、status event・CAS・`future_slot=NULL`の同一transactionで成功させた場合だけ解放する。ACTIVE化のgate/CAS/競合失敗、SUPERSEDE失敗、transaction rollbackでは候補statusとfuture_slot=1を維持し、次候補は409とする。

### 4.2 `m_compliance_mapping_source`

columns: `tenant_id`, `mapping_id`, `source_code`, `source_url`, `source_version`, `confirmed_on`,
`effective_from`, `effective_to`, actor/time。全source業務値はNOT NULL、`effective_to`だけNULL=無期限。
`UNIQUE(tenant_id,mapping_id,source_code)`、index `(tenant_id,source_code,confirmed_on)`、
FK `(tenant_id,mapping_id) -> m_compliance_mapping_version(tenant_id,id)`。
parentがDRAFTの間だけINSERT/UPDATE/DELETE可。freeze後はDB triggerでも変更を拒否する。

### 4.3 `m_compliance_external_reviewer_type`

columns: `tenant_id`, `type_code`, `display_name`, `description`, `credential_label`,
`credential_required`, `enabled`, `sort_order`, `version`, `created_by/at`, `updated_by/at`。
descriptionだけNULL可。`UNIQUE(tenant_id,type_code)`、`UNIQUE(tenant_id,id)`、index `(tenant_id,enabled,sort_order)`、
actor FK。type codeの値を限定するCHECK/enum/seedは置かない。参照後のtype_code変更と物理DELETEは拒否する。

### 4.4 `m_compliance_mapping_review_requirement_group`

columns: `tenant_id`, `mapping_id`, `requirement_group_code`, `display_name`,
`minimum_distinct_reviewers`, `sort_order`, actor/time。全てNOT NULL。
`CHECK(minimum_distinct_reviewers >= 1)`、`UNIQUE(tenant_id,mapping_id,requirement_group_code)`、
`UNIQUE(tenant_id,id)`、index `(tenant_id,mapping_id,sort_order)`、mapping複合FK。
minimumの保存先はこの1行だけであり、同一groupの矛盾表現を作らない。

### 4.5 `m_compliance_mapping_review_requirement_type`

columns: `tenant_id`, `requirement_group_id`, `reviewer_type_id`, `reviewer_type_code_snapshot`,
`reviewer_type_name_snapshot`, `credential_label_snapshot`, `credential_required_snapshot`, actor/time。全てNOT NULL。
`UNIQUE(tenant_id,requirement_group_id,reviewer_type_id)`、index `(tenant_id,reviewer_type_id)`、
group/typeへのFK。snapshotはpolicy freeze時の値であり、type master rename/disable後も変更しない。

### 4.6 `t_compliance_responsible_assignment`

columns: `tenant_id`, `workplace_id`, `user_id`, `role_code`, `effective_from`, `effective_to`, `active_slot`,
`assigned_by`, `ended_by`, `end_reason`, `version`, actor/time。`role_code`は常に`COMPLIANCE_RESPONSIBLE`。
`effective_from/effective_to`は`DATETIME(6)`で保存する。`effective_to`はexclusiveで、open assignmentは
`effective_to IS NULL`かつ`active_slot=1`、`ended_by/end_reason=NULL`とする。有限assignmentは
`active_slot=NULL`かつ`effective_to/ended_by/end_reason`を必須にする。
`UNIQUE(tenant_id,workplace_id,active_slot)`、`UNIQUE(tenant_id,id)`、
index `(tenant_id,workplace_id,effective_from,effective_to)`と`(tenant_id,user_id,effective_from,effective_to)`、
FK `workplace_id -> m_workplace.id`, `user_id/assigned_by/ended_by -> sys_user.id`。
§2.2のworkplace anchor lock + overlap SQLを必須とする。

### 4.7 `t_compliance_mapping_approval_event`

columns: `tenant_id`, `mapping_id`, `mapping_version`, `mapping_hash`, `review_policy_hash`, `assignment_id`,
`workplace_id_snapshot`, `actor_id`, `actor_display_name_snapshot`, `actor_role_snapshot`, `action`,
`event_chain_id`, `target_event_id`, `supersedes_event_id`, `occurred_at`, `reason`,
`evidence_document_id`, `evidence_document_version_id`, `evidence_document_version`, `evidence_document_hash`,
`operation_id`, `correlation_id`, `idempotency_key`, `created_at`。
reasonはAPPROVEだけNULL可、REJECT/REVOKEは必須。evidence4項目はAPPROVEでNOT NULL相当、他actionではtargetから解決し
NULLを許す。`UNIQUE(tenant_id,idempotency_key)`、`UNIQUE(tenant_id,id)`、index
`(tenant_id,mapping_id,workplace_id_snapshot,assignment_id,occurred_at,id)`、chain/target index、mapping/assignment/user/
document/version/self FK。INSERTのみ。DB triggerで直接UPDATE/DELETEを拒否する。

### 4.8 `t_compliance_external_review_event`

columns: `tenant_id`, `mapping_id`, `mapping_version`, `mapping_hash`, `review_policy_hash`,
`requirement_group_id`, `requirement_group_code_snapshot`, `reviewer_type_id`, `reviewer_type_code_snapshot`,
`reviewer_type_name_snapshot`, `reviewer_name_snapshot`, `organization_snapshot`,
`credential_snapshot_encrypted`, `credential_key_version`, `credential_cipher_format`, `credential_masked_snapshot`,
`reviewer_identity_hash`, `action`,
`review_chain_id`, `target_event_id`, `supersedes_event_id`, `reviewed_at`, `valid_until`, `recorded_at`,
`evidence_document_id`, `evidence_document_version_id`, `evidence_document_version`, `evidence_document_hash`,
`recorded_by`, `operation_id`, `correlation_id`, `idempotency_key`。
`operation_id`はevent INSERT前に確定したledger UUIDv4であり、credential暗号化のAADへ使う。AUTO_INCREMENTのexternal event IDはAADへ使わず、
event INSERT後のUPDATEも行わない。credentialが任意で未入力なら`credential_snapshot_encrypted`、`credential_key_version`,
`credential_cipher_format`, `credential_masked_snapshot`の4項目を全てNULLにする。入力時は4項目全て非NULLとし、暗号値だけをINSERTする。
organization/valid_untilはpolicyに応じてNULL可。credential_required typeでcredential NULLは拒否し、optional typeで未入力ならcredential関連4項目を全NULLにする。
credentialは専用暗号契約（§6.5）のenvelopeとkey versionで保存し、`credential_key_version`/`credential_cipher_format`を
平文credentialと別に必ず保持する。
`UNIQUE(tenant_id,idempotency_key)`、`UNIQUE(tenant_id,id)`、index
`(tenant_id,mapping_id,requirement_group_id,reviewer_identity_hash,recorded_at,id)`、chain/target/valid_until index、
mapping/group/type/document/version/user/self FK。INSERTのみ。資格情報の平文保存・response返却は禁止する。
DB triggerで直接UPDATE/DELETEを拒否する。

### 4.9 `t_compliance_mapping_status_event`

columns: `tenant_id`, `mapping_id`, `mapping_version`, `mapping_hash`, `review_policy_hash`, `before_status`,
`after_status`, `actor_id`, `actor_display_name_snapshot`, `actor_role_snapshot`, `occurred_at`, `expected_version`,
`gate_snapshot_hash`, `operation_id`, `correlation_id`, `reason`, `created_at`。
ACTIVE成功eventと、同一transactionで旧ACTIVEをSUPERSEDEDにするeventだけgate_snapshot_hash NOT NULL相当。
PROVISIONAL→SUPERSEDED、明示的なACTIVE終了、DRAFT系のstatus eventはNULLを許可し、理由を必須にする。
`UNIQUE(tenant_id,id)`、index `(tenant_id,mapping_id,occurred_at,id)`と`(tenant_id,correlation_id)`、
mapping/user FK。INSERTのみ。DB triggerで直接UPDATE/DELETEを拒否する。

### 4.10 DB immutability

V102はmapping sourceへfreeze状態を参照する`BEFORE INSERT`/`BEFORE UPDATE`/`BEFORE DELETE` triggerを作り、DRAFT parentだけ編集を許可し、
PROVISIONAL_REVIEWED/ACTIVE/SUPERSEDED parentでは直接変更を拒否する。さらにapproval/external review/status eventの各tableへ`BEFORE UPDATE`/`BEFORE DELETE` triggerを作り、
`SIGNAL SQLSTATE '45000'`で直接変更を拒否する。event mapperはINSERT/SELECTだけを公開し、operation ledgerは
claim、SELECT、PROCESSINGからのCAS遷移だけを明示APIで公開する。全G2 mapperで`BaseMapper`の汎用DELETE/UPDATE APIを公開しない。
MySQL direct regressionはapplicationを経由しないSQLでsourceとeventへINSERT/UPDATE/DELETEを発行し、DRAFT sourceの3操作だけが成功し、
freeze済みsourceの3操作とeventの6操作が拒否され、行/hashが不変で
あることをassertする。修復が必要な場合は公開済みV102を編集せず、承認済みforward repair migrationで扱う。

## 5. Operation / transition decision table

| operation / transition | scope | caller role | service actor condition | current status | assignment条件 | approval条件 | external Review policy条件 | evidence条件 | mapping/policy hash条件 | SQL tenant/workplace境界 | success state/event | failure code | idempotency | cache | audit |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DRAFT作成/編集 | tenant mapping | 管理者 | active user | DRAFT | 不要 | 不要 | 非空group/type/minimum整合 | source/evidence pickerはscope内 | write後3 hashのうちmapping/policyを再計算 | deployment tenant | DRAFT row、監査 | 403/`GATE_DRAFT_FROZEN` | `MAPPING_DRAFT_UPSERT` ledger + CAS | afterCommit | API audit+before/after hash |
| PROVISIONAL化 | tenant mapping | 管理者 | active user | DRAFT | 不要 | 不要 | 非空・整合・freeze可能 | L0/独立Review evidence CLEAN | 再計算一致 | tenant+mapping | PROVISIONAL status event、gate hash=NULL | `GATE_POLICY_INVALID`/409 | operation ledger + CAS | afterCommit | status event |
| assignment作成 | workplace | 管理者 | assignee role eligible | 任意 | 半開区間、overlap 0 | 不要 | 不要 | 任命理由 | — | tenant+workplace lock | assignment INSERT | `GATE_ASSIGNMENT_CONFLICT`/409 | operation ledger + UNIQUE | afterCommit | API audit |
| assignment終了 | workplace | 管理者 | active user | 任意 | expected version、end>from | 不要 | 不要 | end reason | — | tenant+workplace lock | assignment CAS | 409 | operation ledger + CAS | afterCommit | API audit |
| reviewer type create/update/disable | tenant reviewer master | 管理者 | active user | 任意 | 不要 | 不要 | type code参照後不変、disabledは新規policy/review不可 | credential label等allow-list | tenant/type code再解決 | tenant | master row/CAS、監査。過去freeze snapshot不変 | 403/409 | `REVIEWER_TYPE_CREATE/UPDATE/DISABLE` ledger + CAS | afterCommit | API audit+before/after hash |
| review requirement update | tenant mapping policy | 管理者 | active user | DRAFT | mapping DRAFT、minimum/group/type整合 | 不要 | freeze前のみ | enabled typeとscope内evidence | policy hash再計算 | tenant+mapping | requirement rows/CAS | `GATE_DRAFT_FROZEN`/409 | `REVIEW_REQUIREMENT_UPDATE` ledger + CAS | afterCommit | API audit+before/after hash |
| internal APPROVE/REJECT/REVOKE | workplace+mapping | 管理者/HR/マネージャー | asOf有効assignmentのuser本人。管理者bypassなし | PROVISIONAL/ACTIVE | actorとassignment一致 | reducer §7 | 不要 | APPROVEはCLEAN exact evidence | mapping/policy完全一致 | tenant+workplace+assignment | append event | 403/`GATE_ACTOR_MISMATCH`、409 | operation ledger + event UNIQUE | afterCommit | domain event+API audit |
| external review登録 | tenant mapping+group | 管理者 | recorder本人 | PROVISIONAL/ACTIVE | 不要 | 不要 | enabled typeがfreeze groupに存在 | exact version/hash+CLEAN | mapping/policy完全一致 | tenant+mapping+group | append APPROVED/REJECTED | `GATE_REVIEW_TYPE_INVALID`等409 | operation ledger + event UNIQUE | afterCommit | domain event+API audit |
| external review REVOKE | tenant mapping+group | 管理者 | recorder本人 | PROVISIONAL/ACTIVE/SUPERSEDED | 不要 | 不要 | targetが同chainの有効positive | target evidence再解決 | target hash完全一致 | tenant+target event | append REVOKED | `GATE_REVOKE_TARGET_INVALID`/409 | operation ledger + event UNIQUE | afterCommit | domain event+API audit |
| ACTIVE化 | tenant mapping | 管理者 | active user | PROVISIONAL | request approval eventのassignmentがasOf有効、mapping effective period内 | 指定event有効 | 全group成立 | 全evidence CLEAN/exact | 完全一致 | tenant、approval workplace | 旧SUPERSEDED+新ACTIVE events | §8 failure codes | operation ledger + CAS + active slot | commit後のみ | 2 status events+API audit |
| PROVISIONAL→SUPERSEDED | tenant mapping | 管理者 | active user | PROVISIONAL_REVIEWED | expected version | 不要 | 不要 | reason必須 | hash不変、gate hash=NULL | tenant+mapping | SUPERSEDED status event | `GATE_SUPERSEDE_REASON_REQUIRED`/409 | operation ledger + CAS | afterCommit | status event |
| formal generate/delivery | contract workplace | 管理者/HR/マネージャー | role+DataScope | ACTIVEかつmapping effective period内 | profile workplaceの現assignment有効 | 同assignment actorの有効APPROVE | 現在時点で全group成立 | CLEAN/exact、3 rendition全てCLEAN | current mapping/policy一致 | tenant+contract+profile workplace | delivery_business_keyで予約した1 delivery、FULL/MASK/LIMITED immutable document version + delivery snapshot | `GATE_*`/409、scope404 | client keyはoperation ledger、business keyはdelivery UNIQUE。異key同入力は既存result 200 | afterCommit | delivery+API audit、notification key=business key |
| preview | contract workplace | 管理者/HR/マネージャー | role+DataScope | DRAFT/PROVISIONAL/ACTIVE | 不要 | 不要 | 構造的に非空/整合 | document evidence不要 | draft mapping/policy再計算一致 | tenant+contract+profile workplace | watermark responseのみ | 403/409 | request key、永続行0 | cache更新なし | API auditのみ |
| delivery一覧/confirm | contract workplace | 既存R4 matrix | role+DataScope | delivery状態 | 新gate再評価不要 | 新gate再評価不要 | 新gate再評価不要 | file scope | 保存済hashを表示 | tenant+contract+workplace | 既存状態CAS | 403/404/409 | CAS | afterCommit | API audit |
| 過去delivery download | delivery snapshot | 管理者/HR/マネージャー/営業 | document ACL+role mask | delivery済み | 現assignment不要 | 現approval不要 | 現review不要 | 選択したimmutable renditionがCLEAN | 保存済hash、current master不使用 | tenant+delivery+contract ACL | 保存済みFULL/MASK/LIMITED document version | 403/404/`FILE_NOT_CLEAN` | access log key | current gate cache不使用 | download access log |

全state-changing row（reviewer type create/update/disable、review requirement updateを含む）は、業務transaction前にoperation ledgerのkey claimを行う。
同key同payloadのSUCCEEDEDは同じresultを返し、同key異payloadは409、処理中は409、retryable FAILEDだけが同じkeyでCAS再開する。
HTTP responseの再送をdomain CASの再実行で代替しない。

## 6. Hash contract

### 6.1 共通canonical形式

- payloadはJSON objectをUTF-8（BOMなし）へserializeし、改行はLFだけを使用する。
- 全stringをUnicode NFCへnormalizeする。業務値のtrim/case-foldは各fieldの入力正規化後の値を使い、hash時に勝手に変えない。
- object keyはUnicode code pointの昇順。numberはscaleをfield contractどおり固定し、指数表記を使わない。
- arrayは入力順を信用せず、mapping row=`stableRowId`、source=`sourceCode`、group=`groupCode`、
  type=`typeCode`、gate review=`groupCode,reviewerIdentityHash,eventId`の昇順にsortする。
- 明示NULLはJSON `null`として含め、項目不存在はkey自体を出さない。両者を同一視しない。
- LocalDateは`uuuu-MM-dd`。timestampはtenant/deployment timezoneでinstantへ解決後UTCへ変換し、
  `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'`の6桁精度で表す。DBもDATETIME(6)とする。
- hashはcanonical bytesのSHA-256、64文字lowercase hexである。
- 再計算はDBからtenant predicate付きでsource rowを再取得し、同一canonicalizerでpayload bytesを生成して保存hashと比較する。
  requestに含まれるhashは比較入力にも使わない。
- surrogate DB ID、created/updated actor/time、CAS version、active_slot、UI sort_order、localized display-only description、
  mask済み表示値はcontent hashから除外する。ID自体がgate証跡であるevent/assignment/document versionはgate hashへ含める。
- content hashとidempotency keyは別物である。同内容を新operationとして記録でき、retryだけをidempotency keyで抑止する。
- operation `request_hash`はcontent hashと別のcanonical payload（operation type、server解決scope/target、allow-list body、
  expected version、effective period）から算出する。operation keyの再送判定はkeyとrequest_hashの組で行い、同key異payloadを拒否する。

### 6.2 `mapping_hash`

含むもの: mapping code/version/effective period、96 stable row IDごとのsource ID/field semantics/canonical resolution、
source code/URL/version/confirmed_on/effective period。review policy、status、actor、UI表示順は含めない。
mapping変更は新versionを作り、既存hashを更新しない。

### 6.3 `review_policy_hash`とreviewer identity hash

policy hashはgroup code/minimum、各groupの許容type snapshot（type code/name、credential label/required）を含む。
group/typeのdisplay sort、masterの現在値、eventは含めない。

reviewer identity hashは次のcanonical objectのSHA-256とする。
`reviewerTypeCode`, `credentialIdentifier`（optionalなら明示NULL可）、`organization`, `reviewerName`。
各値は登録時のNFC normalized snapshotを使う。氏名文字列だけではdistinct判定しない。
credential原文は暗号化storageだけに保存し、responseはmask済みsnapshotだけを返す。

### 6.4 `gate_snapshot_hash`

含むもの: mapping ID/version/hash、review policy hash、target workplace ID、assignment ID/user/effective interval、
採用したworkplace approval event ID/chain/action/occurred_at、groupごとに採用したexternal review event ID/
reviewer identity hash/reviewed_at/valid_until、各evidence document ID/version ID/version/hash、gate evaluated asOf。
deliveryごとに再計算し、`t_document_delivery`へ保存する。ACTIVE化時のhashはstatus eventへ保存する。

### 6.5 credential専用暗号契約

- `credential_snapshot_encrypted`は専用credential crypto providerだけが読み書きする。MFA、Freee、BP bank accountの鍵を流用しない。
- envelopeは`CGC1:<keyVersion>:<base64url(random 12-byte IV)>:<base64url(ciphertext+128-bit GCM tag)>`とし、AES-256-GCM、
  random IV、AAD=`tenantId|mappingId|mappingVersion|operationId|credentialField`を固定する。operationIdはINSERT前に確定済みである。
  plaintext、鍵、AAD、復号値はDB、API response、audit、通常log、exception messageへ出さない。
- `credential_key_version`はenvelopeのversionと一致し、`credential_cipher_format=CGC1`を保存する。writeはcurrent keyだけ、readはcurrentと
  rotation前の許可済み旧keyだけを使う。key rotationは新key versionで新規writeし、過去eventのciphertext/hashを上書きしない。
- key versionは`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`、key configはbase64url（paddingなし）で、decode後が必ず32 bytesでなければならない。
  空白、standard base64、長さ不一致、未知versionは拒否する。
- providerは`ComplianceGateCredentialKeyProvider`に固定し、productionではdeployment secret storeから
  `compliance.gate.credential-crypto.current-key-version`と
  `compliance.gate.credential-crypto.keys.<version>`を解決する。prodはcurrent keyと参照される旧keyが全て設定済みでなければ起動時fail-fast、
  test/devのplaceholderをprodへ持ち込まない。MFA/Freee/BPのconfig namespaceとの相互fallback、別providerへの暗黙fallbackはしない。
- GCM tag不正、wrong key、未知key version、改竄ciphertext、復号失敗は`GATE_CREDENTIAL_UNAVAILABLE`としてfail-closedする。
  現在gateの評価・ACTIVE・formal deliveryは拒否し、masked snapshot/identity hashの表示だけを許可する。秘密値を推測・再生成しない。

### 6.6 delivery render input hash

`render_input_hash`はmapping/policy/gate hashの代替ではなく、交付時renderのcontent provenance hashである。canonical payloadは
既存`t_contract_compliance_snapshot`のprofile snapshot ID/hash、既存worker snapshot ID/hashまたは明示的な`worker_snapshot_presence=ABSENT`とNULL sentinel、
profileから解決したworkplace ID、recipient/display name snapshot、帳票に実際に渡すcompany/config content snapshot、template version、field mask policy hash/version、render engine version、worker asOf、
選択したFULL/MASK/LIMITED rendition discriminatorを含む。actual config snapshot tableやstorage path/key、current masterの更新時刻、
表示専用のlocale labelは保存・hash対象にせず、PDF renditionがcontentの唯一のimmutable正本である。downloadはこのhashから業務内容を
再構成せず、保存済みDocumentVersionのsha256と照合する。

recipient/display snapshot hashは、各帳票へ実際に渡すrecipient ID/versionと表示値（氏名、部署、役職、住所、電話等）のcanonical objectから作る。
company/config content snapshot hashは、`m_system_config`から帳票へ実際に渡すcompany name/address/representativeおよびtemplateが参照するconfig key/valueの
canonical objectから作る。両方とも現在masterの更新時刻、表示専用locale label、storage path/keyを含めず、値がAからBへ変わればhashが変わる。

## 7. Event reducer

### 7.1 共通順序とchain

- eventはappend-only。UPDATE/DELETEしない。
- state-changing operationはevent reducerの前にoperation ledgerでclaimし、event rowの`operation_id`とledger rowを同一tenantで結ぶ。
  event固有の`idempotency_key` UNIQUEは二重防御として残す。
- chain IDは同じ論理対象で維持する。internal approvalは`tenant+mapping+workplace+assignment`、external reviewは
  `tenant+mapping+group+reviewerIdentityHash`が論理対象である。
- reducer順序はinternal=`occurred_at ASC,event_id ASC`、external=`recorded_at ASC,event_id ASC`。
  同一時刻はDB採番event IDが大きい方をlatestとする。
- `supersedes_event_id`は同chainの直前latest event、`target_event_id`はREJECT/REVOKEが無効化するpositive eventを指す。
- REVOKE/REJECTのtargetは同tenant/chain/mapping/hash/groupで、asOf直前に有効なpositive eventでなければ拒否する。
- APPROVE/APPROVEDの再登録は、直前REJECT/REVOKEを`supersedes`して同chainに追加できる。新positive eventがlatestとなる。
- idempotency retryはoperation ledgerの`tenant+operation_type+idempotency_key`とrequest_hashを先に照合し、同じresult eventを返し、
  別eventを作らない。event固有keyだけが一致してもrequest_hash不一致なら409とする。
- concurrent insertはmapping rowとassignment/chain対象rowを`SELECT ... FOR UPDATE`し直列化する。異なるidempotency keyの
  競合は再読後に一方だけが有効なlatestとなり、不正targetは409で全rollbackする。

### 7.2 internal approvalの有効条件

asOf時点で、mapping ID/version/hash/policy hashが完全一致し、eventのassignmentがtarget workplaceで有効、
actor IDがassignment user本人、`occurred_at <= asOf`、evidence exact version/hash+CLEAN、reducer latest actionがAPPROVEであること。
assignment交代でassignment IDが変われば旧chainは新規deliveryへ使用しない。管理者もactor本人でなければapproveできない。

### 7.3 external reviewの有効条件

- mapping ID/version/hashとreview policy hashが完全一致する。
- requirement groupがfreeze済みgroupに一致し、reviewer typeがそのgroupのfreeze済み許容typeに含まれる。
- `reviewed_at <= asOf`かつ`recorded_at <= asOf`。
- `valid_until IS NULL OR asOf < valid_until`。valid_untilちょうどは失効済みである。
- reducer latest actionがAPPROVEDである。
- evidence document/version/hashがevent snapshotと一致し、scan status=CLEANである。
- evidenceのowner/linkはtenant mapping versionであり、storage path/keyを公開しない。delivery側では別途target contractの
  workplace/org/DataScopeを満たす。clientが別workplace evidenceを差し替える入力欄は持たない。
- groupごとにreviewer identity hashをdistinct countし、minimum以上である。

### 7.4 status event

status eventはappend-onlyだが、current mapping rowのstatus/active_slot/versionはexpected version CASで更新する。
旧ACTIVEのSUPERSEDED化と新versionのACTIVE化は別transitionであり、同一correlation IDとACTIVE operation_idの2 status eventを保存する。
新ACTIVE成功と置換に伴う旧ACTIVE SUPERSEDEDだけがgate_snapshot_hashを必須とし、未使用PROVISIONALの廃止と明示的ACTIVE終了は
reasonを必須にgate_snapshot_hash=NULLで記録する。

## 8. ACTIVE transaction

1. operation ledgerで`MAPPING_ACTIVE`とrequest_hashをclaimし、同keyのSUCCEEDED/FAILED/retryable状態を先に処理する。
2. transaction開始時にClockから`asOf`を1回だけ確定する。
3. target mapping versionをtenant+ID+expected versionでCAS lockする。
4. mapping/source/review policyがPROVISIONAL_REVIEWEDかつfreeze済みで、canonical hash再計算が一致することを確認する。
5. `effective_from <= asOf`かつ(`effective_to IS NULL`または`asOf <= effective_to`)を確認し、future/expiredなら拒否する。
6. requestで指定された`approvalEventId`からassignment、tenant、workplace、actorをDB再解決する。
7. assignmentがasOf時点で有効であることを確認する。
8. approval actorが実際のassignment user本人であることを確認する。
9. mapping ID/version/hashとreview policy hashの完全一致を確認する。
10. freeze済みreview policyの全groupを評価する。
11. groupごとのdistinct reviewer数を評価する。
12. external reviewの期限、REVOKE、hash、group、type snapshot、credential復号可否を確認する。
13. 全evidenceのtenant/file scope、exact version/hash、scan=CLEANを確認する。
14. 既存ACTIVEをexpected version CASでSUPERSEDEDへ遷移する。置換時だけgate_snapshot_hashを共有する。
15. 旧ACTIVEのactive_slotをNULLにする。
16. target mappingをexpected version CASでACTIVEへ遷移する。
17. target mappingのactive_slotを1にする。
18. 旧・新双方のstatus eventを同じcorrelation IDとoperation_idでappendする。
19. operation ledgerへresult reference/hashを保存し、SUCCEEDEDへCASする。
20. 任意の失敗時はmapping、event、operation、cache予約を全rollbackし、決定的失敗だけFAILEDを保存する。
21. cache invalidationは`ScopeChangeInvalidator`等の既存afterCommit境界からだけ実行する。

ACTIVE requestは`approvalEventId`, `expectedVersion`, `idempotencyKey`だけを業務指定する。
tenant/workplace/assignment/actor/mapping version/hash/policy hashはresponse比較用を含めてrequestから信用しない。
ACTIVE statusだけを見てdeliveryを許可せず、formal generateごとにtarget workplaceのgateを§5どおり再評価する。

## 9. Delivery、formal generate、preview

### 9.1 formal generate

現行`POST /api/contracts/{contractId}/compliance-documents/generate`は正式交付操作である。ACTIVEと現在gateを満たす時だけ、
同一transaction意味論でarchive、delivery row、delivery statusを作成する。gate不足時はarchive/delivery/notificationを0件のまま
409でrollbackする。delivery確定時刻は1回だけ確定し、worker snapshot asOfとdelivery `delivered_at`に同じ値を使う。

`t_document_delivery`へV102で次を追加する案とする。

| column | legacy / new row |
|---|---|
| `mapping_version_id`, `mapping_version`, `mapping_hash` | legacyはNULL表示、新規はNOT NULL相当 |
| `review_policy_hash` | legacyはNULL、新規必須 |
| `gate_evaluated_at`, `gate_snapshot_hash` | legacyはNULL、新規必須 |
| `profile_snapshot_id/hash`, `worker_snapshot_id/hash`, `workplace_id` | `profile_snapshot_id/hash`は既存`t_contract_compliance_snapshot.id/snapshot_hash`へ1対1 mappingで新規必須。workerは既存`t_contract_compliance_worker_snapshot.id/snapshot_hash`へ1対1 mappingし、交付時点以前の確定版が無い場合はID/hashを同時NULLとしてworker項目を省略する。片側NULLは拒否する。`workplace_id`はprofileからserver解決したscope scalarで新規必須。legacyはNULL |
| `render_input_hash`, `recipient_display_snapshot_hash`, `company_config_snapshot_hash`, `field_mask_policy_hash`, `render_engine_version` | legacyはNULL、新規必須。actual config snapshot tableは作らず、PDF renditionをcontentの唯一のimmutable正本とする。render_input_hashはprofile/worker/workplace scope、recipient/display/company/configの実render値、template、mapping/policy/gate、mask policy、engine、roleのprovenance hashであり、hashから業務内容を再構成しない。2つのsnapshot hashはbusiness keyにも含める |
| `rendition_group_id`、`full_document_version_id/sha256`、`mask_document_version_id/sha256`、`limited_document_version_id/sha256` | legacyはNULL、新規は3 role renditionのimmutable document version/hashを必須保存 |
| `delivery_business_key`, `generation_state` | legacyはNULL。新規はbusiness keyをNOT NULL、`generation_state`は`CREATING`または`READY`。`UNIQUE(tenant_id,delivery_business_key)`でclient idempotency keyとは分離し、`CREATING`予約→3 rendition/CLEAN/notification準備→`READY`を同一transactionで行う。失敗は予約をrollbackする。新規rowのformal downloadはREADYだけを許可するが、legacyのNULL rowは既存delivery ACL、file scope、scan=CLEAN、安全な保存済みDocumentVersionを再検証してdownload 200を許可する |

既存行へのactor/mapping/reviewの捏造backfillは禁止する。DTOはlegacyを`LEGACY_GATE_SNAPSHOT_UNAVAILABLE`として表示する。legacyの`generation_state=NULL`をREADY不足として拒否してはならず、既存ACL/CLEAN条件を満たすlist/downloadだけを許可する。
新規deliveryはprofile snapshot、resolved workplace_id、mapping/policy/gate、3 rendition refsなしで保存できない。worker snapshotが交付時点以前に無い場合はworker ID/hashを同時NULLで保存し、worker項目を省略して生成を継続する。
新しいworkplace snapshot table、render config snapshot table、canonical input JSON列は作らず、actual contentは既存のappend-only
`t_document_version`へ保存した3つのPDF renditionだけを正本とする。formal generateは同一交付transactionで、同じprofile/workplace scopeと、存在する場合だけworker snapshotを入力に、
mapping/policy/gateを固定してFULL、MASK、LIMITEDの3つの`DocumentVersion`を生成し、それぞれ`source_type=COMPLIANCE_DELIVERY_RENDITION`、
`scan_status=CLEAN`、独立sha256、同一`rendition_group_id`を持たせる。FULLはarchive正本、MASK/LIMITEDはrole別downloadの正本であり、
1つでも生成・scanに失敗したらdeliveryと全renditionをrollbackする。

client idempotency keyは共通operation ledgerの再送識別だけに使う。deliveryの業務一意keyは、生成時に新設される`rendition_group_id`、delivery ID、
operation_id、client idempotency key、notification ID、`gate_snapshot_hash`、`render_input_hash`、gate evaluated asOf、worker asOf、
`delivered_at`を除外し、serverが選択したstable inputだけから作る。authoritative canonical payloadは
`tenantId,contractId,documentType,templateVersion,profileSnapshotId,profileSnapshotHash,workerSnapshotId,workerSnapshotHash-or-ABSENT,workplaceId,recipientDisplaySnapshotHash,companyConfigSnapshotHash,mappingVersionId,mappingVersion,mappingHash,reviewPolicyHash,approvalEventId,externalReviewEventIds(sorted),evidenceDocumentVersionIds(sorted),evidenceHashes(sorted),fieldMaskPolicyHash,renderEngineVersion`
とする。worker snapshot不在は`workerSnapshotId=NULL,workerSnapshotHash=NULL,workerSnapshotPresence=ABSENT`を固定する。`recipientDisplaySnapshotHash`と`companyConfigSnapshotHash`は、直前のrenderへ渡したcanonical objectから同じcanonicalizerで計算し、`t_document_delivery`の同名保存列およびbusiness key計算へ同一値を渡す。各ID/hashは採用証跡・保存snapshot・recipient/company/configの実render内容・template/mask/engineの版を示すstable値であり、時間経過だけでは変わらない。
`UNIQUE(tenant_id,delivery_business_key)`の予約INSERTが異key同入力を直列化し、既存READY rowから同じdelivery/rendition/resultを返す。business keyが同じでも、formal generate前に現在gateを再評価し、期限切れ・撤回・scope不成立なら既存deliveryを新規formal resultとして返さず409とする。
notificationは`COMPLIANCE_DELIVERY:{delivery_business_key}`をidempotency keyとし、予約ownerだけが1件作成する。mapping/policy/review evidence/render inputのいずれかが変わった時だけbusiness keyが変わり、新group/notificationを許可する。

### 9.2 preview

`POST /api/contracts/{contractId}/compliance-documents/preview`を別APIとし、requestは
`documentType,templateVersion,mappingVersionId,idempotencyKey`のallow-listだけとする。

- archive row、delivery row、notificationを作らず、delivery IDを返さない。
- PDF各pageへ`PREVIEW / 本番交付物ではありません`の透かしを付ける。
- responseは`Content-Disposition: inline; filename="preview-...pdf"`と`X-Compliance-Preview: true`を返し、
  formal generate responseと同じDTO/content-dispositionにしない。
- tenant/workplace/DataScope、field mask、template/mapping/policy hash再計算は適用するが、assignment/approval/external review/ACTIVEは要求しない。
- policyは構造的に非空・整合していなければpreviewも409とする。
- 管理者/HR=FULL、マネージャー=MASK、営業/要員=403とする。

### 9.3 過去delivery download

downloadはcurrent gateを再評価しない。管理者/HRのFULLは保存済み`full_document_version_id`を返す。マネージャーは保存済み
`mask_document_version_id`、営業は保存済み`limited_document_version_id`を返す。download時にContract/Engineer/customer/config/
responsible/profile/worker masterを業務内容のrender inputとして再読込・再生成しない。現在のACL、DataScope、file scope、scan=CLEANは
毎回検証するが、content bytes/hashは交付時のimmutable document versionから変わらない。
SUPERSEDED/期限切れ/REVOKED/assignment交代は過去deliveryを無効化しない。ACL、DataScope、file link、exact document version、
scan=CLEAN、download access logは毎回必要である。

## 10. `/compliance-gate` UI / API / permission

### 10.1 tabsとAPI

| tab | API | method / action |
|---|---|---|
| Mapping | `/api/compliance-gate/mappings` | GET、POST create、PUT DRAFT edit、POST `/{id}/provisional-review` |
| 外部専門家type | `/api/compliance-gate/reviewer-types` | GET、POST、PUT、POST `/{id}/disable` |
| Review requirement | `/api/compliance-gate/mappings/{id}/review-requirements` | GET、PUT（DRAFTのみ） |
| workplace責任者assignment | `/api/compliance-gate/workplaces/{id}/assignments` | GET、POST、POST `/{assignmentId}/end` |
| 社内approval | `/api/compliance-gate/mappings/{id}/approvals` | GET、POST APPROVE/REJECT/REVOKE |
| 外部Review | `/api/compliance-gate/mappings/{id}/external-reviews` | GET、POST、POST `/{eventId}/revoke` |
| ACTIVE化 | `/api/compliance-gate/mappings/{id}/activate` | POST |
| status/event履歴 | `/api/compliance-gate/mappings/{id}/events` | GET |
| bootstrap/evidence | `/api/compliance-gate/bootstrap`, `/api/compliance-gate/evidence-options` | GET |

pageは`/compliance-gate`。action endpointはPOST/PUTを使い、PATCHは採用しない。全POST/PUTはCSRF対象である。
request/responseはallow-list DTOで、entityを返さない。

### 10.2 action permission / capability

| action | 管理者 | HR | マネージャー | 営業 | 要員 | service条件 |
|---|---:|---:|---:|---:|---:|---|
| `compliance-gate.view` | allow | allow | allow | 403 | 403 | tenant/DataScope |
| `compliance-gate.policy.manage` | allow | deny | deny | deny | deny | DRAFTのみ |
| `compliance-gate.assignment.manage` | allow | deny | deny | deny | deny | workplace lock/CAS |
| `compliance-gate.external-review.manage` | allow | deny | deny | deny | deny | real reviewer/evidence |
| `compliance-gate.activate` | allow | deny | deny | deny | deny | §8全条件 |
| `compliance-gate.approve` | page到達可 | page到達可 | page到達可 | deny | deny | 有効assignment actor本人だけ。管理者bypassなし |

UI JavaScriptはrole名で判定しない。bootstrapまたは各responseがserver計算した
`canManagePolicy`, `canManageAssignments`, `canRecordExternalReview`, `canActivate`, `canApprove`を返す。
UI非表示は補助であり、direct URL/APIはaction permissionとservice actor条件で403にする。

更新APIはApiAuditFilterに加え、domain append-only eventへactor snapshot、before/after、hash、correlation IDを保存する。
evidence picker responseは`documentId,versionId,versionNumber,title,originalFilename,sha256,scanStatus,createdAt`だけを返す。
storage path/key、不要なmetadata、未mask credentialを返さない。clientはdocument/version IDだけを送り、serverがtenant、file、
workplace/org/DataScope、exact hash、scan=CLEANをDBから再検証する。

## 11. `GATE-T066-HISTORY` separation

`GATE-T066-HISTORY`は`TRACKED P2 / production release gate`である。S10 spec PASSとS12開始を阻害しないが、
未実装を受入済み・検証済みとは扱わない。次のfield familyを名称とstable IDで固定する。

| family | stable mapping ID | 対象帳票 / production条件 |
|---|---|---|
| 月次就業実績 | `FM-L-20 / LEDGER_WORK_HISTORY` | 派遣元管理台帳で対象月次行を必要とする交付は禁止 |
| 苦情処理状況 | `FM-C-16..18`, `FM-E-16`, `FM-L-21..23 / COMPLAINT_HISTORY` | 苦情eventを必要とする個別契約書・明示書・台帳は禁止 |
| 教育訓練 | `FM-L-24 / TRAINING_HISTORY` | 教育訓練eventを必要とする台帳は禁止 |
| キャリアconsulting | `FM-L-25 / CAREER_HISTORY` | consulting eventを必要とする台帳は禁止 |
| 紹介予定 | `FM-C-27`, `FM-E-18/22`, `FM-L-29 / PLANNED_INTRODUCTION_*` | 紹介予定派遣の該当帳票は禁止 |
| 紛争防止 | `FM-C-25`, `FM-E-19 / DIRECT_HIRE_DISPUTE_HISTORY` | 条件該当する個別契約書・明示書は禁止 |
| 差異通知 | `FM-N-12..16 / NOTIFICATION_DIFFERENCE_HISTORY` | 差異eventを必要とする派遣先通知書は禁止 |

後続history specはwrite path、CORRECTED/CANCELLED event、asOf reconstruction、permission/mask、帳票goldenを実装する。
本番release catalogで上表に該当する帳票を禁止する。S10 Phase Bはproduction authorizationを伴わない受入環境で
formal workflowを検証できるが、これを上表のproduction受入済み証拠へ流用しない。

HISTORY非block化とは別に、T066/S10 PASSにはG2 mechanism、実在assignment actor approval、ページ設定policyを満たす
実在external review、実在CLEAN evidence、正式PDF browser目視、R10最終Reviewが必要である。

## 12. Migration decision

read-only inventory（2026-08-11）ではcommon latestは
`db/migration/V101__remove_unimplemented_menu_routes.sql`、dev locationに
`db/migration-dev/V100__seed_r3_scale_300.sql`が実在する。Flyway locationはdefault=common、dev=common+dev、
prod=common+prodである。

| 対象 | 旧現行表記 | 新しい現行decision |
|---|---|---|
| common V99 | S12予約等 | 永久欠番。後から補填しない |
| V100 | S13/common予約等 | `migration-dev`実在。commonとして永久に再利用しない |
| common V101 | S14予約等 | 既存用途を維持し、編集・再利用しない |
| S10 G2 follow-up | 未採番 | `V102` |
| S12 staffing | V99/V105が混在 | `V103` |
| S13 external portal | V100/V106が混在 | `V104` |
| S14 engineer portal | V101/V107が混在 | `V105` |
| S15 accounting | V102 | `V106` |
| S16 JP PINT | V103 | `V107` |
| S17 AI feedback | V104 | `V108` |

V84/V85/V101は変更しない。V59/V72/V82/V99は永久欠番として補填しない。V100は欠番ではなくdev実在versionである。
R10受理前にV102を作成しない。

R10受理後、`SpecDispatchConsistencyTest`へ次を追加する。

- S12〜S17のdependency順で予約番号がV103〜V108へ単調増加すること。
- 中央README/ledger/task-start/spec start/review/copyableの現行番号が一致すること。
- S10 V84/V85実在とV102 follow-up予約を区別して表現すること。
- common/migration-dev/prodの全Flyway locationを横断し、version重複と予約衝突を検査すること。

## 13. Direct regression matrix

共通: `tenant=A/B`は論理tenant fixture、`wp=A/B`は別workplace、`t0`は1回確定したasOf。
HTTP `—`はservice/DB direct test。rollback/cache欄の`不変`はmapping/event/delivery 0差分かつcache invalidation 0を意味する。

### 13.0 R21 fix delta direct regression

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---:|---|---|
| G2-IDP-01 / R6.5 | L2 | DRAFT作成をcommit後response喪失 | admin | A | — | t0 | same POST retry | 200/200 | row 1、same result reference、operation 1 | domain 1/cache 1 |
| G2-IDP-02 / R6.5 | L2 | PROVISIONAL化をcommit後response喪失 | admin | A | t0 | same action retry | 200/200 | status event 1、same event ID | cache 1 |
| G2-IDP-03 / R6.5 | L2 | assignment create response喪失 | admin | A | A | t0 | same key retry | 200/200 | assignment 1、same result | cache 1 |
| G2-IDP-04 / R6.5 | L2 | assignment end response喪失 | admin | A | A | t0 | same key retry | 200/200 | end CAS 1、same result | cache 1 |
| G2-IDP-05 / R6.5 | L2 | ACTIVE成功後response喪失 | admin | A | A | t0 | same key retry | 200/200 | old/new status event 2、same result | cache 1 |
| G2-IDP-06 / R6.5 | L2 | formal delivery成功後response喪失 | admin | A | A | t0 | same key retry | 200/200 | delivery/rendition 1組、same IDs | cache 1 |
| G2-IDP-07 / R6.5 | L2 | same key異payload | admin | A | A | t0 | retry changed body | 409 | operation unchanged、`IDEMPOTENCY_KEY_REUSED` | 不変 |
| G2-IDP-08 / R6.5 | L3 | same key concurrent 2request while first lease is valid | admin×2 | A | A | t0 | concurrent action then retry after completion | 200/409→200 | operation 1、domain event 1、retry returns same result | loser no mutation |
| G2-IDP-09 / R6.5 | L2 | transient failure/rollback後retry | admin | A | A | t0 | retry same key | 503→200 | failed domain 0、retry success 1 | rollback then cache 1 |
| G2-IDP-10 / R6.5 | L2 | reviewer type create response loss/concurrent same key | admin×2 | A | — | t0 | create then retry | 200/409→200 | type row 1、same result | cache 1 |
| G2-IDP-11 / R6.5 | L2 | reviewer type update response loss/concurrent same key | admin×2 | A | — | t0 | update then retry | 200/409→200 | master CAS 1、same result | cache 1 |
| G2-IDP-12 / R6.5 | L2 | reviewer type disable response loss/concurrent same key | admin×2 | A | — | t0 | disable then retry | 200/409→200 | disabled state 1、same result | cache 1 |
| G2-IDP-13 / R6.5 | L2 | review requirement update response loss/concurrent same key | admin×2 | A | A | t0 | DRAFT policy update then retry | 200/409→200 | requirement rows 1、same policy hash/result | cache 1 |
| G2-IDP-14 / R6.5/R8.2 | L3 | same delivery input with different client keys, sequential/L3 concurrent, response loss | admin×2 | A | A | t0 | K1/K2 generate then retry | 200/200 | delivery 1、business key 1、rendition group 1、3 rendition、notification 1、both results same | duplicate branch no-op or reservation rollback; cache 1 |
| G2-IDP-15 / R6.5/R8.2/R8.4 | L3 | same stable inputs at t0 and t0+1秒/翌日 with different client keys; gate remains valid | admin×2 | A | A | t0/t1 | K1/K2 generate | 200/200 | time-independent business key、delivery/group/rendition/notification各1、same result; changed snapshot/template/evidence only creates new key | gate/render audit hashes may differ; business reservation 1 |
| G2-LIFE-01 / R6.6 | L2 | mapping from=t0+1day | admin | A | A | t0 | ACTIVE | 409 | PROVISIONAL/status 0 | 不変 |
| G2-LIFE-02 / R6.6 | L2 | mapping from=t0 | admin | A | A | t0 | ACTIVE | 200 | ACTIVE 1、gate hashあり | afterCommit 1 |
| G2-LIFE-03 / R6.6 | L2 | finite to=t0 | admin | A | A | t0 / t0+1day | generate | 200 / 409 | boundary day allowed、after expired delivery 0 | 不変 |
| G2-LIFE-04 / R6.6 | L2 | old ACTIVE effective_to=NULL + future PROVISIONAL | admin | A | A | t0 / future date | create future then activate | 200 / 409 before date | future row saved with future_slot=1, old ACTIVE unchanged; activation only on effective date; successful activation sets future_slot=NULL in same CAS transaction | 不変 |
| G2-LIFE-05 / R6.6 | L2 | period gap after old expiry | admin | A | A | t0+gap | generate | 409 | delivery 0、no auto transition | cache old |
| G2-LIFE-06 / R6.6 | L2 | unused PROVISIONAL future candidate | admin | A | A | t0 | SUPERSEDE | 200 | status event 1、gate hash NULL、future_slot NULL in same transaction | afterCommit 1 |
| G2-LIFE-07 / R6.6 | L2 | old ACTIVE replacement by future candidate | admin | A | A | t0/effective date | new ACTIVE | 200 | old SUPERSEDED/new ACTIVE, same gate hash/correlation; candidate future_slot NULL and old active_slot NULL in CAS transaction | afterCommit 1 |
| G2-LIFE-08 / R6.6 | L2 | explicit ACTIVE end/no replacement | admin | A | A | t0 | SUPERSEDE | 200 | status event 1、reasonあり、gate hash NULL | delivery now fail-closed |
| G2-LIFE-09 / R6.6 | L2 | missing/invalid deployment timezone | admin | A | A | t0 | ACTIVE/generate | 409 | `GATE_TIMEZONE_UNAVAILABLE`、state/event/delivery 0 | cache 0 |
| G2-LIFE-10 / R6.6 | L3 | current ACTIVE + two different-key future candidates, same/partial/different future dates | admin×2 | A | A | t0 | concurrent future create | 200/409 | future_slot=1候補1件、loserのrow/event/cache 0 | rollback/no cache |
| G2-LIFE-11 / R6.6/R6.7 | L2/L3 | future candidate success/failure/time passage | admin×2 | A | A | t0/effective date | ACTIVE or SUPERSEDE then next create; CAS/gate failure; wait without transition | 200/409 | success transition sets future_slot=NULL and next candidate succeeds; failure/rollback keeps slot=1 and next candidate 409; time passage alone does not clear slot | rollback/cache unchanged |
| G2-DEL-12 / R8.3 | L2 | delivery後master/config/profile/worker変更 | manager/sales | A | A | t1 | download all roles | 200 | bytes/sha256 unchanged | access log only |
| G2-DEL-13 / R8.3 | L2 | delivery snapshot IDs/hashes | admin | A | A | t0 | generate | 200 | existing profile/worker snapshot ID+hash、resolved workplace_id、recipient/display/company/config snapshot hash、render_input_hash、3 rendition refs persisted | afterCommit 1 |
| G2-DEL-14 / R8.3 | L2 | one role rendition missing/unclean | admin | A | A | t0 | generate/download | 409/403 | delivery or rendition not usable | rollback/no cache |
| G2-DEL-15 / R8.3 | L2 | FULL/MASK/LIMITED same rendition group | HR/manager/sales | A | A | t1 | download | 200 | role output from exact stored version, no current reread | access log only |
| G2-DEL-16 / R8.3/T066-ASOF-01 | L2 | profile snapshotあり、交付時点以前のworker snapshotなし／片側NULL | admin/DB | A | A | t0 | generate then download/invalid insert | 200/409 | delivery成功、worker ID/hash両NULL、worker項目なし、partial NULL拒否、bytes/hash不変 | rollback/no cache |
| G2-DEL-17 / R8.4 | L2 | company/recipient/display render content A→B→A、template version同一 | admin | A | A | t0/t1/t2 | K1 generate, config change, K2 generate, K3 generate | 200/200/200 | canonicalizerのpayloadへrecipientDisplaySnapshotHash/companyConfigSnapshotHashが存在し、A≠Bで2 hash・business key・groupが変わり新delivery、K1 historical bytes不変、B→Aで元A hash・business key・READY resultを再利用 | content hash/key reservation 1 per content |
| G2-SEC-12 / R7.4 | L1 | required/optional credential single append | DB | A | A | t0 | one INSERT | — | requiredはencrypted/key version/CGC1/masked全て非NULL、optional未入力は4項目全NULL、平文/中間row 0 | 不変 |
| G2-SEC-13 / R7.4 | L1 | same credential twice / AAD operation ID | admin | A | A | t0 | review insert×2 | 200 | random IV/ciphertext differs、identity hash stable、AADはINSERT前operation_id、event ID後UPDATE 0 | afterCommit |
| G2-SEC-14 / R7.4 | L2 | restart with current/old key | app | A | A | t0 | review/gate | 200 | decrypt old version、new write current | 不変 |
| G2-SEC-15 / R7.4 | L2 | rotation old read/new write | admin | A | A | t0 | review update/new event | 200 | old event unchanged、new key version | afterCommit |
| G2-SEC-16 / R7.4 | L2 | prod key missing/placeholder/32-byte invalid | app | A | A | t0 | startup/gate | startup fail / 409 | no ACTIVE/delivery、key decode length mismatch拒否 | no cache |
| G2-SEC-17 / R7.4 | L2 | tamper/wrong key/unknown version | admin | A | A | t0 | reducer/ACTIVE | 409 | `GATE_CREDENTIAL_UNAVAILABLE`, no status | 不変 |
| G2-SEC-18 / R7.4 | L1 | response/log/exception capture | admin | A | A | t0 | review/evidence | 200 | plaintext credential 0 | audit masked |
| G2-MIG-10 / R6.6 | L2 | DRAFT source direct SQL | DB | A | A | t0 | INSERT/UPDATE/DELETE | — | DRAFT insert/edit/delete allowed by contract | transaction |
| G2-MIG-11 / R6.6 | L2 | PROVISIONAL/ACTIVE/SUPERSEDED source direct SQL | DB | A | A | t0 | INSERT/UPDATE/DELETE | — | all three rejected, hash/row unchanged | rollback |
| G2-MIG-12 / R6.6 | L2 | partial/missing source freeze trigger | DB | A | A | t0 | V102 smoke | — | trigger inventory mismatch fails apply/assert | migration rollback |

### 13.1 Assignment

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---:|---|---|
| G2-ASG-01 / R6.2 | L2 | assignment 0 | assigned候補/HR | A | A | t0 | approve/generate | 409 | event/delivery 0 | 不変 |
| G2-ASG-02 / R6.2 | L2 | from=t0+1s | actor/HR | A | A | t0 | approve | 409 | event 0 | 不変 |
| G2-ASG-03 / R6.2 | L2 | from=t0 | actor/HR | A | A | t0 | approve | 200 | APPROVE 1 | afterCommit 1 |
| G2-ASG-04 / R6.2 | L2 | to=t0 | actor/HR | A | A | t0 | approve | 409 | event 0 | 不変 |
| G2-ASG-05 / R6.2 | L2 | to<t0 | actor/HR | A | A | t0 | approve | 409 | event 0 | 不変 |
| G2-ASG-06 / R6.2 | L2 | old.to=new.from | admin | A | A | t0 | assignment create | 200 | adjacent 2 rows | afterCommit 1 |
| G2-ASG-07 / R6.2 | L2 | partial overlap | admin | A | A | t0 | assignment create | 409 | 既存1行のみ | 不変 |
| G2-ASG-08 / R6.2 | L2 | existing open-ended | admin | A | A | t0 | assignment create | 409 | 既存1行のみ | 不変 |
| G2-ASG-09 / R6.2 | L3 | 同一区間2request | admin×2 | A | A | t0 | concurrent create | 200/409 | assignment 1 | loser rollback/cache 1回 |
| G2-ASG-10 / R6.2 | L1 | open-ended 2件direct SQL | DB | A | A | t0 | INSERT | — | active_slot UNIQUE拒否 | transaction rollback |
| G2-ASG-11 / R6.2 | L2 | finite overlap | admin | A | A | t0 | lock+overlap SQL | 409 | row 0追加 | 不変 |
| G2-ASG-12 / R6.4 | L2 | old承認後に交代 | old/new actor | A | A | t0+ | generate | 409→新承認後200 | 旧event不変、新event/delivery | commit後のみ |
| G2-ASG-13 / R6.4 | L2 | A承認、B未承認 | actor A | A | B | t0 | generate B | 409 | B delivery 0 | 不変 |

### 13.2 Dynamic Review policy

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---|---|---|
| G2-POL-01 / R7.1 | L2 | type/policy空 | admin | A | A | t0 | ACTIVE | 409 | status不変 | 不変 |
| G2-POL-02 / R7.1 | L2 | 任意type `TYPE-X` | admin | A | — | t0 | type追加 | 200 | master 1 | afterCommit 1 |
| G2-POL-03 / R7.1 | L0 | source/DDL/JS | reviewer type任意 | — | — | — | enum/static/check/seed scan | — | 固定type 0件 | — |
| G2-POL-04 / R7.2 | L2 | 1group type A/B、min1、A review | admin | A | A | t0 | ACTIVE | 200 | ACTIVE event | afterCommit |
| G2-POL-05 / R7.2 | L2 | group A/B各min1、Aだけ | admin | A | A | t0 | ACTIVE | 409 | status不変 | 不変 |
| G2-POL-06 / R7.2 | L2 | min2、1 reviewer | admin | A | A | t0 | ACTIVE | 409 | status不変 | 不変 |
| G2-POL-07 / R7.2 | L2 | min2、distinct 2名 | admin | A | A | t0 | ACTIVE | 200 | ACTIVE event | afterCommit |
| G2-POL-08 / R7.2 | L2 | 同identity event 2件 | admin | A | A | t0 | ACTIVE | 409 | distinct=1 | 不変 |
| G2-POL-09 / R7.3 | L2 | freeze後type rename | admin | A | — | t0 | master PUT | 200 | policy snapshot/hash不変 | master cacheだけafterCommit |
| G2-POL-10 / R7.3 | L2 | freeze後type disable | admin | A | — | t0 | disable | 200 | 過去event/policy不変 | afterCommit |
| G2-POL-11 / R7.3 | L2 | disabled type | admin | A | — | t0 | DRAFT policy/review追加 | 409 | row/event 0 | 不変 |
| G2-POL-12 / R7.3 | L2 | PROVISIONAL policy edit | admin | A | — | t0 | PUT | 409 | hash不変、新version要求 | 不変 |
| G2-POL-13 / R7.2 | L2 | event policy hash違い | admin | A | A | t0 | ACTIVE | 409 | status不変 | 不変 |
| G2-POL-14 / R7.2 | L2 | event group違い | admin | A | A | t0 | ACTIVE | 409 | status不変 | 不変 |
| G2-POL-15 / R7.2 | L1 | corrupted empty group/min0 | DB fixture | A | — | t0 | reducer | — | invalid判定 | cache不変 |
| G2-POL-16 / R7.2 | L2 | group A/B両方成立 | admin | A | A | t0 | ACTIVE | 200 | ACTIVE event | afterCommit |

### 13.3 Approval / external Review reducer

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---|---|---|
| G2-EVT-01 / R6.5 | L2 | mapping hash違い | actor/HR | A | A | t0 | approve | 409 | event 0 | 不変 |
| G2-EVT-02 / R6.5 | L2 | policy hash違い | actor/HR | A | A | t0 | approve | 409 | event 0 | 不変 |
| G2-EVT-03 / R6.3 | L2 | assigned user≠caller | admin非actor | A | A | t0 | approve | 403 | event 0 | 不変 |
| G2-EVT-04 / R6.4 | L2 | old assignment actor | old actor/HR | A | A | t0 | approve/generate | 409 | event/delivery 0 | 不変 |
| G2-EVT-05 / R6.5 | L2 | APPROVE→REJECT→APPROVE | actor/HR | A | A | t0 | 3 action | 200 | same chain 3 events、latest APPROVE | afterCommit各1 |
| G2-EVT-06 / R6.5 | L2 | 別chain/既に無効target | actor/admin | A | A | t0 | REVOKE | 409 | revoke 0 | 不変 |
| G2-EVT-07 / R7.2 | L2 | external review 0 | admin | A | A | t0 | ACTIVE/generate | 409 | status/delivery 0 | 不変 |
| G2-EVT-08 / R7.2 | L2 | valid_until=t0 | admin | A | A | t0 | ACTIVE/generate | 409 | 失効判定 | 不変 |
| G2-EVT-09 / R7.2 | L2 | APPROVED→REVOKED | admin | A | A | t0 | ACTIVE/generate | 409 | latest REVOKED | 不変 |
| G2-EVT-10 / R6.5 | L2 | 同occurred_at 2 event | actor/admin | A | A | t0 | reducer | 200 | event_id最大がlatest | afterCommit |
| G2-EVT-11 / R6.5 | L2 | same idempotency retry | actor/admin | A | A | t0 | POST×2 | 200/200 | event 1、同ID返却 | cache 1回 |
| G2-EVT-12 / R7.2 | L2 | evidence=PENDING/INFECTED/UNKNOWN | admin | A | A | t0 | review/ACTIVE | 409 | valid event/status 0 | 不変 |
| G2-EVT-13 / R7.2 | L2 | evidence version/hash違い | admin | A | A | t0 | review/ACTIVE | 409 | event/status 0 | 不変 |
| G2-EVT-14 / R8.3 | L2 | other tenant/file link | admin | A | A | t0 | evidence選択/ACTIVE | 404 | event/status 0 | 不変 |

### 13.4 ACTIVE

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---|---|---|
| G2-ACT-01 / R6.6 | L2 | 全gate成立 | admin | A | A | t0 | ACTIVE | 200 | mapping ACTIVE、status event | afterCommit 1 |
| G2-ACT-02 / R6.6 | L3 | 同version concurrent | admin×2 | A | A | t0 | ACTIVE×2 | 200/409 | ACTIVE 1 | loser全rollback/cache 1回 |
| G2-ACT-03 / R6.6 | L2 | old ACTIVEあり | admin | A | A | t0 | new ACTIVE | 200 | old SUPERSEDED event | afterCommit |
| G2-ACT-04 / R6.6 | L2 | new PROVISIONAL | admin | A | A | t0 | new ACTIVE | 200 | new ACTIVE event、同correlation | afterCommit |
| G2-ACT-05 / R6.6 | L2 | event INSERT前後で例外 | admin | A | A | t0 | ACTIVE | 409 | mapping/event不変 | cache 0 |
| G2-ACT-06 / R6.6 | L2 | transaction未commit | admin | A | A | t0 | ACTIVE観測 | — | DB未可視 | cacheは旧値、commit後進む |

### 13.5 Delivery / preview

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---|---|---|
| G2-DEL-01 / R8.1 | L2 | ACTIVEなし | admin | A | A | t0 | generate | 409 | archive/delivery 0 | 不変 |
| G2-DEL-02 / R6.1 | L2 | request workplace=B、profile=A | admin | A | A | t0 | generate | 200 | A gate snapshotだけ | afterCommit |
| G2-DEL-03 / R6.4 | L2 | A approval、profile B | admin | A | B | t0 | generate | 409 | delivery 0 | 不変 |
| G2-DEL-04 / R6.4 | L2 | assignment交代、旧approval | admin | A | A | t0 | generate | 409 | delivery 0 | 不変 |
| G2-DEL-05 / R8.2 | L2 | mapping hash変更/new version | admin | A | A | t0 | generate same old key | 200 | 新delivery/key | afterCommit |
| G2-DEL-06 / R8.2 | L2 | policy/gate evidence変更 | admin | A | A | t0 | generate | 200 | 旧deliveryを返さず新規 | afterCommit |
| G2-DEL-07 / R8.3 | L2 | delivery後mapping SUPERSEDED | HR/manager/sales | A | A | t1 | download | 200 | access log、原版/role mask | current gate cache不使用 |
| G2-DEL-08 / R8.5 | L1 | legacy NULL snapshot / generation_state=NULL | admin | A | A | t0 | list/download | 200 | LEGACY表示、既存ACL/file scope/scan=CLEANを満たす保存済みDocumentVersionをdownload、backfill 0; READY-onlyは新規rowだけ | 不変 |
| G2-DEL-09 / R8.6 | L2 | valid DRAFT | admin/HR/manager | A | A | t0 | preview | 200 | archive 0 | cache 0 |
| G2-DEL-10 / R8.6 | L2 | valid DRAFT | admin/HR/manager | A | A | t0 | preview | 200 | delivery/notification 0 | cache 0 |
| G2-DEL-11 / R8.6 | L2 | preview PDF | admin/manager | A | A | t0 | preview | 200 | watermark、deliveryIdなし | cache 0 |

### 13.6 Security / UI

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---:|---|---|
| G2-SEC-01 / R9.1 | L2 | admin | 管理者 | A | A | t0 | manage/activate | 200 | actionに応じたevent | afterCommit |
| G2-SEC-02 / R9.1 | L2 | assigned HR | HR本人 | A | A | t0 | approve | 200 | approval event | afterCommit |
| G2-SEC-03 / R9.1 | L2 | assigned manager | manager本人 | A | A | t0 | approve | 200 | approval event | afterCommit |
| G2-SEC-04 / R9.1 | L2 | sales | 営業 | A | A | t0 | page/API | 403 | DB不変 | cache 0 |
| G2-SEC-05 / R9.1 | L2 | worker | 要員 | A | A | t0 | page/API | 403 | DB不変 | cache 0 |
| G2-SEC-06 / R9.1 | L2 | URL直打ち | unauthorized role | A | A | t0 | GET page | 403 | DB不変 | — |
| G2-SEC-07 / R9.1 | L2 | API直打ち | unauthorized role | A | A | t0 | POST | 403 | DB不変 | cache 0 |
| G2-SEC-08 / R9.2 | L2 | CSRFなし/不一致 | admin | A | A | t0 | POST/PUT | 403 | DB/event不変 | cache 0 |
| G2-SEC-09 / R9.3 | L2 | tenant/workplace/org/DataScope B | any | A | B | t0 | list/detail/evidence | 404/0件 | DB不変 | cache汚染0 |
| G2-SEC-10 / R9.3 | L1 | DTO/response/log capture | admin | A | A | t0 | bootstrap/evidence | 200 | entity/credential/storage path 0 | — |
| G2-SEC-11 / R9.3 | L0 | messages | — | — | — | — | 4 bundle scan | — | key欠落0 | — |

### 13.7 Migration

| ID / requirement | level | fixture | actor/role | tenant | workplace | asOf | operation | HTTP | expected DB state/event | rollback/cache |
|---|---|---|---|---|---|---|---|---|---|---|
| G2-MIG-01 / R10.1 | L1 | empty DB | DB | — | — | — | V1→V102 | — | schema/assert一致 | failure rollback |
| G2-MIG-02 / R10.1 | L2 | exact V101 legacy | DB | — | — | — | V102 apply | — | legacy delivery NULL維持 | forward only |
| G2-MIG-03 / R10.1 | L2 | partial table/index/trigger | DB | — | — | — | apply/retry | — | canonical shape収束 | forward repair |
| G2-MIG-04 / R10.1 | L2 | failed history/checksum | DB | — | — | — | repair→apply | — | 1回成功、history assert | runbook |
| G2-MIG-05 / R10.1 | L2 | post-apply code revert | DB | — | — | — | startup | — | DB rollback禁止 | forward migration |
| G2-MIG-06 / R10.1 | L1 | V1/V102/H2/entity/mapper | DB | — | — | — | manifest/sweep | — | 9 domain table + operation ledger、deliveryの既存profile/worker snapshot FK/ID/hash、resolved workplace_id、3 rendition refs、render_input_hash列一致。不存在のworkplace/config snapshot table/FKは0 | — |
| G2-MIG-07 / R6.5 | L2 | event各1行 | DB direct | A | A | t0 | UPDATE/DELETE×6 | — | trigger全拒否、行不変 | transaction rollback |
| G2-MIG-08 / R10.2 | L0 | common/dev/prod inventory | — | — | — | — | version scan | — | V100実在、V102予約、重複0 | — |
| G2-MIG-09 / R10.2 | L0 | S12〜S17 docs | — | — | — | — | monotonic scan | — | V103<V104<...<V108 | — |

### 13.8 R22 schema implementation direct regression

| ID | level | fixture / operation | expected |
|---|---|---|---|
| G2-ASG-14 / R6.2 | L1/L2 | open assignment 2件、finite assignmentのslot誤値、ACTIVE mappingのslot NULL | openはslot=1で1件、finite/non-ACTIVEはslot=NULL、NULL-safe CHECK/trigger |
| G2-ASG-15 / R6.2 | L2 | `DATETIME(6)` assignmentをt0−1µs/t0/t0+1µsで評価 | 半開区間を秒・マイクロ秒精度で再現し、隣接だけ許可 |
| G2-ASG-16 / R6.2 | L2 | H2/MySQLのasOf predicate、隣接、部分重複、有限終了 | `from <= asOf AND (to IS NULL OR asOf < to)`を実SQLでassertし、隣接は許可、部分重複は検出 |
| G2-FK-01 / R6.1/R7.2 | L2 | G2 childの同tenant/cross-tenant parent | `(tenant_id,parent_id)`複合FKの同tenantだけ成功、cross-tenant拒否、row count/SQLStateをassert |
| G2-FK-02 / R6.5 | L2 | approval/external eventのtarget/supersedes存在なし・別tenant | self複合FKでtarget/supersedes双方の孤立chain/別tenant参照を拒否し、拒否後row count不変 |
| G2-FK-03 / R6.1/R6.5 | L2 | mapping→group、group→type、mapping/assignment→approval、status→mappingの同tenant/cross-tenant direct INSERT | 各relation familyでsame-tenant成功、cross-tenant/孤立parent拒否、SQLState/row countとFK列順をmetadataでassert |
| G2-OP-01 / R6.5 | L0 | event/operation mapper API inventory | eventはINSERT/SELECT、operationはclaim/SELECT/CASのみ。BaseMapper/deleteById/updateById 0 |
| G2-OP-02 / R6.5 | L2 | claim初期FAILED、finished/failure付きPROCESSING、PROCESSING operationのDELETE、SUCCEEDED result改変、FAILED payload、PROCESSING payload | claimはPROCESSING/0/1/0で固定、初期不正stateと不正結果行列をDB CHECK/triggerが拒否、PROCESSING/FAILEDのresult/reference全列NULL、row不変 |
| G2-OP-03 / R6.5 | L2/L3 | PROCESSING→SUCCEEDED/FAILEDとexpected version競合、成功hash欠落 | `SUCCEEDED`はfinished_at非NULL・failure_code NULL・summary/http/hash全て必須、`FAILED`はfinished_at/failure_code必須、許可されたCASだけ1勝、terminal rowは永久保持 |
| G2-OP-04 / R6.5 | L2/L3 | retryable/non-retryable FAILED、同時restart、stale version | retryable=1だけFAILED→PROCESSINGを許可し、同時restartは1勝、非retryable/staleは拒否 |
| G2-OP-05 / R6.5 | L2 | PROCESSING→PROCESSINGでresult/reference/failureを改変 | lease/attempt/version以外のfield改変を拒否 |
| G2-OP-06 / R6.5 | L2 | FAILED/terminal rowのDELETE・result改変 | DELETEとterminal/result改変を拒否、row/result不変 |
| G2-MIG-13 / R10.1 | L1 | index/parent uniqueが既存・欠落・別phase | `information_schema`確認後、欠落だけ作成し、全named UNIQUEを含む列順/列数/NON_UNIQUE不一致は明示fail-closed |
| G2-MIG-14 / R10.1 | L1/L2 | G2 tableがabsent/partial/old definition | canonical columns/type/length/NULL/default/precisionをassertし、shape/column contract mismatchはfail-closed |
| G2-MIG-15 / R10.2 | L2 | UNIQUE/CHECK/FK/triggerがabsent、旧定義、途中失敗後 | canonical constraint manifestを検証し、named CHECKはcanonical expressionへdrop/re-add、named FK/triggerも収束、不一致はforward repair要求 |
| G2-MIG-16 / R10.2 | L2 | V102適用後にgit commitをrevert | DBをgit revertで戻さず、checksum/history確認後のforward repairのみ |
| G2-MIG-17 / R10.1 | L2 | 同名誤定義index/UNIQUE/CHECK、欠落constraint、同一DBでの途中失敗後retry | 初回V102失敗時にhistory 102未成功を確認し、同じDBの誤定義をforward repairして`repair()`後にV102を再実行、canonical index/UNIQUE/CHECKとhistory 102=1をassert |
| G2-MIG-18 / R10.1 | L2 | MySQL fresh/partial/old-definition | 重要columnの型/長さ/NULL/default/precisionをmanifest検証 |
| G2-MIG-19 / R10.2 | L2 | FK/trigger absent・旧定義・同一DBhistory repair | 同一DBの失敗後再実行でnamed FK/triggerをcanonical状態へ収束し、history 102を誤成功登録せず、成功後にFK/trigger存在をassert |
| G2-MIG-20 / R10.2 | L2 | post-apply repair/rollback | apply後はforward repair、git revertでDBを旧状態へ戻さない |

## 14. Browser acceptance

### 14.1 Phase A — external review / ACTIVE前

preview APIだけを使う。desktopと390pxで、管理者/HR=FULL、マネージャー=MASK、営業=明示403を4帳票すべてで確認する。
証拠へrun ID、screenshot、role、viewport、mapping DRAFT version/hash、review policy hash、preview flag、watermark、
font埋込み、改ページ、横overflowなし、mask、console error 0、archive 0、delivery IDなしを保存する。
テキスト抽出testを実ブラウザ目視の代替にしない。

### 14.2 Phase B — 実在review / ACTIVE後

ページで設定・freezeしたpolicyを満たす実在external reviewer、実在CLEAN evidence、実在assignment actor approvalを登録し、
controlled acceptance tenantでACTIVE化する。formal generate/archive/delivery/downloadをdesktop/390pxで実行し、
管理者/HR=FULL、マネージャー=MASK、営業=LIMITED download（generateは403）、要員=403を確認する。
4帳票それぞれのPDF SHA-256、screenshot、role、viewport、mapping version/hash、review policy hash、gate snapshot hash、
delivery ID、rendition_group_id、FULL/MASK/LIMITED DocumentVersion ID/sha256、既存profile/worker snapshot ID+hash、resolved workplace_id、render_input_hash、
downloaded file、console error 0を記録する。mappingをSUPERSEDEDにした後、同じdeliveryを再downloadし、
原版/role renditionが変わらないことを確認する。Phase Bもproduction authorizationそのものではない。

## 15. R10 acceptanceと実装順

R10はHead `3f7cc518e928c02f8eba7c08f368beb5d8f33526`のdocs deltaを独立Reviewし、
`ACCEPTED_FOR_IMPLEMENTATION`を記録済みである。以下の実装順へ進めるが、T066/S10 PASS条件と本番gateは未達のまま維持する。

1. V1/V102/H2/entity/mapper。
2. MySQL migration direct regression。
3. G2 service/API/UI/security/audit。
4. dynamic reviewer type/requirement画面。
5. ACTIVE guard。
6. delivery snapshot/idempotency。
7. L1〜L3。
8. Phase A preview browser evidence。
9. 実在external reviewer review登録。
10. ACTIVE化。
11. Phase B formal PDF browser evidence。
12. T066 L4。
13. R10最終Review Packet。

実装判断の未決定事項は0件である。実在reviewer type/組合せ/minimumはtenant管理者がページで設定する業務dataであり、
code decisionではない。未解決なのは実在actor/reviewer/evidence/browser証跡と、別trackの法務/production gateである。

## 16. 未取得証跡と既存gate

| item | 許可する選択肢 | 推奨 | 未達時の影響 |
|---|---|---|---|
| R10 decision delta Review | `ACCEPTED_FOR_IMPLEMENTATION`済み。以後は実装差分の独立Review | R10がBase/Head固定でschema/code/testをReviewする | R22 P1がOPENならT066完了、ACTIVE、本番交付、S10 PASSへ進まない |
| 実在assignment actor / approval | 権限を持つ自然人をruntime指名して本人がapproval、またはgateを閉じたままにする | 実運用責任者を管理者が指名し、対象mapping/policy hashへ本人が承認する | ACTIVE、Phase B、formal generate/delivery、T066/S10 PASSを禁止 |
| dynamic policy / external review / CLEAN evidence | tenant画面でpolicyを設定し実在reviewerと実在evidenceを記録、またはgateを閉じたままにする | code既定値やseedを作らず、実際の業務判断をfreezeして要件を満たすreviewを取得する | ACTIVE、Phase B、formal generate/delivery、T066/S10 PASSを禁止 |
| PDF browser evidence | Phase AとPhase Bを§14どおり実施、または未達として停止 | desktop/390px・role別・4帳票の両phaseを証跡化する | R19-P2-02 OPEN、T066/S10 PASSを禁止 |
| `GATE-T066-HISTORY` | 後続history specを完了、または該当fieldを要する帳票をproduction交付しない | write/correction/asOf/permission/goldenを別specで実装する | S10 PASS/S12開始は阻害しないが、該当production帳票はrelease禁止 |
| `GATE-T060-2026-10` / `GATE-T066-RETENTION` / `GATE-T060-COOLING` | 一次source・法務判断・実運用値を取得、または影響機能をfail-closedで維持 | 各既存gateの証拠所有者が決定し、旧mapping/hashへ遡及しない | 対象version、retention、抵触日算定のproduction authorizationを禁止 |

専門家type、組合せ、minimumの具体値は未決定のcode contractではなくtenantごとの業務設定である。
上表の証跡をAI生成、seed、backfill、仮actor、仮reviewで代替しない。
