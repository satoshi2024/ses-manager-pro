# review-ledger — approval-workflow-internal-control (S07)

現行判定: **IN PROGRESS（T041/T042完了、実装対話は継続中。独立Review未実施）**

## T042(F1) route/request/action/delegation DDL 完了（2026-08-02）

TASK CONTRACTに基づき実装した。詳細は`tasks.md`のF1エントリと`design.md` §8「F1実装注記」を正とする。

- **requirements ID**: R1.1〜R1.4、R2.1〜R2.2、R2.4、R3.4、R4.1
- **変更file**:
  - migration: `V75__approval_workflow.sql`（新規5テーブル + `ActionPermissionResolver`用`approval.*`権限seed）
  - H2: `sql/schema-approval-h2.sql`（新規）、`application-test.yml`（schema-locations追加）、
    `sql/permission-group-seed-h2.sql`（`approval.*`のgroup権限seed追加）
  - entity: `ApprovalRoute`/`ApprovalRouteStep`/`ApprovalRequest`/`ApprovalAction`/`ApprovalDelegation`
  - mapper: `ApprovalRouteMapper`/`ApprovalRouteStepMapper`/`ApprovalRequestMapper`/`ApprovalActionMapper`/`ApprovalDelegationMapper`
  - service: `com.ses.service.approval`パッケージ（`ApprovalEngineService`/`ApprovalTargetAdapter`/
    `RouteResolverService`/DTO群）+ `RouteResolverServiceImpl`/`ApprovalEngineServiceImpl`
  - controller: `ApprovalApiController`（`/api/approval/requests`配下の汎用engine API）
  - DTO: `com.ses.dto.approval`パッケージ
  - `ActionPermissionResolver.java`（`approval`をRESOURCE_NAMESへ登録。CRM-R2-P1-01の再発防止）
  - `messages{,_en,_ko,_zh_CN}.properties`（`error.approval.*` 5key×4言語）
  - `FlywayMigrationSmokeTest.java`（V75のtable/column/index/action権限assert追加）
  - test: `RouteResolverServiceTest`（新規, 8件）、`ApprovalEngineServiceTest`（新規, 12件）
- **DDL/H2/MySQL同期**: 新規テーブルのみのためV1は無変更（CRM V73と同方針、design冒頭に明記）。
  H2は`schema-approval-h2.sql`（FK無し、CLOB化、CRM/BPと同じ方針）。MySQL smoke assertは
  `FlywayMigrationSmokeTest`へ追加したがDocker未導入のため本環境では未実行（release gate継続）。
- **実行testと件数/結果**:
  - `RouteResolverServiceTest` 8/8 PASS（金額帯inclusive境界、該当routeなし拒否、負数絶対値、
    金額なし申請の専用route、自己承認候補ゼロでの拒否、組織具体性/金額帯狭さ/version_no新しさの決定順）
  - `ApprovalEngineServiceTest` 12/12 PASS（申請直後in_review到達、単一承認者の終端、
    並列group全員承認での進行、並列group1人却下での即終端、自己承認のみのroute拒否、
    非承認者からのapprove拒否(403)、代理期間内/期間外、本人と代理の同時解決での先着1件のみ有効、
    同一slotへの二重clickの冪等性、終端到達後retryの状態不正エラー、versionのCAS(0件更新)確認）
  - 共有基盤への直接影響範囲の回帰: `MigrationScriptIntegrityTest`・`ActionPermissionMatrixTest`・
    `ActionPermissionResolverTest`・`MessageBundleConsistencyTest`（4クラス計51件）全PASS
  - 上記6クラス合計 71件 / failures 0 / errors 0 / skipped 0。`mvn -o compile`・`mvn -o test-compile` BUILD SUCCESS。
  - `git diff --check` exit 0
- **Demo**: `RouteResolverServiceTest`の境界fixture群で金額帯境界のroute解決を自動確認。
  route未設定時の`notifyAdminsOfConfigGap`呼び出しはコードレビューで確認（実通知送信の目視Demoは
  B1のSLA/通知実装と合わせて実施）。実ブラウザ/curl Demoは対象画面が無いF1段階では実施せず、
  A1（inbox UI）着手後またはM taskで行う。
- **未検証事項**:
  1. MySQL fresh/legacy smokeの実機実行（Docker未導入環境のため）。
  2. 実通知送信（`NotificationService.publishToUser`呼び出し先の実際の到達）はB1と合わせて確認。
  3. 本番相当のbrowser Demo（対象画面が無いため、A1/Mへ持ち越し）。
  4. G7の正式decision-log記録は未実施（推奨既定採用として`operation-inventory.md`へ記録済みだが、
     `decision-log.md`自体の更新は発注者/統合担当の所掌）。
- **既知のトレードオフとロールバック**: `design.md` §8に記載の5件の実装注記（二重action防止キーの変更、
  draft/requested collapse、approver_type範囲限定、resolveApprovers実現方法、target_version/`@Version`の
  F2持ち越し、escalateのB1持ち越し）はいずれも既存資産の再利用または後続task境界の明確化であり、
  要件変更ではない。ロールバックは本task分の新規file削除 + `V75__approval_workflow.sql`の取り下げ
  （適用済みでなければ）で完結する。適用済みの場合は新migrationでDROPする（V75自体は編集しない）。
- **base/head commit**: 未commit（working tree、ユーザーからのcommit指示があれば別途実施）。
- **Review開始条件**: 未成就。F2（5 target adapters）着手前、またはA1/A2/B1と合流するタイミングで
  主担当が独立Reviewへ提出する。現時点でF2以降を自動開始しない。

## Readiness再確認とT041完了（2026-08-02）

前回STOP後、`main`側でCRM(S08)がRound 8独立再ReviewでPASS確定し（`spec-execution-ledger.md` row8、
Base `94f95083f178b812caa43782a5e00d09a8d6f324` → Head `042bd0cfb8139466eb7199a7d625adfb181c8563`、
L4全量1,280/0/0/0、MySQL fresh/legacy/partial/repair全4経路成功、desktop/390px全role Demo確認済み）、
central ledger row7（approval-workflow-internal-control）が`NOT READY`→`READY`へ更新された。
working treeもclean化された（HEAD `6645644`）。これによりT041(0)の開始条件が成就したため着手した。

```text
READINESS（再確認）
- spec/task: approval-workflow-internal-control T041(0)
- handbook version: v2.0
- base commit / working tree: main 6645644、clean
- dependency merge/review evidence: BP(S06) PASS、CRM(S08) PASS（Round 8、central ledger row8）確認
- migration latest/reserved: 実在latestはV74系列。本specの予約V75は依然空き番号（F1着手時に再確認する）
- G7: blocking=no、decision-log推奨既定（組織上長→財務/管理者。閾値は設定画面で管理）を採用し、
  operation-inventory.md §1へ明記。decision-log.md自体の正式decision記録は発注者/統合担当の所掌として
  別途依頼する（本task 0はinventory担当であり、decision-log更新権限を僭称しない）
- decision: GO（T041のみ。F1はTASK CONTRACTを別途提示してから着手する）
```

### T041(0) 成果物・実測

- 成果物: [`operation-inventory.md`](operation-inventory.md)（対象5業務・9操作の現endpoint/service/申請field/route/SLA/職務分離表）。
- 変更file: `operation-inventory.md`（新規）、`tasks.md`（task 0を`[x]`化）、本ファイル。production code(Java/SQL/JS/HTML)は無変更。
- 対応requirements ID: R1.1, R1.2, R1.3, R2.1, R2.2, R2.4, R4.1（各行に付与、詳細はoperation-inventory.md §2）。
- test: L0。`git diff --check` exit 0。表の全9操作にendpoint/service/requirements IDが存在することを目視確認。
- Demo: 財務/管理者向け提示内容としてoperation-inventory.md §4に記録（実ブラウザ/実会議での提示はF1〜Mのrelease gateへ継続）。
- 未検証事項: G7の正式decision-log記録（発注者/統合担当待ち）。operation-inventory.md §3の3件の非対称性（月次締めロック未呼び出し、単価改定の状態非依存、BP支払確定のlock方式）はF1のdesign.md決定表反映が必要な申し送りであり、F1着手前に解消する。
- rollback: 本task分のドキュメント3ファイルをrevertするのみ（production変更なし）。
- Base/Head: Base `6645644`（変更前）→ 本task分のドキュメント変更のみ、コミットは未実施（ユーザー指示によるcommit要求があれば別途実施）。

## Readiness Gate 判定（2026-08-02、旧・STOP時点の記録として保持）

`execution-review-handbook.md` v2.0 §4 Readiness Gateに従い、T041着手前の確認を実施した結果、
開始条件が未成就のため production file・SQL・`tasks.md`のcheckboxを一切変更せず停止する。

```text
READINESS
- spec/task: approval-workflow-internal-control T041(0)〜T047(M)
- handbook version: v2.0
- requirements/acceptance: requirements.md R1〜R6（未着手）
- base commit / working tree: main 182dce7（作業木はdirty。ui-scale-regression-hardening-200系の
  未commit変更（ProposalApiController/WorkRecordApiController/DashboardSummaryDto等）と、
  CRM T049関連とみられる未commit変更（LeadServiceImpl.java、CrmLeadPaginationTest.java）が
  本specと無関係に存在する。本spec用のbranch/worktreeは未作成）
- dependency merge/review evidence: BP master(S06)はPASS（Head 4d34212）。
  CRM(S08)は central ledger row8で状態`IN PROGRESS`。CRM tasks.mdのtask M（回帰）が
  `- [ ]`未完了（「L4全量とdesktop/390px全role browser Demoは最終gateとして残る」）。
  Round 7時点でP0=0/P1=0だがM未完了のためS08はPASSに至っていない
- migration latest/reserved/gaps: 実在latestはV74系列（V74, V74_1, V74_2）。
  本specの予約はV75（tasks.md冒頭で確定済み）。V72/V59は永久欠番。今回のmerge済み最新確認では
  ユーザー指示にあった「V71」は既にBP procurement fix（V71__bp_company_fix_and_procurement.sql）に
  使用済みで、本spec着手時の空き番号ではない
- mandatory environments: 未確認（STOPのため未着手）
- file ownership: 未宣言（着手条件未成就のため子Agentへの割当も未実施）
- assumptions: G7はdecision-log.mdでblocking=no。推奨既定「組織上長→財務/管理者。閾値は設定画面で管理」を
  採用する前提を置くことは可能だが、spec-execution-ledger.mdの開始条件（row7）は
  「CRM(S08)のT049〜T053完了とG7方針記録後にS07」を明示しており、G7の推奨既定採用を記録するだけでは
  開始条件の後半しか満たさない
- blockers:
  1. CRM(S08) T049〜T053が未完了（central ledger row8 = IN PROGRESS、CRM tasks.md task M `- [ ]`）
  2. G7の方針記録（推奨既定採用の明記、またはG7決定）が本specのdecision-log/review-ledgerへ未記録
  3. ユーザー指示の着手条件記載「Migration: V71」がmerge済み最新（V74系列）と不整合。
     正しい予約はV75（tasks.md冒頭、central ledger row7、dependency-matrix該当節と一致）
- decision: STOP
```

## Blocker詳細

| # | Blocker | 根拠 | 影響task |
|---|---|---|---|
| 1 | CRM(S08)未完了 | `spec-execution-ledger.md` row8 = `IN PROGRESS`。`crm-contact-opportunity/tasks.md`のtask M が `- [ ]`、備考「L4全量とdesktop/390px全role browser Demoは最終gateとして残る」。`crm-contact-opportunity/review-ledger.md` Round 7時点でP0=0/P1=0だがL4全量・browser Demo未実施 | T041〜T047全件（0→F1→F2→(A1\|\|A2\|\|B1)→M）。dependency-matrix「approval」行、parallel-execution-plan「Wave 1-B」、central ledger row7がいずれも「CRM完了後にS07」と明記 |
| 2 | G7方針記録が未記録 | `decision-log.md` G7: blocking=no、推奨既定「組織上長→財務/管理者。閾値は設定画面で管理」、状態=未決。central ledger row7の開始条件は「G7方針記録後にS07」であり、推奨既定を採る場合もその旨を明記した記録が要求されている | T041（Objective自体が「G7と対象操作inventory」）、およびF1のroute金額帯設計の前提 |
| 3 | 着手条件のMigration番号不整合 | ユーザー指示「Migration: V71」に対し、`db/migration`実在最新はV74系列。`tasks.md`冒頭・`spec-execution-ledger.md` row7・`dependency-matrix.md`はいずれも本specの予約をV75と確定済み | F1（DDL）着手時の採番。今回はSTOPのため実際の採番作業は行っていない |

## 必要な発注者回答

1. CRM(S08)を「完了・merge済み」とみなしてS07着手を許可するか、CRM task M（`mvn test`全量、fresh/legacy MySQL smoke、desktop/390px browser Demo）とCRMの独立Review PASS確定を待つか。
2. G7について、decision-log推奨既定（組織上長→財務/管理者。閾値は設定画面で管理）をそのまま採用してよいか、または別の決定値を出すか。採用する場合、`decision-log.md`のG7行へ「決定」「決定日」「決定者」を記録してよいか（本spec側のT041ではなく、decision-log自体の更新は発注者/統合担当の所掌と理解している）。
3. 着手条件に記載された「Migration: V71」は本spec着手時点のmerge済み最新（V74系列）と不整合であり、正しい予約はV75である旨の認識合わせ。

## 再開条件

- `spec-execution-ledger.md` row8（CRM）が `PASS` に更新され、Base/Head commitが記録され、`main`へmerge済みであることを確認する。
- G7について、decision-log推奨既定の採用または発注者決定が`decision-log.md`へ記録されていることを確認する。
- 再開時に`db/migration`のmerge済み最新を再確認し、V75が依然空き番号であることを確認してからT041に着手する（衝突していれば後発である本specを繰り上げ、前の欠番は埋めない）。
- 再開後は本ファイル冒頭の「現行判定」を更新し、T041のTASK CONTRACTから実装を開始する。

## 変更ファイル

- 本ファイル（新規作成）。production code / SQL / 他specファイル / `tasks.md`のcheckboxは変更していない。
