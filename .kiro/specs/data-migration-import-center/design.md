# data-migration-import-center Design

## 1. 設計状態

実source適合・Owner・legacy schema・binding・実移行は未承認。技術委任の合成W0契約は[execution-contract.md](execution-contract.md)を正本とし、業務コード/DDLは本W0で追加しない。

原則は、source file → parser → canonical DTO → read-only validator / domain service → row result・reconciliationである。parserからmapperへ直接渡す経路、canonical DTOを飛ばしてentityを組み立てる汎用reflection経路、validate中の業務writeは採用しない。

## 2. 構成

### 2.1 レイヤー

1. Source adapter: UTF-8 BOM有無/Shift_JISを明示選択したCSVのquoteとrow boundaryを処理し、XLSXは拒否する。巨大sourceを全量Stringへ展開しない。
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
- VALIDATED: DB no-domain-write validationが完了し、validated_accepted_total/rejected/empty_skippedが確定。metadata allowlistはexecution-contract.md。
- READY: source hash、mapping hash、base snapshot、承認条件がvalidate結果と一致。
- APPLYING: CAS付きlease generationを取得し、chunkを有限の進捗単位として処理。
- COMPLETED:全row結果、件数/金額、result hash、auditが確定。
- FAILED:再開可能/要手動判定の理由を保存。無条件retryしない。再開は同じjobのlease/CASを再取得してAPPLYINGへ戻す。
- ROLLBACK_REQUESTED:対象・承認者・rollback policyを再確認する。新しい業務writeはまだ行わない。
- ROLLING_BACK: row result/id-map単位でrollback/compensationを実行する。
- ROLLED_BACK:承認済みrollbackとcompensationの結果が確定。
- ROLLBACK_FAILED:一部の補償が失敗し、再実行または管理者対応を必要とする終端状態。

不許可遷移、同じjobの二重apply、source identityが同じでmapping hashが異なる登録/再開を拒否する（409）。COMPLETEDから直接APPLYINGへ戻すことはできず、再importは同一identityかつ同一mapping hashの既存job再利用に限る。

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

営業、HR、マネージャー、要員、portal user、および未認証主体は、対象データへの通常の可視権限を持っていてもImport Centerのupload/mapping/preview/validate/apply/retry/rollback/error export/reconciliation downloadを実行できない。MVPは管理者＋明示されたimport権限だけを許可し、その他はUI表示の有無にかかわらずAPIで403、scope/role解決不能時はfail-closedとする。

空の許可集合は全件許可ではなく0件とする。preview、count、error export、apply、retry、rollback、document downloadで同じvisible populationを再計算し、UI非表示を認可の代替にしない。

#### 状態機械・競合

| 資源 | 競合制御 | 一意性/再開規則 |
|---|---|---|
| job | version CAS + apply lease | READY→APPLYINGは1回。lease期限後だけ同じjobを再開 |
| source identity | tenant + source-system + entity + schema + source_sha256がunique。mapping_hashはimmutable property。external key registryはtenant/source-system/entity/normalized key unique | 同一identityはmapping hash一致時のみ再利用。hash不一致は409、別job禁止 |
| mapping | mapping_version + mapping_hash | hash再計算不一致でREADY/apply拒否 |
| row | job_id + source_row_number、source row hash、attempt sequence/fencing generation | fingerprint一致はREUSED/DEDUPED attempt。business action/result/counters/hash維持 |
| target natural key | target domain unique/行lock | id-mapとtarget lookupを同一row transactionで照合 |
| checkpoint | row resultから再構築するprojection | 正本ではない。遅延・破損時はrow resultから再構成 |
| rollback | ROLLBACK_REQUESTED→ROLLING_BACK CAS | 二重rollbackを拒否し、compensation failureを終端化 |

### 2.4 状態遷移の許可表

| From | To | 許可条件 | terminal / 再開 |
|---|---|---|---|
| UPLOADED | MAPPED | source documentがCLEAN、job identityを直列化登録、mapping schemaが許可済み | 非terminal |
| MAPPED | VALIDATED | mapping hash/versionを固定してread-only validateを開始 | 非terminal |
| VALIDATED | READY | blocking finding 0、reconciliation式が成立、base snapshotと承認が一致 | 非terminal |
| READY | APPLYING | executorがCASと新fencing generationを取得、apply approvalあり | 非terminal |
| APPLYING | COMPLETED | 全row business result確定、apply_failed=0、差異0、business result hash確定 | terminal |
| APPLYING | FAILED | retryable/unknown errorを保存し、実行leaseを解放 | 非terminal。same jobだけ再開可 |
| FAILED | APPLYING | 同じsource/mapping/base、lease期限切れまたは明示retry承認、CAS成功 | 非terminal |
| APPLYING / FAILED / COMPLETED | ROLLBACK_REQUESTED | applied/updated prefixがあり、rollback対象と別承認者を確定。APPLYINGはlease失効後のみ | 非terminal |
| ROLLBACK_REQUESTED | ROLLING_BACK | rollback policy、scope、version、承認を再確認しCAS取得 | 非terminal |
| ROLLING_BACK | ROLLED_BACK | 全rowのrollback/compensation成功、reconciliation確定 | terminal。同一source identityを再applyしない |
| ROLLING_BACK | ROLLBACK_FAILED | 1件以上のcompensation失敗、失敗理由と未処理rowを保存 | operational terminal。再試行は別承認＋CASのみ |
| ROLLBACK_FAILED | ROLLING_BACK | 同じrollback plan、未処理row、scope/baseを再確認し、別のrollback lease/CASを取得 | terminal化まで再試行可 |

上表にない遷移、COMPLETEDからAPPLYINGへの遷移、ROLLED_BACKからの再apply、異なるmappingでのFAILED再開は拒否する。COMPLETEDからのrollbackは業務参照を壊さない範囲で明示承認を要求し、ROLLED_BACK後に同じsource identityをもう一度適用する場合はsource hashを変えた新しいsourceとして扱うのではなく、運用者が新しいmigration jobを承認できる状態になるまで拒否する。

## 3. 候補データモデル

### 3.1 t_import_job（候補）

job単位の不変入力と状態を保持する。sourceの全payloadやPIIをJSONで複製しない。

- identity: id、tenant_id、entity_type、schema_version、mapping_id/mapping_version
- state: status、version/CAS、apply_lock_owner、apply_started_at、finished_at
- source: document_id、source_sha256、source_size_bytes、detected_encoding、source_name
- actor: created_by、approved_by、executor、correlation_id
- reconciliation: source/validated_accepted_total/apply_pending/rejected/applied/updated/business_skipped/apply_failed/empty_skipped count、各amount、amount_excluded_count、currency、period_from/to
- evidence: base_snapshot_hash、mapping_hash、result_hash、failure_code、failure_message

source原本はDocumentService.registerReceivedで登録し、DocumentService.link(documentId, "IMPORT_JOB", jobId)で紐付ける。job作成とdocument登録の失敗は、sourceを孤児にしないcleanup-safeな状態へ記録する。source documentは業務rollbackで削除しない。

source identityは`(tenant, source-system, entity, schema_version, source_sha256)`でDB unique。mapping_hashはidentity外のimmutable job property。unique identity既存時はmapping hash一致だけ再利用し、不一致は409で拒否し、異なるmapping用の別jobを作らない。cross-job external key registryはtenant/source-system/entity/normalized external keyでunique。

### 3.2 t_import_mapping（候補）

- entity_type、schema_version、mapping_version、mapping_hash
- source columnsからcanonical pathへのmapping
- required/default/transform/lookup、date format、amount format、timezone、rounding、encoding policy
- natural key columns、duplicate policy、upsert policy、existing-row approval policy
- mapping status、created_by、approved_by、created_at

同一source identity `(tenant, source-system, entity, schema, source_sha256)`のjob登録を一意にし、mapping_hashはimmutable propertyとする。同じidentityがあればhash一致時だけ再開/再利用し、不一致は409。mapping変更だけでは同一identityの別jobを作成できず409とする。実際に異なるsource bytes/hashの場合だけ別jobを登録し、cross-job external key bindingの衝突を別途承認解決する。

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

job_id + chunk_noを一意にする。checkpointはrow result/registryを正本とする再構築可能projectionであり、checkpointとrow transactionを原子性の根拠にしない。外部I/Oはtransaction内に置かず、外部効果はtransactional outboxで冪等配信する。

### 3.5 t_import_id_map（候補）

- job_id、source_natural_key_hash、source_natural_key_display（必要最小限でマスク可）
- target_entity、target_id、action、first_source_row、last_result_hash
- resolution_type（NEW/EXISTING/CANDIDATE/MANUAL）、created_at

job_id + source_natural_key_hashおよび、approved target entity/idの組を一意にする。自然キーが不足・衝突・論理削除候補複数の場合はid-mapを確定せず、候補解決またはrejectとする。

## 4. Entity mappingと自然キー決定表

DG-06未決定のため、これは候補値と保留理由を示す表であり、承認済み自然キーではない。

| entity | 依存 | canonical/既存service | 候補自然キー | duplicate policy候補 | いま許可すること |
|---|---|---|---|---|---|
| Customer | なし | CustomerSaveDto / CustomerImportFacade | source_customer_codeはreal source approval待ち。syntheticではregistry externalKey | insert-only。更新は別mapping/binding approval/version時だけ | synthetic create、real sourceはcandidate resolution |
| Engineer | なし | EngineerSaveDto / EngineerImportFacade | source_engineer_codeはreal source approval待ち。syntheticはregistry externalKey | insert-only、updateは別承認binding/version時のみ | 既存CSV互換mappingの観測のみ |
| SkillTag / EngineerSkill / ProjectSkill | scope外 | create/replaceなし | — | — | child mutation禁止 |
| Project | Customer | ProjectSaveDto / ProjectImportFacade | source_project_codeはreal source approval待ち。syntheticはregistry externalKey | insert-only、updateは別承認binding/version時のみ | preceding Customer ref resolve |
| EngineerCareer / history | scope外child/history | — | — | — | 読取参照のみ。apply mutationなし |
| EngineerSkill / ProjectSkill | scope外 | create/replaceなし | — | — | child mutation禁止 |
| ProjectPosition | scope外child | create/update/assignなし | — | — | 参照取込/assignmentなし |
| Proposal | Engineer + Project | ProposalImportFacade | source_proposal_codeはreal source approval待ち。syntheticはregistry externalKey | insert-only、updateは別承認binding/version時のみ | duplicate finding |
| EngineerSales / assignment | scope外 | create/update/releaseなし | — | — | child mutation禁止 |
| Contract | Customer + Project + Engineer | ContractSaveDto / ContractImportFacade | source_contract_codeはreal source approval待ち。syntheticはregistry externalKey（contract_noへ写像しない） | insert-only、updateは別承認binding/version時のみ | relation consistency |
| ProposalHistory / career history | scope外child/history | create/updateなし | — | — | hard delete/compensationなし |

特にCustomer.company_name、Engineer.full_name、Project.project_nameは現行schemaで一意ではない。Contractのcontract_noは現行で一意だが、legacy側に欠損する場合の代替keyは承認なしに発明しない。

## 5. Domain validation正本

### 5.1 Validate path

validateは以下のread-only順序を通る。

1. parser structural validation（encoding/header/quote/row/cell）
2. canonical conversion（trim、enum、date、JPY BigDecimal、formula文字列）
3. source-row duplicate validation
4. reference resolver（現行DBのid、論理削除、候補複数、scope）
5. cross-row/entity validation（customer-project-contract、engineer-project、period/amount/status）
6. rejection/error export candidate生成
7. count/amount previewとvalidation snapshot保存

validateでは、CustomerService.save、ProjectService.saveProjectWithSkills、ContractService.saveWithBusinessRules、ProposalService.save、EngineerService.save、SkillTagResolver.resolveOrCreate、skill replace、mapper writeを呼ばない。既存domain serviceのvalidationが副作用を伴う場合、F2で副作用を分離したread-only validatorを追加する。

### 5.2 Apply path

applyはcanonical DTOを各domain serviceへ渡す。

- Engineer: EngineerService.save / updateWithStatusGuard。statusの契約整合、会計履歴、scope invalidationを維持する。
- Customer: 新規createの明示domain facadeを追加し、updateはversion付きCustomerService.updateWithOptimisticLockを使う。versionなしupdateは拒否する。
- Project: `ProjectImportFacade.createWithoutSkills/updateWithoutSkills`というtyped facadeを追加する。DTOからskills項目を拒否し、serviceのnull guardに依存して`skills=null`を強制してから`saveProjectWithSkills/updateProjectWithSkills`を呼ぶ。
- Proposal: 通常のProposal create pathと共有するtyped create commandを設け、validatePosition、active重複検査、proposedAt/proposedBy、EngineerStatusService.onProposalCreated（Bench→提案中）、AiOutcomeService.onProposalSaved相当（aiItemIdがある場合。positionIdは承認済み既存参照のみ）、auditを保つ。現行AI outcome helperの例外握りつぶしをImport pathで再利用せず、適用outcomeは厳格insertまたは一意キー付きdurable outboxで確定する。これらはproposal rowと同じrow transactionで確定し、例外時は全体rollbackする。非transaction効果はdurable idempotent outboxへ同transactionで記録する。初期「書類選考中」の新規作成だけを許し、status遷移/成約/Contract draft/history/通知を実行しない。確定済みrowの再開はattempt追記のみで副作用を再実行しない。
- Contract: ContractService.saveWithBusinessRules / updateWithBusinessRules。初期「準備中」のみでContractService.changeStatusは呼ばない。採番・compliance・staffing同期を維持する。
- SkillTag/EngineerSkill/ProjectSkill: 全てscope外。Import applyでresolveOrCreate/replaceSkills/createしない。
- EngineerSales/assignment: 全てscope外。Import applyでcreate/update/releaseしない。

既存serviceのsaveがgeneric IServiceを継承していて業務規則を持たない場合、Import Centerから直接呼ばず、ドメイン規則を集約するfacadeをF2で用意する。mapper直接insertは永続化の近道として認めない。

## 6. 冪等性・再開

### 6.1 apply lock

READY→APPLYINGとlease takeoverはjob/lease rowのatomic lock+CASでgenerationを増加させ排他取得する。各row transactionは同じrowをFOR UPDATE/equivalent CASでlockし、generationを検証してcommitまで保持する。二回の通常SELECTは不可。restartはcheckpointでなくrow result/registryから復元する。

### 6.2 chunk境界

1. source streamを一定数のchunkへ読み、canonical rowを検証する。chunkはメモリ/進捗単位であり、未確定の業務writeを意味しない。
2. rowごとにcross-job registryとtarget versionをlookup/lockする。fingerprint一致はattempt=REUSED/DEDUPEDを追記し、確定business result/counter/amount/hashを保持。不一致は停止。
3. 未処理rowだけdomain serviceへ渡す。
4. row単位transactionでdomain facade、target lock、cross-job registry binding/revision、id-map、business row result、audit/outboxを原子的にcommitする。同じjob/lease row lockとCASでtakeover/row transactionを直列化し、generationをtransaction中有効化。
5. `checkpoint`、`apply_pending`、終端counterはcommit済みrow resultから再構築可能なprojectionとして別途更新し、row/chunk transactionの原子性根拠にしない。projectionの遅延・破損はrow result再走査で修復する。
6. chunk checkpointをprojectionとして更新。全row resultからjob終端状態・reconciliation/result hashを確定する。

crash windowは次のとおり固定する。

- row transaction commit前: target/registry/id-map/business result/audit/outboxは同時rollback。checkpoint/apply_pending/終端counter projectionは別管理され未変更またはrow resultsから再構築され、同じrowを再処理する。
- commit後、checkpoint更新前: target/registry/resultが揃う。再開時はattempt=REUSEDとし、business counter/amount/hashを変更しない。
- job状態更新前: row resultを正本として照合し、未完了rowだけを処理する。
- lease期限切れ: 同じjob/lease rowを一つのtransaction内でlockし、generationをatomic CASで増加させたworkerだけが取得する。workerのrow transactionも同じlease row lockをcommitまで保持するためtakeoverとapplyが排他となり、旧generationのcommitを拒否する。通常SELECTの前後比較では代替できない。

これによりmid-chunk crash後の再開で重複作成を起こさない。10,000行ではread/parserをchunk化し、row transactionを既定の安全境界とする。target domainにnatural key unique/idempotency commandが不足する場合は、B1より前にそのdomain facade/constraintを追加する。

## 7. Rollback / compensation

entityごとに次の表をmapping versionへ記録する。

| rollback class | 動作 |
|---|---|
| 5 adapterの全row | hard delete、自動void、自動restoreを禁止。domain compensationの承認済operationが無ければjobを停止してmanual remediation要とする。manual remediation要表示だけでNF-06全体のrollback/運用機能完了とは扱わない |
| 論理削除・履歴保持が正本 | Import rollbackからのlogical delete/void/restoreも禁止。承認済み補償operationがなければmanual remediation |
| 後続参照あり/なし | 同上。参照の有無でhard-delete禁止を緩和しない |
| source既存行を更新 | 自動restore禁止。承認済みdomain compensationのみ。version conflictで停止しmanual remediation |
| Document/source/error/audit | rollbackから除外。retention/legal holdと監査証跡を保つ |

契約・提案成約・assignment・work record・invoice・document linkが存在する行は、親rowをhard deleteしない。compensationが失敗した場合はROLLBACK_FAILEDとし、成功扱いにしない。

## 8. Reconciliationとhash

- source hash: 保存したsource documentの実バイトSHA-256。
- mapping hash: canonical column path、format、transform、lookup、natural key、policyをstable sortしてSHA-256。
- row hash: source rowの元値をencoding復元後のcanonical representationでSHA-256。PIIをログへ出さない。
- result hash: row number順のrow result（target id、action、status、error code、amount、row hash）をstable serializeしてSHA-256。

amountはJPY BigDecimalで正規化し、scale/roundingをmapping versionに含める。validate時に不変基数`validated_accepted_total`を確定する。`source=validated_accepted_total+rejected`、`physical_rows=source+empty_skipped`。動的残数`apply_pending`はvalidated_accepted_totalから終端bucket合計を引く。終端式は`validated_accepted_total=applied+updated+business_skipped+apply_failed`。nonterminal errorはpending、終端失敗だけapply_failed。attemptのREUSED/DEDUPEDはbusiness_skippedではない。各amount対象カウンタには金額を、empty_skipped/amount_excludedには理由付き件数を保存する。

分類式は次で固定する。

- source_rows = validated_accepted_total + rejected_rows
- physical_data_rows = source_rows + empty_skipped_rows
- apply_pending_rows = validated_accepted_total - applied_rows - updated_rows - business_skipped_rows - apply_failed_rows
- terminal時: validated_accepted_total = applied_rows + updated_rows + business_skipped_rows + apply_failed_rows（apply_failedはretry終了後のterminal行のみ）
- source_amount_known = validated_accepted_amount + rejected_amount
- apply_pending_amount = validated_accepted_amount - applied_amount - updated_amount - business_skipped_amount - apply_failed_amount
- terminal時: validated_accepted_amount = applied_amount + updated_amount + business_skipped_amount + apply_failed_amount（apply_failedはretry終了後のterminal行のみ）
- validate_difference_count = source_rows - validated_accepted_total - rejected_rows
- validate_difference_amount = source_amount_known - validated_accepted_amount - rejected_amount
blank/invalid/formula-like amountはamount_excludedへ分離し、数値合計へ入れない。duplicateは同一source内の二重rowとしてrejected。既存registry/result一致によるrestartはREUSED/DEDUPED attemptのみで、business_skippedへ数えない。COMPLETEDの条件はvalidate_difference_count=0、validate_difference_amount=0、apply_failed_rows=0、apply terminal差異=0、result hash確定である。

金額の決定主体と確定時点も固定する。technical defaultはcurrency=JPY、scale=0、rounding=UNNECESSARYをmapping versionへ保存する。real sourceの適合承認者は別途必要。既定のrounding modeはUNNECESSARYとし、小数を含むsource amountは丸めずamount_excluded/rejectedにする。ROUND_HALF_UP等を使う場合はDG-06の承認理由とmapping versionへ明記し、canonical conversion時に一度だけ適用する。apply時に現在設定を再読込して丸め直すことは禁止する。

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
- idempotency: same source identity/different mapping hashを409、cross-job key collision、mid-chunk crash、checkpoint reconstruction、stale lease fencing、business hash equality。
- rollback: 後続参照あり、既存行update、compensation success/failure、audit/document retention。
- scale: 10,000行のstream/chunk、heap上限、checkpoint件数、重複なし。
- dialect: H2 fast suiteとMySQL Testcontainers gateを分離し、Flyway/unique/FK/lockをMySQLでも確認する。
- compatibility: Engineer CSVの既存golden testとCsvApiControllerの既存テストを維持する。

## 11. platform-invariantsとの差異

現時点で差異を設けない。time/asOf、actor×operation×visible population、state machine/concurrency、file双登録、afterCommit、money/Japanese locale/CSV、migration/H2/MySQL同期、audit/scopeの基線をそのまま適用する。

## 12. 技術increment契約

5 adapter、registry、fencing、no-write allowlist、synthetic acceptance、W1 waveは[execution-contract.md](execution-contract.md)を正本とする。
