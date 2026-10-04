# NF06 F1a 永続metadata・identity基盤 DDL/reader設計draft

状態: ASTRA_REVIEW待ちの設計draft。実装・DDL・production承認ではない。
対象: F1aの6 metadata表、source読取境界、validation再開/確定、permission境界。

本書は承認済み`requirements.md` / `design.md` / `tasks.md` / `execution-contract.md`を書き換えない。
差分・未解決事項は末尾のdelta欄に記録し、Astraのschema/implementation review後にだけDDLを起こす。
F1aはmetadata切出しであり、READY/APPLY/target binding/UI/real data、F1全体またはNF06全体の完成を含まない。

## 1. 共通DDL規約

- MySQL 8/InnoDB、`utf8mb4`を用いる。tenant FK列は既存親表の文字型・長さ・照合順序に一致させ、FK互換性を保つ。identifier比較にはbinary相当の照合順序を個別に指定する。
  tenantは既存文書・権限表と同じ`VARCHAR(100) NOT NULL`。全PKは`BIGINT NOT NULL AUTO_INCREMENT`。
- tenantと親IDの複合unique/FKでtenant境界をDB制約にもする。API入力のtenant/actorは権威にしない。
- 時刻は`DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`。lease期限判定だけはMySQL primaryの
  `CURRENT_TIMESTAMP(6)`をSQL内で評価し、Java時計を使わない。
- hash/digestはhex文字列でなく固定長`BINARY(32)` SHA-256/HMAC。固定長比較はbyte列で行う。
- JSONはcanonical mapping bytesの正本に使わない。正規canonical serializationのUTF-8 bytesとhashを保存する。
- CHECKはDB engine上でも検証する。状態値はCHECKとservice遷移CASの両方で制限する。soft deleteは設けない。

## 2. 6表のcolumn・key・constraint案

### 2.1 `t_import_source_system`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | tenant複合参照用に`UNIQUE(tenant_id,id)` |
| `tenant_id` | VARCHAR(100) NOT NULL | tenant境界 |
| `source_code` | VARCHAR(64) NOT NULL | ASCII pattern `[A-Za-z][A-Za-z0-9_-]{0,63}`をserviceで検証しlowercase保存 |
| `display_name` | VARCHAR(120) NOT NULL | 表示名。ログ/暗号namespaceには使わない |
| `status` | VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' | CHECK `ACTIVE`,`DISABLED` |
| `version_no` | BIGINT NOT NULL DEFAULT 1 | source metadataのCAS version。状態変更ごとに増加 |
| `lookup_key_version` | VARCHAR(64) NOT NULL | tenant/source/entityのlookup HMAC鍵version。作成後不変 |
| `created_by` | BIGINT NOT NULL | active principal IDを記録 |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 作成時刻 |
| `updated_by` | BIGINT NOT NULL | 状態変更principal。作成時はcreated_by |
| `updated_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | CAS成功時のみ更新 |

PK `id`; unique `(tenant_id,source_code)`、`(tenant_id,id)`。`version_no`は可変CAS値であり、外部snapshotから参照するunique/FKには使わない。CHECK source_code lowercase済みを
DBで完全には表現しないため、保存前validatorとimmutable ID/codeに依存する。FK actorはuser削除/tenant表現差を
踏まえ別レビューし、user FKを付けない場合はactor audit retentionを明記する。

### 2.2 `t_import_mapping`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | 複合FK用uniqueを持つ |
| `tenant_id` | VARCHAR(100) NOT NULL | namespace |
| `source_system_id` | BIGINT NOT NULL | sourceへのtenant複合FK |
| `entity_type` | VARCHAR(24) NOT NULL | CHECK: F2 enum `CUSTOMER`,`ENGINEER`,`PROJECT`,`PROPOSAL`,`CONTRACT` |
| `schema_version` | VARCHAR(32) NOT NULL | F2 schema namespace（現行v1） |
| `mapping_version` | INT NOT NULL | namespace内1以上のimmutable版 |
| `canonical_bytes` | BLOB NOT NULL | canonical header→canonical-field map bytes。F1a direct mappingのみ。DB CHECKとserviceで32,768 bytes以下を検証 |
| `mapping_sha256` | BINARY(32) NOT NULL | canonical bytesのSHA-256。insert時再計算照合 |
| `created_by` | BIGINT NOT NULL | active admin principal |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 作成時刻 |

PK `id`; unique `(tenant_id,id)`、`(tenant_id,source_system_id,entity_type,schema_version,mapping_version)`、
`(tenant_id,id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256)`。
FK `(tenant_id,source_system_id)`→source system。canonical bytesは32,768 bytesを上限とし、超過を切り詰めず拒否する。
UPDATE/DELETEはservice権限でなく
DB repository allowlistでも提供しない。

### 2.3 `t_import_job`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | job identity |
| `tenant_id` | VARCHAR(100) NOT NULL | tenant |
| `source_system_id` | BIGINT NOT NULL | source複合FK |
| `source_version` | BIGINT NOT NULL | job作成時に固定するsource metadata version |
| `entity_type` | VARCHAR(24) NOT NULL | F2 enum CHECK |
| `schema_version` | VARCHAR(32) NOT NULL | schema namespace |
| `source_encoding` | VARCHAR(16) NOT NULL | CHECK `UTF8`,`SHIFT_JIS`。明示選択を永続化 |
| `source_sha256` | BINARY(32) NOT NULL | source全byte hash |
| `legal_entity_id` | VARCHAR(100) NOT NULL | tenant内の権威法人。呼出元値ではなく現在の認可済みcontextで照合 |
| `document_id` | BIGINT NOT NULL | source document identity |
| `document_version_id` | BIGINT NOT NULL | exact immutable document version |
| `document_version_no` | INT NOT NULL | download APIが必要とする正確な版番号 |
| `document_sha256` | BINARY(32) NOT NULL | DocumentVersionのhex hashをbinary化しsource_sha256と一致検証 |
| `mapping_id` | BIGINT NULL | UPLOADEDではNULL。初回bind後immutable |
| `mapping_version` | INT NULL | mappingのexact version |
| `mapping_sha256` | BINARY(32) NULL | exact mapping fingerprint |
| `state` | VARCHAR(16) NOT NULL DEFAULT 'UPLOADED' | CHECK `UPLOADED`,`MAPPED`,`VALIDATING`,`VALIDATED`,`BLOCKED`。BUILDING等はrun側 |
| `version_no` | BIGINT NOT NULL DEFAULT 1 | 全状態遷移CAS |
| `lease_owner` | VARCHAR(128) NULL | server-side random worker identity、PII/user入力不可 |
| `lease_generation` | BIGINT NOT NULL DEFAULT 0 | fencing token、単調増加 |
| `lease_expires_at` | DATETIME(6) NULL | DB時刻基準の30秒lease |
| `active_validation_run_id` | BIGINT NULL | 現在のBUILDING/SEALED run pointer |
| `current_validation_run_id` | BIGINT NULL | 公開中の唯一COMPLETED run |
| `created_by` | BIGINT NOT NULL | actor |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 作成時刻 |
| `updated_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 遷移時刻 |

Unique `(tenant_id,id)`、job identity `(tenant_id,source_system_id,entity_type,schema_version,source_sha256)`。
FK source `(tenant_id,source_system_id)`→source `(tenant_id,id)`、document `(tenant_id,document_id)`、document version `(tenant_id,document_version_id)`。
`source_version`はjob作成時の不変snapshotであり、可変なsource `version_no`へのFKは設けない。各操作でsourceをlockし、statusと現在の`version_no`をsnapshotと比較して不一致をfail closedにする。
V1にはdocumentとversionそれぞれに`(tenant_id,id)` uniqueがある（`V1__create_tables.sql:751,781`）。version IDだけでは
参照先versionが指定documentに属することをFKで保証できないため、registrationおよび各exact source readで
`(tenant_id,document_id,id,version_no)`を同じquery/lockで照合する。既存のtenant unique/indexを重複追加せず、旧V1/V162も変更しない。
FK mapping `(tenant_id,mapping_id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256)`→mappingを同じ列順で結び、
mapping全値の組合せ一致を保証する。mapping列は全てNULLか全てNOT NULLのCHECK。
`active_validation_run_id`と`current_validation_run_id`はそれぞれrun unique `(tenant_id,job_id,id)`へ
`(tenant_id,id,pointer)`で結び、別job runを指せないようにする。jobにはさらにunique
`(tenant_id,id,source_system_id,source_version,entity_type)`を置き、runの同じsnapshot tupleからFK参照できるようにする。
CHECK leaseはowner/expiry両方NULLまたは両方NOT NULL。active pointerはactive slotを持つ唯一のBUILDING/SEALED runを指す。
current pointerは現在有効な公開結果を指す。新validation開始transactionではjobの`current_validation_run_id`をNULLへCASし、旧COMPLETED runの`published_slot`をNULLにして履歴として保持した後、新BUILDING run/active slot/active pointerとjob state=VALIDATINGを同一transactionで作成する。publish時は（開始時に旧slotが解放済みのため）新runのpublished slotを設定し、pointerとjob stateを同一transactionでCASする。
新validationはjob state=MAPPEDまたはVALIDATEDかつactive pointer=NULLの場合だけ開始できる。
旧published runのslot解放を先に行い、MySQLのimmediate unique制約に抵触しない順序でpointer/run/jobを同一transaction内でCASする。
active runがある間の別run開始は拒否する。resume/takeoverは同じactive runを先頭から再読込してprefix照合し、jobの現在lease_generationと
active pointerをlock/CASし、`last_resumed_lease_generation`を更新する。`created_lease_generation`は変更しない。
abortはrunをABORTED、active slot/pointerとjob leaseを解放し、jobをMAPPEDへ同一transactionでCASする。current pointerと旧runのpublished slotは開始時にNULL化済みのためNULLのままとし、旧COMPLETED runは履歴として保持する。
publishはactive pointerとjobの現在generation/owner/expiryを照合し、runをCOMPLETED、active slotをNULL、published_slotを1にし、job pointerを新runへ差し替え、jobをVALIDATEDへ同一transactionでCASする。

### 2.4 `t_import_validation_run`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | run identity |
| `tenant_id` | VARCHAR(100) NOT NULL | tenant |
| `job_id` | BIGINT NOT NULL | 親job |
| `run_no` | INT NOT NULL | job内単調増加ordinal |
| `state` | VARCHAR(16) NOT NULL DEFAULT 'BUILDING' | CHECK `BUILDING`,`SEALED`,`COMPLETED`,`ABORTED` |
| `created_lease_generation` | BIGINT NOT NULL | run作成時のjob generation。不変でありcurrent generationではない |
| `last_resumed_lease_generation` | BIGINT NOT NULL | active run再開/takeover時にjobからsnapshotする |
| `active_slot` | TINYINT NULL DEFAULT 1 | BUILDING/SEALEDは1、terminal stateはNULL。同一jobのactive run一意制約用 |
| `published_slot` | TINYINT NULL | current pointerが参照するCOMPLETED runだけ1。それ以外NULL |
| `actor_user_id` | BIGINT NOT NULL | start時principal audit |
| `source_system_id` | BIGINT NOT NULL | immutable input snapshot |
| `source_version` | BIGINT NOT NULL | jobに固定されたsource metadata version |
| `source_code_snapshot` | VARCHAR(64) NOT NULL | normalized namespace snapshot; secretではない |
| `entity_type` | VARCHAR(24) NOT NULL | enum snapshot |
| `schema_version` | VARCHAR(32) NOT NULL | exact parser schema snapshot |
| `source_encoding` | VARCHAR(16) NOT NULL | CHECK `UTF8`,`SHIFT_JIS`。jobで明示選択したencoding |
| `source_sha256` | BINARY(32) NOT NULL | job input snapshot |
| `document_id` | BIGINT NOT NULL | exact document snapshot |
| `document_version_id` | BIGINT NOT NULL | exact version snapshot |
| `document_version_no` | INT NOT NULL | exact DocumentService version selector |
| `document_sha256` | BINARY(32) NOT NULL | exact source document digest |
| `mapping_id` | BIGINT NOT NULL | immutable mapping snapshot |
| `mapping_version` | INT NOT NULL | immutable mapping version |
| `mapping_sha256` | BINARY(32) NOT NULL | immutable mapping digest |
| `validation_fingerprint` | BINARY(32) NOT NULL | parser/schema/validator policy fingerprint |
| `source_row_count` | BIGINT NULL | parser EOF確定時のみ設定 |
| `physical_row_count` | BIGINT NULL | header後の全physical data record数（empty recordを含む） |
| `empty_row_count` | BIGINT NULL | EOF確定時のみ設定。gapを許す |
| `accepted_row_count` | BIGINT NULL | disposition再計算後に確定 |
| `rejected_row_count` | BIGINT NULL | disposition再計算後に確定 |
| `finding_summary_bytes` | VARBINARY(8192) NULL | validation finding codeごとの件数を持つclosed-code canonical summary |
| `finding_summary_sha256` | BINARY(32) NULL | finding summary bytesのSHA-256 |
| `expected_unit_price_accepted_known_count` / `expected_unit_price_accepted_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | expectedUnitPriceのaccepted行における既知件数と円合計 |
| `expected_unit_price_rejected_known_count` / `expected_unit_price_rejected_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | expectedUnitPriceのrejected行における既知件数と円合計 |
| `expected_unit_price_excluded_count` | BIGINT NULL | expectedUnitPriceのunknown/excluded件数 |
| `unit_price_min_accepted_known_count` / `unit_price_min_accepted_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | unitPriceMinのaccepted既知件数と円合計 |
| `unit_price_min_rejected_known_count` / `unit_price_min_rejected_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | unitPriceMinのrejected既知件数と円合計 |
| `unit_price_min_excluded_count` | BIGINT NULL | unitPriceMinのunknown/excluded件数 |
| `unit_price_max_accepted_known_count` / `unit_price_max_accepted_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | unitPriceMaxのaccepted既知件数と円合計 |
| `unit_price_max_rejected_known_count` / `unit_price_max_rejected_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | unitPriceMaxのrejected既知件数と円合計 |
| `unit_price_max_excluded_count` | BIGINT NULL | unitPriceMaxのunknown/excluded件数 |
| `selling_price_accepted_known_count` / `selling_price_accepted_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | sellingPriceのaccepted既知件数と円合計 |
| `selling_price_rejected_known_count` / `selling_price_rejected_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | sellingPriceのrejected既知件数と円合計 |
| `selling_price_excluded_count` | BIGINT NULL | sellingPriceのunknown/excluded件数 |
| `cost_price_accepted_known_count` / `cost_price_accepted_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | costPriceのaccepted既知件数と円合計 |
| `cost_price_rejected_known_count` / `cost_price_rejected_sum_yen` | BIGINT NULL / DECIMAL(38,0) NULL | costPriceのrejected既知件数と円合計 |
| `cost_price_excluded_count` | BIGINT NULL | costPriceのunknown/excluded件数 |
| `amount_excluded_reason_counts` | VARBINARY(8192) NULL | amount parserの閉じたreason codeごとの件数を持つcanonical summary |
| `amount_excluded_reason_sha256` | BINARY(32) NULL | amount excluded reason summaryのSHA-256 |
| `row_set_sha256` | BINARY(32) NULL | ordered (row_no,row_hash,fingerprint,disposition/findings) commitment |
| `summary_sha256` | BINARY(32) NULL | EOF、hash、row counter、finding/amount集計全体のcommitment |
| `started_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | start時刻 |
| `sealed_at` | DATETIME(6) NULL | SEALED CAS時刻 |
| `completed_at` | DATETIME(6) NULL | publish時刻 |

Unique `(tenant_id,id)`、`(tenant_id,job_id,id)`、`(tenant_id,job_id,run_no)`、
`(tenant_id,job_id,active_slot)`（NULLは複数許容）、`(tenant_id,job_id,published_slot)`（NULLは複数許容）、
`(tenant_id,job_id,id,source_system_id,entity_type)`。
FK `(tenant_id,job_id,source_system_id,source_version,entity_type)`→jobの同じ列順snapshotを張り、別source/entityのrunを拒否する。
source versionはjob/runに不変snapshotとして保持するが、可変なsource `version_no`へのFKは設けない。安定source identityへのFKはjobで保証し、各operationでsource rowをlockしてACTIVE状態と現在versionのsnapshot一致を検証する。不一致はfail closedにする。mapping複合FKも付ける。
document versionはjobのexact tupleと照合する。run作成時はjob lock下で全input snapshot一致を条件にINSERTする。
CHECKでcountersはNULLまたは>=0に制限し、
COMPLETED時には全row counter、finding/amount reason summary、全known count、row/summary hashをNOT NULLとする。
金額sumは対応するknown countが0ならNULL、1以上ならNOT NULLとする。金額fieldが入力全体で欠落/empty/不正の場合も
sumを0円として表示せずNULLにする。countは0を明示でき、sumが既知行だけの合計であることを保つ。
`physical_row_count = source_row_count + empty_row_count`を確認する。ここでsource_row_countはparserが受理した非空data record数、
physical_row_countはheader後の全data record数とし、header自体はどちらにも含めない。
`active_slot=1`はBUILDING/SEALEDのみ、COMPLETED/ABORTEDではNULLとするCHECK。unique active slotが同一jobのactive runを最大1件に制約する。
`published_slot=1`はcurrent pointerの対象となるCOMPLETED runだけに設定し、uniqueにより同一jobのcurrent published runを最大1件に制約する。
job.active pointerとrun.active slotはstart/resume/seal/abort/publishの同一transactionでCAS整合させる。
`SEALED/COMPLETED`後のrow追加禁止はtransaction/service/CASで強制する。input snapshotは不変とする。
runで更新できるのはstate、active_slot、published_slot、last_resumed_lease_generation、seal/completion time、確定counter/hashに限り、
row結果と入力snapshotは更新/削除しない。

### 2.5 `t_import_validation_row`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | row metadata identity |
| `tenant_id` | VARCHAR(100) NOT NULL | tenant |
| `job_id` | BIGINT NOT NULL | fencing/親検証用冗長列 |
| `validation_run_id` | BIGINT NOT NULL | run identity |
| `source_row_number` | BIGINT NOT NULL | physical data-record ordinal。blank row由来のgapを許可 |
| `source_row_sha256` | BINARY(32) NOT NULL | F2 parserのrow hash |
| `canonical_fingerprint` | BINARY(32) NOT NULL | canonical fieldsのprivacy-safe fingerprint |
| `disposition` | VARCHAR(8) NOT NULL | CHECK `ACCEPTED`,`REJECTED` |
| `finding_codes` | VARBINARY(4096) NOT NULL | sort後のcode-only canonical bytes。説明文/原値を保存しない |
| `row_commit_sha256` | BINARY(32) NOT NULL | row metadataのcommitment |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | append時刻 |

Unique `(tenant_id,validation_run_id,source_row_number)`。FK `(tenant_id,job_id,validation_run_id)`→run
`(tenant_id,job_id,id)`でjob/run tenant整合。index `(tenant_id,job_id,validation_run_id,source_row_number)`。
row_number>0 CHECK。PII field values、raw CSV row、external key平文、amount値は保存しない。
duplicate external identityはvalidation sessionで暗号/registry lookupし、row metadataにはdigest/candidateを複製しない。

### 2.6 `t_import_identity_registry`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | candidate identity |
| `tenant_id` | VARCHAR(100) NOT NULL | namespace tenant |
| `source_system_id` | BIGINT NOT NULL | source FK |
| `entity_type` | VARCHAR(24) NOT NULL | entity namespace |
| `lookup_key_version` | VARCHAR(64) NOT NULL | 固定key version snapshot |
| `lookup_digest` | BINARY(32) NOT NULL | purpose-separated HMAC-SHA-256 |
| `cipher_format_version` | SMALLINT NOT NULL DEFAULT 1 | encoding/AAD format version |
| `encryption_key_version` | VARCHAR(64) NOT NULL | AES-GCM key version |
| `nonce` | BINARY(12) NOT NULL | CSPRNG nonce |
| `identity_ciphertext` | VARBINARY(512) NOT NULL | encrypted normalized UTF-8 key + 16-byte tag |
| `status` | VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE' | CHECK `CANDIDATE`; no approved/target state in F1a |
| `first_job_id` | BIGINT NOT NULL | candidate creation provenance |
| `first_validation_run_id` | BIGINT NOT NULL | exact validation provenance |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | first candidate time |
| `updated_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | identical candidate observation only |

Unique `(tenant_id,source_system_id,entity_type,lookup_digest)` と unique `(tenant_id,id)`。
FK `(tenant_id,source_system_id)`→source。candidate provenance FK
`(tenant_id,first_job_id,first_validation_run_id,source_system_id,entity_type)`→run
`(tenant_id,job_id,id,source_system_id,entity_type)`を張り、同じjob/source/entityのrunであることをDBでも保証する。
index `(tenant_id,first_job_id)`。collisionによる重複INSERT時は既存ciphertextを復号し、
normalized UTF-8 bytesを`MessageDigest.isEqual`でconstant-time compareする。同一identityならcandidateを冪等に再利用し、
ciphertext/key versionは更新しない。一致しない場合はHMAC collision/corruptionとしてfail-stopする。
registryへの書込みは独立したcandidate登録操作だけに許可し、validate transactionからは決して書き込まない。
candidate登録操作の失敗はvalidation runに影響させない。

## 3. Source key暗号とnamespaceの構成

最初にF2の`ExternalKeyNormalizer.normalize`を適用する（NFC、大小文字を保持、前後空白/制御文字を拒否、UTF-8で最大256 bytes）。
その結果をnormalized UTF-8 bytesとする。曖昧さのないlength-prefix encodingを用いる：
`formatVersion || len(tenant)||tenantUTF8 || len(sourceCode)||sourceCodeUTF8 || len(entity)||entityASCII || len(key)||keyBytes`。
Lookup HMACには専用の`import.identity.lookup` purpose keyを、AES-GCMには専用の`import.identity.encrypt` keyを用い、
両者を共用しない。key providerは明示されたversionに対応する用途別keyだけを返し、dev/production fallbackを設けない。
AADには`cipherFormatVersion || tenant || sourceCode || entity || lookupDigest`を同じlength-prefix形式で含める。
HMAC versionはsource作成時に固定した`lookup_key_version`から取得する。ciphertextにはGCM tagを含め、平文は保存しない。
nonceは96-bit CSPRNGで生成し、同一AES keyの下で再利用しない。cryptographic adapterは復号前にexpected tenant/source/entityと
DB行snapshotの一致を毎回確認する。key欠落、unknown version、tag/AAD不一致はいずれも拒否し、別namespace/key versionを試さず、
平文を含む例外も返さない。

実装コードにまだ存在しないkey-provider interfaceの命名はAstra review後に行う。本稿では既存facadeとして扱わない。

## 4. 現在の管理者とexact permission

feature serviceの各transaction入口でfeature専用resolverを呼び出す。resolverは現在の`Authentication`/`SecurityUtils`主体を読み取り、
DBでユーザーがactiveかつ未deletedであること、role=`管理者`であること、tenantが`AccountingTenantContextHolder`と一致すること、
要求された法人がtenantに属することを確認する。その後、現在tenant/ユーザーのactiveな
`t_user_permission_group` assignment、enabledかつ未deletedの`m_permission_group`、`t_permission_group_action`を検索する。
`import.metadata.manage`のexact ALLOWを必須とする。exact DENY、`import.*` DENY、`*` DENYはALLOWより優先する。
wildcard ALLOWだけではexact grantの代わりにならない。`AuthorizationService.isAllowed`の結果だけで許可してはならない：
`src/main/java/com/ses/service/security/impl/AuthorizationServiceImpl.java:41-56`では、一般actionに対して管理者を直接trueとし、
また、group未割当時にはlegacy fallbackを通る（89-90）。既存action mapperのデータモデルとDENY優先順は読取実装の参考にできるが、
現行serviceは本項のexact permission要件を満たさない。

現在ユーザーのactive確認には`SysUserMapper.selectByIdAndTenant`と`SysUser`のstatus/deleted項目を参考にできる。permissionの読取元は
`UserPermissionGroupMapper`、`PermissionGroupMapper`、`PermissionGroupActionMapper`及V63/V66 schema。
本incrementでこれらの表や共通securityへ権限を自動seedしない。action inventory、permission group templateまたはseedの追加は、
別途rootの判断を得る。
每次source create/update、mapping create、job create/bind、validation start/chunk/seal/publish、candidate register
各操作で現在の管理者状態とexact permissionを再確認し、期限切れのbooleanやstart時の判定結果を使い回さない。

## 5. Exact document reader：既存APIとreviewが必要な不足点

### 5.1 利用可能な既存API

- `DocumentService.download(Long documentId, Integer versionNo)`は
  `src/main/java/com/ses/service/DocumentService.java:137-152`に定義され、documentとexact version numberを指定して管理されたstreamを開ける。
- `DocumentServiceImpl.download`は`src/main/java/com/ses/service/impl/DocumentServiceImpl.java:656`付近で
  tenant内の指定版を取得し、`CLEAN`以外を拒否する。document access logを記録してからstorage providerを呼び出すが、
  expected version ID/hashを引数に取らない。
- `DocumentVersionMapper.findByTenantAndDocumentId`と`findLatestByTenantAndDocumentId`は
  `src/main/java/com/ses/mapper/DocumentVersionMapper.java:29-37`にある。version metadataの読取には使えるが、
  これらのqueryだけではdownloadを認可できない。
- `FileScopeValidationService.assertCertificationEvidenceDownloadAllowed`は
  `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java:156-185`にあり、exact version/hashを検証するが、
  ただしpermissionの意味は`CERTIFICATION_EVIDENCE`に固定されており、`IMPORT_JOB`には使えない。
- 共通の`assertDownloadAllowed(storedName,expectedVersionId,expectedHash)`は同ファイル193行以降にある。特定の
  file type分岐で個別ACLを適用し、t_document_versionの共通分岐は281行以降でCLEANとdocumentTypeのpermissionを確認するが、
  現時点でIMPORT_JOB exact typed-link分岐はない。`t_document_link`は多態的targetのためjobへのFKは張れないが、
  tenant_idはV162で追加済みであり、tenant付きqueryでlinkを検証できる。

### 5.2 F1aで提案するreaderの呼出順

feature専用の`ImportSourceDocumentReader`（新たな設計名であり、既存repositoryに実在するclassではない）は、認可済みの内部job
snapshotだけを受け取る。exact IMPORT_JOB FileScope判定は必ずdownload/storage openより先に行い、既存の汎用FileScope経路から迂回できないようにする。
0. streamを開く前に現在のactive adminとexact `import.metadata.manage` grantを再確認する。
1. tenant context内で`Document`、`DocumentVersion`、`IMPORT_JOB` typed linkを再読込する。documentが未deletedでtenantが一致し、
   version ID/number/hashがjob snapshotと一致すること、scan=`CLEAN`であること、legal hold/retentionによる制限がないことを確認する。
   source SHAはversion SHAと一致させる。
2. 専用FileScope判定へstoredName、jobId、documentId、versionId、expectedHash、tenantを渡し、`IMPORT_JOB` typed link、
   現在のユーザーのDataScope/メニュー/tenant、document/versionの状態とhashを再確認する。exact許可が得られない限り以後へ進まない。
   汎用`assertDownloadAllowed`はtenant付き`IMPORT_JOB` typed link queryで対象documentにjob linkが存在することを検出し、専用exact authorizationを経ないgeneric downloadを拒否する。documentの`document_type`だけで判定しない（登録時にdocument typeは変更しない）。typed-link検証を省略するdownload経路を作らない。
3. FileScope許可の直後にdocument IDとexact version numberを指定して`DocumentService.download`を呼び、管理されたstreamを取得する。
   `DocumentServiceImpl.download`自身はFileScope認可を行わないため、FileScope判定を先に完了する。storage providerを直接呼んだり、
   `getVersionStorageKey`でファイルを直接開いたりしない。
4. F2 parserが実際にEOFまで読んだ後、parserが返すwhole-source SHAをjob/document version hashと照合する。
   metadata検索結果や呼出元のEOF申告をsource読取完了の証明にしない。読取後はstreamをcloseし、DB transaction外でI/Oを行う。
5. 同じstreamを複数の250-row transactionにまたがって読む場合、各chunk開始時にも現在のprincipal/permission、job fence、source/document状態を
   再確認してから次のchunkを処理する。再起動、SEALED recountによる別stream openでは、毎回手順1-3をやり直す。

### 5.3 shared changeと監査ログの扱い

1. 現行FileScopeに`IMPORT_JOB`分岐はなく、既存のspecialized exact methodが扱うのはCERTIFICATIONだけである。
   実装時には`FileScopeValidationService`と、必要に応じてlink/mapper queryの拡張を検討する。共通security境界への変更範囲はrootが管理し、
   Astra reviewで設計を確認する。
2. `DocumentServiceImpl.download`は`t_document_access_log`へ記録する。execution-contractのF1a metadata write allowlistと
   既存download APIの通常の監査ログは独立したsecurity side effectとして保持する。これは業務domain tableへの書込みではない。
   DocumentServiceを迂回したり、ログを削除したりしない。
3. `t_document_link`は多態的targetへのFKを持たない。tenant_idはV162で追加済みである。readerは現在tenantのdocument/versionとjoinし、
   tenant付きtyped linkを個別に確認する。polymorphic targetの参照先job存在はtransaction/serviceの不変条件で確認する。

rootは専用IMPORT_JOB FileScope判定、汎用経路での迂回拒否、必要なDocumentService/link queryとtests、通常access log維持を許可済み。
設計上はこれらをshared boundaryの変更として扱い、Astra reviewで実装詳細を確認する。既存していないmapper/facadeを実装済みのものとして記述しない。

### 5.4 Source documentとIMPORT_JOB linkの制御された登録transaction

F1aのjob登録は、upload/scanを行わず、既存のCLEAN exact document versionをsourceとして登録する独立操作とする。
1. transaction開始時に現在のactive admin、exact `import.metadata.manage`、既存Documentへのpermissionを確認する。
   既存document permissionは`DocumentServiceImpl.assertDocumentAccessAllowed(Document)`の規則と、既存linkに対する
   現行FileScope download authorizationを確認する。generic FileScopeのexpectedVersionId/hash引数はCERTIFICATION_EVIDENCE以外で
   exact version照合を保証しないため、exact versionは次項のmetadata row lock/queryで別途確認する。
   前者はpublic実装methodだが`DocumentService` interfaceにはないため、production callerは承認済みshared boundary/APIを使い、
   implementation classへ暗黙castしない。source版はtenant内のexact `document_id/version_id/version_no/hash`で選択する。
2. transaction内で決められたlock順（source system→Document/DocumentVersion→job→mapping→run）に従ってsource systemとDocument/DocumentVersion metadataをlockし、tenant、legal entity、document未deleted、versionの所属document、
   `CLEAN`、scan/hash、requested encodingの整合を確認する。`source_sha256`と`document_sha256`はDocumentVersionの保存済みhashから導出し、
   caller申告hashを正本にしない。version `original_name`をparserのfileNameとして用いる。実byteの検証は後続validationのEOF readで行う。
3. job rowをinsertして生成IDを得た後、同じtransaction内で`DocumentService.link(documentId,"IMPORT_JOB",jobId)`を呼ぶ。
   実装`DocumentServiceImpl.link` (`src/main/java/com/ses/service/impl/DocumentServiceImpl.java:375-395`)は
   `@Transactional(rollbackFor = Exception.class)`、tenant付きlinkをidempotentにinsertし、storage I/Oを行わない。
   呼出しは既定のREQUIRED propagationであるためjob writerと同じdatasource/transaction managerへ参加させ、失敗時にjob rowもrollbackする。
   既存実装の`getDocumentOrThrow`はtenant document存在とdigital-invoice境界を確認するが、一般FileScope permissionの代替とはしない。
4. 同一transactionの最後にDocumentVersionのtenant/document membershipと`IMPORT_JOB` linkの存在を再確認してcommitする。
   jobはcommit前に外部可視にならず、validation入口は完全なtyped linkが見え、document/versionが再確認できるjobだけ受け付ける。
   link失敗時にjobを残す非原子的なcleanup flowは使わない。生成されたIDが得られる前にlinkする循環依存はない。

既存schemaの`t_document`と`t_document_version`にはV1で`UNIQUE(tenant_id,id)`がある（`V1__create_tables.sql:751,781`）。
document-version tenant FKにはこの既存uniqueを使い、重複uniqueは追加しない。versionの所属documentは登録/読取時のexact tuple lock/queryで確認する。
`t_document_link`のtenant列はV162で追加され、既存`DocumentService.link`はtenant付き重複確認/insertを行う。
linkはpolymorphic targetなのでjob rowへのFKは張れない。migrationに追加するshared schema constraintが必要になった場合だけ、
rootの指定する新migrationで最小変更を記述し、履歴済みmigrationは変更しない。registration transactionの可視性、同一transaction参加、
link insert失敗時のrollbackをintegration testで証明する。testではDocumentService.linkが同じtransaction managerへ参加すること、
failure injection時にjob row/linkの双方がrollbackされること、commit前jobを別transactionのvalidation readerが取得できないことも確認する。

## 6. Validationと復旧状態機械

### 6.1 開始とlease

validation startはMAPPEDまたはVALIDATED jobを受け付ける。全transactionのlock順序をsource system→Document/DocumentVersion→job→mapping→run→registryと定める。
同一段階内ではtenant/source/entity/digestの昇順で取得し、start/resume/chunk/seal/abort/publish/candidate registrationで順序を崩さない。
file read、KMS/provider呼出し、待機をDB lock保持中に行わない。stream open前に専用FileScopeを確認し、chunk transactionでは次のchunkを処理する直前に
actor/permission/source/document状態を再確認する。
job行を`SELECT ... FOR UPDATE`でlockしてからMySQL `CURRENT_TIMESTAMP(6)`を読む。初回取得/takeover時はcurrent job `lease_generation`を+1し、
ownerにはrandom値を設定する。expiryはDB time+30 secondsとする。同一owner・同一generationかつ未失効のrenewではexpiryだけを延長し、
generationは増やさない。heartbeatは10秒間隔を目安とする。runの`created_lease_generation`は作成時の不変値で、current fencingには使わない。
active runのresume/takeoverではjobの現generationとactive pointerをCASし、runの`last_resumed_lease_generation`を現在値へ更新する。
他workerによるtakeoverがまだなくても、無効または失効したownerのcommitは拒否する。actor、exact permission、source status/version、
法人、documentのexact version/CLEAN/hash、mapping immutable fingerprintをstartと後続のchunk/seal/abort/publishで再確認する。

source versionはjob登録時点で固定し、runにも同じ値をsnapshotする。source FKはtenant/source identityのみを参照し、可変な`version_no`には張らない。sourceのstatus変更（DISABLED、再有効化を含む）はversion_noを増やす。各entry/chunk/seal/abort/publishでsource rowをlockし、current sourceがACTIVEかつversion_noがjob/run snapshotと一致することを確認する。不一致時は古いjob/runの入力を更新して追従させず、操作を拒否する。
active runがあれば次のchunk/resumeでrunをABORTED、active slot/pointerとleaseを解放し、jobをBLOCKEDへCASする。
completed jobもsource version drift後は再validationできずBLOCKEDとする。再有効化しても古いjobは復活しない。
再実行には新しいsource-system identity/job登録を要求し、source ID/code/versionや既存run snapshotを暗黙に差し替えない。

### 6.2 BUILDINGと250行chunk

新しいBUILDING runを作成し、job/source/source version/document/mapping/schema/encoding/validator/actorと`created_lease_generation`を保存する。
F2の`ImportEncoding`は`UTF8`または`SHIFT_JIS`であり自動判定しない。parserのfileNameにはexact DocumentVersionの`original_name`を使う。
F2の呼出点は
`SesCsvParser.parse` (`src/main/java/com/ses/importcenter/foundation/SesCsvParser.java:29-80`)、
`ImportValidationPipeline.validate` (`.../ImportValidationPipeline.java:18-38`)、
`CanonicalRowFactory.create` (`.../CanonicalRowFactory.java:9-46`)とpure
`ImportAmountParser.parse` (`.../ImportAmountParser.java:10-33`)である。F2 pipeline自体はDB/HTTP/domainを参照しない。

元のsourceをphysical data record number順にstream処理する。`SesCsvParser`は61-77行で全空recordをskipし、後続行番号にはgapを残す。
最大250件の非空rowごとに短いtransactionを実行し、principal/permission/source/document/lease fencingを再確認してから、
source row number順にhash/fingerprint/disposition/finding codesだけを含むrow metadataをinsertする。
競合時のretryは、同じrun/row numberで全metadata/hashが完全一致する場合のみ冪等に扱う。それ以外は拒否する。
sourceのblank行数はparserが実際に返した値だけを使い、row numberの連続性から推測しない。

### 6.3 再起動、SEALED、COMPLETEDとcounter

worker再起動時はexact sourceの先頭byteから読み直し、client offsetから再開しない。commit済みprefixについてrow number/hash/fingerprint/
disposition/finding commitmentを行ごとに照合し、normalized external identityからduplicate setを再構築する。
prefixの不足/余分/変更、key version不明、source hash不一致、またはFileScope確認失敗があれば停止し、以前の結果を再利用しない。
BUILDINGまたはSEALED runは、入力、mapping、policy fingerprintが同一の場合に同じrunとして再開する。
新しいvalidation開始transactionはjobの`current_validation_run_id`をNULLへCASし、旧runの`published_slot`をNULLにして旧completed runをimmutableな履歴として残したうえで、新runのactive pointer/slotを設定する。abort後もcurrent pointerはNULLであり、旧runは公開中の結果として扱わない。新runのpublish transaction成功時にのみ新runをCOMPLETED/currentとしてpointerとpublished slotを設定する。

sourceのEOF到達後にwhole SHAを再確認する。全非空source recordの`(rowNumber,rowHash)`順序付き集合はstored row集合と完全一致させる。
空recordに由来するnumber gapは許容する。job lockを取得してDB時刻を読み、同一CAS transactionでlease有効性とrunがBUILDINGであることを
確認してから、実測したphysical/empty countsを保存しSEALEDへ遷移させる。SEALED後のrow insert/update/deleteは拒否する。

SEALED recountではexact-source readerを使って先頭からEOFまで再解析し、DB/domain validationはやり直さない。
各rowのrow hashとcanonical fingerprintを再確認し、保存済みのimmutable disposition/finding codeを使用する。業務entityを再照会しない。
finding reason countはimmutable rowのfinding codeからcanonical `finding_summary_bytes`を再構成する。
amount/excluded reasonはF2の同一pure `ImportAmountParser`を使い、この認可済みexact-source読取中のfieldから再計算し、
fieldごと・ACCEPTED/REJECTED dispositionごとのknown countとJPY合計を算出する。既知値が1件以上のときだけsumを設定し、
既知値0件ならsumはNULL（有効な0円値がある場合はknown count>0、sum=0）。unknown/invalid amountはfieldごとのexcluded countと
`AMOUNT_UNKNOWN_OR_EMPTY`、`AMOUNT_PRECISION_EXCEEDED`、`AMOUNT_INVALID_OR_FRACTIONAL_YEN`、`AMOUNT_NEGATIVE_NOT_ALLOWED`等の
閉じたreason code別countに分け、`amount_excluded_reason_counts`へcanonical bytesで保存する。
raw field、amount、CSV rowはvalidation rowに保存しない。結果counterはrunに明示列/summaryとして永続化するため、
許可されたmetadata readerはsource fileを再度開かずに検証済み件数・金額・reason集計を表示できる。
これらの結果値はcallerから受けず、exact source EOF、source hash、row hash/fingerprint、保存済みdispositionの全照合後にだけrecomputeし、
summary bytesと各hashを同一publish CASで確定する。
最後の短いtransactionでjob lockとDB時刻を再取得し、owner、generation、expiry、current run、job version、source/mapping snapshotの一致を条件にする。
runをSEALEDからCOMPLETEDへCASし、counter/hash、job.current_validation_run_id、VALIDATED状態を原子的に記録する。
成功を返す前に更新件数が正確に1であることを確認する。

COMPLETEDでjob.current pointerとpublished_slotから参照されるrunだけを現在の結果とする。BUILDING/SEALED/ABORTEDは公開しない。
candidate登録は独立したmetadata操作とし、validate/start/chunk/seal/recount/publishからregistry書込methodを呼び出さない。
F1aではREADYまたはapplyを実装しない。

## 7. Mapper/DTO境界と対象外

6表にはfeature専用Mapperと、上記metadata列だけを含む内部DTOを用いる。job/run読取DTOにはraw row、外部key平文、ciphertext、nonce、
credential、validation row単位のamount fieldを含めない。認可済みreaderには確定済み集約amount counterを返せる。registry管理DTOを一般の一覧APIに公開せず、identityを復号して返さない。HTTP/UIの書込入口は設けない。
すべてのqueryをtenantで限定し、表をまたぐ読取では親子関係の一致を確認する。write mapperは明示的なinsert/CAS/append/select-for-updateだけを提供し、
汎用updateById/deleteByIdを生成しない。DTOのstate/tenant/actor/counterを、外部booleanやrequest fieldでresolverを迂回して再構成できないようにする。

本稿ではproduction class/mapper/controller/DDLを追加せず、これらのinterfaceが既存するとも主張しない。
具体的な実装名、byte serialization、MySQL CHECK/index長、migration番号はschema reviewで決定する。

## 8. 文書の差分とAstra review依頼

- 承認済みrequirements/design/tasks/execution-contractは変更しない。これらの文書はF1/NF06のより広い目標を記載している。
  本文では今回の実装範囲をF1aに限定し、READY/apply/UI/real-dataを含まないことを明確にする。
- execution-contractのmetadata write allowlistは`job/mapping/validation_run/validation_row`の4表である。
  `source_system`と独立した`identity_registry`への書込が本featureの独立metadata操作に該当することを確認する。validation自体はregistryへ書き込まない。
- 現行Document/FileScope APIにはIMPORT_JOB exact source読取の専用経路がない。shared security変更はroot承認範囲に従い、
  normal document access logは独立したsecurity side effectとして保持する。
- Astraには、download前のexact FileScope/generic bypass拒否、job＋linkのatomic registrationとlock順、
  active/published run slotとresume/abort/publish CAS、source version drift、source encoding/physical count、
  金額・reasonの永続化とauthorized recount、tenant/document/version/member FK、registry provenanceを再レビューしてもらう。
  F1bのtarget binding、READY/apply、UI、real data、human approvalはこのdraftの対象外として維持する。
  次のimplementation verificationでは、拒否時storage openなし/generic bypass拒否、link rollback、version drift後のresume拒否、
  concurrent active run 1件、abort/publish pointer整合、persisted aggregateをsource再読込なしで表示できることを確認する。

## 参照実装（現状確認。設計名を実在APIと誤認しないこと）

- F2 CSV parser / empty row / EOF SHA: `src/main/java/com/ses/importcenter/foundation/SesCsvParser.java:29-80`
- F2 streaming validation: `src/main/java/com/ses/importcenter/foundation/ImportValidationPipeline.java:18-38`
- F2 canonical DTO mapping: `src/main/java/com/ses/importcenter/foundation/CanonicalRowFactory.java:9-46`
- F2 pure JPY parser: `src/main/java/com/ses/importcenter/foundation/ImportAmountParser.java:10-33`
- Existing document stream API: `src/main/java/com/ses/service/DocumentService.java:137-152`
- Existing version list mapper (metadata read only): `src/main/java/com/ses/mapper/DocumentVersionMapper.java:29-37`
- Existing CLEAN/download/log implementation: `src/main/java/com/ses/service/impl/DocumentServiceImpl.java:656-690`
- Exact certification-only FileScope branch: `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java:156-185`
- Generic FileScope dispatch and version checks: `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java:193-320`
- Admin bypass / legacy fallback: `src/main/java/com/ses/service/security/impl/AuthorizationServiceImpl.java:41-95`
- Current assignment/group/action schema reference: `src/main/resources/db/migration/V63__enterprise_identity_security.sql`,
  `src/main/resources/db/migration/V66__permission_action_deny.sql`。

## 9. Candidate登録のsource membership確認案（Astra review待ち）

Candidate登録のrequestは`jobId`、`validationRunId`、`sourceRowNumber`のみを受け取り、external key、source row hash、fingerprint、`verified`等のcaller申告値を受け取らない。候補操作はvalidation pipelineから呼び出さず、完了済みvalidationへの独立metadata操作とする。

1. DB lock取得前に、現在のprincipal/tenant/明示`import.metadata.manage`権限を確認する。既存の認可済みexact source readerを通して、jobに固定されたdocument ID/version ID/version number/hash、CLEAN状態、tenant、legal entity、FileScopeおよび正確な`IMPORT_JOB` typed linkを検査してからstreamを開く。generic link/FileScope経路やstorage provider直接呼出しを使わない。
2. readerが返すjob snapshotのentity、encoding、source SHAとimmutable mapping ID/version/hashを取り、sourceを先頭からEOFまでF2 parserで再parseする。mappingのcanonical bytes/hashを再検証し、直接header mappingからcanonical `externalKey`列を特定する。要求row numberのrow hashと正規化前keyをstream内で一時取得し、全source SHA/EOFを照合する。source hash不一致、mapping不整合、row欠落、key欠損/不正、reader非対応なら登録を拒否する。ファイルI/O完了後にstreamを閉じ、PIIをログ/例外/DTOへ出さない。
3. 短いmetadata transactionを開始し、source→job→mapping→validation run→validation rowの順にlockする。lock後に権限/current userとsource active/version、job identity・document snapshot・mapping snapshot、runが`COMPLETED`かつjobの`current_validation_run_id`であり`published_slot=1`であることを再検証する。行は当該run/jobに属し`ACCEPTED`で、DB保存済みsource row hashがreaderで再取得したrow hashと一定時間比較一致しなければ拒否する。
4. canonical fingerprintを同一のF2 canonical fingerprint実装で再計算して保存済みfingerprintと比較する。保存済みfingerprintがraw row/hashと結び付くcanonical algorithmを実装が提供できない間、candidate登録は利用不可とする。run fingerprint/source EOF/mappingのいずれかをcaller proofで代替しない。
5. 保存済みsource namespaceのlookup key versionでcandidateを暗号化し、最後にdigest順でregistry keyをlock/insertする。既存digestは復号後の正規化済みidentity byte列を一定時間比較し、完全一致の同一candidateだけを冪等に返す。不一致、unknown key、decrypt/AAD failureはfail closed。transaction内でsource/file/crypto provider I/O、待機、業務domain書込を行わない。

この操作はcandidateを`CANDIDATE`として記録するだけで、target ID/version、parent binding、approval、READY/APPLYを生成しない。必要なfingerprint再計算がF2 DTO/algorithmだけでは安全に完了しない場合は、F1a側にPIIを永続化せずcanonicalization再利用境界を先に追加してから実装する。
