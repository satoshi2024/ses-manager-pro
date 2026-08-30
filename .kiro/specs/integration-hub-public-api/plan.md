# NF-05 Public API 実装計画（scope expansion承認済み・F2 remediation・独立再Review待ち）

## 現在のゲート

NF-05はAPPROVEDであり、F1 Decision DG-05-F1-APPROVAL-20260830-01とscope expansion Decision
DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02（いずれも2026-08-30）、OwnerRef=PROJECT_OWNER、
Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fdをapproval-decision.mdへ固定した。F1は
7e50bf1360ea8d7271acc0667593635451300268でPLAN PASS / IMPLEMENTATION PASS済みであり、再オープンしない。
固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcのscope expansion Plan deltaはPLAN FAIL
（P0=0、P1=4、P2=2）、固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13もPLAN FAIL（P0=0、P1=3、P2=0）だったが、
remediation後の固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPLAN PASS（P0=0、P1=0、P2=0）を受領した。
F2は独立Implementation Review FAIL後のremediation済みで再Review待ちであり、PASS受領後にA1→B1→B2→Mを順次実装する。A2はapproved command=0件のためN/A、
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
| F2 | dedicated security chain | client principal、scope/data scope/command permission、audit、rate/IP | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING。F2 FAIL remediation済み |
| A1 | v1 read APIs / OpenAPI | external DTO、cursor/count/error contract、contract tests | APPROVED_SEQUENCED。F2 Review PASS後 |
| A2 | limited command APIs | permission、idempotency、CAS、audit | NOT_APPLICABLE_UNDER_CURRENT_DECISION。default deny |
| B1 | outbound webhook | subscription、signed event、claim/lease/retry/DLQ | APPROVED_SEQUENCED。A1 Review後、mock/loopbackのみ |
| B2 | inbound webhook / DLQ / admin UI | event uniqueness、replay、safe admin operations | APPROVED_SEQUENCED。B1 Review後 |
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
5. A2をN/Aへ統一し、Plan delta PASS（ca27f455）、F1 PASS維持、F2 remediation再Review待ち、Owner/Base正本値を全traceへ同期する。

6. ExternalApiAuditBoundaryでGETを含む全decisionを監査し、trusted proxy/IP/CIDR確定をnonce commitより
   前に置く。401/403 stable JSON、CSRF/CORS、anonymous無効化、correlation headerを専用chainへ固定する。
7. OpenAPI wire header、raw request-target取得元、path/queryのsplit・empty/valueなし・sort・rebuild、
   Content-Encoding、header/target/body全上限、credentialVersion/keyId、signature decode後32-byteを
   golden vectorへ固定する。
8. public-api=falseでもdeny-only chainを残し、controller/worker/scheduler/transport beanを生成しない。
   profileへfalse/MOCKを明示し、missingはimplicit defaultで補わず起動拒否する。mode enumはMOCK/STUB/
   LOOPBACKへ統一する。

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

F2はIMPLEMENTATION_REMEDIATION_REVIEW_PENDING、A1/B1/B2/MはAPPROVED_SEQUENCEDであり、F2 Implementation Review再PASS後に順次開始する。A2は
NOT_APPLICABLE_UNDER_CURRENT_DECISIONで、command/exportはdefault denyのままとする。production enablement、実顧客
credential、実providerへの外部送信は引き続き禁止する。

F2の独立Implementation ReviewはFAIL後のremediation再Review待ちであり、これを公開可能または全体PASSとは扱わない。

## 完了・引き渡し条件

各Taskは専用branchで検証し、Task単位のcommitを作る。production変更のpushはapproved scopeに含まれるremoteだけに限定し、
force pushは行わない。明示されたReview remediationのdocs-only commit/pushはレビュー証跡固定のために行う。
最終remote Head、commit一覧、plan/spec/tasks、completion matrix、evidence indexを
独立Reviewへ渡す。ReviewのPLAN/IMPLEMENTATION双方がPASSになるまでPRは作成しない。
