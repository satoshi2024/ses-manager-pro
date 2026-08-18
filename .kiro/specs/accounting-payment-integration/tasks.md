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

- [ ] R4-T01. V106.1 Migration 5形状契約、Soft-Delete UNIQUE & 3段階リース DDL
  - **Objective**: S16予約衝突を回避した `V106.1` forward migration。`legal_entity_key` と `active_slot` による NULL 一意性および soft-delete 後の再作成保証。`token_version`, `refresh_lease_token`, `refresh_lease_expires_at`, `payload_snapshot`, `lease_token`, `lease_expires_at`, `tenant_id`, `legal_entity_id`, `organization_id` の完全網羅。重複 connection の事前退避 (`m_integration_connection_backup_v106_1`)・論理削除・ロールバック手順の確立。
  - **実装ガイダンス**: `V106_1__accounting_integration_snapshot_and_slot.sql`, `V1__create_tables.sql`, `engineer-schema-h2.sql`, `schema-accounting-integration-h2.sql`。
    5形状（fresh V1, legacy V106→V106.1, partial, backfill, repair）およびロールバック手順の検証。
  - **テスト要件**: L1〜L3。connection unique (NULL 重複拒否, soft-delete 再作成), 既存 connection 移行後の回帰, 重複 connection 退避とロールバック復元検証。
  - **Demo**: (1) NULL法人接続の重複登録が拒否されること、(2) 論理削除後に同一キーで新規登録が成功すること、(3) 重複データを含むDBでV106.1適用時に有効レコードが正しく選定され退避テーブルに保存されることをMySQL上で実演確認。

- [ ] R4-T02. Multi-node 3段階リース・Fencing Token CAS & タイムアウト未知結果 Pagination
  - **Objective**: DB トランザクション外で HTTP を呼ぶ 3段階リース・CAS 状態機械による 401 トークンリフレッシュ直列化。45秒リースと10秒HTTPタイムアウトによるフェンシング。タイムアウト未知結果の全件 pagination (最大50ページ) 照合。
  - **実装ガイダンス**: `IntegrationConnectionServiceImpl.java`, `FreeeAccountingProvider.java`。
  - **テスト要件**: L2〜L3。独立 service instance・独立 transaction・MySQL 上で同時 401 発生時に 1 回のみ外部更新され他ノードが新トークンを再利用することの検証。HTTP 31秒遅延時の CAS 敗北と新トークン安全再利用。
  - **Demo**: 2並行ノードで同時401を発生させ、1ノードのみがOAuthエンドポイントを呼出して更新し、もう一方のノードが待機後に最新トークンを安全に共有利用することをログで実演確認。

- [ ] R4-T03. 外部マスタ 10種別 実在検証・数値 Tax Code & PII 完全遮断
  - **Objective**: 全10マスタ種別の正規識別子照合（税区分は数値 `tax_code: 34`/`21`、取引先/勘定科目/部門は数値 `id`）。未知種別 fail-closed 検証。allow-list canonical snapshot 保存。生レスポンス・生例外の完全遮断（定型エラーコード化）。
  - **実装ガイダンス**: `FreeeAccountingProvider.java`, `ExternalMappingServiceImpl.java`。
  - **テスト要件**: L2〜L3。全10種別検証、未知種別 404/false、一覧 200 だが ID 不存在時 false、PII 遮断ログキャプチャテスト。
  - **Demo**: 10種別のマッピング検証を実行し、不正な税コードや未知種別が即座に拒否され、例外発生時にもログおよびJob詳細に生JSONや個人情報が出力されないことを実演確認。

- [ ] R4-T04. SQL Scope 境界制御 (実エンティティ導出 & 全 Consumer 対応) & 4言語 i18n
  - **Objective**: 実在エンティティ（原価部門 `m_cost_center.organization_id` または契約/要員所属 `t_user_organization`）に基づく組織導出。Consumer inventory 全機能（list/detail/count/export/preview/reconciliation/notification/retry/cancel）に SQL スコープを適用。マネージャーは自組織のみ、空組織集合時は DB レベルで 0 件返却。単一翻訳源 `messages*.properties` による 4言語 (ja/en/zh/ko) 完全国際化。
  - **実装ガイダンス**: `AccountingIntegrationApiController.java`, `accounting-integration.js`, `messages*.properties`。
  - **テスト要件**: L2〜L3。マネージャー他組織遮断、空組織 DB 0 件、4言語 API / UI 表示検証、通知配信スコープ検証。
  - **Demo**: マネージャーアカウントでログインし、自組織外のジョブ・マッピング・プレビュー・照合データが一切表示されず（0件）、画面言語を ja/en/zh/ko に切り替えても欠落なく多言語表示されることを実演確認。

- [ ] R4-T05. 売上連携 Payload Snapshot & 原子補償ジョブ Enqueue
  - **Objective**: `payload_snapshot`（不変バイト列）による売上連携。取消 enqueue 時の `externalDealId`, `cancelReasonCode` 固定。HTTP in-flight 取消時の `CANCELLED_EXTERNALLY_CREATED` 検知と同一 Tx での補償 `SALES_INVOICE_CANCEL` ジョブ enqueue。BP・経費の RUNNING 取消拒否 (400)。
  - **実装ガイダンス**: `SalesInvoiceIntegrationServiceImpl.java`, `AccountingIntegrationWorker.java`。
  - **テスト要件**: L2〜L3。10回同時実行冪等性、金額不一致時 FAILED、in-flight 取消原子補償、締め済み月への更新拒否。
  - **Demo**: 売上送信のHTTP通信中にジョブ取消を実行し、外部に作成された取引を検知して自動的に補償取消ジョブが同一トランザクションでエンキューされ、孤立伝票が残らないことを実演確認。

- [ ] R4-T06. BP / 経費 業務日付固定・厳格照合 & 経費 CAS
  - **Objective**: テナントタイムゾーン（`TenantContext.getTimezone()`）に基づく BP Canonical 業務日付（`work_month` 末日）固定による翌日再実行ハッシュ不変。支払同期時の金額・日付双方非 NULL 厳格一致。経費同期の snapshot ハッシュ検証と `ExpenseRequest` CAS 更新 (`承認済` -> `会計連携済`)。
  - **実装ガイダンス**: `PurchaseExpensePaymentIntegrationServiceImpl.java`。
  - **テスト要件**: L2〜L3。翌日 retry 成功、NULL 金額/日付の拒否、経費改ざん拒否、経費 CAS 競合時の FAILED。
  - **Demo**: BP支払連携において、実行日翌日にジョブを手動リトライしてもハッシュ不変で安全に再試行でき、経費の承認ステータスが正常に「会計連携済」へ遷移することを実演確認。

- [ ] R4-T07. 月次照合 4母集団・入金一意キー・手数料計算 & Fail-Closed
  - **Objective**: 売上・仕入・入金・経費の 4 母集団完全照合。入金の `{externalDealId}:{paymentId}` 照合および `amount + fee` 手数料込み総消込突合。freee 取引一覧全件 pagination 取得。未接続・トークンなし・API 障害・50 ページ上限到達時の fail-closed (`readyForClosing=false`)。SUCCEEDED ジョブの実金額突合。
  - **実装ガイダンス**: `AccountingReconciliationServiceImpl.java`。
  - **テスト要件**: L2〜L3。4母集団照合、同日複数入金 1:1 突合、振込手数料付き入金の正確な照合、no-token fail-closed、50 ページ到達 fail-closed、同 dealId 異金額検知。
  - **Demo**: 振込手数料が引かれた同日複数入金データが freee 側の決済レコードと過不足なく 1:1 突合され、外部取引金額の乖離時には即座に月次締めがブロックされることを実演確認。

- [ ] R4-T08. S15 総合回帰 & CI 4Gate 完全検証 (M Gate)
  - **Objective**: 会計・支払連携の全体回帰テスト、障害耐性・セキュリティ・エラーハンドリング・マイグレーション・顧客効果の総合証明。
  - **テスト要件**: L4全量回帰 (`mvn test` 2,381+ PASS, 0 fail, 0 error, 0 skip)、`scripts/verify-like-ci.ps1`（Fast Suite, MySQL Shards 1-3, Performance Suite, Backup Gate）全通過。
  - **Demo**: (1) 4母集団の月次照合完全一致と締め処理完了、(2) 管理者・マネージャーでの desktop/390px 画面操作と認可境界、(3) 401トークン失効からの自動復旧と障害耐性、(4) CI 4Gate の全グリーン通過ログを提示。
