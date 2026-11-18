# NF05 LOOPBACK総deadline修復 batch

状態: **READY_FOR_IMPLEMENTATION**。NF05-R-P2-01の限定修復であり、実provider導入・外部送信・
production enablementを承認しない。実装担当はgpt-6-luna、独立Reviewはgpt-6-astra。

## 隔離と変更範囲

- 最新fetch base: `1443fb68943849a3260d7ebe005259f3eb609d60`。
- 再利用worktree: `C:\work\ses-nf05-remediation`、branch: `codex/snf-prod-nf05-deadline`。
- 開始前clean、旧Headのorigin/main包含、当該path依存processなしを確認済み。
  旧branchを維持し、既存targetは`target-preserved-20261001-prod-start`へ保存した。
- 変更可: `LoopbackIntegrationHubWebhookTransport.java`、必要最小のdeadline helper、
  対応するintegrationhub transport/worker test、NF05限定証拠文書。
- 共通security/config、他NF、既存migration、実credential/実URLは変更禁止。新migrationなし。
  property契約の変更が必要なら互換性・lease関係を示して総括へ差分を連絡する。

## 必須動作

1. 総予算は既存connect/read timeout合計に整合させ、`System.nanoTime`等の単調時計で管理する。
   各readのSO_TIMEOUTだけをdeadlineとして扱わない。開始からconnect、write、header完了までを拘束する。
2. peer/port allow-list、DNS/proxy/redirect禁止、header上限、署名/idempotency keyを維持する。
   不要なresponse bodyのdrainを成功条件にしない。途中header/壊れたstatusを成功扱いしない。
3. writeが詰まる経路にも有限deadlineを適用し、期限後はsocket/resourceを確実に閉じる。
   無制限thread、放置future、requestごとに残るschedulerを導入しない。
4. deadline後の結果はTIMEOUT/retryableへ分類する。lease token/generationと結果CASを弱めない。
   timeout/HTTP成功はprovider副作用の有無を断定しない。再送は既存の安定idempotency keyを維持する。

## 検証とhandoff

- 許可済みloopback serverで、read timeout未満の周期で1 byteずつ送り続けるslow-drip headerを再現し、
  総deadline以内の有限終了を検証する。応答bodyを終えないserverでも不要な待機をしない。
- connect/read通常成功、429/5xx retry、redirect拒否、壊れたheader、resource解放を回帰する。
- stale workerが新lease/generationの結果を上書きしないことを既存worker/service CASと照合する。
  単にmockがfalseを返したことだけをDB並行の証拠にしない。
- 対象: `IntegrationHubLoopbackTransportIntegrationTest`、`IntegrationHubWebhookTransportBoundaryTest`、
  `IntegrationHubLoopbackEndpointGuardTest`、`IntegrationHubWebhookDeliveryWorkerTest`と追加回帰。
  当該hostはprocess限定`JAVA_TOOL_OPTIONS`へ`-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`を指定する。
- zero skipped、正確なrun別raw XML/log/hash、固定commitをAstraへ渡す。失敗runを成功証拠へ上書きしない。
- 限定CODE PASS後にcontrolへ統合し、固定RCの全Fast/MySQL/Browser/Performance/backup gateは別途実行する。

実transport、provider sandbox、契約/credential、Security/Operations/Production Ownerの承認は未完了のまま残る。
