# NF02 P1 定向検証証跡

## 最終sourceと実行

- Branch/base: `codex/snf-prod-nf02-scope` / `1443fb68943849a3260d7ebe005259f3eb609d60`。
- 最終scope実行のsource snapshot: `C:\work\ses-manager-pro-nf02-nf03-review-v2\verification-evidence\nf02-p1-targeted\raw\final-scope-integration\source-manifest.txt`、SHA-256 `E53FE7D00CCBE48CC66ADD9591C8EC6F69A4D961F877B63A00F7E5C16DE4B644`。
- コマンド: `./apache-maven-3.9.6/bin/mvn.cmd test -Dtest=ServiceSlaRecipientScopeIntegrationTest`。
- Java option: `-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`。
- `metadata.txt`のPowerShell ISO時刻による実行区間: 2026-10-02 01:02:24–01:03:41 JST、Maven exit 0。
- 最終scope integration結果: **1 test、失敗0、error 0、skip 0**。
- raw console SHA-256: `4B87377C80818D17573EFF0A2F9CE424F84D8368BCE9F265E42936C755FF982D`。
- Surefire text SHA-256: `609ACF45958D047765AC21C15FAA1C752BE84C9AC8620F473C5F0E332D6B2E55`。
- Surefire XML SHA-256: `F62A996F9C70F7A873C29496A26909F0E14D2B320631598FA71C1A5A5238CF19`。

## その他の定向test（別run）

2026-10-02 00:43–00:45 JSTの5 class定向実行は32 testsだった。`ServiceRequestServiceImplTest` 15/0/0/0、`ServiceSlaSchedulerTest` 7/0/0/0、`ServiceSlaRecipientScopeTest` 3/0/0/0、`TestIsolationAuditTest` 6/0/0/0は成功した。scope integrationは、synthetic usernameがVARCHAR(50)に対して51文字となりfixture insertでerrorになった。raw console、metadata、各classのreportとhashは`C:\work\ses-manager-pro-nf02-nf03-review-v2\verification-evidence\nf02-p1-targeted\raw\attempt5-hr-username-too-long\`に保存した。usernameを短縮後、影響を受けたintegration classだけを上記の最終runで再実行した。件数はrunごとに記載し、複数runを一つの成功件数として合算していない。

## 調査内容とfixture修正

- integration testは専用H2 datasourceへ`scope.sales-own-data-only=true`をREQUIRES_NEW transactionでcommitする。`SystemConfigServiceImpl`はcommit後にcacheを無効化するため、rollbackされる外側test transactionの中だけで設定すると、古い`false`がcacheから読まれる。
- contract、user role、role-menuの変更にはMyBatis mapperを使う。初期の診断runでは、同じtest transaction内でMyBatisがrecipient scope queryをcacheした後に`JdbcTemplate`でDBを書き換えていた。このJDBC更新ではMyBatis local cacheがflushされなかった。診断時の失敗runは`C:\work\ses-manager-pro-nf02-nf03-review-v2\verification-evidence\nf02-p1-targeted\raw\attempt6`から`attempt10`へ保存した。
- serviceにあるcaller customer-scope precheckは維持した。recipient自身のscopeを独立して確認するため、testはSalesのDENY cacheを作った後、同tenantで作成した有効な管理者をcallerにする。管理者はcustomer全体を参照できるため前置checkを通過し、候補Salesは本人scopeに従って拒否され、既存仕様に沿って管理者fallbackを返す。契約担当をSalesへ戻した後もcallerの同じ古いDENY attributesを保ったままserviceを呼び、Salesを再評価して通知先に選び、管理者identityとcaller request attributesを復元することを確認する。role/menu revokeも候補本人の資格と正当な管理者fallbackを確認する。
- synthetic管理者はmanager組織scope境界を検証した後に作成し、それ以前のempty-recipient判定に影響させない。scheduler testは管理者が存在しないunique tenantを使い、recipient 0件、NotificationService呼出0、outbox 0を確認する。
- `C:\work\ses-manager-pro-nf02-nf03-review-v2\verification-evidence\nf02-p1-targeted\raw\attempt11-known-admin-fallback-aborted\`は意図的に中止したMaven compileであり、test証跡ではない。その時点の`empty`期待が誤りで、activeなtenant-local管理者は既存fallback recipientになると判明したため、test実行前に停止した。

各raw attempt directory (`C:\work\ses-manager-pro-nf02-nf03-review-v2\verification-evidence\nf02-p1-targeted\raw`)に、利用可能なrun単位のconsole/report/metadataを保存した。このbatchではMySQL、Docker、browser、full suite、push、PR、deployment、credential発行、人工approvalは行っていない。

最終scope run後、ServiceSlaRecipientScopeTestのコメント3箇所をAGENTS.mdに合わせて日本語にした。実行コード・assertionは変えていない。spec/evidenceの説明文もrun後に証跡を反映したため、source-manifest.txtはMavenが実際に使用したsource snapshotを指す。
