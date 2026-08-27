# data-migration-import-center Discovery / Read-only Mapping Spike

## 0. この文書の位置付け

本文書は data-migration-import-center の開工時Discovery記録である。NF-06は候補段階、DG-06は未決定であり、Approved entity/schema、Owner、Base commit の入力もプレースホルダのままである。

したがって、本コミットで許可される成果物は以下に限定する。

- 現行schema、既存取込、domain service、文書台帳、監査、batch実装の読み取り記録
- サンプルをDBへ書き込まない mapping spike
- requirements / design / tasks の承認前ドラフト

本段階では、本番DDL、Flyway migration、apply処理、upsert、rollback、画面の状態変更を実装しない。

## 1. 開始時の基線証跡

| 項目 | 確認値 |
|---|---|
| 通常checkout | C:\work\ses-manager-pro |
| 専用worktree | C:\work\ses-data-migration-import-center |
| 実装branch | codex/data-migration-import-center |
| Base branch | origin/main（指定値が <BASE_BRANCH> のため暫定） |
| Base commit | 0333b0a4afadef42639bad27e1ae443758f9804f（<BASE_COMMIT> が未解決のため暫定） |
| remote | origin = https://github.com/satoshi2024/ses-manager-pro.git |
| 初期worktree status | clean |
| 通常checkoutの扱い | 既存の未追跡レビュー成果物を含め、変更しない |
| NF-06 | CANDIDATE |
| DG-06 | 未決定 |
| Approved scope / Owner | <APPROVED_SCOPE> / <OWNER>（未解決） |

## 2. 読了した基線と再利用境界

### 2.1 受入後文書

- .kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md
- .kiro/roadmap/2026-08-27-post-acceptance-requirements-design.md
- .kiro/roadmap/2026-08-27-post-acceptance-traceability.md
- .kiro/specs/customer-product-expansion-2026/platform-invariants.md

基線で確認したNF-06の要件は、source file hash、schema/mapping version、preview、DB no-write validate、chunk/checkpoint、restart、id-map、reconciliation、rollback/compensation、監査と、既存CSV/domain service/DocumentServiceの再利用である。DG-06は最初のentityと旧schema、自然キー、重複時のinsert-only/upsert、既存行更新の承認、後続参照がある行のrollback補償を決めるゲートである。

### 2.2 既存取込とCSV

- src/main/java/com/ses/service/csv/EngineerCsvService.java
- src/main/java/com/ses/service/csv/impl/EngineerCsvServiceImpl.java
- src/main/java/com/ses/controller/api/CsvApiController.java
- src/main/java/com/ses/common/util/CsvUtils.java

Engineer CSVは、既存仕様として日本語11列、UTF-8 BOM付き出力、引用フィールド内のカンマ・改行・クォート、行単位の部分成功を持つ。現実装のimportはUTF-8固定、ヘッダー検証なし、全行を新規Engineerとして保存し、EngineerService.saveを呼ぶ。hash、mapping version、重複拒否、Shift_JIS、巨大cell上限、validate/apply分離は持たない。

この互換経路は変更しない。Import CenterからEngineerを扱う場合も、既存 /api/engineers/import-csv の挙動を暗黙に変えず、legacy-engineer mappingとして明示した別経路とする。既存のpartial-success契約を壊す変更は、別の承認と回帰試験なしに行わない。

### 2.3 Domain service

| 対象 | 現行の正本候補 | Import Centerの扱い |
|---|---|---|
| Engineer | EngineerSaveDto、EngineerService.save、updateWithStatusGuard | applyだけがdomain serviceを呼ぶ。status変更時の契約有無、会計履歴、scope invalidationを迂回しない |
| Customer | Customer entity/CustomerSaveDto、CustomerService.updateWithOptimisticLock | 更新はversion必須。新規createは現在 generic saveであり、F2でcreate用facade/validation境界を明文化するまでapplyしない |
| Project | ProjectSaveDto、ProjectService.saveProjectWithSkills/updateProjectWithSkills、ProjectSkillService | 顧客参照、価格/日付range、契約・open proposal制約、skills置換をservice経由にする |
| Proposal | ProposalService.save/changeStatus | 新規status、position整合、重複提案、成約時の契約draft生成をservice経由にする。statusをmapperから直接変更しない |
| Contract | ContractSaveDto、ContractService.saveWithBusinessRules/updateWithBusinessRules/changeStatus | 顧客・案件・要員・position整合、価格、検収理由、sales user、compliance、採番、staffing同期をservice経由にする |
| Engineer skill | SkillTagResolver、EngineerSkillService.replaceSkills | validateではresolveOrCreateを呼ばない。lookup-only検証を用い、apply時だけresolver/serviceを使う |
| Project skill | SkillTagResolver、ProjectSkillService.replaceSkills | 同上。直insertしない |
| Engineer-sales | EngineerSalesService | released_atで履歴を保ち、primary切替/解除規則をservice経由にする。hard deleteしない |
| Document | DocumentService.registerReceived/link/verifyIntegrity | source原本はDocumentServiceとDocumentLinkで管理し、独自storage名や直接ファイル保存を作らない |

### 2.4 既存の文書・監査・batch

- DocumentServiceはquarantine、scan、hash、DB metadata、publish、link、access log、integrity verifyを持つ。source fileを独自テーブルのpathだけで保持しない。
- 既存のFileScopeValidationServiceはresume/engineer photo/proposal/project ingestion/BP ingestion/document archiveを認識するが、IMPORT_JOBは未登録である。F1ではDocumentLinkのcleanup参照と別に、IMPORT_JOBのtenant/job/entity scopeをfail-closedで検証する経路を追加する。
- FileStorageService / FileKindは拡張子、magic、MIME、サイズ、scanを検証する。CSV/XLSXの許可種別はDG-06承認後にFileKind追加または既存種別の適用を決める。
- FileReferenceProviderを実装し、FileCleanupServiceからimport原本やerror exportが孤児扱いされないようにする。
- ApiAuditFilterは更新系 /api/**をt_audit_logへ記録する。import jobの内部行処理は、request単位の監査だけに依存せず、jobのexecutor、correlation id、操作結果を専用証跡へ残す。
- BatchOperationServiceはpreview tokenと上限・scope検証の参考になるが、現状はgeneric updateByIdであり、Import Centerのdomain validation/restart/idempotencyの代替にはしない。

## 3. 現行schemaと依存候補

現行の業務表は、src/main/resources/db/migration/V1__create_tables.sqlおよび後続migrationで管理され、確認できた最新versionはV111である。Import Center専用のt_import_job、t_import_mapping、t_import_row_result、t_import_checkpoint、t_import_id_mapはまだ存在しない。

| 依存順候補 | 表/aggregate | 現行の主な参照 |
|---:|---|---|
| 1 | m_customer | t_project.customer_id、t_contract.customer_id |
| 2 | t_engineer / m_skill_tag | t_proposal.engineer_id、t_contract.engineer_id、skills |
| 3 | t_project / t_project_skill / t_project_position | t_proposal.project_id/position_id、t_contract.project_id/position_id |
| 4 | t_proposal / t_proposal_history | contract draft、proposal status |
| 5 | t_engineer_sales | engineer担当履歴、sales attribution |
| 6 | t_contract | 後続のwork record、invoice、document、staffing等 |
| 7 | history / assignment / document link | 既存業務IDを参照する後続行を持つため、rollback対象から除外または補償 |

これは実装順の承認ではない。最初のentity、旧schemaの実在列、外部IDの有無、既存IDの扱いがDG-06で承認されるまで、順序は候補のままとする。

## 4. DG-06で決める自然キー候補

現行schemaには、全候補entityに共通して使えるlegacy source keyがない。会社名や案件名だけを自然キーとして自動upsertするのは、同名企業・同名案件・期間違い・論理削除行を誤結合するため禁止する。

| entity | 現行DBの一意制約 | read-only spikeの候補 | 承認前の動作 |
|---|---|---|---|
| Customer | company_nameはuniqueでない | source_customer_code。無い場合はcompany_name正規化を候補表示だけに使う | insert-only候補。自動upsertしない |
| Engineer | full_nameはuniqueでない | source_engineer_code。既存CSVはlegacy insert-only | 既存Engineerとの自動結合をしない |
| Project | project_nameはuniqueでない。source_opportunity_idは別機能の一意候補 | source_project_codeをcustomer scope付きで要求 | insert-only候補。名前+日付の推測結合をしない |
| Proposal | active重複制約は業務条件でありlegacy keyではない | source_proposal_code | insert-only候補。重複はrejected/candidate resolution |
| Contract | contract_noはunique、order_line_idも一意 | source_contract_codeをcontract_noへ写像できる場合 | source key不在はinsert-onlyまたは要承認。複合推測upsert禁止 |
| Engineer-sales / assignment | engineer_id、sales_user_id、released_atの履歴モデル | source_assignment_codeまたはengineer source key + sales source key + effective dates | EngineerSalesService経由。releaseをdeleteで表現しない |
| Career / history | 一意制約なし | source_history_code。無ければ完全な業務keyを承認 | insert-only候補。後続参照を持つ行のhard delete禁止 |

上表は承認済み設計ではない。DG-06で値が埋まらない限り、mapping versionをREADYへ進めず、applyを拒否する。

## 5. 旧schema fixtureの状態

現worktreeのsql配下には、旧schema本体として確認できるsql/001〜008は存在しない。存在するのはsql/runbook、sql/seedと、test用の現行統合H2 schemaである。到達可能なgit履歴のsqlパスにも旧001〜008は確認できなかった。

よって、旧schema fixtureは以下の承認入力を受けてから追加する。

1. 旧schema DDLまたは本番snapshotから機密値を除いたDDL
2. 旧schemaのテーブル・列・型・charset/collation・NULL/既定値・制約
3. source key、deleted/active、金額単位、日付timezone、status/enumの実値
4. 代表正常行、同名/重複行、参照欠損行、論理削除行、桁超過行
5. Ownerがfixtureの機密除去と利用許可を承認した証跡

承認後は、旧schema fixtureと現行fresh schema fixtureの両方を用意し、legacy DBを直接本番DBへ接続しない。H2で再現できないMySQL固有の型/FK/unique/lockingは、mysql tagのTestcontainers gateで確認する。

## 6. Read-only mapping spike

### 6.1 目的

source schemaが未提供でも、既存のEngineer CSV形式を読み取り、parser → canonical候補 → validation候補までをDB no-writeで確認できる形式を先に固定する。これは既存Engineer CSV importの置換ではない。

### 6.2 入力の観測

- encoding候補: UTF-8 BOM、UTF-8 no BOM、Shift_JIS
- record: CRLF/LF/CR、quoted comma、quoted newline、escaped quote
- header: 既存Engineer 11列との一致を候補表示
- amount: 希望単価をJPYのBigDecimal候補として解釈
- formula: =、+、-、@、tab、CR始まりを文字列として扱い、負の数値と混同しない
- cell size: 最大長超過をrow error候補にする。全入力を一括Stringへ連結しない

### 6.3 canonical候補

Engineer CSVの1行は、次のようなcanonical候補へ写像する。現段階ではnew Engineerを作らず、値・型・error codeの表示だけを行う。

| source列 | canonical候補 | validation候補 | apply時の正本 |
|---|---|---|---|
| 氏名 | engineer.fullName | required、最大長 | EngineerService |
| 氏名カナ | engineer.fullNameKana | 最大長 | EngineerService |
| イニシャル | engineer.initialName | 最大長 | EngineerService |
| 性別 | engineer.gender | allowlist | EngineerService / entity |
| 雇用形態 | engineer.employmentType | allowlist、required境界 | EngineerService / entity |
| ステータス | engineer.status | allowlist、既存契約整合 | EngineerService.updateWithStatusGuard |
| 希望単価 | engineer.expectedUnitPrice | JPY、非負、桁/scale | EngineerService + accounting history |
| 経験年数 | engineer.experienceYears | integer、非負 | EngineerService |
| 最寄駅 | engineer.nearestStation | 最大長 | EngineerService |
| 日本語レベル | engineer.japaneseLevel | allowlist/最大長 | EngineerService / entity |
| 備考 | engineer.remarks | 最大長・formula文字列 | EngineerService |

### 6.4 Spikeのno-write証拠

Spikeでは、DB接続をread-only datasourceまたはtransaction rollbackで隔離し、次を証明する。

- parserがrowsを生成しても、t_engineer、m_skill_tag、t_engineer_skillその他の件数が変わらない
- 既存サービスのsave、update、resolveOrCreate、replaceSkillsを呼ばない
- source hash、mapping version、canonical row hashを算出できる
- 同じ入力を2回観測しても結果hashが一致する
- malformed quote、encoding不一致、巨大cell、10,000行でメモリ上限/row errorを観測できる

## 7. Reconciliationの基準

金額列を持たないEngineer spikeでも件数reconciliationを記録する。Customer/Project/Contractなど金額列を持つmappingでは、通貨JPY、period、amount source列、roundingをmapping versionに含め、次を保存する。

| 指標 | 定義 |
|---|---|
| source | parserが観測した非空データ行 |
| accepted | canonical validationを通過した行 |
| rejected | structural/field/cross-row validationで拒否した行 |
| applied | applyで新規作成された行 |
| updated | 承認済みupsertで更新された行 |
| skipped | idempotent再実行または明示skipされた行 |
| apply_failed | accepted後に業務処理が失敗し、jobがFAILEDになった行 |
| empty_skipped | header以外の完全空行として除外した行 |
| amount_excluded | 空/不正/formula-likeで金額合計から除外した行 |
| amount source/accepted/applied/updated/skipped/apply_failed/rejected | 各状態に属する既知JPY金額の合計 |
| difference | source = accepted + rejected、accepted = applied + updated + skipped + apply_failedの差。COMPLETEDでは件数・金額・apply_failedとも0 |

amountが空・不正・非金額entityの場合は、合計対象外件数と理由を別に保持し、ゼロ金額と欠損を混同しない。

## 8. 未解決事項

- <APPROVED_SCOPE> の具体的entity、列、初回migration対象
- <OWNER>、承認者、実施予算、開始条件
- <BASE_BRANCH> / <BASE_COMMIT> の確定値
- 旧schema DDL/fixtureとsource fileの提供
- DG-06の自然キー、upsert/insert-only、既存行更新承認
- rollbackの対象、後続参照時のcompensation、再実行時の運用者承認
- XLSXをMVPへ含めるか、CSV onlyで始めるか
- source原本のretention、legal hold、error export retention

これらの未解決事項が残る限り、F1以降のDDL/apply/rollback実装を開始しない。
