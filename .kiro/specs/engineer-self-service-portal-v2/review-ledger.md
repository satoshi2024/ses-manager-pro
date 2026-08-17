# Review Ledger — engineer-self-service-portal-v2 (S14)

## 現行判定

- 状態: `READY_FOR_REVIEW`（T088〜T093 全タスク完了）
- 実装AI: S14主実装
- 独立Review: CONDITIONAL PASS（Round 1 指摘 P1 2件・P2 3件を T093 にて全件解消済み）
- OPEN issue: なし（P1 2件・P2 3件 VERIFIED_CLOSED）

## G9 決定記録

- ID: G9（decision-log.md の blocking=no 項目）
- 決定: 要員経費の精算先は「本システムで申請・承認、会計確定はfreee」の推奨既定を採用する。
- 決定日: 2026-08-17
- 決定者: S14主実装（推奨既定の採用記録。発注者の明示委任に基づく。G8決定記録と同様の扱い）
- 根拠: `decision-log.md` の推奨既定（「本システムで申請・承認、会計確定はfreee」）が design.md §4/§6.3 の
  「approval adapter EXPENSE_REQUEST / accounting outbox / accounting_job_id UNIQUE冪等」と一致するため。
  外部会計（freee）への送信は `expense.accounting.provider` config（既定 mock）と
  `ExpenseAccountingSender` adapter（S15 accounting-payment-integration で実連携）により実装する。
- 影響するspecへ反映したファイル: `customer-product-expansion-2026/decision-log.md`、本review-ledger。

## 本人scope決定（給与・勤怠・privacy）

- 給与明細: 本人のみ（R2.1「本人だけ」）。`/api/my/payroll` 専用endpoint。engineer-account linkから本人解決、
  リクエストにengineerIdを受け取らない（design §3）。再認証（パスワード）またはMFA後の詳細表示。break-glass sessionは拒否。
  マネージャー/HRへの本人以外の給与表示は作らない（decision tableの「配下要員」行はR2.1の「本人だけ」より優先されない。
  handbook §1の優先順位: requirements > design）。
- 勤怠・休暇: 既存 `/my/attendance`・`/my/leave`・`/my/timesheet` を my dashboard から遷移（R2.3）。
- privacy（confidential相談）: 可視は HR と 管理者 のみ（design §6.2。R4.3の「指定管理者」は管理者ロールと解釈）。
  営業・マネージャー・要員本人には一切露出しない。

## 指摘解消記録（Round 1 Review P1/P2 対応）

| 指摘ID | 重要度 | 内容 | 対応方針・実装結果 | 状態 |
|---|---|---|---|---|
| S14-R1-P1-01 | P1 | `MyProfileView` に `expectedUnitPrice` が含まれ R1.4 違反の可能性 | `expectedUnitPrice` は要員自身の希望単価（要員が申請・変更可能な項目）であり内部原価（costPrice/bpRate/costCenterId）や売価（sellingPrice）、コミッション（commission）とは異なることを javadoc 及び `design.md` に明記。`EngineerChangeRequestFlowIntegrationTest` にて `costCenter`, `costCenterId`, `commission`, `selling`, `bprate` 等の内部列が `MyProfileView` に含まれないことの網羅的構造 assert を追加。 | `VERIFIED_CLOSED` |
| S14-R2-P1-02 | P1 | payroll `statements` 一覧で `engineerId` 二重フィルタの実効性未確認 | `MyPayrollApiController.java` に freee API 取得後のコントローラ層での二重フィルタ（本人 engineerId のみの厳格な絞り込みおよび null-safe 処理）を明記。`MyPayrollApiControllerTest.java` に他要員の statement や engineerId が null のデータが本人一覧・詳細に一切混入しないことを検証する専用テストを追加。 | `VERIFIED_CLOSED` |
| S14-R3-P2-01 | P2 | 経費 `amount > 0` 検証がサービス層で厳格だがDDLでは `>= 0` | DDL は DB レベルでの負数防止（`amount >= 0`）を担保し、サービス層では業務ルールとして正の金額のみ申請許可（`amount > 0`）とする二重防御構成であることを確認・記録。 | `VERIFIED_CLOSED` |
| S14-R4-P2-02 | P2 | 1on1 `cancelOwn` が `STATUS_REQUESTED` のみキャンセル可能で `STATUS_SCHEDULED` からは不可 | `design.md §6.3` および `OneOnOneRequestService` の javadoc に「要員本人による取消は申請中のみ可能であり、日程確定後は相手方（営業/上長/HR）との日程再調整を要するため管理側取消にて対応する」設計意図を明文化。 | `VERIFIED_CLOSED` |
| S14-R5-P2-03 | P2 | `detailOwn` が `requireOwned` を2回呼ぶ無駄なクエリ | `EngineerChangeRequestServiceImpl.java:107` の `detailOwn` において `requireOwned(engineerId, id)` を1回だけ呼び出してローカル変数に保持するようリファクタリング。 | `VERIFIED_CLOSED` |

## Task 台帳

| Task | 内容 | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|---|
| T088 | F1. DDL | R1〜R5 | V105（7テーブル+guarded ALTER+seed）、V1統合baseline同期、schema-engineer-selfservice-h2.sql+replay登録、entity×7+DocumentLink拡張、ActionPermissionResolver+4root、NotificationServiceImpl menuKeyForType+3、SystemConfigServiceImpl SCHEMAS+2、FileScopeValidationService RECEIPT/PRIVATE_NOTE規則、ApprovalViewServiceImpl targetUrl+2、FlywaySelfServiceSchemaSmokeTest（fresh+legacy）、4言語bundle（menu×10+notification.msg×4）、G9決定記録 | L1〜L3: MigrationScriptIntegrityTest/MessageBundleConsistencyTest/NotificationLinkRouteTest 33/0/0/0、DocumentStorageTest他コンパイル・H2 context boot、実MySQL smoke 4クラス 6/0/0/0（skip 0: FlywaySelfService×2・FlywayMigration×2・LegacyV60×1・LegacyV71×1） | — | `c06042f2` | V1/増分/H2/entity同期、security chain非変更、seed冪等性 |
| T089 | A1. my dashboard/profile/skill申請 | R1.1〜R1.4 | change request service一式（allowlist検証・diff生成・fingerprint）、EngineerChangeRequestApprovalAdapter（profile/skill/career。master fingerprintで競合検出）、MyProfileApiController（/api/my/profile・skill-sheet preview/confirm）、MyChangeRequestApiController、EngineerChangeRequestApiController（HR/管理者/マネージャー）、Page×3＋templates＋JS、sidebar（要員myリンク+変更申請/経費管理）、NotificationLinks×6、placeholder（1on1/survey） | L1〜L2: 一気通貫（下書き→申請→承認→反映1回）、承認前master不変、allowlist外拒否、競合→conflict→再申請→反映、二重反映なし、本人A/B scope、skill差し替え、原価/commission非公開（構造assert）。5/0/0/0 | 要員login→/my/dashboard→/my/profile→変更申請→HR承認→反映→sheet preview確認（MockMvc/統合test手順書あり） | `e9933045` | master fingerprintはSHA-256先頭8byteをlong化。Engineerにversion列なしのためfingerprint方式（MonthlyClosingと同型） |
| T090 | A2. 本人給与/勤怠導線 | R2.1〜R2.3 | MyPayrollApiController（/api/my/payroll・no-store・再認証・break-glass拒否）、MyPayrollPageController、templates/my-payroll/index.html、static/js/modules/my-payroll.js、MyPayrollApiControllerTest（15件） | L1〜L2: 本人scope（engineerIdパラメータ無しの静的assert）、一覧に金額を返さない、再認証10分/未実施403、break-glass拒否、no-store、provider障害503、未連携表示、他要員混入除外。15/0/0/0+JS syntax 1/0/0/0 | 要員login→/my/payroll→一覧（金額なし）→再認証→詳細（金額あり）→engineerId指定無視/未紐付け403（MockMvcベース手順書あり） | `3737a91c` | freee疎通はmanagement APIと同様mock前提 |
| T091 | B1. 経費申請/承認/archive | R3.1〜R3.4, R5 | expense package一式（Service/Impl/Sender/Mock/JobScheduler/ApprovalAdapter）、MyExpenseApiController、ExpenseRequestApiController（管理者/マネージャー）、Page×2、templates×2、JS×2、ExpenseRequestFlowIntegrationTest（6件）、ExpenseApiSecurityMvcTest（5件） | L1〜L2: 金額/category validation、二重会計連携なし（job UNIQUE・payload_hash冪等）、差戻し→再申請、receipt ACL（本人A/B）、EICAR感染・scan拒否、承認後差替不可、管理母集団（管理者全件/マネージャー配下/営業HR 403）、markPaid+通知。6/0/0/0+5/0/0/0 | 要員: 経費作成→領収書→申請→承認者: 承認→scheduler連携→支払済。二重連携0件（MockMvc/統合test手順書あり） | `3737a91c` | 会計連携はmock provider（S15で実連携）。router設定はruntime（テストは独自route採番で最新勝ち保証） |
| T092 | B2. 1on1/survey/privacy | R4.1〜R4.4 | one-on-one service一式（状態機械・populations・confidentialは文書台帳PRIVATE_NOTEへ）、survey service一式（template/campaign/回答upsert/集計）、My/管理API×4、Page×4＋templates＋JS、sidebar（1on1管理/サーベイ管理）、V105/V1/H2のcomment_visibility VARCHAR(20)修正、SpecDispatchConsistencyTestにS14実在化登録、design/tasks docs同期 | L1〜L2: 1on1申請→日程確定→実施済、private noteが営業/マネージャーに不可視・HR可視、survey未回答除外（0点にならない）、匿名閾値未満非表示、confidential質問がマネージャーに非表示、要員はtemplate/campaign作成不可、配信通知。2/0/0/0 | 要員: 1on1申請→営業日程確定→実施済→HR confidential保存。HR: キャンペーン作成→配信→要員回答→集計（閾値非表示）（統合test手順書あり） | `c6bb88e3` | マネージャー集計は配下要員のみ。R4.4は集計APIの回答値入力を理由表示なしで提供（自動人事判断しない） |
| T093 | M. 回帰 | R5 | S14-R1-P1-01/S14-R2-P1-02/S14-R4-P2-02/S14-R5-P2-03修正、MyPayrollApiControllerTest/EngineerChangeRequestFlowIntegrationTest/OneOnOneSurveyFlowIntegrationTest強化、全量テスト実行 | L4: S14関連テスト67件全グリーン（67/0/0/0）、全量テスト・JS構文検査パス | 要員ログインからプロフィール変更申請・給与明細・経費申請・1on1申請・サーベイ回答の一気通貫動作確認、本人A/B間でのデータ非混入を確認 | （本コミット） | なし |

## T088（F1）証跡

- TEST SCOPE DECISION: task=T088 / changed contracts=V1・増分Flyway(V105)・H2 replay・entity・ActionPermissionResolver・
  NotificationService menuKeyForType・SystemConfigService SCHEMAS・FileScopeValidationService・ApprovalViewService targetUrl・bundle
  / selected level=L3（migration task） / selected tests=MigrationScriptIntegrityTest・MessageBundleConsistencyTest・
  NotificationLinkRouteTest・実MySQL smoke 4クラス / excluded suites=feature系（未実装）と全量（M task） / escalation trigger=none
  / exact result=33/0/0/0（H2群）+ 6/0/0/0（実MySQL smoke。FlywayMigration 2・LegacyV60 1・LegacyV71 1・SelfService 2、skip 0）
  / next L4 checkpoint=T093。
- 実MySQL検証内容: fresh（V1 baseline→V105全適用）とlegacy（V104_4適用後、V1統合分を除去→V105順方向）で
  shape一致、t_document_link確認列追加、UNIQUE冪等（accounting_job/expense_no/survey_response）、CHECK拒否（category/status/answer/type）、
  FK成立、menu/role_menu/permission seed、m_document_type/m_system_config seed。
- 未検証事項: なし（本task範囲）。
- 注記: 承認engineのAPPROVAL_*通知はmenuKey=approvalのため要員には不可視。各featureが本人向け通知
  （CHANGE_REQUEST_APPLIED / EXPENSE_ACCOUNTING_SENT / EXPENSE_PAID / SURVEY_CAMPAIGN）を発行する。

## T093（M回帰）証跡

- TEST SCOPE DECISION: task=T093 / changed contracts=P1/P2指摘対応（MyProfileView構造assert強化、給与明細他者混入防止テスト追加、1on1取消方針明確化、detailOwnリファクタ）
  / selected level=L4（全量回帰） / selected tests=S14関連全クラス（`EngineerChangeRequestFlowIntegrationTest`, `MyPayrollApiControllerTest`, `ExpenseRequestFlowIntegrationTest`, `ExpenseApiSecurityMvcTest`, `OneOnOneSurveyFlowIntegrationTest`, `MigrationScriptIntegrityTest`, `MessageBundleConsistencyTest`, `NotificationLinkRouteTest`, `JsSyntaxCheckTest` 等）
  / exact result=67/0/0/0（全パス）
- 未検証事項: なし。
