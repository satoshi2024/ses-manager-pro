# Design — 注文・注文請・月次検収

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL & マイグレーション戦略

- `t_sales_order(id, tenant_id, legal_entity_id, order_no, customer_po_no, customer_id, contact_id, quotation_id, order_date, start/end_date, status, total_amount_snapshot, payment_terms_snapshot, source_document_id, acknowledgement_document_id, version)`。
- `t_sales_order_line(order_id, line_no, project_id, engineer_id, quantity, unit_price, settlement_min/max, amount, remarks)`。
- `t_contract.order_line_id`（UNIQUEで1明細→1契約、将来複数契約更新はrenewed chainで表現）。
- `t_contract.acceptance_required` (`NOT NULL DEFAULT 1`) および `acceptance_exemption_reason` (`VARCHAR(500)` nullable)。
- `t_acceptance(id, contract_id, work_record_id, work_month, status, submitted_at, customer_contact_id, accepted_at, reject_comment, document_id, version)`、`UNIQUE(contract_id, work_month)`。

### 1.1 マイグレーション方針 (R10-P0-01, R9-P1-02)
- **V80原状復元**: 公開済み `V80__order_acceptance_workflow.sql` のファイルを Base バイト単位で完全に原状復元し、Flyway checksum mismatch を防止する。
- **新規順方向マイグレーション `V81__order_acceptance_remediation.sql`**:
  1. **新規契約の決定論的分類**: V81 は V80 成功後にのみ実行されるため、V81 側で過去の不可信な失敗中契約を自動キャプチャすることは行わない。可信なマーカーが存在しない早期失敗（Path 3）の場合、独立スクリプト `scripts/v80-pre-repair.ps1` および手順書 `.kiro/runbooks/v80-pre-repair-runbook.md` により事前検証・境界確定を行い、境界を証明できない契約は全て `acceptance_required = 1` に維持する。失敗期間中に挿入された契約が誤って免除に化けないことをテストで断言する。
  2. **INDEX/FK 3-Way 構造修復**: インデックス `uk_contract_order_line` および FK `fk_contract_order_line` の全構成列・順序・prefix・参照先テーブル/列・ON UPDATE/DELETE 規則まで厳格検査し、missing / wrong-shape / correct の三分岐で制御する。
  3. **既存データ Preflight & DB CHECK 制約 (R9-P1-06)**:
     `acceptance_required = 0` かつ理由が NULL/空白の既存汚染データが存在する場合、理由の自動捏造を行わず `acceptance_required = 1` へ安全に復元する preflight クレンジングを実行した上で、以下の DB CHECK を追加する:
     `ALTER TABLE t_contract ADD CONSTRAINT chk_contract_acceptance_exemption CHECK (acceptance_required = 1 OR (acceptance_exemption_reason IS NOT NULL AND TRIM(acceptance_exemption_reason) != ''));`
  4. **文書アトミック Claim テーブル & 既存データバックフィル (R9-P1-09)**:
     `CREATE TABLE t_document_hash_claim (tenant_id VARCHAR(100) NOT NULL, document_type VARCHAR(50) NOT NULL, sha256 VARCHAR(64) NOT NULL, document_id BIGINT NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (tenant_id, document_type, sha256), CONSTRAINT fk_document_hash_claim_document FOREIGN KEY (document_id) REFERENCES t_document(id) ON DELETE CASCADE)` を作成（`tenant_id` は `t_document.tenant_id` と同じ `VARCHAR(100)`）。
     既存 `t_document` × `t_document_version`（全非削除 `deleted_flag = 0` バージョン）から `SELECT DISTINCT` でバックフィル。回填前に同一 `tenant_id + document_type + sha256` が複数 `document_id` に跨がる既存重複を検出した場合は preflight fail とする。
- **4つのスキーマ定義ファイルの完全同期**:
  1. マイグレーションスクリプト: `src/main/resources/db/migration/V81__order_acceptance_remediation.sql`
  2. 統合ベースライン: `src/main/resources/db/migration/V1__create_tables.sql`
  3. メインテスト用 H2 スキーマ: `src/test/resources/sql/engineer-schema-h2.sql`
  4. 注文検収専用 H2 スキーマ: `src/test/resources/sql/schema-order-acceptance-h2.sql`

---

## 2. Service & Concurrency Control

- **HRロール厳格判定 (R9-P0-01)**:
  `SecurityUtils.isHrRole()` は `StatusConstants.ROLE_HR` (`"HR"`) との一致のみを判定する（UI表示名 `"人事"` での判定は禁止）。
  `DocumentServiceImpl`, `AcceptanceServiceImpl`, `MonthlyClosingServiceImpl`, `DashboardServiceImpl`, `DataScopeServiceImpl` の全パスで適用。
- **マルチ月間 ACCEPTANCE アーカイブ Scope 複合条件述語 (R9-P0-01)**:
  単一の平坦な `WHERE contract_id IN (...)` 述語は「契約 × 月」の対応関係を失い越権閲覧を招くため、検索対象の各 `work_month` ごとに Java 側で `allowedContractIdsAsOf(monthEnd(workMonth))` を取得し、**SQL の WHERE 句で `(a.work_month = 'YYYY-MM' AND a.contract_id IN (...)) OR ...` の複合論理積述語を構成**（または月別に事前抽出した許可 `document_id` 集合を `WHERE d.id IN (...)` で指定）してページネーション前にフィルタする。list と count は全く同一の SQL 述語を共有する。
- **submit 対 reopenMonth 3段階行ロック順序 & 状態ガード (R9-P1-03)**:
  `reopenMonth` は以下の**厳格な順序**で行ロックを獲得する:
  - Step 1: `Contract` `FOR UPDATE`
  - Step 2: `WorkRecord` `FOR UPDATE`
  - Step 3: `Acceptance` `FOR UPDATE`
  `Acceptance` の状態が `提出済` または `検収済` の場合は `BusinessException(409, "error.acceptance.alreadySubmittedOrAccepted")` を返却して拒否する（`差戻し` または未提出のみ再オープン可能）。メッセージキーは全4言語 bundle へ登録し一致性をテストする。
- **明細契約化 retry (R9-P2-01)**:
  `ContractServiceImpl.buildAndSaveDraft` で `DataIntegrityViolationException` 発生時、`baseMapper.selectOneForUpdate(orderLineId)` による current read で勝者トランザクションのコミット行を確実に取得し冪等返却する。
- **アトミック Document Hash Claim & トランザクション同期補償 (R9-P1-09)**:
  処理順序: (1) `t_document` 作成 -> (2) `t_document_hash_claim` へ挿入 -> (3) `DocumentStorage` へ物理保存 -> (4) `t_document_version` 作成・リンク。
  Claim 重複時は `409 Conflict` (`error.order.duplicateSourceDocument`) を返却。
  ファイル物理保存時に `TransactionSynchronizationManager.registerSynchronization` にて `afterCompletion(ROLLED_BACK)` フックを登録し、DBコミット失敗時や例外ロールバック時に `DocumentStorage` 上の物理ファイルを確実に自動補償削除する。

---

## 3. Document, Legal Entity & PDF

- **法人バインド & 代表法人 `WHERE legal_entity_id = ?` 解決 (R9-P1-07)**:
  - UI候補取得は `/api/autocomplete/legal-entities` へ接続。
  - 保存/更新時に `legal_entity_id` の存在およびアクセス権限を検証。
  - PDF 描画時: `OrganizationUnitMapper` で `WHERE tenant_id = ? AND legal_entity_id = ? AND deleted_flag = 0 ORDER BY parent_id IS NULL DESC, id ASC` により代表組織ユニットを検索し、社名・住所・インボイス登録番号を解決する。該当法人または組織が存在しない場合はサイレントフォールバックせず `BusinessException(400)` で即時拒否する。
  - テストフィクスチャにて `legal_entity_id != organization_unit.id` (例: `legal_entity_id = 500L`, `organization_unit.id = 501L`) の構成を用意し解決を検証する。
- **注文請PDF再発行のアーカイブ正本一貫性 (R10-P1-01)**:
  - 状態が `ORDER_ACK_SUBMITTED` 以降の再発行時は、動的再生成を行わず `DocumentStorage` からアーカイブ済みの初回 PDF バイト配列を直接返却する。
  - 状態が推進済かつアーカイブ不在時は fail-closed (`BusinessException(500)`)。
  - テストにて HTTP 返却 SHA-256 とアーカイブ SHA-256 の一致を検証する。
- **発行 POST / ダウンロード GET の分離 & 権限・監査 (R9-P1-08)**:
  - `POST /api/sales-orders/{id}/acknowledgement-pdf` (発行・状態遷移: CSRF 必須, 権限 `sales-order.edit`)
  - `POST /api/acceptances/{id}/document` (アップロード/書き込み: CSRF 必須, 権限 `file.upload` / `acceptance.edit` — **`file.download` へ誤判定しないことを明示断言**)
  - `GET /api/sales-orders/{id}/documents/{documentId}/download` (ダウンロード: 権限 `file.download`, `ApiAuditFilter` で `FILE_DOWNLOAD` / `FILE_DOWNLOAD_REJECTED` をログ記録)

---

## 4. UI & Accessibility (R9-P2-03)

- 全モーダル・ボタン・動的入力行にアクセシブルネームを付与:
  - モーダル閉じるボタン: `aria-label="閉じる"`
  - アイコンページネーション: `aria-label="前へ" / "次へ"`
  - 動的明細入力: `<input aria-label="単価">`, `<button aria-label="明細削除">`
- キーボード操作 (`Tab`, `Shift+Tab`, `Enter`, `Escape`) および 390px モバイル表示の画面証跡を記録。

---

## 5. 決定表

### 5.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| 注文金額/支払条件 | 見積・契約の現在値 | — | `total_amount_snapshot` / `payment_terms_snapshot` | **注文確定時点** | 未確定（下書き） |
| 検収対象工数 | `t_work_record_daily` | — | `t_acceptance`へwork record `version`＋amount snapshot | **提出時点**。以後の工数変更で検収額を変えない | — |
| 検収の要否 | `t_contract.acceptance_required` | 変更は監査 | — | 請求生成時点 | **NULLを許さない**（default true）。未設定を「不要」にしない |
| work_month | `t_acceptance.work_month` | — | `UNIQUE(contract_id, work_month)` | 対象月 | — |
| 顧客確認者 | `customer_contact_id` | contact側の期間 | 検収時の名称snapshot | 検収実行時点 | 内部代行入力（R3.5） |

### 5.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件 | 全件 | 全て | 未検収/期限batch |
| マネージャー | 組織scope ∩ DataScope | 同左 | 自組織の未検収/差戻し | 同上 |
| 営業 | 既存DataScope（担当顧客/契約）。**組織で追加制限しない** | 同左 | 自担当の注文未受領/検収未提出 | 同上 |
| HR | 不可視 | — | — | — |
| 要員 | 自分が対象の検収状態のみ（金額非表示） | — | — | — |
| portal user (顧客) | **本specでは非公開**（S13で自社分の検収を開放） | — | — | — |
| scheduler principal | 全件 | — | 宛先は担当営業、管理者、対象月時点の自組織マネージャー | 期限超過、未提出 |

### 5.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| order 下書き | →受領確認 / →取消 | 状態CAS | — | 下書きへ |
| 受領確認 | →注文請提出 / →取消 | 状態CAS | — | 受領確認へ |
| 注文請提出 | →契約化 / →取消 | 状態CAS＋`version` | 二重契約化click | 注文請提出へ |
| 契約化 | →完了 / →取消（**承認必須**） | 状態CAS | — | 取消はapproval経由 |
| 完了 / 取消 | 終端 | — | — | 新規注文 |
| acceptance 未提出 | →提出済 | 状態CAS | 二重提出 | 未提出へ |
| 提出済 | →検収済 / →差戻し | 状態CAS＋`version` | 顧客と内部代行の同時操作 | 提出済へ |
| 差戻し | →提出済（再提出） | 状態CAS | — | — |
| 検収済 | →取消（**承認必須**、R3.4） | 状態CAS | 請求生成との競合 | 取消はapproval経由 |

---

## 6. Round 10 Traceability Matrix

| Finding ID | Description | Implementation File | Automated Test | Demo / Evidence |
|---|---|---|---|---|
| `R9-P0-01` | HR Role & Multi-month As-of Document Scope | `SecurityUtils.java`, `DocumentServiceImpl.java`, `AcceptanceServiceImpl.java`, `MonthlyClosingServiceImpl.java`, `DashboardServiceImpl.java` | `DocumentServiceImplTest`, `AcceptanceAsOfScopeTest`, `MonthlyClosingUnacceptedTest`, `DashboardServiceImplTest` | Desktop/Mobile HR access returning 0/403 across archive list/detail/download, closing, and KPIs |
| `R10-P0-01` | V80 Byte Pinning & V81 Forward Migration | `V80__order_acceptance_workflow.sql` (restored), `V81__order_acceptance_remediation.sql` (new) | `FlywayV80PinnedChecksumTest`, `FlywayV81RepairSmokeTest`, `FlywayMigrationSmokeTest` | Clean V1->V81 fresh DB, true V79.1 legacy DB, V80-succeeded DB, and pre/post-marker partial failure recovery |
| `R9-P1-01` | Index & FK 3-Way Structural Repair | `V81__order_acceptance_remediation.sql` | `FlywayV81RepairSmokeTest` | Real MySQL repair test for wrong-shape composite index and wrong child/referenced column FK |
| `R9-P1-02` | Migration Durability & Recovery Runbook | `V81__order_acceptance_remediation.sql` | `FlywayV81RepairSmokeTest` | MySQL pre-marker partial failure simulation, inserting contract during failure, and verifying fail-closed recovery |
| `R9-P1-03` | WorkRecord Reopen vs Submit FOR UPDATE Lock | `WorkRecordServiceImpl.java`, `AcceptanceServiceImpl.java` | `ConcurrentSubmitReopenTest`, `WorkRecordServiceImplTest` | Real MySQL 2-transaction test proving submit/reopen lock order (`Contract`->`WorkRecord`->`Acceptance`) and rejection of submitted/accepted records |
| `R9-P1-06` | DB CHECK Constraint for Exemption Reason | `V81__order_acceptance_remediation.sql`, `V1__create_tables.sql`, `engineer-schema-h2.sql`, `schema-order-acceptance-h2.sql` | `ContractAcceptanceExemptionTest`, `OrderAcceptanceSchemaTest` | DB CHECK rejection when `acceptance_required=0` and `acceptance_exemption_reason` is NULL/blank |
| `R9-P1-07` | Legal Entity Autocomplete API & Dynamic Resolution | `sales-order.js`, `SalesOrderServiceImpl.java`, `SalesOrderPdfServiceImpl.java` | `SalesOrderApiControllerTest`, `SalesOrderPdfServiceImplTest` | UI legal entity option loading via `/api/autocomplete/legal-entities`, resolution by `legal_entity_id`, and PDF print verification |
| `R9-P1-08` | POST/GET Route Separation & Audit Logging | `SalesOrderApiController.java`, `AcceptanceApiController.java`, `ActionPermissionResolver.java`, `ApiAuditFilter.java` | `SalesOrderApiControllerTest`, `ActionPermissionResolverTest`, `ApiAuditFilterTest` | POST PDF generation state-change, GET download permission mapping (`file.download`), and `FILE_DOWNLOAD` audit logging |
| `R9-P1-09` | Atomic Document Hash Claim Table | `V81__order_acceptance_remediation.sql`, `DocumentServiceImpl.java`, `t_document_hash_claim` | `DocumentHashClaimTest`, `DocumentServiceImplTest` | Real MySQL 2-transaction atomic claim test, 409 Conflict mapping, transaction rollback, and storage orphan cleanup |
| `R9-P1-10` | Real MySQL Browser Demo & L4 Zero-Skip | `RealBrowserScreenshotTest.java`, `.kiro/specs/order-acceptance-workflow/evidence/` | `verify-like-ci.ps1` (1551 tests PASS) | Real MySQL browser closed-loop evidence (Quotation->Order->PO->Ack PDF->Contract->WorkRecord->Acceptance->Invoice) with HAR/console/PNG & `git diff --check` PASS |
| `R10-P1-01` | PDF Re-issuance Archive Byte Consistency | `SalesOrderServiceImpl.java`, `SalesOrderPdfServiceImpl.java` | `SalesOrderPdfServiceImplTest` | Re-issuance SHA-256 comparison between HTTP response bytes and archived PDF bytes |
| `R9-P2-01` | Contractization Retry FOR UPDATE Read | `ContractServiceImpl.java` | `ConcurrentContractizationTest`, `ContractServiceImplTest` | Real MySQL 2-transaction test proving duplicate `orderLineId` contractization returns existing contract via `FOR UPDATE` current read |
| `R9-P2-03` | UI Accessibility & Keyboard Demo | `sales-order/list.html`, `acceptance/list.html`, `sales-order.js`, `acceptance.js` | `MobileResponsiveLayoutTest`, `JsSyntaxCheckTest` | Keyboard navigation (Tab/Shift+Tab/Enter/Escape) and 390px mobile viewport demo |
| `R9-P2-04` | Notification DB Dedupe & KPI Direct Assertions | `NotificationGenerateService.java`, `DashboardServiceImpl.java` | `NotificationGenerateServiceTest`, `DashboardServiceImplTest` | Asserting exact DB row counts after 2x notification runs and dashboard KPI direct assertions (0/1, boundary date, HR=0) |
