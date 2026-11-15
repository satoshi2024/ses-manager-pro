# G3s：永続再開状態と条件付きbulk replay（未承認）

起点5a9572b8。G3r-P2-1とG3r-P3-1〜8の修正。default OFF+MOCK、DG-05未署名、Production NO-GOは維持する。

## 永続化と状態遷移

既存m_system_configの専用key integration.hub.outbound.runtime.v1を使い、DDLは追加しない。
値はv1|phase|highWater|tenantFingerprint。fingerprintは設定したbound-tenant-idのSHA-256であり、tenant名や秘密値をmetric labelへ出さない。
SystemConfigApiControllerから専用keyを除外し、汎用serviceの既存unknown-key拒否は変更しない。
この一項のcontroller保護は、全行保存UIが未知keyを返して保存全体を失敗させることを防ぐ必要な統合である。

状態図（文字）：

- 未登録 + ON → 未準備/MISSING_STATE（状態を推測しない）
- 未登録または既存状態 + 明示OFFの設定起動 → required監査と同一commitでOFF
- 永続OFF + ONの設定起動 → 高水位を一度固定してQUARANTINING
- QUARANTINING + 一batch成功 → 同じ境界のまま継続
- QUARANTINING + 旧PENDING/RETRYABLE/CLAIMEDがゼロ → required監査と同一commitでON、送信準備完了
- QUARANTINING + DB/監査失敗 → rollback、未準備のまま5秒周期/dispatchで再試行
- QUARANTINING + crash/再起動 → 保存済み境界から再開
- ON + ONの配備/crash再起動/stop-start → ON、通常queueを維持、at-least-once
- stop → ローカルgateのみ閉じる。ONをOFFへ書き換えない

初回配備は必ずOFF。同じbound-tenant-idを設定し、OFF行と監査のcommit成功をSREが確認した後だけONへ進む。
OFF記録に失敗したまま終了して旧ONが残る場合、次のONからOFF経由は観測できない。これを実装で検出済みとは言わず、開放gateへ明記する。
SmartLifecycleの最小phaseでweb server前に境界を確定する。早期DB障害後に遅れて復旧した場合は、その間の新行も境界に含まれて隔離され得る。
全停止配備、旧in-flightの結果照合、同じDBにON/OFF nodeを混在させないことが前提。汎用設定APIやSQLからruntime値を直接変更しない。

## 隔離とアプリ可用性

runnerは最大一batch128件だけを進め、4096件の起動拒否を廃止する。件数超過やDB/required audit障害でアプリ全体を落とさない。
未準備時はworkerを送信させず、独立した状態schedulerが5秒ごとに一batch継続する。OFF時も状態記録失敗を再試行できる。
app.scheduling.enabled=falseでは周期実行しない。状態schedulerのShedLockは1分、状態行の短いFOR UPDATEとdelivery version CASも併用する。
隔離候補のSELECTは非locking。有効leaseは奪わず、旧active行がなくなるまで未準備を保ち、完了後の過去行走査を止める。
WARNは最大1分間隔、有限reason counterとメモリgaugeで示す。Prometheus→SRE当番/Ownerの閾値・配送確認はrunbook §13とOUT-7のgate。
通知サービスを呼んだ/通知を配送したとは扱わない。transaction timeoutは5秒を要求するが、DB接続待ち等の全wall clock上限は別途SREが検証する。

## bulk replayと監査

既存integration-hub root、管理者/既存action/CSRFを使い、現在tenant、subscription/法人、UTC期間最大31日、有限failureClass、reason、limit1〜20を要求する。
最新DLQ generationかつ次generation監査のない行だけをid順に選ぶ。旧元行はDLQのまま残るが、再送済みの元を繰返し選ばない。
要求の条件/候補ID/requestIdをrequired監査で先にcommitする。失敗時は新generationゼロ。
その後は既存management.replayを一項ずつ呼び、現行tenant/法人/scope/ownershipとallow-list/DNS/TLSを再確認する。TLSはDB transaction外、payload/secretは送信しない。
成功は各replay auditと同一transaction。競合/拒否は固定REJECTEDで次項へ進む。120秒経過後は次項を開始せずdeferredにする。
これは120秒＋最後のprobe総期限までのnetwork予算であり、DB時間を含む厳密な120秒終了保証ではない。
完了監査が失敗した時も要求監査/各成功のreplay auditは残り、completionAuditRecorded=falseを返す。既成功をrollbackしたように返さない。
繰返し拒否される行は再試行条件を絞って調べる。並行replayは既存generation/unique制約の敗者が拒否される。

## P3対応表

| ID | 対応/残る条件 |
|---|---|
| G3r-P3-1 | 隔離SELECTのFOR UPDATEを除去。CASと状態行lockで整合し、旧activeゼロ後の周期走査を停止 |
| G3r-P3-2 | early SmartLifecycleで境界確定。早期DB故障の遅延復旧時には新行も保守的隔離され得る限界を明記 |
| G3r-P3-3 | 不変replay audit.created_atをgeneration開始時刻として24hを測る。元event/body/idempotency規則は維持しupdated_atで無期限延長しない。元行purgeなどbinding不明時は安全側DLQ |
| G3r-P3-4 | 順序保証や新sequence列は追加せず、必要な受信側はread APIで現在値を再取得する契約を明記。DDL/外部schema変更を避ける |
| G3r-P3-5 | 送信lock5分へ戻し、実dispatchは単調clockで120秒を超えて次項を開始しない。最大一項のnetwork余量を検証し、DB待ち/CAS/SRE実測の限界を明記 |
| G3r-P3-6 | MANDATORYへ戻し実proxyのtransaction外呼出し拒否を検証。唯一の本番advisorはTransactionTemplate内で呼ぶ。OFF時のproxy外transaction契約は緩めず、new()のno-op試験を伝播証明としない |
| G3r-P3-7 | TENANT_MISMATCHをWARN/有限counterにしてeventをskipし業務を継続。event欠落と復旧責任はOwner署名条件 |
| G3r-P3-8 | preflightは検証済みIPを同一絶対期限内で順に試し、最初のTLS成功で終了する。DNS120s/64 addressの安全側拒否と管理DNSはSRE条件。旧G3の残り3ownership図のMySQL証拠は本修正では広げない |

## テストとRunner

- 実H2状態試験：ON再起動/stop-start、初回未知ON、OFF→ON/crash復旧の固定境界、有効lease、4097行、一周期128、audit失敗のrollback、scheduler復旧。
- 実SpringBoot起動：状態DB障害時も起動し、未準備のworkerが送らずloginが200で応答する。
- 実producer/MVC：既存原子commit/rollback、小数秒保存、MANDATORY実proxy、system-config隠蔽/拒否/通常保存、bulkのCSRF/role/条件/件数上限/三回の連続進展/各監査、実replay-window JOIN。
- unit：bulk partial/audit失敗/予算、worker新generation起点と開始予算、tenant mismatch skip。既存ローカルTLSは先頭IP失敗後のfallbackを加えて再実行する。
- 既存shard-3 IntegrationHubV192AuditRetentionMySqlTestへ状態/新SELECTの用例を追加する。実SQL/mapper/TMとAuditLogServiceImplを使うが手動構成であり、実Spring proxyの証拠はH2側と分ける。本作業ではMySQL未実行。
- 正式Runnerは最終SHAのfast/全体guard、mysql shard-3、実Tomcat/connectorでphase・proxy・queueを確認する。
  旧G3-P3-9の3図MySQL、expired/TLS1.1実wire、外部ネットワークpeerの正式証拠とG3-P3-13の承認digest/CI gateは引き続き別批次で必要。禁止実行と未記述の試験コードを混同しない。
