# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち | 009b6965 | ec576c81 | — | — | — | T081/T082完了。T083（F2 security chain/DTO boundary）完了。T084以降はこのHeadから続行 |

## Issue Register

（OPEN項目なし。BpPaymentMapperTestの既存REDはT082起因でないことをHEAD再現で確認済み — 下記「既存REDの確認」参照）

## Review Packet（T083分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T083（F2. 専用security chain/DTO boundary）
- base commit: `ec576c81`（T082完了Head）
- changed files:
  - `src/main/resources/db/migration/V104_1__portal_session.sql`（新規。t_portal_session＋t_portal_user.last_used_step）
  - `V1__create_tables.sql`・`schema-portal-h2.sql`・`engineer-schema-h2.sql`（V104_1同期）
  - `config/PortalSecurityConfig.java`（新規。@Order(1) portal chain。portal専用CSRF cookie/header・STATELESS・entry point/denied handler）
  - `config/PortalSecurityProperties.java`（新規。app.portal.*）
  - `config/portal/PortalSessionFilter.java`・`PortalRateLimitFilter.java`（新規）
  - `portal/PortalLoginUser.java`（新規principal。内部LoginUserへ変換する経路なし）
  - `service/portal/`（PortalSessionService/Impl・PortalAuthService/Impl・PortalMfaService/Impl・PortalAuthorizationService/Impl・PortalRateLimiter/Impl）
  - `entity/PortalSession.java`・`mapper/PortalSessionMapper.java`（新規）・`entity/PortalUser.java`（lastUsedStep）
  - `dto/portal/`（LoginRequest/LoginResponse/MfaSetupDto/MfaCompleteDto/AcceptInvitationRequest/ConsentRequest/MeDto）
  - `controller/api/portal/PortalAuthApiController.java`・`controller/page/portal/PortalPageController.java`（新規）
  - `templates/portal/*.html`（login/accept-invitation/terms/index、モバイル優先・専用layout）・`static/portal/js/portal.js`・`static/portal/css/portal.css`
  - `application.yml`（app.portal.*）
  - `messages.properties`/`_en`/`_zh_CN`/`_ko`（portal.*キー追加、4言語同一キー集合）
  - `service/impl/SystemConfigServiceImpl.java`（portal.* SCHEMAS登録）
  - テスト: `src/test/java/com/ses/portal/`（PortalTestSupport/PortalAuthFlowTest/PortalScopeMatrixTest/PortalRateLimitTest）・`FlywayPortalSchemaSmokeTest.java`（V104_1 assert追加）
- requirements trace: R1.4（TOTP必須・recovery code 1回限り・terms同意・最終login・停止・session失効）→ PortalMfaService/AuthService/SessionService / R4.3（組織scopeをquery boundaryで）→ PortalAuthorizationService・matrix test / R4.5（rate limit）→ PortalRateLimitFilter / G3（別chain・別cookie・別CSRF・招待4条件・別identity）→ PortalSecurityConfig・AuthService
- migration: V104_1実在（最新=V104.1）。V59/V72/V82/V99欠番維持。4系統同期済み
- test evidence（L1〜L3・実MySQL含む）:
  - PortalAuthFlowTest 5/0/0/0（login→MFA_SETUP→enable→recovery code→session発行、同一TOTP step再使用拒否（last_used_step CAS）、recovery code 1回限り、招待4条件+token一回性+hash保存、user/組織停止の即時session失効、terms version更新後の再同意強制）
  - PortalScopeMatrixTest 7/0/0/0（3組織×endpoint matrixの相互非参照、内部API/pageへの401（本アプリ既存契約どおり。PayrollSecurityAuditTest「未認証はpage/APIとも401」と同一）、CSRF分離、公開DTOに内部情報なし）
  - PortalRateLimitTest 2/0/0/0（login/招待の429。専用IPでbucket分離）
  - FlywayPortalSchemaSmokeTest 2/0/0/0（実MySQL 8.0 fresh/legacy、V104.1まで、shape一致）
  - MigrationScriptIntegrityTest 27/0/0/0・PortalEntityMapperTest 2/0/0/0・MessageBundleConsistencyTest 4/0/0/0・JsSyntaxCheckTest 1/0/0/0
  - 内部回帰: PayrollSecurityAuditTest 13/0/0/0・MobileResponsiveLayoutTest 26/0/0/0・LoginSuccessHandlerAuditTest 2/0/0/0・LoginSuccessHandlerBreakGlassTest 1/0/0/0・ConcurrentLoginSessionSmokeTest 2/0/0/0（内部chainがportal chain追加後も不変）
  - `git diff --check` exit 0
- Demo（CONDITIONAL gate・実browser）: portal userの内部URL到達不可・顧客A sessionで顧客B ID直接指定の404/403は、データendpoint実装後のT084/T085でdesktop/390px含め実施（自動テストではmatrixで検証済み）。招待→登録→MFA設定→規約同意のUI DemoはT083で実装済みのフローをT087のbrowser Demoで実施
- skipped/unverified: なし
- known issue IDs: なし（既存REDを除く）
- out-of-scope changes: なし
- rollback: V104_1適用前形状へは `DELETE FROM flyway_schema_history WHERE version='104.1';` 後のt_portal_session DROP＋last_used_step列DROPで原状復帰
- requested verdict: intermediate（T083完了確認）

### 設計上の決定（ledger記録）

1. **portal sessionはDB永続token**（t_portal_session。生tokenはcookieのみ・DBはSHA-256 hash）。
   servlet HttpSession（JSESSIONID）を使わずSTATELESS＋portal専用cookie PORTAL_SESSION。停止/MFA reset/
   管理者操作はrevokeAllForUserで即時全失効（G3）。B1のsession管理画面で一覧・失効が可能。
2. **MFA reset時はrevokeAllForUserを明示呼出し**（t_portal_user.last_used_step CASでTOTP再使用も拒否）。
   MFA secretは内部MfaServiceImplと同じAES-GCM形式で暗号化保存。
3. **portalパスワードは常にBCrypt**（内部のprofile切替NoOp encoderに依存しない。portalは外部公開のため）。
4. **招待の2人目以降の組織管理者承認**は「招待発行主体=組織管理者」で成立させる（初回のみ内部管理者が
   発行、B1で招待発行APIを実装）。受諾側では4条件＋DB CAS一回性を実装済み。
5. **内部chainのMenuPermissionFilterが持つportal遮断プレースホルダ**（`/portal/**` deny）は、
   portal chain導入後は到達前に遮断されるため冗長だが、fail-closed維持のため残置（削除は他spec範囲外）。
6. 内部URLへの到達不可は**401**（本アプリの既存契約「未認証はpage/APIとも401」）。
   F2 Demo表記の403はこの既存契約により401で実現されることをledgerに記録。

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md, review-ledger.md, decision-log.md | L0 | matrix/threat modelレビュー | fc5ec63e | なし |
| T082 | R1.1, R1.2, R1.4, R2.1〜R3.4, R4.4 | V104, V1, schema-portal-h2, application-test.yml, engineer-schema-h2, entity×5, mapper×5, BpPayment, ActionPermissionResolver, smoke/mapper test, SpecDispatchConsistencyTest | L1〜L3: 39/0/0/0（実MySQL含む） | token CASはmapper test。UI DemoはT083で実施 | ec576c81 | 既存REDはHEAD再現で分離 |
| T083 | R1.4, R2.4, R4.3, R4.5, 前提(G3) | V104_1, PortalSecurityConfig/Properties, portal filters, PortalLoginUser, service/portal×5, PortalSession entity/mapper, dto/portal×7, controllers×2, templates×4, portal.js/css, yml, messages×4, SystemConfig SCHEMAS, テスト×4クラス | L1〜L3: portal 14/0/0/0 + smoke 2/0/0/0（実MySQL）+ integrity 27/0/0/0 + bundle 4/0/0/0 + 内部回帰 44/0/0/0 + JS 1/0/0/0 | 内部URL不可・IDORは自動テストで検証。browser DemoはT087 | （T083 commit） | 既存REDは分離管理 |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備（本番release gate）
- 実browserでのdesktop/390px Demo（T087で実施）
- 顧客A sessionで顧客B ID直接指定の404/403 browser Demo（T084/T085で実施）
