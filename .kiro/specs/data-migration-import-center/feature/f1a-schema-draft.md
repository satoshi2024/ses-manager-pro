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
job snapshotとの同一性はcreate-run条件付きINSERTで照合する。CHECK counters NULLまたは>=0、completed时
全部not null；`SEALED/COMPLETED` row追加不可はtransaction/service/CASで強制する。
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

Unique `(tenant_id,source_system_id,entity_type,lookup_digest)`; unique `(tenant_id,id)`。
FK `(tenant_id,source_system_id)`→source。candidate provenance FK `(tenant_id,first_job_id,first_validation_run_id)`
→run同一job。index `(tenant_id,first_job_id)`。collision duplicate INSERT時decrypt existing ciphertext并对
normalized UTF-8 bytes用`MessageDigest.isEqual` constant-time compare；相同identity只幂等复用candidate，不更新
ciphertext/key version；不相同视为HMAC collision/corruption并fail-stop。registry只允许独立候选登记操作，validate
transaction绝不写registry；候选操作自己的失败不影响validation run。

## 3. Source key暗号与namespace构造

先用F2 `ExternalKeyNormalizer.normalize` (NFC、保留大小写、拒绝前后空白/控制字符、UTF-8最大256 bytes)，得到
normalized UTF-8 bytes。采用无歧义length-prefix编码：`formatVersion || len(tenant)||tenantUTF8 || len(sourceCode)||sourceCodeUTF8 || len(entity)||entityASCII || len(key)||keyBytes`。
Lookup HMAC用独立`import.identity.lookup` purpose key，AES-GCM用独立`import.identity.encrypt` key，二者不得复用；
key provider仅按显式version返回用途专用key，不允许dev/production fallback。AAD为
`cipherFormatVersion || tenant || sourceCode || entity || lookupDigest`相同length-prefix格式。
HMAC版本来自source创建时固定的`lookup_key_version`。ciphertext必须包含GCM tag，不存明文；nonce 96-bit CSPRNG，
同一AES key下不得复用。cryptographic adapter每次校验expected tenant/source/entity与DB行快照一致后才解密。
缺key、unknown version、tag/AAD错误均拒绝，禁止尝试其他namespace/key版本或返回明文异常。

实现代码尚不存在的key-provider接口在Astra review后才能命名；本稿不把它描述为现有facade。

## 4. Current admin与exact permission

调用点应在feature service事务入口调用feature-owned resolver，resolver内部读取当前`Authentication`/`SecurityUtils`主体，
再从DB核对用户仍active、未deleted、role=`管理者`、tenant与`AccountingTenantContextHolder`一致及请求法人仍归属tenant。
随后查询当前tenant、用户的active `t_user_permission_group` assignment、enabled/non-deleted `m_permission_group`、
`t_permission_group_action`。必须存在精确`import.metadata.manage` ALLOW；精确DENY、`import.*` DENY或`*` DENY先于ALLOW；
wildcard ALLOW不替代exact grant。不可调用`AuthorizationService.isAllowed`作为充分条件：
`src/main/java/com/ses/service/security/impl/AuthorizationServiceImpl.java:41-56`对一般action的管理员直接true，
并在无组时走legacy fallback（89-90）。现有action mapper的数据模型和deny顺序可作读路径参考，但当前service不满足本条exact规则。

当前用户active查询可参考`SysUserMapper.selectByIdAndTenant`和`SysUser`的status/deleted字段；permission读模型为
`UserPermissionGroupMapper`、`PermissionGroupMapper`、`PermissionGroupActionMapper`及V63/V66 schema。
这些表/共通security无需本增量自动seed权限。新增action inventory、permission group模板或seed均须root另批。
每次source create/update、mapping create、job create/bind、validation start/chunk/seal/publish、candidate register
均重新检查当前管理员与exact permission，而不是复用过期boolean或start时结果。

## 5. Exact document reader：现有调用与必须review的缺口

### 5.1 可复用的真实API

- `DocumentService.download(Long documentId, Integer versionNo)`定义于
  `src/main/java/com/ses/service/DocumentService.java:137-152`，可按document和exact version number打开受控流。
- `DocumentServiceImpl.download`在`src/main/java/com/ses/service/impl/DocumentServiceImpl.java:656`附近选取
  tenant内指定版、拒绝非`CLEAN`，记录document access log后调用storage provider；它没有expected version ID/hash参数。
- `DocumentVersionMapper.findByTenantAndDocumentId`和`findLatestByTenantAndDocumentId`位于
  `src/main/java/com/ses/mapper/DocumentVersionMapper.java:29-37`。它们可读version metadata，但单独查询不能授权下载。
- `FileScopeValidationService.assertCertificationEvidenceDownloadAllowed`位于
  `src/main/java/com/ses/service/security/impl/FileScopeValidationService.java:156-185`，能核exact version/hash，
  但其权限语义硬编码`CERTIFICATION_EVIDENCE`，不能用于`IMPORT_JOB`。
- 通用`assertDownloadAllowed(storedName,expectedVersionId,expectedHash)`位于同文件193-193ff；它只在特定
  文件类型分支实现专属ACL，t_document_version通用分支从281ff开始做CLEAN及documentType权限，当前没有
  IMPORT_JOB exact typed-link分支。`t_document_link`为多态target且当前没有tenant_id，不能靠它证明tenant FK。

### 5.2 F1a拟议的reader调用顺序

feature-owned `ImportSourceDocumentReader`（新设计名称，不是仓库现存类）只接收已授权的内部job snapshot，流程为：
1. 在tenant context内重读`Document`、`DocumentVersion`与`IMPORT_JOB` typed link；要求document未deleted、tenant一致、
   version ID/number/hash均与job snapshot相同、scan=`CLEAN`、未legal hold/retention阻断；source SHA必须等于version SHA。
2. 以document ID/exact version number调用`DocumentService.download`取得受控stream，禁止调用storage provider或用
   `getVersionStorageKey`直接开文件。
3. 共享security边界新增经root批准的专用exact IMPORT_JOB FileScope方法，输入应绑定storedName、jobId、documentId、
   versionId、expectedHash、tenant；该方法重查`IMPORT_JOB` typed link、当前用户DataScope/菜单/tenant、document/version
   当前状态和hash，并fail-closed。调用发生在每个initial read、restart、chunk continuation、seal recount之前。
4. Reader以F2 parser实际消费到EOF；用parser返回的whole-source SHA与job/document version hash比对。metadata查询或调用者
   声称EOF不可作为source读取成功证明。读完关闭stream；在DB事务外做文件I/O。

### 5.3 需root决定的shared change/审计delta

1. 当前FileScope没有`IMPORT_JOB`分支，当前specialized exact方法只能授权CERTIFICATION。实现需要修改
   `FileScopeValidationService`及可能的link/mapper查询；这是共通安全边界的shared change，必须先由root明确批准并由Astra review。
2. `DocumentServiceImpl.download`会写`t_document_access_log`。execution-contract允许的F1a metadata write allowlist
   若被解释为只准写4张import表，则该reader与既有download API冲突。root需明确将合规安全访问日志列为只读业务数据外的
   允许副作用，或批准新增受控read-only audit策略。不得静默绕开DocumentService或删除日志。
3. `t_document_link`无tenant列且多态target无FK。reader必须join当前tenant document/version并逐项核typed link；不能声称
   DB FK已保证typed link tenant一致。若root要求DB强约束，需另批shared document schema变更。

没有上述决定和Astra review前，不写reader生产实现，也不编造mapper/facade已经存在。

## 6. Validation与恢复状态机

### 6.1 启动与lease

验证入口只接受MAPPED job，锁序固定为source system→job→mapping→run/registry key按tenant/source/entity/digest升序。
每次事务锁与变更都用同一顺序。禁止在锁持有期间读文件、调用KMS/provider或等待。
job行`SELECT ... FOR UPDATE`后读取MySQL `CURRENT_TIMESTAMP(6)`；成功takeover/首取时generation+1，owner随机值，
expiry=DB time+30 seconds。same owner/same generation且未过期renew只延长expiry，不增generation；heartbeat目标10秒。
无效/失效owner即使尚无另一个takeover也不能commit。actor、exact permission、source ACTIVE/version、法人、document
精确版/CLEAN/hash与mapping immutable fingerprint在start及后续chunk/seal/publish再核。

### 6.2 BUILDING与250行chunk

创建新的BUILDING run，snapshot job/source/document/mapping/schema/validator/actor和generation。F2调用点为
`SesCsvParser.parse` (`src/main/java/com/ses/importcenter/foundation/SesCsvParser.java:29-80`)、
`ImportValidationPipeline.validate` (`.../ImportValidationPipeline.java:18-38`)、
`CanonicalRowFactory.create` (`.../CanonicalRowFactory.java:9-46`)和纯
`ImportAmountParser.parse` (`.../ImportAmountParser.java:10-33`)。F2 pipeline自身不读DB/HTTP/domain。

原source按物理data record number流式读；`SesCsvParser`在`:61-77`跳过全空records并让后续行号保留gap。
每最多250个非空row写短transaction：重检principal/permission/source/document/lease fencing，按source row number有序
插入只含hash/fingerprint/disposition/finding codes的row metadata。冲突retry只有相同run/row number且所有metadata/hash
精确一致时幂等返回；任何差异拒绝。source blank行数只从实际parser回传记数，不以row number连续性推导。

### 6.3 重启、SEALED、COMPLETED与counter

worker重启从exact source字节开头重读，不跳到客户端offset。读取已提交prefix时逐行比较row number/hash/fingerprint/
disposition/finding commitment，并从normalized external identity重建duplicate set；prefix缺失、额外、变化、key版本不明、
source hash不同或任何FileScope状态失败时终止，不续用旧结果。正在BUILDING的run只有在同一输入、mapping和policy
fingerprint下可续跑；新validation则创建新run，旧completed run只作immutable历史。

source实际EOF后再次核whole SHA。所有非空source records的`(rowNumber,rowHash)`有序集合必须与stored row集合完全相同，
允许空record产生的number gap。取得job lock、再读DB时间，在同一CAS transaction确认lease有效和run仍BUILDING后写实际
physical/empty counts并转SEALED；SEALED不接受row insert/update/delete。

SEALED recount再次经exact-source reader从头到EOF解析，不重做DB/domain validation。每行重新确认row hash与canonical
fingerprint；用已保存的immutable disposition/finding code，不再次查询业务实体。金额/excluded-reason通过F2同一纯
`ImportAmountParser`从本次内存source字段重新计算，按disposition累计接受/拒绝金额及数量；不把raw field、amount或CSV row
落表。summary中保存count/row-set/source/policy commitments。最终短transaction重新取得job lock与DB时间，条件更新必须匹配
owner、generation、expiry、current run、job version、source/mapping snapshot；run由SEALED CAS至COMPLETED，并原子写counter/hash、
job.current_validation_run_id和VALIDATED。返回成功前确认更新行数精确为1。

只有COMPLETED并由job pointer引用的run是当前结果。BUILDING/SEALED/ABORTED不发布。candidate注册是独立metadata操作；
validate/start/chunk/seal/recount/publish都不调用registry写方法。F1a未实现READY或apply。

## 7. Mapper/DTO边界与非目标

六表使用feature专属Mapper及只含上述metadata列的内部DTO。job/run读DTO不得含raw row、外部key明文、ciphertext、nonce、
credential或amount field；registry管理DTO不向普通列表API公开且绝不解密回显identity。没有HTTP/UI写入口。
所有查询至少tenant限定；跨表读必须验证parent一致。写mapper方法只提供明确的insert/CAS/append/select-for-update，
不生成通用updateById/deleteById。DTO的状态/tenant/actor/counter不可由外部boolean或请求字段绕过resolver重建。

本稿没有新增production class/mapper/controller/DDL，也没有声称这些接口已存在。具体实现命名、byte serialization、
MySQL CHECK/索引长度与迁移编号留待schema review决议。

## 8. 文档差分与Astra review请求

- 不改已批准requirements/design/tasks/execution-contract；这些文件描述更宽的F1/NF06目标。本文将本次实现截面限定为
  F1a并明确未实现READY/apply/UI/real-data。
- execution-contract的metadata write allowlist为四张`job/mapping/validation_run/validation_row`；新增
  `source_system`及独立`identity_registry`写入需确认其属于本feature独立metadata动作，validation本身不写registry。
- 当前Document/FileScope API不满足IMPORT_JOB exact source读取；shared security授权和Document access log副作用需root决定。
- 请Astra逐表审column/type/collation/constraint/FK/index、唯一active run/current pointer、canonical mapping容量、
  crypto namespace/AAD/constant-time比较、exact permission查询语义、reader/FileScope缺口、chunk/lease fencing及seal恢复。
  所有审查未通过项解决并获得root批准前，不开始DDL/production实现。

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
