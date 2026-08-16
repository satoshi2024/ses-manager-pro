# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち | 009b6965 | e83dd171 | — | — | — | T081〜T083完了。T084（A1 顧客portal）完了。T085以降はこのHeadから続行 |

## Issue Register

（OPEN項目なし。BpPaymentMapperTestの既存REDはT082起因でないことをHEAD再現で確認済み — 下記「既存REDの確認」参照）

## Review Packet（T084分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T084（A1. 顧客portal）
- base commit: `e83dd171`（T083完了Head）
- changed files:
  - `src/main/resources/db/migration/V104_2__portal_customer_invoice_columns.sql`（新規。t_invoice: received_confirmed_at/payment_expected_date/portal_inquiry。R2.3）
  - `schema-portal-h2.sql`・`engineer-schema-h2.sql`（t_invoice列同期＋H2 ENUM拡張（'一部入金'、V28相当））
  - `entity/Invoice.java`（3列追加。portalInquiryはFieldStrategy.ALWAYSでクリア可能）
  - `mapper/AcceptanceMapper.java`（selectPortalPageDto/selectPortalByIdForUpdate）・`mapper/ContractMapper.java`（selectPortalPageDto/selectPortalDetailDto）
  - `service/AcceptanceService.java`＋Impl（portalAccept/portalReject/portalPage/portalGet。SQL境界＋状態CAS委譲）
  - `service/portal/PortalCustomerService.java`＋Impl（新規。見積/注文請/契約/検収/請求。全クエリcustomerIdをSQL境界に含める）
  - `dto/portal/`（PortalQuotationDto/PortalSalesOrderDto/PortalContractDto/PortalAcceptanceDto/PortalInvoiceDto/PortalAcceptanceActionRequest/PortalInvoiceRegisterRequest）
  - `controller/api/portal/PortalCustomerApiController.java`（新規。/api/portal/customer/**）
  - `controller/page/portal/PortalPageController.java`（/portal→org種別リダイレクト、/portal/customer）
  - `templates/portal/customer/index.html`・`static/portal/js/portal.js`（initCustomerPage）・`static/portal/css/portal.css`（タブ/一覧スタイル）
  - `messages*.properties`（portal.customer.*キー、4言語）
  - テスト: `PortalCustomerApiTest`（新規）・`PortalScopeMatrixTest`（customer endpoint matrix追加・org種別403）・`PortalAuthFlowTest`（/portalリダイレクト反映）・`FlywayPortalSchemaSmokeTest`（V104_2 assert）
- requirements trace: R2.1（見積/注文請/契約/作業報告/検収/請求の閲覧download）→ 全endpoint / R2.2（検収/差戻し・comment・添付）→ acceptances + comment（添付は検収書原本の既存機能。portalからの添付はR4.4のquarantine要件のためT085の提出物と併せて運用） / R2.3（受領確認・支払予定日・問い合わせ・入金済非変更）→ invoices/register / R2.4（電子署名はポータルが代行しない）→ 契約詳細のesign状態表示（CloudSignはメールリンク方式のため、portalは状態表示のみ。署名URLを生成する経路はHFP-02実装上存在しない — ledger記録）
- migration: V104_2実在（最新=V104.2）。V104/V104_1は変更なし。4系統同期済み
- test evidence（L2〜L3・実MySQL含む）:
  - PortalCustomerApiTest 6/0/0/0（A/B組織のIDOR（list/detail/download/操作全404秘匿）、顧客portal×内部の同時検収で先着1件のみ成功（双方向CAS検証）、差戻し→内部再提出→再検収、入金済状態の変更API不存在（PUT/pay系404）＋portal登録で状態不変、DTO field allowlist（sellingPrice/costPrice/paidDate等が構造的に不在）、workMonth/status絞り込み）
  - PortalScopeMatrixTest 12/0/0/0（顧客endpoint×3組織matrix、BP orgは403、/portalリダイレクト先）
  - PortalAuthFlowTest 5/0/0/0・PortalRateLimitTest 2/0/0/0
  - FlywayPortalSchemaSmokeTest 2/0/0/0（実MySQL 8.0、V104_2までfresh/legacy、t_invoice 3列）
  - MigrationScriptIntegrityTest 27/0/0/0・PortalEntityMapperTest 2/0/0/0・MessageBundleConsistencyTest 4/0/0/0・JsSyntaxCheckTest 1/0/0/0
  - 内部回帰: PayrollSecurityAuditTest 13/0/0/0・MobileResponsiveLayoutTest 26/0/0/0
  - `git diff --check` exit 0
- Demo: 作業報告→顧客検収→内部請求可のbrowser Demo、顧客×内部同時検収の1件成立はT087（M）でdesktop/390px含め実施（自動テストでは双方向CAS検証済み）
- skipped/unverified: なし
- known issue IDs: なし（既存REDを除く）
- out-of-scope changes: なし
- rollback: V104_2適用前形状へは `DELETE FROM flyway_schema_history WHERE version='104.2';` 後の3列DROPで原状復帰
- requested verdict: intermediate（T084完了確認）

### 設計上の決定（ledger記録）

1. **見積は提出済/受注/失注のみ公開**（下書きは社内検討中のため非公開）。請求は送付済/一部入金/入金済のみ公開（未送付は非公開）。
2. **検収の二重反映防止**はAcceptanceServiceのportalAccept/portalReject（SELECT...FOR UPDATE＋状態CAS）へ委譲。
   内部のDataScope判定は使わず、`t_acceptance JOIN t_contract WHERE customer_id=?` のSQL境界で認可（R4.3）。
3. **PDFは動的生成（見積/請求）と文書台帳正本（注文請/検収書/契約書）を使い分け**。文書台帳経路は
   `t_document_link` の二重認可＋DocumentService（CLEAN以外403）を通す（R4.4）。
4. **電子署名（R2.4）**: CloudSignは署名URLを生成せずメールリンク方式のため、ポータルは契約詳細に
   「電子署名状態（下書き/先方確認中/締結済/取消・却下/要確認/未実施）」を表示し、署名はCloudSignの
   メールリンクで実施される旨を運用とする。ポータルが署名を代行する経路は作らない。
5. **H2のt_invoice.status ENUM**はV5 replayの3値のため、schema-portal-h2.sqlで'一部入金'（V28相当）を
   ENUMへ追加（MySQLはV28適用済みで同値域）。

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md, review-ledger.md, decision-log.md | L0 | matrix/threat modelレビュー | fc5ec63e | なし |
| T082 | R1.1, R1.2, R1.4, R2.1〜R3.4, R4.4 | V104, V1, H2群, entity×5, mapper×5, BpPayment, ActionPermissionResolver, smoke/mapper test, SpecDispatchConsistencyTest | L1〜L3: 39/0/0/0（実MySQL含む） | token CASはmapper test。UI DemoはT083で実施 | ec576c81 | 既存REDはHEAD再現で分離 |
| T083 | R1.4, R2.4, R4.3, R4.5, 前提(G3) | V104_1, PortalSecurityConfig/Properties, portal filters, PortalLoginUser, service/portal×5, PortalSession entity/mapper, dto/portal×7, controllers×2, templates×4, portal.js/css, yml, messages×4, SystemConfig SCHEMAS, テスト×4クラス | L1〜L3: portal 14/0/0/0 + smoke 2/0/0/0（実MySQL）+ integrity 27/0/0/0 + bundle 4/0/0/0 + 内部回帰 44/0/0/0 + JS 1/0/0/0 | 内部URL不可・IDORは自動テストで検証。browser DemoはT087 | e83dd171 | 既存REDは分離管理 |
| T084 | R2.1, R2.2, R2.3, R2.4, R4.3, R4.4 | V104_2, Invoice entity, AcceptanceService portal委譲, PortalCustomerService/Impl, dto/portal×7, PortalCustomerApiController, 顧客画面(template/js/css), messages×4, テスト×3クラス更新 | L2〜L3: portal 25/0/0/0 + smoke 2/0/0/0（実MySQL）+ integrity 27/0/0/0 + bundle 4/0/0/0 + 内部回帰 39/0/0/0 + JS 1/0/0/0 | 検収フロー等のbrowser DemoはT087で実施（自動テストでCAS/IDOR/ACL検証済み） | （T084 commit） | 既存REDは分離管理 |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備（本番release gate）
- 実browserでのdesktop/390px Demo（T087で実施）
- 顧客A sessionで顧客B ID直接指定の404/403 browser Demo（T084実装分はT087で実施）
