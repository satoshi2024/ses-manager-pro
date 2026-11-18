# NF06 F2 synthetic read-only resolver 統合計画

状態: DRAFT / Astra PLAN Review 待ち。実装・実source適合・production enablementの承認ではない。

## 1. 固定基線と目的

- base: `d8f381a55460d32df00a85d294d3bb198ed642f4`。
- branch: `codex/snf-nf06-f2-plan-20261105`。本書以外の変更を含めない。
- 目的: 既存のparser/canonical adapter/validatorを再利用し、trusted synthetic validation contextから既存domain rowをread-only照合する経路を閉じる。
- W3Dとは独立したF2 sliceであり、W3D実MySQL FullGate待ちをコード依存にしない。実行窓は分離し、F2差分を稼働中のFullGate固定treeへ含めない。
- 現在のW3D source `4d2054ec58280c2881be9303ec7dfbbc067829a0` はAstra限定CODE PASS、d8派生H2はSealService 25 + IdentityCandidate 12 = 37/0/0/0とのroot証拠あり。W3D MySQL/FullGateは未完、NF06は未完。
- `f1b-wave3-seal-publish-plan.md` の「W3D CODE REVIEW待ち」は現状と矛盾するため、後続docs-only同期対象として記録する。本書で既存履歴は書き換えない。

## 2. 既存境界と再利用

- `foundation/ImportValidationPipeline.validateMapped(...)` がCSV parse、canonical conversion、`ImportValidationService.validate(...)`、`ValidationCounterAccumulator`を連結する。
- `foundation/ReadOnlyImportResolver.resolve(ReferenceType, tenantId, legalEntityId, normalizedExternalKey)` はwrite APIを持たない。
- `ReferenceLookup` / `LookupStatus` / `ReferenceTarget` はNOT_FOUND、LOGICALLY_DELETED、AMBIGUOUS、OUT_OF_SCOPE、UNIQUE_ACTIVEとrelation keyを表現する。
- `metadata/ImportValidationAppendService` はappend/recount時に常にnullを返すfail-closed resolverを使う。既定production pathのresolverを有効化しない。
- `ImportValidationLifecycleService.AppendSnapshot.context()` が現在認可されたtenant/actor/legalEntityを提供する。request由来tenant、legalEntity、resolver、target IDを信頼しない。
- 五つのadapterと現行field/error contractは`ImportValidationService`、`SesImportV1Schema`、既存foundation testsを正本とする。新しいfield、natural key、adapter、counterを追加しない。

## 3. 最小実装範囲（本書承認後）

### 3.1 Resolver seam

- 変更: `metadata/ImportValidationAppendService.java`。
- field `validationResolver`を追加し、package-private constructorで既存pipelineと`ReadOnlyImportResolver`を同一instanceとして受け取る。`appendRemainingWithSealCapability(...)`と`recountSealed(...)`の両方で、この同じfieldからそれぞれの`ValidationContext`を構築する。
- test constructor以外の`@Autowired` constructorは既存fail-closed resolverを設定する。両context構築箇所の一方だけを差替える実装は禁止し、production profile、API、UIからresolver選択やalias catalogを渡せるbean/entrypointを作らない。
- scopeはappend snapshotの`ImportAuthorizedContext`から導出する。resolverはtenant/legalEntityを固定し、呼出引数のscopeが異なればOUT_OF_SCOPEでfail-closed。

### 3.2 Test-only synthetic resolver

- 新設: `src/test/java/com/ses/importcenter/metadata/SyntheticJdbcImportResolver.java`。
- `ReadOnlyImportResolver`を実装し、`JdbcTemplate`でtest fixtureが作る非実在synthetic keyを既存targetの明示列と完全一致照合する。CSVからtarget ID/lookup catalogを受け取らない。
- fixtureは`SYNTH-*`のような非PII keyだけを用いる。これはsynthetic validation用候補検索であり、本番natural key契約ではない。候補IDは表示・finding用に返すだけで、registry、id-map、target bindingへ保存しない。
- target queryは型ごとの固定allowlistとparameterized SELECTだけを使い、`deleted_flag`と実在する`legal_entity_id`/親行scopeでstatusを判定する。列名・joinはV1/V157と現行entity定義に照合する。
- tenant scopeはdomain表間で一貫しない（多くはtenant_idなし、`m_organization_unit.tenant_id`はBIGINTだがImport contextはString）。試験では同列をNULLに固定し、DBによるtenant分離を証明しない。本resolver/fixtureはsynthetic限定とする。legal entity不一致はOUT_OF_SCOPE。
- `ReferenceTarget`には最小id、存在する場合だけversion、active、relationKeys、DBで完全一致したsynthetic keyを返す。PII/name/ciphertext/raw rowを含めず、未対応ReferenceType、未知key、scope不明はUNIQUE_ACTIVEにしない。

### 3.3 Adapter relation matrix

| Import row | 既存lookup type | relation検証 |
|---|---|---|
| CUSTOMER | なし | field validationのみ |
| ENGINEER | ORGANIZATION / COST_CENTER (optional) | 存在時のみ照合 |
| PROJECT | CUSTOMER | customerが一意activeであること |
| PROPOSAL | ENGINEER / PROJECT / POSITION (POSITION optional) | position.projectと指定projectを照合 |
| CONTRACT | CUSTOMER / ENGINEER / PROJECT | project.customerと指定customerを照合 |

| ReferenceType | synthetic-only exact lookup列 | scope / relation |
|---|---|---|
| CUSTOMER | `m_customer.company_name` | `legal_entity_id` exact |
| ENGINEER | `t_engineer.full_name` | `legal_entity_id` exact |
| PROJECT | `t_project.project_name` | `legal_entity_id` exact; `customer_id` relation |
| POSITION | `t_project_position.role_name` | 親`t_project`のlegal entity; `project_id` relation |
| ORGANIZATION | `m_organization_unit.code` | `tenant_id IS NULL`, `legal_entity_id` exact; status/validity |
| COST_CENTER | `m_cost_center.code` | `legal_entity_id` exact; status/validity |

keyはbinary exact matchとし、同名複数はAMBIGUOUSにする。nullable legal entity、参照scope不一致、disabled/expired organization/cost center（DB現在日付によるvalidity判定）はfail-closed。ここでのname/code matchはSYNTH-onlyの候補照合であり、DG-06自然キー選定、upsert、target bindingへ転用しない。

## 4. 契約

- 入力: serviceが認可・snapshotしたtenant/legalEntity、immutable mapping、DocumentService経由のexact source stream、targetの明示列へfixtureがseedしたsynthetic key。
- 出力: 既存`ValidatedRow` / `ImportFinding` / counter snapshot。source SHA、row hash、PII非保持の既存契約を維持する。
- 正常: keyが一意active・同legalEntityのrowと一致する場合だけUNIQUE_ACTIVE候補を返す。これはvalidation候補であり、identity binding/approvalではない。
- 拒否: 既存validatorの`REFERENCE_NOT_FOUND`、`REFERENCE_DELETED`、`REFERENCE_AMBIGUOUS`、`REFERENCE_OUT_OF_SCOPE`、`RELATIONSHIP_MISMATCH`、`RELATIONSHIP_UNVERIFIABLE`、`REFERENCE_LOOKUP_UNKNOWN`を維持する。
- 入力source keyが無い、exact matchなし、lookup結果のtenant/legalEntity/id/status矛盾、複数候補、関係キー不足はacceptedにしない。
- SQL/connection timeout等のinfra failureはdomain findingへ変換せず伝播する。
- source全読取とparser error/EOF/hash確認は既存append lifecycleを使う。resolver lookup中にtarget/domain write、sequence採番、registry/id-map更新を行わない。

## 5. 変更ファイルと試験

- main変更: `src/main/java/com/ses/importcenter/metadata/ImportValidationAppendService.java`（test-only resolver injection constructor）。既定constructorのfail-closed動作を回帰確認。
- test新設: `src/test/java/com/ses/importcenter/metadata/SyntheticJdbcImportResolver.java`、`ImportValidationResolverRealMySqlTest.java`。
- test更新: `src/test/java/com/ses/importcenter/foundation/ImportValidationServiceTest.java` と既存pipeline/counter testsは既存pure contractを維持。必要なcontext-construction helperのみ追加する。
- MySQL test classは`@Tag("mysql")`を付け、`scripts/test-suites/mysql-shard-3.txt`とinventoryへ一度だけ登録する。H2のテストは既存fast gate内に置く。
- H2/service: 5 entity positive/negative、optional/required reference、tenant/legalEntity mismatch、missing/deleted/multiple、project-customer・position-project mismatch、catalog/target矛盾、unmapped/unknown fail-closed。
- 実service経路: required referenceを持つsynthetic positiveを`appendRemainingWithSealCapability` → `lifecycle.seal` → `recountSealed`まで実行する。append後とrecount後のresolver callを同一instanceで記録し、recountがUNKNOWNにならないことを確認する。保存rowのfinding codes/counters/row commitmentはseal前後・recount前後で一致させる。`@Autowired` default constructorのfail-closed回帰も別caseで確認する。
- MySQL: 実V1/V157 schemaでSELECT結果のid/deleted/legalEntity/relationKeysを検証。SELECT-only recorderはresolver発行SQLだけを対象にし、append/seal/recount全体のmetadata SQLをread-onlyと誤認しない。
- no-write境界は`execution-contract.md` §4（`validate` metadata allowlist）に従う。許可されるmetadata writeは`t_import_job`のstate/source hash/actor/time/lease/version、`t_import_validation_run`のrun metadata/hash/counters、`t_import_validation_row`のrow番号/hash/fingerprint/finding codes。`t_import_mapping`は本sliceではread-only。
- 禁止writeの証拠: CUSTOMER/ENGINEER/PROJECT/PROPOSAL/CONTRACTおよび参照するOrganization/CostCenter/Positionのrow snapshot hash、業務sequence状態とbusiness-table max ID、`t_import_identity_registry`/binding、id-map/checkpoint、domain history/audit、document linkageは不変。id-map/checkpoint schemaが未導入ならその不在と該当SQL/write callが0であることを確認する。domain write mapper/service spyは0回。
- lifecycleが許可metadata row/runをinsertし、job lease/versionを更新すること、およびそのmetadata auto-increment IDが増えることはno-write違反に数えない。resolver SQL recorderはresolver自身のSELECT-onlyだけを判定する。
- test dataは専用MySQL fixture内で生成・cleanupし、既存本番/共有データを参照しない。H2をMySQL dialect/ACL proofとして扱わない。

## 6. Demoと受入

- 合成CSVを既存pipelineへ渡し、5 entity別のcanonical row、source row hash、finding code、counterを表示する。
- missing/deleted/ambiguous/out-of-scopeとrelation mismatchを一件ずつ再現し、未知aliasはclosed findingになることを示す。
- `appendRemainingWithSealCapability` → `lifecycle.seal` → `recountSealed`のsynthetic positiveでresolverが両scan段階に同じinstanceとして使われ、finding/counter/row commitmentが一致する証拠を示す。
- 許可metadata allowlistの書込みを別掲し、それ以外のbusiness table/sequence/registry/id-map/history/audit/document linkageのbefore/after hash・max ID不変、domain write spy 0回を示す。resolver SQLのみSELECT-onlyである証拠を添付する。
- H2/MySQL対象のreport、固定開始/終了SHA、clean status、shard inventory差分、Demo artifactをAstraへ渡す。未実行・skipありはPASSとしない。

## 7. 明示的対象外・前提差

- migration/DDL、V185、API/controller/UI/upload/FileKind、external source connector、real source/schema/credential、target binding/approval、READY/APPLYING、domain apply/upsert、identity registry state change、rollback/compensationは変更しない。
- `DG-06`、data Owner、実source schema/natural key/target bindingはproduction data applyの別human gate。synthetic keyや候補IDは承認証拠にならない。
- `ReferenceTarget.externalKey`とdomain tableに共通のexternal-key columnは確認できない。synthetic row fieldの一致結果をtest fixtureだけで使い、natural-key mappingを本番へ一般化しない。
- F2 foundation CODE PASS (`1e8aa651e6ec4901e1e498aca1a484a7b88fe4c8`)はpure parser/canonical validation限定。F2全体・DB resolver・DemoのPASSではない。
- Astra PLAN Review PASS後にだけこの範囲を実装する。本書はPLAN承認依頼であり、コード実装は含まない。
