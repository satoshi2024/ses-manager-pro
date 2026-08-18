# Design — 会計・支払連携

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL 及びスキーマ設計（S15 正式 migration V106 / forward V107）

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
  - `external_id` VARCHAR(64) NOT NULL COMMENT '正規識別子'
  - `external_code` VARCHAR(64) NULL
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
- `V1__create_tables.sql`: Consolidated baseline として全最新スキーマ（`legal_entity_key`, `active_slot`, `token_version`, `payload_snapshot`, `lease_token`, `lease_expires_at`, `tenant_id`, `legal_entity_id`, `organization_id`）を含める。
- `V107__accounting_integration_snapshot_and_slot.sql`: 既存 V106 適用済み環境用の forward migration。
  - 重複データ Preflight & Reconciliation: `(tenant_id, COALESCE(legal_entity_id, 0), provider, product)` で `deleted_flag = 0` の重複が存在する場合、`last_refreshed_at` / `updated_at` が最新の 1 行を残し、他行を `deleted_flag = 1` に論理削除。
  - 旧 `uk_int_conn` を DROP し、新 `uk_int_conn (tenant_id, legal_entity_key, provider, product, active_slot)` を作成。
  - `t_integration_job` に `payload_snapshot`, `lease_token`, `lease_expires_at`, `tenant_id`, `legal_entity_id`, `organization_id` を追加。
- `engineer-schema-h2.sql` / `schema-accounting-integration-h2.sql`: テスト用 H2 スキーマを同期。
- `legal_entity_id = 0` の Sentinel 根拠: `m_legal_entity.id` は AUTO_INCREMENT >= 1 のため、0 は実法人 ID と絶対に衝突しない。

---

## 2. 外部マスタ 10種別 & G4 判定決定表

| マッピング種別 | 正規識別子 | freee API エンドポイント | 存在検証ルール | deal ペイロード適用先 |
|---|---|---|---|---|
| `CUSTOMER_PARTNER` | `partner.id` (Numeric) | `GET /api/1/partners/{id}?company_id={company_id}` | `partner.id == external_id` かつ事業所一致 | deal `partner_id` |
| `BP_PARTNER` | `partner.id` (Numeric) | `GET /api/1/partners/{id}?company_id={company_id}` | `partner.id == external_id` かつ事業所一致 | deal `partner_id` |
| `ACCOUNT_SALES` | `account_item.id` (Numeric) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` |
| `ACCOUNT_PURCHASE` | `account_item.id` (Numeric) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` |
| `ACCOUNT_EXPENSE` | `account_item.id` (Numeric) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` |
| `TAX_SALES_10` | `tax_code` (String) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_code` | deal details `tax_code` |
| `TAX_PURCHASE_10` | `tax_code` (String) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_code` | deal details `tax_code` |
| `TAX_EXPENSE_10` | `tax_code` (String) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_code` | deal details `tax_code` |
| `SECTION` | `section.id` (Numeric) | `GET /api/1/sections?company_id={company_id}` | 一覧走査で `section.id == external_id` | deal details `section_id` |
| `COST_CENTER` | `section.id` (Numeric) | `GET /api/1/sections?company_id={company_id}` | G4 決定: SECTION へ写像 | deal details `section_id` |

- 未知の `object_type` は即座に `return false` (fail-closed)。
- `m_external_mapping.payload_snapshot` には allow-list された canonical snapshot (`{ "id": 101, "name": "...", "code": "...", "verifiedAt": "..." }`) のみを保存。

---

## 3. 状態機械・Lease・In-flight Cancel & 補償決定表

### 3.1 状態遷移マトリクス

| 遷移元 (`from_status`) | 遷移先 (`to_status`) | トリガー | CAS 条件 | 副作用 / 補償 |
|---|---|---|---|---|
| `PENDING` / `RETRYABLE` | `RUNNING` | Worker Claim | `status IN ('PENDING', 'RETRYABLE') AND (next_retry_at IS NULL OR next_retry_at <= NOW()) AND (lease_expires_at IS NULL OR lease_expires_at <= NOW())` | `lease_token = UUID`, `lease_expires_at = NOW()+15m` |
| `RUNNING` | `SUCCEEDED` | Worker 正常終了 | `status = 'RUNNING' AND lease_token = #{token}` | `lease_token = NULL`, 金額・dealId 照合 |
| `RUNNING` | `RETRYABLE` | 一時障害 (429/5xx/timeout) | `status = 'RUNNING' AND lease_token = #{token}` | `lease_token = NULL`, `next_retry_at = NOW()+backoff` |
| `RUNNING` | `FAILED` | 恒久障害 (400/422/改ざん) | `status = 'RUNNING' AND lease_token = #{token}` | `lease_token = NULL`, 終端 |
| `PENDING` / `RETRYABLE` / `RUNNING` | `CANCELLED` | ユーザー取消要求 | `status IN ('PENDING', 'RETRYABLE', 'RUNNING')` | 実遷移元を記録。HTTP in-flight 時は完了 CAS 失敗で検知 |
| `RUNNING` (in-flight cancel 後) | `CANCELLED` (維持) | Worker HTTP 完了時 | CAS 失敗 (status != 'RUNNING') | 外部取引作成済の場合 `CANCELLED_EXTERNALLY_CREATED` event 記録 + 補償 `SALES_INVOICE_CANCEL` ジョブ enqueue |
| `RUNNING` (stale lease 満了) | `RETRYABLE` | Worker Stale 回収 | `status = 'RUNNING' AND lease_expires_at <= NOW() AND lease_token = #{token}` | 個別 Tx で CAS + event 記録。再送前外部取引照合 |
| `FAILED` / `RETRYABLE` | `PENDING` | ユーザー手動リトライ | `status IN ('FAILED', 'RETRYABLE')` | 個別 Tx で CAS + 手動リトライ event 記録 |

### 3.2 ジョブ種別ごとの In-flight 取消・補償方針

| ジョブ種別 | In-flight Cancel 許可 | 外部作成検知時の補償動作 |
|---|---|---|
| `SALES_INVOICE_SYNC` | 許可 | `CANCELLED_EXTERNALLY_CREATED` イベント記録 ＋ `SALES_INVOICE_CANCEL` 補償ジョブを自動 enqueue |
| `BP_PURCHASE_SYNC` | 拒否 (RUNNING 取消不可) | 外部側での自動取消 API がないため、RUNNING 中の取消は 400 エラーで拒否（完了後に手動精算を案内） |
| `EXPENSE_DEAL_SYNC` | 拒否 (RUNNING 取消不可) | 同上 |
| `SALES_INVOICE_CANCEL` | 拒否 (終端動作) | 取消処理そのものの取消は不可 |
| `PAYMENT_SYNC` | 許可 | 参照系ジョブのため外部副作用なし（補償不要） |

---

## 4. Multi-node 401 Token Refresh 決定表

1. **Token Refresh 契約**:
   - `m_integration_connection.token_version` (INT) を保持。
   - 401 を検知した Worker は `forceRefreshToken(connectionId, observedTokenVersion)` を呼出。
   - `SELECT * FROM m_integration_connection WHERE id = ? FOR UPDATE` で排他行ロックを取得。
   - ロック取得後:
     - `current.token_version > observedTokenVersion`: 別ノードが既にリフレッシュ完了。`current.expires_at > NOW() + 60s` を確認し、外部 API を呼ばずに新トークンを復号して返却。
     - `current.token_version == observedTokenVersion`: freee OAuth エンドポイントへ POST。新トークンを暗号化保存し、`token_version = token_version + 1`, `last_refreshed_at = NOW()` を更新してコミット。
2. **Timeout 未知結果の Pagination 照合**:
   - `verifyDealCreatedByRefNumber`: `limit = 100`, `offset = 0, 100, 200, ...`（最大 50 ページ / 5,000 件）。
   - `deal.ref_number == refNumber` かつ `deal.amount == expectedAmount` かつ `deal.company_id == externalCompanyId` の一致で確定。

---

## 5. 主体 × 操作 × SQL データスコープ決定表

| 主体 / ロール | 接続 / マッピング参照 | 接続 / マッピング編集 | ジョブ一覧 / 詳細 | ジョブ操作 (Retry/Cancel) | プレビュー / 月次照合 |
|---|---|---|---|---|---|
| **管理者** (`ROLE_管理者`) | 全件 (トークン非露出) | 全件可 | 全件 | 全件可 | 全件可 |
| **マネージャー** (`ROLE_マネージャー`) | 全件 (参照のみ) | 不可 (403) | **自組織のみ** (SQL 境界) | 自組織のみ | **自組織のみ** (SQL 境界) |
| **営業 / HR / 要員** | 不可 (403) | 不可 (403) | 不可 (403) | 不可 (403) | 不可 (403) |

- **SQL 境界クエリ仕様**:
  - `t_integration_job` に `tenant_id`, `organization_id` を保持。
  - マネージャーロールの場合: `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})`
  - `allowedOrgIds` が空集合（無所属または権限なし）の場合は `WHERE 1 = 0` を付加し、DB レベルで厳格に 0 件を返却。

---

## 6. 月次照合 (4母集団・Pagination・Fail-Closed) 決定表

| 母集団種別 | 内部対象テーブル・抽出条件 | freee 外部対象 | 照合キー | 金額突合単位 | 照合ルール |
|---|---|---|---|---|---|
| **売上** | `t_invoice` (`status IN ('送付済', '入金済', '一部入金')`) | `deals` (type=`income`) | `ref_number` (内部 `invoice_no`) | `invoice.total` vs `deal.amount` | 金額完全一致で `MATCHED`、不一致は `AMOUNT_MISMATCH` |
| **仕入** | `t_bp_payment` (`status IN ('未払', '承認済', '支払済')`) | `deals` (type=`expense`) | `ref_number` (内部 `BP_PAYMENT:{id}`) | `bp_payment.amount` vs `deal.amount` | 金額完全一致で `MATCHED` |
| **入金** | `t_invoice_payment` | `payments` (income deal 配下) | `deal.id` + 入金日 | `invoice_payment.amount` vs `payment.amount` | 決済金額完全一致で `MATCHED` |
| **経費** | `t_expense_request` (`status IN ('承認済', '会計連携済')`) | `deals` (type=`expense`, 経費) | `ref_number` (内部 `expense_no`) | `expense.amount` vs `deal.amount` | 金額完全一致で `MATCHED` |

- **Fail-Closed 規則**:
  - connection が存在しない、または tokens が NULL の場合: `externalFetchFailed = true`, `readyForClosing = false`。
  - freee 取引一覧取得で 50 ページ (5,000 件) に達しても未完了、ページ重複 ID 検出、API タイムアウト・エラー発生時: 即座に `externalFetchFailed = true`, `readyForClosing = false`。
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
