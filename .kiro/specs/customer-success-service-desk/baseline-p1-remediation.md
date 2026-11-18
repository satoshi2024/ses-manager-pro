# NF02 baseline review P1 remediation

独立baseline reviewの `PRD-NF02-001` / `PRD-NF02-002` に対する実装メモ。
この対応はNF02のOwner/DG-02承認、production approval、全specの完了を意味しない。

## P1-001: SLA通知先の現行資格

`ServiceSlaMonitoringServiceImpl` は候補recipient本人のtenant/status/deletedを確認し、管理者・営業・マネージャーに限定する。管理者以外は`service-desk` menuを持ち、候補自身の`SecurityContext`で`CustomerScopeResolver`が許可するcustomerを担当する場合だけ通知先になる。scheduler/呼出者のscopeは流用しない。HR/要員やscope/menu失効は候補から除外され、有効recipientがいないときは既存escalation ledgerのrecipientCount=0へ留まり、通知本文/outboxを発行しない。合法な管理者への既存fallbackは維持する。

対象回帰: `ServiceSlaRecipientScopeIntegrationTest` は専用H2 datasourceとunique tenantで実DBのmanager組織移動、sales契約担当解除、role変更、menu撤回を実resolverで確認する。候補評価前のcaller request cacheを温め、Sales担当解除後はSales本人の新しいscopeがdenyになることを確認し、admin callerによる合法なfallbackも確認する。Sales担当復元後はdeny状態を保持したcaller attributesを使ったままSalesが再び選ばれ、呼出者のSecurityContextとrequest attributesが復元されることを確認する。DB変更はMyBatis mapper経由とし、テスト用JdbcTemplate更新が同一transactionのMyBatis local cacheへ古い行を残すことを避ける。role-menu seedの一時削除はこの専用DB内の`@Transactional`でのみ行い、`@AfterTransaction`でrollback後の復元を検査する。`ServiceSlaRecipientScopeTest` はscope分岐、HR/要員role、管理者fallback、およびresolver例外後のsecurity/request/report context復元を確認する。`ServiceSlaSchedulerTest` はadminが存在しないunique tenantでrecipient 0件時のNotificationService呼出0、outbox 0、recipientCount=0のretry ledgerを確認する。

## P1-002: 担当者のtenant境界とsafe error

`ServiceRequestServiceImpl.validateOwnerUser` は`selectByIdAndTenant`に切替え、tenant一致、有効、未削除、許可roleを保存前に検証する。不存在、別tenant、無効、削除済み、非対象roleは同一generic errorを返し、real nameやroleを含めない。

対象回帰: `ServiceRequestServiceImplTest` のcreate/updateでforeign-tenant userを拒否し、create側にrequest行が残らず、update側で元のsubject/version/ownerが不変で、foreign real nameが応答へ出ないことを確認する。

## 検証状態

H2 targeted runは全てskipなし。2026-10-02 00:43 JSTの5クラス実行は32件中、担当者15・scheduler7・unit scope3・isolation audit6が成功、scope integrationはfixture usernameの長さ制限で1 errorとなった。該当fixture修正後、2026-10-02 01:02 JSTにscope integration単体を再実行し1/0/0/0で成功した。両runのsource manifest、console、Surefire reportとSHA-256を`docs/review/evidence/nf02-p1-targeted/`に分けて保存した。Mavenコマンド、正確なsource manifest hash、console/report hash、失敗した試行の原因は同ディレクトリの`evidence-ja.md`を参照。
