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

## 次のgate

1. 2 classのfixtureを修復し、対象限定5件を正しい環境で実行、固定HeadをAstraへ提示する。
2. 上記4 classのloopback試験を明示socket directoryで再実行する。単に環境原因と分類してPASSにはしない。
3. 原因修復と独立Review後の統合Headで全fastを再実行し、raw evidenceを別directoryへ保存する。
4. MySQL/Performance/Browser/backup/staging/production gateは本runで代替しない。

実行時は既存の必要JVM optionを維持し、当該shell/processにのみpropertyを追加する。
全体環境やOS設定は変更しない。CIのLinux等にWindows固有pathを埋め込まない。
