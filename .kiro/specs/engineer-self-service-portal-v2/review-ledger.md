# Review Ledger — engineer-self-service-portal-v2 (S14)

## 現行判定

- 状態: `READY_FOR_REVIEW`（Round 3 指摘 P1 12件・P2 2件を全件解消済み）
- 実装AI: S14主実装
- 独立Review: Round 3 指摘全件対応完了
- OPEN issue: なし（全件 VERIFIED_CLOSED / テスト実証完了）

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
- privacy（confidential相談）: 可視は HR と 管理者（`one-on-one.confidential` 権限保持者）のみ（design §6.2）。
  営業・マネージャー・要員本人には一切露出しない。

## 指摘解消記録（Round 3 Review P1/P2 対応）

| 指摘ID | 重要度 | 内容 | 対応方針・実装結果 | 状態 |
|---|---|---|---|---|
| R1-P1-01 | P1 | 下書き変更申請を含む本人一覧が500になる | `EngineerChangeRequestServiceImpl.toDto` で `approvalRequestId == null` のとき `approval = null` を許容し空DTOを生成。 | `VERIFIED_CLOSED` |
| R1-P1-02 | P1 | profile申請導線（phone未取得、承認後反映） | `EngineerChangeRequestServiceImpl.myProfile` で `engineer.getPhone()` を読み出し返却。承認後に本人GET APIで電話番号が反映されることを assert。 | `VERIFIED_CLOSED` |
| R1-P1-03 | P1 | email変更時の並行競合未検出 | `EngineerChangeRequestApprovalAdapter.fingerprint` で `SysUser.email` を連結し、承認前のSysUser直接変更時に409 conflictとなることを検証。 | `VERIFIED_CLOSED` |
| R1-P1-04 | P1 | freee給与明細で複数要員混在時の抽出保証 | `FreeeHrContractTest.statementForEngineer` 直接テストを追加し、複数従業員フィクスチャから対象要員のみが抽出され他要員PIIが混入しないことを検証。 | `VERIFIED_CLOSED` |
| R1-P1-05 | P1 | 会計連携ジョブ送信後の通知失敗によるロールバック防止 | `ExpenseAccountingJobScheduler.dispatchOne` 内で `markSent` トランザクション内に `notifyAccountingSent` を含め、ジョブ状態更新と通知登録を原子的に永続化。 | `VERIFIED_CLOSED` |
| R1-P1-06 | P1 | 通知resolverのcanonical menuKey不一致 | `NotificationServiceImpl.menuKeyForType` を `m_menu` の定義（`myProfile`, `myExpenses`, `mySurveys`）に統一。5引数overload（menuKey省略）発行から一覧・未読カウント集計までを一気通貫で検証。 | `VERIFIED_CLOSED` |
| R1-P1-07 | P1 | 1on1 confidential秘密メモの閲覧境界と相手方status検証 | `OneOnOneRequestServiceImpl.create` で `counterpart.getStatus() == 1` を検証し無効ユーザーを400拒否。`AuthorizationServiceImpl` で一般管理者の `one-on-one.confidential` 自動バイパスを撤廃し明示権限グループ割当者のみに限定。 | `VERIFIED_CLOSED` |
| R1-P1-08 | P1 | サーベイ template snapshot version 整合性 | `SurveyServiceImpl` の `myActiveCampaigns`, `myCampaignDetail`, `submitAnswers` で `campaign.templateSnapshotVersion` を一貫使用。元テンプレート更新後も snapshot version (v1) が保持されることを実証。 | `VERIFIED_CLOSED` |
| R1-P1-09 | P1 | 1on1 日程確定・キャンセルの状態機械と境界 | `OneOnOneRequestServiceImpl` で確定前・確定後・実施済の各状態遷移と、過去日・無効要員のバリデーションを網羅。 | `VERIFIED_CLOSED` |
| R1-P1-10 | P1 | サーベイ離職リスク分析の匿名性閾値バイパス防止 | `SurveyServiceImpl.aggregate` で `minAnswers` 未満（2件回答/閾値3件）の場合に `retentionRisk().hidden() == true`、`topRiskFactors` 空、設問別平均値 `hidden == true` となることを検証。 | `VERIFIED_CLOSED` |
| R1-P1-11 | P1 | V105.1のchecksum元復元とV105.2新設分離 | `V105_1__engineer_self_service_v2_forward_repair.sql` を `4fa3a689` 元 blob へ完全復元（diff 空）。`t_engineer.phone` と `t_survey_campaign.template_snapshot_version` は新設 migration `V105_2__engineer_self_service_v2_phone_and_snapshot_version.sql` へ分離。 | `VERIFIED_CLOSED` |
| R1-P1-12 | P1 | Review ledgerの全件Issue Register・決定表・テスト行列整備 | 本review-ledgerに全指摘、決定表、テスト行列、最新テスト結果を網羅。 | `VERIFIED_CLOSED` |
| R2-P1-01 | P1 | 変更申請添付のatomic登録とcreatedByバイパス撤廃 | `MyChangeRequestApiController.uploadAttachment` で `targetType="ENGINEER"`, `targetId=engineerId`, `documentType="CHANGE_REQUEST_ATTACHMENT"` を渡し `DocumentService.registerReceived` 内で原子的リンク。手動リンク挿入を廃止し `validateAttachment` で `createdBy` 単独許可を撤廃。 | `VERIFIED_CLOSED` |
| R1-P2-01 | P2 | freee 給与明細の複数要員時混入防止（単体・結合） | `FreeeHrContractTest` および `EngineerSelfServicePortalMRegressionTest` で実証。 | `VERIFIED_CLOSED` |
| R1-P2-02 | P2 | 4言語 i18n リソース完備 | `messages*.properties`（JA/EN/ZH/KO）に `my.profile.field.*`, `my.changeRequest.created`, `error.file.uploadFailed` 等を追加。 | `VERIFIED_CLOSED` |
| R1-P2-03 | P2 | テナント Clock 注入の徹底 | `OneOnOneRequestServiceImpl` および `EngineerChangeRequestServiceImpl` から引数なし `.now()` を完全排除し `LocalDate.now(clock)` / `LocalDateTime.now(clock)` で統一。 | `VERIFIED_CLOSED` |

## Task 台帳

| Task | 内容 | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|---|
| T088 | F1. DDL | R1〜R5 | V105（7テーブル+guarded ALTER+seed）、V105_1元復元、V105_2新規移行、V1統合baseline同期、schema-engineer-selfservice-h2.sql+replay登録、entity×7+DocumentLink拡張、ActionPermissionResolver+4root、NotificationServiceImpl menuKeyForType+3、SystemConfigServiceImpl SCHEMAS+2、FileScopeValidationService RECEIPT/PRIVATE_NOTE規則、ApprovalViewServiceImpl targetUrl+2、FlywaySelfServiceSchemaSmokeTest（fresh+legacy）、4言語bundle（menu×10+notification.msg×4）、G9決定記録 | L1〜L3: MigrationScriptIntegrityTest/MessageBundleConsistencyTest/NotificationLinkRouteTest 33/0/0/0、FlywaySelfServiceSchemaSmokeTest 2/0/0/0 | — | `c06042f2` | V1/増分/H2/entity同期、security chain非変更、seed冪等性 |
| T089 | A1. my dashboard/profile/skill申請 | R1.1〜R1.4 | change request service一式（allowlist検証・diff生成・fingerprint・email/phone連携）、EngineerChangeRequestApprovalAdapter（profile/skill/career。master fingerprintで競合検出）、MyProfileApiController（/api/my/profile・skill-sheet preview/confirm）、MyChangeRequestApiController（atomic attachment upload）、EngineerChangeRequestApiController（HR/管理者/マネージャー）、Page×3＋templates＋JS、sidebar（要員myリンク+変更申請/経費管理） | L1〜L2: 一気通貫（下書き→申請→承認→反映1回）、本人GET phone反映、email並行変更conflict、allowlist外拒否、未所有添付404拒否、二重反映なし、本人A/B scope。8/0/0/0 | 要員login→/my/dashboard→/my/profile→変更申請→HR承認→反映→sheet preview確認（MockMvc/統合test手順書あり） | `e9933045` | master fingerprintはSHA-256先頭8byteをlong化。SysUser.emailとEngineer.phoneを連携 |
| T090 | A2. 本人給与/勤怠導線 | R2.1〜R2.3 | MyPayrollApiController（/api/my/payroll・no-store・再認証・break-glass拒否）、MyPayrollPageController、templates/my-payroll/index.html、static/js/modules/my-payroll.js、MyPayrollApiControllerTest（15件）、FreeeHrContractTest（28件） | L1〜L2: 本人scope（engineerIdパラメータ無しの静的assert）、一覧に金額を返さない、再認証10分/未実施403、break-glass拒否、no-store、provider障害503、未連携表示、他要員混入除外（複数従業員抽出テスト）。28/0/0/0+15/0/0/0 | 要員login→/my/payroll→一覧（金額なし）→再認証→詳細（金額あり）→engineerId指定無視/未紐付け403（MockMvcベース手順書あり） | `3737a91c` | freee疎通はmanagement APIと同様mock前提 |
| T091 | B1. 経費申請/承認/archive | R3.1〜R3.4, R5 | expense package一式（Service/Impl/Sender/Mock/JobScheduler/ApprovalAdapter）、MyExpenseApiController、ExpenseRequestApiController（管理者/マネージャー）、Page×2、templates×2、JS×2、ExpenseRequestFlowIntegrationTest（6件）、ExpenseApiSecurityMvcTest（5件） | L1〜L2: 金額/category validation、二重会計連携なし（job UNIQUE・payload_hash冪等・tx内通知）、差戻し→再申請、receipt ACL（本人A/B）、EICAR感染・scan拒否、承認後差替不可、管理母集団（管理者全件/マネージャー配下/営業HR 403）、markPaid+通知。6/0/0/0+5/0/0/0 | 要員: 経費作成→領収書→申請→承認者: 承認→scheduler連携→支払済。二重連携0件（MockMvc/統合test手順書あり） | `3737a91c` | 会計連携はmock provider（S15で実連携）。router設定はruntime |
| T092 | B2. 1on1/survey/privacy | R4.1〜R4.4 | one-on-one service一式（状態機械・populations・active counterpart検証・confidential秘密メモ境界）、survey service一式（template/campaign/snapshot version固定/回答upsert/集計・匿名性閾値）、My/管理API×4、Page×4＋templates＋JS、sidebar（1on1管理/サーベイ管理）、OneOnOneSurveyFlowIntegrationTest（8件） | L1〜L2: 1on1申請→日程確定→実施済、無効ユーザー400拒否、private noteが営業/マネージャー/一般管理者に不可視・HR/明示指定管理者に可視、survey元更新後もsnapshot version保持、未回答除外、匿名閾値未満非表示、配信通知。8/0/0/0 | 要員: 1on1申請→営業日程確定→実施済→HR confidential保存。HR: キャンペーン作成→配信→要員回答→集計（閾値非表示）（統合test手順書あり） | `c6bb88e3` | マネージャー集計は配下要員のみ。R4.4は集計APIの回答値入力を理由表示なしで提供 |
| T093 | M. 回帰 | R5 | S14関連全クラス強化、全量テスト実行、JS構文検査 | L4: S14関連テストおよび全量回帰全グリーン（skip 0） | 要員ログインからプロフィール変更申請・給与明細・経費申請・1on1申請・サーベイ回答の一気通貫動作確認、本人A/B間でのデータ非混入を確認 | （本コミット） | なし |

## T088（F1）証跡

- TEST SCOPE DECISION: task=T088 / changed contracts=V1・増分Flyway(V105, V105_1元復元, V105_2新設)・H2 replay・entity・ActionPermissionResolver・NotificationService menuKeyForType・SystemConfigService SCHEMAS・FileScopeValidationService・ApprovalViewService targetUrl・bundle / selected level=L3 / selected tests=MigrationScriptIntegrityTest・MessageBundleConsistencyTest・NotificationLinkRouteTest・FlywaySelfServiceSchemaSmokeTest / exact result=35/0/0/0（skip 0）。

## T093（M回帰）証跡

- TEST SCOPE DECISION: task=T093 / changed contracts=Round 3 指摘全件対応（phoneマッピング、email並行競合、atomic attachment link、ジョブ通知tx内化、canonical menuKey、1on1 active status・confidential境界、survey snapshot version、survey匿名閾値、V105.1 blob復元+V105.2新設、4言語i18n、Clock注入）
- selected level=L4（全量回帰）
- exact result=全パス（0 failures, 0 errors, 0 skipped）
