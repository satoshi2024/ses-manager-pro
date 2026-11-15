# G3rの修正方針とRunner条件（2026-11-15、未承認）

対象はG3 a2186394の独立Review。prod既定OFF+MOCK、DG-05未署名、Production NO-GOを維持する。

## P1 / P2

- P1-1: 既存のInfrastructureAdvisorAutoProxyCreatorに合わせてproducer advisorをROLE_INFRASTRUCTUREへ登録する。
  四つの業務serviceへ別々の呼出しを追加する方法は採用せず、既存の一つのtransaction境界を維持する。
  実containerのSpringBootTestでadvisor適用、業務とledgerのcommit、障害後の別transactionからのrollbackを検証する。
- P1-2: producerはUTCの秒へ切り捨て、createdAtはISO_INSTANT。enqueueは小数秒を拒否する。
  replayは元の不変createdAt/body/idempotency generation規則を維持し、操作時刻だけを秒へ正規化する。
  小数秒ClockのH2往復とMySQL shard-3のDATETIME(0)往復を別々の証拠として扱う。
- P2-1: ON起動でID高水位を固定する。初期化完了前はschedulerからも直接dispatchOneからも送信しない。
  高水位以前のPENDING/RETRYABLEと期限切れCLAIMEDを128件単位でDLQへ隔離し、同じtransactionで領域監査を書く。
  有効leaseは奪わず、期限後に同じ経路へ収束させる。古いIDは回復後も自動送信しない。
  起動時は最大32batchで超過時はfail-closed。通常のON再起動も保守的に再確認対象とし、管理者replayだけが新IDを作れる。
  複数nodeの開放・停止は全停止配備とし、旧processのin-flight HTTP完了/中断を確認してから再開する。
- P2-2: OFFではproducerがtenant/client/lockへ触らない。登録時にtenant全体256件上限と全数値dimensionを検証する。
  登録だけがtenantの最古client行を共通mutexとして使い、producerのclient readは非lockingとする。
  既存DBの不正scope/過大subscriptionはboundedにskipし、固定reasonのWARN/counterで可視化する。
  tenant不明もNO_TENANTとしてskipする。業務継続を優先した結果、event欠落を許容する選択であり、Ownerの明示承認が必要。
  enqueueの永続化障害は引き続き業務更新をrollbackする。
- P2-3: 証明書/hostname/peer/destinationの恒久障害はDLQ。replay前に現行scopeと宛先を検証し、
  DB transaction外でTLS handshakeだけを行う。HTTP payload・署名secretは送らない。FAILEDにも即時alertを設ける。

## P3対応表

| ID | 対応 |
|---|---|
| 1 | 未送信確定のretryもevent作成から24時間でDLQ。RETRYABLEの無期限増加を防ぐ |
| 2 | peer判定は120秒・hostごと64 addressまでの検証済み集合を保持。DNS接続先は最新snapshotだけを使い、並行検証で旧peerを消さない。上限超過/期限切れは拒否。古い公開IPを120秒許容する取引をSecurityが承認する |
| 3 | outbound / scheduler / producer / snapshot purgeのBoolean条件をBinderへ統一 |
| 4 | usable credentialをversion降順で選択。client×keyIdの既存UNIQUEを維持し、旧keyId再使用を認めない。ローテーションは受信側準備→新credential→subscription再登録→確認→旧版失効 |
| 5 | clientの90日期限を延長するAPIは追加しない。期限前に新clientIdを登録し、scope/credential/subscriptionを移行して旧credentialを失効させる（旧client bindingは期限到来で無効になる）管理された手順を採用 |
| 6 | inbound HMACとoutbound署名の同一secret利用はcanonical用途分離付きで現状維持。DG-05 OUT-6へ明示し、Securityの受容が無ければ別鍵発行を追加する |
| 7 | いずれか一方向ONでのみgaugeを登録、12状態のsnapshotを30秒cache。status先頭の既存索引を利用し、scrapeごとのDB反復を抑える |
| 8 | V192の列COMMENTを保持。DDL暗黙commitとJST→UTCの一度だけ変換、部分失敗からの復旧をrunbookへ明記。version/件数は統合担当が調整 |
| 9 | 実container、秒精度、再起動、管理MVC等を補強。MySQL/実Tomcat/browserは主控Runner。公開IPの本物egress peerを含む実ネットワーク証拠は外部接続禁止の本作業では作らない |
| 10 | secret scanにoutbound管理controllerを追加 |
| 11 | prodコメント、kill説明、証拠の位置と限界を更新 |
| 12 | FWD-SANITIZEのapp.security.trusted-proxiesとNF05側を同じALB経路へ合わせる。/20〜/22 subnetは承認済みの複数/24で覆い、client CIDRとは分離 |
| 13 | 決定IDと承認値digestの結合、および実connector XFF E2EのCI gate化は別batch。既存承認・署名を迂回しない |
| 14 | lock最大40分と最大batch総期限を整合させる。protocol/cipher不一致のhandshake失敗は最大8回のretry後にDLQであり、設定不良の事前確認はSREの署名条件 |
| 15 | ISO時刻、宛先判定を一箇所へ統一。対象service以外・差分でないchangedFieldNames・管理list APIなし・未検証client_idを保存しない方針は維持して明記 |

## 配備・運用で確認する事項

- 起動ごとの隔離は外部送信の重複防止を優先する。旧CLAIMEDの結果不明を照合せず新generationで再送しない。
- replay TLS probeは4件までを受け入れ、deadline timerも4件以下、専用daemon一つ。DNS+connect+readの絶対期限で
  raw socketをcloseし、trickle handshakeも期限を超えて継続させない。closeでtimerを停止し2秒まで待つ。
- 専用truststoreの出所・更新と再起動、OCSP/CRL/pinning無しの選択、管理DNSとegress firewall、120秒pin graceをSecurity/SREが判断する。
- 外部監査時刻はUTC。運用SQLはUTC_TIMESTAMP()を使い、DBのNOW()（+09:00）をそのまま比較しない。
  V192途中失敗時は再UPDATE/repairで九時間を二重に引かない。配備前backupへ戻すか、DBAが各列/履歴/index/checkの段階を照合して手動補完後に検証する。
- 正式証拠は統合候補の最終SHAでRunnerが取得する。ローカルFASTだけでDG-05を署名済みとは扱わない。

### Runner追加確認

- 既存の `IntegrationHubV192AuditRetentionMySqlTest` は、V192変換・二接続claimに加え、小数秒Clockの実producerを実MyBatis mapperへ通すDATETIME(0)往復を検証する。保存後のsend bindingと移管後のownership拒否も同じ用例で確認する。本作業ではMySQLを実行していない。
- H2の実Spring容器は独立DBを使い、業務commitとenqueue障害のrollbackを終了後の新transactionで再読する。小数秒の本番producer、MOCK worker、起動時の旧PENDING/RETRYABLE/有効lease/期限切れlease隔離、管理APIのCSRF/roleを検証する。
- replay用TLS確認はHTTP要求を送らず、接続4本・deadline executor1本に制限する。小刻みな未完了TLS応答でもDNS+connect+readの総期限でsocketを閉じ、context終了時も実行中socketを閉じる。
- scheduler lockは40分のため、crash時の引継ぎに最大40分を要する。network batchの式はDB・隔離監査のlock待ちを上限化しない。CAS fencingとSREの実測・監視を引き続き必須とする。
