# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち | 009b6965 | 8b8451a7 | — | — | — | T081〜T084完了。T085（A2 BP portal）完了。T086以降はこのHeadから続行 |

## Issue Register

（OPEN項目なし。BpPaymentMapperTestの既存REDはT082起因でないことをHEAD再現で確認済み — 下記「既存REDの確認」参照）

## Review Packet（T085分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T085（A2. BP portal）
- base commit: `8b8451a7`（T084完了Head）
- changed files:
  - `mapper/BpPaymentMapper.java`（selectPortalPageDto/confirmReceipt CAS）
  - `service/portal/PortalBpService.java`＋Impl（新規。空き要員/発注・実績/提出物/支払状況/口座変更。全クエリbpCompanyIdをSQL境界に含める）
  - `dto/portal/`（PortalBpPaymentDto/PortalBpAvailabilityDto/PortalBpSubmissionDto/PortalBpAvailabilityRequest/PortalBpBankAccountRequest）
  - `service/approval/ApprovalTargetAdapterRegistry.java`（applicantId明示overload。portal等の内部ログイン外申請者用）
  - `service/impl/BpBankAccountChangeAdapter.java`（新規。bp_bank_account.change。承認適用でPENDING→APPROVED）
  - `controller/api/portal/PortalBpApiController.java`（新規。/api/portal/bp/**）
  - `controller/api/BpAvailabilityApiController.java`（listから未確認/却下を除外＋review endpoint追加。R3.2）
  - `dto/document/DocumentRegisterRequest.java`＋`service/impl/DocumentServiceImpl.java`（createdBy明示指定（additive）。portal等のNOT NULL対応）
  - `templates/portal/bp/index.html`・`static/portal/js/portal.js`（initBpPage）・`static/portal/css/portal.css`
  - `messages*.properties`（portal.bp.*キー、4言語）
  - テスト: `PortalBpApiTest`（新規）・`PortalScopeMatrixTest`（BP endpoint追加）
- requirements trace: R3.1（空き要員登録/更新/停止・内部review後に有効化）→ availabilities + 内部review / R3.2（発注受領確認・請求書/作業報告書提出）→ confirm-receipt CAS + submissions（scan fail-closed） / R3.3（支払状況参照のみ・金額/支払状態の変更API不存在）→ payments参照のみ / R3.4（口座変更申請・承認前は支払先へ未反映）→ 承認engine委譲（bp_bank_account.change adapter）
- migration: 本taskはDDL変更なし（t_bp_availability.statusはVARCHAR（CHECKなし）、t_document_link.target_typeは自由文字列、received_confirmed_atはV104で追加済み）
- test evidence（L2〜L3）:
  - PortalBpApiTest 8/0/0/0（A/B組織のIDOR（list/detail/受領確認/提出物404秘匿）、受領確認一回性CAS＋支払済行は不可、金額/支払状態変更API不存在（PUT/DELETE 405・pay系404）＋状態不変、空き要員reviewフロー（未確認→却下→再提出→提案可能→停止）、review前が内部候補に出ない（内部list除外）、口座変更（PENDING→承認→APPROVED・承認前未反映・担当営業未設定は400・承認engine申請作成・職務分離（承認者≠申請者））、提出物scan（EICAR拒否・CLEANのみ公開）、DTO allowlist）
  - PortalScopeMatrixTest 15/0/0/0（BP endpoint×3組織、顧客orgは403）
  - PortalAuthFlowTest 5/0/0/0・PortalCustomerApiTest 6/0/0/0・PortalRateLimitTest 2/0/0/0
  - 内部回帰: DocumentServiceImplTest 17/0/0/0・DocumentApiControllerTest 4/0/0/0・ContractDocumentApiControllerTest 9/0/0/0（archive変更の回帰）・MigrationScriptIntegrityTest 27/0/0/0・PayrollSecurityAuditTest 13/0/0/0・MobileResponsiveLayoutTest 26/0/0/0・MessageBundleConsistencyTest 4/0/0/0・JsSyntaxCheckTest 1/0/0/0
  - `git diff --check` exit 0
- Demo: BP提出→内部review→支払予定表示・口座変更申請→承認前旧口座のままのbrowser DemoはT087（M）でdesktop/390px含め実施（自動テストで検証済み）
- skipped/unverified: なし
- known issue IDs: なし（既存REDを除く）
- out-of-scope changes: なし
- rollback: 本taskはDDLなし。承認engineへの申請は既存の承認取消機能で取り消し可能（未承認のPENDING口座は支払先に影響しない）
- requested verdict: intermediate（T085完了確認）

### 設計上の決定（ledger記録）

1. **空き要員のportal提出状態は「未確認」**（既存status: 提案可能/失効/要員化済に追加。DDLはVARCHARのため変更不要）。
   内部list（/api/bp-availabilities）は既定で未確認/却下を除外し、review（承認→提案可能/却下）は同一メニュー配下の
   新endpointで実施（R3.2: review前のavailabilityが内部候補に出ない）。
2. **口座変更は承認engineへ委譲**（design §3・R3.4）: portal申請時にt_bp_bank_accountへPENDING行を作成し
   （暗号化・マスク保存。支払先としては未参照）、`bp_bank_account.change` 承認申請を発行。
   承認適用でPENDING→APPROVED（BpCompanyService.updateBankAccountApprovalのCAS）。却下時はPENDINGのまま不変。
   申請者=BP担当営業（primarySalesUserId。内部user）。未設定なら申請不可（error.portal.bp.noSalesOwner）。
   `ApprovalTargetAdapterRegistry` へapplicantId明示overloadを追加（portal principalは内部userIdを持たないため）。
3. **提出物（請求書/作業報告書）**はarchiveのregisterReceived経由（scan fail-closed: EICAR拒否・未知はUNAVAILABLE拒否）。
   t_document_link.target_type='BP_PAYMENT'で紐付け。downloadはCLEAN判定＋リンク二重認可（R4.4）。
4. **t_document_version.created_by（NOT NULL）**: portal提出はBP担当営業を明示指定（DocumentRegisterRequest.createdByは
   additive追加。内部経路は従来どおり認証userの自動補完）。
5. **受領確認（R3.2）**は status='未払' AND received_confirmed_at IS NULL のCASで一度だけ。支払済行は不可。
6. 支払予定日は取引条件（t_bp_terms）から既存式（BpComplianceServiceImplと同一）で概算表示。null=未確定。

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md, review-ledger.md, decision-log.md | L0 | matrix/threat modelレビュー | fc5ec63e | なし |
| T082 | R1.1, R1.2, R1.4, R2.1〜R3.4, R4.4 | V104, V1, H2群, entity×5, mapper×5, BpPayment, ActionPermissionResolver, smoke/mapper test, SpecDispatchConsistencyTest | L1〜L3: 39/0/0/0（実MySQL含む） | token CASはmapper test。UI DemoはT083で実施 | ec576c81 | 既存REDはHEAD再現で分離 |
| T083 | R1.4, R2.4, R4.3, R4.5, 前提(G3) | V104_1, PortalSecurityConfig/Properties, portal filters, PortalLoginUser, service/portal×5, PortalSession entity/mapper, dto/portal×7, controllers×2, templates×4, portal.js/css, yml, messages×4, SystemConfig SCHEMAS, テスト×4クラス | L1〜L3: portal 14/0/0/0 + smoke 2/0/0/0（実MySQL）+ integrity 27/0/0/0 + bundle 4/0/0/0 + 内部回帰 44/0/0/0 + JS 1/0/0/0 | 内部URL不可・IDORは自動テストで検証。browser DemoはT087 | e83dd171 | 既存REDは分離管理 |
| T084 | R2.1, R2.2, R2.3, R2.4, R4.3, R4.4 | V104_2, Invoice entity, AcceptanceService portal委譲, PortalCustomerService/Impl, dto/portal×7, PortalCustomerApiController, 顧客画面(template/js/css), messages×4, テスト×3クラス更新 | L2〜L3: portal 25/0/0/0 + smoke 2/0/0/0（実MySQL）+ integrity 27/0/0/0 + bundle 4/0/0/0 + 内部回帰 39/0/0/0 + JS 1/0/0/0 | 検収フロー等のbrowser DemoはT087で実施 | 8b8451a7 | 既存REDは分離管理 |
| T085 | R3.1, R3.2, R3.3, R3.4, R4.3, R4.4 | PortalBpService/Impl, dto/portal×5, BpPaymentMapper portalクエリ, ApprovalTargetAdapterRegistry overload, BpBankAccountChangeAdapter, PortalBpApiController, BpAvailabilityApiController（list除外+review）, DocumentRegisterRequest/DocumentServiceImpl（createdBy）, BP画面(template/js/css), messages×4, テスト×2クラス更新 | L2〜L3: portal 36/0/0/0 + integrity 27/0/0/0 + bundle 4/0/0/0 + 内部回帰 96/0/0/0（archive含む）+ JS 1/0/0/0 | BPフロー等のbrowser DemoはT087で実施 | （T085 commit） | 既存REDは分離管理 |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備（本番release gate）
- 実browserでのdesktop/390px Demo（T087で実施）
- 顧客A sessionで顧客B ID直接指定の404/403 browser Demo（T087で実施）
- 承認engineのroute設定（bp_bank_account.change）は運用時に承認設定画面で作成（本番gate）
