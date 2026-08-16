# Review Ledger — 顧客・BP外部ポータル（S13）

> 書式: `customer-product-expansion-2026/review-ledger-template.md`。append-only。
> 判定は独立Review AIのみが出す。実装AIは `REVIEW待ち` まで記録する。

## 現行判定

| 日付 | 判定 | Base | Head | P0 | P1 | P2 | 備考 |
|---|---|---|---|---|---|---|---|
| 2026-08-16 | REVIEW待ち | 009b6965 | fc5ec63e | — | — | — | T081完了。T082（F1 DDL）完了。T083以降はこのHeadから続行 |

## Issue Register

（OPEN項目なし。BpPaymentMapperTestの既存REDはT082起因でないことをHEAD再現で確認済み — 下記「既存REDの確認」参照）

## Review Packet（T082分）

- handbook version: v2.0
- spec/tasks: external-customer-bp-portal / T082（F1. portal org/user/invite/consent DDL）
- base commit: `fc5ec63e`（T081完了Head）
- changed files:
  - `src/main/resources/db/migration/V104__external_customer_bp_portal.sql`（新規。portal 5テーブル・`t_bp_payment.received_confirmed_at`・config seed・portal-admin menu/action seed）
  - `src/main/resources/db/migration/V1__create_tables.sql`（統合baselineへportal 5テーブルのfresh shapeを追記）
  - `src/test/resources/sql/schema-portal-h2.sql`（新規。H2 replay用）
  - `src/test/resources/application-test.yml`（schema-locationsへschema-portal-h2.sql追加）
  - `src/test/resources/sql/engineer-schema-h2.sql`（portal 5テーブル＋`t_bp_payment.received_confirmed_at`を同期）
  - `entity/PortalOrganization.java` / `entity/PortalUser.java` / `entity/PortalInvitation.java` / `entity/PortalUserPermission.java` / `entity/PortalTermsConsent.java`（新規）
  - `mapper/PortalOrganizationMapper.java` / `PortalUserMapper.java` / `PortalInvitationMapper.java` / `PortalUserPermissionMapper.java` / `PortalTermsConsentMapper.java`（新規）
  - `entity/BpPayment.java`（`receivedConfirmedAt`追加）
  - `service/security/ActionPermissionResolver.java`（`portal-admin`登録。V104 menu seedと対）
  - `src/test/java/com/ses/migration/FlywayPortalSchemaSmokeTest.java`（新規。fresh/legacy実MySQL smoke）
  - `src/test/java/com/ses/migration/PortalEntityMapperTest.java`（新規。H2 mapper経路検証）
  - `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java`（S13を予約→REALIZEDへ移行、pattern拡張）
  - `.kiro/specs/external-customer-bp-portal/design.md`（「予約V104」→「S13正式migration V104」）
  - `.kiro/specs/external-customer-bp-portal/tasks.md`（予約→正式migration表記）
- requirements trace: R1.1（組織/user管理）→ 5テーブル / R1.2（期限付き1回token・hash・email/組織/権限）→ t_portal_invitation + CAS / R1.4（TOTP・recovery code・terms・last_login）→ t_portal_user / R3.4（口座変更。受領確認列はR3.2）→ received_confirmed_at
- migration: V104実在（最新=V104 > V103_1）。V59/V72/V82/V99欠番維持。H2 replay・engineer-schema-h2・V1・MySQL smoke 4系統同期
- test evidence:
  - MigrationScriptIntegrityTest 27/0/0/0（静的検査。V1 sync・裸ADD禁止・menu/action解決・権限seed）
  - PortalEntityMapperTest 2/0/0/0（H2。token CAS一回性・email一意・同意UNIQUE・permission・latestConsentedVersion）
  - FlywayPortalSchemaSmokeTest 2/0/0/0（**実MySQL 8.0**。fresh full run＋V103_1→V104 legacy順方向、fresh/legacy shape一致、CHECK/FK/UNIQUE実挙動、CAS 1件のみ成功、seed・menu・action seed確認）
  - SpecDispatchConsistencyTest 9/0/0/0（S13 REALIZED移行後）
  - BpPaymentWritePathTest 1/0/0/0（t_bp_payment直接consumer回帰）
  - `git diff --check` exit 0
- Demo: F1の招待→登録→MFA設定→規約同意のUI Demoは**CONDITIONAL gate**として管理 — T083（F2 security chain/login実装）完了時に実行する（owner: 主実装、期限: T083完了時）。token再利用拒否はmapper CASテストで検証済み
- skipped/unverified: なし（Docker利用可能のためsmoke実行済み）
- known issue IDs: なし（下記の既存REDを除く）
- out-of-scope changes: なし
- rollback: V104適用前形状へは `DELETE FROM flyway_schema_history WHERE version='104';` 後のportalテーブルDROP＋`received_confirmed_at`列DROP＋seed/menu削除で原状復帰（データ破壊なし）
- requested verdict: intermediate（T082完了確認）

### 既存REDの確認（T082起因ではない）

- `com.ses.mapper.BpPaymentMapperTest` が単独実行で失敗する（work_record_id=9999に対するFK違反。replay schemaのV5 FKがwork_record_idを持つため）。
- temp worktree（HEAD 009b6965、クリーン状態）でも同一失敗を再現 — **T082前から存在する既存RED**。
- 全量実行時の順序依存でgreenになる可能性があるため、T087（M）のL4で全体の中の挙動を再確認し、REDなら他specのバックログとしてledgerへ分離する（本specのM PASS条件には含めない判断を記録する）。

## 証跡（task別）

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T081 | 前提(G3/G8), R1.3, R2.1, R3.2, R3.3, R4.3 | field-inventory.md, review-ledger.md, decision-log.md | L0 | matrix/threat modelレビュー | fc5ec63e | なし |
| T082 | R1.1, R1.2, R1.4, R2.1〜R3.4, R4.4 | V104, V1, schema-portal-h2, application-test.yml, engineer-schema-h2, entity×5, mapper×5, BpPayment, ActionPermissionResolver, smoke/mapper test, SpecDispatchConsistencyTest, design/tasks表記 | L1〜L3: 39/0/0/0（integrity 27 + mapper 2 + smoke 2 + spec-dispatch 9 + BpPayment回帰 1。実MySQL含む） | token CAS一回性はmapper testで検証。UI DemoはT083完了時（CONDITIONAL gate） | （T082 commit） | 既存RED（BpPaymentMapperTest）はHEAD再現で分離確認済み |

## 未検証事項（本番release gateとして継続管理）

- 利用規約の外部法務承認（G3、本specのM PASS条件外。本番release gate）
- `portal.<base-domain>` の実DNS/証明書/配備（本番release gate）
- 実browserでのdesktop/390px Demo（T087で実施）
- F1の招待→登録→MFA設定→規約同意UI Demo（T083完了時に実施）
