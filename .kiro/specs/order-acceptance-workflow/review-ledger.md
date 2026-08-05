# Review Ledger — order-acceptance-workflow (S09)

本ledgerは `review-ledger-template.md` v2.0に従い、T054〜T059の実装証跡をappend-onlyで記録する。
現行判定は本ファイル先頭の「現行判定」表が唯一の正。

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | order-acceptance-workflow |
| handbook | v2.0 |
| state | IN PROGRESS |
| base | f523e11（main / origin/main 一致） |
| head | （T054 commit後に更新） |
| merge | unmerged |
| latest review | —（未開始） |
| verdict | — |
| issue count | — |
| next action | T055 F2 見積→注文→契約 |

## 2. OPEN Issue Register

（現時点なし）

## 3. Closed/Deferred Issue

（なし）

## 4. 最新Review Packet

（Review開始時に記入。T059 M完了後に確定）

## 5. Requirements Trace

| requirement/AC | implementation | automatic test | Demo | verdict |
|---|---|---|---|---|
| R1.1〜R1.5 注文/状態機械 | T054〜T056 | OrderAcceptanceSchemaTest / SalesOrderServiceImplTest | T056で実施 | 実装中 |
| R2.1〜R2.4 見積→注文→契約 | T055 | （T055で追加） | T055で実施 | 実装中 |
| R3.1〜R3.5 月次検収 | T054/T057 | OrderAcceptanceSchemaTest | T057で実施 | 実装中 |
| R4.1〜R4.3 通知/KPI | T058 | （T058で追加） | T058で実施 | 実装中 |
| R5 受入 | T054〜T059 | 各task | T059で実施 | 実装中 |

## 6. T054 F1 注文/明細/検収DDL — 記録（2026-08-05）

- **task**: T054 F1
- **requirements**: R1.1〜R1.5（DDL部分）、R3.1、R5（UNIQUE/NOT NULL）
- **変更file**:
  - `src/main/resources/db/migration/V80__order_acceptance_workflow.sql`（新規）
  - `src/main/resources/db/migration/V1__create_tables.sql`（baseline同期）
  - `src/test/resources/sql/schema-order-acceptance-h2.sql`（新規・H2 replay）
  - `src/test/resources/application-test.yml`（schema-locations追加）
  - `src/test/resources/sql/engineer-schema-h2.sql`（t_contract列・新テーブル同期）
  - `src/main/java/com/ses/entity/{SalesOrder,SalesOrderLine,Acceptance}.java`（新規）
  - `src/main/java/com/ses/entity/Contract.java`（orderLineId / acceptanceRequired）
  - `src/main/java/com/ses/mapper/{SalesOrderMapper,SalesOrderLineMapper,AcceptanceMapper}.java`（新規）
  - `src/main/java/com/ses/common/constant/StatusConstants.java`（注文/検収状態）
  - `src/main/java/com/ses/service/SalesOrderService.java` + `impl/SalesOrderServiceImpl.java`（採番・状態機械）
  - `src/main/java/com/ses/service/security/ActionPermissionResolver.java`（sales-orders/acceptances）
  - `src/main/java/com/ses/service/ContractService.java` + `impl/ContractServiceImpl.java`（orderLineId引継ぎ）
  - `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java`（V80 assert）
  - `src/test/java/com/ses/order/{OrderAcceptanceSchemaTest,SalesOrderServiceImplTest}.java`（新規）
- **DDL/H2/MySQL同期**: V1統合baseline + V80増分（information_schema guard付きADD）+ H2 `schema-order-acceptance-h2.sql` + `engineer-schema-h2.sql` + MySQL smoke assert を同一taskで同期。
- **test**: `OrderAcceptanceSchemaTest` 5/0/0、`SalesOrderServiceImplTest` 5/0/0、`MigrationScriptIntegrityTest` 26/0/0。直接回帰（L3）: ActionPermissionResolverTest/MessageBundleConsistencyTest/NotificationLinkRouteTest/MobileResponsiveLayoutTest/MenuPermissionFilterTest/RoleNavigationVisibilityTest/GlobalControllerAdvicePermissionTest/CsrfProtectionTest 55/0/0。Docker必須のFlyway実MySQL smokeはCI/Mで実行（ローカル自動skip）。
- **Demo**: 未実施（A1画面実装後のT056で実施）。F1の状態遷移・UNIQUE・NOT NULLは自動testで検証済み。
- **commit**: （T054 commit hashを記入）
- **risk/備考**:
  - t_acceptance の「work record version」は t_work_record にversion列が無いため、`work_record_updated_at`（DATETIME snapshot）で実装（design §5.1の意図を充足。後続B1で差戻し→再提出時に再snapshot）。
  - V1はfresh DBで最初に実行されるため、V1の新テーブルFKはV1内テーブル(m_customer/t_contract)のみ。V73以降のテーブル(t_customer_contact/t_document/t_quotation)へのFKはV80側にのみ定義（fresh/legacyで形状が僅かに非対称だがUNIQUE制約は両経路で同一。SmokeTestは列/索引でassert）。
