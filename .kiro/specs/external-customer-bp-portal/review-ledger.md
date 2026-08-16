# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち（T081〜T087全task完了） | 009b6965 | （T087 commit） | — | — | — | 全feature実装＋L4全量完了。独立Review待ち |

## Issue Register

（OPEN項目なし。BpPaymentMapperTestはL4全量（アルファベット順）ではPASS — 既存REDは順序依存で、全量実行ではgreen。T087で解消確認済み）

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
