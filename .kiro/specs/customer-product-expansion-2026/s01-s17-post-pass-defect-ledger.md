# S01–S17 Post-Pass Defect Ledger

基線 HEAD: `2de4aed6`  
修復ブランチ: `fix/s01-s17-post-pass-defects`（worktree `C:\work\ses-review-s17`）  
作成日: 2026-08-21

独立 Review の PASS 判定は変更しない。本表は欠陥チケット単位の修復記録のみ。

| ID | 状態 | 主なファイル | テストコマンド | 結果 |
|---|---|---|---|---|
| CROSS-P0-01 | FIXED | `FlywaySelfServiceSchemaSmokeTest`, `FlywayV106_2CompanyForwardRepairSmokeTest` | `mvn test -Pmysql-tests -Dtest=FlywaySelfServiceSchemaSmokeTest,FlywayV106_2CompanyForwardRepairSmokeTest` | コード修正済。Docker Desktop 500 のため MySQL 未実行。latest=`108.3`、V106.2 経路は `.target("106.2")` |
| S13-P0-01 | FIXED | `PortalAdminApiController`, `PortalUserAdminDto` | `mvn test -Dtest=PortalAdminApiTest,PortalAuthFlowTest,PortalRateLimitTest` | 21/0/0 PASS |
| S13-P1-01 | FIXED | `PortalSessionAdminDto` | 同上 | PASS（tokenHash 非露出） |
| S13-P1-02 | FIXED | `PortalAuthServiceImpl`, `PortalRateLimitFilter` | 同上 | PASS（password/ticket 必須・429） |
| S13-P2-01 | FIXED | `PortalMfaServiceImpl` | 同上 | PASS（`MessageDigest.isEqual`） |
| S13-P2-02 | FIXED | `PortalRateLimitFilter` | 同上 | PASS（`/submissions` 末尾スラッシュ不要） |
| S13-P2-03 | FIXED | `PortalInvitationAdminDto` | 同上 | PASS |
| S04-P0-01 | FIXED | `DocumentServiceImpl` | `mvn test -Dtest=DocumentApiControllerTest,DocumentServiceImplTest,DocumentExportServiceImplTest,FileScopeValidationServiceTest` | 35/0/0 PASS |
| S04-P0-02 | FIXED | `DocumentApiController`, `DocumentServiceImpl` | 同上 | PASS |
| S04-P1-01 | FIXED | `DocumentExportServiceImpl` | 同上 | PASS（非 CLEAN は SKIPPED） |
| S07-P0-01 | FIXED | `ApprovalEngineServiceImpl` + 4 bundle | `mvn test -Dtest=ApprovalEngineServiceTest` | 18/0/0 PASS |
| S07-P1-01 | FIXED | `ApprovalEngineServiceImpl` resubmit | 同上 | PASS（帯跨ぎで route 再解決） |
| S11-P0-01 | FIXED | `OvertimeComplianceServiceImpl`, `AttendanceServiceImpl.close`, ShedLock scheduler | `mvn test -Dtest=OvertimeComplianceCalculatorTest,OvertimeComplianceWiringTest,*Leave*Test` | 58/0/0 PASS |
| S11-P1-01 | FIXED | `LeaveApprovalAdapter` FOR UPDATE | 同上 | PASS（並行承認 1 成功） |
| S11-P2-01 | FIXED | `LeaveServiceImpl` overlap lock | 同上 | PASS |
| S09-P1-01 | FIXED | V108.2 + `SalesOrderServiceImpl` | `mvn test -Dtest=SalesOrderServiceImplTest,AcceptanceUploadDocumentUnitTest` | 単体 PASS。`SalesOrderConcurrentCreateTest` は Docker 未実行 |
| S09-P2-01 | FIXED | `AcceptanceServiceImpl` | 同上 + `AcceptanceUploadDocumentUnitTest` | PASS |
| S12-P1-01 | FIXED | `AllocationPlanServiceImpl` | `mvn test -Dtest=AllocationConcurrentConfirmTest,AllocationPlanService*Test` | 19/0/0 PASS |
| S16-P1-02 | FIXED | V108.3 + `DigitalInvoiceServiceImpl` | `mvn test -Dtest=*DigitalInvoice*,*InboundInvoice*` | 34/0/0 PASS（再 clean 後） |
| S16-P1-01 | FIXED | `MockFastAccountingProviderImpl` `@Profile("!prod")`, HMAC | 同上 | PASS |
| S16-P1-03 | FIXED | webhook 401 fail-closed | 同上 | PASS |
| S15-P1-01 | FIXED | `FreeeIntegrationServiceImpl` short-TX | `mvn test -Dtest=FreeeOAuthContractTest,*FreeeAccounting*` | 47/0/0 PASS。`FreeeConcurrentRefreshTest` Docker 未実行 |
| S15-P1-02 | FIXED | payroll/accounting missing refresh_token → REAUTH | 同上 | PASS |
| S05-P1-01 | FIXED | `BatchOperationServiceImpl`, `BATCH_TOKEN_SECRET` | `mvn test -Dtest=BatchOperationServiceH2Test,BatchOperationApiControllerTest` | 6/0/0 PASS |
| S06-P1-01 | FIXED | `BpPaymentServiceImpl`, `WorkRecordServiceImpl`, invoice UI | `mvn test -Dtest=BpPaymentWritePathTest` | 2/0/0 PASS |
| S08-P1-01 | FIXED | `CrmNormalize`, `CustomerContactServiceImpl` | `mvn test -Dtest=*CustomerContact*` | 24+/0/0 PASS |
| CROSS-P1-01 | FIXED | `ActionPermissionResolver` RESOURCE_NAMES | `mvn test -Dtest=ActionPermissionResolverTest,ActionPermissionMatrixTest` | 26/0/0 PASS |
| CROSS-P1-02 | FIXED | `PageUtils.safePage` 各 list | 同上 | PASS |
| S17-P1-01 | FIXED | `AiEvaluationApiController` run/list 管理者限定 | `mvn test -Dtest=AiEvaluationApiControllerTest,*AiExecution*,*AiFeedback*` | 27/0/0 PASS（Wave 再計測 79 件スイート含む） |
| S17-P2-01 | FIXED | `AiExecutionGatewayImpl` HTTP を TX 外へ | 同上 | PASS |
| S17-P2-02 | FIXED | segments 表示 + 90 日窓 | 同上 | PASS |
| S17-P2-04 | FIXED | matching が `request_params` へ entity ID を書かない | 同上 | PASS |
| S17-P2-03 | DEFERRED | （V108.4 予約・未使用） | — | 人手修正差分列は本輪見送り |
| CROSS-P2-01 | FIXED | `messages_en.properties` invoice/quotation 14 keys | `mvn test -Dtest=MessageBundleConsistencyTest` | 4/0/0 PASS |
| CROSS-P2-02 | FIXED | `AttendanceSyncServiceImpl.exportCsv` max-rows | AttendanceSyncServiceTest | PASS |
| S10 docs | FIXED（文書のみ） | `dispatch-outsourcing-compliance-ledger/tasks.md` | — | M に technical PASS / production B gate 未達を注記。T066 未チェック |
| S13-P1-03 | FIXED | `PortalPageController` `@GetMapping("/portal/bp")` | `mvn test -Dtest=PortalScopeMatrixTest` | 15/0/0 PASS（BP login → `/portal/bp` 200） |
| S07-P1-02 | FIXED | `ApprovalApiController.export` 真 CRLF + UTF-8 BOM | `mvn test -Dtest=ApprovalApiControllerTest` | 1/0/0 PASS |
| S12-P1-02 | FIXED | `AllocationPlanServiceImpl.saveDraft` | `mvn test -Dtest=AllocationPlanServiceTest` | 17/0/0 PASS（POST の2フィールドは DB null） |
| S06-P1-02 | FIXED | `BpComplianceServiceImpl` 60日判定 | `mvn test -Dtest=BpComplianceServiceImplTest` | 6/0/0 PASS（terms無し+期日超→EXCEEDS_MAX_PAYMENT_DAYS） |
| S15-P0-02 | FIXED | `FreeeAccountingProvider` → `freee.client-id` | `mvn test -Dtest=FreeeAccountingProviderTest#clientCredentials*` | PASS（dummy 既定廃止、prod fail-fast） |
| S15-P1-03 | FIXED | `AccountingIntegrationWorker.recoverStaleRunning` | `mvn test -Dtest=AccountingIntegrationWorkerTest` | 4/0/0 PASS（既存 deal → SUCCEEDED、無ければ RETRYABLE） |
| S07-ORG-01 | FIXED | `ApprovalOrganizationResolver` + Wave1 adapters | `mvn test -Dtest=ApprovalTargetAdapterTest,SalesOrderApprovalAdapterTest,AcceptanceApprovalAdapterTest` | snapshot に organizationId。ORGANIZATION_MANAGER が空承認者にならない |
| S07-AMT-01 | FIXED | `QuotationApprovalAdapter`（単価帯として明示） | `ApprovalTargetAdapterTest#quotationAdapterのsnapshot*` | SES見積は明細無し。amountSnapshot=unitPrice（唯一行金額）。ledger で単価帯と確定 |
| S06-VER-01 | FIXED | `BpCompanyServiceImpl.updateBpCompany` | `mvn test -Dtest=BpCompanyServiceImplTest#updateBpCompany*` | client version 必須・不一致 409 |
| S13-XFF-01 | FIXED | `ClientIpResolver` + portal login lock | `mvn test -Dtest=ClientIpResolverTest,PortalAuthFlowTest,PortalRateLimitTest` | 非信頼 remote の XFF 無視。login 失敗 N 回で 429 |
| FIND-I18N-01 | FIXED | `static/js/common.js` SES.i18n.t | `mvn test -Dtest=I18nTFallbackTest` | 2引数 fallback。placeholder 付きは置換継続 |
| FIND-SCHED-01 | FIXED | `AccountingIntegrationWorker` `@SchedulerLock` | `mvn test -Dtest=AccountingIntegrationWorkerTest` | processDueJobs / recoverStaleRunning に短 lock。HTTP は従来どおり TX 外 |

## 第二波・要確認（修復後）

| ID / 論点 | 判定 | 根拠 |
|---|---|---|
| S07 ORGANIZATION_MANAGER / Wave1 adapter `organizationId` | **FIXED** | `ApprovalOrganizationResolver` 経由で createdBy/salesUser/costCenter/engineer から解決 |
| S07 見積 `amountSnapshot` | **FIXED（単価帯）** | 明細テーブル無しのため `unitPrice` を唯一行金額＝金額帯基準と明記。seed 変更不要 |
| S06 `updateBpCompany` の version | **FIXED** | 更新前に client `version` をセット。null → 400 |
| S13 限流 X-Forwarded-For / アカウントロック | **FIXED** | `app.security.trusted-proxies` 時のみ XFF。email 失敗ロック（既定 5 回/15 分） |
| FIND-I18N-01 / FIND-SCHED-01 | **FIXED** | 上記表参照 |
| S17 FAILED 行の TX rollback | **NOT A DEFECT（第一波後）** | `execute` は `@Transactional` なし。FAILED は独立 `TransactionTemplate` で persist 済み |

## Flyway

| 版 | 用途 | 状態 |
|---|---|---|
| V108.2 | `t_sales_order.quotation_id` 可空 UNIQUE | 落地 |
| V108.3 | digital invoice SEND UNIQUE（profile/spec 含む） | 落地 |
| V108.4 | S17-P2-03 人手修正差分 | **未使用（DEFERRED）** |
| V59/V72/V82/V99 | 永久欠番 | 未充填 |

## 未実施の外部ゲート（本輪対象外）

- S01 共有 DB 多テナント（T002–T007）
- S10 G2 人間証跡 / 外部専門家 Review / PDF 目視 / GATE-T066-HISTORY
- GATE-S15-FREEE-PROD / 真 2-JVM 401 sandbox
- Peppol sandbox E2E
- GATE-S17-G10-PROD DPA
- MySQL smoke（Docker Desktop 不可時）: FlywaySelfService / V106.2 repair / SalesOrderConcurrent / FreeeConcurrentRefresh

## 協調メモ

- 並列 agent による `target/classes` 破損で一度 Spring コンテキスト失敗が発生。`mvn clean test` 後に再緑化。
- A4 が一時的に他 WIP を restore したため CROSS-P0-01 を主 AI が再適用。
