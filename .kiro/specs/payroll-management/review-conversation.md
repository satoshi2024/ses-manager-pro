# HFP-01 独立Review用パケット（review-conversation.md）

> 実装AIが独立Reviewerへ引き渡すためのパケット。最終verdictは実装AIは付けない。
> 生成日: 2026-08-14。sandbox credential未提供のため HFP-01-011 は BLOCKED。

## 1. REVIEW PACKET（execution-review-handbook §8）

- handbook/spec version: `.kiro/specs/half-finished-production-readiness/` 全3文書 + `payroll-management/` 全5文書（2026-08-12時点、本branchで変更なし）
- task IDs: HFP-01-001〜HFP-01-011（001〜010完了・検証済み、011はBLOCKED）
- base commit: `841e10aaf67deb295d5b3397321f30e9d08c0fce`（main）
- head commit: `dc0a8104`（`codex/hfp-01-payroll-freee`）
- merge status: PRE_MERGE（独立Review後、mainへmerge予定）
- changed files（task別）:

| Task | 主要変更file |
|---|---|
| 001 | `src/test/resources/freee/*`（fixture 26件）、`FreeeContractBaselineTest`（10）、`research.md` §7 |
| 002 | `db/migration/V102_2__freee_company_boundary.sql`、`FreeeConnection.connectionStatus`、`FreeeEmployeeLink.freeeCompanyId`、`schema-freee-payroll-h2.sql`、`engineer-schema-h2.sql`、`FlywayV102_2FreeeCompanyBoundarySmokeTest`、`FreeeCompanyBoundarySchemaH2Test`、`FlywayMigrationSmokeTest` |
| 003 | `application.yml`/`application-prod.yml`（freee設定分離）、`FreeeIntegrationService`/`Impl`（OAuth公式host・company検証・状態機械・refresh rotation・revoke）、`FreeeOAuthController`（state TTL/一回性）、`FreeeOAuthContractTest`（17）、`FreeeOAuthCallbackWebTest`（7）、`FreeeConnectionStatusDto`、messages 4bundle |
| 004 | `service/freee/FreeeHrContractAdapter`、`dto/freee/hr/*`（5）、`hrGet`/`executeWithRetry`（base URL・401 code分類・429 Retry-After・5xx bounded retry・Sleeper）、`fetchAllEmployees/fetchSalaryStatements/fetchBonusStatements`、`FreeeHrContractTest`（26） |
| 005 | `FreeeEmployeeDto`、`PayrollEngineerCandidateDto`、`engineerCandidates`、link/unlink company境界、`FreeeEmployeeMappingTest`（12） |
| 006 | `PayrollStatementDto`/`PayrollItemDto`、`mapSalary/BonusStatements`（inner join・category変換・stable sort）、`PayrollReadModelTest`（7） |
| 007 | `SecurityConfig`（静的rule）、`FreeeOAuthController`（監査）、`FreeePayrollApiController`（機微GET監査・no-store）、`ApiAuditFilter`（payroll除外）、`PayrollSecurityAuditTest`（12） |
| 008 | `templates/payroll/index.html`、`static/js/modules/payroll.js`、`PayrollLandmarkA11yTest`（5） |
| 009 | `CashFlowForecastServiceImpl.getEstimatedPayroll`（design §14）、`CashFlowForecastServiceTest`（13） |
| 010 | `tasks.md` checkbox、`review-ledger.md` RUN-01〜08、V102_2採番訂正 |
| 011 | BLOCKED（sandbox credential未提供） |

- requirements/acceptance → code → test → Demo trace: 下記§2
- official contract: `freee/freee-api-schema@52c69a6819ef14979a31b342123df816cb72c742`（hr/open-api-3、schema version 2022-02-01）。固定commit以降 hr に差分なし（`hr` tree SHA一致確認済み）
- migration latest: `V102`（実在）。HFP-01は `V102_2`（V103〜V108はS12〜S17予約）。`ReviewerVerificationMigrationOrderContractTest`/`SpecDispatchConsistencyTest`/`MigrationScriptIntegrityTest` green
- test/Demo evidence: `review-ledger.md` RUN-01〜08 + surefire-reports
- skipped/unverified: HFP-01-011（sandbox E2E、desktop/390px実操作）。HFP-01-G01（freee test事業所spike）OPEN
- known issue: なし（OPEN P0/P1 0）
- out-of-scope: S11勤怠・S15会計の仕様是正（共有基盤の回帰のみ）
- rollback/recovery: design §16.3（menu無効化・token revoke・forward fix。適用済みmigration不変）
- requested verdict: intermediate（REVIEWABLE判定依頼）

## 2. Acceptance trace

| Acceptance | 状態 | 証跡（file / test / command） |
|---|---|---|
| AC01 公式URL・scopeなし | PASS | `FreeeContractBaselineTest.oauthAuthorizeUrlUsesOfficialHostAndSelectCompany`、`FreeeOAuthContractTest`（URL exact） |
| AC02 state検証・token 1回 | PASS | `FreeeOAuthCallbackWebTest`（7: 正常1回/欠落/不一致/期限/再送/拒否/設定不足） |
| AC03 company_id+company_admin→CONNECTED | PASS | `FreeeOAuthContractTest`（company match/self_only/mismatch）、`FreeeIntegrationServiceImpl.verifyCompanyAdmin` |
| AC04 並行refresh 1回・rotation・REAUTH | PASS | `FreeeOAuthContractTest`（refresh concurrency、invalid_grant→REAUTH_REQUIRED） |
| AC05 revoke成功/既失効/一時障害 | PASS | `FreeeOAuthContractTest`（revoke matrix）、`FreeeIntegrationServiceImpl.revokeToken` |
| AC06 従業員0/1/100/101/200・BP拒否 | PASS | `FreeeHrContractTest`（pagination）、`FreeeEmployeeMappingTest`（BP・company境界）、fixture employees-0/1/3/100/101/200 |
| AC07 公式root/field・同名明細・計算中null | PASS | `PayrollReadModelTest`（7）、`FreeeHrContractTest`（root/field strict parse）、fixture salary-calculated/calculating |
| AC08 対応付け済み要員だけ返却 | PASS | `PayrollReadModelTest`（未対応/BP変更済み/別company除外） |
| AC09 root欠落・途中空・反復・invalid amount | PASS | `FreeeHrContractTest`（26: contract error系）、`FreeeIntegrationServiceImpl.contractError`（502） |
| AC10 retry matrix・log秘密0 | PASS | `FreeeOAuthContractTest`・`FreeeIntegrationServiceApiTest`（401/429/5xx/timeout）、`apiGetは秘密情報をログへ出力しない` |
| AC11 role matrix・CSRF | PASS | `PayrollSecurityAuditTest`（6主体×page/API/OAuth、CSRFあり/なし） |
| AC12 no-store・1 request 1 row・禁止値0 | PASS | `PayrollSecurityAuditTest`（全GET no-store、audit row、禁止値scan） |
| AC13 desktop/390px Demo | BLOCKED | MockMvc描画+`PayrollLandmarkA11yTest`（5）はPASS。実ブラウザ操作はsandbox接続が必要（HFP-01-011） |
| AC14 S11/S15・CashFlow・MySQL smoke・全test | PASS | `FreeeIntegrationServiceApiTest`・`FreeeAttendanceProviderTest`・`PaymentReconciliationServiceImplTest`・`CashFlowForecastServiceTest`・`FlywayMigrationSmokeTest`・`FlywayV102_2FreeeCompanyBoundarySmokeTest`・verify-like-ci |
| AC15 E2E・独立Review | BLOCKED | sandbox credential未提供。merge前独立Review（本パケット）・merge後Reviewは未実施 |

## 3. 外部契約と環境

- freee test事業所・OAuth app・credential: **未提供**（FREEE_*環境変数未設定）。HFP-01-G01 OPEN。
- Docker 29.6.2 / Node v24.18.0 / JDK 17（CIはTemurin 21を指定。bytecode target 17）
- 秘密値・実給与・実氏名・外部IDはrepository・ledger・logに無し（禁止値scan済み）

## 4. Reviewerへ依頼する確認項目

1. 固定commit `52c69a6...` に対するfixture/実装のfield対応（特に employees root=配列、salary/bonus root、`gross_payment_amount` 等、`calc_status`、`{name,amount}` item）
2. OAuth state TTL・一回性・constant-time比較、redirectへのcode/state非掲載
3. refresh row-lock後再確認・rotation必須・invalid_grant遷移
4. pagination（raw配列/`total_count`）・drift検知・有限終了
5. 401 code分類とretry回数、429 Retry-After、5xx/timeout bounded retry
6. 静的認可（管理者/HR）、CSRF、no-store全端末、1 request 1 audit row、禁止値非記録
7. CashFlow優先順位と正式0円/0件の区別
8. V102_2採番（V103〜V108予約との非衝突）とH2/MySQL schema同期
9. S11/S15共有基盤の非破壊（apiGet/apiPost/bankDeposits contract不変）

## 5. 再実行手順（Reviewer用）

```powershell
# 全test（Docker必須。skip 0がCI契約）
.\scripts\verify-like-ci.ps1
# freee関連のみ
.\apache-maven-3.9.6\bin\mvn test -Dtest='FreeeContractBaselineTest,FreeeOAuthContractTest,FreeeOAuthCallbackWebTest,FreeeHrContractTest,FreeeEmployeeMappingTest,PayrollReadModelTest,PayrollSecurityAuditTest,PayrollLandmarkA11yTest,FreeeIntegrationServiceApiTest,FreeeAttendanceProviderTest,PaymentReconciliationServiceImplTest,CashFlowForecastServiceTest,FreeeCompanyBoundarySchemaH2Test,MessageBundleConsistencyTest,JsSyntaxCheckTest'
# MySQL migration smoke
.\apache-maven-3.9.6\bin\mvn test -Dtest='FlywayMigrationSmokeTest,FlywayV102_2FreeeCompanyBoundarySmokeTest'
```

## 6. BLOCKED残件（HFP-01-011）

| ID | 内容 | 必要外部条件 | 再実行 |
|---|---|---|---|
| HFP-01-G01 | freee test事業所で users/me・employee・salary・bonus spike | freeeアプリ（private）・test事業所・company_admin user・credential（FREEE_*環境変数） | 提供後、design §15.3のE2E手順 |
| AC13/AC15 | desktop/390px実操作、E2E、merge後Review | 同上 + merge | HFP-01-011 |
