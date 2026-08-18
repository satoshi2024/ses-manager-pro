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

### 1.2 Migration 5形状契約 & Forward Repair (platform-invariants §4.2 準拠)

- **番号採番ルール**: S15 の正式 migration は `V106`（Consolidated baseline V1 に反映済み）。既存 V106 適用済み環境用の forward repair migration は **`V106.1` / `V106_1__accounting_integration_snapshot_and_slot.sql`** とする（S16 に予約済みの `V107` と衝突させない）。
- **5形状の契約手順**:
  1. **Fresh V1**: `V1__create_tables.sql` に全最新スキーマ（`legal_entity_key`, `active_slot`, `token_version`, `refresh_lease_*`, `payload_snapshot`, `lease_*`, `tenant_id`, `legal_entity_id`, `organization_id`）を含め、新規DBを一発初期化。
  2. **Legacy V106**: 既存DBに対し `V106_1` を適用。
  3. **Partial (非トランザクショナル途中失敗リカバリ)**: 各DDLステートメントに `IF NOT EXISTS` またはカラム存在検査を行い、任意の中断点から再実行しても冪等に成功するスクリプト構造とする。
  4. **Backfill & Preflight**:
     - `m_integration_connection`: `(tenant_id, COALESCE(legal_entity_id, 0), provider, product, deleted_flag = 0)` の重複行を検査。
     - **Survivorship / Merge 規則**:
       - 優先度1: `status = 'CONNECTED'` かつ `encrypted_tokens IS NOT NULL` かつ `expires_at > NOW()` の有効接続を最優先で残す。
       - 優先度2: `last_refreshed_at` が最新の行。
       - 優先度3: `updated_at` が最新の行（IDが大きい行）。
     - 重複レコードは監査退避テーブル `m_integration_connection_backup_v106_1` へ全列退避後、非残存行を `deleted_flag = 1` に論理削除。
     - `t_integration_job`: 既存完了・失敗ジョブの `payload_snapshot IS NULL` はレガシー記録（読み取り専用）として保持。手動リトライ時は新スナップショットで新規Jobを作成。
  5. **Repair**: `V106_1` を再実行した場合、差分なしで正常終了。
- **Rollback 順序と手順**:
  1. 新 `UNIQUE KEY uk_int_conn` を DROP。
  2. `m_integration_connection_backup_v106_1` から退避レコードを `deleted_flag = 0` で復元。
  3. 旧 `UNIQUE KEY uk_int_conn (tenant_id, legal_entity_id, provider, product, deleted_flag)` を再作成。
  4. 追加列 `legal_entity_key`, `active_slot`, `token_version`, `refresh_lease_*` を DROP。

---

## 2. 外部マスタ 10種別 & G4 判定決定表

| No | マッピング種別 | 正規識別子型 | freee API エンドポイント / 一次資料 | 存在検証ルール | deal ペイロード適用先 (JSON型) | 確認状態 |
|---|---|---|---|---|---|---|
| 1 | `CUSTOMER_PARTNER` | `id` (Numeric String) | `GET /api/1/partners/{id}?company_id={company_id}` (freee API v1) | `partner.id == external_id` かつ事業所一致 | deal `partner_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 2 | `BP_PARTNER` | `id` (Numeric String) | `GET /api/1/partners/{id}?company_id={company_id}` (freee API v1) | `partner.id == external_id` かつ事業所一致 | deal `partner_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 3 | `ACCOUNT_SALES` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` (freee API v1) | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 4 | `ACCOUNT_PURCHASE` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` (freee API v1) | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 5 | `ACCOUNT_EXPENSE` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` (freee API v1) | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 6 | `TAX_SALES_10` | `tax_code` (Numeric Integer, 例: `34`) | `GET /api/1/taxes/companies/{company_id}` (freee API v1) | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 34` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 7 | `TAX_PURCHASE_10` | `tax_code` (Numeric Integer, 例: `21`) | `GET /api/1/taxes/companies/{company_id}` (freee API v1) | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 21` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 8 | `TAX_EXPENSE_10` | `tax_code` (Numeric Integer, 例: `21`) | `GET /api/1/taxes/companies/{company_id}` (freee API v1) | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 21` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 9 | `SECTION` | `id` (Numeric String) | `GET /api/1/sections?company_id={company_id}` (freee API v1) | 一覧走査で `section.id == external_id` | deal details `section_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |
| 10 | `COST_CENTER` | `id` (Numeric String) | `GET /api/1/sections?company_id={company_id}` (freee API v1) | G4 決定: SECTION へ写像 | deal details `section_id` (Number) | `PROVISIONAL` (開発用公式準拠) / Release Gate |

- **PROVISIONAL 運用契約**: 開発・テスト（R4-T01〜R4-T08）は公式仕様に基づく上記 PROVISIONAL 定義および WireMock fixture で進め、実契約プラン・実会社ID・本番マスタIDは本番Release Gateとして管理する。
- 未知の `object_type` は即座に `return false` (fail-closed)。
- `m_external_mapping.payload_snapshot` には allow-list された canonical snapshot (`{ "objectType": "...", "externalId": "...", "externalCode": "...", "name": "...", "companyId": ..., "verifiedAt": "..." }`) のみを保存。

---

## 3. 状態機械・Lease・In-flight Cancel & 補償決定表 (platform-invariants §8 表3 準拠)

### 3.1 状態遷移マトリクス

| 状態 (`status`) | 許可遷移 | 防重手段 | competing writer | rollback 挙動 |
|---|---|---|---|---|
| `PENDING` | → `RUNNING` (Worker claim)<br>→ `CANCELLED` (取消要求) | DB CAS (`status='PENDING' AND (next_retry_at IS NULL OR <= NOW())`) | 複数 Worker の同時 claim | claim CAS 失敗時は `PENDING` を維持 |
| `RUNNING` | → `SUCCEEDED` (HTTP 200)<br>→ `RETRYABLE` (429/5xx/timeout)<br>→ `FAILED` (400/422/改ざん)<br>→ `CANCELLED` (`SALES_INVOICE_SYNC`, `PAYMENT_SYNC` のみ) | `status='RUNNING' AND lease_token=#{token}` による完了 CAS | Worker 完了 vs ユーザー取消 | HTTP in-flight 中に取消された場合、完了 CAS が失敗。同一 Tx 内で (1) `t_integration_job_event` 記録 + (2) 補償 `SALES_INVOICE_CANCEL` enqueue を原子実行。Tx 失敗時は全 rollback |
| `RETRYABLE` | → `RUNNING` (Worker claim)<br>→ `CANCELLED` (取消要求) | DB CAS (`status='RETRYABLE' AND next_retry_at <= NOW()`) | 複数 Worker の同時 claim | claim CAS 失敗時は `RETRYABLE` を維持 |
| `FAILED` | → `PENDING` (手動リトライ) | DB CAS (`status='FAILED'`) | 複数ユーザーの同時リトライ | 手動リトライ CAS 失敗時は `FAILED` を維持 |
| `SUCCEEDED` | 終端 (遷移不可) | `UNIQUE(idempotency_key)` | 再実行リクエスト拒否 | 変更不可。取消時は別ジョブ (`SALES_INVOICE_CANCEL`) を新規作成 |
| `CANCELLED` | 終端 (遷移不可) | 状態 CAS | 二重取消要求拒否 | 変更不可 |

### 3.2 ジョブ種別ごとの In-flight 取消・補償方針

| ジョブ種別 | In-flight Cancel 許可 | 外部作成検知時の補償動作 |
|---|---|---|
| `SALES_INVOICE_SYNC` | **許可** | `CANCELLED_EXTERNALLY_CREATED` イベント記録 ＋ `SALES_INVOICE_CANCEL` 補償ジョブを同一 Tx で原子 enqueue |
| `BP_PURCHASE_SYNC` | **拒否** (400 エラー) | 外部側での自動取消 API がないため、RUNNING 中の取消は 400 エラーで拒否（完了後に手動精算を案内） |
| `EXPENSE_DEAL_SYNC` | **拒否** (400 エラー) | 同上 |
| `SALES_INVOICE_CANCEL` | **拒否** (終端動作) | 取消処理そのものの取消は不可 |
| `PAYMENT_SYNC` | **許可** | 参照系ジョブのため外部副作用なし（補償不要） |

---

## 4. Multi-node 401 Token Refresh (3段階リース・Fencing・CAS 状態機械)

> **原則**: DB トランザクション内で HTTP を呼ばない（platform-invariants §3.3 遵守）。

```
[Node A (401検知)] ───────> Step 1: Claim Lease (短期DB Tx) ─────> Lease獲得成功 (45s)
                                                                           │
[Node B (401検知)] ───────> Step 1: Claim Lease (短期DB Tx) ─────> 0件更新 (Lease保有中)
                                    │                                      │
                                    │ (Wait 500ms x 3)                     ▼
                                    │                             Step 2: freee OAuth POST (DB Tx 外, 10s timeout)
                                    │                                      │
                                    │                                      ▼
                                    │                             Step 3: Update Tokens & CAS (短期DB Tx)
                                    ▼                                      │
                         再読込: 新トークン取得完了 <───────────────────────┘
```

1. **HTTP タイムアウト & リース安全幅**:
   - HTTP Client 設定: Connect Timeout = 5秒, Read Timeout = **10秒** (最大 HTTP 呼出時間 15秒)。
   - リース期間: **45秒** (`refresh_lease_expires_at = NOW() + INTERVAL 45 SECOND`)。最大 HTTP 時間に対し 30秒の安全マージンを確保。
2. **Step 1: 排他リース獲得 (短期 DB トランザクション)**:
   ```sql
   UPDATE m_integration_connection
   SET refresh_lease_token = #{workerUuid},
       refresh_lease_expires_at = DATE_ADD(NOW(), INTERVAL 45 SECOND),
       version = version + 1
   WHERE id = #{connectionId}
     AND token_version = #{observedTokenVersion}
     AND (refresh_lease_expires_at IS NULL OR refresh_lease_expires_at <= NOW());
   ```
   - 更新件数 = 1: リース獲得。即座にトランザクションをコミットして Step 2 へ進む。
   - 更新件数 = 0: 他ノードがリース獲得中または更新完了。現在行を再読込:
     - `current.token_version > observedTokenVersion`: 別ノードが更新完了。新トークンを復号して返却。
     - `current.token_version == observedTokenVersion`: 他ノードが外部通信中。バックオフ (500ms, 1000ms, 2000ms 最大 5回) 後に再読込。
3. **Step 2: 外部 OAuth トークン更新 (DB トランザクション外)**:
   - freee OAuth `/oauth/token` エンドポイントへ POST (10s timeout)。
4. **Step 3: 新トークン確定 Fencing CAS (短期 DB トランザクション)**:
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
     AND token_version = #{observedTokenVersion}
     AND refresh_lease_token = #{workerUuid};
   ```
   - CAS 成功 (1件): トークン確定完了。
   - CAS 失敗 (0件): タイムアウト等でリースが失効し他ノードに奪われた場合、取得したトークンを破棄して再読込。
5. **認証不能・障害時の復旧**:
   - OAuth エラー `invalid_grant`（リフレッシュトークン失効）時: `status = 'ERROR'`, `error_code = 'REAUTH_REQUIRED'` を設定し、全ノードで再認可を要求。

---

## 5. 主体 × 操作 × SQL データスコープ決定表 (Consumer Inventory)

### 5.1 実エンティティに基づく組織導出ルール

1. **売上ジョブ (`SALES_INVOICE_SYNC`, `SALES_INVOICE_CANCEL`)**:
   - 優先度1: `t_invoice.cost_center_id` -> `m_cost_center.organization_id`。
   - 優先度2: `cost_center_id` が NULL の場合、主請求明細の契約 `t_contract.sales_user_id` の所属組織 (`t_user_organization` where `as_of = invoice.issued_date` または `billing_month` 末日)。
   - 優先度3: `t_invoice.customer_id` -> `m_customer.sales_user_id` の所属組織。
   - 複数組織明細を含む場合: 請求書の原価部門または主明細の契約組織を採用。
   - 導出不能時: NULL (全社共通、管理者のみ可視)。
2. **BP 仕入ジョブ (`BP_PURCHASE_SYNC`, `PAYMENT_SYNC`)**:
   - 優先度1: `t_bp_payment.cost_center_id` -> `m_cost_center.organization_id`。
   - 優先度2: `t_bp_payment.work_record_id` -> `t_contract.sales_user_id` の所属組織 (`t_user_organization` where `as_of = work_month` 末日)。
   - 導出不能時: NULL (全社共通)。
3. **経費ジョブ (`EXPENSE_DEAL_SYNC`)**:
   - 優先度1: `t_expense_request.engineer_id` -> `t_engineer.user_id` の所属組織 (`t_user_organization` where `as_of = expense_date`)。
   - 優先度2: `t_expense_request.project_id` -> `t_project` の担当組織。
   - 導出不能時: NULL (全社共通)。

### 5.2 Consumer Inventory & SQL 述語

| Consumer 機能 | HTTP Method / 契機 | 管理者 (`ROLE_管理者`) SQL 述語 | マネージャー (`ROLE_マネージャー`) SQL 述語 | 営業 / HR / 要員 |
|---|---|---|---|---|
| 接続一覧 / 詳細 | `GET /api/accounting/connections` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND (legal_entity_id IS NULL OR legal_entity_id IN (#{allowedLegalEntities}))` (トークン非表示) | 403 |
| マッピング一覧 | `GET /api/accounting/mappings` | `WHERE connection_id = ?` | `WHERE connection_id IN (allowed_connections)` | 403 |
| マッピング検証 / 編集 | `POST/PUT /api/accounting/mappings` | 実行可 | 不可 (403) | 403 |
| 送信プレビュー | `GET /api/accounting/preview` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| ジョブ一覧 / カウント | `GET /api/accounting/jobs` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| ジョブ詳細 | `GET /api/accounting/jobs/{id}` | `WHERE id = ? AND tenant_id = #{tenantId}` | `WHERE id = ? AND tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| ジョブ再試行 / 取消 | `POST /api/accounting/jobs/{id}/retry|cancel` | `WHERE id = ? AND tenant_id = #{tenantId}` | `WHERE id = ? AND tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` (SQL境界で検証) | 403 |
| 月次照合 / エクスポート | `GET /api/accounting/reconciliation` | `WHERE tenant_id = #{tenantId}` | `WHERE tenant_id = #{tenantId} AND organization_id IN (#{allowedOrgIds})` | 403 |
| 障害・照合通知 | イベント通知契機 | 管理者全員に配信 | ジョブの `organization_id IN (#{allowedOrgIds})` を満たすマネージャーに限定配信 | 403 |
| スケジューラ / Worker | バックグラウンド実行 | システム Principal (try-finally で `TenantContext` を設定・完全解除) | — | — |

- **空集合ガード**: マネージャーの `allowedOrgIds` が空集合（無所属）の場合、`WHERE 1 = 0` を付加し DB レベルで 0 件を返却。

---

## 6. 決定表

### 6.1 時間・asOf 決定表 (Time & asOf Model)

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| **connection token** | `encrypted_tokens` + `token_version` + `expires_at` | 監査ログ (`t_audit_log`) | — | 現在値 (`TenantContext.getTimezone()`) | 未接続。**外部送信即時停止** (R1.3) |
| **refresh lease** | `refresh_lease_token` + `refresh_lease_expires_at` | — | — | DB 現在時刻 (`NOW()`) | リース非保有 (待機中) |
| **mapping** | `m_external_mapping.verified_at` あり | 上書き更新 (版なし) | `payload_snapshot` (検証時 allow-list JSON) | 現在値 | **未検証**。送信前バリデーションで停止 (R1.3) |
| **job payload** | — | `t_integration_job_event` | `payload_snapshot` (不変 canonical JSON byte 列) | job enqueue 時点 | レガシージョブ (再試行時新規作成) |
| **BP / 経費業務日付** | — | — | テナントタイムゾーンにおける `work_month` 末日 (`YYYY-MM-DD`) および契約支払期日 | `work_month` に基づく固定日 | — |
| **外部連携状態** | job から**導出** | job event | — | 現在値 | job 無し＝**未送信** |
| **支払実績** | 外部同期値 (金額 + 決済日) | — | 照合時の金額 / 日付 | 同期実行時点 | 未決済 |
| **月次照合** | 都度計算 | 保存しない | — | 対象月 `[start_date, end_date + 1day)` 半開区間 (`TenantContext.getTimezone()`) | — |
| **job lease** | `lease_token` + `lease_expires_at` | — | — | DB 現在時刻 (`NOW()`) | リース非保有 |

- **Timezone 規約**: テナント設定から解決した `ZoneId`（`TenantContext.getTimezone()`、既定: `Asia/Tokyo` `+09:00`）を用いて月境界・業務日付を計算。
- **確定日付ルール**: BP 仕入の canonical payload は `YearMonth.parse(workMonth).atEndOfMonth()` を固定設定。翌日再試行でも `payload_hash` が一切変動しない。

---

### 6.2 月次照合 (4母集団・入金一意キー・手数料計算・Fail-Closed) 決定表

| 母集団種別 | 内部対象テーブル・抽出条件 | freee 外部対象 | 照合キー | 金額突合単位・計算式 | 照合ルール |
|---|---|---|---|---|---|
| **売上** | `t_invoice` (`status IN ('送付済', '入金済', '一部入金')`) | `deals` (type=`income`) | `ref_number` (内部 `invoice_no`) | `invoice.total` vs `deal.amount` | 金額完全一致で `MATCHED`、不一致は `AMOUNT_MISMATCH` |
| **仕入** | `t_bp_payment` (`status IN ('未払', '承認済', '支払済')`) | `deals` (type=`expense`) | `ref_number` (内部 `BP_PAYMENT:{id}`) | `bp_payment.amount` vs `deal.amount` | 金額完全一致で `MATCHED` |
| **入金** | `t_invoice_payment` | `payments` (income deal 配下) | `{externalDealId}:{paymentId}` または決済金額 + 入金日 | `invoice_payment.amount + COALESCE(invoice_payment.fee, 0)` vs `payment.amount` | 振込手数料を含む総消込額が一致で `MATCHED` (同日複数入金も 1:1 突合) |
| **経費** | `t_expense_request` (`status IN ('承認済', '会計連携済')`) | `deals` (type=`expense`, 経費) | `ref_number` (内部 `expense_no`) | `expense.amount` vs `deal.amount` | 金額完全一致で `MATCHED` |

- **入金 1:1 突合規則**:
  - 外部決済の `payment_id` が判明している場合は `{externalDealId}:{paymentId}` で完全 1:1 結合。
  - 未突合決済は同日・同額の消込（`amount + fee`）と順次 1:1 で引当て、1つの外部決済を複数回二重突合させない（多重マッチ時は `PAYMENT_AMBIGUOUS` で fail-closed）。
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
