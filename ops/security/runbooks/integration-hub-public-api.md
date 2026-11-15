# Integration Hub Public API 運用 runbook（NF-05 / M）

本 runbook は `integration.hub.public-api.enabled=false` / `integration.hub.external-transport.enabled=false` /
`integration.hub.provider.mode=MOCK` を production 正本とする公開 API の障害対応手順である。
production enablement、実顧客 credential、実 provider 送信は DG-05 Owner 承認と Review PASS 後のみ実施する。
開放は inbound（`DG-05-IN-*`）と outbound（`DG-05-OUT-*`）を別々に行う。承認項目と手順は
`.kiro/specs/integration-hub-public-api/dg-05-enablement-decision-draft.md`（草案・未署名）を正本とする。

## 0. 配備前提 D-1（LB/WAF での `/external-api/**` 遮断）

inbound を開放するまで、production の LB/WAF は `/external-api/**`（`/external-api/v1` を含む）を app へ転送せず、
LB 自身が 403/404 を返す。app 側も OFF 時は 404 を返すが、それに頼らず入口で止める。

| 項目 | 内容 |
|---|---|
| 理由 | 認証前の外部 traffic は誰でも送れる。app へ届くと DB 接続・CPU を消費する（認証前の rate 制限は無い: N5-P3-4）。OFF 時の app は監査 table へ書かないが（N5-P2-1）、metric と処理負荷は残る |
| 確認 | production で `integration_hub_external_api_requests_total{outcome="DISABLED"}` と `integration_hub_external_api_audit_suppressed_total{reason="PUBLIC_API_DISABLED"}` が 0 のままであること。増えたら LB rule が効いていない（§1） |
| 解除条件 | DG-05-IN 署名済み、かつ以下をすべて満たすこと。LB/WAF rule は承認済み送信元 CIDR だけを通す rule に置き換える（遮断を外すだけにしない） |
| 解除時の LB 設定 | WAF の認証前 rate 制限。ALB は `routing.http.xff_header_processing.mode=append`（client の X-Forwarded-For を保持したまま実 peer を右端へ追記）、`routing.http.xff_client_port.enabled=false`（port 付きは app が拒否する）。`preserve`/`remove` は不可 |
| 解除時の app 設定 | `integration.hub.security.trusted-proxies` に LB の subnet を CIDR で設定、`security.forwarded-header=X_FORWARDED_FOR`。app は trusted proxy が追記した右端 1 hop だけを送信元として使い、左側（client が書ける値）は使わない。trusted proxy の前段にさらに proxy を置く構成は未対応（`UNSUPPORTED_PROXY_CHAIN` で拒否） |
| outbound との関係 | outbound だけを開ける場合も D-1 は維持する（inbound を開けないため） |

> app 内の他機能（login 監査、portal、MFA 等）の remote address は従来どおり `server.forward-headers-strategy: framework`
> の書換え値を使う。公開 API の source IP 判定だけが Tomcat valve で捕捉した原文を使う。

## 1. 監視と alert

| 信号 | 閾値 | 初動 |
|---|---|---|
| `integration_hub_external_api_requests_total{status_class="5xx"}` | 5分連続 > 0 | §2 一般障害 |
| `integration_hub_external_api_requests_total{outcome="RATE_LIMITED"}` | client tier 単位で急増 | §3 rate/quota |
| `integration_hub_external_api_requests_total{status_class="4xx",outcome=~"AUTH_REJECTED|SOURCE_REJECTED"}` | 急増 | §4 認証・送信元拒否 |
| `integration_hub_external_api_audit_suppressed_total{reason="UNAUTHENTICATED_BUDGET"}` | 1以上 | §3 abuse 切り分け（未認証 reject が監査上限を超えた） |
| `integration_hub_external_api_audit_suppressed_total{reason="PUBLIC_API_DISABLED"}` | production で 1以上 | §0 D-1（LB 遮断が効いていない） |
| `integration_hub_delivery_rows{status="DLQ"}` | 1件以上 | §5 DLQ / manual replay |
| `integration_hub_delivery_rows{status="FAILED"}` | 新規増加が1件以上 | 即時通知。payload/恒久HTTP拒否を確認し、無断再送しない |
| `integration_hub_producer_skipped_total{reason=~"NO_TENANT|TENANT_MISMATCH|INVALID_SCOPE|SUBSCRIPTION_LIMIT"}` | 1以上 | 即時通知。event欠落の対象期間と業務変更を照合する |
| `integration_hub_inbound_rows{status="DLQ"}` | 15分連続増加 | §4.1 inbound |
| DB connection pool exhausted | Hikari pending > 0 が継続 | §6 DB障害 |
| startup validator fail-closed | 起動失敗 | §7 config / emergency stop |

metrics label は route template / method / status class（`2xx`〜`5xx`）/ bounded outcome / client tier、
および監査抑止の reason の有限集合のみ。client ID、correlation ID、resource ID、IP、provider event ID を label に置かない。
`status_class` は百の位だけを持つため、429 は `status_class="4xx"` に含まれる。429 の監視は `outcome="RATE_LIMITED"` を使う。
delivery / inbound gaugeはいずれか一方向ON時だけ登録し、各6状態の固定statusだけをlabelに持つ。
12状態のDB snapshotを30秒cacheする。最大12 query/30秒であり、scrape毎の反復はしない。DB障害時はNaNとなるため、欠測alertも設定する。

## 2. 一般 API 障害（timeout / 5xx）

1. `integration.hub.public-api.enabled` が意図どおりか確認する。production では default-off。
2. 専用 security chain（`ExternalApiSecurityConfig` @Order(0)）が deny-only か enabled かを profile で確認する。
3. `t_external_api_audit` で直近 reject の status/result code を確認する。secret/PII/raw body は保存されない。
   public-api OFF 時は監査行を書かない（metric のみ）。ON 時も未認証 reject（principal 無し・5xx 以外）は
   node ごとに `public-api.unauthenticated-audit-max-per-minute`（既定 60/分）までしか行を書かず、超過分は
   `audit_suppressed_total{reason="UNAUTHENTICATED_BUDGET"}` に集約される。HMAC検証済みreject（CLIENT_IP_NOT_ALLOWED / NONCE_REPLAYを含む）、認証済みrequestと5xxは常に一行。
4. correlation ID（`X-Correlation-ID`）で同一 request の監査 1 件を特定する（上記の抑止対象は行が無い場合がある）。
5. 原因が DB の場合は §6 へ。アプリのみの場合は rolling restart 前に in-flight idempotency / delivery lease を確認する。
6. 復旧後、enabled connector E2E（read 202/200、inbound duplicate/conflict）を development/test で再実行する。

## 3. rate / quota（429 / Retry-After）

- quota subject key は `client × scope × tenant × route template` のみ。IP や raw path は含まない。
- burst capacity 20、3 秒 1 token refill、minute 60、day 50,000。
- 429 応答の `Retry-After` をそのまま client へ返す。server 側で burst を手動 reset しない。
- 正当な traffic spike と abuse を `client_tier` と audit の route template 分布で切り分ける。
- 恒久対応は client scope / tier 見直し。production で quota を無効化しない。
- quota は認証後にだけ働く。未認証 traffic の急増（`audit_suppressed_total{reason="UNAUTHENTICATED_BUDGET"}` の増加）は
  WAF の認証前 rate 制限で止める（§0）。監査上限を一時的に 0 にすると未認証 reject の行は書かれなくなるが、
  処理負荷は減らない。

## 4. 認証・送信元拒否（401）

| 監査の authentication decision | 意味と初動 |
|---|---|
| `CLIENT_IP_NOT_ALLOWED` | 送信元が client の `allowed_cidrs`、または `public-api.approval.allowed-client-cidrs`（DG-05-IN の承認範囲）の外。両方を確認する |
| `UNTRUSTED_PROXY` | trusted proxy 以外の peer が転送 header 付きで接続した。LB を経由しない経路が無いか確認する |
| `PROXY_HEADER_MISSING` | trusted proxy から設定 header（既定 X-Forwarded-For）が届かない。ALB の xff mode（`remove` になっていないか）と `security.forwarded-header` を確認する |
| `DUPLICATE_PROXY_HEADER` | 転送headerが複数行。ALBの単一行正規化を確認する |
| `INVALID_PROXY_HEADER` | 右端 hop が IP literal でない（port 付き、空要素など）または header が過大。ALB の `xff_client_port.enabled=false` を確認する |
| `UNSUPPORTED_PROXY_CHAIN` | 右端 hop も trusted proxy の範囲内（多段 proxy）。trusted-proxies の範囲が広すぎないか確認する |
| `SOURCE_IP_INVALID` | Tomcat valve の捕捉値が無い。組込み Tomcat 以外での起動や valve 設定の欠落を疑う |

送信元判定は Tomcat valve が filter 前に捕捉した TCP peer と転送 header 原文だけを使う。servlet の `getRemoteAddr()` は
`ForwardedHeaderFilter` が client の書いた先頭値へ書き換えるため使わない。

## 4.1 inbound webhook 障害

| 症状 | 安全な初動 |
|---|---|
| 400 `REQUEST_INVALID` | Content-Type が単一 `application/json`（許可 charset のみ）か確認 |
| 403 `FORBIDDEN_SCOPE` | provider catalog、subscription、receive permission、tenant/legal scope intersection |
| 409 `INBOUND_PAYLOAD_CONFLICT` | 同一 provider event ID・別 payload。ledger は作成済み。手動修正しない |
| duplicate 200 | 正常。再送は同結果 |
| signature / timestamp 失敗 | credential rotation overlap（24h）と clock skew（±300s）を確認 |
| lease 滞留 | inbound の lease は `public-api.inbound-lease-seconds`（outbound の `external-transport.lease-seconds` とは独立） |

拒否系（400/403）は inbound ledger を作成しない。監査 DB の result code を確認する。
nonce は `max(受付時刻, 署名時刻) + 601 秒` まで保存し、±300 秒の受付窓の境界で purge 後に再受付されないようにしている。

## 5. outbound webhook / DLQ / manual replay

1. `t_api_delivery` の status / attempt_count / lease_expires_at を確認する。
2. worker crash 後は stale lease recovery（`recoverExpiredLeases`）で RETRYABLE へ戻る。transport 再試行は lease 満了後。
3. attempt 8 到達で DLQ。4xx は retry しない。
4. manual replay は authenticated internal admin + `integration.webhook.replay` permission が必要。
5. replay 直前に current scope / membership を再検証する。不一致なら replay せず terminal state を維持する。
6. replay 失敗時は DLQ を維持し、operator reference は server-side 導出値のみを audit へ記録する。
7. retention / nonce purge scheduler は inbound・outbound のどちらかが有効なら生成される（outbound だけの開放でも
   delivery payload 30/90 日と replay 監査 1 年を purge する）。両方向 OFF では生成されない（§7.3）。
8. outbound だけを開ける場合も `public-api.public-id-key`、`topology.bound-tenant-id`、法人 readiness が起動時に必須。
   `public-api.enabled` は false のままにする（true にすると DG-05-IN の承認項目が要求され起動しない）。

## 6. DB / worker / provider 停止

- 外部 HTTP は DB transaction 外。DB 停止中も in-flight lease は expiry 後に recovery 可能。
- provider 停止時: MOCK/STUB（無接続）または LOOPBACK（development/test のみ）。production は STUB/LOOPBACK を常に拒否し、
  outbound無効時はMOCK固定。本番outboundはHTTPSだけを許可し、承認不足・不正な専用truststoreで起動を拒否する。
- restore 後: `advanceRestoreEpoch` により purge checkpoint を invalid 化し、retention 対象を先頭から再評価する。
- legal hold 中は purge を停止する。hold 解除後に cursor reset で再 purge する。

## 7. key rotation / revoke / emergency stop

### 7.1 credential rotation（24h overlap）

1. 新 credential を issue する。旧 ACTIVE は OVERLAP（24h）へ。
2. overlap 中は旧 keyId / 新 keyId の双方で HMAC 検証可能。
3. overlap 終了後、旧世代は usable 検索から除外される。
4. secret平文は管理者向け発行応答で一度だけ表示する。再表示APIは無い。ログ・監査へ出力せず、DBはenvelope（IHG1）のみ保存。

### 7.2 revoke

- `CredentialVersionService.revoke` は version CAS。revoke 後は即座に usable から除外。
- 失効 credential での request は 401 `AUTHENTICATION_FAILED`。client へ詳細を返さない。

### 7.3 emergency stop（production）

1. `integration.hub.public-api.enabled=false`
2. `integration.hub.external-transport.enabled=false`
3. `integration.hub.provider.mode=MOCK`
4. 再起動後、deny-only chain が `/external-api/v1/**` を stable 404 JSON で返し、`t_external_api_audit` に行が増えないことを確認する。
5. external controller / worker / 送信scheduler / transportの停止を確認する。内部管理APIは管理者専用のまま。
   prodの停止操作はOFFへ変更して全停止・再起動する。実行中propertiesの変更とON/OFFの混在nodeは運用しない。
   OFF/ONの状態管理componentはOFFでも残り、既存m_system_configへ明示OFFを保存する。正常終了stopだけではOFFを書かない。
   必ず§13のOFF永続化成功を確認してからONへ戻す。OFFのDB書込が失敗した直後にONへ再起動すると、旧ON値からOFF経由を識別できない。
   初回の未登録状態でONを指定しても送信は未準備のまま。最初に同じbound-tenant-idを設定してOFFで配備・確認する。
   OFF→ONだけが高水位以前の旧PENDING/RETRYABLEと期限切れCLAIMEDを隔離する。ON→ONの通常配備/crash再起動はキューを維持する。
   未完了/DB/監査障害はアプリ全体の起動を妨げず、outboundだけ未準備とする。128件ずつ周期復旧し、4096件の起動拒否上限は廃止した。
6. inbound を止めた場合は LB/WAF の `/external-api/**` 遮断（§0 D-1）を戻す。片方向だけ止める場合は該当方向の
   `enabled=false` だけを変え、他方向の承認値は触らない。
7. 両方向 OFF では retention purge scheduler が生成されない。停止が長引く場合の payload・監査の扱いは DBA が判断する。

## 8. backup / restore 連携

- DB restore 後は Flyway 状態と `t_api_purge_checkpoint.restore_epoch` を確認する。
- retention purge は restore epoch 以降、checkpoint を信用せず全対象を再評価する。
- idempotency / inbound / delivery の terminal retention（succeeded 30d、failed/DLQ 90d、audit 1y）を維持する。

## 9. エスカレーション

| 重大度 | 条件 | 通知 |
|---|---|---|
| SEV-1 | production enablement 下で全 client 5xx | 60 分以内（IH-R5） |
| SEV-2 | DLQ 滞留 > 1h または inbound DLQ 連続増加 | 営業時間内 30 分 |
| SEV-3 | 単一 client 429 常態化 | 翌営業日 |

## 10. 参照

- Spec: `.kiro/specs/integration-hub-public-api/`
- 開放決定（草案）: `.kiro/specs/integration-hub-public-api/dg-05-enablement-decision-draft.md`
- Evidence: `.kiro/specs/integration-hub-public-api/evidence-index.md`
- Backup restore: `ops/backup/runbooks/restore-cutover.md`

## 11. G3の設定・署名・管理操作

詳細は `.kiro/specs/integration-hub-public-api/g3-implementation-and-runner.md`。HTTPS専用truststore、DNSとegress firewall、
TLSの失効確認/pinning判断、v2 audience署名の受信側互換性、管理APIとone-time secret、thread drainと残件処理を記載する。
WAFは正規化後pathも遮断し、encoded slash・dot segment・重複slashの迂回を実LBで検証する。FWD-SANITIZEは別batch。
prod trusted proxyはIPv4 /24以上・IPv6 /64以上、client許可CIDRと重複不可。AJP / RemoteIpValveは起動時に拒否する。
V192はUTC一年expiry、EXTERNAL_AUDIT hold/checkpoint付きの最大1000件purgeを追加する。独立Review・Runner・DG-05署名前はNO-GO。

## 12. G3rの再開と運用判断

- G3r当時の記録は `.kiro/specs/integration-hub-public-api/g3r-fix-and-runner.md`。再開/期限/lockの正本はG3s文書と本書§13。通常ON再起動はqueueを維持する。
- 証明書/hostname/peer/destination拒否はretryせずDLQ。TLS確認と設定修正後、管理者replay前のallow-list/handshake検証を通す。
  handshake probeはHTTPも署名secretも送らず、DB transaction外。結果不明の旧CLAIMEDは受信側と照合してからreplayする。
- 未送信確定retryは初代eventで作成から24時間、replayでは当該generationの不変audit作成から24時間でDLQ。元event/bodyは保ち、操作時刻で予算を無期限延長しない。
- credentialローテーション: 受信側へ新keyId/secretを安全に設定→新versionを発行→同endpointを新versionで再登録→新署名の受信確認→旧credential失効。
  24時間overlap内にoutbound subscriptionも必ず切り替える。同一clientでkeyIdを再使用しない（DB UNIQUE）。
- client 90日期限の自動延長はない。期限前に新clientIdでscope/credential/subscriptionを構築し、新しいopaque IDへの移行を受信側と調整する。
  旧credentialを失効して旧subscriptionを止め、旧client bindingは期限で無効になる。失効後のclientを直接SQLで延命しない。
- inbound/outboundの共用secretはcanonical用途分離付きで維持する。DG-05 OUT-6のSecurity受容が無ければ開放しない。
- FWD-SANITIZEのapp.security.trusted-proxiesとNF05のintegration.hub.security.trusted-proxiesを同じALB経路に合わせる。
  /20〜/22 subnetは承認済み複数/24へ分け、client CIDRとは重ねない。LBのsecurity groupとWAFの証跡を残す。
- pinのpeer集合は検証済み公開IPだけを120秒/host64件まで保持する。新しい接続は最新DNS回答へ限定する。
- 送信scheduler lockは最大5分。実dispatchは120秒経過後に次項を開始しないため、network上界は120秒＋最後の一項のDNS/connect/read/close。
  最大batch件数32は維持する。DB/quarantine/auditのlock待ち時間はこのnetwork予算で上限化しない。
  overlap時の安全はledger CAS/fenceが担い、SREはDB timeoutと実測を確認する。状態復旧schedulerは別の1分lockを使う。
- V192はDDLが暗黙commitする。既存dev/stagingのJVMがJSTだったことを確認し、部分失敗でUPDATEを再実行して二度九時間を引かない。
  配備前backupから戻すか、DBAが列・UTC変換済み値・index/check・Flyway履歴を照合して段階的に補完する。repairだけで先へ進まない。
  UTC列の運用比較にはUTC_TIMESTAMP()を使い、DB NOW()（+09:00）と混ぜない。

## 13. G3s：永続状態、再開と有界bulk replay

### 13.1 永続状態と配備

- 専用keyはm_system_configのintegration.hub.outbound.runtime.v1。値はv1／phase（OFF、QUARANTINING、ON）／高水位ID／bound-tenantのSHA-256を縦線で区切る。
  schema変更はない。これはruntime専用でsystem-config一覧から除外し、汎用設定APIの未知key拒否も維持する。DBから直接書き換えない。
- 状態遷移は「未登録→明示OFFの永続成功→OFF」「OFF→ON設定起動→QUARANTINING（境界固定）→旧active行ゼロ→ON」。
  ON→ON（stop/start、通常配備、crash再起動）はONのままでPENDING/RETRYABLEを継続する。QUARANTINING中のcrash後は同じ境界から再開する。
  OFFへの設定起動だけがOFFを記録する。OFFが観測できない未登録ONはMISSING_STATEで送信を停止する。
- OFF配備の時点から正しいintegration.hub.topology.bound-tenant-idを指定し、再開時にも同じ値を使う。fingerprint不一致はTENANT_STATE_MISMATCHで停止する。
- SREはON変更前に、当該DBの専用keyがOFFでcommit済みであることとNF05_OUTBOUND_OFFの監査を確認する。DB障害時のOFF確認失敗を無視してONへ進めない。
  旧ONが残ったままOFFプロセスを終了すると、その後のONは経由を検出できない。この不可観測の区間は運用gateで防ぐ。
- 境界はweb server前のSmartLifecycle phaseで固定する。早期DB障害時は準備未完了のまま起動し、周期復旧時に境界を取得する。
  この回復区間の新規eventも境界以下に含まれれば保守的に隔離される。外部送信の安全を優先し、必要なら条件付きbulk replayで戻す。
- 旧CLAIMEDの有効leaseは奪わない。leaseが終わるまで全outboundを未準備に保ち、期限後も旧snapshotはDLQへ隔離する。
  初期runner、5秒ごとの状態scheduler、送信dispatchの準備確認はいずれも一回128件まで。状態行の短いlock、delivery version CAS、required監査が同じtransaction。
  走査SELECTにFOR UPDATEは付けず、全隔離完了後は過去行走査を止める。隔離失敗・監査失敗はrollbackして次周期で再試行し、他の機能は起動・応答を続ける。
- 全停止配備とproviderのin-flight結果照合を維持する。混在ON/OFF node、他プロセスからのruntime値変更、汎用設定cacheを介した操作は対応しない。
  app.scheduling.enabled=falseでは周期復旧が動かない。drain解除後のscheduling有効化と復旧完了を確認する。

### 13.2 WARN、metricと通知gate

- integration_hub_startup_quarantine_pending=1が60秒継続、またはintegration_hub_outbound_state_events_totalのSTATE_IO_ERROR／MISSING_STATE／TENANT_STATE_MISMATCHが5分以内に増加したら、SRE当番へ即時通知する。
  5分継続またはQUARANTINE_PENDINGがlease最大時間を超えたらOwnerと運用管理者にもSEV-2として通知し、DB・監査・leaseを確認する。
- enabled=ON予定の配備でintegration_hub_outbound_ready=0が続く場合も同じ宛先へ通知する。OFF予定のready=0自体は異常ではない。
  OFF予定でもpending=1は永続化未確認なので、ON移行を禁止する。
- reason labelは上記4種だけ。metricはメモリのgate状態で、scrapeからDBの反復走査を行わない。WARNはcomponentごと最大1分間隔。
- 通知方式はPrometheus/Alertmanager等からSREの連絡先への運用alert。アプリからのアプリ内通知や実際の通知配送を本修正で実装・実行したとは扱わない。
  SREは宛先、routing、閾値、テスト通知、ack責任を設定して証拠化する。未接続のalertや未署名のOUT-7のままoutboundを開放しない。

### 13.3 管理者の条件付きbulk replay

- 既存rootのPOST /api/integration-hub/deliveries/bulk-replayを使う。管理者、既存integration-hub.update、CSRFを要求し、汎用allow-listは増やさない。
- bodyはtenantId（現在のtenantと完全一致）、subscriptionId、legalEntityId、from／until（UTC Instant、created_atの半開区間、最大31日）、
  failureClass、reasonCode（大文字の有限code、最大64文字）、limit（1〜20）。failureClassはSTARTUP_REVALIDATION_REQUIRED／TLS_CERTIFICATE／TLS_IDENTITY_REJECTED／PEER_REJECTED／DESTINATION_REJECTED／UNSENT_AGE_EXCEEDED／MAX_ATTEMPTSだけ。
  subscriptionと現在のclient/法人の境界をTLSより前に検査し、DLQの最新generationだけをid順で選ぶ。上位generationまたは既存replay監査がある元行は選ばない。
- 最初に条件・候補ID・requestIdのrequired要求監査を短い独立transactionでcommitする。ここで失敗すれば新generationはゼロ。
  以後は一件ずつ既存replayを呼び、現在権限・scope・帰属を再検証し、HTTPSではallow-list/DNSを検証し、同じ絶対期限内で検証済みaddressを順に試すTLS preflightをtransaction外で行う。最初のTLS成功で止め、全addressの成功は要求しない。
  HTTP本文やsecretはprobeに送らない。各成功は既存のrequired replay監査と同じtransactionで確定する。
- 一件の拒否・競合はREJECTEDとして次へ進み、既成功をrollbackしたとは返さない。selected/succeeded/rejected/deferredと元ID／新IDを確認する。
  120秒経過後は次項を開始しない。最大所要時間のnetwork部分は120秒＋最後のprobe総期限であり、厳密な120秒終了でもDB時間上限でもない。
- 完了監査が失敗しても要求監査と各成功のreplay監査は残る。completionAuditRecorded=falseとWARNを受けたらrequestIdで照合し、全件失敗と誤認して再投入しない。
  同じ条件を繰り返すと、再送済みの元DLQを除外して次の未処理へ進む。並行replayの敗者はgeneration/unique制約で拒否される。skipや拒否が続く元行は条件を絞って調査する。
- eventId、元createdAt、payload/hashはreplayでも変えない。未送信24時間期限だけは当該generationの不変replay audit.created_atから測る。
  updated_atを使って毎回期限を延長しない。元行がpurge済みなどでauditとのbindingを確認できなければ安全側でDLQに戻す。
  eventはat-least-onceかつ順序保証なし。同一秒の複数eventも並べ替えできないため、順序が必要な受信側はread APIで現在値を再取得する。
