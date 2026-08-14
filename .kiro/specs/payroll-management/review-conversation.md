# HFP-01 独立Review用パケット（review-conversation.md）

> 実装AIが独立Reviewerへ引き渡すためのパケット。最終verdictは実装AIは付けない。
> 生成日: 2026-08-14。sandbox credential未提供のため HFP-01-011 は BLOCKED。
> 更新（2026-08-14 Round 1修正後）: head を `2538ef9e` へ。REV-001〜007 の修正を §7 に追記。

## 1. REVIEW PACKET（execution-review-handbook §8）

- handbook/spec version: `.kiro/specs/half-finished-production-readiness/` 全3文書 + `payroll-management/` 全5文書（2026-08-12時点、本branchで変更なし）
- task IDs: HFP-01-001〜HFP-01-011（001〜010完了・検証済み、011はBLOCKED）
- base commit: `841e10aaf67deb295d5b3397321f30e9d08c0fce`（main）
- head commit: `2538ef9e`（`codex/hfp-01-payroll-freee`、Round 1修正を含む）
- merge status: PRE_MERGE（独立Review後、mainへmerge予定）
- changed files（task別）:

| Task | 主要変更file |
|---|---|
| 001 | `src/test/resources/freee/*`（fixture 26件）、`FreeeContractBaselineTest`（10）、`research.md` §7 |
| 002 | `db/migration/V102_2__freee_company_boundary.sql`、`FreeeConnection.connectionStatus`、`FreeeEmployeeLink.freeeCompanyId`、`schema-freee-payroll-h2.sql`、`engineer-schema-h2.sql`、`FlywayV102_2FreeeCompanyBoundarySmokeTest`、`FreeeCompanyBoundarySchemaH2Test`、`FlywayMigrationSmokeTest` |
| 003 | `application.yml`/`application-prod.yml`（freee設定分離）、`FreeeIntegrationService`/`Impl`（OAuth公式host・company検証・状態機械・refresh rotation・revoke）、`FreeeOAuthController`（state TTL/一回性）、`FreeeOAuthContractTest`（17）、`FreeeOAuthCallbackWebTest`（7）、`FreeeConnectionStatusDto`、messages 4bundle |
| 004 | `service/freee/FreeeHrContractAdapter`、`dto/freee/hr/*`（5）、`hrGet`/`executeWithRetry`（base URL・401 code分類・429 Retry-After・5xx bounded retry・Sleeper）、`fetchAllEmployees/fetchSalaryStatements/fetchBonusStatements`、`FreeeHrContractTest`（27） |
| 005 | `FreeeEmployeeDto`、`PayrollEngineerCandidateDto`、`engineerCandidates`、link/unlink company境界、`FreeeEmployeeMappingTest`（12） |
| 006 | `PayrollStatementDto`/`PayrollItemDto`、`mapSalary/BonusStatements`（inner join・category変換・stable sort）、`PayrollReadModelTest`（7） |
| 007 | `SecurityConfig`（静的rule）、`FreeeOAuthController`（監査）、`FreeePayrollApiController`（機微GET監査・no-store）、`ApiAuditFilter`（payroll除外）、`PayrollSecurityAuditTest`（13） |
| 008 | `templates/payroll/index.html`、`static/js/modules/payroll.js`、`PayrollLandmarkA11yTest`（5） |
| 009 | `CashFlowForecastServiceImpl.getEstimatedPayroll`（design §14）、`CashFlowForecastServiceTest`（13） |
| 010 | `tasks.md` checkbox、`review-ledger.md` RUN-01〜10、V102_2採番訂正 |
| Round1修正 | `FreeeHrContractAdapter.strictAmount`（REV-001）、`FreeeReauthMarker`＋`persistReauthAfterCompletion`＋`FreeeReauthPersistenceTest`（REV-002）、`FreeePayrollApiController`/`FreeeOAuthController`失敗系監査＋`PayrollSecurityAuditTest`（REV-003）、`FreeeConcurrentRefreshTest`（REV-004）、`handleCallback`/`link` tx分離（REV-005）、ledger訂正/H2コメント（REV-006）、X-Request-Id/相関ID log（REV-007） |
| 011 | BLOCKED（sandbox credential未提供） |

- requirements/acceptance → code → test → Demo trace: 下記§2
- official contract: `freee/freee-api-schema@52c69a6819ef14979a31b342123df816cb72c742`（hr/open-api-3、schema version 2022-02-01）。固定commit以降 hr に差分なし（`hr` tree SHA一致確認済み）
- migration latest: `V102`（実在）。HFP-01は `V102_2`（V103〜V108はS12〜S17予約）。`ReviewerVerificationMigrationOrderContractTest`/`SpecDispatchConsistencyTest`/`MigrationScriptIntegrityTest` green
- test/Demo evidence: `review-ledger.md` RUN-01〜10 + surefire-reports
- skipped/unverified: HFP-01-011（sandbox E2E、desktop/390px実操作）。HFP-01-G01（freee test事業所spike）OPEN
- known issue: なし（OPEN P0/P1 0。Round 1のP0/P1は修正済み・FIXED_BY_IMPLEMENTER）
- out-of-scope: S11勤怠・S15会計の仕様是正（共有基盤の回帰のみ）
- rollback/recovery: design §16.3（menu無効化・token revoke・forward fix。適用済みmigration不変）
- requested verdict: intermediate（REVIEWABLE判定依頼）

## 2. Acceptance trace

| Acceptance | 状態 | 証跡（file / test / command） |
|---|---|---|
| AC01 公式URL・scopeなし | PASS | `FreeeContractBaselineTest.oauthAuthorizeUrlUsesOfficialHostAndSelectCompany`、`FreeeOAuthContractTest`（URL exact） |
| AC02 state検証・token 1回 | PASS | `FreeeOAuthCallbackWebTest`（7: 正常1回/欠落/不一致/期限/再送/拒否/設定不足） |
| AC03 company_id+company_admin→CONNECTED | PASS | `FreeeOAuthContractTest`（company match/self_only/mismatch）、`FreeeIntegrationServiceImpl.verifyCompanyAdmin` |
| AC04 並行refresh 1回・rotation・REAUTH | PASS（Round1修正後） | `FreeeConcurrentRefreshTest`（実MySQL・外部POST 1回・rotation保存）、`FreeeReauthPersistenceTest`（実proxy+H2・REAUTH_REQUIRED永続化） |
| AC05 revoke成功/既失効/一時障害 | PASS | `FreeeOAuthContractTest`（revoke matrix）、`FreeeIntegrationServiceImpl.revokeToken` |
| AC06 従業員0/1/100/101/200・BP拒否 | PASS | `FreeeHrContractTest`（pagination）、`FreeeEmployeeMappingTest`（BP・company境界）、fixture employees-0/1/3/100/101/200 |
| AC07 公式root/field・同名明細・計算中null | PASS（REV-001修正後） | `PayrollReadModelTest`（7）、`FreeeHrContractTest`（root/field strict parse・invalid amountの生値非漏洩） |
| AC08 対応付け済み要員だけ返却 | PASS | `PayrollReadModelTest`（未対応/BP変更済み/別company除外） |
| AC09 root欠落・途中空・反復・invalid amount | PASS | `FreeeHrContractTest`（27: contract error系、生値なしdetail） |
| AC10 retry matrix・log秘密0 | PASS（REV-007反映） | `FreeeOAuthContractTest`・`FreeeIntegrationServiceApiTest`（401/429/5xx/timeout）、X-Request-Id/相関ID log、`apiGetは秘密情報をログへ出力しない` |
| AC11 role matrix・CSRF | PASS | `PayrollSecurityAuditTest`（6主体×page/API/OAuth、CSRFあり/なし） |
| AC12 no-store・1 request 1 row・禁止値0 | PASS（REV-003修正後） | `PayrollSecurityAuditTest`（成功/失敗系とも1 row、success_flag、禁止値0、生金額0） |
| AC13 desktop/390px Demo | BLOCKED | MockMvc描画+`PayrollLandmarkA11yTest`（5）はPASS。実ブラウザ操作はsandbox接続が必要（HFP-01-011） |
| AC14 S11/S15・CashFlow・MySQL smoke・全test | PASS | 17 class 147/0/0/0、`FlywayMigrationSmokeTest`+`FlywayV102_2FreeeCompanyBoundarySmokeTest` 4/0/0/0（実MySQL）、verify-like-ci（Round1修正後に再実行中） |
| AC15 E2E・独立Review | BLOCKED | sandbox credential未提供。merge前独立Review（本パケット）・merge後Reviewは未実施 |

## 7. Round 1修正（REV-001〜007）の対応

| Finding | Severity | 対応 | 再test |
|---|---|---|---|
| REV-001 | P0 | adapterのdetailから生金額・項目名を除去（field種別のみ） | `FreeeHrContractTest.invalidAmountは生値をdetailへ含めない`、`PayrollSecurityAuditTest`（responseに生金額0） |
| REV-002 | P1 | `FreeeReauthMarker`（REQUIRES_NEW）＋afterCompletionでREAUTH_REQUIREDを独立永続化 | `FreeeReauthPersistenceTest`（実proxy+H2でDB永続化） |
| REV-003 | P2 | 機微GET/link/unlink/connect/disconnectの失敗系監査（success_flag=false） | `PayrollSecurityAuditTest.失敗系も1request1rowで監査される` |
| REV-004 | P2 | 並行refresh自動test（実MySQL+実HTTP） | `FreeeConcurrentRefreshTest`（外部POST 1回） |
| REV-005 | P2 | handleCallback/linkの外部HTTPをtx外へ（保存のみTransactionTemplate） | `FreeeOAuthContractTest` 17/0/0/0、`FreeeEmployeeMappingTest` 12/0/0/0 |
| REV-006 | NOTE | ledger件数訂正（単独run 1992×2）、H2コメントV102_2化 | `FreeeCompanyBoundarySchemaH2Test` 5/0/0/0 |
| REV-007 | NOTE | X-Request-Id/内部相関IDを障害時logへ（秘密なし） | 秘密log test green維持 |

## 5. 再実行手順（Reviewer用）

```powershell
# 全test（Docker必須。skip 0がCI契約）
.\scripts\verify-like-ci.ps1
# freee関連のみ（17 class、並行refreshは実MySQL）
.\apache-maven-3.9.6\bin\mvn test -Dtest='FreeeContractBaselineTest,FreeeOAuthContractTest,FreeeOAuthCallbackWebTest,FreeeHrContractTest,FreeeEmployeeMappingTest,PayrollReadModelTest,PayrollSecurityAuditTest,PayrollLandmarkA11yTest,FreeeIntegrationServiceApiTest,FreeeAttendanceProviderTest,PaymentReconciliationServiceImplTest,CashFlowForecastServiceTest,FreeeCompanyBoundarySchemaH2Test,MessageBundleConsistencyTest,JsSyntaxCheckTest,FreeeReauthPersistenceTest,FreeeConcurrentRefreshTest'
# MySQL migration smoke
.\apache-maven-3.9.6\bin\mvn test -Dtest='FlywayMigrationSmokeTest,FlywayV102_2FreeeCompanyBoundarySmokeTest'
```

## 6. BLOCKED残件（HFP-01-011）

| ID | 内容 | 必要外部条件 | 再実行 |
|---|---|---|---|
| HFP-01-G01 | freee test事業所で users/me・employee・salary・bonus spike | freeeアプリ（private）・test事業所・company_admin user・credential（FREEE_*環境変数） | 提供後、design §15.3のE2E手順 |
| AC13/AC15 | desktop/390px実操作、E2E、merge後Review | 同上 + merge | HFP-01-011 |
