# data-migration-import-center Design

## 1. 設計状態

本設計はNF-06の候補設計であり、DG-06の承認値を受けてから実装設計へ昇格させる。対象entity/schema、Owner、Base commit、legacy schema、natural key、upsert policyが未確定のため、以下のDDL名・API名・画面名は候補であり、本段階ではコード化しない。

原則は、source file → parser → canonical DTO → read-only validator / domain service → row result・reconciliationである。parserからmapperへ直接渡す経路、canonical DTOを飛ばしてentityを組み立てる汎用reflection経路、validate中の業務writeは採用しない。

## 2. 構成

### 2.1 レイヤー

1. Source adapter: CSV/XLSXのencoding、BOM、quote、cell type、formula、row boundaryを処理する。巨大sourceを全量Stringへ展開しない。
2. Canonical adapter: entityごとのimmutable canonical DTOへ変換し、空値、enum、日付、JPY amount、自然キー成分を正規化する。source row number/hashを常に持つ。
3. Read-only resolver/validator: current DBの候補、論理削除、参照整合、cross-row重複を読む。SkillTagResolver.resolveOrCreateやreplaceSkillsなどwrite系serviceは呼ばない。
4. Apply facade: canonical DTO、executor、correlation id、mapping versionを受け、既存domain serviceの公開メソッドを呼ぶ。domain serviceが不足するentityは、F2で明示的なfacadeを追加してから利用する。
5. Job state/checkpoint: job/row/checkpoint/id-map/reconciliationを同一のidempotency規則で管理する。
6. Evidence/export: source document、error CSV、row result、result hash、監査ログを運用者へ提供する。

### 2.2 Job state

UPLOADED → MAPPED → VALIDATED → READY → APPLYING → COMPLETED
                                      └──────────────→ FAILED
                                      └──────────────→ ROLLBACK_REQUESTED → ROLLING_BACK → ROLLED_BACK
                                                     └─────────────────────────────────→ ROLLBACK_FAILED

- UPLOADED: source documentがCLEANで、source SHA-256が確定。
- MAPPED: schema versionとmapping version/hashが確定。
- VALIDATED: DB no-write validationが完了し、accepted/rejectedとreconciliation候補が確定。
- READY: source hash、mapping hash、base snapshot、承認条件がvalidate結果と一致。
- APPLYING: apply lockを1つだけ取得し、chunk/checkpointを更新。
- COMPLETED:全row結果、件数/金額、result hash、auditが確定。
- FAILED:再開可能/要手動判定の理由を保存。無条件retryしない。再開は同じjobのlease/CASを再取得してAPPLYINGへ戻す。
- ROLLBACK_REQUESTED:対象・承認者・rollback policyを再確認する。新しい業務writeはまだ行わない。
- ROLLING_BACK: row result/id-map単位でrollback/compensationを実行する。
- ROLLED_BACK:承認済みrollbackとcompensationの結果が確定。
- ROLLBACK_FAILED:一部の補償が失敗し、再実行または管理者対応を必要とする終端状態。

不許可遷移、同じjobの二重apply、source hashが同じでもmapping hashが異なるapply、mapping変更後のREADY再利用を拒否する。COMPLETEDから直接APPLYINGへ戻すことはできず、再importは同一source identityの既存jobを返すか、別mappingとして拒否する。

### 2.3 platform-invariantsの3決定表

#### 時間・asOf

| 項目 | 決定 |
|---|---|
| 日付型 | canonical DTOはLocalDateまたはYearMonth。日時はInstant/LocalDateTimeをtenant timezoneで正規化する |
| timezone | tenant設定から取得し、Asia/Tokyoをコードへハードコードしない |
| 有効期間 | start/endはinclusive。end < startはreject |
| historical lookup | sourceのeffective date/asOfで候補を解決し、現在値だけへ黙って置換しない |
| 欠損日 | default=nowを自動適用しない。mappingで明示defaultが承認されない限りreject |
| monthly close | closed monthへのapply/updateはMonthlyClosingServiceのopen checkを通す |

#### 主体×操作×可視母集団

| 操作 | 主体 | 可視・変更可能な母集団 | 拒否条件 |
|---|---|---|---|
| upload/mapping/preview/validate | 管理者＋import権限 | tenant内の承認済みentity/schemaとsource document | role/menu/tenant不一致、scope外 |
| error/reconciliation download | 管理者＋job可視性 | jobとそのsource/error document、対象entityのvisible population | FileScope未登録、tenant/job不一致 |
| apply/retry | 管理者＋apply権限＋承認者 | validate snapshotと一致するscope内row | scope変化、approval不足、base hash不一致 |
| rollback | 管理者＋rollback権限＋別承認者 | 当該jobのapplied/updated rowのみ | downstream ref、version conflict、policy禁止 |
| async/batch | system executor（起動actorを保存） | 起動時にsnapshotしたtenant/scope | request SecurityContextの参照、scope未保存 |

空の許可集合は全件許可ではなく0件とする。preview、count、error export、apply、retry、rollback、document downloadで同じvisible populationを再計算し、UI非表示を認可の代替にしない。

#### 状態機械・競合

| 資源 | 競合制御 | 一意性/再開規則 |
|---|---|---|
| job | version CAS + apply lease | READY→APPLYINGは1回。lease期限後だけ同じjobを再開 |
| source identity | tenant + entity + schema + source_sha256のDB unique/lock | different mappingは409。same mappingのFAILEDは同一job再開 |
| mapping | mapping_version + mapping_hash | hash再計算不一致でREADY/apply拒否 |
| row | job_id + source_row_number、source row hash | 完了hash一致はSKIP、hash不一致は停止 |
| target natural key | target domain unique/行lock | id-mapとtarget lookupを同一row transactionで照合 |
| checkpoint | job_id + chunk_no、last_committed_row | commit済みprefixのみ再開。未commit rowは再処理 |
| rollback | ROLLBACK_REQUESTED→ROLLING_BACK CAS | 二重rollbackを拒否し、compensation failureを終端化 |

## 3. 候補データモデル

### 3.1 t_import_job（候補）

job単位の不変入力と状態を保持する。sourceの全payloadやPIIをJSONで複製しない。

- identity: id、tenant_id、entity_type、schema_version、mapping_id/mapping_version
- state: status、version/CAS、apply_lock_owner、apply_started_at、finished_at
- source: document_id、source_sha256、source_size_bytes、detected_encoding、source_name
- actor: created_by、approved_by、executor、correlation_id
- reconciliation: source/accepted/rejected/applied/updated/skipped count、各amount、amount_excluded_count、currency、period_from/to
- evidence: base_snapshot_hash、mapping_hash、result_hash、failure_code、failure_message

source原本はDocumentService.registerReceivedで登録し、DocumentService.link(documentId, "IMPORT_JOB", jobId)で紐付ける。job作成とdocument登録の失敗は、sourceを孤児にしないcleanup-safeな状態へ記録する。source documentは業務rollbackで削除しない。

source identityはtenant_id + entity_type + schema_version + source_sha256で定義し、同じidentityのjobを複数のapply対象として作らない。既存jobがMAPPED/VALIDATED/READY/APPLYING/COMPLETEDの場合、別mapping_hashは拒否する。同じmapping_hashのFAILED/lease期限切れjobだけを同一jobとして再開し、COMPLETEDは二重applyとして拒否する。この判定はアプリケーションの事前lookupだけでなく、DB unique/lockとCASで直列化する。

### 3.2 t_import_mapping（候補）

- entity_type、schema_version、mapping_version、mapping_hash
- source columnsからcanonical pathへのmapping
- required/default/transform/lookup、date format、amount format、timezone、rounding、encoding policy
- natural key columns、duplicate policy、upsert policy、existing-row approval policy
- mapping status、created_by、approved_by、created_at

同じsource hashへ別mappingを適用しないため、job内でmapping_hashを固定し、READY/apply時に再計算して一致を要求する。mappingのJSONは定義情報に限り、全row payloadは保存しない。source identityの別job lookupでもmapping_hashを比較し、different mappingを拒否する。

### 3.3 t_import_row_result（候補）

rowごとの判定とapply結果を一意に保持する。

- job_id、source_row_number、source_row_sha256
- validation_state、apply_state、action（INSERT/UPDATE/SKIP/REJECT/COMPENSATE）
- error_code、error_message、error_field、severity、candidate_count
- source_natural_key_hash、target_entity_id、result_hash、correlation_id
- amount、amount_state、processed_at、retry_count

job_id + source_row_numberを一意にし、row再実行で同じrow resultを重複insertしない。error exportはsource documentを再parseし、row number/hashで該当rowを取り出し、危険な先頭文字を文字列として無害化する。

### 3.4 t_import_checkpoint（候補）

- job_id、chunk_no、first_row、last_row
- status（OPEN/APPLYING/COMPLETED/FAILED）
- last_committed_row、processed/failed/skipped count
- checkpoint_hash、started_at、completed_at、failure_code

job_id + chunk_noを一意にし、checkpoint commitとそのchunkのrow result/id-mapを同じトランザクション境界に置く。外部I/Oはtransaction内に置かず、再試行時にdomain結果をid-mapで確認する。

### 3.5 t_import_id_map（候補）

- job_id、source_natural_key_hash、source_natural_key_display（必要最小限でマスク可）
- target_entity、target_id、action、first_source_row、last_result_hash
- resolution_type（NEW/EXISTING/CANDIDATE/MANUAL）、created_at

job_id + source_natural_key_hashおよび、approved target entity/idの組を一意にする。自然キーが不足・衝突・論理削除候補複数の場合はid-mapを確定せず、候補解決またはrejectとする。

## 4. Entity mappingと自然キー決定表

DG-06未決定のため、これは候補値と保留理由を示す表であり、承認済み自然キーではない。

| entity | 依存 | canonical/既存service | 候補自然キー | duplicate policy候補 | いま許可すること |
|---|---|---|---|---|---|
| Customer | なし | CustomerSaveDto / CustomerService | source_customer_code。無い場合はcompany_name正規化を候補検索だけに使用 | code一致のみupsert。名前だけはmanual | read-only候補表示 |
| Engineer | なし | EngineerSaveDto / EngineerService | source_engineer_code。既存CSVはkey無しlegacy insert-only | legacyはinsert-only。code有りのみ承認後upsert | 既存CSV形式のmapping観測 |
| SkillTag | なし | SkillTagResolver | normalize(skill_name) | exact normalized match。新規作成はapplyのみ | lookup-only |
| Project | Customer | ProjectSaveDto / ProjectService | source_project_code + customer key | code一致のみupsert。name+date推測禁止 | customer候補との参照表示 |
| EngineerCareer | Engineer | EngineerCareerServiceまたは専用facade | source_history_code。無い場合はengineer + period + project_nameを候補だけに使用 | insert-only/manual | 参照欠損検出 |
| EngineerSkill / ProjectSkill | parent + SkillTag | replaceSkills service | parent source key + normalized skill key | mapping定義がreplaceかappendかを承認 | lookup-only |
| ProjectPosition | Project | ProjectPosition domain path | project key + source position code/position_no | source code一致のみ | 参照候補表示 |
| Proposal | Engineer + Project | ProposalService | source_proposal_code | source code一致。それ以外はinsert-only/manual | duplicate候補表示 |
| EngineerSales | Engineer + sys_user | EngineerSalesService | source_assignment_code、または両者のsource key + assigned_at | 現任重複はreject。releaseはdomain operation | read-only履歴表示 |
| Contract | Customer + Project + Engineer | ContractSaveDto / ContractService | source_contract_codeをcontract_noへ写像。現行contract_noはunique | source code一致のみupsert。複合値推測禁止 | 参照整合表示 |
| ProposalHistory / career history | parent | 専用history service | source_history_code | insert-only。existing後続参照はdelete禁止 | row candidate |

特にCustomer.company_name、Engineer.full_name、Project.project_nameは現行schemaで一意ではない。Contractのcontract_noは現行で一意だが、legacy側に欠損する場合の代替keyは承認なしに発明しない。

## 5. Domain validation正本

### 5.1 Validate path

validateは以下のread-only順序を通る。

1. parser structural validation（encoding/header/quote/row/cell）
2. canonical conversion（trim、enum、date、JPY BigDecimal、formula文字列）
3. source-row duplicate validation
4. reference resolver（現行DBのid、論理削除、候補複数、scope）
5. cross-row/entity validation（customer-project-contract、engineer-project-position、period/amount/status）
6. rejection/error export candidate生成
7. count/amount previewとvalidation snapshot保存

validateでは、CustomerService.save、ProjectService.saveProjectWithSkills、ContractService.saveWithBusinessRules、ProposalService.save、EngineerService.save、SkillTagResolver.resolveOrCreate、skill replace、mapper writeを呼ばない。既存domain serviceのvalidationが副作用を伴う場合、F2で副作用を分離したread-only validatorを追加する。

### 5.2 Apply path

applyはcanonical DTOを各domain serviceへ渡す。

- Engineer: EngineerService.save / updateWithStatusGuard。statusの契約整合、会計履歴、scope invalidationを維持する。
- Customer: 新規createの明示domain facadeを追加し、updateはversion付きCustomerService.updateWithOptimisticLockを使う。versionなしupdateは拒否する。
- Project: ProjectService.saveProjectWithSkills / updateProjectWithSkills。skillsはProjectSkillService経由でreplace policyを守る。
- Proposal: ProposalService.save。status changeはProposalService.changeStatusのみを使い、成約時のcontract draft side effectを承認されたmappingに含める。
- Contract: ContractService.saveWithBusinessRules / updateWithBusinessRules。status changeはContractService.changeStatus、採番・compliance・staffing同期を維持する。
- Skill: SkillTagResolverはapply時のみ必要なら使用し、EngineerSkillService/ProjectSkillServiceへ渡す。
- Engineer-sales: EngineerSalesServiceでactive sales user、primary、released_atを検証する。履歴をhard deleteしない。

既存serviceのsaveがgeneric IServiceを継承していて業務規則を持たない場合、Import Centerから直接呼ばず、ドメイン規則を集約するfacadeをF2で用意する。mapper直接insertは永続化の近道として認めない。

## 6. 冪等性・再開

### 6.1 apply lock

job version CASでREADY→APPLYINGを1回だけ許可する。scheduler/HTTP retryはcorrelation idとjob idで同一applyと判定する。lock timeout後の再開は、last checkpoint、row result、id-map、target lookupを照合してから行う。

### 6.2 chunk境界

1. source streamを一定数のchunkへ読み、canonical rowを検証する。chunkはメモリ/進捗単位であり、未確定の業務writeを意味しない。
2. rowごとにid-mapとtarget natural keyをlookup/lockする。COMPLETED済みtarget/result hash一致はSKIPPED、hash不一致は同一jobの改竄/不整合として停止する。
3. 未処理rowだけdomain serviceへ渡す。
4. row単位のREQUIRES_NEW transactionで、domain service、target natural key lock、row result、id-map、last_committed_rowを同一DB transactionへ入れる。domain serviceの@Transactionalはこのtransactionへjoinし、mapper直insert/updateは使わない。
5. row transactionがcommitするまで、target/domain変更もrow result/id-map/checkpointも外部から確定済みと見なさない。commit後の通知/cache invalidationはafterCommitで実行する。
6. chunk完了時にcheckpointをCOMPLETEDへCASする。job全chunk完了後にCOMPLETEDへCASし、reconciliation/result hashを確定する。

crash windowは次のとおり固定する。

- row transaction commit前: target変更、row result、id-map、checkpointが同時にrollbackされ、再開時に同じrowを再処理する。
- commit後、次row開始前: target/result/id-map/last_committed_rowが揃っているため、再開時はhash一致をSKIPPEDにする。
- job状態更新前: COMPLETED checkpointを再照合し、未完了chunkだけを処理する。
- lease期限切れ: 同じjobのCASを再取得し、異なるexecutorが同時にapplyしない。

これによりmid-chunk crash後の再開で重複作成を起こさない。10,000行ではread/parserをchunk化し、row transactionを既定の安全境界とする。target domainにnatural key unique/idempotency commandが不足する場合は、B1より前にそのdomain facade/constraintを追加する。

## 7. Rollback / compensation

entityごとに次の表をmapping versionへ記録する。

| rollback class | 動作 |
|---|---|
| 可逆新規で後続参照なし | domain delete/void facade。ただしhard delete可否はentity ruleで明示 |
| 論理削除・履歴保持が正本 | domain serviceのlogical delete/voidのみ |
| 後続参照あり | hard delete禁止。補償更新、取消、status revert、link解除などをdomain service/approvalで実施 |
| source既存行を更新 | 自動rollback禁止またはbefore snapshot復元を管理者承認。version conflictで停止 |
| Document/source/error/audit | rollbackから除外。retention/legal holdと監査証跡を保つ |

契約・提案成約・assignment・work record・invoice・document linkが存在する行は、親rowをhard deleteしない。compensationが失敗した場合はROLLBACK_FAILEDとし、成功扱いにしない。

## 8. Reconciliationとhash

- source hash: 保存したsource documentの実バイトSHA-256。
- mapping hash: canonical column path、format、transform、lookup、natural key、policyをstable sortしてSHA-256。
- row hash: source rowの元値をencoding復元後のcanonical representationでSHA-256。PIIをログへ出さない。
- result hash: row number順のrow result（target id、action、status、error code、amount、row hash）をstable serializeしてSHA-256。

amountはJPY BigDecimalで正規化し、scale/roundingをmapping versionに含める。reconciliationにはsource、accepted、rejected、applied、updated、skippedに加え、apply_failed、empty_skipped、amount_excludedを各件数・金額または理由付き件数で保存する。

分類式は次で固定する。

- source_rows = accepted_rows + rejected_rows
- physical_data_rows = source_rows + empty_skipped_rows
- accepted_rows = applied_rows + updated_rows + skipped_rows + apply_failed_rows
- validate完了時は apply_failed_rows = 0
- source_amount_known = accepted_amount + rejected_amount
- accepted_amount = applied_amount + updated_amount + skipped_amount + apply_failed_amount
- difference_count = source_rows - accepted_rows - rejected_rows
- difference_amount = source_amount_known - accepted_amount - rejected_amount

blank/invalid/formula-like amountはamount_excludedへ分離し、数値合計へ入れない。duplicateは同一source内の二重rowとしてrejected、既存id-mapのhash一致による再実行だけをskippedとする。COMPLETEDの条件はdifference_count=0、difference_amount=0、apply_failed_rows=0、result hash確定である。

## 9. File / UI / API候補

### 9.1 File

UploadはFileStorageService/FileKindとDocumentServiceの境界を再利用し、独自のupload pathを作らない。CSV用FileKind、XLSX用FileKind、source/errorのretentionはDG-06後に決める。error CSVはstream出力し、CsvUtilsと同じRFC4180/formula injection規則を使う。

DocumentLinkのtargetType=IMPORT_JOBは、既存のFileScopeValidationService/DocumentService access boundaryへ登録する。jobのtenant、executor/承認者、対象entityのDataScope/OrganizationScope、document statusを検証し、未登録のtargetTypeやscope不明はfail-closedで403とする。FileReferenceProviderはcleanupからの孤児削除を防ぎ、FileScopeValidationは閲覧・download・error exportの認可を担う。DocumentServiceがIMPORT_JOBを認識できない状態で実装を進めない。

### 9.2 API

候補 endpoint:

- POST /api/import-jobs
- GET /api/import-jobs/{id}
- POST /api/import-jobs/{id}/mapping
- POST /api/import-jobs/{id}/preview
- POST /api/import-jobs/{id}/validate
- POST /api/import-jobs/{id}/apply
- POST /api/import-jobs/{id}/rollback
- GET /api/import-jobs/{id}/errors
- GET /api/import-jobs/{id}/reconciliation

すべてApiResultで返し、CSRF、role、menu、DataScope/OrganizationScope、auditを適用する。preview/validateはread-only、apply/rollbackはjob stateと承認を再検証する。

### 9.3 UI

Upload → entity/schema選択 → mapping → sample/preview → validation errors → reconciliation → apply approval → progress/restart/rollbackの順とする。画面上で、自然キー、upsert/insert-only、既存行更新、rollback可否、amount差異、source/mapping/result hashを常に表示する。

## 10. テスト設計

- parser: UTF-8 BOM/no BOM、Shift_JIS、CRLF/LF/CR、quoted newline、escaped quote、comma。
- safety: =、+、@、tab、CR、通常の負数、巨大cell、制御文字、malformed quote、wrong encoding。
- formula/number: 型がnumericの列だけはBigDecimal全体parseに成功する-50000、-1.5、+1、指数表記を通常の数値として扱う。numeric parseに失敗した-1+cmdや、型がStringの列の-始まりはformula-like literal/warningとして扱い、error/exportでは無害化する。
- semantic: required、enum、date/amount、duplicate natural key、missing reference、logical-delete candidate、customer-project-contract mismatch。
- no-write: validate前後の全対象table count/max id/updated_at/hashが不変。resolveOrCreate/replaceSkills等のwrite mockは0回。
- idempotency: double apply、same hash/different mapping、mid-chunk crash、checkpoint restart、result hash mismatch。
- rollback: 後続参照あり、既存行update、compensation success/failure、audit/document retention。
- scale: 10,000行のstream/chunk、heap上限、checkpoint件数、重複なし。
- dialect: H2 fast suiteとMySQL Testcontainers gateを分離し、Flyway/unique/FK/lockをMySQLでも確認する。
- compatibility: Engineer CSVの既存golden testとCsvApiControllerの既存テストを維持する。

## 11. platform-invariantsとの差異

現時点で差異を設けない。time/asOf、actor×operation×visible population、state machine/concurrency、file双登録、afterCommit、money/Japanese locale/CSV、migration/H2/MySQL同期、audit/scopeの基線をそのまま適用する。
