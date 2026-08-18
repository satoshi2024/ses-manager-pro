# Implementation Plan — 会計・支払連携 (accounting-payment-integration / S15)

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）:
> - 通常Task（R4-T01〜R4-T07）: L0〜L3の定向test・直接回帰。
> - M Task（R4-T08）: L4全量回帰 (`mvn test`) および `scripts/verify-like-ci.ps1`（Fast, MySQL Shards 1-3, Performance, Backup Gate）。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md`（特に§7 外部連携）を実装前に読む。
> 時間/scope/状態/error分類の判断は `design.md` §6「決定表」を正とする。
>
> **Migration**: 本specの正式migrationは **V106**（consolidated baseline V1反映済み）および **V106.1** (`V106_1__accounting_integration_snapshot_and_slot.sql` による forward repair）。
> `V107` は S16 (`jp-pint-digital-invoice`) 予約済みのため使用しない。V59は永久欠番。

---

## 1. 歴史的タスク（初期実装ベースライン）

- [x] 0. G4/API spike/canonical mapping (T094)
- [x] F1. connection/mapping/job DDLと既存connection移行 (T095)
- [x] F2. AccountingProvider/freee/CSV (T096)
- [x] A1. mapping/preview/job管理UI (T097)
- [x] B1. 売上/取消連携 (T098)
- [x] B2. BP/経費/支払連携 (T099)
- [x] B3. 月次照合/closing (T100)
- [x] M. 回帰/障害訓練 (T101)

---

## 2. R4 是正タスク（Stage B 実装・検証対象）

- [ ] R4-T01. V106.1 Migration, Soft-Delete UNIQUE & 3段階リース・CAS DDL
  - **Objective**: S16予約衝突を回避した `V106.1` forward migration。`legal_entity_key` と `active_slot` による NULL 一意性および soft-delete 後の再作成保証。`token_version`, `refresh_lease_token`, `refresh_lease_expires_at`, `payload_snapshot`, `lease_token`, `lease_expires_at`, `tenant_id`, `legal_entity_id`, `organization_id` の完全網羅。重複 connection の事前退避・論理削除・ロールバック手順の確立。
  - **実装ガイダンス**: `V106_1__accounting_integration_snapshot_and_slot.sql`, `V1__create_tables.sql`, `engineer-schema-h2.sql`, `schema-accounting-integration-h2.sql`。
    4 migration 経路（fresh V1, legacy V106→V106.1, H2, MySQL）の検証。
  - **テスト要件**: L1〜L3。connection unique (NULL 重複拒否, soft-delete 再作成), 既存 connection 移行後の回帰。

- [ ] R4-T02. Multi-node 3段階リース Token CAS & タイムアウト未知結果 Pagination
  - **Objective**: DB トランザクション外で HTTP を呼ぶ 3段階リース・CAS 状態機械による 401 トークンリフレッシュ直列化。タイムアウト未知結果の全件 pagination (最大50ページ) 照合。
  - **実装ガイダンス**: `IntegrationConnectionServiceImpl.java`, `FreeeAccountingProvider.java`。
  - **テスト要件**: L2〜L3。独立 service instance・独立 transaction・MySQL 上で同時 401 発生時に 1 回のみ外部更新され他ノードが新トークンを再利用することの検証。

- [ ] R4-T03. 外部マスタ 10種別 実在検証・数値 Tax Code & PII 完全遮断
  - **Objective**: 全10マスタ種別の正規識別子照合（税区分は数値 `tax_code` e.g. 34/21、取引先/勘定科目/部門は数値 `id`）。未知種別 fail-closed 検証。allow-list canonical snapshot 保存。生レスポンス・生例外の完全遮断（定型エラーコード化）。
  - **実装ガイダンス**: `FreeeAccountingProvider.java`, `ExternalMappingServiceImpl.java`。
  - **テスト要件**: L2〜L3。全10種別検証、未知種別 404/false、一覧 200 だが ID 不存在時 false、PII 遮断ログキャプチャテスト。

- [ ] R4-T04. SQL Scope 境界制御 (全 Consumer 対応) & 4言語 i18n
  - **Objective**: Consumer inventory 全機能（list/detail/count/export/preview/reconciliation/notification/retry/cancel）に SQL スコープを適用。マネージャーは自組織のみ、空組織集合時は DB レベルで 0 件返却。単一翻訳源 `messages*.properties` による 4言語 (ja/en/zh/ko) 完全国際化。
  - **実装ガイダンス**: `AccountingIntegrationApiController.java`, `accounting-integration.js`, `messages*.properties`。
  - **テスト要件**: L2〜L3。マネージャー他組織遮断、空組織 DB 0 件、4言語 API / UI 表示検証。

- [ ] R4-T05. 売上連携 Payload Snapshot & 原子補償ジョブ Enqueue
  - **Objective**: `payload_snapshot`（不変バイト列）による売上連携。取消 enqueue 時の `externalDealId`, `cancelReasonCode` 固定。HTTP in-flight 取消時の `CANCELLED_EXTERNALLY_CREATED` 検知と同一 Tx での補償 `SALES_INVOICE_CANCEL` ジョブ enqueue。BP・経費の RUNNING 取消拒否 (400)。
  - **実装ガイダンス**: `SalesInvoiceIntegrationServiceImpl.java`, `AccountingIntegrationWorker.java`。
  - **テスト要件**: L2〜L3。10回同時実行冪等性、金額不一致時 FAILED、in-flight 取消原子補償、締め済み月への更新拒否。

- [ ] R4-T06. BP / 経費 業務日付固定・厳格照合 & 経費 CAS
  - **Objective**: BP Canonical の業務日付（`work_month` 末日 JST）固定による翌日再実行ハッシュ不変。支払同期時の金額・日付双方非 NULL 厳格一致。経費同期の snapshot ハッシュ検証と `ExpenseRequest` CAS 更新 (`承認済` -> `会計連携済`)。
  - **実装ガイダンス**: `PurchaseExpensePaymentIntegrationServiceImpl.java`。
  - **テスト要件**: L2〜L3。翌日 retry 成功、NULL 金額/日付の拒否、経費改ざん拒否、経費 CAS 競合時の FAILED。

- [ ] R4-T07. 月次照合 4母集団・Pagination & Fail-Closed
  - **Objective**: 売上・仕入・入金・経費の 4 母集団完全照合。入金の `{externalDealId}:{paymentId}` 照合。freee 取引一覧全件 pagination 取得。未接続・トークンなし・API 障害・50 ページ上限到達時の fail-closed (`readyForClosing=false`)。SUCCEEDED ジョブの実金額突合。
  - **実装ガイダンス**: `AccountingReconciliationServiceImpl.java`。
  - **テスト要件**: L2〜L3。4母集団照合、同日複数入金 1:1 突合、no-token fail-closed、50 ページ到達 fail-closed、同 dealId 異金額検知。

- [ ] R4-T08. S15 総合回帰 & CI 4Gate 完全検証 (M Gate)
  - **Objective**: 全体回帰テストと障害耐性・セキュリティ・エラーハンドリング・マイグレーション確認。
  - **テスト要件**: L4全量回帰 (`mvn test` 2,381+ PASS, 0 fail, 0 error, 0 skip)、`scripts/verify-like-ci.ps1`（Fast Suite, MySQL Shards 1-3, Performance Suite, Backup Gate）全通過。
