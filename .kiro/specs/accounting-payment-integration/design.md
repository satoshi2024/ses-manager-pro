# Design — 会計・支払連携

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL 及びスキーマ設計（S15 正式 migration V106 / forward V106.1）

### 1.1 テーブル定義

- `m_integration_connection`:
  - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
  - `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default'
  - `legal_entity_id` BIGINT NULL COMMENT '法人ID (NULL=共通/全社)'
  - `provider` VARCHAR(32) NOT NULL COMMENT 'freee / csv / mock'
  - `product` VARCHAR(32) NOT NULL COMMENT 'accounting / payroll'
  - `external_company_id` BIGINT NULL COMMENT 'freee company_id'
  - `company_name` VARCHAR(255) NULL
  - `encrypted_tokens` TEXT NULL COMMENT 'AES-GCM暗号化JSON'
  - `expires_at` DATETIME NULL
  - `status` VARCHAR(32) NOT NULL DEFAULT 'CONNECTED'
  - `connected_by` BIGINT NULL
  - `connected_at` DATETIME NULL
  - `last_refreshed_at` DATETIME NULL
  - `token_version` INT NOT NULL DEFAULT 1 COMMENT 'トークン更新世代番号 (multi-node CAS用)'
  - `refresh_lease_token` VARCHAR(64) NULL COMMENT 'トークン更新排他リースUUID'
  - `refresh_lease_expires_at` DATETIME NULL COMMENT 'トークン更新排他リース期限'
  - `deleted_flag` INT NOT NULL DEFAULT 0
  - `version` INT NOT NULL DEFAULT 0
  - `legal_entity_key` BIGINT GENERATED ALWAYS AS (COALESCE(legal_entity_id, 0)) STORED
  - `active_slot` INT GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN 1 ELSE NULL END) STORED
  - `UNIQUE KEY uk_int_conn (tenant_id, legal_entity_key, provider, product, active_slot)`

- `m_external_mapping`:
  - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
  - `connection_id` BIGINT NOT NULL
  - `object_type` VARCHAR(64) NOT NULL COMMENT 'CUSTOMER_PARTNER, BP_PARTNER, ACCOUNT_SALES, ACCOUNT_PURCHASE, ACCOUNT_EXPENSE, TAX_SALES_10, TAX_PURCHASE_10, TAX_EXPENSE_10, SECTION, COST_CENTER'
  - `internal_id` BIGINT NULL
  - `internal_code` VARCHAR(64) NOT NULL
  - `external_id` VARCHAR(64) NOT NULL COMMENT '正規識別子 (IDまたは数値tax_code文字列)'
  - `external_code` VARCHAR(64) NULL COMMENT '外部マスタコードまたは分類識別子'
  - `payload_snapshot` TEXT NULL COMMENT 'allow-listされた外部マスタ検証JSON'
  - `verified_at` DATETIME NULL
  - `deleted_flag` INT NOT NULL DEFAULT 0
  - `version` INT NOT NULL DEFAULT 0
  - `UNIQUE KEY uk_ext_mapping (connection_id, object_type, internal_code, deleted_flag)`

- `t_integration_job`:
  - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
  - `connection_id` BIGINT NOT NULL
  - `job_type` VARCHAR(64) NOT NULL COMMENT 'SALES_INVOICE_SYNC, SALES_INVOICE_CANCEL, BP_PURCHASE_SYNC, EXPENSE_DEAL_SYNC, PAYMENT_SYNC'
  - `target_type` VARCHAR(32) NOT NULL COMMENT 'INVOICE, BP_PAYMENT, EXPENSE_REQUEST'
  - `target_id` BIGINT NOT NULL
  - `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default'
  - `legal_entity_id` BIGINT NULL
  - `organization_id` BIGINT NULL COMMENT 'スコープ解決用組織IDスナップショット'
  - `idempotency_key` VARCHAR(128) NOT NULL
  - `payload_snapshot` LONGTEXT NULL COMMENT '送信時canonical byte列 (不変)'
  - `payload_hash` VARCHAR(64) NOT NULL COMMENT 'SHA-256(payload_snapshot)'
  - `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING'
  - `lease_token` VARCHAR(64) NULL COMMENT 'Worker lease UUID'
  - `lease_expires_at` DATETIME NULL COMMENT 'Worker lease 期限'
  - `attempt_count` INT NOT NULL DEFAULT 0
  - `max_attempts` INT NOT NULL DEFAULT 5
  - `next_retry_at` DATETIME NULL
  - `external_id` VARCHAR(64) NULL
  - `provider_request_id` VARCHAR(128) NULL
  - `error_code` VARCHAR(64) NULL
  - `error_message_safe` VARCHAR(500) NULL COMMENT '定型安全エラーメッセージ'
  - `deleted_flag` INT NOT NULL DEFAULT 0
  - `version` INT NOT NULL DEFAULT 0
  - `UNIQUE KEY uk_int_job_idemp (idempotency_key, deleted_flag)`

- `t_integration_job_event`:
  - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
  - `job_id` BIGINT NOT NULL
  - `from_status` VARCHAR(32) NULL
  - `to_status` VARCHAR(32) NOT NULL
  - `occurred_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  - `safe_detail` VARCHAR(500) NULL COMMENT '安全な監査メッセージ'

### 1.2 Migration & Forward Repair 契約
- **番号採番ルール**: S15 の正式 migration は `V106`（Consolidated baseline V1 に反映済み）。既存 V106 適用済み環境用の forward repair migration は **`V106.1` / `V106_1__accounting_integration_snapshot_and_slot.sql`** とする（S16 に予約済みの `V107` と衝突させない）。
- **`V106_1` 変更内容**:
  1. `m_integration_connection` に `token_version INT NOT NULL DEFAULT 1`, `refresh_lease_token VARCHAR(64) NULL`, `refresh_lease_expires_at DATETIME NULL` を追加。
  2. `m_integration_connection` に生成列 `legal_entity_key BIGINT GENERATED ALWAYS AS (COALESCE(legal_entity_id, 0)) STORED`, `active_slot INT GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN 1 ELSE NULL END) STORED` を追加。
  3. 重複データ Preflight & 退避・Reconciliation:
     - 移行前に `(tenant_id, COALESCE(legal_entity_id, 0), provider, product, deleted_flag = 0)` で重複するレコードを監査退避テーブル `m_integration_connection_backup_v106_1` へ退避。
     - `last_refreshed_at` / `updated_at` が最新の 1 行のみを `deleted_flag = 0` として維持し、その他の重複行を `deleted_flag = 1` に論理削除。
  4. 旧 `UNIQUE KEY uk_int_conn` を DROP し、新 `UNIQUE KEY uk_int_conn (tenant_id, legal_entity_key, provider, product, active_slot)` を作成。
  5. `t_integration_job` に `payload_snapshot LONGTEXT NULL`, `lease_token VARCHAR(64) NULL`, `lease_expires_at DATETIME NULL`, `tenant_id VARCHAR(64) NOT NULL DEFAULT 'default'`, `legal_entity_id BIGINT NULL`, `organization_id BIGINT NULL` を追加。
- **既存 Job の `payload_snapshot IS NULL` 扱い**:
  - 既存の完了・失敗ジョブの NULL snapshot は「レガシー記録」として読み取り専用で維持。
  - レガシージョブを手動リトライする場合は、業務エンティティから新 snapshot を生成して新規 Job として enqueue する。
- **Rollback 手順**:
  - 障害時は DDL ロールバック用 SQL（新 UNIQUE 削除、旧 UNIQUE 復元、追加列削除、`m_integration_connection_backup_v106_1` からの復元）を実行。
- **受入 4 経路**:
  1. Fresh V1 (新規 DB 構築)
  2. Legacy V106 → V106_1 (本番 upgrade 経路)
  3. H2 (`engineer-schema-h2.sql`, `schema-accounting-integration-h2.sql`)
  4. MySQL 8 Testcontainers 実コンテナ

---

## 2. 外部マスタ 10種別 & G4 判定決定表

| No | マッピング種別 | 正規識別子型 | freee API エンドポイント | 存在検証ルール | deal ペイロード適用先 (JSON型) | 確認状態 (Release Gate) |
|---|---|---|---|---|---|---|
| 1 | `CUSTOMER_PARTNER` | `id` (Numeric String) | `GET /api/1/partners/{id}?company_id={company_id}` | `partner.id == external_id` かつ事業所一致 | deal `partner_id` (Number) | **未確認 (Release Gate)** |
| 2 | `BP_PARTNER` | `id` (Numeric String) | `GET /api/1/partners/{id}?company_id={company_id}` | `partner.id == external_id` かつ事業所一致 | deal `partner_id` (Number) | **未確認 (Release Gate)** |
| 3 | `ACCOUNT_SALES` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | **未確認 (Release Gate)** |
| 4 | `ACCOUNT_PURCHASE` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | **未確認 (Release Gate)** |
| 5 | `ACCOUNT_EXPENSE` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | **未確認 (Release Gate)** |
| 6 | `TAX_SALES_10` | `tax_code` (Numeric Integer, 例: `34`) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 34` (Number) | **未確認 (Release Gate)** |
| 7 | `TAX_PURCHASE_10` | `tax_code` (Numeric Integer, 例: `21`) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 21` (Number) | **未確認 (Release Gate)** |
| 8 | `TAX_EXPENSE_10` | `tax_code` (Numeric Integer, 例: `21`) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 21` (Number) | **未確認 (Release Gate)** |
| 9 | `SECTION` | `id` (Numeric String) | `GET /api/1/sections?company_id={company_id}` | 一覧走査で `section.id == external_id` | deal details `section_id` (Number) | **未確認 (Release Gate)** |
| 10 | `COST_CENTER` | `id` (Numeric String) | `GET /api/1/sections?company_id={company_id}` | G4 決定: SECTION へ写像 | deal details `section_id` (Number) | **未確認 (Release Gate)** |

- 未知の `object_type` は即座に `return false` (fail-closed)。
- `m_external_mapping.payload_snapshot` には allow-list された canonical snapshot (`{ "objectType": "...", "externalId": "...", "externalCode": "...", "name": "...", "companyId": ..., "verifiedAt": "..." }`) のみを保存。

---

## 3. 状態機械・Lease・In-flight Cancel & 補償決定表

### 3.1 状態遷移マトリクス

| 遷移元 (`from_status`) | 遷移先 (`to_status`) | トリガー | CAS 条件 | 副作用 / 補償 |
|---|---|---|---|---|
| `PENDING` / `RETRYABLE` | `RUNNING` | Worker Claim | `status IN ('PENDING', 'RETRYABLE') AND (next_retry_at IS NULL OR next_retry_at <= NOW()) AND (lease_expires_at IS NULL OR lease_expires_at <= NOW())` | `lease_token = UUID`, `lease_expires_at = NOW()+15m` |
| `RUNNING` | `SUCCEEDED` | Worker 正常終了 (200 OK) | `status = 'RUNNING' AND lease_token = #{token}` | `lease_token = NULL`, 金額・dealId 照合 |
| `RUNNING` | `RETRYABLE` | 一時障害 (429/5xx/timeout) | `status = 'RUNNING' AND lease_token = #{token}` | `lease_token = NULL`, `next_retry_at = NOW()+backoff` |
| `RUNNING` | `FAILED` | 恒久障害 (400/422/改ざん) | `status = 'RUNNING' AND lease_token = #{token}` | `lease_token = NULL`, 終端 |
| `PENDING` / `RETRYABLE` | `CANCELLED` | ユーザー取消要求 (全種別) | `status IN ('PENDING', 'RETRYABLE')` | 実遷移元を記録。終端 |
| `RUNNING` | `CANCELLED` | ユーザー取消要求 (`SALES_INVOICE_SYNC`, `PAYMENT_SYNC` のみ) | `status = 'RUNNING'` | 実遷移元 `from_status='RUNNING'` を記録。HTTP in-flight 時は完了 CAS 失敗で検知 |
| `RUNNING` (in-flight cancel 後) | `CANCELLED` (維持) | Worker HTTP 完了時 | CAS 失敗 (status != 'RUNNING') | **同一 Tx 内で原子実行**: (1) `t_integration_job_event` に `to_status='CANCELLED'`, `safe_detail="CANCELLED_EXTERNALLY_CREATED (externalDealId=...)"` 記録 + (2) 補償 `SALES_INVOICE_CANCEL` ジョブ enqueue |
| `RUNNING` (stale lease 満了) | `RETRYABLE` | Worker Stale 回収 | `status = 'RUNNING' AND lease_expires_at <= NOW() AND lease_token = #{token}` | 個別 Tx で CAS + event 記録。再送前外部取引照合 |
| `FAILED` / `RETRYABLE` | `PENDING` | ユーザー手動リトライ | `status IN ('FAILED', 'RETRYABLE')` | 個別 Tx で CAS + 手動リトライ event 記録 |

### 3.2 ジョブ種別ごとの In-flight 取消・補償方針

| ジョブ種別 | In-flight Cancel 許可 | 外部作成検知時の補償動作 |
|---|---|---|
| `SALES_INVOICE_SYNC` | **許可** | `CANCELLED_EXTERNALLY_CREATED` イベント記録 ＋ `SALES_INVOICE_CANCEL` 補償ジョブを同一 Tx で原子 enqueue |
| `BP_PURCHASE_SYNC` | **拒否** (400 エラー) | 外部側での自動取消 API がないため、RUNNING 中の取消は 400 エラーで拒否（完了後に手動精算を案内） |
| `EXPENSE_DEAL_SYNC` | **拒否** (400 エラー) | 同上 |
| `SALES_INVOICE_CANCEL` | **拒否** (終端動作) | 取消処理そのものの取消は不可 |
| `PAYMENT_SYNC` | **許可** | 参照系ジョブのため外部副作用なし（補償不要） |

---

## 4. Multi-node 401 Token Refresh (3段階リース・CAS 状態機械)

> **原則**: DB トランザクション内で HTTP を呼ばない（platform-invariants §3.3 遵守）。

```
[Node A (401検知)] ───────> Step 1: Claim Lease (短期DB Tx) ─────> Lease獲得成功 (30s)
                                                                           │
[Node B (401検知)] ───────> Step 1: Claim Lease (短期DB Tx) ─────> 0件更新 (Lease保有中)
                                    │                                      │
                                    │ (Wait 500ms x 3)                     ▼
                                    │                             Step 2: freee OAuth POST (DB Tx 外)
                                    │                                      │
                                    │                                      ▼
                                    │                             Step 3: Update Tokens & CAS (短期DB Tx)
                                    ▼                                      │
                         再読込: 新トークン取得完了 <───────────────────────┘
```

1. **Step 1: 排他リース獲得 (短期 DB トランザクション)**:
   ```sql
   UPDATE m_integration_connection
   SET refresh_lease_token = #{workerUuid},
       refresh_lease_expires_at = DATE_ADD(NOW(), INTERVAL 30 SECOND),
       version = version + 1
   WHERE id = #{connectionId}
     AND token_version = #{observedTokenVersion}
     AND (refresh_lease_expires_at IS NULL OR refresh_lease_expires_at <= NOW());
   ```
   - 更新件数 = 1: リース獲得。即座にトランザクションをコミットして Step 2 へ進む。
   - 更新件数 = 0: 他ノードがリース獲得中または更新完了。現在行を再読込:
     - `current.token_version > observedTokenVersion`: 別ノードが更新完了。新トークンを復号して返却。
     - `current.token_version == observedTokenVersion`: 他ノードが外部通信中。バックオフ (500ms x 3) 後に再読込。
2. **Step 2: 外部 OAuth トークン更新 (DB トランザクション外)**:
   - freee OAuth `/oauth/token` エンドポイントへ POST。
3. **Step 3: 新トークン確定 CAS (短期 DB トランザクション)**:
   ```sql
   UPDATE m_integration_connection
   SET encrypted_tokens = #{newEncryptedTokens},
       expires_at = #{newExpiresAt},
       last_refreshed_at = NOW(),
       token_version = token_version + 1,
       refresh_lease_token = NULL,
       refresh_lease_expires_at = NULL,
       version = version + 1
   WHERE id = #{connectionId}
     AND refresh_lease_token = #{workerUuid};
   ```
4. **Timeout 未知結果の Pagination 確定**:
   - `verifyDealCreatedByRefNumber`: `limit = 100`, `offset = 0, 100, 200, ...`（最大 50 ページ / 5,000 件）。
   - `deal.ref_number == refNumber` かつ `deal.amount == expectedAmount` かつ `deal.company_id == externalCompanyId` の一致で確定。

---

## 5. 主体 × 操作 × SQL データスコープ決定表 (Consumer Inventory)

### 5.1 Consumer Inventory & SQL 述語

| Consumer 機能 | HTTP Method / エンドポイント | 管理者 (`ROLE_管理者`) SQL 述語 | マネージャー (`ROLE_マネージャー`) SQL 述語 | 営業 / HR / 要員 |
|---|---|---|---|---|
| 接続一覧 / 詳細 | `GET /api/accounting/connections` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND (legal_entity_id IS NULL OR legal_entity_id IN (#{allowedLegalEntities}))` (トークン非表示) | 403 |
| マッピング一覧 | `GET /api/accounting/mappings` | `WHERE connection_id = ?` | `WHERE connection_id IN (allowed_connections)` | 403 |
| マッピング検証 / 編集 | `POST/PUT /api/accounting/mappings` | 実行可 | 不可 (403) | 403 |
| 送信プレビュー | `GET /api/accounting/preview` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| ジョブ一覧 / カウント | `GET /api/accounting/jobs` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| ジョブ詳細 | `GET /api/accounting/jobs/{id}` | `WHERE id = ? AND tenant_id = #{tenantId}` | `WHERE id = ? AND tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| ジョブ再試行 / 取消 | `POST /api/accounting/jobs/{id}/retry|cancel` | 実行可 | `organization_id IN (#{allowedOrgIds})` の場合のみ許可 | 403 |
| 月次照合 / エクスポート | `GET /api/accounting/reconciliation` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| スケジューラ / Worker | バックグラウンド実行 | 全件 (システム Principal) | — | — |

- **空集合ガード**: マネージャーの `allowedOrgIds` が空集合（無所属）の場合、`WHERE 1 = 0` を付与し DB レベルで 0 件を返却。
- **`organization_id` 導出元**:
  - 売上ジョブ: `t_invoice.department_id` / `organization_id`
  - BP 仕入ジョブ: `t_bp_payment.organization_id`
  - 経費ジョブ: `t_expense_request.organization_id`
  - NULL の場合: 全社共通（マネージャーには不可視、管理者のみ可視）。

---

## 6. 決定表

### 6.1 時間・asOf 決定表 (Time & asOf Model)

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| **connection token** | `encrypted_tokens` + `token_version` + `expires_at` | 監査ログ (`t_audit_log`) | — | 現在値 (JST) | 未接続。**外部送信即時停止** (R1.3) |
| **refresh lease** | `refresh_lease_token` + `refresh_lease_expires_at` | — | — | DB 現在時刻 (`NOW()`) | リース非保有 (待機中) |
| **mapping** | `m_external_mapping.verified_at` あり | 上書き更新 (版なし) | `payload_snapshot` (検証時 allow-list JSON) | 現在値 | **未検証**。送信前バリデーションで停止 (R1.3) |
| **job payload** | — | `t_integration_job_event` | `payload_snapshot` (不変 canonical JSON byte 列) | job enqueue 時点 | レガシージョブ (再試行時新規作成) |
| **BP / 経費業務日付** | — | — | `work_month` 末日 (JST) および支払期日 | `work_month` に基づく固定日 | — |
| **外部連携状態** | job から**導出** | job event | — | 現在値 | job 無し＝**未送信** |
| **支払実績** | 外部同期値 (金額 + 決済日) | — | 照合時の金額 / 日付 | 同期実行時点 | 未決済 |
| **月次照合** | 都度計算 | 保存しない | — | 対象月 `[start_date, end_date + 1day)` 半開区間 | — |
| **job lease** | `lease_token` + `lease_expires_at` | — | — | DB 現在時刻 (`NOW()`) | リース非保有 |

- **Timezone**: 全システム処理・日付計算は `Asia/Tokyo` (JST) で統一。
- **確定日付ルール**: BP 仕入の canonical payload は `LocalDate.now()` を排除し、対象月（`work_month`）の最終日（例: `2026-08-31`）および契約支払期日を固定設定。翌日再試行でも `payload_hash` が一切変動しない。

---

### 6.2 月次照合 (4母集団・Pagination・Fail-Closed) 決定表

| 母集団種別 | 内部対象テーブル・抽出条件 | freee 外部対象 | 照合キー | 金額突合単位 | 照合ルール |
|---|---|---|---|---|---|
| **売上** | `t_invoice` (`status IN ('送付済', '入金済', '一部入金')`) | `deals` (type=`income`) | `ref_number` (内部 `invoice_no`) | `invoice.total` vs `deal.amount` | 金額完全一致で `MATCHED`、不一致は `AMOUNT_MISMATCH` |
| **仕入** | `t_bp_payment` (`status IN ('未払', '承認済', '支払済')`) | `deals` (type=`expense`) | `ref_number` (内部 `BP_PAYMENT:{id}`) | `bp_payment.amount` vs `deal.amount` | 金額完全一致で `MATCHED` |
| **入金** | `t_invoice_payment` | `payments` (income deal 配下) | `{externalDealId}:{paymentId}` または決済金額 + 入金日 | `invoice_payment.amount` vs `payment.amount` | 決済金額完全一致で `MATCHED` (同日複数入金も 1:1 対応) |
| **経費** | `t_expense_request` (`status IN ('承認済', '会計連携済')`) | `deals` (type=`expense`, 経費) | `ref_number` (内部 `expense_no`) | `expense.amount` vs `deal.amount` | 金額完全一致で `MATCHED` |

- **Fail-Closed 規則**:
  - connection なし / tokens なし: `externalFetchFailed = true`, `readyForClosing = false`。
  - freee 取引一覧取得で 50 ページ (5,000 件) 上限到達、重複 ID 検出、API タイムアウト・障害発生時: 即座に `externalFetchFailed = true`, `readyForClosing = false`。
  - SUCCEEDED ジョブであっても外部実金額との突合を必ず実施し、外部側での金額乖離を `AMOUNT_MISMATCH` として検知。

---

## 7. PII / Secret 遮断 & エラーハンドリング仕様

1. `FreeeAccountingProvider`:
   - 外部 API レスポンス本文（raw text）および例外メッセージはログ出力・保存を完全禁止。
   - エラー種別を以下の定型コードに写像:
     - `400 / 422`: `VALIDATION_ERROR` (`error.accounting.validation_error`)
     - `401`: `UNAUTHORIZED` (`error.accounting.unauthorized`)
     - `403`: `PLAN_LIMITATION` (`error.accounting.plan_limitation`)
     - `429`: `RATE_LIMITED` (`error.accounting.rate_limited`)
     - `5xx / timeout`: `SERVER_ERROR` (`error.accounting.server_error`)
   - `X-Freee-Request-ID` のみを相関 ID として記録。
2. Service 層 catch ブロック:
   - `e.getMessage()` を生で保存せず、定型エラーコードと局所化メッセージのみを記録。

---

## 8. i18n 単一翻訳源方針

1. **リソースバンドル**:
   - `messages.properties` (日本語既定), `messages_en.properties`, `messages_zh_CN.properties`, `messages_ko.properties` を正本とする。
2. **HTML / JS 連携**:
   - 画面初期描画時に Thymeleaf メッセージ解決から辞書を注入。
   - `accounting-integration.js` 内の全可視文言（事業所、有効期限、最終更新、プレビュー見出し、テーブルヘッダー、空表示、ボタン）を `t(key)` 化。
3. **取消理由コード**:
   - DB には機械可読コード (`REASON_CLIENT_CANCEL`, `REASON_AMOUNT_CORRECTION`, `REASON_DUPLICATE`, `REASON_DISPUTE`, `REASON_OTHER`) を保存し、UI 表示時に各言語へローカライズ。
