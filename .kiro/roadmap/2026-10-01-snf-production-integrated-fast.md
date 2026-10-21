# 統合fast gateの実行記録

## 最新: 第7回 PASS（2026-10-21）

- 固定Head `6b5415a6fd7311f6ff60e5571d8063f8b33e7b20`、起止SHA一致・clean、exit0。
- selectorなしの `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test`。
- UTC `2026-10-21T01:07:46.4160930Z`〜`2026-10-21T01:24:11.1903463Z`。
- **694 class、4232 tests / failures 0 / errors 0 / skipped 0**。
- `verification-evidence/20261021-control-fast-07/` にraw log/XML/text、起止metadata、集計、manifestを保存。
  log SHA-256 `799FC727FF31B730CEBDE2897C53DDFD713F5BCE64474BEEF6363DC357456CDA`。
- Astraが694 XML、1388 report hash、起止SHA/clean、selectorなしを独立照合してfast証拠PASS。
- NF06 wave2 append/renew修復はこのHeadに未統合であり、本runの対象ではない。
  MySQL/performance/browser/backup、staging、production承認をこの結果で代替しない。全体NO-GOを維持する。

## 履歴: 第6回 FAIL（2026-10-16）

- 固定Head `e09595c883afe62518281ac9a85f2343cbf716cb`、開始終了clean・SHA一致。
- selectorなしの `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test`。
- UTC05:38:46.2440343〜05:53:52.0586530、exit1、692 class **4210/2/1/0**。
- AstraはFAIL証拠として受理。Maven集計は4209件、XMLは4210件であり、offboardingの反復Run 2: PASSを
  伴う集計口径差を保持する。上記はXML口径、両口径ともfailure/error/skippedは2/1/0。
- `verification-evidence/20261016-control-fast-06/` に全証拠を保存。1384 report hash一致。
  log SHA-256 `0C2B7A2B2FAC7178BA5D025434749A929475A030867A49F4E51ADE8E31D981E8`。
- IntegrationHubMPenetrationTest: readiness前提による1 failure/1 error。
  ExternalAccountFailClosedOffboardingIntegrationTest: poll後UNKNOWN期待に対しPENDING_CONFIRMATIONの1 failure。
- H2再構築修復は本runに包含され、前回の連鎖errorは非再現。残留fixture修復source `f1d57af2` は
  Astra限定CODE PASS、control `f1ddc635` へ統合済みだが、本runの結果は修復後の証拠ではない。
  再検証が終わるまで最新全fastをPASSへ変更しない。

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

### Q1 テスト所有範囲の隔離と限定Review

- source `32dc10ce4ad0b1ce8588caf0119ac2d28b1c4841`、Astra限定CODE PASS（P0/P1/P2=0/0/0）、
  control `98ac6172`へ統合。変更はExpense/OneOnOne/Portal BPのprivate H2 URL、class後context閉鎖、説明のみ。
  製品code、法人guard、既存assertionは変更しない。既存async shutdown待機も維持する。
- seed `48992185070100`はExpense→Portal BP→OneOnOne、seed `1`は完全逆順。それぞれ**23/0/0/0**。
  原始log、3 source hash、Portal XML/text、基準失敗証拠のhashをAstraが照合した。
- 未変更の小pairでは元403を再現できなかった。cached contextで法人重複が起きたという根因は未確定であり、
  この修復は確認済みのshared fixture依存を除去する。第3回FAILを後からPASSへ変更しない。
- 詳細は`docs/review/q1-portal-h2-isolation-evidence.md`。全fast第4回は後続の固定統合Headで実施する。

## 第4回: PASS（2026-10-04に保存証拠を再照合）

- fixed Head `3d9e3a8790cca94409719ca0a44a303809cf8efe`、開始/終了clean、Head一致。
- `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test`、process限定socket directory指定。
- UTC `2026-10-02T01:14:20.5694536Z`〜`2026-10-02T01:28:09.9737852Z`、exit 0。
- **4088 tests / 0 failures / 0 errors / 0 skipped、668 class XML**。
- `verification-evidence/20261002-control-fast-04/`のXML/text manifest 1336件を再照合し不一致0。
  Maven log SHA-256: `98BEDCC36711FB61E1181D95D6553726A7B637B9397FB5078977AA03444D8C68`。
  totals JSON SHA-256: `6BC17B2FDA642FF8986D2A0B70184AB874DD02ED2E9FDD5CC0A736059BF135D3`。
- Q0/Q1修復後の全fast合格である。Q1元403の根因が動的に確定したという意味ではない。
  後続NF04/NF06実装、MySQL、Browser、production approvalの合格へ転用しない。

## 同じ固定Headの全MySQL第1回: FAIL

- command: `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test -Pmysql-tests`。
- UTC `2026-10-02T01:29:10.1912076Z`〜`2026-10-02T02:13:19.4022749Z`、exit 1、開始/終了clean、Head一致。
- **190 tests / 7 failures / 0 errors / 0 skipped、78 class XML**。
- 5 failureは4 classの最新版断言が182のままで、実際はNF01 V183まで適用済みだった。
  CustomerSuccess 1、SelfService 2、Asset 1、V110AdminBoundaryUpgrade 1。
- OperationalBoundaryの2 failureは子JVMの`JAVA_TOOL_OPTIONS`通知がstderrからprotocolへ混入したもの。
  先頭行のlock判定と、protocol以外を含めたpid distinct判定が誤った。業務assertionを削除せず修復する。
- `verification-evidence/20261002-control-mysql-01/`のXML/text manifest 156件を再照合し不一致0。
  Maven log SHA-256: `EBD4327E6CD8F9C4BC0FBEE3459954CF601B7936D5CCB72D2AB8690EBBC4E25E`。
  totals JSON SHA-256: `C7C730E197273971DADA71DE08C8114D9E4567EDFF4CEBEA515F6DC416DA080D`。
- このrunの実containerでMySQL `8.0.46`とimage digest
  `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`を観測した。
  `observed-mysql-runtime.json`参照。全containerや過去NF07 run13の個別観測を意味しない。
- NF04の限定4 class試験が一部時間帯に重複した。scheduling-noteを保持し、完全な単独負荷測定とは呼ばない。

### Q2/Q3限定再検証第1回: FAIL_SKIPPED（2026-10-04）

- source fixed `557b74a4c4ed4c89531eb89831f57b06ad346bb1`、作業先`C:\work\ses-nf02-remediation`。
  Q2 `910a4c78`、Q3 `ecfc99b9`、起動失敗cleanup追加`557b74a4`。Astra静的待修findingなし。
- 上記5 class限定のMySQL profileで**20 tests / 0 failures / 0 errors / 20 skipped**。
  Docker daemon未起動による`disabledWithoutDocker`であり、Maven exit 0でもGateはFAIL。
- 証拠は`verification-evidence/20261004-q2-q3-mysql-01/`。原始結果を保存し、成功へ読み替えない。
  Docker起動確認後、同じscopeを別runで再実行する。限定成功も全MySQL第1回をPASSに変更しない。

### Q2/Q3限定再検証第2回: PASS

- 同じfixed source `557b74a4c4ed4c89531eb89831f57b06ad346bb1`、clean start/end、Head一致。
- UTC `2026-10-04T07:48:24.3032998Z`〜`2026-10-04T07:54:13.0555109Z`。
- Docker事前確認後、同じ5 classで**20/0/0/0、exit 0**。原7 failure methodを全て含む。
  Asset 11、CustomerSuccess 1、SelfService 3、V110AdminBoundaryUpgrade 2、OperationalBoundary 3。
- 原log、全10 XML/textとhash、metadata、zero-skipped gate判定、実MySQL8.0.46の観測を
  sourceおよびcontrolの`verification-evidence/20261004-q2-q3-mysql-02/`へ保存した。
  log SHA-256: `2E0C70E6CA055D4CFE05440312086540752B4274EBE4397089CF16D9631670EF`。
- Astraがraw/report hash、原失敗method包含、child実PID/claim/lock断言を確認し、限定CODE PASS。
  controlへ`47ca275b`、`2d1b60e6`、`b978fbac`として統合した。
- これは5 classの修復完了であり、全MySQL190件の再合格ではない。第1回全体FAILと20 skipped runを履歴として保持する。

## 2026-10-13 全fast第5回: FAIL

- 固定統合Head `02f6ace46775f61accc9b38c010cd595cc9351a0`。NF04 G2、NF06 F1a/V184、NF07 D1、NF08 A/B/C1を含む。
- command: `mvn -B -Dsurefire.runOrder.random.seed=48992185070100 test`。selectorなし。
- UTC `2026-10-13T05:59:41.8878464Z`〜`2026-10-13T06:06:55.0276180Z`、起止SHA一致、clean、exit1。
- 新規691 class XMLの集計は **3980 tests / 4 failures / 1498 errors / 0 skipped**。context初期化失敗を含むため、全テスト本体の正常実行証明ではない。
- `verification-evidence/20261013-control-fast-05/` に原log、XML/text、manifest、totals、failure詳細を保存した。
  log SHA-256: `E3B3F5D34BB2A47131352356F6B1EC705254810E3E53DF648250BA08B2014E0F`。
- 初期診断: V184の文書外部キーとH2文書schema DROPの競合、H2複合ADD CONSTRAINTの構文、
  ImportIdentityCandidateContext/PwaRestoreTransactionIntegration/PwaRestoreServletBoundaryIntegrationの隔離契約。
  TENANT_ID欠落などの連鎖エラーと個別failureはさらに根因分類する。単純なallowlist追加や制約無効化で成功にしない。
- 修復はbase `02f6ace4` の `codex/snf-prod-fast-v184-isolation` で実施し、Astra Reviewと必要な再検証を要求する。
  第4回4088件成功や別SHAのNF06 MySQL40/NF08定向91件成功を、このrunの代替証拠にしない。
