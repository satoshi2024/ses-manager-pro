# NF-05 Public API 実装計画（scope expansion承認済み・F2/A1/B1 PASS・B2 Review待ち）

## 現在のゲート

NF-05はAPPROVEDであり、F1 Decision DG-05-F1-APPROVAL-20260830-01とscope expansion Decision
DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02（いずれも2026-08-30）、OwnerRef=PROJECT_OWNER、
Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fdをapproval-decision.mdへ固定した。F1は
7e50bf1360ea8d7271acc0667593635451300268でPLAN PASS / IMPLEMENTATION PASS済みであり、再オープンしない。
固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcのscope expansion Plan deltaはPLAN FAIL
（P0=0、P1=4、P2=2）、固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13もPLAN FAIL（P0=0、P1=3、P2=0）だったが、
remediation後の固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPLAN PASS（P0=0、P1=0、P2=0）を受領した。
F2は独立再Review fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でP0/P1/P2=0/0/0のIMPLEMENTATION PASSを受領した。A1はremediation後、
fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`でP0/P1/P2=0/0/0の独立Implementation PASSを受領した。B1は`971c17d7`の初回実装に対する
独立Review FAIL（fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`、P0=0/P1=4/P2=1）を`30199db8`でremediateした。
再Review fixed Head `29d749bb6db1aad9ca98a9dd253b30d375dbba5c`のP1=2（operator permission、scopeとopaque IDの直接比較）を
  `2684ff8f1303b6d0cc6550882601405d3d78f3b2`でremediateしたが、独立再ReviewでP1-007が残ったため、V134/現行membership mapperによる追加remediationを実施した。
さらにNF05-IMPL-B1-008（初回送信前primary binding未検証）を共通binding validator、enqueue/worker/DuplicateKey検証で
`c2cbfb99133d0df3f8d5eee285be340163747e31`へremediateし、固定Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`で独立再Review PASSを受領した。
B2は初回実装`122c7c3b`後、`cc468e4f`でReview指摘をremediate済み・独立再Implementation Review待ちである。A2はapproved command=0件のためN/A、
production enablementと実顧客/実providerは引き続き禁止する。

## 推奨順序

| 順序 | Task | 成果物 | 開始条件 |
|---|---|---|---|
| 0 | threat / contract / field inventory | approval ledger、filter/secret/outbox/correlation/rate/DTO inventory | 完了。production変更なし |
| 0R | Review remediation | atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review trace | docs-only範囲で完了。実装PASSではない |
| 0R-D | delta Review remediation | count/asOf/status-code/correlation header契約 | docs-only範囲で完了。実装PASSではない |
| 0R-P | R-NF05 Plan finding remediation | rate key、nonce ledger、delivery分離、retention/hold/restore contract | docs-only修正後、R-NF05再Review |
| 0R-P2 | R-NF05 residual Plan remediation | burst algorithm、canonical state/terminal retention mapping | docs-only修正後、R-NF05再Review |
| 0R-P5 | scope expansion Plan delta remediation | dedicated chain、HMAC byte canonical、production fail-closed、mock/loopback destination、A2 N/A、trace | 前回P1-EXP-004/P2はSPEC_ADDRESSED。残存P1を0R-P6で補正 |
| 0R-P6 | scope expansion Plan delta residual remediation | security chain監査/error境界、canonicalTarget完全byte手順、disabled deny-onlyとbean/config契約 | docs-only修正後、R-NF05 Plan delta再Review |
| F1 | client / credential / scope / idempotency DDL | Flyway、H2、migration evidence、rollback、purge | 完了。PLAN/IMPLEMENTATION PASS |
| F2 | dedicated security chain | client principal、scope/data scope/command permission、audit、rate/IP | IMPLEMENTATION_PASS。fixed Head `d022e600`、P0/P1/P2=0/0/0 |
| A1 | v1 read APIs / OpenAPI | external DTO、cursor/count/error contract、customer scope、materialized cursor snapshot、bounded snapshot purge、contract tests | IMPLEMENTATION_PASS。fixed Head `69f857d3`、P0/P1/P2=0/0/0 |
| A2 | limited command APIs | permission、idempotency、CAS、audit | NOT_APPLICABLE_UNDER_CURRENT_DECISION。default deny |
| B1 | outbound webhook | subscription、signed event、claim/lease/retry/DLQ、primary/secondary scope binding、current DB membership、初回送信前identity binding | IMPLEMENTATION_PASS。fixed Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`、P0/P1/P2=0/0/0。mock/stub/loopbackのみ |
| B2 | inbound webhook / DLQ / admin UI | event uniqueness、replay、safe admin operations | IMPLEMENTATION_REVIEW_PENDING。fixed Head `122c7c3bb5653eb788d58040c6defc816ff67013`、H2/MySQL/connector証跡PASS、独立Review待ち |
| M | penetration / recovery / performance | review evidence、load、failure drill、runbook、fixed head | APPROVED_SEQUENCED。B2 Review後 |

## R-NF05 P1 remediationの完了条件

1. rate/quotaの保存キーが client × scope × tenant × route template に固定され、IPやraw pathを含まず、
   burstをcapacity 20、初期20、3秒ごとに1 token refillの固定token bucketとしてminute/day条件と同一transactionで
   原子的に扱うこと。clock rollback、refill境界、Retry-Afterを含めて一意なpredicateを持つこと。
2. t_api_nonce_replayのclient + nonce hash atomic unique、credential rotation跨ぎの再利用拒否、
   max(accepted_at, signed_timestamp) + 5分のTTL、bounded purgeを保存契約とすること。
3. 第二の汎用outboxを作らず、既存notification outbox/Accounting IntegrationJobを変更・二重書込みせず、
   t_api_deliveryをNF-05専用delivery ledgerとして分離し、event/subscription/generation uniqueと
   atomic insert、claim/HTTP/CAS境界を固定すること。
4. idempotency/delivery/inboundのcanonical enumと全遷移を一つに固定し、全terminal stateをretention class/起算点へ
   漏れなく接続すること。retention_class、retention_expires_at、t_api_retention_hold、t_api_purge_checkpointの
    保存モデルと、hold/purgeのlock/CAS競合、active lease、部分失敗、restore epoch後の全件再評価を固定すること。

## Scope expansion Plan delta remediationの完了条件

1. F2専用chainのorder、/external-api/v1/** matcher排他、stateless境界、filter順序、既存chainとの
   principal非共有、unknown method/pathのdefault denyをdesign/requirements/tasks/inventoryへ同期する。
2. HMAC canonical requestのraw body bytes、UTF-8 byte length、RFC3986 path/query、duplicate保持、
   encoded byte順sort、固定field/LF framing、厳密base64urlを固定する。
3. production public-api/external-transport default-off、MOCK default、unknown/malformed/conflicting
   configとreal URL/credentialの起動fail-closedを固定する。
4. MOCK/STUB/LOOPBACKの三値だけを許可し、MOCK/STUBは無接続、LOOPBACKはliteral loopback/port、
   peer/DNS、redirect/proxy、multi-address/rebinding拒否を
   config時とconnection直前の契約として固定する。
5. A2をN/Aへ統一し、Plan delta PASS（ca27f455）、F1 PASS維持、F2 fixed Head `d022e600`のIMPLEMENTATION PASS、A1 fixed Head `69f857d3`の独立Implementation Review PASS、B1初回Review FAILを`30199db8`でremediateし、再Review P1-006/P1-007を`2684ff8f`でremediateした。残存P1-007へcode `5c94367c` → `0618d983`でprimary/secondary bindingと現行DB membership再検証を追加し、NF05-IMPL-B1-008を`c2cbfb99133d0df3f8d5eee285be340163747e31`で追加remediateした。独立再Review pendingを全traceへ同期する。Owner/Base正本値も維持する。

6. ExternalApiAuditBoundaryでGETを含む全decisionを監査し、trusted proxy/IP/CIDR確定をnonce commitより
   前に置く。401/403 stable JSON、CSRF/CORS、anonymous無効化、correlation headerを専用chainへ固定する。
7. OpenAPI wire header、raw request-target取得元、path/queryのsplit・empty/valueなし・sort・rebuild、
   Content-Encoding、header/target/body全上限、credentialVersion/keyId、signature decode後32-byteを
   golden vectorへ固定する。
8. public-api=falseでもdeny-only chainを残し、controller/worker/scheduler/transport beanを生成しない。
   profileへfalse/MOCKを明示し、missingはimplicit defaultで補わず起動拒否する。mode enumはMOCK/STUB/
   LOOPBACKへ統一する。

## A1実装証跡（独立Implementation Review PASS）

`466bd9aa44e8699f58cfe0ac033c9c444a7de71e`でA1 read APIを実装した。初回Review（fixed Head `111f4baa`、P0=0、P1=2、P2=2）は
invoice customer scope、cursor可視母集団、非canonical cursor、DTO/E2E証跡を指摘した。`874fface3bfe90dd27b766ddf9aeff4e00eae591`で次をremediateした。

1. invoiceのlist/detail/countに`invoiceIds × customerIds`を同一SQL predicateで適用し、複数contract invoiceは一意contract時だけpublic IDを返す。
2. 初回listのas-of時点でvisible rowのallow-list DTO snapshotを`t_api_read_snapshot`/`t_api_read_snapshot_item`へ短期保存し、snapshot ID、scope digest、route、client、tenant、legal entity、expiryをcursorへbindする。次ページはlive queryを行わない。
3. cursorの全Base64URL部をpaddingなしのcanonical再encodeと完全一致させ、unused bits variantを拒否する。
4. 4 DTOのfield allow-list、11 GET-only path、entity serialization negative、snapshotのinsert/update/delete/reparentをテストし、enabled connector E2E fixtureへtest keyを明示した。
5. snapshot purgeを公開readから分離し、expiry index順の最大32 header batchをFK cascadeで削除するscheduler/jobを追加した。read pathはpurgeを行わず、
   partial failure rollback、複数batch、再実行、無通信時期限超過をintegration/service testで固定した。
6. 初回as-ofをUTC epoch secondsへ正規化し、fractional clockでもページ間のasOfを一致させた。connector E2E credential fixtureはUTC `LocalDateTime`で投入する。

対象はOpenAPI candidateのGET-only 11 pathsで、
`ExternalApiReadController`、`ExternalApiReadService`、`ExternalApiReadMapper`、external DTO、opaque public ID codec、
暗号化cursor codecを追加した。list/detail/countはF2のimmutable effective scopeから作る同一populationを使用し、DB queryはallow-list列と
scope IDだけを選択する。cursorはclient/tenant/legal entity/route/scope/as-of/expiryへbindし、detailの不存在とscope外は同じ404へ収束する。

remediation focused/integration suiteは23 tests、failure/error/skipなしでPASSした。Windowsのenabled connector browser E2EはUTC fixture修正後もloopback接続確立失敗でHTTP assertion前に停止したため、
独立Reviewではこの環境制約をPASS根拠にしない。固定Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS（P0/P1/P2=0/0/0）を受領した。

## B1実装証跡・初回Review remediation（独立再Review待ち）

`971c17d7`でNF-05専用`t_api_delivery`を再利用するoutbound delivery workerを実装した。業務stateとdelivery rowのatomic insert、
claim/lease transaction、外部HTTP、provider idempotency key・payload hash・generation・lease tokenを用いる結果CASを分離し、
timeout/429/5xxのみ最大8回のbackoff+jitter、その他4xxはFAILED、上限到達はDLQへ収束させる。DLQ replayは新generationとsafe metadata auditへ固定する。
固定framingのHMAC-SHA256署名、credential version/key ID、correlation、provider idempotency key header、MOCK/STUB無接続、LOOPBACK strict literal/peer検証/
redirectなし/proxy・DNSなしを実装した。V132、H2 schema、focused B1 suite 28 tests（failure/error/skipなし）を確認済みで、実顧客credential・実provider送信・production enablementは行わない。

初回B1 Implementation Review（fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`）はFAIL（P0=0、P1=4、P2=1）だった。
`30199db8`で次をremediateした。署名canonical framingへcredential version/idempotency keyを固定してenvelope/ledger一致を検証し、
manual replayでadmin permission、active subscription、current scope・tenant/legal entity・payload membershipを再取得して再認可する。
V133でreplay auditをdelivery payloadから分離し、audit metadataの期限と独立purgeを追加した。workerのclaim/result clockを再取得し、
provider成功後のCAS障害をtransport retryへ変換せずstale lease recoveryへ委ねる。`IntegrationHubWebhookDeliveryWorkerTest`、H2 retention、
MySQL concurrency/retentionを追加し、focused unit/H2/MySQL suiteはfailure/error/skipなしでPASSした。独立再Review受領までB1 PASSとは扱わない。

## B1再Review remediation（fixed Head `29d749bb` → `2684ff8f` → P1-007追加remediation）

再ReviewはP1=2（呼出側operatorRefだけを形式検証、current numeric scopeとopaque public IDを直接比較）だった。
`2684ff8f`でreplay serviceからoperatorRef入力を除去し、認証済み内部`LoginUser`の`ROLE_管理者`と
`integration.webhook.replay` action permissionをservice boundaryで検証する。auditへはprincipalから導出したsafe referenceだけを渡す。
さらにclient/permission/subscriptionのintersection後、許可されたnumeric内部resource IDごとに`ExternalApiPublicIdCodec`でHMAC opaque IDを
再計算し、envelope/payload membershipを照合する。resource dimension不在、reparent、削除、scope縮小、ID不一致はfail-closedとする。
未認証、非admin、permission拒否、operatorRef偽装のnegative testと、numeric scope＋実HMAC public ID、reparent/delete/scope narrowingのtestを追加した。

その後の独立再Review（fixed Head `1c3efc30eefe1f4b7bba2cafa20fa996d7a08a91`）で、複数dimensionを単一`publicResourceId`へ比較していたP1-007が残った。
追加remediationではV134の`t_api_delivery.primary_resource_type/id`を必須bindingとして扱い、`publicResourceId`とpayload primary fieldだけを
primary内部IDから再計算する。project×customer、invoice×customer×contract等のsecondaryは各専用public IDを検証し、
`IntegrationHubWebhookResourceScopeMapper`で現行`deleted_flag`、active parent/customer/project/contract、invoice item/work record relationを
再照会する。scope据置のsoft-delete、同一tenant reparent、invoice itemのcontract付替えはfail-closedとし、legacy bindingなしrowはreplay不可とする。

## B1 NF05-IMPL-B1-008追加remediation

初回送信前primary binding未検証のP1を、`c2cbfb99133d0df3f8d5eee285be340163747e31`で対応した。enqueue保存前はclient bindingを
短い同一DB transactionで取得し、primary type/内部IDから`ExternalApiPublicIdCodec`でopaque IDを再計算する。workerはclaim後・外部HTTP前に
同じvalidatorを実行し、envelope `publicResourceId`とprimary DTO fieldの一致を確認して、不一致rowを送信せずFAILEDへ収束させる。
`DuplicateKeyException`収束もpayload hashだけでなくprimary type/IDを比較する。type/ID不一致、同時enqueueの同一payload・別primary、初回送信前不一致の
negative testと、client DB rowを使うH2/MySQL enqueue証跡を追加した。状態は独立B1再Review待ちであり、B2開始条件を満たしたとは扱わない。

## F2 Implementation Review remediation（固定Head 220ac86f → e47025b5）

独立Implementation Reviewは固定Head `220ac86f531d6e656aeac0ef19225e9596b9385b`でFAIL（P0=0、P1=4、P2=2）だった。
次の実装境界を追加し、再Reviewへ提出する。

1. `ExternalApiRawRequestTargetValve`をTomcat connectorのrequest-line bytes境界へ接続し、`T_BYTES`のimmutable copyだけを
   `external.raw-request-target`へ渡す。servlet normalized URI/query、Forwarded/XFF、proxy rewriteは署名入力に使わない。
   enabled `@SpringBootTest`はrequest attributeを手動設定せず、実filter chainを通す。
2. `ExternalApiDataScope`をstrict JSON object/allow-list dimension/有限IDのtyped modelとし、client bindingとroute scopeの共通dimensionだけを
   intersectionする。tenant/legal entityはprincipal bindingから再確認し、`ExternalApiEffectiveScope`をimmutable request contextへ渡す。
   malformed、empty、duplicate、wildcard、route/resource dimension不一致はfail-closedとする。
3. `ExternalApiAuditBoundary`はcorrelation、認証前後principal、credential version/key ID、route template、authentication/scope/dataScope/
   command/rate decisionを一request一recordで保存する。専用`V130` audit table/serviceがない場合はresponseを500へ収束させ、raw target/body、IP、secret、
   PIIを保存しない。
4. CIDRはDNSを使わないstrict literal parserとし、IPv4 4 octet、IPv6、IPv4-mapped IPv6を固定正規化する。short/integer/leading-zero/
   zone ID/hostnameを拒否する。
5. metricsはroute/method/status class/outcome/client tierの有限集合のみをlabelとし、ID類をlabelへ入れない。namespace root `/external-api/v1`も
   security matcher、filter、audit、correlationの同一境界で扱う。

実装commitは `e47025b5`。対象F2 suiteは29 testsがfailure/error/skipなしでPASSした。enabled connector E2EはWindowsのloopback接続確立失敗でHTTP assertion前に停止したため、独立Review再判定前は
F2 IMPLEMENTATION_PASSへ昇格しない。

## F2 Implementation Review追加remediation（固定Head f57df6d2 → a16cdcba）

固定Head `f57df6d2cd962c4695d41b9a1980cc4b621cb408` の独立再ReviewはFAIL（P0=0、P1=1、P2=1）だった。`a16cdcba`で次を追加した。

1. `tenantIds`/`legalEntityIds`がJSONに存在する場合はprincipalのtenant/legal entityとexact singleton一致を要求し、client/route双方で検証する。
   intersectionでは明示dimensionの空集合を削除せず、`ExternalApiEffectiveScope`が空predicateを拒否する。JSONでdimensionを省略した場合は、
   principalのtenant/legal entity singletonをeffective scopeへ必ず追加する。
2. CIDR parserはIPv4-mapped IPv6 `::ffff:0:0/96`を4-byte IPv4へcollapseし、mapped source×IPv4 CIDR、IPv4 source×mapped CIDRを同一familyで比較する。
   mapped CIDR prefixは96〜128だけを受け付け、IPv4 prefixへ96を減算する。

新規境界を含むfocused suiteは19 tests、failure/error/skipなしでPASSした。独立再Review fixed Head
`d022e60039880dc5d4743f336661819cda7fc3f4`でF2 IMPLEMENTATION PASS（P0/P1/P2=0/0/0）を受領し、A1を開始した。

## F1 Implementation Review remediation（独立Review PASS）

独立Implementation Reviewは初回固定Head `b420911b63177763544edd1e02d663bf528d9dc1` に対して
FAIL（P0=0、P1=7、P2=2）だった。F1 approved scope内で、typed snapshot/service boundary、conflictの
canonical persistence、delivery CAS、purge keyset/hold/lease競合、credential overlap、route templateを
修正し、implementation commit `a184c1f4`へpushした。その後、delivery CASへgeneration predicateを追加する
残存指摘を`d476614e`で修正した。H2 F1対象31 testsとMySQL multi-connection concurrency
3 testsはPASSした。follow-up reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1`に対して
FAIL（P0=0、P1=4、P2=0）となったため、`5a2a0231`でsnapshotのfield-specific構造検証、NULL leaseの
fail-closed、checkpoint→target→holdの共通lock順序、実service/mapperを使うMySQL 5競合証跡を追加した。
H2 F1対象31 testsとMySQL 5 testsはPASSしたが、独立Implementation Reviewの再判定を受けるまでF1をPASS扱いしない。

再Reviewは固定Head `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`でFAIL（P0=0、P1=1、P2=0）となり、lease fail-closed、
lock順序、MySQL競合証跡はクローズされた。残存したnested scalar bypassに対し、`96d6801c`でpublic ID、date/date-time、
status/resultCode、signature/processing status、error codeをfield固有pattern/enumで検証し、nested object深度と配列項目も
boundedにした。H2 F1対象31 testsは再実行でPASSし、固定Head `0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation
ReviewはPASS（P0=0、P1=0、P2=0）となった。F1は実装Review gateを通過したが、MおよびF2以降は未完了である。

follow-up remediationの実装境界:

- ExternalDtoSnapshotのpayload/canonicalPayloadはallow-list済み構造化object、changedFieldNames/skillTagCodeはbounded string array、
  public ID・date/date-time・status/resultCode・signature/processing status・error codeはfield固有pattern/enumとし、raw body/PIIを
  許可scalarへ埋め込めないようにする。
- delivery purgeはlease tokenとlease expiryが両方NULL、または両方non-NULLかつexpiry<=nowの場合だけ候補/削除可能とする。
- retention hold/purgeのrow lock順序はcheckpoint→target→holdへ統一し、checkpoint初期化とquota subject初期化はgap-lockを避けるinsert/upsert-firstとする。
- MySQL 8上で実service/mapperを複数connectionから呼び、usage unique初期化、delivery CAS、hold/purge、malformed lease、inbound duplicateを検証する。

F2はIMPLEMENTATION_PASS、A1はfixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`でIMPLEMENTATION_PASS、B1は初回Review FAILを`30199db8`、再Review P1-006/P1-007を`2684ff8f`で
remediateし、P1-007追加remediation `5c94367c` → `0618d983`、NF05-IMPL-B1-008 `c2cbfb99133d0df3f8d5eee285be340163747e31`を経て独立再Review待ちである。B1再Review PASS後にB2→Mを順次開始する。A2はNOT_APPLICABLE_UNDER_CURRENT_DECISIONで、command/exportは
default denyのままとする。production enablement、実顧客credential、実providerへの外部送信は引き続き禁止する。

F2の独立Implementation Reviewはfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でPASSし、A1の独立Implementation Reviewも
fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`でPASSした。B1の独立再Review完了まではB2を開始せず、公開可能または全体PASSとは扱わない。

## 完了・引き渡し条件

各Taskは専用branchで検証し、Task単位のcommitを作る。production変更のpushはapproved scopeに含まれるremoteだけに限定し、
force pushは行わない。明示されたReview remediationのdocs-only commit/pushはレビュー証跡固定のために行う。
最終remote Head、commit一覧、plan/spec/tasks、completion matrix、evidence indexを
独立Reviewへ渡す。ReviewのPLAN/IMPLEMENTATION双方がPASSになるまでPRは作成しない。

## B2 implementation remediation checkpoint

固定Head `0514e00a1cd27fdedba8d15b5bc87d2fd02d706c` のB2独立ReviewはP0=0、P1=4、P2=1でFAILだった。code commit
`cc468e4f`でprovider/subscription binding、resource current-membership再検証、LoginUser/admin permission、opaque admin reference、
strict Content-Typeを実装・検証した。H2 focused 15 testsとMySQL V136 smoke 2 testsはPASS。Windows connector E2Eはloopback接続
エラーでHTTP到達前に停止したため未検証とし、Linux実connectorを独立再Reviewで確認する。

B2は独立再Review受領までIMPLEMENTATION_REVIEW_PENDING。M開始、production receive enablement、実credential、実provider送信、
PR/mergeは禁止する。F1/F2/A1/B1の既存PASSは再オープンしない。
