# Requirements — NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

## 0. 状態と適用範囲

本書はDG-05-F1-APPROVAL-20260830-01とDG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02でOwner承認された
NF-05基線である。F1はPLAN/IMPLEMENTATION PASS済み、scope expansion Plan deltaは固定Head
ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPLAN PASS（P0=0、P1=0、P2=0）を受領した。
F2は独立Implementation Review FAIL後のremediation再Review待ち、PASS受領前にA1へ進まない。production implementationの
public endpoint enablement、外部送信は引き続き開始しない。PASS後もproduction enablement、実顧客credential、実provider送信は禁止し、
development/testのmock/stubとloopbackだけを許可する。
T0/0R/0R-D以外のcheckboxを実装完了扱いにしない。

参照:

- 受入後feature backlog: NF-05はread API、限定command、署名service account、
  client scope、data scope、rate、IP、rotation、OpenAPI、cursor、Idempotency-Key、
  Correlation-ID、outbox、署名、replay防止、retry/backoff、DLQ。
- 受入後requirements/design: IH-R1〜IH-R3。
- 受入後traceability: NF-05はAPPROVED、OwnerRef=PROJECT_OWNER、DecisionId=DG-05-F1-APPROVAL-20260830-01および
  scope expansion DecisionId=DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02。
- customer-product-expansion-2026/platform-invariants.md: transaction、scope、secret、
  external I/O、pagination、audit、migration、性能。
- enterprise-identity-security: client secret/tokenをログへ出さず、action permissionとfield maskingを
  service境界で実施する。

### Scope expansionのwave状態

| Wave | 状態 | 開始条件・境界 |
|---|---|---|
| F2 | IMPLEMENTATION_REMEDIATION_REVIEW_PENDING | scope expansion Plan delta PASS済み。F2 FAIL remediation済み、再Review待ち |
| A1 | APPROVED_SEQUENCED | F2 Implementation PASS後。GET-only 11 pathsとexternal DTO allow-list |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION | approved command=0件。command/exportはdefault denyで完了をblockしない |
| B1 | APPROVED_SEQUENCED | A1 Review後。mock/stub/loopbackのみで外部送信を検証 |
| B2 | APPROVED_SEQUENCED | B1 Review後。inbound/DLQ/admin UI、production受信enablementなし |
| M | APPROVED_SEQUENCED | B2 Review後。penetration/recovery/performance/scan/runbookと固定Head |

## IH-R1 Client / credential / security

1. 管理者はAPI clientについて、client ID、owner、tenant/legal entity、許可scope、許可operation、
   client×scope×tenant×route templateの60 req/min、burst 20、日次50,000、CIDR IP制限、発行時刻、
   90日expiry、revoked時刻、状態、credential世代を管理できる。
2. API clientは内部ログインuserやportal userへ偽装しない専用principalとして認識する。
3. credential原文は発行時に一度だけ返せる。AES-256-GCM envelopeのAADへclientId、credentialVersion、
   purposeをbindし、crypto keyは環境注入keyringから取得する。DB、API response、監査、通常ログ、例外、
   metricsへsecret原文を出さない。
4. rotationは旧世代を24時間だけ検証可能にし、revokeは即時、credential有効期間は90日とする。
   旧世代失効時刻、clock skew、expired、revoked、unknown versionはfail-closedで拒否する。
5. IP boundaryはclientごとのCIDR allow-listをdefault denyで適用する。Forwarded/X-Forwarded-Forは
   明示設定されたtrusted proxyからのみ採用し、unknown、malformed、multi-hop不正を拒否する。
   IPv4/IPv6を正規化する。
6. requestごとにclient、認証世代、scope decision、data scope decision、rate decision、
   correlation ID、結果code、target分類を監査可能にする。secret、raw body、PIIは監査しない。
7. action permissionはrole名ではなく、client scopeとcommand permissionで表す。roleは内部管理
   principalに限る。公開APIの認可をsidebar、既存role、URL prefixだけへ依存しない。
8. rate/quotaの永続化キーは client × scope × tenant × route template に固定する。minute/day windowと
   burst stateをこの論理キーへ結び付け、source IPを保存キーへ含めない。routeはraw pathではなく正規化した
   route templateを使い、capacity 20 token、3秒ごとに1 token refillの固定token bucket、条件付きincrement、
   DB uniqueでmulti-nodeの60 req/min、burst 20、日次50,000を原子的に判定する。minute/day/burstの全条件を
    同一transactionで満たした場合だけconsumeし、Retry-Afterは不足条件の最大待機秒を返す。欠落subjectの初期化は
    gap lockを作る先行FOR UPDATEを避けるinsert/upsert-firstとし、insert/既存rowを同じrow lockへ収束させる。
9. 署名検証済みnonceはt_api_nonce_replayへatomic insertし、client_id + nonce_hash uniqueで重複を拒否する。
   raw nonce、署名、body、secret、PIIは保存せず、expires_at <= server_nowをbounded purgeする。TTLは
   max(accepted_at, signed_timestamp) + 5分とし、認証失敗へ安全に収束させる。
10. F1のpersistence serviceは汎用IService/CRUDを公開せず、許可された遷移・状態・snapshot型を受ける
    明示的なcommandだけを公開する。mapperは内部実装に限定し、external DTO snapshotは保存用途別の
     allow-list（safe response / inbound / outbound）を構造的に検証する。payload/canonicalPayloadは用途別allow-listの
     構造化objectだけ、changedFieldNames/skillTagCodeはbounded string arrayだけを許可する。public IDはsafe opaque
     token、日付/date-timeはRFC形式、status/resultCodeはbounded code pattern、availability/signature/processing statusと
     error codeは承認済みenumで検証し、raw body/PIIの文字列埋込みや未型付けscalarを拒否する。
11. Idempotency-Keyのdigest不一致は例外を返す前にIN_PROGRESS rowをCONFLICT、409、安全な固定code、
     90日retentionへCAS遷移させる。同一provider eventのinsert競合でhashが異なる場合も、row lock後に
     RECEIVED/PROCESSINGからCONFLICTへ永続化し、単なるメモリ上の拒否にしない。
IH-R1-12. F2は /external-api/v1/** にだけ一致する@Order(0)の専用security chainとし、既存portal/internal
    chainとのmatcherを排他的にする。処理順序はcorrelationとExternalApiAuditBoundary開始、size/raw
    request-target検証、trusted proxy/source IP解決、HMAC検証、client principal確定後のclient
    CIDR判定（nonce未永続化）、nonce atomic insert commit、client scope/data scope/command permission、
    rate/quota、全decisionの監査確定、
    controllerとする。STATELESS、NullSecurityContextRepository、request cache無効、session/form/basic/
    OIDC/anonymous継承なし、認可済みGET以外anyRequest().denyAll()を固定する。既存ApiAuditFilterが
    GETを監査しない場合でも専用監査境界が全requestと全reject outcomeを記録し、CSRFは外部chainだけ
    disable、CORSは許可originなし・wildcardなし、anonymous無効、401/403はstable JSON entrypointと
    correlation headerで返し、internal form/errorへfall-throughさせないことをtestする。認証前principal、
    allow-list route template、全decisionの監査record境界も固定する。
IH-R1-13. HMAC canonical requestは値の意味だけでなくbyte列を固定する。OpenAPI candidateの
    X-Client-ID / X-Credential-Version / X-Key-ID / X-Timestamp / X-Nonce / X-Client-Signature
    をwire名とし、credentialVersionは1〜2147483647のASCII十進数、keyIdは1〜100 byteとする。
    raw external header blockは16,384 byte/32 field以下、raw request-targetの取得元、
    path/queryの分割、?・&・=、値なしと空値、pair sortと再構築、raw body bytesのSHA-256、
    Content-Encoding、上限、UTF-8 byte length prefix、field順、LF framing、base64url decode後32-byte
    制約、constant-time compareをdesign 3.1の手順とgolden vectorへ固定する。JSON再シリアライズ、
    trim、Forwarded/X-Forwarded-Forの影響を許可しない。
IH-R1-14. 安全なdefault-offは各profile設定へ明示されたpublic-api.enabled=false、
    external-transport.enabled=false、provider.mode=MOCKを意味し、codeのimplicit defaultではない。
    未設定・unknown・型不正・矛盾設定は全profileでstartup validatorがfail-closed拒否する。
    disabled時もdeny-only external chainを生成し、controller、worker、scheduler、transport beanを
    生成せずinternal/portalへfall-throughさせない。productionはoff＋MOCKのみとし、enablement、
    STUB/LOOPBACK、実credential/real provider URLを拒否する。
IH-R1-15. development/testのprovider.mode enumはMOCK、STUB、LOOPBACKの三値だけとする。MOCK/STUBは
    networkなし、LOOPBACKはliteral 127.0.0.1/[::1]とallow-list portだけとし、hostname/DNS、
    redirect、proxy、userinfo、non-loopback、DNS rebinding/multi-addressを拒否する。redirectはNEVER、
    HTTP_PROXY等とJVM proxyは適用せず、config時とconnection直前のpeer検証を行う。

IH-R1-16. raw request-targetは承認済みHTTP connector境界からのみ供給する。Tomcat HTTP/1.1 request-lineまたはHTTP/2 path
    pseudo-headerのservlet正規化前bytesをimmutable copyし、`external.raw-request-target` request attributeへ一度だけ渡す。
    filter、controller、test fixtureがservletのnormalized URI/queryや手動attributeを署名入力へ差し替えてはならない。
    connector属性がない、bytesでない、origin-formを検証できない場合はfail-closed 400とし、enabled実filter-chain E2Eで手動注入なしを検証する。
IH-R1-17. client data scopeとroute data scopeはstrict typed modelへparseし、承認済みdimensionと有限IDだけを許可する。unknown field、
    malformed/empty array、duplicate、wildcard、過大値、route resource dimension不一致は拒否する。clientとrouteの共通dimensionのintersectionを
    tenant/legal entityへbindしたimmutable effective scopeとして認可contextへ渡し、list/detail/countが同じeffective populationを使う。
IH-R1-18. external専用auditは専用schema/serviceでsuccess、error、rejectを一request一recordへ収束させる。correlation ID、認証前後principal、
    credential version/key ID、allow-list route template、authentication/scope/dataScope/command/rateの各decision、status/result codeをbounded metadataとして
    保存し、audit service/schema欠落または永続化失敗は公開responseをfail-closed 500へする。raw target/body、IP、secret、PIIを保存・logしない。
IH-R1-19. CIDR入力はDNSを一切使わないstrict literal parserで扱い、IPv4は4個のcanonical decimal octet、IPv6は厳密なhex group/`::`、
    IPv4-mapped IPv6はIPv4へ正規化する。short/integer/leading-zero/zone ID/hostname/bracket/空白表記は拒否し、source IPとCIDRのfamily不一致も拒否する。

## IH-R2 External contract

1. 公開APIは /external-api/v1/** のversion namespaceと、Owner承認済みOpenAPI candidate契約を持つ。
   scope expansionは開発実装を承認し、Plan deltaはca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPASSした。
   F2実装はFAIL後のremediation再Review待ちで、production endpoint enablementは常に禁止する。candidateは承認前のread-only契約候補である。
2. responseはinventoryのexternal専用DTO allow-listだけを返し、internal entityを直接serializeしない。
   internal DB id、secret、audit metadata、internal path、PII、原価、口座、文書本文、raw provider
   responseは返さない。
3. list/detail/count/exportの認可母集団はclient scope×data scope×field permissionで同一にする。
   count、cursor、error、empty responseから他clientの存在、件数、ID、期間、金額を推測できない。
4. listはopaque cursor、stable sort、limit上限、cursor失効/tenant bindingを持つ。page offsetや
   database idを外部契約へそのまま漏らさない。client指定asOf queryはcandidateでは公開せず、
   request受信時server clockでas-ofを固定してresponse/cursorへbindする。
5. HTTP statusごとのstable error code集合、correlation ID、retryable判定だけを返す。approved mappingは
   400=request/cursor invalid、401=authentication failed、403=forbidden scope、404=resource not found、
   429=rate limited、500=internal errorに限定する。認証失敗・scope外・不存在の
   distinctionがclient dataを推測させる場合は同一の外部エラー契約に収束させる。
6. command surfaceはdefault denyとし、approved command=0件のためA2は
   NOT_APPLICABLE_UNDER_CURRENT_DECISIONである。A2の実装taskやcommand/exportの公開を作成せず、
   current scopeの全体完了をblockしない。将来別Decisionで承認された場合だけIdempotency-Keyと
   canonical request digestの要件を再評価する。
7. external DTO contract testは、許可fieldの集合と禁止fieldの不在を反射/JSON assertionで固定する。
   entity型、internal DTO、Lombokの自動getterに依存して公開形を生成しない。

## IH-R3 Inbound / outbound webhook

1. outboundはevent type、opaque event ID、created time、schema version、allow-list payload、
   correlation ID、subscription識別子、timestamp、signatureを送信する。
2. signatureはHMAC-SHA256とし、clientId、credentialVersion/keyId、timestamp、nonce、method、canonical
   path/query、body SHA-256をcanonical bytesとして固定する。許容時刻差は±5分、nonce replayを拒否する。
3. nonce replay ledgerは署名とtimestamp、client状態、IP検証の後にinsertし、insert commit後にhandlerへ進む。
   同一clientの同一nonceはcredential rotation後も再利用できず、後続業務処理の失敗ではledgerをrollbackしない。
4. receiverはtimestamp tolerance、provider event ID、raw body hash、tenant/client bindingで
   replayとduplicateを拒否する。同一provider event IDの再送は一度だけ処理し、別payload hashは
   conflict/DLQへ収束させる。
5. 業務state変更とoutbox/event row insertは同一DB transaction内で原子的にcommitし、commit後のworkerは
   短いclaim/lease transactionで取得する。外部HTTPはDB transaction外で実行し、結果は別の短いCAS
   transactionでSUCCEEDED、RETRYABLE、FAILED、DLQへ遷移させる。timeout/429/5xxのみ最大8回の
   exponential backoff+jitter、その他4xxはretryなし、失敗後DLQ、manual replayを持つ。
6. NF-05では第二の汎用outboxを作らず、既存notification outboxとAccounting IntegrationJobをreuse・二重書込み
   しない。t_api_deliveryをNF-05専用delivery ledgerとして分離し、event_id + subscription_id + generationを
   uniqueにする。業務stateとt_api_delivery rowだけを同一transactionでcommitする。
   webhook endpointは最大512文字とし、utf8mb4複合unique keyの境界を固定する。
7. retryはnetwork/timeout/429/5xxだけを対象とし、validation/auth/permission等の4xxは無限retryしない。
   retry状態、last safe error code、next attempt、attempt count、provider request IDを保存する。
8. manual replayはadmin action permission、reason、元event snapshot hash、再生世代、scope再検証、
   auditを必須にし、同一eventを無制限に再送しない。
9. inbound handlerの業務適用はclaim処理とtransaction境界を分離し、外部応答を待つ間に内部DB
   transactionを保持しない。

## IH-R4 Data scope / command permission

1. 公開APIの母集団はtenant、legal entity、organization、customer、project、contract等の
   clientに許可されたdata scopeでSQL境界に限定する。取得後Java filterを正本にしない。
2. client A/Bは同一resourceへ同時アクセスしても、scope外のrow、count、cursor、export、
   error detail、webhook payload、DLQ detailを相互に観測できない。
3. command permissionはresource.operation単位で固定し、read scopeがcommand権限を暗黙付与しない。
   read-only clientがcommandを実行できないことを検証する。
4. scopeが空の場合はDBレベル0件または外部契約上同等の存在秘匿応答とする。空集合を全件として
   解釈しない。

## IH-R5 Availability / SLA / operations

1. contract SLAは月間99.9%、p95 500ms（同時接続・payload上限内、計画保守除外）、計画保守7日前通知、
   重大障害60分以内通知、v1廃止予告180日とする。quotaは60 req/min、burst 20、日次50,000である。
2. timeout、429、5xx、provider停止、DB障害、鍵rotation、clock skew、worker crash、DLQ滞留、
   manual replay失敗のrunbookとalertを持つ。
3. Mではsecurity review、負荷、障害訓練、key rotation、secret/PII scan、backup/restore、
   recovery、runbook、remote Head固定を完了する。
IH-R5-4. public-api.enabledとexternal-transport.enabledは各profileへfalseを明示し、provider.modeは
   productionへMOCKを明示する。missing/unknown/malformed/conflicting configはimplicit defaultで補わず
   fail-closed起動拒否とする。productionではoff＋MOCK以外、development/testではMOCK、STUB、LOOPBACK
   以外のmode、real provider URL、proxy、redirectを起動時またはconnection直前に検出した場合は
   controller、worker、scheduler、transportを生成せず送信しない。

## IH-R6 Metrics / payload retention

1. metrics labelはroute template、HTTP method、status class、bounded outcome、client tier等の
   有限集合に限定する。client ID、correlation ID、request/idempotency key、resource ID、user ID、
   IP、provider event IDをlabelへ置かない。label cardinality上限と有限集合をscrape testで検証する。
2. idempotencyはcanonical digest、status、safe response snapshotだけを保持し、raw secret/PII requestを
   保存しない。inbound webhookは署名検証中のみraw bytesをメモリで使い、永続化はraw hash、provider
   event ID、timestamp、allow-listed parsed fields、safe error codeに限定する。outbound webhookは
   承認済みexternal DTO snapshotだけを保存し、internal entity/provider raw bodyを保存しない。
3. retentionはsucceeded 30日、failed/DLQ 90日、audit metadata 1年とする。legal hold中はpurgeを
   停止する。t_api_idempotency_record、t_api_delivery、t_inbound_eventはretention classと期限を保存し、
    terminalのsucceeded/processedは30日、failed/conflict/DLQは90日とする。IN_PROGRESS、PENDING、CLAIMED、RETRYABLE、
   RECEIVED、PROCESSINGはterminal化するまでpurgeしない。
    t_api_retention_holdはrecord kind/id、ACTIVE/RELEASED、generation、versionを一意管理する。hold開始/解除と
    purgeはcheckpoint→target row→holdの共通順序でlockし、active hold、active lease、row versionをCASで再確認する。
    deliveryのleaseはtokenとexpiryが両方NULL、または両方non-NULLでexpiry<=nowの場合だけpurge可能とする。purge jobは
   期限境界、再実行、部分失敗、backup/restore後のpurgeを安全に扱い、restore後はcheckpointを信用せず全対象を
   再評価する。bounded purgeのcursorはretention_expires_at,idのkeysetとし、active holdは解除時、active leaseは
   eligibility集合の末尾到達時にcursorをresetして再評価する。候補取得時に除外された時間依存rowをcursorの先へ
   取り残さず、delete predicateでもlease expiry、retention expiry、terminal state、versionを再確認する。
4. F1のroute templateはOpenAPI candidateの11個の固定template集合だけを受け付け、raw path、query string、
   resource IDをusage bucketの保存キーにしない。credential OVERLAPはnon-nullのoverlap_untilがserver_nowより
   後である場合だけ有効とし、NULL期限をfail-openに扱わない。

## 受入テスト最低条件

- client A/Bのresource、field、operation、data scope matrix。
- revoked、expired、rotation overlap、旧世代失効、IP境界、rate exact boundary、burst、retry-after。
- Idempotency-Key同一payload再送の同結果、別payload拒否、永続化失敗、worker再起動。
- rate/quota保存キーがclient×scope×tenant×route templateだけで、IP/raw pathを含まず、minute/day/burstの
  条件付きincrementとunique競合がmulti-nodeで同じ結果になること。burst capacity 20、3秒ごとの1 token refill、
  refill直前/直後、minute/day境界、clock rollback、片方のquota更新失敗、Retry-Afterを検証する。
- nonce ledgerのatomic unique、rotationを跨ぐ再利用拒否、future timestampを含むTTL、bounded purge再実行。
- dedicated chainの@Order、matcher排他、stateless/session拒否、filter一回実行、unknown method/pathの
  default deny、既存portal/internal chainとのprincipal非共有。trusted proxy/source IP/CIDRがnonce commit
  より前に確定し、ExternalApiAuditBoundaryがGETを含む全decision/reject outcomeを監査すること。
- CSRF/CORS/anonymous拒否、専用401/403 stable JSON entrypoint、全responseのcorrelation header、
  internal form/errorへのfall-throughなし、既存ApiAuditFilterとの二重監査なし。
- canonicalTargetのraw request-target取得元、origin-form、path/queryの?・&・= split、値なし/空値、
  duplicate保持、canonical byte sort/rebuild、percent encoding、raw target/path/query/header/body上限、
  credentialVersion/keyId、Content-Encoding、
  empty body、signature decode後32-byte、golden vector、再シリアライズ差異の拒否。
- profileへ明示したdefault-off、missing/unknown/malformed/conflicting config、production violation、
  real URL/credential、disabled deny-only chain、controller/worker/scheduler/transport bean不存在、
  MOCKの無接続、起動後outboundなし。
- provider.modeのMOCK/STUB（無接続）、LOOPBACKのliteral IPv4/IPv6とport、hostname/DNS/multi-address/rebinding、non-loopback、userinfo、
  redirect、HTTP_PROXY/HTTPS_PROXY/NO_PROXY、JVM proxyの拒否およびconnection直前peer検証。
- service汎用CRUDの非公開、safe response/inbound/outbound snapshotの構造allow-list、idempotency/inboundの
  hash conflict永続化。
- cursor stability、limit上限、count/export/errorからの存在推測防止。
- JSON contract allow-list、entity serialization禁止、secret/PII log scan。
- webhook署名改ざん、timestamp古い/未来、replay、duplicate、provider event conflict、
  claim競合、timeout、429/5xx backoff、4xx no-retry、DLQ、manual replay。
- t_notification_outbox・Accounting IntegrationJobへの二重書込みがなく、t_api_deliveryの分離、unique generation、
  atomic event insert、claim/HTTP/CAS境界が固定されていること。
- 業務stateとoutbox rowの原子commit、provider成功直後crash、stale lease、同時claim、replayで
  副作用が一件へ収束すること。delivery rowはevent/subscription/generationから導出した決定的な
  provider idempotency keyを保持し、workerは再試行でも同じ値をproviderへ渡すこと。
- 外部callがDB transaction内で実行されないことの境界テスト。
- metrics labelの有限集合/cardinality上限、secret/PII log・trace・metrics scan。
- metrics scrapeはroute template/method/status class/outcome/client tierの有限集合だけを確認し、client/correlation/request/idempotency/resource/user/IP/
  provider IDがlabelに存在しないことと、route unknownを固定bucketへ収束することを検証する。
- enabled connector E2Eはrequest attributeの手動注入なしでraw request-targetを取得し、raw target欠落時はfail-closed、通常のrequestは認証境界まで到達することを検証する。
- payload期限境界、succeeded/failed/DLQ purge、legal hold、backup/restore後purge、purge再実行。
- legal hold取得/解除とpurgeの競合、row version/CAS、active lease、restore epoch後の全件再評価、部分失敗の再実行。
- active leaseでkeyset先を通過した後のlease expiry再評価、hold解除時cursor reset、checkpointの同時claim/CAS。
- 実MySQLの複数connectionから実service/mapperを通したusage unique初期化、delivery CAS、hold/purge競合、NULL lease
  fail-closed、inbound duplicate raceが、deadlockなくcanonical stateへ収束すること。
- idempotency/delivery/inboundのcanonical enum全値が一つの遷移表とretention class/起算点へ漏れなく分類され、
  alias状態、terminal逆遷移、期限境界、各CAS失敗を許可しないこと。
