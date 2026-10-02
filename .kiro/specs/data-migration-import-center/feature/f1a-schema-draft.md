# NF06 F1a 永続metadata・identity基盤 DDL/reader設計draft

状態: ASTRA_REVIEW待ちの設計draft。実装・DDL・production承認ではない。
対象: F1aの6 metadata表、source読取境界、validation再開/確定、permission境界。

本書は承認済み`requirements.md` / `design.md` / `tasks.md` / `execution-contract.md`を書き換えない。
差分・未解決事項は末尾のdelta欄に記録し、Astraのschema/implementation review後にだけDDLを起こす。
F1aはmetadata切出しであり、READY/APPLY/target binding/UI/real data、F1全体またはNF06全体の完成を含まない。

## 1. 共通DDL規約

- MySQL 8/InnoDB、`utf8mb4`、文字列比較はidentifierについてbinary相当の明示照合順序を用いる。
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
| `version_no` | BIGINT NOT NULL DEFAULT 1 | 状態更新CAS。1以上 |
| `lookup_key_version` | VARCHAR(64) NOT NULL | tenant/source/entityのlookup HMAC鍵version。作成後不変 |
| `created_by` | BIGINT NOT NULL | active principal IDを記録 |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 作成時刻 |
| `updated_by` | BIGINT NOT NULL | 状態変更principal。作成時はcreated_by |
| `updated_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | CAS成功時のみ更新 |

PK `id`; unique `(tenant_id,source_code)` と `(tenant_id,id)`。CHECK source_code lowercase済みを
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
| `canonical_bytes` | VARBINARY(65535) NOT NULL | canonical header→canonical-field map bytes。F1a direct mappingのみ |
| `mapping_sha256` | BINARY(32) NOT NULL | canonical bytesのSHA-256。insert時再計算照合 |
| `created_by` | BIGINT NOT NULL | active admin principal |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 作成時刻 |

PK `id`; unique `(tenant_id,id)`、`(tenant_id,source_system_id,entity_type,schema_version,mapping_version)`、
`(tenant_id,id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256)`。
FK `(tenant_id,source_system_id)`→source system。canonical bytes上限はF2のheader/column限界と
MySQL行サイズを踏まえAstraで最終決定し、超過を切り詰めず拒否する。UPDATE/DELETEはservice権限でなく
DB repository allowlistでも提供しない。

### 2.3 `t_import_job`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | job identity |
| `tenant_id` | VARCHAR(100) NOT NULL | tenant |
| `source_system_id` | BIGINT NOT NULL | source複合FK |
| `entity_type` | VARCHAR(24) NOT NULL | F2 enum CHECK |
| `schema_version` | VARCHAR(32) NOT NULL | schema namespace |
| `source_sha256` | BINARY(32) NOT NULL | source全byte hash |
| `legal_entity_id` | VARCHAR(100) NOT NULL | tenant内の権威法人。呼出元値ではなく現在の認可済みcontextで照合 |
| `document_id` | BIGINT NOT NULL | source document identity |
| `document_version_id` | BIGINT NOT NULL | exact immutable document version |
| `document_version_no` | INT NOT NULL | download APIが必要とする正確な版番号 |
| `document_sha256` | BINARY(32) NOT NULL | DocumentVersionのhex hashをbinary化しsource_sha256と一致検証 |
| `mapping_id` | BIGINT NULL | UPLOADEDではNULL。初回bind後immutable |
| `mapping_version` | INT NULL | mappingのexact version |
| `mapping_sha256` | BINARY(32) NULL | exact mapping fingerprint |
| `state` | VARCHAR(16) NOT NULL DEFAULT 'UPLOADED' | CHECK `UPLOADED`,`MAPPED`,`VALIDATING`,`VALIDATED`。BUILDING等はrun側 |
| `version_no` | BIGINT NOT NULL DEFAULT 1 | 全状態遷移CAS |
| `lease_owner` | VARCHAR(128) NULL | server-side random worker identity、PII/user入力不可 |
| `lease_generation` | BIGINT NOT NULL DEFAULT 0 | fencing token、単調増加 |
| `lease_expires_at` | DATETIME(6) NULL | DB時刻基準の30秒lease |
| `current_validation_run_id` | BIGINT NULL | 公開中の唯一COMPLETED run |
| `created_by` | BIGINT NOT NULL | actor |
| `created_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 作成時刻 |
| `updated_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | 遷移時刻 |

Unique `(tenant_id,id)`、job identity `(tenant_id,source_system_id,entity_type,schema_version,source_sha256)`。
FK source `(tenant_id,source_system_id)`、document `(tenant_id,document_id)`、document version
`(tenant_id,document_version_id)`は現行schemaのcomposite unique有無をDDL前に再照合する。
FK mapping `(tenant_id,mapping_id,source_system_id,entity_type,schema_version,mapping_version,mapping_sha256)`
→mapping同じ列順で結び、mapping全値の組合せ一致を保証する。mapping列は全てNULLか全てNOT NULLのCHECK。
`current_validation_run_id` FKは後述のrun unique `(tenant_id,job_id,id)`へ
`(tenant_id,id,current_validation_run_id)`で結ぶため、他job runのpointerを防ぐ。
CHECK leaseはowner/expiry両方NULLまたは両方NOT NULL。唯一公開runの不変条件はpointerがNULLまたは
このjobに属すCOMPLETED runであり、publish transactionでpointerを更新する。新validation開始時はCASで
state=MAPPED、pointer=NULLにし、旧completed run自体は保持する。

### 2.4 `t_import_validation_run`

| Column | 型/null/default | 制約・用途 |
|---|---|---|
| `id` | BIGINT PK NOT NULL AUTO_INCREMENT | run identity |
| `tenant_id` | VARCHAR(100) NOT NULL | tenant |
| `job_id` | BIGINT NOT NULL | 親job |
| `run_no` | INT NOT NULL | job内単調増加ordinal |
| `state` | VARCHAR(16) NOT NULL DEFAULT 'BUILDING' | CHECK `BUILDING`,`SEALED`,`COMPLETED`,`ABORTED` |
| `lease_generation` | BIGINT NOT NULL | job fencing token snapshot |
| `actor_user_id` | BIGINT NOT NULL | start時principal audit |
| `source_system_id` | BIGINT NOT NULL | immutable input snapshot |
| `source_code_snapshot` | VARCHAR(64) NOT NULL | normalized namespace snapshot; secretではない |
| `entity_type` | VARCHAR(24) NOT NULL | enum snapshot |
| `schema_version` | VARCHAR(32) NOT NULL | exact parser schema snapshot |
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
| `empty_row_count` | BIGINT NULL | EOF確定時のみ設定。gapを許す |
| `accepted_row_count` | BIGINT NULL | disposition再計算後に確定 |
| `rejected_row_count` | BIGINT NULL | disposition再計算後に確定 |
| `row_set_sha256` | BINARY(32) NULL | ordered (row_no,row_hash,fingerprint,disposition/findings) commitment |
| `summary_sha256` | BINARY(32) NULL | EOF/hash/counter summary commitment |
| `started_at` | DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) | start時刻 |
| `sealed_at` | DATETIME(6) NULL | SEALED CAS時刻 |
| `completed_at` | DATETIME(6) NULL | publish時刻 |

Unique `(tenant_id,id)`、`(tenant_id,job_id,id)`、`(tenant_id,job_id,run_no)`。
FK `(tenant_id,job_id)`→job。snapshot FK `(tenant_id,source_system_id)`→source、mapping複合FKを付与し、
job snapshotとの同一性はcreate-run条件付きINSERTで照合する。CHECKでcountersはNULLまたは>=0に制限し、
COMPLETED時には全counterをNOT NULLとする。`SEALED/COMPLETED`後のrow追加禁止はtransaction/service/CASで強制する。
Runはappend-only。state遷移以外のsnapshot/counter UPDATE、DELETEを提供しない。

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
FK `(tenant_id,source_system_id)`→source。candidate provenance FK `(tenant_id,first_job_id,first_validation_run_id)`
→同一jobのrun。index `(tenant_id,first_job_id)`。collisionによる重複INSERT時は既存ciphertextを復号し、
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
- `DocumentServiceImpl.download`在`src/main/java/com/ses/service/impl/DocumentServiceImpl.java:656`附近选取
  tenant内の指定版を取得し、`CLEAN`以外を拒否する。document access logを記録してからstorage providerを呼び出すが、
  expected version ID/hashを引数に取らない。
- `DocumentVersionMapper.findByTenantAndDocumentId`和`findLatestByTenantAndDocumentId`位于
  `src/main/java/com/ses/mapper/DocumentVersionMapper.java:29-37`にある。version metadataの読取には使えるが、
  これらのqueryだけではdownloadを認可できない。
- `FileScopeValidationService.assertCertificationEvidenceDownloadAllowed`位于
  `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java:156-185`，能核exact version/hash，
  ただしpermissionの意味は`CERTIFICATION_EVIDENCE`に固定されており、`IMPORT_JOB`には使えない。
- 共通の`assertDownloadAllowed(storedName,expectedVersionId,expectedHash)`は同ファイル193行以降にある。特定の
  file type分岐で個別ACLを適用し、t_document_versionの共通分岐は281行以降でCLEANとdocumentTypeのpermissionを確認するが、
  現時点でIMPORT_JOB exact typed-link分岐はない。`t_document_link`は多態的targetでtenant_idを持たないため、tenant FKの根拠にはできない。

### 5.2 F1aで提案するreaderの呼出順

feature専用の`ImportSourceDocumentReader`（新たな設計名であり、既存repositoryに実在するclassではない）は、認可済みの内部job
snapshotだけを受け取る。処理順は次のとおり。
1. tenant context内で`Document`、`DocumentVersion`、`IMPORT_JOB` typed linkを再読込する。documentが未deletedでtenantが一致し、
   version ID/number/hashがjob snapshotと一致すること、scan=`CLEAN`であること、legal hold/retentionによる制限がないことを確認する。
   source SHAはversion SHAと一致させる。
2. document IDとexact version numberを指定して`DocumentService.download`を呼び、管理されたstreamを取得する。
   storage providerを直接呼び出したり、`getVersionStorageKey`でファイルを直接開いたりしない。
3. 共通security境界にIMPORT_JOB専用のexact FileScope判定を追加する。引数はstoredName、jobId、documentId、versionId、
   expectedHash、tenantに結び付ける。判定処理で`IMPORT_JOB` typed link、現在のユーザーのDataScope/メニュー/tenant、
   document/versionの現在状態とhashを再確認し、確認できない場合は拒否する。initial read、restart、chunk continuation、
   seal recountの各読取前に実行する。
4. F2 parserが実際にEOFまで読んだ後、parserが返すwhole-source SHAをjob/document version hashと照合する。
   metadata検索結果や呼出元のEOF申告をsource読取完了の証明にしない。読取後はstreamをcloseし、DB transaction外でI/Oを行う。

### 5.3 shared changeと監査ログの扱い

1. 現行FileScopeに`IMPORT_JOB`分岐はなく、既存のspecialized exact methodが扱うのはCERTIFICATIONだけである。
   実装時には`FileScopeValidationService`と、必要に応じてlink/mapper queryの拡張を検討する。共通security境界への変更範囲はrootが管理し、
   Astra reviewで設計を確認する。
2. `DocumentServiceImpl.download`は`t_document_access_log`へ記録する。execution-contractのF1a metadata write allowlistと
   既存download APIの監査ログをどう整理するかはrootが決める。DocumentServiceを迂回したり、ログを削除したりしない。
3. `t_document_link`にはtenant列がなく、多態的targetへのFKもない。readerは現在tenantのdocument/versionとjoinし、typed linkを
   個別に確認する。DB FKがtyped linkのtenant一致を保証すると記載しない。DBでの追加制約が必要な場合は、rootの範囲決定に従う。

readerの具体的な実装は、共通境界の変更範囲と監査ログの扱いをrootが決定し、Astra reviewで設計を確認した後に進める。
既存していないmapper/facadeを実装済みのものとして記述しない。

## 6. Validationと復旧状態機械

### 6.1 開始とlease

validation入口はMAPPED jobだけを受け付ける。lock順序はsource system→job→mapping→run/registry keyとし、
tenant/source/entity/digestの昇順で取得する。すべてのtransactionで同じ順序を守る。lock保持中にfileを読んだり、
KMS/providerを呼び出したり、待機したりしない。
job行を`SELECT ... FOR UPDATE`でlockしてからMySQL `CURRENT_TIMESTAMP(6)`を読む。初回取得/takeover時はgenerationを+1し、
ownerにはrandom値を設定する。expiryはDB time+30 secondsとする。同一owner・同一generationかつ未失効のrenewではexpiryだけを延長し、
generationは増やさない。heartbeatは10秒間隔を目安とする。
他workerによるtakeoverがまだなくても、無効または失効したownerのcommitは拒否する。actor、exact permission、sourceのACTIVE/version、
法人、documentのexact version/CLEAN/hash、mapping immutable fingerprintをstartと後続のchunk/seal/publishで再確認する。

### 6.2 BUILDINGと250行chunk

新しいBUILDING runを作成し、job/source/document/mapping/schema/validator/actorとgenerationのsnapshotを保存する。F2の呼出点は
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
BUILDING runを再開できるのは入力、mapping、policy fingerprintが同一の場合に限る。新しいvalidationでは新runを作成し、
旧completed runはimmutableな履歴として保持する。

sourceのEOF到達後にwhole SHAを再確認する。全非空source recordの`(rowNumber,rowHash)`順序付き集合はstored row集合と完全一致させる。
空recordに由来するnumber gapは許容する。job lockを取得してDB時刻を読み、同一CAS transactionでlease有効性とrunがBUILDINGであることを
確認してから、実測したphysical/empty countsを保存しSEALEDへ遷移させる。SEALED後のrow insert/update/deleteは拒否する。

SEALED recountではexact-source readerを使って先頭からEOFまで再解析し、DB/domain validationはやり直さない。
各rowのrow hashとcanonical fingerprintを再確認し、保存済みのimmutable disposition/finding codeを使用する。業務entityを再照会しない。
金額/excluded-reasonはF2の同一pure `ImportAmountParser`を使い、この読取中のsource fieldから再計算し、disposition別に件数と金額を集計する。
raw field、amount、CSV rowはtableへ保存しない。summaryにはcount/row-set/source/policy commitmentsを保存する。
最後の短いtransactionでjob lockとDB時刻を再取得し、owner、generation、expiry、current run、job version、source/mapping snapshotの一致を条件にする。
runをSEALEDからCOMPLETEDへCASし、counter/hash、job.current_validation_run_id、VALIDATED状態を原子的に記録する。
成功を返す前に更新件数が正確に1であることを確認する。

COMPLETEDでjob pointerから参照されるrunだけを現在の結果とする。BUILDING/SEALED/ABORTEDは公開しない。
candidate登録は独立したmetadata操作とし、validate/start/chunk/seal/recount/publishからregistry書込methodを呼び出さない。
F1aではREADYまたはapplyを実装しない。

## 7. Mapper/DTO境界と対象外

6表にはfeature専用Mapperと、上記metadata列だけを含む内部DTOを用いる。job/run読取DTOにはraw row、外部key平文、ciphertext、nonce、
credential、amount fieldを含めない。registry管理DTOを一般の一覧APIに公開せず、identityを復号して返さない。HTTP/UIの書込入口は設けない。
すべてのqueryをtenantで限定し、表をまたぐ読取では親子関係の一致を確認する。write mapperは明示的なinsert/CAS/append/select-for-updateだけを提供し、
汎用updateById/deleteByIdを生成しない。DTOのstate/tenant/actor/counterを、外部booleanやrequest fieldでresolverを迂回して再構成できないようにする。

本稿ではproduction class/mapper/controller/DDLを追加せず、これらのinterfaceが既存するとも主張しない。
具体的な実装名、byte serialization、MySQL CHECK/index長、migration番号はschema reviewで決定する。

## 8. 文書の差分とAstra review依頼

- 承認済みrequirements/design/tasks/execution-contractは変更しない。これらの文書はF1/NF06のより広い目標を記載している。
  本文では今回の実装範囲をF1aに限定し、READY/apply/UI/real-dataを含まないことを明確にする。
- execution-contractのmetadata write allowlistは`job/mapping/validation_run/validation_row`の4表である。
  `source_system`と独立した`identity_registry`への書込が本featureの独立metadata操作に該当することを確認する。validation自体はregistryへ書き込まない。
- 現行Document/FileScope APIにはIMPORT_JOB exact source読取に不足がある。shared security変更とDocument access logの扱いはrootの決定に従う。
- Astraには、各表のcolumn/type/collation/constraint/FK/index、唯一のactive run/current pointer、canonical mapping容量、
  crypto namespace/AAD/constant-time比較、exact permission queryの意味、reader/FileScopeの不足、chunk/lease fencing、sealからの復旧をレビューしてもらう。
  DDL/production実装は設計レビュー後に進める。

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
