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

- [x] R4-T01. V106.1 Migration 5形状契約、Backup DDL、Partial-Safe Rollback (厳格順序) & Flyway Repair
  - **Objective**: S16予約衝突を回避した `V106.1` forward migration。`legal_entity_key` と `active_slot` による NULL 一意性および soft-delete 後の再作成保証。`m_integration_connection_backup_v106_1` テーブルの作成と重複 connection の事前退避・UPDATE 復元。新UNIQUE解除を先に行う厳格な順序（新UNIQUE削除 → 退避行UPDATE復元 → 旧UNIQUE復元 → 各追加列独立DROP → バックアップ削除）に基づく `information_schema` ガード付き完全 Rollback SQL および `flyway repair` 手順の確立。
  - **実装ガイダンス**: `V106_1__accounting_integration_snapshot_and_slot.sql`, `V1__create_tables.sql`, `engineer-schema-h2.sql`, `schema-accounting-integration-h2.sql`。
    5形状（fresh V1, legacy V106→V106.1, partial, backfill, repair）および完全ロールバック手順の検証。
  - **テスト要件**: L1〜L3。connection unique (NULL 重複拒否, soft-delete 再作成), 既存 connection 移行後の回帰, activeなNULL法人重複2件の apply → rollback → 全行復元検証, 任意列のみが存在する partial 形状での rollback 成功検証, rollback後のV106期待形状完全一致検証。
  - **Demo**: (1) NULL法人接続の重複登録が拒否されること、(2) 論理削除後に同一キーで新規登録が成功すること、(3) activeなNULL法人重複2件を含むDBでV106.1適用時に有効レコードが選定され退避テーブルに保存されること、(4) ロールバックSQLにより新UNIQUE解除後に重複2件が安全に復元され全追加列が削除されてV106初期状態へ完全復旧すること、(5) 途中失敗DBに対してFlyway repairを実行して再適用が成功することをMySQL上で実演確認。

- [x] R4-T02. Multi-node 3段階リース・Fencing Token CAS、敗者ノード復旧 & タイムアウト未知結果 Pagination
  - **Objective**: DB トランザクション外で HTTP を呼ぶ 3段階リース・CAS 状態機械による 401 トークンリフレッシュ直列化。45秒リースと10秒HTTPタイムアウトによるフェンシング。3回バックオフ待機後の敗者ノードにおける `TOKEN_REFRESH_IN_PROGRESS` 例外送出と Job の `RETRYABLE(5s)` 遷移。Deal 作成タイムアウト未知結果の全件 pagination (最大50ページ / `verifyDealCreatedByRefNumber`) 照合。
  - **実装ガイダンス**: `IntegrationConnectionServiceImpl.java`, `FreeeAccountingProvider.java`。
  - **テスト要件**: L2〜L3。
    1. HTTP Timeout 試験: WireMock 11秒遅延で Read Timeout (10秒) が発生し、Step 3 CAS に到達せず Job が `RETRYABLE` になること。
    2. CAS Fencing 試験: トークン取得後に `token_version` が競合更新された場合、CAS 失敗で新トークンを破棄して最新行を再読込すること。
    3. 9秒遅延 & 敗者リトライ試験: Node A が 9秒間 OAuth 実行中、Node B が 3回待機（3.5秒）後に `TOKEN_REFRESH_IN_PROGRESS` で `RETRYABLE (5s)` に遷移し、5秒後の次回リトライで Node A の新トークンを取得して成功すること。
    4. 未知結果 50 ページ走査試験: タイムアウト後に全 50 ページ走査で作成済み取引を検知すること。
  - **Demo**: 2並行ノードで同時401を発生させ、1ノードがOAuthを実行し、敗者ノードが一時的にRETRYABLEに待機後、5秒後の次回実行で確定した最新トークンを安全に共有利用して成功することをログで実演確認。

- [x] R4-T03. 外部マスタ 10種別 固定 Contract Fixture 検証・数値 Tax Code & PII 完全遮断
  - **Objective**: freee 公式開発者リファレンス（`https://developer.freee.co.jp/reference/accounting/reference`, `https://developer.freee.co.jp/info/accounting`）に基づく固定 contract fixture（`src/test/resources/fixtures/accounting/freee/`）を用いた全10マスタ種別の正規識別子照合（税区分は数値 `tax_code: 34`/`21`、取引先/勘定科目/部門は数値 `id`）。未知種別 fail-closed 検証。allow-list canonical snapshot 保存。生レスポンス・生例外の完全遮断（定型エラーコード化）。
  - **実装ガイダンス**: `FreeeAccountingProvider.java`, `ExternalMappingServiceImpl.java`。
  - **テスト要件**: L2〜L3。全10種別 fixture 照合検証、未知種別 404/false、一覧 200 だが ID 不存在時 false、PII 遮断ログキャプチャテスト。
  - **Demo**: 10種別のマッピング検証を実行し、不正な税コードや未知種別が即座に拒否され、例外発生時にもログおよびJob詳細に生JSONや個人情報が出力されないことを実演確認。

- [x] R4-T04. SQL Scope 境界制御 (経費 asOf 履歴照合・実在エンティティ組織導出 & 全 Consumer 対応) & 4言語 i18n
  - **Objective**: 経費ジョブの `t_engineer_accounting_history`（`asOf = expense_date`）照合および `organization_history_status = 'UNKNOWN'` 時の fail-closed（現在値へのフォールバック禁止）。売上・BP の `t_user_organization` 履歴照合。Consumer inventory 全機能（list/detail/count/export/preview/reconciliation/notification/retry/cancel/scheduler）に SQL スコープを適用。マネージャーは自組織のみ、空組織集合時は DB レベルで 0 件返却。単一翻訳源 `messages*.properties` による 4言語 (ja/en/zh/ko) 完全国際化。
  - **実装ガイダンス**: `AccountingIntegrationApiController.java`, `accounting-integration.js`, `messages*.properties`。
  - **テスト要件**: L2〜L3。異動前後の経費申請が異動前組織に正しく帰属することの検証、UNKNOWN 時の fail-closed 検証、マネージャー他組織遮断、空組織 DB 0 件、4言語 API / UI 表示検証、通知配信スコープ検証。
  - **Demo**: 異動した要員の過去月経費を会計連携した際、異動前組織のマネージャーにのみ可視となり、異動後組織のマネージャーには不可視となること、および ja/en/zh/ko 4言語の切り替えを実演確認。

- [x] R4-T05. 売上連携 Payload Snapshot, 種別別取消マトリクス & 原子補償ジョブ Enqueue
  - **Objective**: `payload_snapshot`（不変バイト列）による売上連携。種別別取消マトリクス（`SALES_INVOICE_CANCEL` の全状態取消拒否 400、BP/経費の RUNNING 取消拒否 400、売上/入金の RUNNING 取消許可）の実装。HTTP in-flight 取消時の `CANCELLED_EXTERNALLY_CREATED` 検知と同一 Tx での補償 `SALES_INVOICE_CANCEL` ジョブ enqueue。
  - **実装ガイダンス**: `SalesInvoiceIntegrationServiceImpl.java`, `AccountingIntegrationWorker.java`。
  - **テスト要件**: L2〜L3。10回同時実行冪等性、金額不一致時 FAILED、in-flight 取消原子補償、締め済み月への更新拒否、`SALES_INVOICE_CANCEL` 取消要求の 400 拒否。
  - **Demo**: 売上送信のHTTP通信中にジョブ取消を実行し、外部に作成された取引を検知して自動的に補償取消ジョブが同一トランザクションでエンキューされ、孤立伝票が残らないことを実演確認。

- [x] R4-T06. BP / 経費 業務日付固定・AccountingTimezoneResolver (Tenant別設定) & 経費 CAS
  - **Objective**: `AccountingTimezoneResolver`（`m_system_config` キー `accounting.timezone.{tenantId}`、既定 `Asia/Tokyo`）および `AccountingTenantContextHolder`（try-finally 管理）に基づく BP Canonical 業務日付（`work_month` 末日）固定による翌日再実行ハッシュ不変。支払同期時の金額・日付双方非 NULL 厳格一致。経費同期の snapshot ハッシュ検証と `ExpenseRequest` CAS 更新 (`承認済` -> `会計連携済`)。
  - **実装ガイダンス**: `PurchaseExpensePaymentIntegrationServiceImpl.java`, `AccountingTimezoneResolver.java`, `AccountingTenantContextHolder.java`。
  - **テスト要件**: L2〜L3。2テナントで異なるタイムゾーン（Tokyo vs New York）設定時の正確な月境界解決、翌日 retry 成功、NULL 金額/日付の拒否、経費改ざん拒否、経費 CAS 競合時の FAILED。
  - **Demo**: 異なるタイムゾーンを持つテナント間で月境界・支払期日が各テナント設定に応じて正確に計算され、BP支払連携が翌日再試行でもハッシュ不変で安全に実行できることを実演確認。

- [x] R4-T07. 月次照合 4母集団・入金一意キー・手数料計算 & Fail-Closed
  - **Objective**: 売上・仕入・入金・経費の 4 母集団完全照合。入金の `{externalDealId}:{paymentId}` 照合および `amount + fee` 手数料込み総消込突合。曖昧マッチ時の `PAYMENT_AMBIGUOUS` fail-closed。freee 取引一覧全件 pagination 取得。未接続・トークンなし・API 障害・50 ページ上限到達時の fail-closed (`readyForClosing=false`)。SUCCEEDED ジョブの実金額突合。
  - **実装ガイダンス**: `AccountingReconciliationServiceImpl.java`。
  - **テスト要件**: L2〜L3。4母集団照合、同日複数入金 1:1 突合、振込手数料付き入金の正確な照合、曖昧入金の fail-closed、no-token fail-closed、50 ページ到達 fail-closed、同 dealId 異金額検知。
  - **Demo**: 振込手数料が引かれた同日複数入金データが freee 側の決済レコードと過不足なく 1:1 突合され、外部取引金額の乖離時には即座に月次締めがブロックされることを実演確認。

- [x] R4-T08. S15 総合回帰 & CI 4Gate 完全検証 (M Gate: CONDITIONAL PASS / Release Gate 分離)
  - **Objective**: 会計・支払連携の全体回帰テスト、障害耐性・セキュリティ・エラーハンドリング・マイグレーション・顧客効果の総合証明。
  - **テスト要件**: L4全量回帰 (`mvn test` 2,381+ PASS, 0 fail, 0 error, 0 skip)、`scripts/verify-like-ci.ps1`（Fast Suite, MySQL Shards 1-3, Performance Suite, Backup Gate）全通過。
  - **Demo (CONDITIONAL PASS 条件)**: (1) 4母集団の月次照合完全一致と締め処理完了、(2) 管理者・マネージャーでの desktop/390px 画面操作と認可境界、(3) 401トークン失効からの自動復旧と障害耐性、(4) CI 4Gate の全グリーン通過ログを提示。
  - **未実行環境・本番前 Release Gate (`GATE-S15-FREEE-PROD`)**: 実 freee 契約プラン、本番 company_id、本番 OAuth クライアント認証情報、本番実マスタID への接続は本番前条件として明記し、CI 上での完了判定は **`CONDITIONAL PASS`** とする。
