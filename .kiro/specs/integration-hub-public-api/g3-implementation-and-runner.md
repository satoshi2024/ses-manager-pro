# G3実装とRunner引継ぎ（2026-11-14、未承認）

起点はb7da8299。#108 TLS候補を継承した状態からproduction専用HTTPS実装を追加した。
候補のTestHooks / ExchangeExecutor / PeerObserverは移植していない。prod既定の両方向OFF+MOCK、
方向別DG-05の未署名状態とProduction NO-GOは維持する。実装は開放承認と同義ではない。

## 実装した境界

- inboundは転送headerの複数行を拒否し、decisionを完全一致で有限metricへ分類する。
  HMAC検証後のCLIENT_IP_NOT_ALLOWED / NONCE_REPLAYを含むrejectと5xxは監査予算を適用しない。
  prodはX_FORWARDED_FORのみ、trusted proxyはIPv4 /24以上・IPv6 /64以上、client許可CIDRとの重複を拒否する。
  有効時はAJP connectorとRemoteIpValveを起動時に拒否する。全機能向けFWD-SANITIZEは別batch。
- V192で外部監査の旧JST created_atをUTCへ一度変換し、一年後のexpiryと索引を追加する。
  EXTERNAL_AUDITを既存hold/checkpointへ統合し、最大1000件のkeyset purgeを使う。
  H2の二経路と最新version guardを192へ同期した。V189〜191は本worktreeの意図的な空号であり、統合側で調整する。
- HTTPSは専用PKCS12/JKS truststoreのみを信頼する。TLS1.3/1.2、AEAD cipher、public suffix対応hostname検証、
  証明書期限切れ・未到来・失効・未信頼・不正・hostname拒否を恒久失敗へ分離する。
  TLS1.2 cipherはECDHE_RSA / ECDHE_ECDSAのAES-GCM、TLS1.3はAES-GCM / ChaCha20。
- 小文字FQDN:明示portだけを登録時と毎回の送信時に検証する。全DNS回答をNonGlobalAddressPolicyで検査し、
  HC5へ検証済みIPだけを渡す。ClientHello前とhandshake後に実peerを照合する。redirect / proxy / cookie / auth cache / 自動retryは無効。
  並行したDNS更新で一致しない場合も拒否する。DNSの既定期限2秒、専用2threadと待機8件で上限を設け、
  OS resolverが割込みを無視してもthread数を増やさない。DNS_UNAVAILABLEは未送信retryとする。
- headerはworkerの9個だけを許可し、User-AgentはSES-Integration-Hub/1固定。prodのHC5 wire/headers loggerはOFF。
  claim時の仮attemptは、未送信確定結果だけを結果CAS内で返却する。通信切断・timeout・crash等の不確定結果は消費を維持する。
  leaseはDNS+connect+read+closeの2秒を含む。動作中kill switchは取得行をKILL_SWITCHのDLQへ移す。
  OFF再起動ではworkerが無く、未取得PENDINGは停止したまま残るため、DBAが残件を確認する。
- 既存内部root /api/integration-hubへ管理APIを追加し、管理者・baseline+deny・tenant・明示法人をserviceでも照合する。
  clients、client-scopes、credentials、subscriptionsの順に登録する。発行secretは発行応答だけへ一度返し、再表示しない。
  credential失効は署名secret複製を持つsubscriptionも同一transactionで失効する。replayは既存current scope/graph認可と必須監査を通す。
- producerは稼働・案件・契約状態・請求状態のresource.changedを明示したservice更新と同一transactionでenqueueする。
  送信直前にも現在のclient / permission / subscription / tenant / 法人 / resource graphを再検証する。
  delivery/inbound gaugeは各6状態、status以外のlabelを付けず、DB障害はNaNを返す。

## 署名v2と受信側の条件

HTTPSだけはX-Integration-Hub-Signatureをv2=へ切り替える。MOCK等の既存v1は互換性を維持する。
canonicalはASCIIのIH-WEBHOOK-2とLF、UTF-8 byte長:audienceとLF、byte長:v1 canonical bytesとLF。
audienceは登録した完全HTTPS URLのURI.toASCIIString()であり、明示portとpathを含む。
受信側は設定済みURLとの一致を必須にし、別endpointの署名を受け入れない。
受信側の相互運用試験とv1を本番で許可しないことをDG-05-OUTの署名条件へ追加する。

## 未充足の判断・実行証跡

- OCSP/CRLの強制取得とleaf/SPKI pinningは未実装。専用CA truststore方式をSecurityが明示承認するか、必要な追加実装を決める。
- SREはtruststoreの出所・更新・失効対応・read-only配置、管理DNS、承認先portだけのegress firewall、
  metadata/private/特殊用途宛てのネットワーク拒否を用意する。実ネットワーク・ALB/WAFの証跡はローカルfakeの代替にならない。
- service外の直接mapper更新、bulk/import、削除と未登録イベントはproducer対象外。
  changedFieldNamesは通知する公開snapshotのfield集合であり、値の差分検出は未実装。Ownerが通知契約と対象範囲を判断する。
- 上記実装の独立Review、主控Runner、DG-05全署名、安全なsecret配布、DPA・顧客/provider書面許可・予算が完了するまでNO-GO。

## Runnerへ渡す検証

fastは変更したsource IP / metric / audit / production policy / purge condition / purge scheduler / worker / signerと、
新規endpoint guard / 実HC5ローカルTLS / 管理service / UTC監査purge H2 / producer transaction / state gaugeを実行する。
ProductionSchemaVersionGuardTest、SchedulingDisabledDrainTest、TestIsolationAuditTest、MySqlTestShardInventoryTest、
権限・i18n・external API inventoryの全体guardも統合Runnerで実行する。
MySQLはshard-3のIntegrationHubV192AuditRetentionMySqlTest（migrationと明示化したpoint lockの並行claim）を含める。
実Tomcat enabled connector E2E、既存TLS候補のregressionもRunner対象とする。MySQL/Docker/browserはG3では実行しない。

### v2相互運用用の合成golden vector

bodyはUTF-8の{}、eventId=event-1、eventType=resource.changed、schemaVersion=v1、createdAt=2026-08-31T12:00:00 UTC、
correlationId=correlation-000001、timestamp=1756520000、credentialVersion=1、keyId=key-1、
idempotencyKeyはevent-1|7|1のSHA-256 hex、payloadHashはbodyのSHA-256 hex。
audience=https://one.example.com:443/hook、合成secret=synthetic-secretの場合、
署名headerはv2=PVmNCutAxOP_sZyS62RLhTpRPlGLI9ujfdDEoCilkX8。
Node cryptoによる独立計算でも既存v1 vectorと一致したframingを使用し、JavaのSignerTestでv2を固定する。実credentialではない。
