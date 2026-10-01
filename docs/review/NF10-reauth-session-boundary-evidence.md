# NF10 再認証grant session境界のP1修正記録

## 対象

- Finding: `PRD-NF10-001`（共有deliveryの`reauthenticated_at`により別sessionがpassword再認証を継承する）
- Worktree / branch: `C:\work\ses-nf10-remediation` / `codex/snf-prod-nf10-reauth`
- Base: `1443fb68943849a3260d7ebe005259f3eb609d60`
- 対象Head: local commit後に確定する。

## 実装

password再認証成功時にsession保存のgrantを発行し、session ID、現在Authentication instance、tenant、user、delivery、current recipient scope hash、10分expiryへ束縛する。共有`reauthenticated_at`は監査目的で更新するが、download認可には使わない。

downloadでは従来どおりtoken hashとdelivery ID/tenant/recipient/status/link期限、run/snapshot、最新recipient preview、現在role/組織scopeとscope hashを検証した後、session grantを検証する。scope/roleのDB上の権限をAuthentication authority比較へ置き換えていない。匿名principal、sessionなし、別session/tenant/user/delivery/scope、session ID rotation/logout、grant満了、Authentication instance/authority変更は拒否し、再認証を要求する。session replication/serializationでgrant内外のAuthentication参照が同一instanceでなくなった場合もfail-closedし、step-upをやり直す。

## 回帰範囲

- `ReportReauthenticationSessionIntegrationTest`: 実controller→実`ReportDeliveryServiceImpl`→MyBatis/H2。A未認証は拒否、A再認証後Aのみ成功、Bは同userでも拒否、B自身の再認証後成功。さらに現在recipient scope撤回、同sessionのAuthentication instance交換を拒否する。
- `ReportReauthenticationGrantTest`: tenant/delivery/scopeとsession境界、10分expiry、session ID rotation/logout、Authentication instance変更、role authority変更、匿名/sessionなしを検証する。
- `ManagementReportDeliveryApiControllerTest`: MVCがsession付きrequestをserviceへ渡すこと、匿名の有効そうなlink tokenがserviceへ到達しないことを検証する。
- `ReportDeliveryServiceImplTest`: 既存token・scope・reauth・cancel・expiry経路を新request引数で維持する。

## 検証状況

最初の定向実行では、H2 test fixtureがMySQL専用`DATE_ADD`を使っていたため修正し、匿名APIの実応答401にassertを合わせた。さらにgrant負境界の各ケースが前ケースのgrant削除に依存しないよう、各ケースを個別に再発行し、同一条件の成功確認後に一要素だけを変えて拒否を検証した。user ID不一致とrole authority変更も追加した。

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

最終Headには本節のtargeted実行記録と対応するlocal commit hashを追記してからAstraへ引き渡す。

全ファイルはSpring transaction下でseedし、test終了時rollbackする。migration、shared SecurityConfig、DocumentService契約、application configは変更していない。
