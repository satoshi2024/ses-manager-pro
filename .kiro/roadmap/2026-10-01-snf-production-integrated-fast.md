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

## 第2回: FAIL（2026-10-02 JST）

- 固定Head: `deb43f3cb2ca5c7674b871f7e5ad0d46d30f0200`。開始時clean、終了Headも一致。
- command: `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test`。
  process限定で`JAVA_TOOL_OPTIONS`へ短いsocket directoryを設定した。
- 開始UTC `2026-10-01T15:07:04.1447343Z`、終了UTC `2026-10-01T15:21:55.1751550Z`。
- raw XML 665 class、**4069 tests / failures 0 / errors 5 / skipped 0**、exit 1、Maven 14:49。
- 5 errorは全て`OvertimeComplianceWiringTest.setUp:81`。
  `t_engineer_account_link`へ新規engineer ID 4〜8を挿入する際に既存unique keyと衝突した。
  このrunでは第1回のfixture/socket errorは再現しなかったが、全fastの判定はFAILのまま。
- log、run/result metadata、全raw XML/text、class集計、失敗一覧、report SHA-256 manifestを
  `verification-evidence/20261002-control-fast-02/`へ保存した。旧target reportは別subdirectoryに保存し、
  今回の665 class集計へ混ぜていない。
- 修復は`C:\work\ses-nf02-remediation` / `codex/snf-prod-fast-isolation`へ隔離。
  再fetch base B0を確認し、審査済み依存を上記固定Headまでfast-forwardした。
  旧browser branchとtarget証拠を保存し、今回の原因と正確なfixture ownershipを調査する。
- unique制約削除、他fixtureのlink削除、test skip、順序固定による隠蔽は禁止。
  固定Headの限定回帰＋Astra独立Reviewを経て統合し、その後全fastを再実行する。

### Q0修復と限定Review

- fixed source `b17758127b102d132ed044e40e07a40d1cca09f6`、control `198ee8e5`へ統合。
- 未修復の2 classでPortal 7件の後にOvertime 5 errorを再現した。Portalが共有H2へ残すaccount linkと、次contextによるengineer ID再採番の衝突だった。
- Portalは専用H2・`DB_CLOSE_DELAY=0`・既存DirtiesContextで所有範囲を限定。Overtimeはseed user 1の変更をやめ、自己所有のactive userを作成する。製品コード・unique制約・assertionは変更しない。
- 元seed、逆順seed、最終ファイル態の元seedはそれぞれ12/0/0/0。別runを合算せず、全fastのPASSへ読み替えない。
- Astraがdiff、既存AsyncConfigのshutdown待機、pool終了、4 logと最終2 XMLのhashを照合し限定CODE PASS。新規findingなし。
- 詳細は`.kiro/evidence/20261002-fast-engineer-account-link-isolation.md`。rawはsource worktreeの`verification-evidence/20261002-fast-isolation/`に保存。第3回全fastは未実行。

## 第3回: FAIL（2026-10-02 JST）

- fixed Head `c9bd265555f2197784f2728a50ed7468bab1f853`。Q0とNF02限定審査済み修復を含む。開始clean、終了Head一致。
- `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test`、process限定socket directory指定。
- UTC `2026-10-01T16:16:09.7232635Z`〜`2026-10-01T16:31:19.8062943Z`、exit 1、Maven 15:08。
- **4075 tests / 1 failure / 0 errors / 0 skipped、667 class XML**。第2回のaccount-link 5 errorは解消したが、全fastはFAILのまま。
- 唯一のfailureは`PortalBpApiTest.空き要員はreview後に有効化されreview前は内部候補に出ない:266`。最初の`POST /api/portal/bp/availabilities`が期待200に対して403だった。法人contextの一意性と共有fixture状態を調査するが、現時点では根因未確定。
- raw全XML/text、全log、preflight/run/result、class totals、failures、report SHA-256 manifestは`verification-evidence/20261002-control-fast-03/`へ保存した。旧target reportはpreexisting-reportsへ分離済み。途中集計の成功を最終PASSにしない。
- 修復後は限定再現/独立Reviewと第4回全fastを必要とする。MySQL/Performance/Browser/backup gateは別途未完。
