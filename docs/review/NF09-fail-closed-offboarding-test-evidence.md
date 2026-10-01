# NF09 fail-closed退社阻止回帰の検証記録

## 対象

- provider境界・offboarding回帰: `02105ce6a92d61736d44ce727f028059618a64c4`
- fixture隔離と確認主体検証: `0f02e8028eb91509def17aafba8358f66d331a8c`
- branch: `codex/snf-prod-nf09-provider-boundary`

テストfixtureは毎回一意のtenant、法人、実在するHRユーザー、実在する要員を作成し、`@AfterEach`で自身が作成したIDだけを物理削除する。poll処理のtenant-wide検索もこの一意tenantに限定される。起票者は実ユーザーIDで記録される。

## 実行証拠

実行コマンド:

```powershell
.\apache-maven-3.9.6\bin\mvn.cmd "-Dtest=ExternalAccountProviderProfileTest,ExternalAccountFailClosedOffboardingIntegrationTest" test
```

結果: `BUILD SUCCESS`、6 tests、0 failures、0 errors、0 skipped。profile matrix 4 tests、offboarding回帰は同一JVMで2回実行した。

回帰ではfail-closed clientから実`ExternalAccountServiceImpl`、MyBatis台帳、実`AssetOffboardingServiceImpl`までを通し、要求後`PENDING_CONFIRMATION`、poll後`UNKNOWN`とretry/backoff、繰返しclearance不許可・未失効件数1を確認する。`revoke_requested_by`は起票HRユーザーIDであり、`revoke_confirmed_at`、`revoke_confirmed_by`、`actor_type`、`confirmation_source`、`revoke_confirmed_source`は未設定のまま保持される。

この機能には外部アカウント専用のdomain event tableがない。したがって「確認event 0件」という存在しないevent streamへの主張はせず、confirmationの永続正本列がすべてNULLであることと`REVOKED`へ遷移しないことを確認した。

Mavenの生レポート: `target/surefire-reports/com.ses.service.ExternalAccountProviderProfileTest.txt` および `target/surefire-reports/com.ses.service.ExternalAccountFailClosedOffboardingIntegrationTest.txt`。
