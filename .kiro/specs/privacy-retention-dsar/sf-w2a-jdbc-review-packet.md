# NF07 SF-W2a synthetic JDBC journal review packet

判定対象はsynthetic JDBC journal foundationのみ。Astraによる固定commitの独立CODE Review待ちであり、このpacket自体はPASS判定ではない。

## 固定対象

- branch: `codex/snf-prod-nf07-foundation-plan`
- review前HEAD: `e5530c01b7697e125f3b4b7f2db168bfc385bd97`
- root cause: 既存head行に対する`INSERT IGNORE`がduplicate-key共有lockを取得してから`SELECT ... FOR UPDATE`へ昇格し、同時appendでdeadlockした。
- 修正: transaction内で`INSERT ... ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id)`によりhead PRIMARY行の排他lockを先に取得し、chain検証・CAS・history insertを同じ短いtransaction内で処理する。deadlockを競合として握り潰さない。
- adapterに公開Spring bean、controller、production connection factory、real schema migrationを追加していない。W2a schemaはtest resourceのみ。`HOLD_RELEASED`はrelease authority/SoDがないため拒否。synthetic `DISPATCH_AUTHORIZED` ACKは`VerifiedDispatchFence`でも実送信許可でもない。

## 最終検証

| run | command | result | source manifest digest |
|---|---|---:|---|
| 11 | `mvn test -Pmysql-tests -Dtest=PrivacyJournalLinearizationMySqlTest` | 24/0/0/0 | `AE291D9034E895029A7524F2D10CC6549CB0C453FC41312C44E430FBCD85DC81` |
| 12 | `mvn test -Dtest=PrivacyJournalBoundaryTest,MySqlTestShardInventoryTest` | 4/0/0/0 | `EA978A9C177334C707470F958475A59628746F01F39B78AE3E0C33F848B24F13` |

Surefire order was random. Both runs retained `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`. Per-run source hashes, HEAD, branch, command and timestamps are in `run-11-source-manifest.txt` and `run-12-source-manifest.txt`; digest is SHA-256 over manifest bytes before the final digest line.

Run 11 used a Testcontainers `mysql:8.0` container. Captured non-secret identity: container ID `65d1a31abaf74678e1387f61e4233bc441eedc65467cc5e4efebdb48e34fd477`, database `sfj_47169d1ca5be4e038f02c703c76bf235`, host `localhost`, mapped port `54268`, schema SHA-256 `9b9d235555c38241573e0fed75ddc8648ed3b12da2ce4c929840eaa1ab68e29b`, capability manifest SHA-256 `0ebd06a8cb9d24b0a85d73ff93fd30c13df905688a691aa62127b9ed0956d152`. Exact MySQL server version and image digest were not captured; the image tag alone is not a version/digest proof.

The run log directly records three row-lock observations. The standalone probe tied writer connection `76` to blocker `75` on `sf_journal_head` / `PRIMARY` / `RECORD`. Two additional test-only JDBC-gated business races held the first append's head lock, started the second append, then observed the second real writer waiting on the same partition PRIMARY record before releasing the first:

- `HOLD_STARTED` then `DISPATCH_AUTHORIZED`: connection `158` then `159`; final event sequence `[LEASE_CLAIMED, HOLD_STARTED]`; authorization rejected.
- `DISPATCH_AUTHORIZED` then `HOLD_STARTED`: connection `168` then `169`; final event sequence `[LEASE_CLAIMED, DISPATCH_AUTHORIZED, HOLD_STARTED]`.

The P5 same-start expiry/takeover race passed: takeover advanced the lease generation, expired/stale authorization was rejected, and no stale action projection was written. P14 injected failure during compound authorization and confirmed rollback left no new event/action/command ACK. P15 checked active/default `prod` refusal before connection acquisition. Projection exact-set/binding corruption, command replay conflict, ambient transaction rejection, primary durability checks and writer table privileges are covered in the same 24-test class.

Raw run 11 log SHA-256: `669752DADDCA8E5832850CEA0E21344FA762DA8FA76D8FA1B4C8F8493BD1E41A`; XML: `36E2ADB023701810B83094B6DD376F21B0BC73B3792D2E5ADDD5C686571716FB`; text summary: `C6C2E1E219BB3896B8379B218FC994ED1A99ACBCE90A6ECDFBE7DA368C1D4BD1`.

Run 12 raw log SHA-256: `0534CC0F2E90BBB6ED5AE644B73820FB6B39BA9D43F4DE9DAA78720B3C3B9D2F`; Boundary XML: `1AF41A756022D58CB84E9933BAEC9081FE2D300F079EE879E06D166B37B9CCE9`; inventory XML: `9D0A233729446C96B1BB71C1EF4677545B5EF8AFEABAEF8E0A2464C8DD3DE67E`.

## 失敗runと証拠の扱い

- run 06はMySQL 22/1/1/0。stack traceは既存head duplicate-key共有lockから`lockedHead ... FOR UPDATE`への昇格deadlockと、古いlockprobeがglobal waitしか見ていなかったことを示す。原log/XMLを保存する。
- run 07はtestCompile failure。`run-07-mysql-surefire.xml` / `.txt`はrun 06とSHA-256が同じ`B20A319D600EE66BA3E1FC53F717765107780186BC7BFEEF5CA325FCD2E05493` / `B4FA5D6D167BD9E20E9EFD92FFC302EE27C26F21994F385BA4F770A0F9F358FC`であり、run 07の結果ではなく、古いtarget reportのコピーとしてのみ保管する。
- run 08は途中改修状態で22/0/0/0、最終証拠には数えない。
- run 09は追加P5 testのtyped command入力不正で23/1/0/0。
- run 10はFutureの`ExecutionException`を直接domain exceptionと比較したassertion errorで24/1/0/0。cause検査を追加し、run 11で再実行した。

全runのログ、XML、text report、exit code、source manifestは`verification-evidence/nf07-w2a/`に保持する。run 07を含め失敗証拠は成功runと混算しない。

## 残る範囲と非主張

このW2a範囲は技術基盤の限定審査にすぎない。action/command/lease/headのjournal内projectionは検証するが、W2bの独立application projection、independent attestor/witnessとcheckpoint freshness、復元時の旧watermark拒否は未実装。W3 worker/provider、実送信・DSAR/disposition、`VerifiedDispatchFence`と歴史receipt信頼、HOLD解除authority/SoD、production adapter/DDL、NF07全体受入も未完。MySQL version/image digestも未収集で、後続RC rehearsalで補う。

従ってこのcommitはSF-W2a synthetic journal境界のみを固定し、W2b/W3、NF07全体、DG-07、Privacy/Legal、production、実処置の完了や承認を示さない。
