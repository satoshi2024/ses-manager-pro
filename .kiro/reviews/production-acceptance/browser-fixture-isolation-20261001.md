# Browser Demo fixture 隔離修正（2026-10-01）

## 原因

CI run `36858538460` / job `110356768526` の `AccountingIntegrationBrowserDemoTest.captureAccountingScreensWithRealBrowser` は、`t_engineer_account_link` の `UNIQUE(engineer_id)` に対し `engineer_id=4` を重複登録して失敗した。seed `101246425020` の browser profile 全体で再現し、14 tests / 0 failures / 1 error / 0 skipped。失敗箇所は `AccountingIntegrationBrowserDemoTest.java:349`。

共有H2で先行する `EngineerSelfServiceBrowserMTest` が専用ポータル要員と `t_engineer_account_link` を作るが、テスト後に自分が新規作成したリンクを解放していなかった。後続のDemoが再利用された要員ID 4へリンクを作ろうとすると一意制約に衝突する。

## 修正

`EngineerSelfServiceBrowserMTest` は、自分がその実行で新規作成したリンクの `(engineer_id, sys_user_id)` の組だけを記録し、`@AfterEach` でその組に一致する行だけ削除する。既に存在していたリンクは記録・削除しない。失敗時もJUnitの `@AfterEach` で後始末される。製品コード、DB制約、認可、実行順、skip設定は変更していない。

## 検証

実行コマンド（PowerShell、Windows Maven、Java 21）：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds'
& 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd' '-Dsurefire.runOrder.random.seed=101246425020' -Pbrowser-tests test
& 'C:\work\ses-manager-pro\apache-maven-3.9.6\bin\mvn.cmd' '-Dsurefire.runOrder.random.seed=987654321' -Pbrowser-tests test
```

| 実行 | 件数 | Failures | Errors | Skipped | Maven |
|---|---:|---:|---:|---:|---|
| 修正前、seed `101246425020` | 14 | 0 | 1 | 0 | BUILD FAILURE (exit 1) |
| 修正後、seed `101246425020` | 14 | 0 | 0 | 0 | BUILD SUCCESS (exit 0) |
| 修正後、seed `987654321` | 14 | 0 | 0 | 0 | BUILD SUCCESS (exit 0) |

各回のMavenログとSurefireレポート一式（18ファイル）を `target/browser-evidence/ci-reproduction/` 以下のrun別ディレクトリに保存した。失敗時ログは `browser-seed-101246425020-reproduction-captured.log`、そのSurefire一式は `seed-101246425020-reproduced/surefire-reports/`。成功runはそれぞれ `seed-101246425020-postcleanup-final/` と `seed-987654321-final/` にある。

H2 browser profileで二つのrunOrder seedを確認した。MySQL profile、fast suite、production上のユーザー承認またはデプロイはこの修正では検証・実施していない。
