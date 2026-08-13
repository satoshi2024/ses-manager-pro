# 本人性確認・資格有効性確認・Review作成者確認 decision delta R23-P1-01（corrected v3） / S10 T066

> 状態 `SUBMITTED_FOR_R10_REVIEW / DOCS_ONLY`（2026-08-13・corrected v3）
>
> 発注者の「S10/T066 本人性確認・資格有効性確認・Review作成者確認の是正」指示（docs-only着手）に基づく
> decision packetのcorrected v3である。R10のR23-P1-01（corrected）独立Review
> `CHANGES_REQUIRED / SPEC_CONCRETIZATION_REQUIRED`（issue A〜J）と、pre-R10独立確認（issue K1〜K8）を反映する。
>
> ## Provenance（固定）
>
> - **authoritative Base = `8ffbcddbc475e61e42eb52f392f12e4e3f2b014d`**
> - **previous R10 reviewed Head = `f42faea00f55417a9ec6fd5656a18e60d240b46f`**
> - **corrected v2 Head = `361558cc7a8ed060d7fa6e198528c568936c3ed1`**
> - **corrected v3 Head = 本commit**（361558ccの子commit・Markdown 2件のみ・+/-は§提示値）
> - **observed main = `31d2930593fe430a62fe296bcc1b43e122dd11f4`**
> - pre-R10確認済み: 361558ccのparent=f42faea0・Base..361558ccはMarkdown 2件のみ・+377/-0・
>   non-Markdown diff 0・diff --check PASS・V102 blob不変・V102_1未作成・local/remote branch一致。
>   このboundaryを維持する。
> - 旧提出candidate `de3cc8b7` はdocs-only不成立として履歴に残す。
> - **repository上でV102はpublished/immutable**。対象environmentの適用状態は
>   `flyway_schema_history` 未採取のため **UNKNOWN**。
>
> 本packetはdocs-onlyであり、R10が `ACCEPTED_FOR_IMPLEMENTATION` を記録するまで
> 新規verification DDL・entity・service・migration（V102_1含む）・API・UI・security・
> ACTIVE/formal delivery関連の追加変更を一切開始しない。
> 既存 `V102__dispatch_compliance_g2_gate_schema.sql` は変更禁止である。
> R10受理は§3〜§5の実装開始許可だけであり、先行実装・T066 PASS・S10 PASS・S12開始・
> 本番ACTIVE化を自動承認するものではない。

## 0. Decision ID一覧

| decision ID | 確定する論点 |
|---|---|
| `G2-VERIFY-01` | 外部Reviewの4独立検証対象: IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP |
| `G2-VERIFY-02` | reviewer type・official source・method・flagはtenant管理の動的master（固定valueをDB CHECK/Java enum/static Set/seedへ入れない） |
| `G2-VERIFY-03` | gate採用条件（IDENTITY/AUTHORSHIP常時必須・QUALIFICATION/ACTIVE_STATUSはfrozen flag条件付き・asOf有効・未REVOKED・exact CLEAN evidence） |
| `G2-VERIFY-04` | デジタル資格者証VALIDだけでは本人性を成立させない・単独確認の禁止 |
| `G2-VERIFY-05` | 初期実装は公的sourceを用いた手動確認（公式API・scraping/server fetch禁止） |
| `G2-VERIFY-06` | UI表記（「本人性確認」「資格有効性確認」「Review作成者確認」・credentialは「入力済・未検証」） |
| `G2-VERIFY-07` | My Number非保存・本人確認書類全面コピー非保存・確認metadataのみ保存 |
| `G2-VERIFY-08` | 受理後schema: `t_compliance_external_reviewer_verification_event`・`t_compliance_external_review_adoption_event`（append-only・UPDATE/DELETE拒否trigger） |
| `G2-VERIFY-09` | gate採用はAPPROVED adoption eventのみ・verification参照欠落fail-closed |
| `G2-VERIFY-10` | reviewer_subject_idをperson-stable DB正本・fingerprintはtenant-HMAC snapshot・key version/rotation |
| `G2-VERIFY-11` | 4 verification eventの同一tenant・subject・reviewer type制約 |
| `G2-VERIFY-12` | REVIEW_AUTHORSHIPのmapping/policy/external review/exact evidence一致（binding列） |
| `G2-VERIFY-13` | distinct reviewer判定はreviewer_subject_id（self-declared hash不使用） |
| `G2-VERIFY-14` | IDENTITY・AUTHORSHIP常時必須・QUALIFICATION/ACTIVE_STATUSはfrozen flag（NULL=UNCONFIGURED・freeze点・hash包含） |
| `G2-VERIFY-15` | revoke/supersedesの同一tenant・subject・kind制約・tenant複合FK・nullability・CHECK・operation ledger・idempotency replay |
| `G2-EVENT-ORDER-01` | SUBMITTED→verification→APPROVED/adoption→REVOKEDのappend-only event順序（polymorphic target不使用・用途別FK列） |
| `G2-SUBJECT-01` | tenant別external reviewer subject master・qualification association |
| `G2-P0-FIX-01..12` | 既存P0修正（空group skip削除・validateFrozenReviewPolicy統一・evidence resolver・gate評価service共通化等） |
| `G2-VERIFY-16` | `/compliance-gate` UI・tabs・capability server計算・typed DTO |
| `G2-VERIFY-17` | regression matrix（§6） |

## 1. Base / Head

### 1.1 Base（authoritative・実測）

- **Base = `8ffbcddbc475e61e42eb52f392f12e4e3f2b014d`**
- 適用済み正本: `g2-gate-decision-delta-r19-p1-01.md`（R10受理・実装中）・`migration-order-decision-r4-p1-01.md`
- migration: repository上でV1〜V102がpublished/immutable（欠番は§3.5に全列挙）。S12〜S17はV103〜V108予約。
- **外部Review実装の現状（実測・Base内に存在）**:
  - API/write pathは存在する: `ComplianceGateApiController.java:121-137`（`POST /external-reviews`・
    `GET /mappings/{id}/external-reviews`）、`ComplianceGateAdminServiceImpl.java:294-355`
    （`recordExternalReview`・`listExternalReviews`）。
  - ただし不適合: tenant固定・Map/entity API・exact evidence・verification・gate適用が未実装。
  - credential文字列の入力・暗号化（`credential_snapshot_encrypted`/key version/cipher/masked）・復号可能性のみ。
    資格の実在性・提示者本人性・Review作成者本人性は未検証。
  - `reviewer_identity_hash`は自称氏名/組織からの単純SHA-256の懸念（§G2-VERIFY-13でgate判定から排除。
    R19 §6.3のself-declared identity hash契約は、gate/distinct判定について本R23 decisionがsupersedeする）。
  - evidenceはsnapshot列のみで、server-side解決・CLEAN検証なし。verification参照列なし。
  - `/compliance-gate`ページ・`ComplianceGateEvidenceResolver`・`ComplianceGateEvaluationService`・
    formal generateのgate適用は未実装。
- policy検証の現状: `assertPolicyNotEmpty`（group 1件のみ・type非空未検証）+ `policyHashMismatch` 照合。

### 1.2 Head（本packet受理後）

- **corrected v3 docs-only Head = 本packet + review-ledger.mdのpre-R10確認記録・v3提出記録のみのMarkdown commit**
  （361558ccの子commitとしてisolatedに作成。Java production code・Java test・migration/DDL・V102_1・
  entity/mapper/service/API/UI/security・HTML/JS/CSS/messages・tasks checkbox・S10/S12 status・
  ACTIVE/formal delivery・seed/backfillは含めない）
- 実装はR10 `ACCEPTED_FOR_IMPLEMENTATION` 後に、§3（V102_1 schema）→§4（P0修正）→§5（API/UI/security）の順

## 2. 変更予定範囲（scope・受理後）

| 種別 | 対象 | 内容 |
|---|---|---|
| 新規migration | `V102_1__reviewer_verification_events.sql`（受理後） | §3の新規table・CHECK forward replacement・subject master |
| 変更禁止 | `V102`・`V84`・`V85`・`V101` | published/immutableのため変更しない |
| 新規entity/mapper | `ComplianceExternalReviewerVerificationEvent`・`ComplianceExternalReviewAdoptionEvent`・`ComplianceExternalReviewerSubject`・各Mapper | INSERT/SELECTのみ |
| 新規service | `ComplianceGateEvidenceResolver`・`ComplianceGateEvaluationService`・subject/fingerprint service | §4 |
| 変更service | `ComplianceMappingServiceImpl`・`ComplianceApprovalServiceImpl`・`ComplianceGateAdminServiceImpl`・外部Review記録service | P0修正・gate統合 |
| 新規controller/page | `/compliance-gate`ページ・tabs・verification API | §5 |
| 新規/変更test | §6 regression・H2 schema・metadata manifest・MySQL smoke | 受理後同期 |

## 3. 受理後のschema（V102_1候補）

### 3.1 version順序契約

- Flyway version: `V102` < `V102_1`（=102.1）< `V103`。`V66_1`・`V74_1`・`V74_2`・`V79_1`の既存実績と同一規則。
- version重複0・outOfOrder不要。
- V102 blob/checksum golden・V102_1存在・Flyway実version順序の検証testは実装受理後に作成する。
- S12〜S17のV103〜V108予約を維持できる候補としてV102_1を採用する。

### 3.2 event順序契約（G2-EVENT-ORDER-01・K1/K2対応）

**K1: `chk_g2_external_review_action`のforward replacement**

- V102の `chk_g2_external_review_action` は `('APPROVED','REJECTED','REVOKED')` のみ許可。
- V102は変更禁止。**V102_1でCHECKをforward replacement**する:
  `action IN ('SUBMITTED','APPROVED','REJECTED','REVOKED')`
- 新規write pathは `SUBMITTED` だけをreview submissionとして使用する。
- 既存 `APPROVED/REJECTED/REVOKED` rowは**legacy扱い**:
  - legacy rowへverificationをbackfillしない
  - legacy row単独では新gate不採用（gateはadoption eventのみ採用・§G2-VERIFY-09）
- V1（consolidated baseline）・H2 schema・metadata manifest・MySQL smokeを同期する。

**event順序（append-only・後付けUPDATE禁止）**

| step | event | table | FK参照 | gate eligibility |
|---|---|---|---|---|
| 1 | **SUBMITTED** | `t_compliance_external_review_event`（V102既存・action='SUBMITTED'） | review_chain_id採番 | 不採用（単独ではgate不可） |
| 2 | **IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP** verification | `t_compliance_external_reviewer_verification_event`（新規） | submitted_review_event_id→step1 row | 不採用（単独ではgate不可） |
| 3 | **APPROVED / REJECTED** adoption | `t_compliance_external_review_adoption_event`（新規） | submitted_review_event_id→step1 row・verification event ID×4 | **gateはAPPROVED adoption eventのみ採用** |
| 4 | **REVOKED** adoption | `t_compliance_external_review_adoption_event`（新規・action='REVOKED'） | revoked_adoption_event_id→APPROVED adoption row | 不採用（最新adoption actionがREVOKEDならgate不可） |

**単純契約（K2指示の契約を正式採用）**

- 1 SUBMITTED chainにつき初回APPROVEDまたはREJECTEDは1件。
- REVOKEDはAPPROVED adoptionだけをtargetにできる。
- reject/revoke後の再Reviewは**新しいSUBMITTED chain**として作成する（同一chainの再利用禁止）。
- 原row UPDATE・polymorphic `target_event_id` は使用しない。

**K3: operation・transaction・reducer**

- 「step 1〜4を同一operation ledger claimで実行」を**廃止**。REVOKEは初回Reviewより後に発生する独立操作。
  各actionを**別operation claim・別transaction**とする:
  1. `EXTERNAL_REVIEW_SUBMIT`
  2. `REVIEWER_VERIFICATION_RECORD`
  3. `REVIEWER_VERIFICATION_REVOKE`
  4. `EXTERNAL_REVIEW_ADOPT`
  5. `EXTERNAL_REVIEW_REVOKE`
- 各operationで: asOfを1回だけ確定・canonical request hashを確定・
  同一key＋同一hashは200 replay・同一key＋異なるhashは409・
  transaction failureはevent/operationをrollback・afterCommit後だけcache失効。
- **gate採用条件（固定）**:
  - exact APPROVED adoption event
  - adoptionがREVOKEDされていない
  - adoptionが参照する必要verification event（当該frozen policyが要求するverification set）がVERIFIED
  - verificationがREVOKEDされていない
  - verificationがasOf時点で有効（§3.7 expiry式）
  - mapping/policy/evidence snapshotが完全一致
- adoption reducerの正本時刻列は schemaに存在する **`adopted_at, id`** に統一（存在しない`occurred_at`は使用しない）。
- 「4 verification」は「**当該frozen policyが要求するverification set**」へ全文統一する。

### 3.3 `t_compliance_external_reviewer_verification_event`（新規・append-only・K2/K7完全具体化）

| 列 | 型 | nullability | 備考 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | NOT NULL | |
| tenant_id | VARCHAR(100) | NOT NULL | |
| reviewer_type_id | BIGINT | NOT NULL | tenant複合FK `(tenant_id, reviewer_type_id)`→master |
| reviewer_type_code_snapshot | VARCHAR(100) | NOT NULL | freeze時snapshot |
| reviewer_type_name_snapshot | VARCHAR(200) | NOT NULL | |
| reviewer_subject_id | BIGINT | NOT NULL | **person-stable DB正本（K4）**・tenant複合FK `(tenant_id, reviewer_subject_id)`→`t_compliance_external_reviewer_subject` |
| person_fingerprint_snapshot | CHAR(64) | NOT NULL | tenant-HMAC・domain=person |
| qualification_fingerprint_snapshot | CHAR(64) | NOT NULL | tenant-HMAC・domain=qualification |
| fingerprint_key_version | VARCHAR(64) | NOT NULL | |
| verification_kind | VARCHAR(20) | NOT NULL | IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP |
| result | VARCHAR(20) | NOT NULL | VERIFIED / FAILED / INCONCLUSIVE / REVOKED |
| method_code | VARCHAR(50) | NOT NULL | dynamic masterのmethod code（§3.8） |
| authority_source_code | VARCHAR(50) | NOT NULL | dynamic masterのsource code（§3.8） |
| authority_source_name | VARCHAR(200) | NOT NULL | |
| official_url_reference_snapshot | VARCHAR(1000) | NULL可 | 公式URL（server-side fetch禁止） |
| registration_identifier_encrypted | TEXT | result別（§3.9） | 専用鍵・AES-GCM |
| registration_identifier_key_version | VARCHAR(64) | result別 | |
| registration_identifier_cipher_format | VARCHAR(20) | result別 | |
| registration_identifier_masked_snapshot | VARCHAR(255) | result別 | |
| checked_at | DATETIME(6) | NOT NULL | |
| source_data_as_of | DATETIME(6) | NULL可 | |
| max_age_days_snapshot | INT | kind別（§3.7） | frozen max age（日） |
| valid_until | DATETIME(6) | kind別（§3.7） | authority由来の有効期限 |
| checked_by | BIGINT | NOT NULL | 確認者 |
| evidence_document_id | BIGINT | kind別（§3.9） | |
| evidence_document_version_id | BIGINT | kind別 | tenant複合FK `(tenant_id, evidence_document_version_id)`→t_document_version |
| evidence_document_version | VARCHAR(100) | kind別 | |
| evidence_document_hash | CHAR(64) | kind別 | SHA-256 |
| review_policy_version | VARCHAR(50) | AUTHORSHIPのみNOT NULL | §3.6の正本（mapping version snapshot） |
| review_policy_hash | CHAR(64) | AUTHORSHIPのみNOT NULL | |
| mapping_id | BIGINT | AUTHORSHIPのみNOT NULL | tenant複合FK `(tenant_id, mapping_id)`→m_compliance_mapping_version |
| mapping_version | VARCHAR(50) | AUTHORSHIPのみNOT NULL | |
| mapping_hash | CHAR(64) | AUTHORSHIPのみNOT NULL | |
| external_review_event_id | BIGINT | AUTHORSHIPのみNOT NULL | tenant複合FK `(tenant_id, external_review_event_id)`→SUBMITTED row |
| external_review_chain_id | VARCHAR(36) | AUTHORSHIPのみNOT NULL | |
| submitted_review_event_id | BIGINT | NOT NULL | **K2**・tenant複合FK `(tenant_id, submitted_review_event_id)`→SUBMITTED row |
| revoked_verification_event_id | BIGINT | result='REVOKED'時のみNOT NULL | **K2**・self-FK（同一tenant・subject・kindのverification row） |
| supersedes_verification_event_id | BIGINT | NULL可 | **K2**・self-FK（同一submitted review・subject・kindの旧verification row） |
| operation_id | VARCHAR(36) | NOT NULL | |
| correlation_id | VARCHAR(100) | NOT NULL | |
| idempotency_key | VARCHAR(200) | NOT NULL | |
| created_at | DATETIME(6) | NOT NULL | |

- `UNIQUE(tenant_id, idempotency_key)`・`UNIQUE(tenant_id, id)`
- **UPDATE/DELETEはMySQL triggerで拒否**。
- **K2: polymorphic `target_event_id` は使用しない**。用途別列（submitted_review_event_id・
  revoked_verification_event_id・supersedes_verification_event_id）のみ。
- **G2-VERIFY-11**: 4 verification eventは同一tenant・同一subject（reviewer_subject_id）・
  同一reviewer type・同一submitted_review_event_idに属する。別subject/type/chainのeventを組み合わせ不可。
- **G2-VERIFY-12**: AUTHORSHIP eventはmapping ID/version/hash・review_policy_version/hash・
  external_review_event_id/chain_id・exact evidence version/hashと一致する。

### 3.4 `t_compliance_external_review_adoption_event`（新規・append-only・K2/K7完全具体化）

| 列 | 型 | nullability | 備考 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | NOT NULL | |
| tenant_id | VARCHAR(100) | NOT NULL | |
| action | VARCHAR(20) | NOT NULL | APPROVED / REJECTED / REVOKED |
| review_chain_id | VARCHAR(36) | NOT NULL | SUBMITTED chainと同一 |
| submitted_review_event_id | BIGINT | NOT NULL | **K2**・常時NOT NULL・tenant複合FK `(tenant_id, submitted_review_event_id)`→SUBMITTED row |
| revoked_adoption_event_id | BIGINT | action='REVOKED'時のみNOT NULL | **K2**・self-FK（APPROVED adoption row） |
| identity_verification_event_id | BIGINT | APPROVEDのみNOT NULL | tenant複合FK |
| qualification_verification_event_id | BIGINT | APPROVED時・frozen flag=trueのtypeのみNOT NULL | |
| active_status_verification_event_id | BIGINT | APPROVED時・frozen flag=trueのtypeのみNOT NULL | |
| authorship_verification_event_id | BIGINT | APPROVEDのみNOT NULL | |
| mapping_id | BIGINT | APPROVEDのみNOT NULL | |
| mapping_version | VARCHAR(50) | APPROVEDのみNOT NULL | |
| mapping_hash | CHAR(64) | APPROVEDのみNOT NULL | |
| review_policy_version | VARCHAR(50) | APPROVEDのみNOT NULL | §3.6の正本 |
| review_policy_hash | CHAR(64) | APPROVEDのみNOT NULL | |
| evidence_document_id | BIGINT | APPROVEDのみNOT NULL | |
| evidence_document_version_id | BIGINT | APPROVEDのみNOT NULL | |
| evidence_document_version | VARCHAR(100) | APPROVEDのみNOT NULL | |
| evidence_document_hash | CHAR(64) | APPROVEDのみNOT NULL | |
| adopted_at | DATETIME(6) | NOT NULL | **reducer正本時刻列（adopted_at, id）** |
| adopted_by | BIGINT | NOT NULL | |
| operation_id | VARCHAR(36) | NOT NULL | |
| correlation_id | VARCHAR(100) | NOT NULL | |
| idempotency_key | VARCHAR(200) | NOT NULL | |
| created_at | DATETIME(6) | NOT NULL | |

- `UNIQUE(tenant_id, idempotency_key)`・`UNIQUE(tenant_id, id)`
- **UPDATE/DELETEはMySQL triggerで拒否**。
- **G2-VERIFY-09**: APPROVED adoptionをgateへ採用する際、必要なverification参照が欠ければfail-closed。
- 原`t_compliance_external_review_event`（SUBMITTED）へverification IDを後付けADD COLUMNしない。
- 既存review eventのcredential_*列はSUBMITTED入力として残すが、gate採用判定には使わない。

### 3.5 migration inventory（read-only・実測）

- common `db/migration`: V1〜V102の86 versionedファイル（V66_1/V74_1/V74_2/V79_1含む）＋`R__crm_contact_reconciliation.sql`
- **欠番（実測・ls-tree確認済み）**: V19・V23・V41・V47・V59・V72・V82・V86〜V90・V92〜V97・V99・V100
  （V103〜V108はS12〜S17予約のため欠番として正しい）
- `db/migration-prod`: `R__update_admin_password_bcrypt.sql`
- test側: `src/test/resources/sql/v79_1-order-acceptance-legacy.sql`（Testcontainers fixture・Flyway対象外）
- `application.yml`: `locations: classpath:db/migration`・`baseline-on-migrate: true`・`baseline-version: 9`
- **「V102適用済み」の2分（K8）**: repository上ではV102 published/immutable。
  対象environmentの適用状態は `flyway_schema_history` 未採取のため **UNKNOWN**。実装開始前に採取する。
- 受理後同期対象: consolidated V1・V102_1・H2 schema・entity/mapper・metadata manifest・
  fresh/upgrade/legacy/partial/failed-history-repair MySQL smoke・S12 V103予約不変。

### 3.6 review_policy_versionの正本・operation type・idempotency（K3/K6）

- **review_policy_versionの正本**: 独立policy version tableは作らない。
  `m_compliance_mapping_version.mapping_version` をpolicy versionとして使用する。
  mapping freeze（PROVISIONAL_REVIEWED transition）時にpolicy snapshot/hashと共に確定し、
  adoption eventにも同一versionを保存する。
- **V102_1で追加するoperation type**（V102既存 `chk_g2_operation_type` は直接変更せず、
  V102_1でCHECKをforward replacement）:
  - `EXTERNAL_REVIEW_SUBMIT`・`REVIEWER_VERIFICATION_RECORD`・`REVIEWER_VERIFICATION_REVOKE`・
    `EXTERNAL_REVIEW_ADOPT`・`EXTERNAL_REVIEW_REVOKE`
- idempotency契約（全operation共通）:
  - 同一key＋同一canonical request hash → 元のresultを200 replay
  - 同一key＋異なるrequest hash → 409
  - 重複event INSERT → DB UNIQUE `(tenant_id, idempotency_key)` で拒否

### 3.7 verification有効期間（K6固定契約）

- 前提: `checked_at <= asOf`
- **effective expiry = `min(authority valid_until（存在時）, checked_at + frozen max_age_days)`**
- 採用条件: `asOf < effective expiry`
- `max_age_days` 未設定（NULL）または不正値（<1）はfail-closed（採用不可）。
- AUTHORSHIPのみ: exact mapping/review binding（mapping/policy/evidence一致）で評価し、独自期限不要
  （`max_age_days_snapshot` NULL可・`valid_until` NULL可）。

### 3.8 dynamic reviewer type / official source（K5）

- **社労士・弁護士・日弁連等を固定valueとして実装しない**。§3.7の名称は例示であり、
  DB CHECK・Java enum・static Set・seedへ入れないことを明記する。
- reviewer typeまたはtenant-scoped関連masterで、管理者が画面設定できるようにする。
- 最低限の動的設定: reviewer type・official authority/source・source code/display name・
  allowed generic verification method・official URL reference・registration identifier label/rule・
  qualification verification required・active-status verification required・
  verification freshness/max age・enabled/effective period。
- mapping policyへ採用する際、これらをsnapshotし、`review_policy_hash` へ含める。
- 新しい資格type/source追加にJava/DDL変更を要求しないことをdirect regressionへ追加（§6 #26）。

### 3.9 CHECK matrix（K7・DDLへ直接変換できる表）

**verification kind × result**

| kind | VERIFIED | FAILED | INCONCLUSIVE | REVOKED |
|---|---|---|---|---|
| IDENTITY | 可 | 可 | 可 | 可 |
| QUALIFICATION | 可 | 可 | 可 | 可 |
| ACTIVE_STATUS | 可 | 可 | 可 | 可 |
| REVIEW_AUTHORSHIP | 可 | 可 | 可 | 可 |

**result × evidence/binding nullability（verification event）**

| result | registration_identifier_* | evidence_* | binding列（mapping/policy/review） | revoked_verification_event_id |
|---|---|---|---|---|
| VERIFIED | kindにより必須（§3.7のkind別表と一致） | 必須 | kind='REVIEW_AUTHORSHIP'のみ必須 | NULL |
| FAILED | 任意 | 必須 | 任意 | NULL |
| INCONCLUSIVE | 任意 | 必須 | 任意 | NULL |
| REVOKED | 任意 | 任意 | 任意 | NOT NULL（self-FK） |

**adoption action × verification references**

| action | identity_verification_event_id | qualification_verification_event_id | active_status_verification_event_id | authorship_verification_event_id | revoked_adoption_event_id |
|---|---|---|---|---|---|
| APPROVED | NOT NULL | frozen flag=trueのtypeのみNOT NULL | 同左 | NOT NULL | NULL |
| REJECTED | NULL可 | NULL可 | NULL可 | NULL可 | NULL |
| REVOKED | NULL | NULL | NULL | NULL | NOT NULL（self-FK） |

**SUBMITTED/APPROVED/REJECTED/REVOKED transition（adoption）**

- SUBMITTED chainごとに初回adoptionはAPPROVEDまたはREJECTEDの1件のみ（DB triggerで保証）。
- REVOKEDはAPPROVED adoptionのみをtargetにできる（REJECTEDをtarget不可・triggerで拒否）。
- REVOKED後・REJECTED後の再Reviewは新しいSUBMITTED chain（同一chain再利用禁止）。

**credential/encryption all-or-none（verification event）**

- `registration_identifier_encrypted`・`key_version`・`cipher_format`・`masked_snapshot` は
  4列ともNOT NULLまたは4列ともNULL（CHECK）。

**evidence ID/version/version/hash all-or-none**

- `evidence_document_id`・`evidence_document_version_id`・`evidence_document_version`・
  `evidence_document_hash` は4列ともNOT NULLまたは4列ともNULL（CHECK）。

**applicable verification setとfrozen flagの一致（adoption APPROVED）**

- `qualification_verification_event_id` のNOT NULL/必須は採用typeの
  `qualification_verification_required_snapshot=true` と一致しなければならない（CHECKまたはservice検証）。
- `active_status_verification_event_id` も同様。

## 4. 既存P0修正（受理後・実装フェーズ）

1. `hasTypes`による空group skipを削除する。**空group/typeはskipせずinvalid frozen policyとしてfail-closed**。
2. `validateFrozenReviewPolicy()`を単一実装とし、PROVISIONAL_REVIEWED・ACTIVE・future promote・formal generateで共用する。
3. policyは「最低1group・各group最低1type・minimum>=1・snapshot/hash一致」を必須にする。
4. 登録時のcredential必須判定はcurrent reviewer type masterではなくfreeze済み`credential_required_snapshot`を使用する。
5. `ComplianceGateEvidenceResolver`を追加し、document ID＋exact version IDをserver-side解決する。
   tenant・document/version対応・file scope・scan=CLEAN・SHA-256を検証し、eventへID/version/hashを全てsnapshotする。
6. evidence NULL・version不存在・document不一致・non-CLEAN・hash不一致を全て拒否する。
   `findLatestByDocumentId()`をgate判定に使用しない。
7. internal approval evidenceにも同じresolverを適用する。
8. ACTIVE・future promote・formal generateで共通の`ComplianceGateEvaluationService`を使用する。
9. formal generateではcurrent assignment.id/user_idとapproval assignment/actorを完全一致させる。
10. promoteでもcurrent assignment・approval・external review・verification・exact evidenceを全て再評価する。
11. gate snapshotとdelivery business keyへadopted external review IDs・verification event IDs・
    全evidence version ID/hashを含める。
12. verificationの撤销/期限切れ後は新規formal deliveryを拒否するが、過去deliveryのimmutable downloadは維持する。

## 5. API/UI/security（受理後）

- `/compliance-gate`ページを実装する。
- tabs: Mapping / Reviewer Type / Review Policy / Assignment / Internal Approval / External Review /
  本人・資格・作成者確認 / ACTIVE / Event History
- 管理者がtype/policy/assignment/external review/verification/ACTIVEを管理する。
- 管理者・HR・マネージャーはapproval画面へ入れるが、serviceでcurrent assignment.user_id == currentUserIdを必須にする。
- capabilitiesはserver計算し、JS role判定をauthorizationに使わない。
- typed request/response DTOとallow-listを使用し、entityやMapをAPI契約にしない（既存Base APIのMap/entity契約も置換）。
- evidence pickerはdocument/version/title/originalName/SHA-256/scan/createdAtだけを返す。
- CSRF・ActionPermissionResolver・MenuPermissionFilter・DataScope・tenant/workplace SQL境界をdirect testする。
- recorded_by・本人性確認者・資格確認者・Review者を画面上で混同しない。

## 6. regression matrix（受理後・最低限）

| # | ケース | 期待 |
|---|---|---|
| 1 | 空policy・空group・group typeなし | PROVISIONAL/ACTIVE/generate拒否（skipせずfail-closed） |
| 2 | credential文字列だけ | 採用拒否 |
| 3 | DQC VALIDだが本人性未確認 | 拒否 |
| 4 | 本人性確認済みだが資格未確認（frozen flag=true） | 拒否 |
| 4b | frozen flag=falseのtype | QUALIFICATION/ACTIVE_STATUS必須なし（採用可能） |
| 5 | 登録存在でも業務停止/期限切れ | 拒否 |
| 6 | 公式公開list未掲載 | INCONCLUSIVE（自動FAILED/PASSにしない） |
| 7 | official source由来ではない連絡先だけの確認 | 拒否 |
| 8 | REVIEW_AUTHORSHIPがmapping/hash/evidence hash不一致 | 拒否 |
| 9 | evidence NULL/non-CLEAN/不存在/hash不一致/latest差替え | 拒否 |
| 10 | SUBMITTED→verification→APPROVED/adoption正常順序 | 採用のみ |
| 10b | verificationなしSUBMITTED | gate不採用（fail-closed） |
| 10c | 循環参照/後付けUPDATE | 拒否（trigger・event順序契約） |
| 11 | 自称氏名/組織/番号を複数入力 | distinct reviewerを増やせない（reviewer_subject_id判定） |
| 11b | 同一人物・複数資格 | distinct水増し不可（subject_id=1・qualification別管理） |
| 12 | verification REVOKED/expired後 | ACTIVE/generate拒否 |
| 13 | 旧assignment approval | 交代後に利用不可 |
| 14 | future promote | 完全gateを通る |
| 15 | REVOKE後の再Review・異なるevidenceでの再確認・retry replay | 200（新しいSUBMITTED chain） |
| 15b | 同一idempotency key同hash | 200 replay |
| 15c | 同一key異hash | 409 |
| 16 | tenant A/B・workplace/DataScope・role 5種・CSRF | 境界維持 |
| 17 | raw credential・本人確認資料path・完全fingerprint | API/logへ出ない |
| 17b | HMAC key rotation / unknown key | subject_idでdistinct維持・unknown key version fail-closed |
| 18 | 過去delivery | 資格失効後も元版download可能 |
| 18b | legacy NULL verification review | 新規gate不採用（backfill捏造なし） |
| 19 | V1/V102_1/H2/entity/mapper・fresh V1・V102→V102_1 forward migration・legacy/partial/failed-history-repair・skip 0 MySQL | 同期・成功 |
| 20 | V102 CHECKではSUBMITTED拒否・V102_1後はSUBMITTED許可 | forward replacement動作 |
| 21 | legacy APPROVED/REJECTED/REVOKED row | 新gate不採用 |
| 22 | submitted_review_event_idのcross-tenant FK | 拒否 |
| 23 | polymorphic target不使用・verification/adoption revokeのtarget table分離 | 構造で保証 |
| 24 | 各operation claim/transaction独立・adopted_at,id reducer | 独立実行 |
| 25 | required verification setのflag true/false・unconfigured flag/freshness | fail-closed |
| 26 | dynamic unknown reviewer type/source追加 | Java/DDL変更なしで画面設定可能 |
| 27 | rejected/revoked後の再Review | 新しいSUBMITTED chain |
| 28 | action/result/nullability CHECK matrix | 全combination検証 |

- test fixtureの架空専門家は自動test内だけに限定し、Phase B正式証跡やseedへ流用しない。

## 7. 人間証跡と停止条件

AIは実在する資格保有者・本人確認・資格確認・外部Reviewを生成・代替しない。実装後は以下が揃うまで
ACTIVE・formal delivery・T066/S10 PASSを禁止する:

1. ページでfreezeした実運用policy
2. 実在assignment actor本人のapproval
3. 実在資格保有者によるReview
4. 本人性・資格有効性・Review作成者の人間確認
5. exact CLEAN evidence
6. Phase A/B desktop/390px browser evidence
7. 最終HeadのL4/CI skip 0
8. R10最終Review

## 8. frozen policy flags（G2-VERIFY-14詳細・K6）

- **master flag（K6・`DEFAULT 0`禁止）**: `qualification_verification_required`・
  `active_status_verification_required`（TINYINT NULL=UNCONFIGURED）。
  - 新規作成APIでは管理者の明示選択（true/false）を必須。
  - legacy rowではNULL=UNCONFIGURED。
  - **NULLのtypeはpolicy freeze/ACTIVE/generate不可**。
  - silent false/true backfillは禁止。
- **mapping requirement type freeze snapshot**: `qualification_verification_required_snapshot`・
  `active_status_verification_required_snapshot`（TINYINT NOT NULL・freeze時に確定）。
- **snapshot時点の一意化**:
  - DRAFT中にtype/source/flag/freshnessを明示設定
  - master変更は既存DRAFTへ自動反映しない
  - refreshが必要ならDRAFT中の明示操作のみ
  - PROVISIONAL_REVIEWED transition時にsnapshot/hashをfreeze
  - freeze後は変更不可
- **review_policy_hash canonical payloadへ包含**（§3.6のpolicy versionと共に）。
- **同一group内でtypeごとにflagが異なる場合**: groupはOR評価のため、「gateが採用するreviewのtype」の
  frozen snapshot=trueなら該当verification必須。
- IDENTITY・REVIEW_AUTHORSHIPは常時必須。QUALIFICATION・ACTIVE_STATUSは採用typeのfrozen snapshotが
  trueの場合だけ必須（§0・§3・§6の「4検証」表記はすべて条件付きへ統一済み）。

## 9. subject masterとfingerprint（G2-VERIFY-10/13・G2-SUBJECT-01・K4）

- **`t_compliance_external_reviewer_subject`（新規・tenant別）**: immutable person-stable正本。
  - `reviewer_subject_id`（BIGINT PK）をperson-stable distinct keyとする。
  - qualification/登録番号はsubjectとは別の資格identityとして管理（qualification association）。
  - 同一人物が複数資格typeを持ってもsubject_idは1つ。distinct reviewer数はsubject_idでcount。
  - self-declared name/org/credential hashはgate判定に使わない。
- **event snapshot列（最低限）**: `reviewer_subject_id`・`person_fingerprint_snapshot`・
  `qualification_fingerprint_snapshot`・`fingerprint_key_version`。
- **HMAC契約**:
  - HMAC-SHA-256・tenant別専用key namespace（`compliance.verification.fingerprint.{tenantId}`）
  - canonical UTF-8 payloadとdomain separatorを明示
    （person: `domain="person"|tenant|subject_id|正規化氏名|正規化組織`・
     qualification: `domain="qualification"|tenant|subject_id|type_code|正規化登録番号`）
  - person fingerprintとqualification fingerprintで別domain
  - normalization: NFKC・全角/半角統一・ハイフン/空白除去・大文字化（英字）
  - registration number optionalでもperson identityは生成可能（subject_idが正本）
  - key rotation後のdistinct比較はstable subject_idで行う（fingerprint再計算不要）
  - fingerprint再検証時にrequired keyがなければfail-closed
  - full fingerprint/raw subject dataをAPI/logへ出さない
  - **My Numberは保存・fingerprint入力とも使用しない**
- 新規subject master/qualification associationは**V102_1候補scopeへ含める**（§2表に記載済み）。
