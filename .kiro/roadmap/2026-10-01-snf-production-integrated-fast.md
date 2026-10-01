# 統合fast gateの実行記録

## 第1回: FAIL

- 固定Head: `9ce0c4dfd4eaceebe5cd29f5e91565d7ffad2efa`、開始時clean。
- command: Maven 3.9.6 / Java 21.0.12.1で`mvn -B test`。
- 開始UTC: `2026-10-01T14:12:02.6911858Z`、終了UTC: `2026-10-01T14:27:22.8331732Z`。
- Surefire random seed: `48992185070100`。
- 報告結果: **4021 tests / failures 0 / errors 12 / skipped 0**、658 class report、exit 1。
- 判定はFAIL。class初期化errorで未到達の試験をPASSやskipへ読み替えない。
- raw XML/text、全log、開始終了metadata、失敗一覧、SHA-256 manifestは
  `verification-evidence/20261001-control-fast-01/`へコピーして保存した。Git ignore対象、旧証拠の上書きなし。

## 失敗分類と修復

| 対象 | errors | 根拠・対応 |
|---|---:|---|
| LifecycleNotificationAndSlaTest | 2 | fixtureのEngineerにtenant未指定。新しいtenant境界によりcreateCaseで拒否。合法な明示tenant/active actor fixtureへ修復中 |
| LifecycleEvidenceAndCompensationTest | 3 | 同じくsetUpのcreateCaseでengineerNotFound。document/cancel検証に到達するようfixtureを修復する。製品側認可は緩和しない |
| PinningHttpsTransportTest | 1 | UnixDomainSockets / loopback初期化error |
| PrometheusScraperLabE2ETest | 1 | embedded Tomcat開始失敗のcauseが同じUnixDomainSockets error |
| CapacityBaselineScriptTest | 3 | 同じUnixDomainSockets / loopback初期化error |
| WebhookNotifierLoopbackIntegrationTest | 2 | 同じUnixDomainSockets / loopback初期化error |

本runには以前の限定試験で使用した`jdk.net.unixdomain.tmpdir`の明示指定がなかった。
総括が同じhost/Javaで最小`Selector.open()`診断を実行したところ、default設定は
`Invalid argument: connect`でexit 1、`-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`指定はexit 0だった。
これでsocket directory設定による再現差を確認したが、Windows内部のpath仮想化等の根因までは断定しない。

このpropertyの用途は[OpenJDK networking properties](https://cr.openjdk.org/~hannesw/release-selector/api.00/jdk17/java.base/java/net/doc-files/net-properties.html)に記載され、
[OpenJDK nio-devのWindows議論](https://mail.openjdk.org/pipermail/nio-dev/2023-March/013297.html)でも別directoryによる診断が示されている。
実際のhostにおける上記の成功/失敗は、本runとは別の最小診断による観測である。

## 限定再検証（全体PASSとは別）

- NF01 fixture修復Head `e3944774e667a226848d3883e7333ddb8b2ef1f6`は対象2 class、
  **5/0/0/0**。Astraが製品側認可・document/cancel断言を緩和していないことを確認しCODE PASS。
  controlへ`e90daaf1`として統合した。
- loopback対象4 classは固定Head `e3ca3ae0eda203e568b463b331cfc2a9fcbae349`で
  `jdk.net.unixdomain.tmpdir=C:\work\jdk-uds`を指定して再実行、**12/0/0/0、exit 0**。
  開始UTC `2026-10-01T14:34:37.1297892Z`、終了UTC `2026-10-01T14:36:10.0248126Z`。
  PinningHttpsTransportTest 4、PrometheusScraperLabE2ETest 1、CapacityBaselineScriptTest 5、
  WebhookNotifierLoopbackIntegrationTest 2。該当4 XML/textだけをlog/metadata/hashと共に
  `verification-evidence/20261001-control-loopback-02/`へ保存した。
- これらの限定成功を足して第1回全fastをPASSへ変更しない。後続実装を統合した固定Headで
  全fastを再実行する。現在のtarget内XMLは複数runが混在するため全件合算禁止。

## 次のgate

1. fixture修復・Astra CODE PASS・統合を完了した。全fast内での再検証は残る。
2. 上記4 classのloopback限定再検証は成功した。全fast内での再検証は残る。
3. 原因修復と独立Review後の統合Headで全fastを再実行し、raw evidenceを別directoryへ保存する。
4. MySQL/Performance/Browser/backup/staging/production gateは本runで代替しない。

実行時は既存の必要JVM optionを維持し、当該shell/processにのみpropertyを追加する。
全体環境やOS設定は変更しない。CIのLinux等にWindows固有pathを埋め込まない。
