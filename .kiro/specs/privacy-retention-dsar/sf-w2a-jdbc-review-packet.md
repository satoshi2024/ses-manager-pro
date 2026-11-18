# NF07 SF-W2a synthetic JDBC journal review packet

## 審査対象

本packetはsynthetic JDBC journal foundationだけを対象とする。Astraの前回指摘P2（P5のgeneration境界を実待ちで確認できていない）に対応するMySQL回帰を追加し、run 13で直接待機を確認した。固定commitの最終独立Reviewは依頼中であり、この記録はCODE PASSの判定ではない。

- branch: `codex/snf-prod-nf07-foundation-plan`
- 変更前HEAD: `e5530c01b7697e125f3b4b7f2db168bfc385bd97`
- P1 root cause: 既存head行に対する`INSERT IGNORE`がduplicate-key共有lockを取得してから`SELECT ... FOR UPDATE`へ昇格し、同時appendでdeadlockした。
- 修正: transaction内で`INSERT ... ON DUPLICATE KEY UPDATE tenant_id = VALUES(tenant_id)`によりhead PRIMARY行の排他lockを先に取得する。chain検証、revision CAS、event/history insertとhead更新を同じ短いtransactionで実施し、deadlock/connection障害を競合として握り潰さない。
- adapterはpackage-privateでSpring bean登録なし。controller、production connection factory、実schema migrationを追加していない。W2a schemaはtest resourceだけである。`HOLD_RELEASED`はrelease authority/SoD未実装のため拒否する。synthetic `DISPATCH_AUTHORIZED` ACKは`VerifiedDispatchFence`でも実送信許可でもない。

## 最終検証

| run | command | result | source manifest SHA-256 |
|---|---|---:|---|
| 13 | `mvn test -Pmysql-tests -Dtest=PrivacyJournalLinearizationMySqlTest` | 25/0/0/0 | `B35FAC5313CF234C574AE3845F0B42D51C54ACC1FF65FAB310F09FE2CC73151C` |
| 14 | `mvn test -Dtest=PrivacyJournalBoundaryTest,MySqlTestShardInventoryTest` | 4/0/0/0 | `DC4746E6D692301B93D5FB9854DB89A9E44E1584805F4899905432D922FF0F20` |

Surefireはrandom順で実行した。両runで`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`を指定した。runごとのsource SHA-256、HEAD、branch、command、start/end UTCは`run-13-source-manifest.txt`と`run-14-source-manifest.txt`に記録した。各manifest末尾のdigestは最終digest行より前の全byteに対するSHA-256である。

run 13はTestcontainersの`mysql:8.0`を使用した。記録した非secret identityはcontainer ID `8caa944521530b97f71767bb679627aabc15c637582d8f5c0dab5fb5f2c30d4d`、random database `sfj_894bf3d0762b4d7cacba8f13804abde0`、host `localhost`、mapped port `55906`、schema SHA-256 `9b9d235555c38241573e0fed75ddc8648ed3b12da2ce4c929840eaa1ab68e29b`、capability manifest SHA-256 `e51af3131eb9652d7a06da35fb10a709673f1e14e4e63560efd394afb84dcbc8`である。実server versionとimage digestは取得していない。`mysql:8.0` tagはその証拠の代用ではない。

run 13 logには同じpartitionのbusiness writer間で観測したInnoDB lock waitを記録した。test-only JDBC gateで先行appendがhead lockを保持してから後続appendを起動し、`performance_schema.data_lock_waits` / `data_locks`と`information_schema.innodb_trx`をwriter/blocker connection ID、`sf_journal_head`、`PRIMARY`、`RECORD`、同じ`LOCK_DATA`で照合した。

- `HOLD_STARTED`先行: connection `158`がlockを保持し、`DISPATCH_AUTHORIZED`のconnection `159`が待機した。先行eventはrevision 2、後続認可はstale expected revisionで拒否され、sequenceは`[LEASE_CLAIMED, HOLD_STARTED]`となった。
- `DISPATCH_AUTHORIZED`先行: connection `168`がlockを保持し、`HOLD_STARTED`のconnection `169`が待機した。更新後head revisionを指定したholdがrevision 3に入り、sequenceは`[LEASE_CLAIMED, DISPATCH_AUTHORIZED, HOLD_STARTED]`となった。
- P5 takeover fence: takeover connection `200`がhead lockを保持し、旧worker/generation 1のauthorization connection `201`が待機した。後続commandはtakeover後の`expectedRevision=2`を持たせ、CAS先行拒否を除外した。lock解放後にrevisionは一致するがcurrent generation 2に対する旧generation 1として拒否され、action rowは0件、headはnew worker/generation 2のままだった。

同じ25 test classでP14 transaction途中fault rollback、P15のactive/default `prod`を含む接続取得前拒否、ambient transaction拒否、durability設定、projectionの欠落/余分/誤binding、command replay、writer権限、microsecondとhash検証も確認した。run 14はpure boundary 3件とMySQL shard inventory 1件の合計4件が成功した。

run 13 raw log SHA-256: `B9ED8EA8E5E8E261703FA8E6467B52406A52279950F8789D3BB1678BFEF34E19`。Surefire XML: `45A916A47266480497665798879ECAF2150D0D89A7E8877D6E8F7AEE97383C29`。run 14 raw log SHA-256: `269645D850F6162E51EB254F2D360C271A29D5D17FF45607B9018FCB4CF163CD`。Boundary XML: `F76ACE57CA40B3AC0AA50940E9C2AD2DF1283CEDED2F7DB2523FB94B099C8411`。Inventory XML: `7FB52AD362E27B346C701E1DC22012E2EBF6AF2D072736992539831CFC99F468`。

## 失敗runとraw証拠

- run 06はMySQL 22/1/1/0。stack traceは既存head duplicate-key shared lockから`lockedHead ... FOR UPDATE`への昇格deadlockを示した。
- run 07はtestCompile failure。`run-07-mysql-surefire.xml`と`.txt`はrun 06と同じSHA-256（XML `B20A319D600EE66BA3E1FC53F717765107780186BC7BFEEF5CA325FCD2E05493`、text `B4FA5D6D167BD9E20E9EFD92FFC302EE27C26F21994F385BA4F770A0F9F358FC`）であり、run 07の結果ではなく古いtarget reportのコピーとして保管した。
- run 08は途中状態の22/0/0/0で、最終判定に含めない。
- run 09はP5追加testのtyped command generation不正で23/1/0/0。
- run 10はFutureの`ExecutionException`をdomain exceptionとして直接assertした誤りで24/1/0/0。causeの型を検証するよう修正した。

全raw logs、XML/text report、exit code、source manifestは`verification-evidence/nf07-w2a/`に維持する。run 07 stale reportや失敗runを成功runへ混算しない。全raw fileのSHA-256一覧は同directoryの`raw-evidence-sha256.txt`に置く（manifest SHA-256 `77B7F160539EDFBD700C6D9B326E915D6B17FE6C471ECFCB740BEF437AEC444F`）。raw filesは手元にそのまま保存し、tracked packet/hash listからbytesを照合できる。

## 未実装範囲

W2aではjournal内のaction/command/lease/head projectionをevent正本と照合するが、W2bの独立application projection、独立attestor/witnessとcheckpoint freshness、restore時のold watermark拒否は未実装である。W3 worker/provider、実送信・DSAR/disposition、`VerifiedDispatchFence`と歴史receipt trust、hold解除authority/SoD、production adapter/DDL、NF07全体受入も未完。MySQL実server version/image digestも未収集で、後続RC rehearsalで補う。

この限定実装はNF07全体、DG-07、Privacy/Legal、production、実処置の完了や承認を示さない。
