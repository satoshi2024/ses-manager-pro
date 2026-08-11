# 実装前Decision Log

後続AIは未決事項を黙って決めてはならない。`blocking=yes` の項目が未確定なら、該当specの実装を停止し
発注者へ確認する。決定後は「決定」「決定日」「決定者」「根拠」を追記する。

| ID | blocking | 決めること | 推奨既定 | 影響spec | 状態 |
|---|---|---|---|---|---|
| G0 | yes | 顧客ごと単独DBか、共有DB SaaSマルチテナントか | 当面は顧客ごと単独DB。共有DB販売が確定した時だけ全表tenant_id化 | 全spec | 決定済（2026-07-26） |
| G1 | yes | 第1IdPとMFA方式 | Entra ID OIDC、全内部user MFA、管理者FIDO2、local TOTP break-glass 2アカウント | identity/portal | 決定済（2026-07-26） |
| G2 | yes | 派遣・準委任・フリーランス・取適法の法務監修者 | 公式資料+L0+独立Reviewでprovisional開発を進め、workplace実actor承認+動的policyを満たす実在external ReviewをM/本番gate化 | BP/compliance/archive | 開発gate決定済。R19実装decision deltaはR10 acceptance待ち |
| G3 | yes | 外部ポータルの公開ドメイン、利用規約、本人確認 | 別subdomain/chain、招待制、別identity、全portal user TOTP MFA | portal | 決定済（2026-07-26） |
| G4 | yes | freeeの契約プランと利用可能API、仕訳方針 | freeeを会計の正、公式OAuth/API+CSV fallback、legal entity別connection | accounting | 決定済（2026-07-26） |
| G5 | yes | Peppol Certified Service Provider | ファーストアカウンティングPeppol AP API、provider adapter、PDF/email併存 | JP PINT | 決定済（2026-07-26） |
| G6 | yes | 雇用勤怠の正（本システム/freee/客先） | 本システムを雇用勤怠の正、客先工数分離、freeeはdownstream/照合 | attendance | 決定済（2026-07-26） |
| G7 | no | 承認金額閾値と承認者 | 組織上長→財務/管理者。閾値は設定画面で管理 | approval | 未決 |
| G8 | no | 顧客/BPポータルで公開する文書種別 | 顧客=見積/注文請/契約/検収/請求、BP=発注/検収/BP請求/支払状況 | portal | 未決 |
| G9 | no | 要員経費の精算先 | 本システムで申請・承認、会計確定はfreee | engineer/accounting | 未決 |
| G10 | no | AI実プロバイダとデータ送信許可 | mock/ruleを既定維持。実AIはPIIマスキングとDPA承認後 | AI/security | 未決 |

## G2 R21 decision delta rework（2026-08-11）

- R21独立ReviewはFAIL（P0=0、P1=4、P2=1）。R19-P1-01は`OPEN / DECISION_DELTA_REWORK_REQUIRED`であり、
  `ACCEPTED_FOR_IMPLEMENTATION`、V102、DDL、Java、HTML、JS、CSS、message、test、seed、DB変更を禁止する。
- docs-only reworkで、operation idempotency ledger、mapping inclusive effective period/future/expired、transition別gate hash、
  delivery時FULL/MASK/LIMITED immutable rendition、専用credential AES-GCM/key rotation/prod fail-closed、source freeze triggerを決定する。
- 新decision IDは`G2-IDEMPOTENCY-01`、`G2-EFFECTIVE-PERIOD-01`、`G2-DELIVERY-IMMUTABILITY-01`、
  `G2-CREDENTIAL-CRYPTO-01`、`G2-SOURCE-FREEZE-01`。旧11 ID、tenant/workplace/G0、V102〜V108予約は維持する。
- T066未完了、S10 `IN PROGRESS`、S12 `NOT READY`、ACTIVE化・本番generate/delivery・production authorization禁止を維持する。

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

## G2 開発gate改訂記録

- ID: G2-DEV-GATE
- 決定: `コンプライアンス責任者`はruntime roleとし、特定の自然人を開発時に固定しない。公式資料、版、確認日、
  effective periodを持つmappingがL0と独立Reviewを通過した時点を`PROVISIONAL_REVIEWED`とし、task 0完了および
  後続開発を許可する。runtime assignment、実actorの承認event、外部専門家Reviewは`ACTIVE`化、M PASS、
  法定帳票の本番交付に必要なrelease gateとする。
- 決定日: 2026-08-09
- 決定者: 発注者
- 根拠/理由: 開発中の個人指名を要求すると、未実装のruntime assignmentと承認eventがtask 0の前提になる循環gateを
  生じる。role、権限、監査、未指名時fail-closedを先に実装し、実在actorは運用開始時に有効期間付きで指名する。
  交代時は旧assignmentを終了して新assignmentを追加し、過去の承認・帳票snapshotを上書きしない。
- 影響するspecへ反映するファイル: `gate-decisions-g1-g6.md`、`README.md`、
  `dispatch-outsourcing-compliance-ledger/requirements.md`、`design.md`、`tasks.md`、`field-mapping.md`、
  `review-ledger.md`、S10/R10派工対話、T060 task対話。

## G2 R19-P1-01 implementation decision delta

- ID: `G2-R19-IMPLEMENTATION-DELTA`
- 状態: `PROPOSED_FOR_R10_REVIEW / ACCEPTED_FOR_IMPLEMENTATION待ち`
- 発注者指示日: 2026-08-11
- 決定案: mapping=tenant scope、assignment=workplace scope、contract workplace=profileからserver-side解決、
  tenant ACTIVEとworkplace delivery authorizationを分離する。reviewer typeは完全動的設定、policyはmapping versionへfreezeし、
  group AND/type OR/minimum distinct reviewerで評価する。approval/external review/statusはappend-only reducer、
  mapping/policy/gateの3 canonical hash、ACTIVE 18-step transaction（旧R19案）、formal generate/preview、過去downloadを固定する。
- data model: 旧deltaの「9 physical table」案をR21で具体化し、現行候補は9 domain table + 共通`t_compliance_operation_ledger`、
  source freeze trigger、`t_document_delivery`のmapping/policy/gate/rendition/snapshot列とする。
- migration: common V99は永久欠番、migration-dev V100はcommon再利用禁止、common V101は既存用途維持、
  S10 follow-up=V102、S12〜S17=V103〜V108。V84/V85/V101を変更しない。
- G0: deployment tenantはserver設定から取得しrequest値を信用しない。現行独立DBを維持し、共有DB完成とは扱わない。
- history: `GATE-T066-HISTORY`は`TRACKED P2 / production release gate`へ分離し、S10 PASS/S12開始は阻害しない。
  未実装・未受入であり、対象history fieldを必要とするproduction帳票は禁止する。
- 実装gate: R10が`ACCEPTED_FOR_IMPLEMENTATION`を明示するまでdocs-only。R19-P1-01/T066を実装担当がcloseせず、
  ACTIVE化、本番generate/delivery、production authorization、S12開始を行わない。
- 詳細: `dispatch-outsourcing-compliance-ledger/g2-gate-decision-delta-r19-p1-01.md`。

## 決定テンプレート

```text
- ID: Gx
- 決定:
- 決定日:
- 決定者:
- 根拠/契約プラン/法務確認資料:
- 影響するspecへ反映したファイル:

## productivity-search-saved-view (S05) スコープ調整決定記録

- ID: S05-SCOPE
- 決定: 保存ビュー（R3.1）は「検索抽出フィルタ (filter)」および「ページサイズ (pageSize)」の保存に限定し、表示列選択 (columns) および動的ソート (sort) は次回機能拡張へ縮小する。一括操作（R4.1/R4.2）は要員・案件のステータス一括変更（2段階 REST API `/preview` -> `/apply`）に限定し、画面上のチェックボックス選択ツールバー・モーダルおよび担当変更・タスク作成・エクスポート選択を次回機能拡張へ縮小する。
- 決定日: 2026-07-31
- 決定者: 発注者（Round 3 独立 Review 判定の明示決定）
- 根拠/理由: UI コンポーネント依存と複数画面への影響を分離し、API 基盤およびコア機能のセキュリティ・整合性を最優先で確立するため。
- 影響するspecへ反映したファイル: `.kiro/specs/productivity-search-saved-view/requirements.md` (R3.1, R4.1, R4.2, §9)、`design.md` (§4, §5, §9)、`tasks.md` (B1, B2)。
