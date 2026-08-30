# NF-05 Public API 設計（Owner承認済み・F1/Plan Review対象）

## 1. 適用範囲と境界

この設計はDG-05-F1-APPROVAL-20260830-01で承認されたF1実装入力である。公開機械クライアントは内部管理chain、
portal chain、既存のanonymous webhook例外へ混ぜず、/external-api/v1/** を専用chainで処理する。
公開clientを内部roleへ変換せず、client principalにtenant、legal entity、client scope、data scope、
command permission、credential version、correlation IDを束ねる。

外部呼出しはDB transaction内に置かない。DB transactionはclaimまたはresult CASまでに限定し、
外部HTTPはtransaction外で実行する。公開APIの業務commandも、長い外部処理はoutbox/jobへ切り離す。

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

## 5. 3つの決定表

### 5.1 時刻・as-of

| 対象 | 承認済み規則 | 実装時証跡 |
|---|---|---|
| list/detail | request受信時のserver clockをas-ofに固定。client指定asOfなし | p95/SLA内でのclock記録 |
| availability | availabilityのeffective intervalをas-ofへ適用 | 未来予約とNULLの実データ境界 |
| contract/invoice status | stateのeffectiveAtを使い、現在値と履歴を混ぜない | historical queryを公開しない |
| cursor | as-of、sort key、tie-breaker、client/scopeへbind | cursor expiry、tamper、scope test |

### 5.2 subject × operation × visible population

| subject | operation | visible population | deny時の応答 |
|---|---|---|---|
| client principal | list/detail/count | client scope ∩ tenant ∩ legal entity ∩ data scope | empty/404の非列挙規則を承認値どおり適用 |
| client principal | command | default deny。A2は未承認 | stable error、内部理由を出さない |
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
provider本文、DLQ内部理由を出さない。approved status/code mappingは400=RequestInvalidまたはCursorInvalid、
401=AuthenticationFailed、403=ForbiddenScope、404=ResourceNotFound、429=RateLimited、
500=InternalErrorに限定する。scope外detailと不存在detailは404/ResourceNotFoundへ収束させ、
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

## 9. F1開始条件と禁止範囲

- 独立Plan ReviewのPLAN PASS、approved Baseの再fetch確認、migration最大値の再確認。
- F1ではclient、credential、scope、idempotency、usage bucket、webhook persistence contractと最小crypto/config
  abstractionだけを実装する。secret、raw body、PIIは保存しない。
- public endpoint、外部送信、A1、A2、B1、B2、production enablement、command、exportはこのscopeで禁止する。
- F1完了後は独立Implementation Reviewへ渡し、MとPLAN/IMPLEMENTATION双方PASS後までPRを作成しない。
