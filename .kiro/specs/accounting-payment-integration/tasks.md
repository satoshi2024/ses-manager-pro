# Implementation Plan — 会計・支払連携 (accounting-payment-integration / S15)

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T094〜T100はL0〜L3の定向test・直接回帰、T101でL4全量を実行する。
> provider error matrixは対象adapter単位、全量/sandbox障害訓練はM/release gateへ集約する。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md`（特に§7 外部連携）を実装前に読む。
> 時間/scope/状態/error分類の判断は `design.md` §6「決定表」を正とする。
>
> **Migration**: 本specの正式migrationは **V106**（consolidated baseline V1反映済み）および **V107**（forward repair）。
> V59は永久欠番。

## 1. タスク一覧

- [x] 0. G4/API spike/canonical mapping
  - **Objective**: freeeの契約plan・利用可能API・rate limitが確認され、全10種別の正規識別子（ID/tax_code）およびマッピングが確定する。
  - **成果物**: `canonical-mapping.md`, `decision-log.md` (S15-G4-MAPPING-01), allow-list snapshot 仕様。
  - **テスト要件**: L0。mapping表の全10 object typeに確認状態が付いていること、未確認項目が本番送信blockerとして列挙されていること、`git diff --check` exit 0。

- [x] F1. connection/mapping/job DDL・soft-delete一意性・Multi-node Token CAS
  - **Objective**: `legal_entity_key` と `active_slot` により NULL 一意性および soft-delete 後の再作成を保証。`token_version` と DB 行ロックによる multi-node 401 トークンリフレッシュ直列化。`lease_token` (UUID) によるジョブ排他制御。
  - **実装ガイダンス**: `V1__create_tables.sql`, `V107__accounting_integration_snapshot_and_slot.sql`, `engineer-schema-h2.sql`, `schema-accounting-integration-h2.sql`。
    4 migration 経路（fresh V1, legacy V106→V107, H2, MySQL）の検証。
  - **テスト要件**: L1〜L3。connection unique (NULL 重複拒否, soft-delete 再作成), `token_version` multi-node CAS（独立 transaction / 独立 service instance での 1 回リフレッシュ確認）, lease claim, 既存 connection 移行後の回帰。
  - **Demo**: 2ノード同時 401 発生時に 1 回のみトークン更新され双方が最新トークンを安全に取得することを確認。

- [x] F2. AccountingProvider/freee/CSV・全10マスタ検証・PII遮断
  - **Objective**: 全10マスタ種別の正規識別子照合・fail-closed検証・canonical snapshot保存。タイムアウト未知結果の全件 pagination 照合。生レスポンス・生例外の完全遮断（定型エラーコード化）。
  - **実装ガイダンス**: `FreeeAccountingProvider.java`, `CsvAccountingExportProvider.java`, `ExternalMappingServiceImpl.java`。
    エラー分類は `design.md` 決定表に従い、PII をログ・Job 詳細へ漏らさない。
  - **テスト要件**: L2〜L3。全10種別検証、未知種別 404/false、一覧 200 だが ID 不存在時 false、50 ページ pagination 照合、PII 遮断ログキャプチャテスト。
  - **Demo**: WireMock で全ステータスコードを返し、生レスポンスがログ・Job 詳細に一切出力されないことを確認。

- [x] A1. mapping/preview/job管理UI・SQLデータスコープ・4言語i18n
  - **Objective**: 組織データスコープを SQL 境界で適用（マネージャーは自組織のみ、空集合は DB 側 0 件）。4言語 (ja/en/zh/ko) による完全国際化（単一翻訳源 `messages*.properties`）。
  - **実装ガイダンス**: `AccountingIntegrationApiController.java`, `accounting-integration.js`, `messages*.properties`。
    トークン暗号文は全ロールに対し非表示。
  - **テスト要件**: L2〜L3。マネージャー他組織遮断、空組織 DB 0 件、4言語 API / UI 表示検証。
  - **Demo**: マネージャーでログイン時に自組織以外のジョブが完全に不可視（0件）であることを確認。

- [x] B1. 売上/取消連携・Payload Snapshot・In-flight 取消補償
  - **Objective**: `payload_snapshot`（不変バイト列）による売上連携。取消 enqueue 時の `externalDealId`, `cancelReasonCode` 固定。HTTP in-flight 取消時の `CANCELLED_EXTERNALLY_CREATED` 検知と自動補償 `SALES_INVOICE_CANCEL` ジョブ enqueue。
  - **実装ガイダンス**: `SalesInvoiceIntegrationServiceImpl.java`。
  - **テスト要件**: L2〜L3。10回同時実行冪等性、金額不一致時 FAILED、in-flight 取消補償、締め済み月への更新拒否。
  - **Demo**: 取消 enqueue 後に別 deal で再 sync されても、元の deal のみが取り消されることを確認。

- [x] B2. BP/経費/支払連携・業務日付固定・厳格照合
  - **Objective**: BP Canonical の業務日付（`work_month` 末日）固定による翌日再実行ハッシュ不変。支払同期時の金額・日付双方非 NULL 厳格一致。経費同期の snapshot ハッシュ検証と `ExpenseRequest` CAS 更新 (`承認済` -> `会計連携済`)。
  - **実装ガイダンス**: `PurchaseExpensePaymentIntegrationServiceImpl.java`。
  - **テスト要件**: L2〜L3。翌日 retry 成功、NULL 金額/日付の拒否、経費改ざん拒否、経費 CAS 競合時の FAILED。
  - **Demo**: BP 支払作成→翌日 retry でも PAYLOAD_MUTATED にならず正常連携されることを確認。

- [x] B3. 月次照合/closing・4母集団・Pagination・Fail-Closed
  - **Objective**: 売上・仕入・入金・経費の 4 母集団完全照合。freee 取引一覧全件 pagination 取得。未接続・トークンなし・API 障害・50 ページ上限到達時の fail-closed (`readyForClosing=false`)。SUCCEEDED ジョブの実金額突合。
  - **実装ガイダンス**: `AccountingReconciliationServiceImpl.java`。
  - **テスト要件**: L2〜L3。4母集団照合、no-token fail-closed、50 ページ到達 fail-closed、同 dealId 異金額検知。
  - **Demo**: 外部取引で直接金額が変更された場合に `AMOUNT_MISMATCH` が検知され月次締めが阻止されることを確認。

- [x] M. 回帰/障害訓練・CI Gate 統合検証
  - **Objective**: 会計・支払連携の全体回帰テストと障害耐性・セキュリティ・エラーハンドリング・マイグレーション確認。
  - **テスト要件**: L4全量回帰 (`mvn test` 2,381+ PASS, 0 fail, 0 error, 0 skip)、`scripts/verify-like-ci.ps1`（Fast, MySQL Shards 1-3, Performance, Backup Gate）全通過。
  - **Demo**: 全 Gate グリーン、review-ledger.md 完全記録。
