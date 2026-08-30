# NF-05 Public API 設計（scope expansion承認済み・Plan delta Review対象）

## 1. 適用範囲と境界

この設計はDG-05-F1-APPROVAL-20260830-01およびDG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02で承認された
NF-05実装入力である。F1は独立PLAN/IMPLEMENTATION PASS済みで、F2以降はscope expansionのPlan delta PASS後に
順次開始する。公開機械クライアントは内部管理chain、
portal chain、既存のanonymous webhook例外へ混ぜず、/external-api/v1/** を専用chainで処理する。
公開clientを内部roleへ変換せず、client principalにtenant、legal entity、client scope、data scope、
command permission、credential version、correlation IDを束ねる。

外部呼出しはDB transaction内に置かない。DB transactionはclaimまたはresult CASまでに限定し、
外部HTTPはtransaction外で実行する。公開APIの業務commandも、長い外部処理はoutbox/jobへ切り離す。

### 1.1 F2専用security filter chain契約

公開APIはBean名 externalApiSecurityFilterChain、@Order(0)、securityMatcher
/external-api/v1/** の専用chainで処理する。既存portal chainは@Order(1)で
/portal/** と /api/portal/**だけを担当し、内部管理chainはその残りを担当する。各chainの
matcherは相互排他的であり、/api/webhooks/**、/login、portal path、内部/api pathを
external chainへ含めない。external chainへServlet FilterRegistrationBeanで同じfilterを
自動登録せず、HMAC filterとExternalApiAuditBoundaryの実行回数が各request一回であることを検証する。

external chainはSessionCreationPolicy.STATELESS、NullSecurityContextRepository、request
cache無効を固定し、form login、basic、OIDC、anonymous session継承、JSESSIONIDによる認証を
受け付けない。client principalは内部roleまたはportal userへ変換せず、既存chainの
Authenticationをexternal chainで再利用しない。明示的なfilter順序は、(1) correlation IDと
ExternalApiAuditBoundaryの開始、request size・raw request-target事前検証、(2) trusted proxyと
source IPを解決（この段階ではclient CIDR判定をしない）、(3) HMAC header・signature・timestamp・
credentialの検証、(4) client principalを確定して解決済みsource IPへclient CIDR allow-listを
適用（この段階でもnonceを永続化しない）、(5) IP判定済みprincipalのnonce atomic insert
commit、(6) client scope・data scope・command permission、(7) distributed rate/quota、
(8) ExternalApiAuditBoundaryの最終decision記録、(9) controllerとする。nonce commitは
trusted proxy/source IP/CIDRと署名、timestamp、client状態をすべて検証した後だけ実行する。
認証前の監査にはsecret、raw path、raw body、PIIを含めず、認証後はexternal principal、
route template、scope/data scope/command/rateの全decision、stable result codeをsafe metadata
だけで記録する。

既存ApiAuditFilterは内部APIの更新系中心でGET external requestを完全には監査しないため、
external chainの監査正本にしない。ExternalApiAuditBoundaryは成功・controller error・
canonical/auth/IP/nonce/scope/rate拒否を含む全decisionをfinallyで一件に確定し、既存auditへ
二重登録しない。external専用AuthenticationEntryPointとAccessDeniedHandlerは、401/403を
stable JSON code（AUTHENTICATION_FAILED / FORBIDDEN_SCOPE）、correlation ID response header、
必要最小限のmessageだけで返す。ExternalApiExceptionHandlerも同じwriterを使い、stack/SQL/
internal IDを出さない。CSRFはcookie/sessionを使わないexternal chainだけでdisableし、
CORSは許可originなし・wildcardなしでOrigin付きbrowser requestとOPTIONSをdefault denyする。
anonymousはdisableし、401/403をinternal form login/error dispatchへfall-throughさせない。

監査recordのroute templateは承認済みallow-listから選ぶ有限値とし、未一致targetは
EXTERNAL_UNKNOWN_ROUTEへ収束させる。認証前principalはUNAUTHENTICATED、認証後principalは
clientIdだけをsafe external principalとして記録し、raw target/query、body、credential header、
secret、PII、source IPは監査recordへ保存しない。decision type、status class、stable result code、
correlation ID、route templateを含む一request一recordを成功・例外・rejectのfinallyで確定する。

external chainの認可は承認済みGET routeの完全一致allow-listだけを通し、anyRequest().denyAll()
を最後に置く。unknown path、unknown method、command、export、matcher外のrequestには
permitAllを適用しない。既存portal/internal chainはexternal clientを認証・認可せず、
external chainはinternal user/sessionを認証根拠にしない。chain選択、順序、排他、stateless、
default deny、filter一回実行を起動時assertionとsecurity testで固定する。

### 1.2 F2 Implementation Review remediation boundary

F2の実装は `e47025b5` で次の専用境界へ固定する。これは実装Reviewの再判定を待つ証跡であり、PASSやproduction enablementを意味しない。

1. raw request-targetは `ExternalApiRawRequestTargetValve` がTomcat connectorのrequest-line bytes（`MessageBytes.T_BYTES`）から取得し、
   `ExternalApiTomcatConfiguration`がengineへ一度だけ登録する。byte配列はcopyしてrequest attributeへ渡し、servlet normalized URI/query、Host、
   Forwarded/X-Forwarded-For、proxy rewrite、testの手動attributeは入力元にしない。属性がない場合は認証前に400 fail-closedとする。
2. `ExternalApiDataScope`はJSON objectのallowed dimensionと有限IDをstrict parseし、client bindingとroute scopeの共通dimensionだけをintersectionする。
   `ExternalApiEffectiveScope`はtenant/legal entity、allowed valuesをdeep-copyしたimmutable recordとし、`ExternalApiAuthorizationFilter`がrequest contextへ一度だけ
   bindする。route resource typeに対応するdimensionがない、またはmalformed/empty/duplicate/wildcardの場合は403へ収束する。
3. `ExternalApiAuditTrail`はrequest内のbounded metadataだけを収集し、`ExternalApiAuditBoundary`が `ExternalApiAuditService` へ一request一recordを要求する。
   `V130__integration_hub_external_api_audit.sql` の専用tableはcorrelation、pre/post principal、credential version/key ID、allow-list route template、
   authentication/scope/dataScope/command/rate decision、status/result codeだけを保存する。永続化serviceの欠落・失敗時はresponseを500へ置換し、response bodyを
   commitする前にfail-closedとする。raw target/body、source IP、secret、PIIはrecord/logへ渡さない。
4. `ExternalApiCidrMatcher`はDNS APIを呼ばず、strict literal parserでIPv4 4 octetとIPv6 group/`::`だけを受け付ける。IPv4-mapped IPv6はIPv4へ
   normalized byte representationとして比較し、short/integer/leading-zero/zone ID/hostname/bracket/空白を拒否する。
5. `ExternalApiMetricsRecorder`はroute/method/status class/outcome/client tierの有限集合だけでCounterを登録する。client/correlation/request/
   idempotency/resource/user/IP/provider IDをlabelへ入れず、unknown route/method/outcome/tierは固定bucketへ収束させる。
6. `/external-api/v1` exact rootは `/external-api/v1/**`と同じsecurity matcher、route catalog、correlation、audit、disabled deny-only境界で処理する。

### 1.3 F2追加remediation：authoritative scopeとmapped CIDR

固定Head `f57df6d2cd962c4695d41b9a1980cc4b621cb408` の再Reviewで、resource dimensionだけのintersectionではtenant/legal entity矛盾を検出できないことが判明した。
そのため、`tenantIds`と`legalEntityIds`はeffective scopeのauthoritative singleton predicateとする。JSONに存在する場合はclient scope、route scope、
およびそのintersectionの各段階で、principalのtenantとlegal entityのexact singletonと一致しなければ拒否する。intersectionは明示dimensionの空集合を
削除せず保持し、`ExternalApiEffectiveScope`のconstructorが空predicateを拒否する。JSONからdimensionが省略された場合も、effective scopeへprincipalの
tenant/legal entity singletonを追加する。A1以降はこのimmutable effective scopeを唯一のvisible population入力とし、raw client/route scopeを直接参照しない。

CIDRはIPv4-mapped IPv6をfamily差異で誤拒否しない。`::ffff:0:0/96`に該当する16-byte address/CIDRを4-byte IPv4へcollapseし、mapped CIDRのprefix 96〜128を
IPv4 prefix 0〜32へ変換して比較する。IPv4 source×mapped CIDRとmapped source×IPv4 CIDRは同じ4-byte predicateへ収束する。mapped prefixの範囲外、short/integer/
leading-zero/zone ID/hostnameは引き続き拒否する。

## 2. F1コンポーネントと保存モデル

F1で実装する責務は次のとおり。DDLのmigration番号とMySQL/H2具体実装は開始時に現行最大値を再確認する。

| 候補 | 主な責務 | 必須境界 |
|---|---|---|
| m_api_client | client、owner、tenant/legal entity、状態、expiry、IP policy、rate policy | 管理画面と監査の正本 |
| m_api_client_scope | clientとclient scopeの許可 | roleではなくclient scope |
| t_credential_version | credential世代、hashまたはsecret reference、暗号文、key version、発行/expiry/revoke、overlap | 原文再表示なし、fail-closed |
| t_api_idempotency_record | client、endpoint、idempotency key、request digest、状態、response reference、expiry | 同一payloadは同結果、別payloadはconflict |
| m_webhook_subscription | client、direction、event allow-list、endpoint、signing key世代、状態 | subscription scopeとdata scopeを分離 |
| t_api_delivery | NF-05専用event snapshot、payload hash、claim lease、attempt、backoff、DLQ、replay generation | notification outboxとは分離したdelivery ledger。deliveryのCAS正本 |
| t_inbound_event | client/provider、provider event ID、timestamp、raw hash、canonical payload、processing state、retention expiry | duplicate/conflict/replayの正本 |
| t_api_usage_bucket | client×scope×tenant×route template、minute/day window state、burst token state | 承認済み4次元だけをDB uniqueで固定し、multi-node atomicityを保証 |
| t_api_nonce_replay | client、credential version、nonce hash、accepted/expiry時刻 | client×nonce hashのatomic uniqueとTTL purgeで署名replayを拒否 |
| t_api_retention_hold | record kind/id、hold state、generation、reason code、version、created/released時刻 | purgeと同じ対象row lockで競合を直列化 |
| t_api_purge_checkpoint | table kind、restore epoch、expires-at cursor、last id、run status | checkpointは最適化情報。restore後の全件再評価を妨げない |

既存notification outboxとaccounting IntegrationJobは、各々の既存契約を壊さず比較対象にする。NF-05では
第二の汎用outboxや同一eventの二重outbox投入を禁止する。既存notification outboxはアプリ内通知専用、
accounting IntegrationJobは会計provider専用とし、NF-05 webhook eventは専用のt_api_deliveryへ一度だけ記録する。
この分離は既存tableの列契約とretention、scope、lease、replay世代が互換でないためであり、既存tableを拡張・
コピー・二重書込みしない。業務stateとt_api_delivery rowは同一transactionでcommitし、claim、外部HTTP、
result CASを分離する。既存notificationのtransaction境界違反はNF-05へ流用しない。

### 2.1 F1 persistence decision

#### Usage bucketの保存キーと原子更新

rate/quotaの全保存rowは、承認済みの client × scope × tenant × route template をquota subject keyとする。
DB unique keyは client_id、scope_code、tenant_id、route_template の4列だけとし、minute_window_start、
minute_count、day_window_start、day_count、burst_tokens、burst_last_refill_at、versionは状態列であって、
追加の認可・課金・client識別dimensionではない。source IPはCIDR/trusted-proxy検証の入力に限り、usage rowや
metrics labelへ保存しない。route_templateはendpoint templateへ正規化した後に作成し、raw pathをキーにしない。

burstはcapacity 20 token、初期token 20、refillは3秒ごとに1 token（20 token/60秒）の固定token bucketとする。
burst_tokensは0以上20以下の整数、burst_last_refill_atは直近refill境界を保存する。短いDB transaction内で
server_nowを読み、floor((server_now - burst_last_refill_at) / 3秒)だけtokenをmin(20, current + elapsed)へ補充し、
burst_last_refill_atを補充済み境界まで進める。clock rollback（server_nowが直近時刻より過去）の場合はwindow resetも
refillも行わず、時刻を後戻りさせない。minute_window_startはUTC minute境界、day_window_startはUTC day境界で管理し、
境界を越えたときだけ対応するcountを0へresetする。

同じ短いDB transactionがrow lockを取得し、minute_count < 60、day_count < 50,000、burst_tokens >= 1の三条件を
同時に満たす場合だけminute_count/day_countを各1増加しburst_tokensを1減らす。いずれかが不足する場合はどのcounterも
変更せずdenyし、Retry-Afterは不足した条件がすべて満たせるまでの最大待機秒を返す（burstは次token境界、minute/dayは
次のUTC境界）。rowが無い場合のinsert競合は、MySQL gap lockを作る先行FOR UPDATEを避けてDB unique upsertで直列化し、
戻り値に依存せず同じlock/predicateへ収束させる。quotaの短いtransactionはREAD COMMITTEDで実行し、deadlock時は
transaction全体を限定回数だけ再試行する。multi-nodeでJVM内counterへfallbackせず、client IDやIPをmetrics labelへ出さない。

#### 保存serviceとsnapshotの型境界

F1のintegrationhub service interfaceはMyBatis-PlusのIServiceを継承せず、保存・状態遷移ごとの明示的な
typed operationだけを公開する。mapperは内部のSQL境界であり、controllerや外部adapterへ汎用save/update/removeを
露出しない。これにより、entityを直接保存してscope、状態、retention、snapshot検証を迂回する経路を作らない。

ExternalDtoSnapshotはJSON objectを構造的にparseし、用途ごとにSAFE_RESPONSE_FIELDS、INBOUND_FIELDS、
OUTBOUND_FIELDSのallow-listを適用する。未知field、過大な配列・文字列、重複JSON key、object以外のrootは拒否する。
idempotencyのsafe responseはcode/statusと承認済みdata fieldだけ、inboundはprovider event metadataと
allow-listed parsed fieldだけ、outboundは承認済みexternal DTO envelopeだけを保存する。自由記述message、internal
entity、DB internal ID、secret、PII、provider raw body、stack/SQLは各保存境界へ渡さない。payload/canonicalPayloadは
allow-list済みのnested object、changedFieldNames/skillTagCodeはbounded string arrayに限定する。public IDはopaque token、
date/date-timeはRFC形式、status/resultCodeはbounded code pattern、availability/signature/processing statusとerror codeは
field固有enumで検証し、未型付けscalar、raw body/PIIを許可fieldの文字列として包んだ値も拒否する。nested objectの深さも
上限を持ち、用途別typed DTOからのみsnapshotを生成する境界をF2以降へ引き渡す。

#### Conflictのcanonical persistence

同じidempotency natural keyでrequest digestが異なる場合、既存IN_PROGRESS rowをFOR UPDATEし、CONFLICT、409、
固定safe code、terminal_at、FAILED_DLQ_PAYLOAD_90Dへ一回だけversion CASする。その後に安全なconflict exceptionを
返し、別payloadを既存結果へ接続しない。provider eventのunique insertがDuplicateKeyになった場合はprovider event
rowをFOR UPDATEで再読し、raw hash不一致かつRECEIVED/PROCESSINGならCONFLICTへ遷移させる。同時処理の勝者・敗者を問わず
DB上のcanonical stateを残す。

#### Nonce replay ledger

t_api_nonce_replayはclient_id、credential_version、nonce_hash、accepted_at、expires_at、created_atだけを
保持し、raw nonce、署名、body、secret、PIIを保存しない。nonce_hashはcanonical nonce bytesのSHA-256とする。
一意制約は client_id + nonce_hash とし、credential versionを跨いだ再利用も拒否する。認証署名、timestamp、
client状態、IPを検証してから短いtransactionでinsertし、unique conflictは安全なauthentication failureへ
収束させる。insertのcommit後だけhandlerへ進むため、後続処理失敗でも同nonceは再利用できない。

expires_atは max(accepted_at, signed_timestamp) + 5分 とし、server clockを正本にする。これは許容時刻差±5分の
future timestampを含めて少なくとも署名受付窓を覆う。purgeはexpires_at <= server_nowのrowだけをbounded batchで
物理削除し、unique ledgerの意味を壊すupdateや再利用は行わない。purge失敗は次回同じpredicateで再実行する。

#### t_api_deliveryのreuse / 分離方針

既存のt_notification_outboxとAccounting IntegrationJobはreuseしない。NF-05ではt_api_deliveryを専用の
public webhook delivery ledgerとして分離して採用するが、t_api_outboxのような第二outboxは作らない。t_api_delivery
自身がNF-05のoutbox/event rowであり、event_id + subscription_id + delivery_generationをuniqueにする。
同じdomain eventをnotification outboxへ複製せず、subscriptionごとに承認済みexternal DTO snapshotを一件だけ
作る。業務stateとこのrowのinsertは同一transaction、claim/leaseは短いtransaction、外部HTTPはtransaction外、
結果更新はrow version、lease token、payload hash、provider idempotency key、delivery generation付きCAS transactionとする。manual replayは新generationの
delivery rowとして作成し、元rowを再pending化しない。

delivery rowには event_id、subscription_id、delivery_generationからSHA-256で決定的に導出した
provider_idempotency_keyも保存する。後続B1 workerはproviderの冪等キーとしてこの値を毎回再利用し、provider成功直後の
worker crashやstale lease recoveryで同じ外部副作用を重ねない。provider request IDは結果確認用のsafe metadataであり、
deliveryのCASはprovider_idempotency_key、payload hash、lease世代が一致する場合だけ許可する。

#### Retention / legal holdの保存モデル

t_api_idempotency_record、t_api_delivery、t_inbound_eventはretention_classとretention_expires_atを持つ。
terminal stateごとの期限は、SUCCEEDED/PROCESSED/DUPLICATEを30日、FAILED/DLQ/CONFLICTを90日とする。
IN_PROGRESS/PENDING/CLAIMED/RETRYABLE/RECEIVED/PROCESSINGはterminalになるまでpurge対象にせず、
audit metadataは既存監査契約のmetadata-only rowとしてcreated_at + 1年を期限にする。payloadは承認済みexternal
DTO snapshotまたはallow-listed parsed fieldsに限り、raw request/body/provider responseは保存しない。

| record_kind | purge対象terminal state | retention_class | 起算点 |
|---|---|---|---|
| IDEMPOTENCY / DELIVERY | SUCCEEDED | SUCCEEDED_PAYLOAD_30D | terminal_at |
| INBOUND | PROCESSED / DUPLICATE | SUCCEEDED_PAYLOAD_30D | terminal_at |
| IDEMPOTENCY / DELIVERY / INBOUND | FAILED / DLQ / CONFLICT | FAILED_DLQ_PAYLOAD_90D | terminal_at |
| AUDIT | metadata-only audit row | AUDIT_METADATA_1Y | created_at |

t_api_retention_holdはrecord_kind（IDEMPOTENCY/DELIVERY/INBOUND/AUDIT）とrecord_idを一意に持ち、
ACTIVE/RELEASED、hold_generation、reason_code、version、created_at、released_atを保存する。record_idは対象rowの
内部参照に限定し、外部payload、raw body、PIIをhold tableへ複製しない。hold開始、解除、purgeは対象record rowを同じ順序でFOR UPDATEし、
hold stateまたはrecord versionをCASする。purgeはterminalかつretention_expires_at <= now、delivery leaseのtoken/expiryが
両方NULL、または両方non-NULLでexpiry <= now、
ACTIVE holdなしのrowだけを削除する。checkpointを確保した後にtarget row、最後にhold rowをlockする共通順序を
hold開始/解除/purgeで使い、初期checkpointはupsert-firstで作る。複数rowのholdはrecord kind/id順にlockしてdeadlockを避ける。holdが先に
commitすればpurgeは削除せず、purgeが先にcommitすればhold操作は消失を隠さずgoneとして監査し、active holdを
削除済みrowへ誤って表示しない。

t_api_purge_checkpointはbounded batchの再開位置にのみ使い、削除可否の正本にしない。backup/restore後の運用は
restore cutover時に新しいrestore_epochをcheckpointへ記録してからpurgeを起動し、旧checkpointを無効化する。
各retention classについてretention_expires_at,idのkeyset cursorでbounded batchを再開する。候補SQLではACTIVE holdと
期限前のleaseを除外し、hold取得・解除は対象classのcursorをresetする。active leaseのように時間でeligibilityが
変化するrowをcursorの先へ取り残さないため、候補数がlimit未満ならbatch完了時にcursorをnullへ戻し、次回走査を先頭から
行う。delete直前には対象rowを同じ順序でFOR UPDATEし、ACTIVE holdなし、lease token/expiryのstrictなNULL組合せまたは
期限切れ、terminal state、
retention_expires_at <= now、取得時version一致をdelete predicateで再確認する。
restore後に復活した期限切れrow、復元されたhold、lease競合を同じlock/CAS規則で処理し、purgeは何度実行しても
同じ結果になる。部分失敗は成功batchを再削除せず、未処理batchを次回へ残し、cursor末尾では再評価のためresetする。

webhook endpoint_urlは最大512文字とし、client・direction・event type・endpointの組合せをDB uniqueで固定する。
この上限はutf8mb4の複合unique keyがMySQLの3072-byte制限を越えないための保存契約であり、B1の登録時にも同じ上限を
検証する。

## 3. 認証・secret

認証はHMAC-SHA256 signed service accountの一方式に固定する。canonical署名対象はclientId、
credentialVersion/keyId、timestamp、nonce、method、canonical path/query、body SHA-256とする。許容時刻差は
±5分、nonce replayは拒否し、OAuth fallbackは持たない。credential principalはclient、tenant、legal entity、
scope、data scopeへserver-sideでbindする。

secret envelopeはAES-256-GCMとし、AADへclientId、credentialVersion、purposeをbindする。crypto keyは環境注入
keyringから取得し、DBへ平文保存しない。envelopeにはcredentialVersion、cryptoKeyVersion、cipherFormat、
secretHashまたはsecretManagerReference、issuedAt、expiresAt、revokedAt、overlapUntilを持たせる。
token世代と暗号鍵世代は別物として管理する。発行時に原文を一度だけ返す場合も、ログ・監査・例外・
DB・metricsには原文を流さず、失敗時はsafe errorにする。rotation overlap中は旧世代を期限まで検証可能にし、
overlapは24時間、revokeは即時、credential有効期間は90日とする。旧keyのdecrypt失敗、expired、revoked、
unknown versionはfail-closedとする。

### 3.1 HMAC canonical requestのbyte契約

署名対象headerは X-Client-ID、X-Credential-Version、X-Key-ID、X-Timestamp、X-Nonce、
X-Client-Signature とし、OpenAPI candidateのwire名と一致させる。header名の重複、obs-fold、
CR/LF、前後空白、許可外ASCIIを拒否し、first/last選択やUnicodeの代替解釈を行わない。
clientIdは1〜64 byteのASCII [A-Za-z0-9._~-]、credential versionは1〜10 byteの正の
ASCII十進数（1〜2147483647、先頭ゼロ、空白、符号を禁止）、keyIdは1〜100 byteのASCII
[A-Za-z0-9._~-]、timestampは10桁のUTC Unix秒（先頭ゼロ、空白、符号を禁止）とする。
nonceはbase64url paddingなしで22〜43 ASCII文字かつdecode後16〜32 byteとする。署名値は
base64url paddingなしで43 ASCII文字、decode後ちょうど32 byteでなければ拒否する。
raw external header blockは16,384 byte以下かつ32 field以下、未定義の単一header valueは
256 byte以下とする。Content-LengthはASCII十進数1〜7桁で0以外の先頭ゼロを禁止し、
1,048,576以下のdecoded body長と一致させる。X-Correlation-IDを受け付ける場合は16〜128 byteの
ASCII [A-Za-z0-9._~-]に限定し、署名・principal・認可判定には使わず、未指定時はserver生成する。

bodySha256はrequestで受信した未加工byte列に対するSHA-256の小文字64桁hexとする。
bodyが空の場合も空byte列のhashを使い、JSON parse、charset変換、再シリアライズ後のbodyを
使わない。raw bodyはtransfer decoding後・content decoding前の受信byte列とし、body上限は
1,048,576 byteとする。Content-Encodingは未指定またはidentityだけを許可し（header valueは
8 byte以下）、gzip/br等は
署名前に拒否して展開しない。重複Content-Length、Content-Length不一致、body上限超過も
拒否し、raw bodyを永続化・logしない。

raw request-targetの入力元は、信頼するHTTP/1.1 connectorがrequest-lineから、HTTP/2 connectorが
path pseudo-headerから、servlet containerのpath/query正規化前に設定するimmutableな
external.raw-request-target byte属性とする。属性がない、connectorが未承認、または
getRequestURI/getQueryString等の正規化値とorigin-form targetを検証できない場合は400へ
拒否する。absolute-form、fragment、CR/LF、NUL、backslash、raw non-ASCII、invalid percent
tripletを拒否し、Forwarded/X-Forwarded-For、Host、proxy rewrite値をsourceにしない。raw
request-targetは4,096 byte以下とし、pathとqueryの各raw byte長も2,048以下とする。

canonicalTargetは次の順序で生成する。全byteはASCII origin-form targetとして扱い、raw targetを
最初の ? でpathとqueryへ一度だけ分割する。pathは / で始め、dot segmentを入力時点で拒否する。
pathのliteral / はseparatorとして保持し、unreserved byte（A-Z、a-z、0-9、-、.、_、~）は
そのまま出力する。percent tripletはHHを一byteとして検査し、そのbyteがunreservedならliteral
へ一度だけdecodeし、それ以外は%HH（hex uppercase）として出力する。その他のraw byteは
%HHへ変換する。これにより%2fと%2Fは同じ%2Fになり、%2Fはliteral /へdecodeしない。
pathのcanonical byte長は2,048以下とする。

queryがない、または ? の後が空ならquery suffixを付けない。queryはliteral & で分割し、
空pairを拒否する（query全体が空の場合を除く）。各pairは最初のliteral = だけでname/valueに
分け、=がない場合はvalueを空とし、canonical outputでは必ずname=とする。nameは空を拒否し、
空valueは許可する。name/valueのnormalizationはpathのpercent規則を使うが、/を特別扱いせず、
unreserved以外を%HHへ変換する。したがって+は空白でなく%2B、value内の=は%3Dとなる。
pairをcanonical name byte、canonical value byte、入力順序の順にstable sortし、duplicate pairを
削除せず、name=valueを&で連結する。query raw byte長は2,048以下、pair数は50以下、各
name/valueのcanonical byte長は256以下、完成したcanonicalTargetは4,096 byte以下とする。
queryが一つ以上ある場合だけpath + ? + joined pairsをcanonicalTargetとする。

canonicalBytesは、先頭のASCII文字列 IH-HMAC-SHA256-V1 とLF byteに続けて、次の固定順序で
構成する。field(name,value)はASCIIの name + ":" + UTF-8 byte lengthの10進表記 + ":" に
続けて、そのvalueのUTF-8 bytesとLF byteを置く。したがって実装上は
ASCII("IH-HMAC-SHA256-V1" + LF) || field("clientId", clientId) || field("credentialVersion",
credentialVersion) || field("keyId", keyId) || field("timestamp", timestamp) || field("nonce",
nonce) || field("method", method) || field("canonicalTarget", canonicalTarget) ||
field("bodySha256", bodySha256) とし、
値をtrimまたは再正規化しない。値のbyte lengthは文字数ではなくUTF-8 byte数である。

    IH-HMAC-SHA256-V1
    clientId
    credentialVersion
    keyId
    timestamp
    nonce
    method
    canonicalTarget
    bodySha256

上記は説明上のfield順であり、実装では各fieldのlength prefixを含むbyte列を連結する。
署名はそのcanonicalBytesへHMAC-SHA256を適用し、base64url paddingなしでconstant-time
compareする。byte lengthは文字数ではなくUTF-8 byte数であり、UTF-8 byte length prefixの境界、
duplicate query、percent encoding、空body、malformed header、path ambiguity、署名vectorを
固定したcontract testを持つ。webhook eventもJSON serializer任せにせず、同じくallow-list
された明示的byte encodingを使う。

contract testのgolden vectorは次を正本とする。最初のvectorはtest fixture専用secretであり、
実credentialやlogへ流さない。

| raw request-target | canonicalTarget |
|---|---|
| /external-api/v1/project?b=2&a=one&a=&flag | /external-api/v1/project?a=&a=one&b=2&flag= |
| /external-api/v1/project?x=hello%20world&x=hello+world&x=a%3Db | /external-api/v1/project?x=a%3Db&x=hello%20world&x=hello%2Bworld |
| /external-api/v1/project?b&b= | /external-api/v1/project?b=&b= |
| /external-api/v1/project? | /external-api/v1/project |
| /external-api/v1/%2f | /external-api/v1/%2F |
| /external-api/v1/a/../b、/external-api/v1/%G0 | reject（REQUEST_INVALID） |

signature vectorは固定test clock 2026-08-30T00:00:00Z、clientId=client-a、
credentialVersion=1、keyId=key-1、timestamp=1788048000、nonce=AQIDBAUGBwgJCgsMDQ4PEA、
method=GET、canonicalTarget=/external-api/v1/project?a=&a=one&b=2&flag=、空body、
test-secretを使う。
canonicalTarget byte lengthは43、empty body SHA-256は
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855、期待するunpadded
base64url signatureは UGMa5HEXan7nOe2RtY8RO_x4TgNXuaBZ0QMA7RaVz2A である。

IPはclientごとのCIDR allow-listをdefault denyで適用する。Forwarded/X-Forwarded-Forは明示設定された
trusted proxyからのみ採用し、unknown、malformed、multi-hop不正を拒否する。IPv4/IPv6を正規化し、
client identityとsource IPの両方を許可判定へ含める。

## 4. 認可モデル

判定は次の順序で行う。

1. credentialを検証し、clientをactiveかつ期限内であることを確認する。
2. client scopeがendpointのrequired scopeを含むことを確認する。
3. operationがcommand permission表で許可されていることを確認する。
4. target resourceへtenant/legal entity/data scope predicateを適用する。
5. list、detail、count、cursor、export、errorの各結果が同じvisible populationを使うことを確認する。

resource/field/operationはinventory.mdのclient scope × data scope × command permission表を正本とする。
role名を公開認可の代替にしない。未記載fieldはdeny-by-defaultとし、internal entity、internal ID、
role、PII、secret、原価・粗利・単価、provider raw body、DLQ内部エラーを返さない。
初期公開契約はGET-only 11 pathsのallow-listで、command/exportはdefault denyである。

## 4.1 A1 read API実装境界

`ExternalApiReadController`は`/external-api/v1`およびcandidateのGET-only 11 pathsだけを持つ。controllerはF2 filterが生成した
principalと`ExternalApiEffectiveScope`を受け取り、raw client/route JSONや内部roleを参照しない。`ExternalApiReadService`はrequest受信時に
server clockを一度取得し、UTC epoch secondsへ正規化してlist/detail/countの同一呼出しへ渡す。A1はclient指定`asOf`を受け付けない。
初回response、snapshot、cursor、後続responseは同じ秒精度のas-ofを返す。

`ExternalApiReadMapper`はresourceごとのselected columns、`deleted_flag=0`、effective scopeのID predicate、ID-desc stable sort、
limit+1をSQL境界へ固定する。detailはopaque public IDを内部IDへ解決した後もeffective scope内だけを検索し、scope外・不存在を同じ
`404 RESOURCE_NOT_FOUND`へ収束する。countは同一predicateで計数し、empty scopeを全件へ拡張しない。

responseは`ExternalApiEngineerAvailability`、`ExternalApiProject`、`ExternalApiContractStatus`、
`ExternalApiInvoiceStatus`およびlist/count wrapperだけを使う。engineer availabilityでは現行sourceの`available_date`だけを
`availableFrom`へ写像し、`availableTo`と`skillTagCode`はcanonical sourceがないためnullとする。public IDはHMAC-SHA256で生成し、
cursorはAES-GCM暗号化してclient、tenant、legal entity、route template、scope digest、snapshot ID、as-of、expiryへbindする。

初回listが次ページを持つ場合、serviceは秒精度へ正規化したrequest受信時のserver clockをas-ofとして、SQLでscope済みの全visible rowをallow-list DTOへ変換し、
`t_api_read_snapshot`（client/tenant/legal entity/route/scope digest/as-of/expiry）と
`t_api_read_snapshot_item`（resource ID、DTO JSON）へ同一transactionで保存する。次ページはsnapshot IDと内部keysetだけでitemを読むため、
初回後のinsert、update、delete、customer/project reparentがmembershipと公開値を変更しない。snapshotにはinternal entity JSON、raw request、
secret、PII、provider responseを保存せず、cursor TTL後にpurgeする。snapshot rowが失われた、期限切れた、またはDTO復元できない場合は
`CURSOR_INVALID`へ収束する。

invoice queryは`invoiceIds × customerIds`をlist/detail/countの同一WHERE predicateへ必須適用し、contract scopeがある場合はinvoice itemからの
EXISTS predicateでintersectionする。invoiceが複数contractへ紐づくときは、単一contractを表す`publicContractId`を返さずnullとする。

snapshot purgeは公開read transactionから分離した`ExternalApiReadSnapshotPurgeScheduler`だけが起動する。
`ExternalApiReadSnapshotMapper.selectExpiredSnapshotIds`は`expires_at, snapshot_id` expiry indexの順で最大32 headerを取得し、
`deleteSnapshotsById`の一つの短いtransactionでheaderをprimary key削除する。itemは`ON DELETE CASCADE`で同じheader batchに限定して削除され、
request pathからpurge mapperを呼ばない。削除件数不一致または例外はtransaction全体をrollbackし、次回schedulerが同じ期限切れ集合を再評価する。
無通信でもschedulerが実行され、複数batchを有限回ずつ処理する。snapshot一件のitem上限はA1の最大512件であり、batch全体のcascade対象も有限である。

enabled connector E2Eのcredential fixtureはDATETIME列へUTC `LocalDateTime`を渡す。認証filterのUTC判定と同じ表現に揃え、
環境依存の`Timestamp.from(Instant)`変換でissued timeが未来になる経路を作らない。

## 5. 3つの決定表

### 5.1 時刻・as-of

| 対象 | 承認済み規則 | 実装時証跡 |
|---|---|---|
| list/detail | request受信時のserver clockをas-ofに固定。client指定asOfなし | p95/SLA内でのclock記録 |
| availability | availabilityのeffective intervalをas-ofへ適用。A1の現行sourceは`available_date`を`availableFrom`へ写像し、履歴・`availableTo`は公開しない | 未来予約とNULLの実データ境界 |
| contract/invoice status | stateのeffectiveAtを使い、現在値と履歴を混ぜない | historical queryを公開しない |
| cursor | as-of、sort key、tie-breaker、client/scopeへbind | cursor expiry、tamper、scope test |

### 5.2 subject × operation × visible population

| subject | operation | visible population | deny時の応答 |
|---|---|---|---|
| client principal | list/detail/count | client scope ∩ tenant ∩ legal entity ∩ data scope | empty/404の非列挙規則を承認値どおり適用 |
| client principal | command | default deny。A2はNOT_APPLICABLE_UNDER_CURRENT_DECISION（approved command=0件） | stable error、内部理由を出さない |
| webhook worker | delivery | subscription scope ∩ event allow-list | claim対象外は処理しない |
| admin operator | replay/rotate | 内部admin action permission ∩ audit requirement | internal admin UIの規則に従う |

### 5.3 canonical state・retention・並行性

状態名は以下を唯一の正本とする。deliveryのretry状態はRETRYABLEとし、別名のRETRY、SENT、
idempotencyのEXPIREDを実装状態として追加しない。
非terminal状態（idempotency=IN_PROGRESS、delivery=PENDING/CLAIMED/RETRYABLE、inbound=RECEIVED/PROCESSING）は
purgeせず、terminal状態はretention tableのclassと起算点へ必ず対応させる。

| 対象 | canonical enum | terminal分類・保持 | 許可遷移と競合規則 |
|---|---|---|---|
| credential | ACTIVE / OVERLAP / REVOKED / EXPIRED | credential metadata。payload retention対象外 | ACTIVE→OVERLAP/REVOKED/EXPIRED、revoke即時、旧世代はnon-null overlapUntilまで。version CAS |
| idempotency | IN_PROGRESS / SUCCEEDED / FAILED / CONFLICT | SUCCEEDED=SUCCEEDED_PAYLOAD_30D、FAILED/CONFLICT=FAILED_DLQ_PAYLOAD_90D。terminal_at起算 | IN_PROGRESS→SUCCEEDED/FAILED/CONFLICT。unique(client, endpoint, key)、digest一致だけ再利用。不一致はCONFLICTを永続化してから409。terminalから逆遷移しない |
| delivery | PENDING / CLAIMED / RETRYABLE / SUCCEEDED / FAILED / DLQ | SUCCEEDED=SUCCEEDED_PAYLOAD_30D、FAILED/DLQ=FAILED_DLQ_PAYLOAD_90D。terminal_at起算 | PENDING→CLAIMED→SUCCEEDED/RETRYABLE/FAILED/DLQ、RETRYABLE→CLAIMED。row version・lease token・payload hash・provider key・generation付きCAS。terminalから逆遷移しない |
| inbound event | RECEIVED / PROCESSING / PROCESSED / DUPLICATE / CONFLICT / DLQ | PROCESSED/DUPLICATE=SUCCEEDED_PAYLOAD_30D、CONFLICT/DLQ=FAILED_DLQ_PAYLOAD_90D。terminal_at起算 | RECEIVED→PROCESSING→PROCESSED/DUPLICATE/CONFLICT/DLQ。provider event ID unique、raw hash不一致はCONFLICT。terminalから逆遷移しない |

## 6. HTTP契約（F1/A1計画入力）

version namespaceは/external-api/v1/**とし、OpenAPIを手書きまたは生成物として固定する。responseは専用
external DTOのみを使い、entityの継承、reflectionによる自動全field変換、Mapの透過返却を禁止する。
clientからas-ofを指定するquery parameterは公開しない。list/detail/countのas-ofはrequest受信時の
server clockで固定し、responseとopaque cursorへだけ反映する。cursorはscope/client/as-of/sort/filterへbindする。
countとexportはlistと同一predicateを使い、
権限外件数、存在判定、ページ境界から他client dataを推測できないようにする。

error bodyはstable code、message、correlationId、必要最小限のfield errorのみとし、stack、SQL、内部ID、
provider本文、DLQ内部理由を出さない。approved status/code mappingは400=REQUEST_INVALIDまたはCURSOR_INVALID、
401=AUTHENTICATION_FAILED、403=FORBIDDEN_SCOPE、404=RESOURCE_NOT_FOUND、429=RATE_LIMITED、
500=INTERNAL_ERRORに限定する。scope外detailと不存在detailは404/RESOURCE_NOT_FOUNDへ収束させ、
list/countはscope適用後の母集団だけを返す。

将来承認されたcommandはIdempotency-Keyを必須とし、正規化したrequest bodyとendpoint/clientを含むdigestを保存する。
同じkey・同じdigestは保存済み結果を返し、同じkey・別digestはconflictを返す。並行requestは一つだけ
実行し、IN_PROGRESSを二重処理しない。

## 7. Webhook契約（実装延期）

webhook persistence contractは承認済みだが、B1/B2の外部送受信はこのscopeでは実装しない。outbound eventは
eventId、eventType、schemaVersion、createdAt、opaque public resource ID、allow-list changedFieldNames、
payload、correlationId、timestamp、signature、keyVersionを持つ。署名はHMAC-SHA256、timestamp toleranceは
±5分、event/provider ID replayは拒否する。

deliveryはclaim transaction、外部HTTP、result CASを分離する。timeout、429、5xxはbounded exponential
backoffとjitterで最大8回、その他4xxはretryなし、失敗後はDLQとする。DLQ manual replayは専用admin
permission、reason、replay generation、再scope検証、operator、auditを記録し、同一payloadを新しいdelivery
IDとして再送する。

inboundはclient/provider binding、signature、timestamp、raw body hash、provider event ID unique、
canonical payload、processing resultを保存する。重複は副作用を一度だけにし、同一IDでhashが異なる場合は
CONFLICT/DLQとする。

## 8. トランザクション・運用

業務state変更とoutbox/event row insertは同一DB transaction内で原子的にcommitする。業務commit後に
callbackや別transactionでoutboxを作る方式は採用しない。workerは短いclaim/lease transactionをcommitして
から外部callを行い、完了後にrow version、lease token、provider idempotency key、payload hash、generationを含む別の短いCAS transactionで
SUCCEEDED、RETRYABLE、FAILED、DLQへ遷移させる。stale leaseはsafeにrecoverする。

provider成功直後にworkerが停止しても、provider request ID、idempotency key、payload hash、lease世代を
使って副作用を一件へ収束させる。同時claimは一つだけを許可し、manual replayは新しいdelivery世代として
監査する。外部providerのrequest IDとcorrelation IDをsafe metadataとして保存する。

metrics labelはroute template、HTTP method、status class、bounded outcome、client tier等の有限集合だけに
限定する。client ID、correlation ID、request/idempotency key、resource ID、user ID、IP、provider event IDは
labelにしない。詳細識別子はsecret/PIIを除外した監査logまたはtraceへだけ置き、label cardinality上限と
scrape時の有限集合をテストする。

payload retentionは、idempotencyをcanonical digest、status、safe response snapshotだけに限定し、
raw secret/PII requestは保存しない。inboundは署名検証中のみraw bytesをメモリで使い、永続化はraw hash、
provider event ID、timestamp、allow-listed parsed fields、safe error codeに限定する。outboundは承認済み
external DTO snapshotだけを保存し、internal entityとprovider raw bodyは保存しない。retentionは
succeeded 30日、failed/DLQ 90日、audit metadata 1年とし、legal hold中はpurgeを停止する。期間は
DG-05 Owner承認済みである。

purge jobは期限境界をUTC/Asia-Tokyoの契約に従って一度だけ判定し、legal hold、backup/restore後、
再実行、部分失敗、dynamic lease eligibilityを安全に扱う。keyset cursorは候補集合の末尾でresetし、hold解除と
restore epochでは対象classを先頭から再評価する。ログ、metrics、auditにはsecret、PII、raw request/responseを出さず、
safe codeとhashの一部だけを使う。

Mではsecurity review、負荷とrate boundary、DB/worker/provider停止、restore、key rotation/revoke、
secret/PII scan、payload purge、alert、runbook、固定remote Headを証拠化する。全テストとReviewの
PLAN/IMPLEMENTATION PASS後のみPR作成を許可する。

### 8.1 production enablementのdefault-off / fail-closed起動契約

設定の正本名は integration.hub.public-api.enabled、integration.hub.external-transport.enabled、
integration.hub.provider.mode とする。安全なdefault-offは実行時codeのimplicit defaultではなく、
各profile設定へ明示する値であり、productionの正本は enabled=false、external-transport=false、
provider.mode=MOCK とする。したがって三つのpropertyのいずれかが未設定、unknown、型不正、または
profile/envの複数値が衝突する場合は、falseやMOCKを補うのではなくstartup validatorがfail-closedで
起動を拒否する。default-offとmissing rejectionはこの区別で両立し、未設定でrouteが有効になる
fallbackは持たない。

public-api.enabled=falseでも externalApiSecurityFilterChain は常に生成する。これは
/external-api/v1/**を捕捉するdeny-only chainで、anyRequest().denyAll()と専用stable error writer
（404 RESOURCE_NOT_FOUND、correlation ID header）だけを持ち、internal/portal chainへfall-through
させない。enabled=trueのcontrollerはdevelopment/testでのみ生成し、productionではstartup guard
が拒否する。external-transport.enabled=falseの場合はdelivery worker、scheduler、transport
clientを生成せず、workerのfallback schedulerも持たない。

provider.modeの許可enumは MOCK、STUB、LOOPBACK の三値だけとする。MOCKとSTUBはネットワークを
発生させず、LOOPBACKはdevelopment/testでのみliteral loopback allow-listへ接続する。
development/testでは明示設定されたenabled値とこの三値の組合せだけを受け入れ、productionでは
MOCK以外を拒否し、real credential、real provider URL、proxy、redirect、未承認接続先が一つでも
あれば起動を停止する。ExternalApiController、delivery worker、scheduler、transport clientの
生成条件、missing/unknown/malformed/conflict、default profile、prod profile、disabled routeの
fall-throughなし、worker/outbound beanなし、起動後callなしをtestで固定する。config guardは
feature flagだけでなく、起動時とconnection直前の二重境界として実装する。

### 8.2 mock/stub/loopback接続先検証契約

development/testのtransportはMOCK/STUB（ネットワークなし）またはLOOPBACKだけを許可する。
LOOPBACKのURLは明示されたhttp scheme、literal 127.0.0.1または [::1]、allow-listされた
portだけとし、hostname、localhost alias、userinfo、credential、非http scheme、未許可port、
path traversalを拒否する。config parse時だけでなく各connection直前にもremote socket peerが
loopbackかつallow-list portであることを検証する。DNS名、多重answer、unresolvable、
DNS rebinding、non-loopback解決はすべて拒否し、literal IPでもpeer検証を省略しない。

HTTP redirectはNEVERに固定し、3xxは失敗として扱う。HTTP_PROXY、HTTPS_PROXY、NO_PROXY、
JVM system proxyその他の暗黙proxyをloopback transportへ適用せず、明示proxy設定や環境変数
混入もfail-closedで拒否する。Forwarded/X-Forwarded-Forは接続先判定に使わない。hostname/
DNS、IPv4/IPv6、redirect、proxy、multi-address/rebinding、non-loopback、credential URL、
MOCKの無接続をtestし、SSRF経路を残さない。

## 9. Wave開始条件と禁止範囲

- scope expansionのPlan delta ReviewのPASS、approved Baseの再fetch確認、各waveのmigration最大値の再確認。
- F1ではclient、credential、scope、idempotency、usage bucket、webhook persistence contractと最小crypto/config
  abstractionを実装済みとする。secret、raw body、PIIは保存しない。
- Plan deltaはca27f455でPASS済み。F2はfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でIMPLEMENTATION PASS、A1初回Review FAILを
  `874fface3bfe90dd27b766ddf9aeff4e00eae591`でremediate済みの独立再Implementation Review待ちである。A1 Review PASS後にB1→B2→Mを各waveの独立Implementation Review後に順次開始する。
  A2はapproved command=0件のためNOT_APPLICABLE_UNDER_CURRENT_DECISIONとし、command/exportはdefault denyのままとする。
- B1/B2のprovider接続はdevelopment/testのmock/stubおよびloopback test serverに限定する。production enablement、
  実顧客credential、実providerへの外部送信、main変更、force push、merge、auto-mergeは禁止する。
- Mではsecurity review、負荷、障害訓練、key rotation/revoke、secret/PII scan、runbook、remote/local Head固定を完了し、
  最終PLAN/IMPLEMENTATION PASS後までPRを作成しない。
