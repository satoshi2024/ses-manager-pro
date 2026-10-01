# NF-05 Loopback 総 deadline 修復証跡

## 変更境界

対象は `LoopbackIntegrationHubWebhookTransport` のloopback送信期限だけである。接続、request write、応答header完了までを単調時計による一つの期限で拘束する。期限を超えた結果は `TIMEOUT` / retryable とし、provider副作用の有無を断定しない。応答bodyはdrainしない。

literal loopback peer/allow-list port、DNS・proxy・redirect防止、header上限、署名/idempotency header、worker lease/CASは維持する。実provider、credential、DDL、production enablementは含まない。

## 検証

- 作業branch: `codex/snf-prod-nf05-deadline`
- B0: `1443fb68943849a3260d7ebe005259f3eb609d60`
- 固定実装commit: `2187b5f9ebc7319c899b4074d54f2b8bf448eb3b`
- 検証したsource SHA-256: transport `0C2D24530EA0C5C0A1F3F5168A1F1B83E0E457FE465A645F4E99BD1D2FAC37EE`、integration test `E4F674B0F3F2641AA761F9B053AAFD1D52B01882FAA92978D3209FBDC61B7D46`
- 検証状態: **PASS**。final raw runは2026-10-02 01:41:23–01:42:26 JST、Git HEADはB0で作業tree source hashを上記固定した。
- 対象: `IntegrationHubLoopbackTransportIntegrationTest`, `IntegrationHubWebhookTransportBoundaryTest`, `IntegrationHubLoopbackEndpointGuardTest`, `IntegrationHubWebhookDeliveryWorkerTest`
- Maven: `apache-maven-3.9.6/bin/mvn.cmd test -Dtest=IntegrationHubLoopbackTransportIntegrationTest,IntegrationHubWebhookTransportBoundaryTest,IntegrationHubLoopbackEndpointGuardTest,IntegrationHubWebhookDeliveryWorkerTest`
- Java options: `-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`（process限定）
- test counts (tests/failures/errors/skips): **26/0/0/0**。class別tests/failures/errors/skipsはtransport integration 12/0/0/0、transport boundary 2/0/0/0、endpoint guard 2/0/0/0、delivery worker 10/0/0/0。
- final raw log: `target/verification-evidence/nf05-deadline/run-final/targeted-maven.log`、SHA-256 `1F8736CDF43C88181BEB94B28D591B0827FBD3B53E2C03E879ED51AB1E2D3FE6`
- final Surefire XML SHA-256: loopback integration `D4165DC3FF1D59E80C49F0EF8442FEB03592AF92CAB8AB0EDA0B4C3C56AF67CA`、worker `B4248EEBB2FB3B486A53933ABAF2D894C93C125D5CB4FB341EE710F26180B6D9`、endpoint guard `F36F3F78F7FEF92E30B2D9FC62521EB7A8B5DCEF5F89D88D1F9DBA3E61738818`、transport boundary `CD2C8F0863B8DA5218DA7EBCE6DE6E2B9C85002FBCC2DCBA28B6F4AFE7E1974B`
- final run時のsource SHA-256: transport `0C2D24530EA0C5C0A1F3F5168A1F1B83E0E457FE465A645F4E99BD1D2FAC37EE`、integration test `E4F674B0F3F2641AA761F9B053AAFD1D52B01882FAA92978D3209FBDC61B7D46`。検証後の最終tree hashはtransport `8635659E387129AA429889CA5F7D7CA9EA981EB40F2C384CEE239E8C84740508`、integration test `29729C624ADF8343D37117553A0BC8D7058B4A6387E883F5F52F5EC67F17B23F`。差分はexception文字列とcommentを日本語へ直しただけで、再実行していない。
- `git diff --check`: PASS。
- 修正runも保存済み。run-1はtests/failures/errors/skips `26/1/0/0`（context fixtureで必須`public-api.enabled`設定漏れ）、run-2は`26/1/0/0`（bean名を固定したcontext assertionが実際のbean名を誤判定）。詳細はignored raw log/XMLに保存し、final runと混算しない。

## 制約と残作業

peerがrequestを読まない回帰は送信attempt全体のbounded終了を確認するが、OSのsocket buffer状態に依存するためOP_WRITE待機そのものを動的に証明したとは扱わない。注入時計とphase hookにより期限後のwriteを防止し、serverが202を返した後でも期限切れを成功扱いしないことを実HTTPで検証した。実送信provider、sandbox契約、production運用承認は未完了である。
