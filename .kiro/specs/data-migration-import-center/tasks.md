# data-migration-import-center Tasks

## 進行ルール

- 実sourceのDG-06は未決定のまま保持する。技術委任によるW0文書・合成schema/fixture設計は完了可能。DDL/business code/実source取込は対象外。
- 各完了Taskは該当waveのReview gateを通し、Demo証拠を記録する。commit/push範囲は親タスクの明示指示に従う。
- 通常checkout C:\work\ses-manager-proは変更しない。
- Reviewへ渡すのはfixed head、technical scopeと実source承認の区別、requirements/design/tasks、完了対応表、test/Demo証拠である。
- Synthetic W1はAstra Plan Review PASS後に開始可能。実legacy適合とproduction data applyにはOwner、source/schema/binding/DG-06等の既存human gateが必要で、synthetic実装の開始条件にしない。

## 0. Source schema / discovery / gate（完了）

### Objective

現行schema、既存Engineer CSV、ingestion、domain service、DocumentService、監査、batch operationの再利用境界と、DG-06で承認が必要な事項を固定する。

### Implementation guidance

- 旧schema DDL/fixtureの所在を探索し、現repo/到達可能履歴に無いことを記録した。
- entityごとの候補自然キー、依存順、insert-only/upsert候補、rollback/compensation境界を承認前の候補として記録した。
- parser → canonical DTO → read-only validator → domain serviceの境界を明記した。
- validate中のwrite、mapper直insert、後続参照行のhard delete rollbackを禁止した。
- validated_accepted_total固定基数/apply_pending projection、JPY集計、business result hashとattempt logをexecution-contract.mdで定義した。

### Test requirements

- 現行のEngineer CSV test、CsvUtils test、CsvApiController test、既存ingestion/domain/document/batch testの期待値を確認する。
- 旧schemaが未提供であるため、旧データを生成して本番入力とみなすtestは作成しない。

### Demo

- 専用worktree root、branch、status、remote、base commitを表示する。
- discovery.mdの読了資料・現行schema・未解決ゲートをOwnerが確認する。
- mapping spike候補がDB writeを行わないことを、実装なしの設計証跡として確認する。

### Evidence

- discovery.md
- requirements.md R0/R6/R7
- design.md §§2–5、§8

## F1. Job / mapping / registry / row / checkpoint schema（Astra Plan PASS後。versionは総括割当）

### Objective

t_import_job、t_import_mapping、t_import_row_result、t_import_checkpoint、t_import_id_mapと、source document link、version/CAS、unique/idempotency、reconciliation fieldsを追加する。

### Implementation guidance

- 最新migration番号の次を使い、適用済みmigrationを編集しない。
- MySQL fresh、legacy/upgrade、partial/backfill、repairの各schema shapeを検証する。
- V1へ逆戻りして列を追加せず、新テーブルは独立migration、H2は専用schema fixtureとして同期する。
- source file全量やrow payloadをJSON保存せず、document_id、row number、row hash、error/id/result hashを保存する。
- external identityはtenant/source-system/entity/normalized external keyでcross-job unique。binding revision/target version、row transaction、audit/outbox、fencing generationをMySQLで検証する。checkpointはrow resultから再構築できるprojectionとする。
- IMPORT_JOBのDocumentLinkをFileScopeValidationServiceへ登録し、FileReferenceProviderとは別に閲覧/download/error exportのfail-closed認可を確認する。

### Test requirements

- Flyway fresh/legacy/partial、H2 context、MySQL Testcontainersを実行する。
- duplicate job, duplicate row, duplicate source key, same source identity/different mapping hash 409、checkpoint/counter projection rebuildをtestする。

### Demo

- EXPLAIN/DDL確認で、重複applyを一意制約とCASが拒否することを示す。
- source documentとIMPORT_JOB link、FileReferenceProviderの参照が確認できる。

## F2. Parser / canonical adapter / validate no-write（Astra Plan PASS後）

- Project.statusのEntity Patternとt_project DB enum値が不一致。W1でdomain validationの整合計画を含め、import adapterだけの局所回避をしない。

### Objective

CSV parser（XLSX拒否）とentityごとのcanonical DTO、read-only resolver、field/cross-row validationを実装する。

### Implementation guidance

- UTF-8 BOM/no BOM、Shift_JIS、CRLF/LF/CR、quoted newline、escaped quoteをstreamで処理する。
- XLSXは初期scope外。拡張子とmagicの両方で拒否する。
- formula injectionと通常の負数を別分類し、numeric parse結果で扱う。
- 5 adapter以外のskills/SkillTag、positions、history、EngineerSales/assignmentは全てscope外とし、lookup/create/replace/releaseを行わない。
- validateではCustomerService.save、ProjectService.saveProjectWithSkills、ContractService.saveWithBusinessRules、ProposalService.save、EngineerService.save、SkillTagResolver.resolveOrCreate、skill replace、mapper writeを呼ばない。
- Customer createにdomain validationが不足する場合は、generic saveの横に明示facadeを追加し、importからgeneric mapper pathを直接呼ばない。

### Test requirements

- parserの全境界、巨大cell、10,000行、malformed quote、wrong encodingをtestする。
- validate前後の対象table count/max id/updated_at/hash不変、write mock 0回をtestする。
- missing reference、論理削除候補、同名候補複数、customer-project-contract不整合、period/amount不整合をtestする。

### Demo

- sample sourceからcanonical row、candidate、error code、source row hashだけを表示し、DB差分が0であることを示す。

## A1. Upload / mapping / preview / error UI（synthetic F1/F2 Review後）

### Objective

管理者がupload、entity/schema選択、mapping、preview、validation error、reconciliation候補を確認できる画面/APIを提供する。

### Implementation guidance

- Page controllerはview名だけ、APIはApiResult、更新系はCSRFとApiAuditFilterを通す。
- UIの表示だけで認可せず、dynamic menu、DataScope、OrganizationScopeをAPI/service側で再検証する。
- natural key、duplicate policy、upsert/insert-only、existing-row approval、rollback class、amount差異、source/mapping hashを表示する。
- error exportはsource documentを再parseし、row number/hashで該当rowを取り出してCSV injection-safeにstreamする。

### Test requirements

- role/menu/scope、CSRF、session expiry、PII mask、preview no-write、error exportのquoted newline/formula/巨大cellをtestする。
- desktopと390pxのUI smokeを実施する。

### Demo

- 管理者でsampleをuploadし、preview/error/reconciliationを確認する。synthetic Applyを実行し、production data applyだけがsource/Owner/DG-06 gateで表示/実行不可であることを確認する。

## B1. Apply / restart / idempotency（synthetic facade Review後）

### Objective

READYのjobだけを、canonical DTOからdomain service経由でchunk applyし、checkpointから重複なく再開する。

### Implementation guidance

- READY→APPLYINGをCAS/lockで一度だけ許可する。
- 各rowでtarget/registry/id-map/business result/audit/outboxを同一DB transactionで確定する。checkpointとapply_pending/終端counterはrow resultから再構築可能なprojectionとし、row transactionに含めない。
- crash再開で確定済みbusiness resultは保持し、REUSED/DEDUPED attemptだけをappendする。business SKIPPEDへ再分類しない。
- importではどのadapterもstatus変更/遷移methodを呼ばない。各新規rowの初期状態のみ契約値で確定し、更新では既存statusを保持する。Proposal作成でEngineer状態が変化しないtyped domain entry pointを用意する。
- updateはversion/既存行承認、clearable ALWAYS fields、scope、audit、side effectを維持する。

### Test requirements

- double apply、same source identity/different mapping hash拒否、chunk境界crash、restart、target natural key collision、version conflictをtestする。
- 10,000行でtarget重複0、row resultとcheckpointの整合をtestする。
- row commit前後、checkpoint/counter projection遅延/破損、同じlease row lock/CASによるtakeover排他、generation更新後の旧worker commit拒否をtestする。同一counter/amount/result hashを中断なしと再開で比較する。

### Demo

- 途中chunkでプロセスを止め、再起動後にCOMPLETEDまで進め、target件数・id-map・row resultの重複0を示す。

## B2. Rollback / compensation / reconciliation（rollback classのReview後）

### Objective

apply前に宣言したrollback classに従い、後続参照を壊さず補償し、件数・金額・hash証拠を確定する。

### Implementation guidance

- 5 adapter全てでhard delete rollbackを禁止する。status遷移、skills/SkillTag、positions、history、EngineerSales/assignmentのcreate/update/replace/releaseはscope外。5 adapterは全rowでhard delete/自動void/取消/restoreを禁止し、compensationが未定義ならmanual remediation。
- source existing row updateは自動復元せず、before snapshot/version conflict/管理者承認を要求する。
- compensation actionとrollback failureをrow/job/auditへ記録する。
- source document、error export、auditはretention/legal holdを尊重し、rollbackで削除しない。
- validate/apply source/validated_accepted_total/apply_pending/rejected/applied/updated/business_skipped/apply_failed/empty_skipped/amount_excluded counters、JPY amount、excluded reasonを再計算し、designの分類式とdifference 0を完了条件にする。

### Test requirements

- downstream referenceありのrollback拒否/補償、compensation failure、partial rollbackをtestする。
- source/mapping/result hash、actor、time、base snapshot、auditの証拠をtestする。

### Demo

- 後続参照ありrowでhard deleteが起きないこと、compensationまたはapproval queueになること、reconciliation差異0を示す。

## M. 10,000行 / 障害 / 復元 / compatibility（MySQL + browser gate）

### Objective

本番相当のsourceで性能・障害復旧・安全性・既存Engineer CSV互換を総合確認する。

### Implementation guidance

- synthetic 10,000行をstream/chunkで処理し、heap、DB lock、checkpoint、audit、FileCleanupの参照を計測する。
- source hash、mapping version/hash、result hash、row/amount reconciliationをartifactとして保存する。
- H2 fast suite、MySQL gate、performance gateを分離して実行し、skip 0を維持する。
- Engineer CSVの既存endpoint/golden fixtureを変更せず、新mappingは明示的に別経路で検証する。

### Test requirements

- UTF-8 BOM/Shift_JIS、quoted newline、formula injection、巨大cell、duplicate/missing ref、10,000行をsynthetic fixtureでtestする。
- mid-chunk crash、restart、double apply、rollback/compensation、document/source retentionを再実行する。
- numeric列の-50000/-1.5/+1と、String列またはnumeric parse不能の-1+cmdを分離してtestする。

### Demo

- source hash、mapping version/hash、result hash、phase別counter、amount reconciliation、target duplicate count、restart/rollback outcomeをReview artifactとして渡す。

## W0 technical increment gate

- [ ] execution-contract.mdとrequirements/design/tasks/mapping-spikeが同じcounter・restart・identity・source scopeを記述する。
- [ ] 各5 adapterのpositive/negative/tenant/relation/side effect/compensation synthetic scenarioを揃える。
- [ ] Astra Plan Review PASS後に限りW1へ進む。
- [ ] migration versionはrootが一括割当し、このfeature文書で番号を予約しない。

## 完了対応表（現時点）

| Task | 状態 | 対応文書/証拠 | commit |
|---|---|---|---|
| 0 Discovery/gate | [x] | discovery.md、requirements.md、design.md、tasks.md、mapping-spike.md、test-evidence.md、targeted regression 16件 PASS | 81354d00, b73a1c49, 26cbcbc4, f12203ec |
| F1 Job/mapping foundation | [ ] | 承認後 |
| F2 Parser/canonical/no-write | [ ] | 承認後 |
| A1 Upload/mapping/preview UI | [ ] | 承認後 |
| B1 Apply/restart/idempotency | [ ] | 承認後 |
| B2 Rollback/reconciliation | [ ] | 承認後 |
| M Scale/failure/recovery | [ ] | 承認後 |
