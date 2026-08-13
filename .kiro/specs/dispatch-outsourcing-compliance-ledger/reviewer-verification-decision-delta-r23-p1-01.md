# 本人性確認・資格有効性確認・Review作成者確認 decision delta R23-P1-01（corrected v2） / S10 T066

> 状態 `SUBMITTED_FOR_R10_REVIEW / DOCS_ONLY`（2026-08-13・再訂正版）
>
> 発注者の「S10/T066 本人性確認・資格有効性確認・Review作成者確認の是正」指示（docs-only着手）に基づく
> decision packetの再訂正版である。R10のR23-P1-01（corrected）独立Review結果
> `CHANGES_REQUIRED / SPEC_CONCRETIZATION_REQUIRED`（issue A〜J）を反映する。
>
> ## Provenance（再訂正）
>
> - **authoritative Base = `8ffbcddbc475e61e42eb52f392f12e4e3f2b014d`**
> - **R10 reviewed Head（前回） = `f42faea00f55417a9ec6fd5656a18e60d240b46f`**
> - **observed main = `31d2930593fe430a62fe296bcc1b43e122dd11f4`**
> - R10はartifact boundaryをREVIEWABLEと確認済み: Base→f42faea0は1 commit・Markdown 2件のみ・
>   +243/-0・non-Markdown diff 0・V102 blob不変・V102_1未作成。このboundaryを維持する。
> - 旧提出candidate `de3cc8b7` はdocs-only不成立（先行実装混入）として履歴に残す。
> - **実環境の `flyway_schema_history` は未採取**。repository上でV102はpublished/immutableであるが、
>   対象environmentの適用状態（checksum含む）は未確認である。
> - 「code差分ゼロ」の記述は撤回する（Markdown-onlyではあるが先行実装が存在するため）。
>
> 本packetはdocs-onlyであり、R10が `ACCEPTED_FOR_IMPLEMENTATION` を記録するまで
> 新規verification DDL・entity・service・migration（V102_1含む）・API・UI・security・
> ACTIVE/formal delivery関連の追加変更を一切開始しない。
> 既存 `V102__dispatch_compliance_g2_gate_schema.sql` は適用済みmigrationとして変更禁止である。
> R10受理は§3〜§5の実装開始許可だけであり、先行実装・T066 PASS・S10 PASS・S12開始・
> 本番ACTIVE化を自動承認するものではない。先行実装 `8ffbcddb..31d29305` の適合性は、
> 受理後にaccepted contractと別途固定Headで独立Reviewする。

## 0. Decision ID一覧

| decision ID | 確定する論点 |
|---|---|
| `G2-VERIFY-01` | 外部Reviewの4独立検証対象: IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP |
| `G2-VERIFY-02` | reviewer typeはtenant管理の動的masterを維持（Java enum・static Set・DB CHECK・固定select・seedに追加しない） |
| `G2-VERIFY-03` | gate採用条件（IDENTITY/AUTHORSHIP常時必須・QUALIFICATION/ACTIVE_STATUSはfrozen flag条件付き・asOf有効・未REVOKED・exact CLEAN evidence） |
| `G2-VERIFY-04` | デジタル資格者証VALIDだけでは本人性を成立させない・単独確認の禁止 |
| `G2-VERIFY-05` | 初期実装は公的sourceを用いた手動確認（弁護士/社労士/DQC・公式API・scraping/server fetch禁止） |
| `G2-VERIFY-06` | UI表記（「本人性確認」「資格有効性確認」「Review作成者確認」・credentialは「入力済・未検証」） |
| `G2-VERIFY-07` | My Number非保存・本人確認書類全面コピー非保存・確認metadataのみ保存 |
| `G2-VERIFY-08` | 受理後schema: `t_compliance_external_reviewer_verification_event`（append-only・UPDATE/DELETE拒否trigger） |
| `G2-VERIFY-09` | gate採用はAPPROVED/adoption eventのみ・verification参照欠落fail-closed |
| `G2-VERIFY-10` | subject fingerprintはtenant分離HMAC・person-stable/qualification-specific区別・key version/rotation |
| `G2-VERIFY-11` | 4 verification eventの同一tenant・subject・reviewer type制約 |
| `G2-VERIFY-12` | REVIEW_AUTHORSHIPのmapping/policy/external review/exact evidence一致（binding列） |
| `G2-VERIFY-13` | distinct reviewer判定はverified tenant-HMAC fingerprint（self-declared hash不使用） |
| `G2-VERIFY-14` | IDENTITY・AUTHORSHIP常時必須・QUALIFICATION/ACTIVE_STATUSはfrozen policy flag（保存先・freeze点・hash包含） |
| `G2-VERIFY-15` | revoke/target/supersedesの同一tenant・subject・kind制約・tenant複合FK・nullability・CHECK・operation ledger・idempotency replay |
| `G2-EVENT-ORDER-01` | SUBMITTED→verification→APPROVED/adoption→REVOKEDのappend-only event順序（循環なし） |
| `G2-P0-FIX-01..12` | 既存P0修正（空group skip削除・validateFrozenReviewPolicy統一・evidence resolver・gate評価service共通化等） |
| `G2-VERIFY-16` | `/compliance-gate` UI・tabs・capability server計算・typed DTO |
| `G2-VERIFY-17` | regression matrix（§6） |

## 1. Base / Head

### 1.1 Base（authoritative・実測）

- **Base = `8ffbcddbc475e61e42eb52f392f12e4e3f2b014d`**
- 適用済み正本: `g2-gate-decision-delta-r19-p1-01.md`（R10受理・実装中）・`migration-order-decision-r4-p1-01.md`
- migration: repository上でV1〜V102がpublished/immutable（欠番は§3.4に全列挙）。S12〜S17はV103〜V108予約。
- **外部Review実装の現状（実測・Base内に存在）**:
  - API/write pathは**存在する**: `ComplianceGateApiController.java:119-140`（`POST /external-reviews`・
    `GET /mappings/{id}/external-reviews`）、`ComplianceGateAdminServiceImpl.java:284-351`
    （`recordExternalReview`・`listExternalReviews`）。
  - ただし**不適合**: tenant固定・Map/entity API・exact evidence・verification・gate適用が未実装。
    （「8ffbcddb以降にAPIが追加された」という記述はしない。APIはBase内にある。）
  - credential文字列の入力・暗号化（`credential_snapshot_encrypted`/key version/cipher/masked）・復号可能性のみ。
    資格の実在性・提示者本人性・Review作成者本人性は**未検証**。
  - `reviewer_identity_hash`は自称氏名/組織からの単純SHA-256の懸念（§G2-VERIFY-13でgate判定から排除）。
  - evidenceはsnapshot列のみで、server-side解決・CLEAN検証なし。verification参照列なし。
  - `/compliance-gate`ページ・`ComplianceGateEvidenceResolver`・`ComplianceGateEvaluationService`・
    formal generateのgate適用は未実装。
- policy検証の現状: `assertPolicyNotEmpty`（group 1件のみ・type非空未検証）+ `policyHashMismatch` 照合。

### 1.2 Head（本packet受理後）

- **corrected v2 docs-only Head = 本packet + review-ledger.mdのR10判定記録・再提出記録のみのMarkdown commit**
  （f42faea0の子commitとしてisolatedに作成。Java production code・Java test・migration/DDL・V102_1・
  entity/mapper/service/API/UI/security・HTML/JS/CSS/messages・tasks checkbox・S10/S12 status・
  ACTIVE/formal delivery・seed/backfillは含めない）
- 実装はR10 `ACCEPTED_FOR_IMPLEMENTATION` 後に、§3（V102_1 schema）→§4（P0修正）→§5（API/UI/security）の順

## 2. 変更予定範囲（scope・受理後）

| 種別 | 対象 | 内容 |
|---|---|---|
| 新規migration | `V102_1__reviewer_verification_events.sql`（受理後） | §3の新規event table・adoption table・CHECK forward replacement |
| 変更禁止 | `V102`・`V84`・`V85`・`V101` | 適用済み・予約のため変更しない |
| 新規entity/mapper | `ComplianceExternalReviewerVerificationEvent`・`ComplianceExternalReviewAdoptionEvent` と各Mapper | INSERT/SELECTのみ |
| 新規service | `ComplianceGateEvidenceResolver`・`ComplianceGateEvaluationService` | §4 |
| 変更service | `ComplianceMappingServiceImpl`・`ComplianceApprovalServiceImpl`・`ComplianceGateAdminServiceImpl`・外部Review記録service | P0修正・gate統合 |
| 新規controller/page | `/compliance-gate`ページ・tabs・verification API | §5 |
| 新規/変更test | §6 regression・H2 schema・metadata manifest・MySQL smoke | 受理後同期 |

## 3. 受理後のschema（V102_1候補）

### 3.1 version順序契約

- Flyway version: `V102` < `V102_1`（=102.1）< `V103`。`V66_1`・`V74_1`・`V74_2`・`V79_1`の
  既存サフィックス実績と同一規則。
- version重複0・outOfOrder不要。
- **V102 blob/checksum golden・V102_1存在・Flyway実version順序の検証testは実装受理後に作成する**
  （docs-only HeadへJava testは含めない）。
- S12〜S17のV103〜V108予約を維持できる候補としてV102_1を採用する。

### 3.2 event順序契約（G2-EVENT-ORDER-01・R10 issue B解決）

append-onlyで成立するevent順序を**一つだけ正式採用**する（原review rowへの後付けUPDATEは禁止）:

| step | event | table | target/supersedes | gate eligibility |
|---|---|---|---|---|
| 1 | **SUBMITTED** | `t_compliance_external_review_event`（V102既存・action='SUBMITTED'） | target=NULL・chain_id採番 | 不採用（単独ではgate不可） |
| 2 | **IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP** verification | `t_compliance_external_reviewer_verification_event`（新規） | target=SUBMITTED event ID・supersedes=同kindの旧verification event ID（あれば） | 不採用（単独ではgate不可） |
| 3 | **APPROVED / REJECTED** adoption | `t_compliance_external_review_adoption_event`（新規） | target=SUBMITTED event ID・4 verification event IDをsnapshot | **gateはAPPROVED adoption eventのみ採用** |
| 4 | **REVOKED** | `t_compliance_external_review_adoption_event`（新規・action='REVOKED'） | target=APPROVED adoption event ID・supersedes=同adoption chainの旧event | 不採用（最新のadoption actionがREVOKEDならgate不可） |

- 同一review_chain_idで1件のSUBMITTED・複数verification・1件以上のadoption（APPROVED/REJECTED/REVOKED）が並ぶ。
- adoptionのreducer: 同一chain内で最新action（occurred_at, id順）がAPPROVEDのときだけgate採用可能。
- 4 verificationはSUBMITTEDをtargetとするため、AUTHORSHIPがreview IDを参照しても循環しない
  （verification→SUBMITTED・adoption→SUBMITTED+verificationの一方参照のみ・既存review row UPDATEなし）。
- REVOKEDはAPPROVED/adoption eventをtargetにする。
- transaction順序: 1→2→3→4の順に各INSERTを同一operation ledger claim下で実行する。
  （docs-only契約として順序を固定。operation typeは§3.6参照。）

### 3.3 `t_compliance_external_reviewer_verification_event`（新規・append-only）

| 列 | 型 | nullability | 備考 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | NOT NULL | |
| tenant_id | VARCHAR(100) | NOT NULL | |
| reviewer_type_id | BIGINT | NOT NULL | tenant複合FK `(tenant_id, reviewer_type_id)`→master |
| reviewer_type_code_snapshot | VARCHAR(100) | NOT NULL | freeze時snapshot |
| reviewer_type_name_snapshot | VARCHAR(200) | NOT NULL | freeze時snapshot |
| reviewer_subject_fingerprint | CHAR(64) | NOT NULL | §G2-VERIFY-10（tenant-HMAC） |
| fingerprint_key_version | VARCHAR(64) | NOT NULL | HMAC key version（rotation比較用） |
| verification_kind | VARCHAR(20) | NOT NULL | IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP |
| result | VARCHAR(20) | NOT NULL | VERIFIED / FAILED / INCONCLUSIVE / REVOKED |
| method_code | VARCHAR(50) | NOT NULL | §3.7 kind別決定表の値域 |
| authority_source_name | VARCHAR(200) | NOT NULL | 日弁連・社労士名簿・連合会/都道府県会・DQC等 |
| official_url_reference_snapshot | VARCHAR(1000) | NULL可 | 公式URL（server-side fetch禁止） |
| registration_identifier_encrypted | TEXT | kind/result別（§3.7） | 専用鍵・AES-GCM |
| registration_identifier_key_version | VARCHAR(64) | kind/result別 | |
| registration_identifier_cipher_format | VARCHAR(20) | kind/result別 | |
| registration_identifier_masked_snapshot | VARCHAR(255) | kind/result別 | |
| checked_at | DATETIME(6) | NOT NULL | |
| source_data_as_of | DATETIME(6) | NULL可 | 公的sourceのデータ時点 |
| valid_until | DATETIME(6) | kind別（§3.7） | 失効はvalid_untilから導出 |
| checked_by | BIGINT | NOT NULL | 確認者（本人性確認者・資格確認者をReview者と混同しない） |
| evidence_document_id | BIGINT | kind別（§3.7） | exact version（§4-5） |
| evidence_document_version_id | BIGINT | kind別 | tenant複合FK `(tenant_id, evidence_document_version_id)`→t_document_version |
| evidence_document_version | VARCHAR(100) | kind別 | |
| evidence_document_hash | CHAR(64) | kind別 | SHA-256 |
| review_policy_version | VARCHAR(50) | AUTHORSHIPのみ必須 | **R10 issue D: 追加列** |
| review_policy_hash | CHAR(64) | AUTHORSHIPのみ必須 | **R10 issue D: 追加列** |
| mapping_id | BIGINT | AUTHORSHIPのみ必須 | tenant複合FK `(tenant_id, mapping_id)`→m_compliance_mapping_version |
| mapping_version | VARCHAR(50) | AUTHORSHIPのみ必須 | |
| mapping_hash | CHAR(64) | AUTHORSHIPのみ必須 | |
| external_review_event_id | BIGINT | AUTHORSHIPのみ必須 | **R10 issue D: 追加列**・tenant複合FK `(tenant_id, external_review_event_id)`→SUBMITTED event |
| external_review_chain_id | VARCHAR(36) | AUTHORSHIPのみ必須 | **R10 issue D: 追加列** |
| target_event_id | BIGINT | NOT NULL | 同一tenant・subject・kindの旧verification/SUBMITTED（§G2-VERIFY-15） |
| supersedes_event_id | BIGINT | NULL可 | 同kind旧verification event ID |
| operation_id | VARCHAR(36) | NOT NULL | |
| correlation_id | VARCHAR(100) | NOT NULL | |
| idempotency_key | VARCHAR(200) | NOT NULL | |
| created_at | DATETIME(6) | NOT NULL | |

- `UNIQUE(tenant_id, idempotency_key)`・`UNIQUE(tenant_id, id)`
- **UPDATE/DELETEはMySQL triggerで拒否**。
- `valid_until`未満なら自動expired扱い（FAILED/INCONCLUSIVEにはしない）。
- **G2-VERIFY-11**: 4 verification eventは同一tenant・同一subject（fingerprint）・同一reviewer typeに属する。
  別subject/typeのeventを組み合わせてgateを成立させない。
- **G2-VERIFY-12**: AUTHORSHIP eventはmapping ID/version/hash・review_policy_version/hash・
  external_review_event_id/chain_id・exact evidence version/hashと一致する。
- **G2-VERIFY-15**: revoke/supersedesのtargetは同一tenant・同一subject・同一kind。
  全参照はtenant複合FK `(tenant_id, id)`。CHECK: verification_kind値域・result値域・method_code値域・
  kind別nullability（AUTHORSHIPはmapping/policy/review binding必須、他kindはNULL可）。

### 3.4 `t_compliance_external_review_adoption_event`（新規・append-only）

| 列 | 型 | nullability | 備考 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | NOT NULL | |
| tenant_id | VARCHAR(100) | NOT NULL | |
| action | VARCHAR(20) | NOT NULL | APPROVED / REJECTED / REVOKED |
| review_chain_id | VARCHAR(36) | NOT NULL | SUBMITTED chainと同一 |
| target_event_id | BIGINT | NOT NULL | SUBMITTED event ID（REVOKEDはAPPROVED adoption event ID） |
| supersedes_event_id | BIGINT | NULL可 | 同chainの旧adoption event ID |
| identity_verification_event_id | BIGINT | APPROVEDのみ必須 | tenant複合FK |
| qualification_verification_event_id | BIGINT | APPROVED時・frozen flag=trueのtypeのみ必須（§G2-VERIFY-14） | |
| active_status_verification_event_id | BIGINT | APPROVED時・frozen flag=trueのtypeのみ必須 | |
| authorship_verification_event_id | BIGINT | APPROVEDのみ必須 | |
| mapping_id / mapping_version / mapping_hash / review_policy_hash | BIGINT・VARCHAR(50)・CHAR(64)×2 | APPROVEDのみ必須 | gate snapshot用 |
| evidence_document_id / version_id / version / hash | BIGINT×2・VARCHAR(100)・CHAR(64) | APPROVEDのみ必須 | |
| adopted_at | DATETIME(6) | NOT NULL | |
| adopted_by | BIGINT | NOT NULL | |
| operation_id / correlation_id / idempotency_key | VARCHAR | NOT NULL | |
| created_at | DATETIME(6) | NOT NULL | |

- `UNIQUE(tenant_id, idempotency_key)`・`UNIQUE(tenant_id, id)`
- **UPDATE/DELETEはMySQL triggerで拒否**。
- **G2-VERIFY-09**: APPROVED adoptionをgateへ採用する際、必要なverification参照が欠ければfail-closed。
- 原`t_compliance_external_review_event`（SUBMITTED）へverification IDを後付けADD COLUMNしない
  （R10 issue B: 後付けUPDATE禁止。adoption eventがbindingを保持する）。
- 既存review eventのcredential_*列はSUBMITTED入力として残すが、gate採用判定には使わない（§G2-VERIFY-04）。

### 3.5 migration inventory（read-only・実測）

- common `db/migration`: V1〜V102の86 versionedファイル（V66_1/V74_1/V74_2/V79_1含む）＋`R__crm_contact_reconciliation.sql`
- **欠番（実測・ls-tree確認済み）**: V19・V23・V41・V47・V59・V72・V82・V86〜V90・V92〜V97・V99・V100
  （V103〜V108はS12〜S17予約のため欠番として正しい）
- `db/migration-prod`: `R__update_admin_password_bcrypt.sql`
- test側: `src/test/resources/sql/v79_1-order-acceptance-legacy.sql`（Testcontainers fixture・Flyway対象外）
- `application.yml`: `locations: classpath:db/migration`・`baseline-on-migrate: true`・`baseline-version: 9`
- **「V102適用済み」の2分**: repository上ではV102 published/immutable。
  対象environmentの適用状態は `flyway_schema_history` 未採取のため未確認。実装開始前に採取する。
- 受理後同期対象: consolidated V1・V102_1・H2 schema・entity/mapper・metadata manifest・
  fresh/upgrade/legacy/partial/failed-history-repair MySQL smoke・S12 V103予約不変。

### 3.6 operation type・idempotency（R10 issue G）

- 存在しない「§8」参照は廃止し、本§3.6に契約を置く。
- **V102_1で追加するoperation type**（V102既存 `chk_g2_operation_type` は直接変更せず、
  V102_1でCHECKをforward replacementする）:
  - `EXTERNAL_REVIEW_SUBMIT`（step 1）
  - `REVIEWER_VERIFICATION_RECORD`（step 2）
  - `REVIEWER_VERIFICATION_REVOKE`（verification REVOKED）
  - `EXTERNAL_REVIEW_ADOPT`（step 3・APPROVED/REJECTED）
  - `EXTERNAL_REVIEW_REVOKE`（step 4）
- idempotency契約（全operation共通）:
  - 同一key＋同一canonical request hash → 元のresultを**200 replay**
  - 同一key＋異なるrequest hash → **409**
  - 重複event INSERT → DB UNIQUE（`(tenant_id, idempotency_key)`）で拒否

### 3.7 verification kind別決定表（R10 issue F）

| 項目 | IDENTITY | QUALIFICATION | ACTIVE_STATUS | REVIEW_AUTHORSHIP |
|---|---|---|---|---|
| allowed method_code | `MANUAL_OFFICIAL_SOURCE`・`MANUAL_DQC` | `MANUAL_REGISTRY`・`MANUAL_DQC` | `MANUAL_REGISTRY`・`MANUAL_AUTHORITY_CONTACT` | `MANUAL_INTERNAL_AUTHORSHIP_CONFIRM` |
| required actor | 管理者（本人性確認者） | 管理者（資格確認者） | 管理者（資格確認者） | 管理者（Review作成者確認者） |
| required official source | 日弁連公式検索・社労士名簿・連合会/都道府県会・DQC（dqcvs.nqs.go.jp） | 同左 | 同左（業務停止等の現在状態） | 内部（mapping/event/evidence解決） |
| required metadata | authority・URL snapshot・照合項目・日時 | 同左＋登録番号（正規化） | 同左 | mapping/policy/review/evidence version ID・hash |
| evidence必須 | 必須（exact CLEAN） | 必須 | 必須 | 必須（review evidenceと一致） |
| exact version/hash/CLEAN | 必須 | 必須 | 必須 | 必須 |
| valid_until必須 | 推奨（確認有効期限） | 必須 | 必須 | 不要（AUTHORSHIPはevent時点で成立） |
| freshness上限 | 管理者設定（例90日・config） | 管理者設定 | 管理者設定（業務停止確認は短く） | なし（mapping/policy freeze時点と一致が条件） |
| VERIFIED条件 | 公的sourceで本人一致＋連絡先確認（申告電話でなく公式検索由来） | 登録・有効・資格保有一致 | 業務停止等なし | mapping/hash/policy/evidence一致＋記録者本人確認 |
| FAILED条件 | 明示的不一致（本人でない） | 登録不存在・無効・不一致 | 業務停止等あり | 不一致・不存在 |
| INCONCLUSIVE条件 | 判定不能（確認不能・DQC無応答） | **公式list未掲載はINCONCLUSIVE**（自動FAILED/PASSにしない） | 確認不能 | 判定不能 |
| REVOKE対象 | 可能 | 可能 | 可能 | 可能 |
| gate採用条件 | VERIFIED（常時必須） | VERIFIED（**採用typeのfrozen snapshot=trueの場合のみ必須**） | 同左 | VERIFIED（常時必須） |

- **DQC VALID単独ではIDENTITYを成立させない**（本人性は別途）。資格証・登録番号・名簿掲載・証票画像の単独確認でも本人性を成立させない。
- 公式公開list未掲載は自動PASS/FAILEDにせずINCONCLUSIVE。

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
| 11 | 自称氏名/組織/番号を複数入力 | distinct reviewerを増やせない（verified tenant-HMAC fingerprint） |
| 11b | 同一人物・複数資格 | distinct水増し不可（person-stable fingerprint＋type別） |
| 12 | verification REVOKED/expired後 | ACTIVE/generate拒否 |
| 13 | 旧assignment approval | 交代後に利用不可 |
| 14 | future promote | 完全gateを通る |
| 15 | REVOKE後の再Review・異なるevidenceでの再確認・retry replay | 200 |
| 15b | 同一idempotency key同hash | 200 replay |
| 15c | 同一key異hash | 409 |
| 16 | tenant A/B・workplace/DataScope・role 5種・CSRF | 境界維持 |
| 17 | raw credential・本人確認資料path・完全fingerprint | API/logへ出ない |
| 17b | HMAC key rotation / unknown key | 旧key比較・fail-closed |
| 18 | 過去delivery | 資格失効後も元版download可能 |
| 18b | legacy NULL verification review | 新規gate不採用（backfill捏造なし） |
| 19 | V1/V102_1/H2/entity/mapper・fresh V1・V102→V102_1 forward migration・legacy/partial/failed-history-repair・skip 0 MySQL | 同期・成功 |

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

## 8. 付記: frozen policy flags（R10 issue C・G2-VERIFY-14詳細）

- **reviewer type master上のdefault設定**: `qualification_verification_required`・
  `active_status_verification_required`（TINYINT NOT NULL DEFAULT 0・master列として追加）。
- **mapping requirement type上のfreeze snapshot**: `qualification_verification_required_snapshot`・
  `active_status_verification_required_snapshot`（TINYINT NOT NULL・`addRequirementType`時にmasterからsnapshot）。
- **DRAFT中だけ設定可能**・freeze（PROVISIONAL_REVIEWED）後は変更不可（既存source freeze契約と同一）。
- **review_policy_hash canonical payloadへ包含**（§6.3のgroup/typeブロックにsnapshot flagを含める）。
- **current master変更は既存mappingへ影響しない**（snapshot不変・policy hash不変）。
- **同一group内でtypeごとにflagが異なる場合**: groupはOR評価のため、「gateが採用するreviewのtype」の
  frozen snapshot=trueなら該当verification必須。group内の他のtypeでgateが成立しない限り、flag=true typeを
  採用する場合は必須。
- IDENTITY・REVIEW_AUTHORSHIPは常時必須。QUALIFICATION・ACTIVE_STATUSは採用typeのfrozen snapshotが
  trueの場合だけ必須（§0・§3・§6の「4検証」表記はすべて条件付きへ統一済み）。
