# 実装前Decision Log

後続AIは未決事項を黙って決めてはならない。`blocking=yes` の項目が未確定なら、該当specの実装を停止し
発注者へ確認する。決定後は「決定」「決定日」「決定者」「根拠」を追記する。

| ID | blocking | 決めること | 推奨既定 | 影響spec | 状態 |
|---|---|---|---|---|---|
| G0 | yes | 顧客ごと単独DBか、共有DB SaaSマルチテナントか | 当面は顧客ごと単独DB。共有DB販売が確定した時だけ全表tenant_id化 | 全spec | 決定済（2026-07-26） |
| G1 | yes | 第1IdPとMFA方式 | Entra ID OIDC、全内部user MFA、管理者FIDO2、local TOTP break-glass 2アカウント | identity/portal | 決定済（2026-07-26） |
| G2 | yes | 派遣・準委任・フリーランス・取適法の法務監修者 | 公式資料+社内コンプライアンス責任者で開発し、専門家承認をM/本番gate化 | BP/compliance/archive | 決定済（2026-07-26） |
| G3 | yes | 外部ポータルの公開ドメイン、利用規約、本人確認 | 別subdomain/chain、招待制、別identity、全portal user TOTP MFA | portal | 決定済（2026-07-26） |
| G4 | yes | freeeの契約プランと利用可能API、仕訳方針 | freeeを会計の正、公式OAuth/API+CSV fallback、legal entity別connection | accounting | 決定済（2026-07-26） |
| G5 | yes | Peppol Certified Service Provider | ファーストアカウンティングPeppol AP API、provider adapter、PDF/email併存 | JP PINT | 決定済（2026-07-26） |
| G6 | yes | 雇用勤怠の正（本システム/freee/客先） | 本システムを雇用勤怠の正、客先工数分離、freeeはdownstream/照合 | attendance | 決定済（2026-07-26） |
| G7 | no | 承認金額閾値と承認者 | 組織上長→財務/管理者。閾値は設定画面で管理 | approval | 未決 |
| G8 | no | 顧客/BPポータルで公開する文書種別 | 顧客=見積/注文請/契約/検収/請求、BP=発注/検収/BP請求/支払状況 | portal | 未決 |
| G9 | no | 要員経費の精算先 | 本システムで申請・承認、会計確定はfreee | engineer/accounting | 未決 |
| G10 | no | AI実プロバイダとデータ送信許可 | mock/ruleを既定維持。実AIはPIIマスキングとDPA承認後 | AI/security | 未決 |

## G0 決定記録

- ID: G0
- 決定: 現在の正式な配備方式は、顧客ごとに独立したデータベースを使用する方式とする。現在は共有データベースSaaSの全表tenant_id化を実施しない。
- 決定日: 2026-07-26
- 決定者: 発注者
- 理由: 顧客間のデータ境界をデータベース単位で明確に保ち、現行の単一データソース、認証、ジョブ、ファイル、バックアップ運用を大きく変更せずに顧客別運用を開始できるためである。共有DB化に伴う全表、全SQL、非HTTP経路、キャッシュ、ファイル、外部連携、バックアップの同時改造による漏洩・移行・運用リスクを現時点で負わない。
- V59の扱い: V59は作成しない。従来のV59予約は取消し、永久欠番として保持する。将来、共有DB SaaSを再承認してtenant実装を再開する場合も、V59を補完・再利用せず、その時点のFlyway最新番号`latest + 1`を新しいmigration番号として採番する。V60以降が適用済みの場合、V59を追加してはならない。過去migrationの改変とout-of-order適用は禁止する。
- 共有DB方案の延期範囲: 全表`tenant_id`、`TenantContext`、tenant interceptor、tenant単位のUNIQUE/FK、共有DB用の認証・Scheduler・Async・Cache・File・Export・通知・Webhook、tenant単位backup/restore、全表cross-tenant isolation testを延期する。延期は未実装を意味し、実装済みとは扱わない。現行の独立DB境界、既存認証、データスコープ、ファイル参照検証は削除しない。
- 共有DB改造の再開条件: SaaS販売方式を正式に採用する事業決定、共有DBの契約・運用要件、法務/セキュリティ/バックアップ・復元要件、移行・容量計測計画が承認され、発注者がG0を再開決定した時点とする。その時点でtenant inventory、requirements、design、tasks、migration採番を再確認し、V59を再利用せず、当時の`latest + 1`から新しいtenant実装計画を作成する。現在のT002/F1を自動開始してはならない。
- 影響するspecへ反映したファイル: `.kiro/specs/README.md`、`customer-product-expansion-2026/README.md`、`dependency-matrix.md`、`parallel-execution-plan.md`、`gate-0-readiness-report.md`、`task-start-conversations.md`、`multi-company-tenant-isolation/requirements.md`、`design.md`、`tasks.md`、`tenant-inventory.md`。

## G1〜G6 決定記録

- 決定日: 2026-07-26
- 決定者: 発注者の明示委任に基づくCodex
- 決定内容: `gate-decisions-g1-g6.md` を唯一の詳細な正とする。
- 実装許可: G1〜G6のarchitecture decisionは確定したため、先行specとmigration条件を満たすtaskは設計・実装を開始できる。
- 本番gate: 法務専門家承認、実freee plan/company ID、Peppol契約/sandbox、法人別36協定/就業規則等は、
  未確認のまま本番releaseまたは該当M taskをPASSにしてはならない。
- 原則: 外部契約・専門家署名が未取得でも、公式contract fixture、mock、WireMock、provisional field mappingで
  基盤実装を進められる。実環境を確認したと虚偽記録してはならない。
- 影響するspec: identity、archive、BP、dispatch、portal、accounting、JP PINT、attendance、engineer portal。

## 決定テンプレート

```text
- ID: Gx
- 決定:
- 決定日:
- 決定者:
- 根拠/契約プラン/法務確認資料:
- 影響するspecへ反映したファイル:
```
