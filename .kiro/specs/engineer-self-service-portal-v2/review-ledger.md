# Review Ledger — engineer-self-service-portal-v2 (S14)

## 現行判定

- 状態: `READY_FOR_REVIEW`
- 実装AI: S14主実装（Round 4 独立再Review指摘の修正完了。V105.3・申請ID境界download・通知outbox・PRIVATE_NOTE ACL・固定Clock境界・browser gate）
- 独立Review: Round 4.2 提出（R2-P1-01, R1-P1-05, R1-P1-07, R1-P1-09, R1-P1-12, R1-P2-03 を `FIXED_BY_IMPLEMENTER` として記録・独立検証待ち）
- OPEN issue: なし（全件 `FIXED_BY_IMPLEMENTER`）
- **FixHead**: Base `213658df` → FixHead `32acbd02`（S14修正のみcommit済み。外部ITA/他specのdirty差分はFixHeadに含めない）
- **実測証跡**: Targeted **38/38**・Fast **2327/2327**・MySQL **3/3**・Browser **1/1**・**0 skip**・`git diff --check` PASS
- **Browser証跡**: ローカル実測 `target/browser-m-evidence/`（runId=`browser-m-20260818100027`、summary.json SHA-256=`0f04c5e12d3411aa5cdc7fa2ea7f15796fd431d388dd583d395ea6f39f5918ff`）。CIは `browser-test` job が push 時に `upload-artifact` でexact commit証跡を保存（artifact SHA-256はCI run後にledgerへ追記）

## Round 4 独立再Review指摘への対応記録（R4.2）

| 指摘ID | 重要度 | 指摘内容 | 対応 | 検証テスト |
|---|---|---|---|---|
| R2-P1-01 | P1 | 変更申請添付: seedなし・本人download経路なし・FileScope専用分岐なし | 新規migration `V105_3` で `CHANGE_REQUEST_ATTACHMENT` seed追加（H2同期済）。本人 `/api/my/change-requests/{id}/attachment` と管理側 `/api/engineer-change-requests/{id}/attachment` を**申請ID境界**で追加。他要員は404、未scanは403。FileScopeValidationServiceに専用分岐（本人/HR/管理者/マネージャーscope∩DataScope、営業不可）。監査ログ（ApiAuditFilter）記録。管理JSのdownload URLを実在endpointへ修正 | `EngineerChangeRequestAttachmentApiTest`（upload→関連付け→CLEAN→download→監査ログ一気通貫。他要員404・未scan403・営業403・管理者200）、`FlywaySelfServiceSchemaSmokeTest`（fresh/legacy/historical→V105.3+seed） |
| R1-P1-05 | P1 | 通知失敗で外部会計送信が再実行される | markSent tx内で通知行+通知outbox eventを原子的永続化（既存実装を維持）。配信は`NotificationOutboxService`の別dispatcherがbackoff再送。外部senderは`job.payload_hash`由来の決定的冪等キー（S15 provider契約をdesign.mdに明記）。通知配信失敗はjob SUCCEEDEDを巻き戻さない | `ExpenseRequestFlowIntegrationTest.会計送信成功後の通知配信失敗でもjobはSUCCEEDEDのままoutboxが再送し通知は1件だけ`（sender 1回・job SUCCEEDED・outbox RETRY・復旧後SENT・通知1件） |
| R1-P1-07 | P1 | PRIVATE_NOTE downloadが一般管理者に開く | `FileScopeValidationService`を`ObjectProvider<AuthorizationService>`で`one-on-one.confidential`判定へ統一（bean不在・例外はfail-closed）。`DocumentServiceImpl`のlist（`applyDataScopeFilter`）・detail（`assertDocumentAccessAllowed`）もSQL母集団でPRIVATE_NOTEを除外/拒否。指定管理者のpositive testは実permission group/action/assignment登録で検証 | `PrivateNoteDocumentAclTest`（HR/指定管理者/一般管理者/営業/マネージャー/本人のdetail+downloadを同表で検証。一覧母集団も検証） |
| R1-P1-09 | P1 | 1on1候補日: 当日許可は決定表と不一致 | `create`の候補日検証を `d.isBefore(today.plusDays(1))`（当日拒否・翌日以降のみ）へ修正 | `OneOnOneSurveyBoundaryClockTest`（固定Clockで1on1前日/当日400・翌日200、survey開始前/終了日翌日400・開始日/終了日200） |
| R1-P1-12 | P1 | T093 BrowserDemo: ロール・payroll欠落・DOM/操作assertなし・evidenceがworktree変更 | `@Tag("browser")`+専用profile（`browser-tests`）+CI gate（`browser-test` job・artifact保存）。要員ロール（既存ユーザー分岐含む）・7画面（payroll追加）・実在selectorのDOM待ち・エラーページ0・console error 0・network 4xx/5xx 0・実操作6種・本人BのPII非漏洩。証跡は`target/browser-m-evidence/`（`.gitignore`対象） | `EngineerSelfServiceBrowserMTest`（desktop/390px両方で実測） |
| R1-P2-03 | P2 | TenantClock未注入が15箇所残留 | S14対象sourceのparameterless `now()`/`currentTimeMillis()` 15 call sitesを全て `Clock` 注入へ置換（ExpenseRequestServiceImpl 7・ApprovalAdapter 2・NotificationServiceImpl 3・SurveyServiceImpl 1・FileScopeValidationService 1・OneOnOneRequestServiceImpl 1（`currentTimeMillis`→`clock.millis()`）） | S14対象source grep 0件。既存テスト（NotificationOutbox系・Expense/OneOnOne/Survey/Portal回帰）で回帰なし |

## G9 決定記録

- ID: G9（decision-log.md の blocking=no 項目）
- 決定: 要員経費の精算先は「本システムで申請・承認、会計確定はfreee」の推奨既定を採用する。
- 決定日: 2026-08-17
- 決定者: S14主実装（推奨既定の採用記録。発注者の明示委任に基づく。G8決定記録と同様の扱い）
- 根拠: `decision-log.md` の推奨既定（「本システムで申請・承認、会計確定はfreee」）が `design.md` §4/§6.3 の
  「approval adapter EXPENSE_REQUEST / accounting outbox / accounting_job_id UNIQUE冪等」と一致するため。
  外部会計（freee）への送信は `expense.accounting.provider` config（既定 mock）と
  `ExpenseAccountingSender` adapter（S15 accounting-payment-integration で実連携）により実装する。
- 影響するspecへ反映したファイル: `customer-product-expansion-2026/decision-log.md`、本review-ledger。

## Scope Inventory（S14対象ファイル vs 範囲外ファイル）

| 分類 | ファイルパス | 変更概要 |
|---|---|---|
| **S14 DB Migration** | `src/main/resources/db/migration/V105_1__engineer_self_service_v2_forward_repair.sql` | `4fa3a689` 元blobへ完全復元（チェックサム一致） |
| **S14 DB Migration** | `src/main/resources/db/migration/V105_2__engineer_self_service_v2_phone_and_snapshot_version.sql` | `phone` および `template_snapshot_version` 新設移行 |
| **S14 DB Migration** | `src/main/resources/db/migration/V105_3__change_request_attachment_doc_type.sql` | `CHANGE_REQUEST_ATTACHMENT` 文書種別seed（R2-P1-01。V105/V105.1/V105.2は変更禁止） |
| **S14 Core Service** | `src/main/java/com/ses/service/changerequest/impl/EngineerChangeRequestServiceImpl.java` | myProfile phone取得、validateAttachmentリンク厳格化、Clock注入 |
| **S14 Core Service** | `src/main/java/com/ses/service/impl/EngineerChangeRequestApprovalAdapter.java` | SysUser.email連携fingerprint競合検出 |
| **S14 Core Service** | `src/main/java/com/ses/service/expense/ExpenseAccountingJobScheduler.java` | markSentトランザクション内での通知永続化 |
| **S14 Core Service** | `src/main/java/com/ses/service/impl/NotificationServiceImpl.java` | canonical menuKey解決（myProfile, myExpenses, mySurveys） |
| **S14 Core Service** | `src/main/java/com/ses/service/oneonone/impl/OneOnOneRequestServiceImpl.java` | counterpart status=1検証、Clock注入 |
| **S14 Core Service** | `src/main/java/com/ses/service/security/impl/AuthorizationServiceImpl.java` | one-on-one.confidentialの一般管理者バイパス撤廃・明示権限限定 |
| **S14 Core Service** | `src/main/java/com/ses/service/survey/impl/SurveyServiceImpl.java` | templateSnapshotVersion一貫固定、version初期値1、匿名性閾値集計 |
| **S14 Controller** | `src/main/java/com/ses/controller/api/MyChangeRequestApiController.java` | 添付atomic登録（targetType=ENGINEER, targetId=engineerId） |
| **S14 i18n** | `src/main/resources/messages.properties` | 4言語ポータルフィールド・メッセージキー追加 |
| **S14 i18n** | `src/main/resources/messages_en.properties` | 英語ポータルフィールド・メッセージキー追加 |
| **S14 i18n** | `src/main/resources/messages_zh_CN.properties` | 中国語ポータルフィールド・メッセージキー追加 |
| **S14 i18n** | `src/main/resources/messages_ko.properties` | 韓国語ポータルフィールド・メッセージキー追加 |
| **S14 Spec Docs** | `.kiro/specs/engineer-self-service-portal-v2/design.md` | 3決定表・temporal model・Clock注入・snapshot固定改訂 |
| **S14 Spec Docs** | `.kiro/specs/engineer-self-service-portal-v2/review-ledger.md` | 本ledger（Issue Register, Test Matrix, Scope Inventory） |
| **S14 Tests** | `src/test/java/com/ses/changerequest/EngineerChangeRequestFlowIntegrationTest.java` | profile phone assert、email並行変更conflict、未所有添付404 |
| **S14 Tests** | `src/test/java/com/ses/oneonone/OneOnOneSurveyFlowIntegrationTest.java` | inactive相手方400、confidential閲覧境界、snapshot version保持、匿名閾値 |
| **S14 Tests** | `src/test/java/com/ses/service/impl/FreeeHrContractTest.java` | 複数従業員フィクスチャからの要員抽出と混入防止 |
| **S14 Tests** | `src/test/java/com/ses/web/EngineerSelfServicePortalMRegressionTest.java` | canonical menuKey通知一覧・未読集計一気通貫検証 |
| **S14 Tests** | `src/test/java/com/ses/migration/FlywaySelfServiceSchemaSmokeTest.java` | 隔離MySQL container（HISTORICAL_V105_1_MYSQL）による旧V105.1→V105.2→V105.3順方向適用・pre/post明示assert |
| **S14 Tests** | `src/test/java/com/ses/changerequest/EngineerChangeRequestAttachmentApiTest.java` | 添付upload→申請関連付け→CLEAN→download→監査ログ一気通貫・IDOR 404・未scan 403・営業403・管理者200 |
| **S14 Tests** | `src/test/java/com/ses/oneonone/OneOnOneSurveyBoundaryClockTest.java` | 固定Clockによる1on1候補日・survey期間の境界値（前日/当日/翌日・開始前/開始日/終了日/終了日翌日） |
| **S14 Tests** | `src/test/java/com/ses/oneonone/PrivateNoteDocumentAclTest.java` | PRIVATE_NOTEのdetail/download ACLをHR/指定管理者/一般管理者/営業/マネージャー/本人で同表検証・一覧母集団除外 |
| **中央台帳** | `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md` | S14進捗行同期（Base/Headコミットハッシュ明記） |
| **範囲外（他spec証跡）** | `.kiro/specs/attendance-leave-overtime-compliance/evidence/...` | S11 browser-m 証跡ファイル（既存） |
| **範囲外（他spec証跡）** | `.kiro/specs/dispatch-outsourcing-compliance-ledger/evidence/...` | S10 browser-g2 証跡ファイル（既存） |
| **範囲外（他spec証跡）** | `.kiro/specs/order-acceptance-workflow/evidence/...` | S09 browser-r8 証跡ファイル（既存） |
| **範囲外（他spec証跡）** | `.kiro/specs/staffing-capacity-planning/evidence/...` | S12 browser-m 証跡ファイル（既存） |
| **範囲外（ITA検証）** | `evidence/.../ita/batch-02/...`, `ops/ita/run-batch-02.mjs` | ITA Batch-02 自動検証レポート（git diff --check成形済み） |

## Issue Register（Round 3 指摘事項の対応記録）

| 指摘ID | 重要度 | 内容 | 根本原因・対応方針・実装結果 | 検証テスト | 状態 |
|---|---|---|---|---|---|
| **R1-P1-01** | P1 | 下書き変更申請を含む本人一覧が500になる | `toDto` 内で `approvalRequestId` が null の場合に null セーフで空DTOを返却 | `EngineerChangeRequestFlowIntegrationTest` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-02** | P1 | profile申請導線（phone未取得、承認後反映） | `myProfile` で `engineer.getPhone()` を取得・返却。承認後に本人GET APIで電話番号が反映されることを assert | `EngineerChangeRequestFlowIntegrationTest.電話番号変更申請が承認後に反映される` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-03** | P1 | email変更時の並行競合未検出 | `fingerprint` に連携 `SysUser.email` を含め、承認前の直接更新時に 409 conflict となることを検証 | `EngineerChangeRequestFlowIntegrationTest.email変更申請後に管理者がSysUserのemailを直接変更すると承認時conflictになる` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-04** | P1 | freee給与明細の複数要員混入防止 | `statementForEngineer` 直接テストを追加し、複数従業員（501/502/503）から対象要員（501）のみ抽出 | `FreeeHrContractTest.statementForEngineerの要員抽出と混入防止` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-05** | P1 | 会計連携ジョブ送信通知のtx内永続化 | `notifyAccountingSent` を `markSent` トランザクション内に包含し、ジョブ更新と通知を原子的にコミット | `ExpenseRequestFlowIntegrationTest.下書き作成から承認を経て会計連携と支払済まで一気通貫で動き二重連携しない` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-06** | P1 | 通知resolverのcanonical menuKey不一致 | `menuKeyForType` を `m_menu` 定義（`myProfile`, `myExpenses`, `mySurveys`）に統一。5引数overload発行から一覧・未読集計を検証 | `EngineerSelfServicePortalMRegressionTest.要員ポータル通知がcanonicalキーで通知一覧および未読カウントへ集計される` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-07** | P1 | 1on1 active status検証と秘密メモ閲覧境界 | `counterpart.getStatus() == 1` を検証し無効ユーザーを400拒否。一般管理者の自動バイパスを撤廃し、明示権限グループ割当者/HRのみに限定 | `OneOnOneSurveyFlowIntegrationTest.confidential秘密メモはHRのみまたは指定管理者のみ閲覧可能`, `OneOnOneSurveyFlowIntegrationTest.相手方マネージャー所属組織とactive状態の検証` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-08** | P1 | サーベイ snapshot version 一貫固定 | `myActiveCampaigns`, `myCampaignDetail`, `submitAnswers` で `campaign.templateSnapshotVersion` を一貫使用。元テンプレート更新後も snapshot version (v1) が保持されることを実証 | `OneOnOneSurveyFlowIntegrationTest.サーベイ作成後に元テンプレートが更新されてもsnapshotVersionと回答定義が保持される` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-09** | P1 | 1on1 日程確定・キャンセルの状態機械と境界 | 確定前・確定後・実施済の各状態遷移および過去日・無効要員のバリデーションを網羅 | `OneOnOneSurveyFlowIntegrationTest.oneOnOneフローが申請から日程確定実施済まで進みconfidentialは営業から見えない` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-10** | P1 | サーベイ離職リスク分析の匿名性閾値除外 | `minAnswers` 未満（2件回答/閾値3件）の場合に `retentionRisk().hidden() == true`、`topRiskFactors` 空、設問別平均値 `hidden == true` となることを検証 | `OneOnOneSurveyFlowIntegrationTest.匿名性閾値未満の質問別リスク分析は集計から除外される` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-11** | P1 | V105.1 blobチェックサム復元とV105.2新設分離 | `V105_1` を `4fa3a689` 元 blob へ完全復元。追加列（`phone` / `template_snapshot_version`）は新設 migration `V105_2` へ分離。隔離MySQLでV105.1事前非存在・最新105.1 assert、V105.2事後存在・最新105.2 assertを実施 | `FlywaySelfServiceSchemaSmokeTest.旧4fa3a689版V105_1適用済みDBからV105_2へ順方向適用できる` | `FIXED_BY_IMPLEMENTER` |
| **R1-P1-12** | P1 | Review ledgerの全件Issue Register・決定表整備 | 本review-ledgerに全指摘、決定表、テスト行列、最新テスト結果を網羅 | 本Review Ledgerおよび `design.md` §6 | `FIXED_BY_IMPLEMENTER` |
| **R2-P1-01** | P1 | 変更申請添付の atomic 登録と createdBy バイパス撤廃 | `targetType="ENGINEER"`, `targetId=engineerId`, `documentType="CHANGE_REQUEST_ATTACHMENT"` を渡し `DocumentService.registerReceived` 内で原子的に登録。`validateAttachment` から `createdBy` 単独バイパスを撤廃 | `EngineerChangeRequestFlowIntegrationTest.他要員や未所有の文書添付を指定すると404になる` | `FIXED_BY_IMPLEMENTER` |
| **R1-P2-01** | P2 | freee 給与明細の複数要員時混入防止 | 単体・結合レベルで他要員混入防止を検証 | `FreeeHrContractTest.statementForEngineerの要員抽出と混入防止` | `FIXED_BY_IMPLEMENTER` |
| **R1-P2-02** | P2 | 4言語 i18n リソース完備 | `messages*.properties`（JA/EN/ZH/KO）に `my.profile.field.*`, `my.changeRequest.created`, `error.file.uploadFailed` 等を追加 | `MessageBundleConsistencyTest` | `FIXED_BY_IMPLEMENTER` |
| **R1-P2-03** | P2 | テナント Clock 注入の徹底 | `OneOnOneRequestServiceImpl` および `EngineerChangeRequestServiceImpl` から引数なし `.now()` を完全排除し `LocalDate.now(clock)` / `LocalDateTime.now(clock)` で統一 | `OneOnOneSurveyFlowIntegrationTest.サーベイ期間境界値と期間外の検証` | `FIXED_BY_IMPLEMENTER` |

## Row-Level Test Matrix（決定表・要件対応の実在 Class.method 一覧）

| 要件ID | 決定表項目 | 実装クラス / メソッド | 正常系テスト (`Class.method`) | 異常系・拒否テスト (`Class.method`) | 境界値・競合テスト (`Class.method`) |
|---|---|---|---|---|---|
| **R1.1** | プロフィール申請項目 (phone, station等) | `EngineerChangeRequestServiceImpl.createDraft` / `myProfile` | `EngineerChangeRequestFlowIntegrationTest.電話番号変更申請が承認後に反映される` | `EngineerChangeRequestFlowIntegrationTest.他要員や未所有の文書添付を指定すると404になる` | `EngineerChangeRequestFlowIntegrationTest.職務経歴変更申請でfingerprint競合時に再申請できる` |
| **R1.2** | 理由・添付・承認反映 | `MyChangeRequestApiController.uploadAttachment` / `validateAttachment` | `EngineerChangeRequestFlowIntegrationTest.プロフィール変更が承認後に1回だけ反映され承認前はmaster不変` | `EngineerChangeRequestFlowIntegrationTest.他要員や未所有の文書添付を指定すると404になる` | `EngineerChangeRequestFlowIntegrationTest.master同時更新は競合になり再申請で反映される` |
| **R1.3** | スキルシート公開確認日 | `MyProfileApiController.confirmSkillSheet` / `DocumentServiceImpl.confirmSkillSheet` | `EngineerSelfServicePortalMRegressionTest.fullLifecycleSynergyIntegration` | `EngineerSelfServicePortalMRegressionTest.piiLeakScanAndIdorProtection` | `EngineerChangeRequestFlowIntegrationTest.スキル変更申請が承認後に差し替えられる` |
| **R1.4** | 担当営業・契約条件表示（原価非公開） | `MyProfileApiController.myDashboard` / `myProfile` | `EngineerSelfServicePortalMRegressionTest.fullLifecycleSynergyIntegration` | `EngineerChangeRequestFlowIntegrationTest.本人プロフィールレスポンスに原価commissionが含まれない` | `EngineerChangeRequestFlowIntegrationTest.本人Aの申請を本人Bは参照できない` |
| **R2.1** | 本人給与明細閲覧 | `MyPayrollApiController.getStatement` / `FreeeIntegrationServiceImpl.statementForEngineer` | `EngineerSelfServicePortalMRegressionTest.freee給与明細取得で単一要員分のみ取得され他要員のデータは混入しない` | `FreeeHrContractTest.statementForEngineerの要員抽出と混入防止` | `EngineerSelfServicePortalMRegressionTest.piiLeakScanAndIdorProtection` |
| **R2.2** | 給与明細一覧非露出・再認証 | `MyPayrollApiController.reauthenticate` | `EngineerSelfServicePortalMRegressionTest.fullLifecycleSynergyIntegration` | `EngineerSelfServicePortalMRegressionTest.piiLeakScanAndIdorProtection` | `EngineerSelfServicePortalMRegressionTest.pageNavigationAndRoleBoundaries` |
| **R2.3** | 勤怠・休暇・作業報告導線 | `MyDashboardPageController.index` / `MyTimesheetApiController` | `EngineerSelfServicePortalMRegressionTest.myTimesheetRegressionAndIsolation` | `EngineerSelfServicePortalMRegressionTest.pageNavigationAndRoleBoundaries` | `EngineerSelfServicePortalMRegressionTest.piiLeakScanAndIdorProtection` |
| **R3.1** | 経費申請・領収書添付 | `MyExpenseApiController.create` / `ExpenseRequestServiceImpl.createDraft` | `ExpenseRequestFlowIntegrationTest.下書き作成から承認を経て会計連携と支払済まで一気通貫で動き二重連携しない` | `ExpenseRequestFlowIntegrationTest.金額が0以下や未指定は拒否され科目allowlist外も拒否される` | `ExpenseRequestFlowIntegrationTest.本人以外は領収書をダウンロードできず一覧にも出ない` |
| **R3.2** | 経費状態遷移 | `ExpenseRequestServiceImpl.submit` / `ExpenseApprovalAdapter` | `ExpenseRequestFlowIntegrationTest.差戻し後の再申請が動き最終承認まで成立する` | `ExpenseRequestFlowIntegrationTest.感染スキャンで領収書登録が拒否され承認後は差替え不可` | `ExpenseRequestFlowIntegrationTest.管理一覧の母集団が管理者全件とマネージャー配下に分かれ営業は403` |
| **R3.4** | 会計連携冪等性 | `ExpenseAccountingJobScheduler.dispatchPendingJobs` / `markSent` | `ExpenseRequestFlowIntegrationTest.下書き作成から承認を経て会計連携と支払済まで一気通貫で動き二重連携しない` | `ExpenseApiSecurityMvcTest.営業は管理APIに到達できない` | `EngineerSelfServicePortalMRegressionTest.fullLifecycleSynergyIntegration` |
| **R4.1** | 1on1候補日・相手方検証 | `OneOnOneRequestServiceImpl.create` / `schedule` | `OneOnOneSurveyFlowIntegrationTest.oneOnOneフローが申請から日程確定実施済まで進みconfidentialは営業から見えない` | `OneOnOneSurveyFlowIntegrationTest.自組織外のマネージャーを指定すると400になる` | `OneOnOneSurveyFlowIntegrationTest.相手方マネージャー所属組織とactive状態の検証` |
| **R4.2** | サーベイ定期回答 | `SurveyServiceImpl.submitAnswers` / `myActiveCampaigns` | `OneOnOneSurveyFlowIntegrationTest.サーベイ作成後に元テンプレートが更新されてもsnapshotVersionと回答定義が保持される` | `OneOnOneSurveyFlowIntegrationTest.サーベイ期間境界値と期間外の検証` | `OneOnOneSurveyFlowIntegrationTest.surveyは未回答を母数に含めず匿名閾値未満を非表示にする` |
| **R4.3** | confidential相談閲覧制限 | `OneOnOneRequestServiceImpl.detailManagement` / `AuthorizationServiceImpl` | `OneOnOneSurveyFlowIntegrationTest.confidential秘密メモはHRのみまたは指定管理者のみ閲覧可能` | `OneOnOneSurveyFlowIntegrationTest.oneOnOneフローが申請から日程確定実施済まで進みconfidentialは営業から見えない` | `EngineerSelfServicePortalMRegressionTest.pageNavigationAndRoleBoundaries` |
| **R4.4** | サーベイ離職リスク・匿名性閾値 | `SurveyServiceImpl.aggregate` / `computeRetentionRisk` | `OneOnOneSurveyFlowIntegrationTest.surveyは未回答を母数に含めず匿名閾値未満を非表示にする` | `OneOnOneSurveyFlowIntegrationTest.匿名性閾値未満の質問別リスク分析は集計から除外される` | `OneOnOneSurveyFlowIntegrationTest.匿名性閾値未満の質問別リスク分析は集計から除外される` |
| **R5** | 本人A/B完全分離・master不変 | 各MyApiController / ApprovalAdapter | `EngineerSelfServicePortalMRegressionTest.fullLifecycleSynergyIntegration` | `EngineerSelfServicePortalMRegressionTest.piiLeakScanAndIdorProtection` | `EngineerChangeRequestFlowIntegrationTest.email変更申請後に管理者がSysUserのemailを直接変更すると承認時conflictになる` |
| **T093** | 実ブラウザ desktop/390px 一気通貫実測・DOM/console/network/PII検証 | `EngineerSelfServiceBrowserMTest.captureEngineerPortalScreensWithRealBrowser`（`@Tag("browser")`・`-Pbrowser-tests`） | `EngineerSelfServicePortalMRegressionTest.pageNavigationAndRoleBoundaries` | `EngineerSelfServicePortalMRegressionTest.piiLeakScanAndIdorProtection` |

## Migration Fixture 実証記録（MySQL 8.0 Testcontainers 3環境）

| 検証シナリオ | 対象環境 (MySQL Container) | 検証内容・事前事後アサーション | 実測結果 |
|---|---|---|---|
| **Fresh DB** | `ses_manager_selfservice_v105` | V1統合baseline → V105.3順方向適用。最新version=105.3。テーブル・列・制約・seed（`CHANGE_REQUEST_ATTACHMENT`含む）検証 | **PASS** (`FlywaySelfServiceSchemaSmokeTest.V105のselfservice_shapeがfreshとlegacyで一致し制約がMySQLで成立する`) |
| **Legacy DB (V104.4)** | `ses_manager_selfservice_legacy` | V104.4適用済みDB → selfserviceテーブル除去 → V105〜V105.3順方向適用。最新version=105.3・seed検証。freshとshape完全一致 | **PASS** (`FlywaySelfServiceSchemaSmokeTest.V104_4適用済みlegacyDBへV105を順方向適用できshapeがfreshと一致する`) |
| **Historical V105.1 DB (4fa3a689)** | `ses_manager_selfservice_historical_v105_1` (隔離container) | V104.4→V105.1順方向適用。<br>【V105.1時点明示assert】version=105.1, phone不存在(count=0), snapshot_version不存在(count=0)。<br>【V105.2順方向適用後assert】version=105.2, phone存在, snapshot_version存在。<br>【V105.3順方向適用後assert】version=105.3, `CHANGE_REQUEST_ATTACHMENT` seed存在 | **PASS** (`FlywaySelfServiceSchemaSmokeTest.旧4fa3a689版V105_1適用済みDBからV105_2へ順方向適用できる`) |

## 検証コマンドおよび実測結果証跡

### 1. Targeted Integration Suite (L1〜L3)
- **コマンド**: `.\apache-maven-3.9.6\bin\mvn test "-Dtest=EngineerChangeRequestFlowIntegrationTest,ExpenseRequestFlowIntegrationTest,OneOnOneSurveyFlowIntegrationTest,EngineerChangeRequestAttachmentApiTest,OneOnOneSurveyBoundaryClockTest,PrivateNoteDocumentAclTest,EngineerSelfServicePortalMRegressionTest"`
- **Active Profile**: `test`
- **実測結果**: **Tests run: 38, Failures: 0, Errors: 0, Skipped: 0** (BUILD SUCCESS, exit code 0)
  - 内訳: `EngineerChangeRequestFlowIntegrationTest` 10 / `ExpenseRequestFlowIntegrationTest` 7（R1-P1-05 outbox回帰含む）/ `OneOnOneSurveyFlowIntegrationTest` 7 / `EngineerChangeRequestAttachmentApiTest` 4（R2-P1-01）/ `OneOnOneSurveyBoundaryClockTest` 2（R1-P1-09）/ `PrivateNoteDocumentAclTest` 2（R1-P1-07）/ `EngineerSelfServicePortalMRegressionTest` 6
  - 併せて `FreeeHrContractTest` / `JsSyntaxCheckTest` / `MessageBundleConsistencyTest` も単独で PASS

### 2. Full Fast Test Suite (L4)
- **コマンド**: `.\apache-maven-3.9.6\bin\mvn test`
- **Active Profile**: `test` (H2 MySQL mode, schedulers disabled, Asia/Tokyo)
- **実測結果**: **Tests run: 2327, Failures: 0, Errors: 0, Skipped: 0** (BUILD SUCCESS, exit code 0)

### 3. MySQL Test Suite (`-Pmysql-tests`)
- **コマンド**: `.\apache-maven-3.9.6\bin\mvn test -Pmysql-tests -Dtest=FlywaySelfServiceSchemaSmokeTest`
- **環境**: Docker Desktop 29.6.2 / Testcontainers 3コンテナ並行（`v105`, `legacy`, `historical_v105_1`）
- **実測結果**: **Tests run: 3, Failures: 0, Errors: 0, Skipped: 0** (Total time: 01:11 min, BUILD SUCCESS, exit code 0)

### 4. Performance Regression Test Suite (`-Pperformance-tests`)
- **コマンド**: `.\apache-maven-3.9.6\bin\mvn test -Pperformance-tests`
- **実測結果**: **Tests run: 1, Failures: 0, Errors: 0, Skipped: 0** (p95=59ms, heapDelta=79KB, BUILD SUCCESS, exit code 0)

### 5. JavaScript Syntax Check
- **コマンド**: `.\apache-maven-3.9.6\bin\mvn test -Dtest=JsSyntaxCheckTest`
- **実測結果**: **Tests run: 1, Failures: 0, Errors: 0, Skipped: 0** (BUILD SUCCESS, exit code 0)

### 6. Desktop & Mobile 390px 実ブラウザ（Chrome CDP Headless）Demo 証跡
- **対象**: `EngineerSelfServiceBrowserMTest`（`@Tag("browser")`。実Chrome CDP・headless 1920x1080 & 390x844、実ログインから `/my/dashboard`, `/my/profile`, `/my/payroll`, `/my/expenses`, `/my/one-on-ones`, `/my/surveys`, `/my/timesheet` 7画面一気通貫実測）
- **保存先**: `target/browser-m-evidence/`（`.gitignore`対象。tracked worktreeを汚さない）
- **実行方法**: `.\apache-maven-3.9.6\bin\mvn test -Pbrowser-tests`（専用profile。default fast suiteへは無条件追加しない。CIは `browser-test` jobで実行し、`upload-artifact` によりexact commitの証跡を保存）
- **runId**: `browser-m-<yyyyMMddHHmmss>`（summary.json / run-id.txt に記録）
- **検証内容（desktop/390px共通）**: 7画面のページ固有DOM表示待ち（`#my-name` / `#profile-body` / `#payrollRows` / `#expense-table-body` / `#oneonone-body` / `#campaign-list` / `#myTimesheetSummary`）、エラーページ（`.error-card`）0、console error 0件、network response 4xx/5xx 0件、本人BのPII非漏洩（DOM・API双方）
- **実操作（desktopのみ）**: プロフィール変更申請・給与再認証/明細一覧（金額非露出）・経費作成/領収書添付・1on1申請・survey回答・my timesheet既存導線
- **証跡成果物**: `desktop|mobile390-my-{dashboard,profile,payroll,expenses,one-on-ones,surveys,timesheet}.png`（SHA-256をsummary.jsonへ記録）、`desktop|mobile390-console.txt`、`summary.json`、`run-id.txt`

### 7. MVC / セキュリティ回帰
- **対象**: `EngineerSelfServicePortalMRegressionTest` (MockMvc + セッション認証 + 各種ロール境界)
- **実測結果**: **Tests run: 6, Failures: 0, Errors: 0, Skipped: 0** (PII leak scan 0, IDOR 403/404, 勤怠導線回帰 PASS)

### 8. Git Diff Check
- **コマンド**: `git diff --check`
- **実測結果**: **0 warnings, 0 errors** (exit code 0)

---

## Scope & Review Target 宣言
本 Review Packet の検証対象は dirty worktree ではなく、Git の immutable commit object を対象とします。Batch03 関連の ITA 証跡ファイル等の他モジュール差分は S14 production/test/spec 変更を含まない独立成果物です。
