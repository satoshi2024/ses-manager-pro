# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち | 009b6965 | b1c00083 | — | — | — | T081〜T085完了。T086（B1 管理/通知/利用規約）完了。T087（M）はこのHeadから |

## Issue Register

（OPEN項目なし。BpPaymentMapperTestの既存REDはT082起因でないことをHEAD再現で確認済み — 下記「既存REDの確認」参照）

## Review Packet（T086分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T086（B1. 管理/通知/利用規約）
- base commit: `b1c00083`（T085完了Head）
- changed files:
  - `src/main/resources/db/migration/V104_3__portal_access_log.sql`（新規。R4.2のportal操作監査。append-only・IP hash保存）＋V1/schema-portal-h2/engineer-schema-h2/smoke同期
  - `entity/PortalAccessLog.java`・`mapper/PortalAccessLogMapper.java`（新規）
  - `service/portal/PortalAuditService`＋Impl（新規。download/検収/提出/口座変更を外部user/組織/IP/時刻で記録。best-effort）
  - `service/portal/PortalAdminService`＋Impl（新規。組織/user/招待/session/access log/規約管理。営業はDataScope）
  - `controller/api/portaladmin/PortalAdminApiController.java`（新規。/api/portal-admin/**。管理者全件・営業は自担当顧客のみ・BP組織は管理者のみ・HR/要員403）
  - `service/portal/PortalMailService`＋Impl（新規。招待メール（tokenはログへ出さない）・通知メール。既存MailService利用）
  - `service/portal/PortalNotificationService`＋Impl（新規。R4.1。組織×種別×日の重複抑止（インメモリ））
  - `service/portal/PortalContactInvalidationService`＋Impl＋`service/scheduler/PortalContactInvalidationScheduler`（新規。R1.5。毎日4:15。担当者退職/無効化→email一致portal user停止＋session失効）
  - `service/impl/AcceptanceServiceImpl.java`（@Slf4j追加＋submit/resubmit/reject後に顧客組織へ通知フック（ObjectProvider））
  - `service/impl/InvoiceServiceImpl.java`（支払済時にBP組織へ通知フック）
  - `mapper/PortalSessionMapper.java`（revokeAllForOrgをJOIN→INサブクエリへ。H2互換）
  - `controller/api/portal/PortalCustomerApiController.java`・`PortalBpApiController.java`（監査記録追加（download/検収/提出/口座変更））
  - `controller/page/portal/PortalPageController.java`（loginのreturn URL検証（相対のみ。open redirect拒否: design §5））・`templates/portal/login.html`・`portal.js`
  - `controller/page/portaladmin/PortalAdminPageController.java`・`templates/portal-admin/list.html`・`static/js/modules/portal-admin.js`
  - `messages*.properties`（portalAdmin.*・portal.notification.*、4言語）
  - テスト: `PortalAdminApiTest`（新規）・`schema-portal-h2.sql`（portal-admin menu seed。H2のMenuPermissionFilter認可に必要）
- requirements trace: R4.1（email通知・文書公開/検収/差戻し/支払状態）→ PortalNotificationService＋hooks / R4.2（download/検収/提出/口座変更の監査）→ t_portal_access_log＋audit hooks / R4.3（組織scope）→ portal-adminの営業DataScope / R1.5（退職/無効化でaccess失効）→ PortalContactInvalidationScheduler / B1（組織/user/招待/session/access log/規約管理）→ PortalAdminService / G3（規約version再同意・MFA reset session失効・組織停止session失効）→ admin API
- migration: V104_3実在（最新=V104.3）。V104〜V104_2は変更なし。4系統同期済み
- test evidence（L2〜L3・実MySQL含む）:
  - PortalAdminApiTest 9/0/0/0（管理者の組織停止→session即時失効、営業は顧客組織のみ・BP組織404・組織作成/MFA reset/規約発行は管理者のみ・HR/要員403、招待発行（token hash保存・メール本文にtoken・重複招待409）、MFA reset→session失効＋再設定必要、portal操作の監査ログ記録（download）、return URL open redirect拒否（//evil→/portalへ）、規約version発行→全sessionで再同意強制、担当者退職→失効連動でuser停止＋session失効、検収提出通知（MailService mock verify 1回）＋同日重複抑止）
  - Portal全回帰: AuthFlow 5・ScopeMatrix 15・RateLimit 2・CustomerApi 6・BpApi 8（計36/0/0/0）
  - FlywayPortalSchemaSmokeTest 2/0/0/0（実MySQL 8.0、V104_3までfresh/legacy）
  - MigrationScriptIntegrityTest 27/0/0/0・MessageBundleConsistencyTest 4/0/0/0・JsSyntaxCheckTest 1/0/0/0
  - 内部回帰: AcceptanceServiceImplTest 11/0/0/0・InvoiceServiceImplTest 41/0/0/0（通知フック追加の回帰）・PayrollSecurityAuditTest 13/0/0/0・MobileResponsiveLayoutTest 26/0/0/0
  - `git diff --check` exit 0
- Demo: 規約改定後再同意・return URL拒否のbrowser DemoはT087（M）で実施（自動テストで検証済み）
- skipped/unverified: なし
- known issue IDs: なし（既存REDを除く）
- out-of-scope changes: なし
- rollback: V104_3適用前形状へは `DELETE FROM flyway_schema_history WHERE version='104.3';` 後のt_portal_access_log DROPで原状復帰
- requested verdict: intermediate（T086完了確認）

### 設計上の決定（ledger記録）

1. **portal監査は専用のt_portal_access_log**（内部ApiAuditFilterは内部chain限定のため）。
   記録はservice/controllerからの明示呼出し（best-effort）。IPはSHA-256 hashのみ保存。
2. **email通知の重複抑止はインメモリ**（組織×種別×日。単一インスタンス前提。複数インスタンス時は共有storeへ）。
3. **担当者失効連動（R1.5）は日次バッチ**（@Scheduled 4:15 + ShedLock "portalContactInvalidation"）。
   顧客担当者（status=退職/valid_to到来/論理削除）・BP担当者（論理削除）とemail一致する
   同種組織のportal userを停止＋session失効。
4. **return URLはサーバー側で相対パスのみ許可**（先頭単一/・//・/\\・スキーム・バックスラッシュを拒否）。
   クライアント側でも再検証。
5. **H2 replayにはV104のmenu seedが無い**ため、schema-portal-h2.sqlへportal-admin menu/role seedを追加
   （MenuPermissionFilterの認可をH2でも再現。MySQLはV104がseed済み）。
6. **revokeAllForOrgはINサブクエリ**（UPDATE...JOINはH2非対応のため）。

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md, review-ledger.md, decision-log.md | L0 | matrix/threat modelレビュー | fc5ec63e | なし |
| T082 | R1.1, R1.2, R1.4, R2.1〜R3.4, R4.4 | V104, V1, H2群, entity×5, mapper×5, BpPayment, ActionPermissionResolver, smoke/mapper test, SpecDispatchConsistencyTest | L1〜L3: 39/0/0/0（実MySQL含む） | token CASはmapper test | ec576c81 | 既存REDはHEAD再現で分離 |
| T083 | R1.4, R2.4, R4.3, R4.5, 前提(G3) | V104_1, PortalSecurityConfig/Properties, portal filters, PortalLoginUser, service/portal×5, PortalSession, dto/portal×7, controllers×2, templates×4, portal.js/css, yml, messages×4, SCHEMAS, テスト×4 | L1〜L3: portal 14/0/0/0 + smoke 2/0/0/0 + integrity 27 + bundle 4 + 内部回帰 44 + JS 1 | browser DemoはT087 | e83dd171 | 既存RED分離 |
| T084 | R2.1〜R2.4, R4.3, R4.4 | V104_2, Invoice, AcceptanceService portal委譲, PortalCustomerService/Impl, dto×7, PortalCustomerApiController, 顧客画面, messages×4, テスト | L2〜L3: portal 25/0/0/0 + smoke 2/0/0/0 + integrity 27 + bundle 4 + 内部回帰 39 + JS 1 | browser DemoはT087 | 8b8451a7 | 既存RED分離 |
| T085 | R3.1〜R3.4, R4.3, R4.4 | PortalBpService/Impl, dto×5, BpPaymentMapper, ApprovalTargetAdapterRegistry overload, BpBankAccountChangeAdapter, PortalBpApiController, BpAvailabilityApiController, DocumentRegisterRequest/DocumentServiceImpl, BP画面, messages×4, テスト | L2〜L3: portal 36/0/0/0 + integrity 27 + bundle 4 + 内部回帰 96 + JS 1 | browser DemoはT087 | b1c00083 | 既存RED分離 |
| T086 | R1.5, R4.1, R4.2, B1, G3 | V104_3, PortalAccessLog, PortalAuditService, PortalAdminService/Impl, PortalAdminApiController, PortalMailService, PortalNotificationService, PortalContactInvalidationService+Scheduler, Acceptance/Invoice通知フック, PortalSessionMapper, 監査hooks, return URL, portal-admin画面(controller/template/js), messages×4, テスト | L2〜L3: portal 45/0/0/0 + smoke 2/0/0/0 + integrity 27 + bundle 4 + 内部回帰 91 + JS 1 | browser DemoはT087 | （T086 commit） | 既存RED分離 |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備・SMTP設定（本番release gate）
- 実browserでのdesktop/390px Demo（T087で実施）
- 承認engineのroute設定（bp_bank_account.change）は運用時に承認設定画面で作成（本番gate）
