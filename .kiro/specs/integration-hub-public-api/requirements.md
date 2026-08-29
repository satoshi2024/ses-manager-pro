# Requirements — NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

## 0. 状態と適用範囲

本書はDG-05-F1-APPROVAL-20260830-01でOwner承認されたNF-05基線である。承認scopeはF1 persistence基盤までで、
独立Plan ReviewのPLAN PASS前にproduction implementation、public endpoint、外部送信を開始しない。
T0/0R/0R-D以外のcheckboxを実装完了扱いにしない。

参照:

- 受入後feature backlog: NF-05はread API、限定command、署名service account、
  client scope、data scope、rate、IP、rotation、OpenAPI、cursor、Idempotency-Key、
  Correlation-ID、outbox、署名、replay防止、retry/backoff、DLQ。
- 受入後requirements/design: IH-R1〜IH-R3。
- 受入後traceability: NF-05はAPPROVED、OwnerRef=PROJECT_OWNER、DecisionId=DG-05-F1-APPROVAL-20260830-01。
- customer-product-expansion-2026/platform-invariants.md: transaction、scope、secret、
  external I/O、pagination、audit、migration、性能。
- enterprise-identity-security: client secret/tokenをログへ出さず、action permissionとfield maskingを
  service境界で実施する。

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

## IH-R2 External contract

1. 公開APIは /external-api/v1/** のversion namespaceと、Owner承認済みOpenAPI candidate契約を持つ。
   この承認ではpublic endpointのenablementを行わない。
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
6. command surfaceはdefault denyとし、A2 commandと万能CRUDは未承認である。将来承認されたcommandでは
   Idempotency-Keyとcanonical request digestを必須とし、同一key・同一payloadは最初の結果を再返却し、
   同一key・別payloadはpayload conflictとして拒否する。
7. external DTO contract testは、許可fieldの集合と禁止fieldの不在を反射/JSON assertionで固定する。
   entity型、internal DTO、Lombokの自動getterに依存して公開形を生成しない。

## IH-R3 Inbound / outbound webhook

1. outboundはevent type、opaque event ID、created time、schema version、allow-list payload、
   correlation ID、subscription識別子、timestamp、signatureを送信する。
2. signatureはHMAC-SHA256とし、clientId、credentialVersion/keyId、timestamp、nonce、method、canonical
   path/query、body SHA-256をcanonical bytesとして固定する。許容時刻差は±5分、nonce replayを拒否する。
3. receiverはtimestamp tolerance、provider event ID、raw body hash、tenant/client bindingで
   replayとduplicateを拒否する。同一provider event IDの再送は一度だけ処理し、別payload hashは
   conflict/DLQへ収束させる。
4. 業務state変更とoutbox/event row insertは同一DB transaction内で原子的にcommitし、commit後のworkerは
   短いclaim/lease transactionで取得する。外部HTTPはDB transaction外で実行し、結果は別の短いCAS
   transactionでSUCCEEDED、RETRYABLE、FAILED、DLQへ遷移させる。timeout/429/5xxのみ最大8回の
   exponential backoff+jitter、その他4xxはretryなし、失敗後DLQ、manual replayを持つ。
5. retryはnetwork/timeout/429/5xxだけを対象とし、validation/auth/permission等の4xxは無限retryしない。
   retry状態、last safe error code、next attempt、attempt count、provider request IDを保存する。
6. manual replayはadmin action permission、reason、元event snapshot hash、再生世代、scope再検証、
   auditを必須にし、同一eventを無制限に再送しない。
7. inbound handlerの業務適用はclaim処理とtransaction境界を分離し、外部応答を待つ間に内部DB
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

## IH-R6 Metrics / payload retention

1. metrics labelはroute template、HTTP method、status class、bounded outcome、client tier等の
   有限集合に限定する。client ID、correlation ID、request/idempotency key、resource ID、user ID、
   IP、provider event IDをlabelへ置かない。label cardinality上限と有限集合をscrape testで検証する。
2. idempotencyはcanonical digest、status、safe response snapshotだけを保持し、raw secret/PII requestを
   保存しない。inbound webhookは署名検証中のみraw bytesをメモリで使い、永続化はraw hash、provider
   event ID、timestamp、allow-listed parsed fields、safe error codeに限定する。outbound webhookは
   承認済みexternal DTO snapshotだけを保存し、internal entity/provider raw bodyを保存しない。
3. retentionはsucceeded 30日、failed/DLQ 90日、audit metadata 1年とする。legal hold中はpurgeを
   停止する。purge jobは期限境界、再実行、部分失敗、backup/restore後のpurgeを安全に扱う。

## 受入テスト最低条件

- client A/Bのresource、field、operation、data scope matrix。
- revoked、expired、rotation overlap、旧世代失効、IP境界、rate exact boundary、burst、retry-after。
- Idempotency-Key同一payload再送の同結果、別payload拒否、永続化失敗、worker再起動。
- cursor stability、limit上限、count/export/errorからの存在推測防止。
- JSON contract allow-list、entity serialization禁止、secret/PII log scan。
- webhook署名改ざん、timestamp古い/未来、replay、duplicate、provider event conflict、
  claim競合、timeout、429/5xx backoff、4xx no-retry、DLQ、manual replay。
- 業務stateとoutbox rowの原子commit、provider成功直後crash、stale lease、同時claim、replayで
  副作用が一件へ収束すること。
- 外部callがDB transaction内で実行されないことの境界テスト。
- metrics labelの有限集合/cardinality上限、secret/PII log・trace・metrics scan。
- payload期限境界、succeeded/failed/DLQ purge、legal hold、backup/restore後purge、purge再実行。
