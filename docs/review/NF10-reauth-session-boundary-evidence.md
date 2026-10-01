# NF10 再認証grant session境界のP1修正記録

## 対象

- Finding: `PRD-NF10-001`（共有deliveryの`reauthenticated_at`により別sessionがpassword再認証を継承する）
- Worktree / branch: `C:\work\ses-nf10-remediation` / `codex/snf-prod-nf10-reauth`
- Base: `1443fb68943849a3260d7ebe005259f3eb609d60`
- 実装Head: `19d87bc6518bf09ea8845c36fdb1e173edff285a`。

## 実装

password再認証成功時にsession保存のgrantを発行し、session ID、現在Authentication instance、tenant、user、delivery、current recipient scope hash、10分expiryへ束縛する。共有`reauthenticated_at`は監査目的で更新するが、download認可には使わない。

downloadでは従来どおりtoken hashとdelivery ID/tenant/recipient/status/link期限、run/snapshot、最新recipient preview、現在role/組織scopeとscope hashを検証した後、session grantを検証する。scope/roleのDB上の権限をAuthentication authority比較へ置き換えていない。匿名principal、sessionなし、別session/tenant/user/delivery/scope、session ID rotation/logout、grant満了、Authentication instance/authority変更は拒否し、再認証を要求する。session replication/serializationでgrant内外のAuthentication参照が同一instanceでなくなった場合もfail-closedし、step-upをやり直す。

追加の境界修正では、再認証時にtenant内の現行`sys_user`が存在し`status=1`であることを確認する。downloadも毎回同じ現行DB状態を確認し、無効化済みアカウントの旧session/grantによる取得を拒否する。delivery監査timestampの更新後、Spring transactionの`afterCommit`で初めてsession grantを発行するため、CAS/update失敗やtransaction rollback/commit失敗でgrantだけが残らない。

## 回帰範囲

- `ReportReauthenticationSessionIntegrationTest`: 実controller→実`ReportDeliveryServiceImpl`→MyBatis/H2。A未認証は拒否、A再認証後Aのみ成功、Bは同userでも拒否、B自身の再認証後成功。さらに現在recipient scope撤回、同sessionのAuthentication instance交換、delivery update失敗、DB上のuser停止を拒否する。afterCommitを実行するためクラスTXを使わず、各testが一意の合成recipient user/run/deliveryを作成し、そのID/run keyで取得・削除する。他testの行を読む`MAX(id)`もseed adminの状態変更も行わない。
- `ReportReauthenticationGrantTest`: tenant/delivery/scopeとsession境界、10分expiry、session ID rotation/logout、Authentication instance変更、role authority変更、匿名/sessionなしを検証する。
- `ManagementReportDeliveryApiControllerTest`: MVCがsession付きrequestをserviceへ渡すこと、匿名の有効そうなlink tokenがserviceへ到達しないことを検証する。
- `ReportDeliveryServiceImplTest`: 既存token・scope・reauth・cancel・expiry経路を新request引数で維持する。

## 検証状況

初回targeted runでは、H2 unit fixtureに現行tenant recipient userのmapper stubがなく、scopeDeniedが期限切れ・認証済みactionの期待結果を先取りしたため、明示的なactive tenant userをstubした。続く最終回帰ではintegration fixtureがseed adminを更新し全表`MAX(id)`でrunを選ぶ問題も修正し、各test専用の合成userと一意run keyに切り替えた。初回失敗runは`docs/review/evidence/nf10-reauth-final-run-attempt1/`に保存した（27 tests中2 failures / 2 errors、0 skipped）。その後、grant負境界は各ケース個別に再発行し、同一条件の成功確認後に一要素だけを変えて拒否を検証した。user ID不一致とrole authority変更も追加した。

```powershell
.\apache-maven-3.9.6\bin\mvn.cmd "-Dtest=ReportDeliveryServiceImplTest,ReportReauthenticationGrantTest,ReportReauthenticationSessionIntegrationTest,ManagementReportDeliveryApiControllerTest" test
```

初回targeted: `37 tests / 0 failures / 0 errors / 0 skipped`、`BUILD SUCCESS`。四つのSurefire text reportは`docs/review/evidence/nf10-reauth-initial-37/`へ保全した（各hashは下記）。このrun後はgrant helperのnegative assertionを独立させ、role-authority testを足した。変更したgrant classだけを再実行し、`4 / 0 / 0 / 0`、`BUILD SUCCESS`を確認した。全scopeは合計38 checksでzero skip。service/helper boundary 27/4、実controller→service session integration 2、controller/API MVC 5。

最終helper Surefire report `ReportReauthenticationGrantTest.txt` SHA-256: `F7E7D0C67418206B3835CD49A568FDFE0D88BC01AD6C77CA5669A94FE2F9EC6B`。XML SHA-256: `98CA79195BB4287CF7F031F0B6B44DB3163D896F60935A4E4B3F1C0ED6C2CBDC`。実行終了は`2026-10-01T23:11:42+09:00`。

Initial 37 run保全text report SHA-256:

- `com.ses.controller.api.ManagementReportDeliveryApiControllerTest.txt`: `F00454D06693903E95C5B7056FA172E47FAE3140653D4F9114C0C51ACE6C6E2C`
- `com.ses.report.ReportDeliveryServiceImplTest.txt`: `7E4F6E5BFFB97DF666DBB6B7CEA2A02403AABC1B9CF15E3437174CD4BDC5ACA6`
- `com.ses.report.ReportReauthenticationGrantTest.txt`（旧3件版）: `C33E94BF4CF6A35783DF1DBCC6D16723D45E63DE5DBAC92342BA238443D38E52`
- `com.ses.report.ReportReauthenticationSessionIntegrationTest.txt`: `1B4AB0B6FCB6230C4F0C32C561AE17FAD0C75E521D8CBE2650981C220A53DF8C`

## 最終回帰（唯一の最終対象run）

- 対象tree: 最終commitに含める作業tree。元実装commitは`19d87bc6518bf09ea8845c36fdb1e173edff285a`。
- 実行時刻: Maven終了 `2026-10-01T23:53:30+09:00`（Surefire/Mavenログ、JST）。開始時刻は端末ログに保存されていないため記録しない。
- コマンド: `mvn.cmd "-Dtest=ReportDeliveryServiceImplTest,ReportReauthenticationGrantTest,ReportReauthenticationSessionIntegrationTest,ManagementReportDeliveryApiControllerTest,TestIsolationAuditTest" test`
- JVM環境: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds`。
- 結果: `46 tests / 0 failures / 0 errors / 0 skipped`、`BUILD SUCCESS`。クラス別はservice 27、grant helper 4、実controller→service integration 4、MVC 5、isolation audit 6。
- 実行source patchはbase `19d87bc6518bf09ea8845c36fdb1e173edff285a`から作成し、`source-diff.patch` SHA-256は`22B61BFD798980D075CD4C5385F23263E6866885D7BC0F8445783B003773B739`。
- 10個のSurefire `.txt` / `.xml` raw reportを`docs/review/evidence/nf10-reauth-final-46/`に保全した。各ファイルSHA-256:

| Report | SHA-256 |
|---|---|
| `com.ses.controller.api.ManagementReportDeliveryApiControllerTest.txt` | `22A1698221E1028C1831E3E0E9E704DEB28BB70FFD07EADA3543F3AD061A6507` |
| `TEST-com.ses.controller.api.ManagementReportDeliveryApiControllerTest.xml` | `EDC2059061C6F308C86CA73389B363B1D0D870964EE4986F071121B8D7E97EF6` |
| `com.ses.report.ReportDeliveryServiceImplTest.txt` | `BCF74FF8E40FE4337A3544A35E7A5950AA163F1830F937CAE9B28C94A06CB7B4` |
| `TEST-com.ses.report.ReportDeliveryServiceImplTest.xml` | `D5C0AAB7A27C58757AA625BA97286EF5EA4370046BA64B98F3997198A6C93534` |
| `com.ses.report.ReportReauthenticationGrantTest.txt` | `900160F6F1D59865991230B027EE647C3ABE3B084B58641D05BE7FC5DC28E275` |
| `TEST-com.ses.report.ReportReauthenticationGrantTest.xml` | `B688B12B230799D233573D66C31189E52158872DF50AA72A6F6A5FCEE150F8D5` |
| `com.ses.report.ReportReauthenticationSessionIntegrationTest.txt` | `46132B6CFBDC9891A56171F23A8A6083827B46B90EBC0A88251379B668074E92` |
| `TEST-com.ses.report.ReportReauthenticationSessionIntegrationTest.xml` | `80E9C200B929AD611186450090D127785CA37EAFBAFA1671AED26BD28B734EC8` |
| `com.ses.testsupport.TestIsolationAuditTest.txt` | `162E44A14D61B0C01A92CEE6FCC65EDF9150E0DE07F439B6DBE288A183596C01` |
| `TEST-com.ses.testsupport.TestIsolationAuditTest.xml` | `0A13FE5E06D1A1484DE76746AA1E1A0104AAC609AAF5B04921090922A9E93005` |

このH2統合testはSpring transaction rollbackを使わず、各testの一意合成user/run/deliveryを明示削除する。migration、shared SecurityConfig、DocumentService契約、application configは変更していない。
