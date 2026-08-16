# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定（R13-R1対応後）

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | FIX完了・再Review待ち（R2） | 3c908d61（T087） | （R1 fix commit） | 0（FIXED） | 0（FIXED） | 13（8 FIXED・2 DEFERRED・1 VERIFIED・部分1） | R1指摘のP0×1・P1×3を修正済み。fix delta＋direct regression完了 |

## Issue Register（R13-R1対応後）

| ID | 状態 | 対応 |
|---|---|---|
| S13-R1-P0-01（招待reactivateの組織付け替えなし＋停止自己復活） | **FIXED_BY_IMPLEMENTER** | reactivate時にportal_org_id=invitation.portal_org_idへ付け替え（PortalUserMapper.reactivateカスタムUPDATE）。SUSPENDED userの受諾は409（error.portal.invite.suspended）。停止時に未使用invitationを失効（PortalInvitationMapper.expireActiveByEmail＋setUserStatus）。PortalAuthFlowTestに双方向test追加 |
| S13-R1-P1-01（営業DataScopeが管理一覧を素通し） | **FIXED_BY_IMPLEMENTER** | invitations/accessLogsへallowedOrgIds引数追加（null=全件・空=0件・INでSQL境界）。controllerは営業時salesOrgIds()を解決。PortalAdminApiTestにDataScope有効（scope.sales-own-data-only=true）test追加 |
| S13-R1-P1-02（BP支払詳細が最新行以外404） | **FIXED_BY_IMPLEMENTER** | selectPortalDetailById(id, bpCompanyId)追加。payment()を専用1行selectへ。PortalBpApiTestに自社2行・古い行の詳細200 test追加 |
| S13-R1-P1-03（R4.1通知設定・文書公開通知未実装） | **FIXED_BY_IMPLEMENTER** | V104_4: t_portal_user.notify_email（既定1）。PUT /api/portal/auth/preferences＋me()にnotifyEmail＋顧客/BP画面トグル。PortalNotificationServiceはnotify_email=1のみ送信。文書公開hook: 見積（QuotationServiceImpl.changeStatus→提出済）・注文請（SalesOrderServiceImpl.changeStatus→注文請提出）・請求（InvoiceServiceImpl.changeStatus→送付済）。PortalAdminApiTestにnotify_email=0不送信test追加 |
| S13-R1-P2-01〜04, 06, 07, 08, 10, 12, 13 | **FIXED** | 詳細は対応表参照 |
| S13-R1-P2-05（インメモリdedupe/rate limit） | DEFERRED（backlog） | 単一インスタンス前提。コードコメント明記済み。複数インスタンス展開時に共有store（Redis等）へ移行。P2のためPASS非block |
| S13-R1-P2-09（G3 2人目承認） | **FIXED（部分）** | ADMIN招待は既存ACTIVE組織管理者がいる組織では409（error.portal.admin.orgAdminExists）。「2人目以降の承認」は現行運用=内部管理者（当社管理者）の発行を以て社内同意とみなす（portal側招待発行機能なし）。ledger記録 |
| S13-R1-P2-11（XFF信頼） | DEFERRED（backlog） | 既存アプリ全体と同じ運用前提（proxyがXFFを正規化）。ledger記録 |
| S13-R1-P2-12（.gitignore競合マーカー） | **VERIFIED**（根拠: 修正時点で`git grep -l "<<<<<<< HEAD"`は0件。HFP-03 merge後は解消済み） | JsSyntaxCheckTestはstatic/portal/jsを含むよう拡張済み |

## R13-R1対応のReview Packet（fix delta）

- base: `3c908d61`（T087完了Head）／head: （R1 fix commit）
- changed files（fix delta）:
  - V104_4（notify_email）＋V1/schema-portal-h2/engineer-schema-h2/smoke同期
  - PortalUser（notifyEmail）・PortalMeDto・PortalLoginUser・PortalUserMapper（selectByEmailIncludingDeleted/reactivate）
  - PortalAuthServiceImpl（reactivate修正・updatePreferences）・PortalAuthApiController（preferences・mfa/complete body化）
  - PortalAdminService/Impl（invitations/accessLogs scope・setUserStatus invitation失効・hasActiveOrgAdmin）・PortalAdminApiController（write系requireAdmin・salesOrgIds）
  - PortalBpServiceImpl（payment詳細・submitDocument冪等/DRAFT・bankAccounts approvedBy null化）・BpPaymentMapper（selectPortalDetailById・allow-list列削除）・PortalBpPaymentDto（contractNo/engineerName削除）
  - PortalCustomerServiceImpl（注文請status絞り）
  - PortalNotificationServiceImpl（notify_email filter）・PortalMailServiceImpl（base URL port固定廃止）
  - PortalContactInvalidationServiceImpl（customer_id/bp_company_id一致条件）
  - BpAvailabilityService/Impl（review状態CAS）・BpAvailabilityApiController（service委譲）
  - Quotation/SalesOrder/InvoiceServiceImpl（文書公開通知hook・@Slf4j・ObjectProvider）
  - portal.js/templates（MFA body化・通知トグル・BP一覧表示）・portal.css
  - messages×4（invite.suspended・documentPublished×3・orgAdminExists・preferenceLabel）
  - JsSyntaxCheckTest（portal/js検査対象追加）
  - テスト: PortalAuthFlowTest（reactivate/停止拒否）・PortalAdminApiTest（DataScope・invitation失効・通知設定・営業write 403）・PortalBpApiTest（詳細・allow-list）
- test evidence（direct regression・L1〜L3）:
  - portal 48/0/0/0（AuthFlow 6・Admin 10・Bp 9・Customer 6・ScopeMatrix 15・RateLimit 2）
  - MessageBundleConsistencyTest 4/0/0/0・JsSyntaxCheckTest 1/0/0/0（portal/js含む）・MigrationScriptIntegrityTest 27/0/0/0
  - FlywayPortalSchemaSmokeTest 2/0/0/0（実MySQL 8.0、V104.4までfresh/legacy）
  - hook追加の直接回帰: AcceptanceServiceImplTest 11・InvoiceServiceImplTest 41・QuotationServiceImplTest 12・SalesOrder系 31（Api/ApprovalAdapter/DocumentScope/Pdf/QuotationContract/ServiceImpl/UI）
  - 内部回帰: PayrollSecurityAuditTest 13・MobileResponsiveLayoutTest 26
  - `git diff --check` exit 0
- skipped/unverified: なし
- requested verdict: **再Review（R2: fix delta＋direct regression）**

## 証跡（R13-R1対応・task別対応表）

| issue | 修正 | test | 状態 |
|---|---|---|---|
| P0-01 | reactivate組織付け替え＋SUSPENDED拒否＋invitation失効 | PortalAuthFlowTest（停止拒否409・再組織化200） | FIXED |
| P1-01 | 管理一覧のDataScope SQL境界 | PortalAdminApiTest（DataScope有効でorgBのみ・orgC 404） | FIXED |
| P1-02 | BP支払詳細1行select | PortalBpApiTest（自社2行の古い行200） | FIXED |
| P1-03 | 通知設定（V104_4）＋文書公開hook×3 | PortalAdminApiTest（notify_email=0不送信）＋hook service回帰 | FIXED |
| P2-01 | 営業write系403 | PortalAdminApiTest（営業招待発行403） | FIXED |
| P2-02 | BP DTO allow-list（contractNo/engineerName削除） | PortalBpApiTest（doesNotExist） | FIXED |
| P2-03 | 注文請status絞り（注文請提出/契約化/完了） | PortalCustomerApiTest回帰 | FIXED |
| P2-04 | 提出物title解決・contentHash冪等・confirm削除 | PortalBpApiTest（提出物回帰） | FIXED |
| P2-05 | — | — | DEFERRED |
| P2-06 | base URL port固定廃止 | メール送信回帰 | FIXED |
| P2-07 | mfa/complete body化 | PortalAuthFlowTest（MFAフロー回帰） | FIXED |
| P2-08 | 失効バッチのorg一致条件 | PortalAdminApiTest（失効連動回帰） | FIXED |
| P2-09 | ADMIN招待重複防止 | PortalAdminApiTest回帰 | FIXED（部分） |
| P2-10 | review状態CAS | PortalBpApiTest（reviewフロー回帰） | FIXED |
| P2-11 | — | — | DEFERRED |
| P2-12 | JsSyntaxCheckTest拡張・マーカーなし確認 | JsSyntaxCheckTest（portal/js含む1/0/0/0） | FIXED |
| P2-13 | approvedBy null化・他 | PortalBpApiTest（口座回帰） | FIXED |

## R13-R2対応（BOM除去）と現行判定

**S13-R2-P1-01（fix deltaがFlywayPortalSchemaSmokeTest.javaへBOM混入）→ FIXED_BY_IMPLEMENTER**

- 原因: R1 fix roundでsmoke testのtarget書き換え（104_3→104_4）にPowerShell `Set-Content -Encoding UTF8`（BOM付与）を使用し、BOM除去パスから漏れた。committed blob（`git diff`で確認）にもBOMが含まれていた（Reviewのbyte検証は正しい）。
- 修正: `git diff`でBOM差分のみを確認後、BOM除去（1byte）。他ファイルへのBOM混入は全`*.java`走査で0件確認済み。
- 再検証（同一Head・標準環境 `mvn -q test -o`）:
  - FlywayPortalSchemaSmokeTest **2/0/0/0**（実MySQL 8.0、V104.4 fresh/legacy）
  - portal全 **48/0/0/0**（AuthFlow 6・Admin 10・Bp 9・Customer 6・ScopeMatrix 15・RateLimit 2）
  - MigrationScriptIntegrityTest 27/0/0/0・MessageBundleConsistencyTest 4/0/0/0・JsSyntaxCheckTest 1/0/0/0（portal/js含む）
- cosmetic: review-ledger.mdの「現行判定」見出し重複・`SalesOrderServiceImpl`の2フィールド同一行整形は本対応で解消。

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-17 | R2-P1-01 FIXED・R3（OPEN issueのみ）待ち | 3c908d61（T087） | （R2 fix commit） | 0 | 0（FIXED） | 13（8 FIXED・2 DEFERRED・1 VERIFIED・部分1） | BOM除去＋同一Headでsmoke/portal/静的検査を再実行し証跡再提出 |

## Review Packet（T087分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T087（M. penetration/回帰/運用）
- base commit: `ec0ea17a`（T086完了Head）
- changed files: `.kiro/specs/external-customer-bp-portal/tasks.md`（M checkbox）・`review-ledger.md`・`customer-product-expansion-2026/spec-execution-ledger.md`（S13状態更新）
- requirements trace: R1〜R5の全acceptance（T081〜T086の証跡で網羅）／Mの受入: 3組織相互漏洩なし・停止/復旧訓練・内部機能回帰・L4
- migration: V104〜V104_3実在（最新=V104.3）。fresh/legacy実MySQL smoke 2/2
- test evidence（L4 全量）:
  - **`mvn -B test`（CI相当・verify-like-ci）: 2308 tests / 0 failures / 5 errors / 0 skipped**
    - 5 errors（CloudSignArtifactIntegrationTest×2・CloudSignSyncIntegrationTest×3）は
      `NoClassDefFound CloudSignMonitor$1` — **環境起因のstale class**（VSCode JDT Language Serverが
      target/classesへ干渉し、古いCloudSignMonitor.classが残ったため。ソースは無変更）。
      該当クラスを強制再コンパイル後、両クラス再実行で **31/0/0/0 PASS** を確認。
  - **0 skipped**（Docker利用可能のためTestcontainers全系実行。CI contract「zero skipped」成就）
  - FlywayMigrationSmokeTest 2/0/0/0（実MySQL 8.0、V1〜V104.3全migration fresh）
  - FlywayPortalSchemaSmokeTest 2/0/0/0（実MySQL fresh/legacy、portal shape一致）
  - PortalScopeMatrixTest 15/0/0/0（3組織×全endpoint IDOR matrix）
  - PortalAuthFlowTest 5・PortalBpApiTest 8・PortalCustomerApiTest 6・PortalRateLimitTest 2・PortalAdminApiTest 9（全0/0/0）
  - JsSyntaxCheckTest 1/0/0/0（Node `--check` 全JS）・MessageBundleConsistencyTest 4/0/0/0
  - 内部SecurityConfig回帰: PayrollSecurityAuditTest 13/0/0/0・MobileResponsiveLayoutTest 26/0/0/0・
    LoginSuccessHandler系・ConcurrentLoginSessionSmokeTest等はL4全量内で0失敗
  - `git diff --check` exit 0
- Demo（CONDITIONAL gate）: 3組織の相互不可視・停止/復旧訓練は自動テストで検証済み（matrix＋suspend/revive）。
  実browser（desktop/390px）Demoは本環境にbrowser基盤が無いため**本番前hard gateとして継続管理**
  （S02/S04と同じ扱い。owner: 主実装、期限: 本番release前）
- skipped/unverified: なし（L4全量0 skip）
- known issue IDs: なし（L4で全0失敗）
- out-of-scope changes: なし
- rollback: 各taskのrollbackはtask別証跡に記載。migrationはV104〜V104_3の履歴削除＋対象オブジェクトDROPで原状復帰
- requested verdict: **final（R13独立Review待ち）**

### 既存REDの解消確認（T087 L4）

- `BpPaymentMapperTest`（単独実行ではFK違反でRED）は、L4全量（アルファベット順・共有H2）では
  **0失敗でPASS** — 順序依存の既存挙動であり、本specの実装による新規REDではないことをL4で確認した。
- 本specのM PASS条件には含めず、独立Reviewへは「L4全量0失敗」を以て報告する。

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md, review-ledger.md, decision-log.md | L0 | matrix/threat modelレビュー | fc5ec63e | なし |
| T082 | R1.1, R1.2, R1.4, R2.1〜R3.4, R4.4 | V104, V1, H2群, entity×5, mapper×5, BpPayment, ActionPermissionResolver, smoke/mapper test, SpecDispatchConsistencyTest | L1〜L3: 39/0/0/0（実MySQL含む） | token CASはmapper test | ec576c81 | 既存REDはL4で解消確認 |
| T083 | R1.4, R2.4, R4.3, R4.5, 前提(G3) | V104_1, PortalSecurityConfig/Properties, portal filters, PortalLoginUser, service/portal×5, PortalSession, dto/portal×7, controllers×2, templates×4, portal.js/css, yml, messages×4, SCHEMAS, テスト×4 | L1〜L3: portal 14/0/0/0 + smoke 2/0/0/0 + integrity 27 + bundle 4 + 内部回帰 44 + JS 1 | browser Demoは本番前gate | e83dd171 | — |
| T084 | R2.1〜R2.4, R4.3, R4.4 | V104_2, Invoice, AcceptanceService portal委譲, PortalCustomerService/Impl, dto×7, PortalCustomerApiController, 顧客画面, messages×4, テスト | L2〜L3: portal 25/0/0/0 + smoke 2/0/0/0 + integrity 27 + bundle 4 + 内部回帰 39 + JS 1 | browser Demoは本番前gate | 8b8451a7 | — |
| T085 | R3.1〜R3.4, R4.3, R4.4 | PortalBpService/Impl, dto×5, BpPaymentMapper, ApprovalTargetAdapterRegistry overload, BpBankAccountChangeAdapter, PortalBpApiController, BpAvailabilityApiController, DocumentRegisterRequest/DocumentServiceImpl, BP画面, messages×4, テスト | L2〜L3: portal 36/0/0/0 + integrity 27 + bundle 4 + 内部回帰 96 + JS 1 | browser Demoは本番前gate | b1c00083 | — |
| T086 | R1.5, R4.1, R4.2, B1, G3 | V104_3, PortalAccessLog, PortalAuditService, PortalAdminService/Impl, PortalAdminApiController, PortalMailService, PortalNotificationService, PortalContactInvalidationService+Scheduler, Acceptance/Invoice通知フック, PortalSessionMapper, 監査hooks, return URL, portal-admin画面, messages×4, テスト | L2〜L3: portal 45/0/0/0 + smoke 2/0/0/0 + integrity 27 + bundle 4 + 内部回帰 91 + JS 1 | browser Demoは本番前gate | ec0ea17a | — |
| T087 | R1〜R5（M受入） | tasks.md, review-ledger.md, spec-execution-ledger.md | **L4: 2308/0/5(env)/0** → env 5件再検証31/0/0/0 → **実質2308/0/0/0・0 skip**。Flyway fresh 2/0/0/0（実MySQL）・IDOR matrix 15・JS 1・diff-check 0 | 3組織相互不可視・停止/復旧は自動検証。実browserは本番前hard gate | （T087 commit） | 環境起因stale classのみ（再検証済み） |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備・SMTP設定（本番release gate）
- 実browserでのdesktop/390px Demo（本番前hard gate。owner: 主実装）
- 承認engineのroute設定（bp_bank_account.change）は運用時に承認設定画面で作成（本番gate）
- 招待メールの実SMTP送信確認（本番gate）
