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
| t_api_delivery | event snapshot、payload hash、claim lease、attempt、backoff、DLQ、replay generation | deliveryのCAS正本 |
| t_inbound_event | client/provider、provider event ID、timestamp、raw hash、canonical payload、processing state | duplicate/conflict/replayの正本 |
| t_api_usage_bucket | client、scope、tenant、IP、window/burst counters | multi-node atomicityが必要 |

既存notification outboxとaccounting IntegrationJobは、各々の既存契約を壊さず比較対象にする。新しい公開
persistence contractを追加する場合も、業務stateとoutbox/event rowを同一transactionでcommitし、claim、
外部HTTP、result CASを分離する。既存notificationのtransaction境界違反はF1/B1実装の回帰対象とする。

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

### 5.3 状態・並行性

| 対象 | 状態 | 遷移と競合規則 |
|---|---|---|
| credential | ACTIVE / OVERLAP / REVOKED / EXPIRED | version付き検証、revoke即時、旧世代はoverlapUntilまで |
| idempotency | IN_PROGRESS / SUCCEEDED / FAILED / CONFLICT / EXPIRED | unique(client, endpoint, key)、digest一致のみ再利用 |
| delivery | PENDING / CLAIMED / RETRY / SENT / DLQ | lease token付きCAS、stale claim recovery |
| inbound event | RECEIVED / PROCESSING / PROCESSED / DUPLICATE / CONFLICT / DLQ | provider event ID unique、raw hash不一致はconflict |

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
から外部callを行い、完了後にlease tokenとpayload hashを含む別の短いCAS transactionで
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
再実行、部分失敗を安全に扱う。ログ、metrics、auditにはsecret、PII、raw request/responseを出さず、
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
