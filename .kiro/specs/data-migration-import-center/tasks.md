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
- 実registryではsource-system名をimmutableな管理namespaceとして登録・固定し、同じ外部キーでもsource-system/entity/tenantが違えば混同しない。parent-child importのbindingは子の外部keyだけで推測せず、source parent identityと承認済target parent ID/versionを同じrow transactionで検証する。現F2の合成resolverが扱う裸externalKeyは単一synthetic namespace専用であり、cross-job registryの実identity実装ではない。
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

### 実装候補の定向検証（Review待ち）

- 2026-10-01、`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`を設定して以下を実行した。main/test全source compile後、指定2クラスのみ実行。
  - `..\apache-maven-3.9.6\bin\mvn.cmd test -Dtest=SesCsvParserTest,ImportValidationServiceTest`
  - `BUILD SUCCESS`、15 tests / 0 failures / 0 errors / 0 skipped。
  - `TEST-com.ses.importcenter.foundation.SesCsvParserTest.xml` SHA-256: `892AB8EA453F7730B1157A3644796DC7896672A4629DC4ADE4D5E76BDE0D3617`
  - `TEST-com.ses.importcenter.foundation.ImportValidationServiceTest.xml` SHA-256: `2FC959CF7E94B2EBFA5726E824546CBFB0FDE79908C1B3933FC1FAFF266CA82D`
- 検証対象は合成CSVのみ。parser/DTO/validator単体試験であり、DB差分、domain apply、HTTP/UI、実source適合、業務Demoは未検証である。F2のDemo完了やNF-06全体完了を意味しない。
- 空行/全空多列行は`empty_skipped`へ計上し、header列数不一致扱いしない。quoted newlineは1 source recordとして保持し、末尾改行だけで空recordを生成しない。
- Astra F2 CODE FAIL指摘の修正定向検証（2026-10-02）：JPY parserはASCII十進形式・最大64文字・整数有効18桁をBigDecimal化前に検証し、指数表記/巨大指数を拒否。Contract `startDate`の空/空白/`\N`を必須違反とし、ISO日付を許可。Proposalはpositionの`projectExternalKey`と解決済project identityを比較し、関係欠損/lookup不能はfail-closed。
  - 同じ定向コマンド、`BUILD SUCCESS`、17 tests / 0 failures / 0 errors / 0 skipped。
  - `TEST-com.ses.importcenter.foundation.SesCsvParserTest.xml` SHA-256: `4A5BB47A2269350C6786D5BB5B573A6860054D085C831883CD90436E75F8715C`
  - `TEST-com.ses.importcenter.foundation.ImportValidationServiceTest.xml` SHA-256: `EA4A06949B53B21F8A420D9A73CEB488D9FAAD5C43508A52C7232AF981689B6E`

### Astra foundation review

- Astra はcommit `1e8aa651e6ec4901e1e498aca1a484a7b88fe4c8` のF2 foundation限定CODE PASSを2026-10-02に判定し、3件のfindingを閉じた。PASS対象は純parser/canonical/read-only validation foundationとその定向testである。
- これはF2 Demo、DB resolver実装、write-mock/DB無変更の統合証跡、実source適合、A1以降、NF-06全体完了の承認ではない。
- 追加回帰境界：JPY整数有効18/19桁、入力長64/65文字、`-0`/`-0.0`をsynthetic unit caseへ追加する。F1 registry実装前にsource-system immutable namespaceとparent target identity/version bindingをPlan/unique設計へ反映する。

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
- importではstatus遷移を行わず、各新規rowの初期状態のみ契約値で確定する。Proposal作成は通常作成と同じvalidatePosition/重複検証、Bench→提案中Engineer同期、onProposalSaved相当outcome（aiItemId有りの場合、positionIdは既存承認bindingのみ）、auditを保つtyped create commandを使う。Import pathは現行AI outcome helperの例外握りつぶしを使わず、required outcome failureをrollbackさせるか一意key付きdurable outboxで回復する。proposal row・同期状態・outcome・row result・registry・id-map・audit/outboxをrow transactionで確定し、失敗はrollbackする。outbox配信とrow replayは冪等にし、確定済みresultの副作用を再実行しない。成約status遷移、Contract draft/history/通知は行わない。
- updateはversion/既存行承認、clearable ALWAYS fields、scope、audit、side effectを維持する。

### Test requirements

- double apply、same source identity/different mapping hash拒否、chunk境界crash、restart、target natural key collision、version conflictをtestする。ProposalではBench/非Bench両ケース、初期状態、必要AI outcome/audit、同期/保存例外時の全row rollback、再開時の副作用二重発生なし、成約/Contract派生なしをtestする。
- 10,000行でtarget重複0、row resultとcheckpointの整合をtestする。
- row commit前後、checkpoint/counter projection遅延/破損、同じlease row lock/CASによるtakeover排他、generation更新後の旧worker commit拒否をtestする。同一counter/amount/result hashを中断なしと再開で比較する。

### Demo

- 途中chunkでプロセスを止め、再起動後にCOMPLETEDまで進め、target件数・id-map・row resultの重複0を示す。

## B2. Rollback / compensation / reconciliation（rollback classのReview後）

### Objective

apply前に宣言したrollback classに従い、後続参照を壊さず補償し、件数・金額・hash証拠を確定する。

### Implementation guidance

- 5 adapter全てでhard delete rollbackを禁止する。status遷移、skills/SkillTag、positions、history、EngineerSales/assignmentのcreate/update/replace/releaseはscope外。5 adapterは全rowでhard delete/自動void/取消/restoreを禁止する。未知/未対応補償は初期増分でjob停止・manual remediation要とするだけで、NF-06全体のrollback/運用機能完了とは扱わない。
- source existing row updateは自動復元せず、before snapshot/version conflict/管理者承認を要求する。
- compensation actionとrollback failureをrow/job/auditへ記録する。
- source document、error export、auditはretention/legal holdを尊重し、rollbackで削除しない。
- validate/apply source/validated_accepted_total/apply_pending/rejected/applied/updated/business_skipped/apply_failed/empty_skipped/amount_excluded counters、JPY amount、excluded reasonを再計算し、designの分類式とdifference 0を完了条件にする。

### Test requirements

- downstream referenceありのrollback拒否/補償、compensation failure、partial rollbackをtestする。manual remediation要の表示だけではB2完了にせず、実運用の記録・割当・再開/閉鎖手順を実装/検証する。
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
