# data-migration-import-center Tasks

## 進行ルール

- NF-06がCANDIDATE、DG-06が未決定の間はTask 0だけを完了可能とする。
- 各完了Taskは、実装と自動testに加えて、下記のDemoを実施し、専用branchへ日本語の小粒commitを作成してpushする。
- 通常checkout C:\work\ses-manager-proは変更しない。
- Reviewへ渡すのはremote head、approved requirements/design/tasks、完了対応表、test/Demo証拠である。実装対話ではPRを作成しない。
- F1以降は、Approved scope、Owner、Base commit、legacy schema fixture、DG-06承認記録が揃うまで開始しない。

## 0. Source schema / discovery / gate（完了）

### Objective

現行schema、既存Engineer CSV、ingestion、domain service、DocumentService、監査、batch operationの再利用境界と、DG-06で承認が必要な事項を固定する。

### Implementation guidance

- 旧schema DDL/fixtureの所在を探索し、現repo/到達可能履歴に無いことを記録した。
- entityごとの候補自然キー、依存順、insert-only/upsert候補、rollback/compensation境界を承認前の候補として記録した。
- parser → canonical DTO → read-only validator → domain serviceの境界を明記した。
- validate中のwrite、mapper直insert、後続参照行のhard delete rollbackを禁止した。
- source/accepted/rejected/applied/updated/skippedと金額reconciliation、source/mapping/result hashの計画を固定した。

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

## F1. Job / mapping / row / checkpoint / id-map DDL（承認後）

### Objective

t_import_job、t_import_mapping、t_import_row_result、t_import_checkpoint、t_import_id_mapと、source document link、version/CAS、unique/idempotency、reconciliation fieldsを追加する。

### Implementation guidance

- 最新migration番号の次を使い、適用済みmigrationを編集しない。
- MySQL fresh、legacy/upgrade、partial/backfill、repairの各schema shapeを検証する。
- V1へ逆戻りして列を追加せず、新テーブルは独立migration、H2は専用schema fixtureとして同期する。
- source file全量やrow payloadをJSON保存せず、document_id、row number、row hash、error/id/result hashを保存する。
- source identity（tenant/entity/schema/source_sha256）のjob間unique/lock、job state、chunk、row、id-mapの一意制約とCAS/lockをMySQLで確認する。
- IMPORT_JOBのDocumentLinkをFileScopeValidationServiceへ登録し、FileReferenceProviderとは別に閲覧/download/error exportのfail-closed認可を確認する。

### Test requirements

- Flyway fresh/legacy/partial、H2 context、MySQL Testcontainersを実行する。
- duplicate job, duplicate row, duplicate source key, same source hash/different mapping、checkpoint raceをtestする。

### Demo

- EXPLAIN/DDL確認で、重複applyを一意制約とCASが拒否することを示す。
- source documentとIMPORT_JOB link、FileReferenceProviderの参照が確認できる。

## F2. Parser / canonical adapter / validate no-write（承認後）

### Objective

CSV/XLSX parserとentityごとのcanonical DTO、read-only resolver、field/cross-row validationを実装する。

### Implementation guidance

- UTF-8 BOM/no BOM、Shift_JIS、CRLF/LF/CR、quoted newline、escaped quoteをstreamで処理する。
- XLSXを対象外にする場合は明示的reject、対象にする場合はcell/formula/sheet/row/巨大cell制限を実装する。
- formula injectionと通常の負数を別分類し、numeric parse結果で扱う。
- Customer/Engineer/Project/Proposal/Contract/assignment/historyのnatural key候補をmapping versionと照合する。
- validateではCustomerService.save、ProjectService.saveProjectWithSkills、ContractService.saveWithBusinessRules、ProposalService.save、EngineerService.save、SkillTagResolver.resolveOrCreate、skill replace、mapper writeを呼ばない。
- Customer createにdomain validationが不足する場合は、generic saveの横に明示facadeを追加し、importからgeneric mapper pathを直接呼ばない。

### Test requirements

- parserの全境界、巨大cell、10,000行、malformed quote、wrong encodingをtestする。
- validate前後の対象table count/max id/updated_at/hash不変、write mock 0回をtestする。
- missing reference、論理削除候補、同名候補複数、customer-project-contract不整合、period/amount不整合をtestする。

### Demo

- sample sourceからcanonical row、candidate、error code、source row hashだけを表示し、DB差分が0であることを示す。

## A1. Upload / mapping / preview / error UI（承認後）

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

- 管理者でsampleをuploadし、preview/error/reconciliationを確認する。ApplyボタンはDG-06未承認時に表示/実行不可であることを確認する。

## B1. Apply / chunk / restart / idempotency（承認後）

### Objective

READYのjobだけを、canonical DTOからdomain service経由でchunk applyし、checkpointから重複なく再開する。

### Implementation guidance

- READY→APPLYINGをCAS/lockで一度だけ許可する。
- chunkのrow result、id-map、checkpoint、job counterを同一transaction境界で確定する。
- mid-chunk crashではtarget result、natural key、row hashを再照合し、完了済みrowをSKIPPEDとして二重作成しない。
- status changeはEngineerService/ProposalService/ContractServiceの状態機械を使い、mapper updateをしない。
- updateはversion/既存行承認、clearable ALWAYS fields、scope、audit、side effectを維持する。

### Test requirements

- double apply、same hash/different mapping拒否、chunk境界crash、restart、target natural key collision、version conflictをtestする。
- 10,000行でtarget重複0、row resultとcheckpointの整合をtestする。
- row transaction commit前/後、checkpoint完成前後、lease期限切れのcrash windowをtestする。

### Demo

- 途中chunkでプロセスを止め、再起動後にCOMPLETEDまで進め、target件数・id-map・row resultの重複0を示す。

## B2. Rollback / compensation / reconciliation（承認後）

### Objective

apply前に宣言したrollback classに従い、後続参照を壊さず補償し、件数・金額・hash証拠を確定する。

### Implementation guidance

- 後続参照がある契約、提案成約、assignment/history、work record、invoice、document linkをhard deleteしない。
- source existing row updateは自動復元せず、before snapshot/version conflict/管理者承認を要求する。
- compensation actionとrollback failureをrow/job/auditへ記録する。
- source document、error export、auditはretention/legal holdを尊重し、rollbackで削除しない。
- source/accepted/rejected/applied/updated/skipped/apply_failed/empty_skipped/amount_excludedの拡張9カウンタ、JPY amount、excluded reasonを再計算し、designの分類式とdifference 0を完了条件にする。

### Test requirements

- downstream referenceありのrollback拒否/補償、compensation failure、partial rollbackをtestする。
- source/mapping/result hash、actor、time、base snapshot、auditの証拠をtestする。

### Demo

- 後続参照ありrowでhard deleteが起きないこと、compensationまたはapproval queueになること、reconciliation差異0を示す。

## M. 10,000行 / 障害 / 復元 / compatibility（承認後）

### Objective

本番相当のsourceで性能・障害復旧・安全性・既存Engineer CSV互換を総合確認する。

### Implementation guidance

- 10,000行をstream/chunkで処理し、heap、DB lock、checkpoint、audit、FileCleanupの参照を計測する。
- source hash、mapping version/hash、result hash、row/amount reconciliationをartifactとして保存する。
- H2 fast suite、MySQL gate、performance gateを分離して実行し、skip 0を維持する。
- Engineer CSVの既存endpoint/golden fixtureを変更せず、新mappingは明示的に別経路で検証する。

### Test requirements

- UTF-8 BOM/Shift_JIS、quoted newline、formula injection、巨大cell、duplicate/missing ref、10,000行を実データfixtureでtestする。
- mid-chunk crash、restart、double apply、rollback/compensation、document/source retentionを再実行する。
- numeric列の-50000/-1.5/+1と、String列またはnumeric parse不能の-1+cmdを分離してtestする。

### Demo

- source hash、mapping version/hash、result hash、拡張9カウンタ、amount reconciliation、target duplicate count、restart/rollback outcomeをReview artifactとして渡す。

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
