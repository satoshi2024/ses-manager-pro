# 本人性確認・資格有効性確認・Review作成者確認 decision delta  ER23-P1-01 / S10 T066

> 状態 `SUBMITTED_FOR_R10_REVIEW / DOCS_ONLY`（2026-08-13）
>
> 発注者の「S10/T066 本人性確認・資格有効性確認・Review作成者確認の是正」指示（docs-only着手）に基づく
> decision packetである。**本packetはdocs-onlyであり、R10が `ACCEPTED_FOR_IMPLEMENTATION` を記録するまで
> 新規verification DDL・entity・service・migration（V102_1含む）を一切作成しない。**
> 既存 `V102__dispatch_compliance_g2_gate_schema.sql` は適用済みmigrationとして変更禁止である。
> R10受理後、本delta §3/§4/§5の順に実装し、§6 regression・§7 人間証跡が揃うまで
> ACTIVE・formal delivery・T066/S10 PASSを禁止する。

## 0. Decision ID一覧

| decision ID | 確定する論点 |
|---|---|
| `G2-VERIFY-01` | 外部Reviewの4独立検証対象: IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP |
| `G2-VERIFY-02` | reviewer typeはtenant管理の動的masterを維持（Java enum・static Set・DB CHECK・固定select・seedに追加しない） |
| `G2-VERIFY-03` | gate採用条件（4検証・asOf有効・未REVOKED・exact CLEAN evidence）と不採用条件 |
| `G2-VERIFY-04` | デジタル資格者証VALIDだけでは本人性を成立させない・単独確認の禁止 |
| `G2-VERIFY-05` | 初期実装は公的sourceを用いた手動確認（弁護士/社労士/DQC・公式API・scraping/server fetch禁止） |
| `G2-VERIFY-06` | UI表記（「本人性確認」「資格有効性確認」「Review作成者確認」・credentialは「入力済・未検証」） |
| `G2-VERIFY-07` | My Number非保存・本人確認書類全面コピー非保存・確認metadataのみ保存 |
| `G2-MIG-1021` | V102不変・S12のV103予約維持・V102_1候補のversion順序契約 |
| `G2-VERIFY-08` | 受理後schema: `t_compliance_external_reviewer_verification_event`（append-only・UPDATE/DELETE拒否trigger） |
| `G2-VERIFY-09` | external review eventへ採用verification event ID snapshot・欠落fail-closed |
| `G2-VERIFY-10` | reviewer_subject_fingerprintはtenant分離HMAC・確認済みauthority ID＋正規化登録番号 |
| `G2-P0-FIX-01..12` | 既存P0修正（空group skip削除・validateFrozenReviewPolicy統一・evidence resolver・gate評価service共通化等） |
| `G2-VERIFY-11` | `/compliance-gate` UI・tabs・capability server計算・typed DTO |
| `G2-VERIFY-12` | regression matrix（§6） |

## 1. Base / Head

### 1.1 Base（修正前の現行Head）

- commit: `ef306d04`（`fix(dispatch): P6 assignment overlap rejection and workplace anchor lock (review round 4)`）
- 適用済み正本: `g2-gate-decision-delta-r19-p1-01.md`（R10受理・実装中）・`migration-order-decision-r4-p1-01.md`
- migration: common V1〜V102（V102適用済み・S12〜S17はV103〜V108予約、V84/V85/V101変更禁止）
- 外部Review実装の現状（`t_compliance_external_review_event`・V102）:
  - credential文字列の入力・暗号化（`credential_snapshot_encrypted`/key version/cipher/masked）・復号可能性のみ
  - 資格の実在性・提示者本人性・Review作成者本人性は**未検証**
  - `reviewer_identity_hash`は自称氏名/組織からの単純SHA-256の懸念（§G2-VERIFY-10で廃止）
  - evidenceは`evidence_document_id/version_id/version/hash`のsnapshot列のみで、server-side解決・CLEAN検証なし
  - `t_compliance_external_review_event`へのverification参照列なし
  - ExternalReview記録API/UI・`/compliance-gate`ページ・`ComplianceGateEvidenceResolver`・
    `ComplianceGateEvaluationService`・formal generateのgate適用は未実装
- policy検証の現状: `assertPolicyNotEmpty`（group 1件のみ・type非空未検証）+ `policyHashMismatch` 照合。
  `hasTypes`による空group skip・`validateFrozenReviewPolicy`の複数実装・`findLatestByDocumentId`を
  gate判定に使う経路は**存在しない**（P0指摘は実装予定経路への事前契約であり、後述§4で実装時に単一実装を固定する）。

### 1.2 Head（本packet受理後）

- `Base + 本decision packet`（docs-only。この時点ではコード差分ゼロ）
- 実装はR10 `ACCEPTED_FOR_IMPLEMENTATION` 後に、§3（V102_1 schema）→§4（P0修正）→§5（API/UI/security）の順

## 2. 変更予定範囲（scope）

| 種別 | 対象 | 内容 |
|---|---|---|
| 新規migration | `V102_1__reviewer_verification_events.sql`（受理後） | `t_compliance_external_reviewer_verification_event` 他§3 |
| 変更禁止 | `V102`・`V84`・`V85`・`V101` | 適用済み・予約のため変更しない |
| 新規entity | `ComplianceExternalReviewerVerificationEvent` | §3の列に対応 |
| 新規mapper | `ComplianceExternalReviewerVerificationEventMapper` | INSERT/SELECTのみ |
| 新規service | `ComplianceGateEvidenceResolver`・`ComplianceGateEvaluationService` | §4 |
| 変更service | `ComplianceMappingServiceImpl`・`ComplianceApprovalServiceImpl`・`ComplianceGateAdminServiceImpl`・外部Review記録service（新規） | P0修正・gate統合 |
| 新規controller/page | `/compliance-gate`ページ・tabs・verification API | §5 |
| 新規/変更test | §6 regression・H2 schema・metadata manifest・MySQL smoke | V1/V102_1/H2/entity/mapper同期 |

## 3. 受理後のschema（V102_1候補）

### 3.1 version順序契約

- Flyway version: `V102` < `V102_1`（=102.1）< `V103`。`V66_1`・`V74_1`・`V74_2`・`V79_1`の
  既存サフィックス実績と同一規則（`_`を小数区切りとしてFlywayが解釈）。
- version重複0・outOfOrder不要をdirect test（§3.4）で保証する。
- S12〜S17のV103〜V108予約を維持できる候補としてV102_1を採用する。

### 3.2 `t_compliance_external_reviewer_verification_event`（新規・append-only）

| 列 | 型 | 備考 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| tenant_id | VARCHAR(100) NOT NULL | |
| reviewer_type_id | BIGINT NOT NULL | |
| reviewer_type_code_snapshot / name_snapshot | VARCHAR | freeze時のsnapshot |
| reviewer_subject_fingerprint | CHAR(64) NOT NULL | §G2-VERIFY-10（HMAC・authority ID＋正規化登録番号） |
| verification_kind | VARCHAR(20) NOT NULL | IDENTITY / QUALIFICATION / ACTIVE_STATUS / REVIEW_AUTHORSHIP |
| result | VARCHAR(20) NOT NULL | VERIFIED / FAILED / INCONCLUSIVE / REVOKED |
| method_code | VARCHAR(50) NOT NULL | 公的source・手動確認の種別 |
| authority_source_name | VARCHAR(200) NOT NULL | 日弁連・社労士名簿・連合会/都道府県会・DQC等 |
| official_url_reference_snapshot | VARCHAR(1000) | 公式URL（管理者入力URLへのserver-side fetchはしない） |
| registration_identifier_encrypted | TEXT | 暗号化（専用鍵・key version/cipher format） |
| registration_identifier_key_version / cipher_format | VARCHAR | |
| registration_identifier_masked_snapshot | VARCHAR(255) | |
| checked_at | DATETIME(6) NOT NULL | |
| source_data_as_of | DATETIME(6) | 公的sourceのデータ時点 |
| valid_until | DATETIME(6) | 失効はvalid_untilから導出 |
| checked_by | BIGINT NOT NULL | 確認者（本人性確認者・資格確認者をReview者と混同しない） |
| evidence_document_id / version_id / version / hash | BIGINT×2・VARCHAR・CHAR(64) | exact version（§4-5） |
| mapping_id / mapping_version / mapping_hash / review_policy_hash | BIGINT・VARCHAR・CHAR(64)×2 | REVIEW_AUTHORSHIP用 |
| target_event_id / supersedes_event_id | BIGINT | 撤销は新規REVOKED eventで表現 |
| operation_id / correlation_id / idempotency_key | VARCHAR | |
| created_at | DATETIME(6) NOT NULL | |

- `UNIQUE(tenant_id, idempotency_key)`・`UNIQUE(tenant_id, id)`
- **UPDATE/DELETEはMySQL triggerで拒否**（`t_compliance_external_review_event`等の既存event表と同契約）
- `valid_until`未満なら自動expired扱い（FAILED/INCONCLUSIVEにはしない）

### 3.3 `t_compliance_external_review_event`の変更（V102_1・ADD COLUMN）

- 採用したidentity/qualification/active-status/authorship verification event IDをsnapshot:
  - `identity_verification_event_id`・`qualification_verification_event_id`・
    `active_status_verification_event_id`・`authorship_verification_event_id`（BIGINT・FK）
- APPROVED eventをgateへ採用する際、必要なverification参照が欠ければfail-closed。
- 既存列（credential_*）は残すが、gate採用判定には使わない（§G2-VERIFY-04）。

### 3.4 migration inventory（read-only・実環境history含む）

- common `db/migration`: V1〜V102の86ファイル（V19/V23/V41欠番・V66_1/V74_1/V74_2/V79_1含む）
- `db/migration-prod`: `R__update_admin_password_bcrypt.sql`
- test側: `src/test/resources/sql/v79_1-order-acceptance-legacy.sql`（Testcontainers fixture・Flyway対象外）
- `application.yml`: `locations: classpath:db/migration`・`baseline-on-migrate: true`・`baseline-version: 9`
- 実環境history: Flyway smoke（Testcontainers・`FlywayMigrationSmokeTest`等）とCIで検証。
  本packetでは追加のhistory変更なし。
- direct test（§3.4・受理前でも実行可能なdocs-only test）:
  1. `V102 < V102.1 < V103` のversion順序（Flyway `Version`相当の比較）を直接検証
  2. 既存migrationファイルのversion重複0・`V102_1`ファイルが未存在であることを検証
  3. V102が`m_compliance_mapping_version`等の既存shapeを保持（既存smokeで担保）
- R10受理前に`V102_1`ファイルを作成しない（作成は§3実装フェーズ）。

## 4. 既存P0修正（受理後・実装フェーズ）

1. `hasTypes`による空group skipを削除する。
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
- typed request/response DTOとallow-listを使用し、entityやMapをAPI契約にしない。
- evidence pickerはdocument/version/title/originalName/SHA-256/scan/createdAtだけを返す。
- CSRF・ActionPermissionResolver・MenuPermissionFilter・DataScope・tenant/workplace SQL境界をdirect testする。
- recorded_by・本人性確認者・資格確認者・Review者を画面上で混同しない。

## 6. regression matrix（受理後・最低限）

| # | ケース | 期待 |
|---|---|---|
| 1 | 空policy・空group・group typeなし | PROVISIONAL/ACTIVE/generate拒否 |
| 2 | credential文字列だけ | 採用拒否 |
| 3 | DQC VALIDだが本人性未確認 | 拒否 |
| 4 | 本人性確認済みだが資格未確認 | 拒否 |
| 5 | 登録存在でも業務停止/期限切れ | 拒否 |
| 6 | 公式公開list未掲載 | INCONCLUSIVE（自動FAILED/PASSにしない） |
| 7 | official source由来ではない連絡先だけの確認 | 拒否 |
| 8 | REVIEW_AUTHORSHIPがmapping/hash/evidence hash不一致 | 拒否 |
| 9 | evidence NULL/non-CLEAN/不存在/hash不一致/latest差替え | 拒否 |
| 10 | 全verificationとexact CLEAN evidence | 採用のみ |
| 11 | 自称氏名/組織/番号を複数入力 | distinct reviewerを増やせない |
| 12 | verification REVOKED/expired後 | ACTIVE/generate拒否 |
| 13 | 旧assignment approval | 交代後に利用不可 |
| 14 | future promote | 完全gateを通る |
| 15 | REVOKE後の再Review・異なるevidenceでの再確認・retry replay | 200 |
| 16 | tenant A/B・workplace/DataScope・role 5種・CSRF | 境界維持 |
| 17 | raw credential・本人確認資料path・完全fingerprint | API/logへ出ない |
| 18 | 過去delivery | 資格失効後も元版download可能 |
| 19 | V1/V102_1/H2/entity/mapper・fresh V1・V102→V102_1 upgrade・legacy/partial/failed-history-repair・skip 0 MySQL | 同期・成功 |

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
