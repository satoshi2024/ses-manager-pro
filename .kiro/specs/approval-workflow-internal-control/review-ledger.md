# review-ledger — approval-workflow-internal-control (S07)

現行判定: **NOT READY（着手不可・STOP）**

## Readiness Gate 判定（2026-08-02）

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
