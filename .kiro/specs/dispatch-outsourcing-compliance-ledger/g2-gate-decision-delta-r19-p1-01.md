# G2 gate decision delta — R19-P1-01 / T066

> 状態: `PROPOSED_FOR_R10_REVIEW / ACCEPTED_FOR_IMPLEMENTATION待ち`
>
> 発注者の2026-08-11 docs-only着手指示を、実装時の推測が残らない粒度へ具体化した正本候補である。
> R10が本書を独立Reviewし、`ACCEPTED_FOR_IMPLEMENTATION`を明示するまでは、V1、V102、既存migration、
> H2、Java、HTML、JavaScript、CSS、message bundle、test、seed、実在DBを変更しない。
> R19-P1-01を実装担当自身がcloseしない。T066は未完了、S10は`IN PROGRESS`、S12は`NOT READY`を維持する。

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
- G2の新規9表とdelivery更新は将来互換の`tenant_id VARCHAR(100) NOT NULL`を持つが、`m_tenant`は作成せず
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
| `PROVISIONAL_REVIEWED` | `-> ACTIVE` | 管理者 | §8のACTIVE transactionを全て満たす | tenant mapping ACTIVE、旧ACTIVEがあればSUPERSEDED |
| `PROVISIONAL_REVIEWED` | `-> SUPERSEDED` | 管理者 | reason、expected version | 未使用versionを終了しstatus event INSERT |
| `ACTIVE` | `-> SUPERSEDED` | ACTIVE transaction | 新version ACTIVE化または明示終了、expected version | active_slotをNULL、status event INSERT |
| `SUPERSEDED` | なし | — | terminal | 再ACTIVE化・編集不可。新versionを作る |

- `DRAFT`中だけmapping本体、source、review requirement group/typeを編集できる。
- `PROVISIONAL_REVIEWED`以降はmapping/source/policyをUPDATE/DELETEしない。変更は新しいmapping versionを作る。
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

## 4. 9 physical table contract（V102候補、現時点ではDDLを作らない）

共通規則: PKは`BIGINT id`、時刻は`DATETIME(6)`、hashは`CHAR(64)` lowercase hex、actor/user/workplace/documentは
既存PKへFKを張る。tenant parentが存在しないためtenant FKは作らない。tenant境界はNOT NULL、複合UNIQUE/index、
service再解決、SQL predicateで強制する。各parentは`UNIQUE(tenant_id,id)`を持ち、G2 childは可能な限り
`(tenant_id,parent_id)`複合FKを使用する。

### 4.1 `m_compliance_mapping_version`

| column | NULL / 意味 |
|---|---|
| `tenant_id`, `mapping_code`, `mapping_version` | NOT NULL。request値を使わない |
| `mapping_hash`, `review_policy_hash` | NOT NULL。DRAFTでも現在payloadから再計算。freeze後不変 |
| `effective_from`, `effective_to` | DATE、from NOT NULL、to NULLは無期限。通常のplatform inclusive期間 |
| `status` | NOT NULL。4状態のみ |
| `active_slot` | ACTIVEだけ1、それ以外NULL |
| `activated_at`, `activated_by` | ACTIVE/SUPERSEDEDの旧ACTIVEだけ値を保持。DRAFT/PROVISIONALはNULL |
| `version` | NOT NULL、current rowのCAS |
| actor/time | `created_by/at`, `updated_by/at` |

制約/index: `UNIQUE(tenant_id,mapping_version)`、`UNIQUE(tenant_id,mapping_code,active_slot)`、
`UNIQUE(tenant_id,id)`、index `(tenant_id,mapping_code,status,effective_from,effective_to)`、
FK `activated_by -> sys_user.id`。ACTIVE時だけslot=1をserviceとDB CHECKの双方で検証する。

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
`effective_to/active_slot/ended_by/end_reason`はopen assignmentではNULL。終了時は`effective_to/ended_by/end_reason`を必須にする。
`UNIQUE(tenant_id,workplace_id,active_slot)`、`UNIQUE(tenant_id,id)`、
index `(tenant_id,workplace_id,effective_from,effective_to)`と`(tenant_id,user_id,effective_from,effective_to)`、
FK `workplace_id -> m_workplace.id`, `user_id/assigned_by/ended_by -> sys_user.id`。
§2.2のworkplace anchor lock + overlap SQLを必須とする。

### 4.7 `t_compliance_mapping_approval_event`

columns: `tenant_id`, `mapping_id`, `mapping_version`, `mapping_hash`, `review_policy_hash`, `assignment_id`,
`workplace_id_snapshot`, `actor_id`, `actor_display_name_snapshot`, `actor_role_snapshot`, `action`,
`event_chain_id`, `target_event_id`, `supersedes_event_id`, `occurred_at`, `reason`,
`evidence_document_id`, `evidence_document_version_id`, `evidence_document_version`, `evidence_document_hash`,
`correlation_id`, `idempotency_key`, `created_at`。
reasonはAPPROVEだけNULL可、REJECT/REVOKEは必須。evidence4項目はAPPROVEでNOT NULL相当、他actionではtargetから解決し
NULLを許す。`UNIQUE(tenant_id,idempotency_key)`、`UNIQUE(tenant_id,id)`、index
`(tenant_id,mapping_id,workplace_id_snapshot,assignment_id,occurred_at,id)`、chain/target index、mapping/assignment/user/
document/version/self FK。INSERTのみ。DB triggerで直接UPDATE/DELETEを拒否する。

### 4.8 `t_compliance_external_review_event`

columns: `tenant_id`, `mapping_id`, `mapping_version`, `mapping_hash`, `review_policy_hash`,
`requirement_group_id`, `requirement_group_code_snapshot`, `reviewer_type_id`, `reviewer_type_code_snapshot`,
`reviewer_type_name_snapshot`, `reviewer_name_snapshot`, `organization_snapshot`,
`credential_snapshot_encrypted`, `credential_masked_snapshot`, `reviewer_identity_hash`, `action`,
`review_chain_id`, `target_event_id`, `supersedes_event_id`, `reviewed_at`, `valid_until`, `recorded_at`,
`evidence_document_id`, `evidence_document_version_id`, `evidence_document_version`, `evidence_document_hash`,
`recorded_by`, `correlation_id`, `idempotency_key`。
organization/credential/valid_untilはpolicyに応じてNULL可。credential_required typeでcredential NULLは拒否。
`UNIQUE(tenant_id,idempotency_key)`、`UNIQUE(tenant_id,id)`、index
`(tenant_id,mapping_id,requirement_group_id,reviewer_identity_hash,recorded_at,id)`、chain/target/valid_until index、
mapping/group/type/document/version/user/self FK。INSERTのみ。資格情報の平文保存・response返却は禁止する。
DB triggerで直接UPDATE/DELETEを拒否する。

### 4.9 `t_compliance_mapping_status_event`

columns: `tenant_id`, `mapping_id`, `mapping_version`, `mapping_hash`, `review_policy_hash`, `before_status`,
`after_status`, `actor_id`, `actor_display_name_snapshot`, `actor_role_snapshot`, `occurred_at`, `expected_version`,
`gate_snapshot_hash`, `correlation_id`, `reason`, `created_at`。
PROVISIONAL transitionではgate_snapshot_hash NULL、ACTIVE/SUPERSEDED transitionではNOT NULL相当。
`UNIQUE(tenant_id,id)`、index `(tenant_id,mapping_id,occurred_at,id)`と`(tenant_id,correlation_id)`、
mapping/user FK。INSERTのみ。DB triggerで直接UPDATE/DELETEを拒否する。

### 4.10 DB immutability

V102はapproval/external review/status eventの各tableへ`BEFORE UPDATE`/`BEFORE DELETE` triggerを作り、
`SIGNAL SQLSTATE '45000'`で直接変更を拒否する。application mapperはINSERT/SELECTだけを公開する。
MySQL direct regressionはapplicationを経由しないSQLでUPDATE/DELETEを発行し、全6操作が拒否され、行/hashが不変で
あることをassertする。修復が必要な場合は公開済みV102を編集せず、承認済みforward repair migrationで扱う。

## 5. Operation / transition decision table

| operation / transition | scope | caller role | service actor condition | current status | assignment条件 | approval条件 | external Review policy条件 | evidence条件 | mapping/policy hash条件 | SQL tenant/workplace境界 | success state/event | failure code | idempotency | cache | audit |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| DRAFT作成/編集 | tenant mapping | 管理者 | active user | DRAFT | 不要 | 不要 | 非空group/type/minimum整合 | source/evidence pickerはscope内 | write後3 hashのうちmapping/policyを再計算 | deployment tenant | DRAFT row、監査 | 403/`GATE_DRAFT_FROZEN` | request key+CAS | afterCommit | API audit+before/after hash |
| PROVISIONAL化 | tenant mapping | 管理者 | active user | DRAFT | 不要 | 不要 | 非空・整合・freeze可能 | L0/独立Review evidence CLEAN | 再計算一致 | tenant+mapping | PROVISIONAL status event | `GATE_POLICY_INVALID`/409 | key+CAS | afterCommit | status event |
| assignment作成 | workplace | 管理者 | assignee role eligible | 任意 | 半開区間、overlap 0 | 不要 | 不要 | 任命理由 | — | tenant+workplace lock | assignment INSERT | `GATE_ASSIGNMENT_CONFLICT`/409 | key+UNIQUE | afterCommit | API audit |
| assignment終了 | workplace | 管理者 | active user | 任意 | expected version、end>from | 不要 | 不要 | end reason | — | tenant+workplace lock | assignment CAS | 409 | key+CAS | afterCommit | API audit |
| internal APPROVE/REJECT/REVOKE | workplace+mapping | 管理者/HR/マネージャー | asOf有効assignmentのuser本人。管理者bypassなし | PROVISIONAL/ACTIVE | actorとassignment一致 | reducer §7 | 不要 | APPROVEはCLEAN exact evidence | mapping/policy完全一致 | tenant+workplace+assignment | append event | 403/`GATE_ACTOR_MISMATCH`、409 | tenant idempotency UNIQUE | afterCommit | domain event+API audit |
| external review登録 | tenant mapping+group | 管理者 | recorder本人 | PROVISIONAL/ACTIVE | 不要 | 不要 | enabled typeがfreeze groupに存在 | exact version/hash+CLEAN | mapping/policy完全一致 | tenant+mapping+group | append APPROVED/REJECTED | `GATE_REVIEW_TYPE_INVALID`等409 | tenant idempotency UNIQUE | afterCommit | domain event+API audit |
| external review REVOKE | tenant mapping+group | 管理者 | recorder本人 | PROVISIONAL/ACTIVE/SUPERSEDED | 不要 | 不要 | targetが同chainの有効positive | target evidence再解決 | target hash完全一致 | tenant+target event | append REVOKED | `GATE_REVOKE_TARGET_INVALID`/409 | tenant idempotency UNIQUE | afterCommit | domain event+API audit |
| ACTIVE化 | tenant mapping | 管理者 | active user | PROVISIONAL | request approval eventのassignmentがasOf有効 | 指定event有効 | 全group成立 | 全evidence CLEAN/exact | 完全一致 | tenant、approval workplace | 旧SUPERSEDED+新ACTIVE events | §8 failure codes | key+CAS+active slot | commit後のみ | 2 status events+API audit |
| formal generate/delivery | contract workplace | 管理者/HR/マネージャー | role+DataScope | ACTIVE | profile workplaceの現assignment有効 | 同assignment actorの有効APPROVE | 現在時点で全group成立 | CLEAN/exact | current mapping/policy一致 | tenant+contract+profile workplace | archive+delivery+gate hash | `GATE_*`/409、scope404 | composite idempotency | afterCommit | delivery+API audit |
| preview | contract workplace | 管理者/HR/マネージャー | role+DataScope | DRAFT/PROVISIONAL/ACTIVE | 不要 | 不要 | 構造的に非空/整合 | document evidence不要 | draft mapping/policy再計算一致 | tenant+contract+profile workplace | watermark responseのみ | 403/409 | request key、永続行0 | cache更新なし | API auditのみ |
| delivery一覧/confirm | contract workplace | 既存R4 matrix | role+DataScope | delivery状態 | 新gate再評価不要 | 新gate再評価不要 | 新gate再評価不要 | file scope | 保存済hashを表示 | tenant+contract+workplace | 既存状態CAS | 403/404/409 | CAS | afterCommit | API audit |
| 過去delivery download | delivery snapshot | 管理者/HR/マネージャー/営業 | document ACL+role mask | delivery済み | 現assignment不要 | 現approval不要 | 現review不要 | archived file/versionがCLEAN | 保存済hashのみ | tenant+delivery+contract ACL | FULL原版またはsnapshot由来MASK/LIMITED | 403/404/`FILE_NOT_CLEAN` | access log key | current gate cache不使用 | download access log |

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

## 7. Event reducer

### 7.1 共通順序とchain

- eventはappend-only。UPDATE/DELETEしない。
- chain IDは同じ論理対象で維持する。internal approvalは`tenant+mapping+workplace+assignment`、external reviewは
  `tenant+mapping+group+reviewerIdentityHash`が論理対象である。
- reducer順序はinternal=`occurred_at ASC,event_id ASC`、external=`recorded_at ASC,event_id ASC`。
  同一時刻はDB採番event IDが大きい方をlatestとする。
- `supersedes_event_id`は同chainの直前latest event、`target_event_id`はREJECT/REVOKEが無効化するpositive eventを指す。
- REVOKE/REJECTのtargetは同tenant/chain/mapping/hash/groupで、asOf直前に有効なpositive eventでなければ拒否する。
- APPROVE/APPROVEDの再登録は、直前REJECT/REVOKEを`supersedes`して同chainに追加できる。新positive eventがlatestとなる。
- idempotency retryは`UNIQUE(tenant_id,idempotency_key)`で同じresult eventを返し、別eventを作らない。
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
旧ACTIVEのSUPERSEDED化と新versionのACTIVE化は別transitionであり、同一correlation IDの2 status eventを保存する。

## 8. ACTIVE transaction

1. transaction開始時にClockから`asOf`を1回だけ確定する。
2. target mapping versionをtenant+ID+expected versionでCAS lockする。
3. mapping/source/review policyがPROVISIONAL_REVIEWEDかつfreeze済みで、canonical hash再計算が一致することを確認する。
4. requestで指定された`approvalEventId`からassignment、tenant、workplace、actorをDB再解決する。
5. assignmentがasOf時点で有効であることを確認する。
6. approval actorが実際のassignment user本人であることを確認する。
7. mapping ID/version/hashとreview policy hashの完全一致を確認する。
8. freeze済みreview policyの全groupを評価する。
9. groupごとのdistinct reviewer数を評価する。
10. external reviewの期限、REVOKE、hash、group、type snapshotを確認する。
11. 全evidenceのtenant/file scope、exact version/hash、scan=CLEANを確認する。
12. 既存ACTIVEをexpected version CASでSUPERSEDEDへ遷移する。
13. 旧ACTIVEのactive_slotをNULLにする。
14. target mappingをexpected version CASでACTIVEへ遷移する。
15. target mappingのactive_slotを1にする。
16. 旧・新双方のstatus eventを同じcorrelation IDでappendする。
17. 任意の失敗時はmapping、event、cache予約を全rollbackする。
18. cache invalidationは`ScopeChangeInvalidator`等の既存afterCommit境界からだけ実行する。

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

既存行へのactor/mapping/reviewの捏造backfillは禁止する。DTOはlegacyを`LEGACY_GATE_SNAPSHOT_UNAVAILABLE`として表示する。
新規deliveryは上記snapshotなしで保存できない。idempotency keyとarchive version discriminatorは少なくとも
`contractId,documentType,templateVersion,complianceSnapshotHash,mappingVersionId,mappingVersion,mappingHash,reviewPolicyHash,gateSnapshotHash`
を含む。mapping/policy/review evidence切替後に旧delivery/archiveを新規生成結果として返さない。

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

downloadはcurrent gateを再評価しない。管理者/HRのFULLは保存済みarchive原版を返す。マネージャー/営業は保存済みdelivery
snapshotだけからMASK/LIMITED renditionを作り、current mapping/master/reviewを読んで内容を変えない。
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
| G2-DEL-08 / R8.2 | L1 | legacy NULL snapshot | admin | A | A | t0 | list/download | 200 | LEGACY表示、backfill 0 | 不変 |
| G2-DEL-09 / R8.4 | L2 | valid DRAFT | admin/HR/manager | A | A | t0 | preview | 200 | archive 0 | cache 0 |
| G2-DEL-10 / R8.4 | L2 | valid DRAFT | admin/HR/manager | A | A | t0 | preview | 200 | delivery/notification 0 | cache 0 |
| G2-DEL-11 / R8.4 | L2 | preview PDF | admin/manager | A | A | t0 | preview | 200 | watermark、deliveryIdなし | cache 0 |

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
| G2-MIG-06 / R10.1 | L1 | V1/V102/H2/entity/mapper | DB | — | — | — | manifest/sweep | — | 9表+delivery列一致 | — |
| G2-MIG-07 / R6.5 | L2 | event各1行 | DB direct | A | A | t0 | UPDATE/DELETE×6 | — | trigger全拒否、行不変 | transaction rollback |
| G2-MIG-08 / R10.2 | L0 | common/dev/prod inventory | — | — | — | — | version scan | — | V100実在、V102予約、重複0 | — |
| G2-MIG-09 / R10.2 | L0 | S12〜S17 docs | — | — | — | — | monotonic scan | — | V103<V104<...<V108 | — |

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
delivery ID、downloaded file、console error 0を記録する。mappingをSUPERSEDEDにした後、同じdeliveryを再downloadし、
原版/role renditionが変わらないことを確認する。Phase Bもproduction authorizationそのものではない。

## 15. R10 acceptanceと実装順

R10が本書と同期docsを独立Reviewし、`ACCEPTED_FOR_IMPLEMENTATION`を記録した後だけ次へ進む。

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
| R10 decision delta Review | `ACCEPTED_FOR_IMPLEMENTATION`、またはdecision ID付き差戻し | R10がBase/Head固定の独立docs Reviewを行う | V102、DDL、code、testへ進まない。R19-P1-01 OPEN |
| 実在assignment actor / approval | 権限を持つ自然人をruntime指名して本人がapproval、またはgateを閉じたままにする | 実運用責任者を管理者が指名し、対象mapping/policy hashへ本人が承認する | ACTIVE、Phase B、formal generate/delivery、T066/S10 PASSを禁止 |
| dynamic policy / external review / CLEAN evidence | tenant画面でpolicyを設定し実在reviewerと実在evidenceを記録、またはgateを閉じたままにする | code既定値やseedを作らず、実際の業務判断をfreezeして要件を満たすreviewを取得する | ACTIVE、Phase B、formal generate/delivery、T066/S10 PASSを禁止 |
| PDF browser evidence | Phase AとPhase Bを§14どおり実施、または未達として停止 | desktop/390px・role別・4帳票の両phaseを証跡化する | R19-P2-02 OPEN、T066/S10 PASSを禁止 |
| `GATE-T066-HISTORY` | 後続history specを完了、または該当fieldを要する帳票をproduction交付しない | write/correction/asOf/permission/goldenを別specで実装する | S10 PASS/S12開始は阻害しないが、該当production帳票はrelease禁止 |
| `GATE-T060-2026-10` / `GATE-T066-RETENTION` / `GATE-T060-COOLING` | 一次source・法務判断・実運用値を取得、または影響機能をfail-closedで維持 | 各既存gateの証拠所有者が決定し、旧mapping/hashへ遡及しない | 対象version、retention、抵触日算定のproduction authorizationを禁止 |

専門家type、組合せ、minimumの具体値は未決定のcode contractではなくtenantごとの業務設定である。
上表の証跡をAI生成、seed、backfill、仮actor、仮reviewで代替しない。
