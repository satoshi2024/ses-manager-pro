# Fast suite engineer account link fixture isolation

## 対象と原因

2026-10-02 のcontrol fast実行（seed `48992185070100`）では `OvertimeComplianceWiringTest.setUp` の5件が `t_engineer_account_link.engineer_id` の一意制約違反で失敗した。失敗直前に `EngineerSelfServicePortalMRegressionTest` が7件すべて成功していた。

共有H2は `DB_CLOSE_DELAY=-1` でテストcontextをまたいで存続する。Portal回帰は各テスト前に2名のengineerとアカウントlinkを作り、クラス全体に `@Transactional` がなく、`@AfterEach` はSecurity/Tenant contextのみを解放していた。`@DirtiesContext(AFTER_CLASS)` で次contextが開始すると、schema-locationsのV1が `t_engineer` を再作成し採番を戻す一方、V32相当の `schema-self-service-h2.sql` はlink表を `CREATE TABLE IF NOT EXISTS` として維持する。このためPortalが作成したengineer ID 4〜8のlinkが残り、Overtimeが新規採番したID 4〜8と衝突する。これは本番制約の問題ではなく、異なるテストcontext間でfixtureを共有したことによるテスト分離不備である。

未修正状態で両クラスを同じMaven実行に含め、元seedを指定すると原因を再現した。Portalは7/0/0/0、続くOvertimeは5/0/5/0となり、重複IDは4〜8だった。

## 修正

- `EngineerSelfServicePortalMRegressionTest` に当該クラス専用のH2 URLを割り当てた。`DB_CLOSE_DELAY=0` とし、既存の `@DirtiesContext(AFTER_CLASS)` によるDataSource終了で専用DBを回収する。共有test profileや本番コード、制約は変更していない。
- `OvertimeComplianceWiringTest` はseed済みの `sys_user.id=1` を更新せず、明示tenant・active状態の専用ユーザーを作成し、そのユーザーと自身のengineerをlinkする。クラスの `@Transactional` rollbackがfixtureを片付ける。通知確認と既存の業務アサーションは維持した。
- Portal側の非同期taskExecutorは `AsyncConfig` でshutdown時の完了待ちが有効（最大30秒）。Portal context close後にHikari poolのshutdownがログに記録され、その後のOvertime contextは既定の `testdb` を開いている。Portal fixtureは専用DataSourceに閉じており、共有DBに遅れて書き込む構成ではない。

## 検証

Java 21で以下を実行した。両コマンドとも `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds` を設定し、seedはSurefireのクラス実行順再現用である。

1. 未修正再現: `mvn.cmd -Dtest=EngineerSelfServicePortalMRegressionTest,OvertimeComplianceWiringTest -Dsurefire.runOrder.random.seed=48992185070100 test` — exit 1。Portal 7/0/0/0、Overtime 5/0/5/0。
2. 修正後・元seed: 同じコマンド — exit 0。Portal 7/0/0/0、Overtime 5/0/0/0、合計12/0/0/0。
3. 修正後・逆順seed: `mvn.cmd -Dtest=EngineerSelfServicePortalMRegressionTest,OvertimeComplianceWiringTest -Dsurefire.runOrder.random.seed=1 test` — exit 0。Overtime 5/0/0/0が先、Portal 7/0/0/0が後、合計12/0/0/0。
4. コメントの日本語化とfixture変数のローカル化後、元seedを再実行 — exit 0。Portal 7/0/0/0 → Overtime 5/0/0/0、合計12/0/0/0。

Mavenの実行ログとSurefire XML/TXTは作業tree内のローカル `verification-evidence/20261002-fast-isolation/` に保存した（このディレクトリは既存 `.git/info/exclude` によりローカル除外される）。seed 1のSurefire実行順は元seedと逆である。主要ファイルのSHA-256は以下のとおり。

- 元seedの未修正再現ログ `01-pair-repro.log`: `EAC21693433C18FABD9F5C8932AD1E37ECF4F4A7CA3BA9A4FA407A7AAF531B32`
- 元seed修正後ログ `02-pair-fixed-original-seed.log`: `2825A209D0B46694CA966807F00169D639BD408DDD20250CEEC0A40C8FE15A1A`
- 逆順seed修正後ログ `03-pair-fixed-seed-1.log`: `7086E652CCCB351529772C24BA7D4C877784A257173F637348676E81B03A862D`
- 最終ファイル状態での元seedログ `04-pair-final-files.log`: `9D32D0CFCB3F7E11D98509E928455826B652B4FE4637DE9976E636A7E9CFD177`
- 最終runのOvertime Surefire XML: `570BFBC248566C2279DD24ABDCB77E3724AAF32E1427F718F86CAF31B06EA1EA`
- 最終runのPortal Surefire XML: `3EF3FA9B1A32990ACC50DEA7E287BDD432168156BE43151559A1DF69A636E924`

記録した各runは同一の2クラスのみであり、full fast suiteの再実行結果ではない。

## 残る範囲

full fastを再実行していないため、他のテストクラスが同じ共有H2で工程師/アカウントlinkを作成し、後続contextで同種のID再利用を起こすかは未検証である。今回確認できた直近producerと影響対象は隔離したが、全suiteでのorder-independentなfixture分離は未証明である。
