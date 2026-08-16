# Design — 会計・支払連携

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V106）

- `m_integration_connection(id, tenant_id, legal_entity_id, provider, product, external_company_id,
  encrypted_tokens, expires_at, status, version)`。既存`t_freee_connection`を段階移行。
- `m_external_mapping(connection_id, object_type, internal_id/code, external_id/code, payload_snapshot, verified_at)`。
- `t_integration_job(id, connection_id, job_type, target_type/id, idempotency_key, payload_hash,
  status, attempt_count, next_retry_at, external_id, provider_request_id, error_code/message_safe, version)`。
- `t_integration_job_event(job_id, from/to_status, occurred_at, safe_detail)`。
- invoice/BP payment/expenseへexternal sync status/idはjob参照から導出し、重複列を最小化。

## 2. Adapter

- `AccountingProvider` interface: validateMappings, upsertSales, cancelSales, upsertPurchase, fetchPayments, fetchObject。
- `FreeeAccountingProvider`はofficial API schema DTOを分離し、raw Mapを業務serviceへ漏らさない。
- plan/API unavailable時`CsvAccountingExportProvider`で同じcanonical DTOをCSV出力。

## 3. Job/outbox

- business transactionはoutbox/job insertまで。workerはDB lock/CASでclaim。
- exponential backoff+jitter、max attempts、manual retry。request/response本文をPII込みで保存せずhash/safe summary。
- provider request ID（freee `X-Freee-Request-ID`）を保存。

## 4. Mapping/preview

- 財務管理画面でmapping、送信preview、税/部門/取引先を確認。
- canonical amount/tax合計とprovider responseを送信後照合。不一致はsucceededにしない。

## 5. Reconciliation

- 月次の内部source ID↔external ID matrix。外部のみは自動内部作成せず、link/ignore理由を人が確定。

## 6. 決定表

既定解は `customer-product-expansion-2026/platform-invariants.md`（特に§7 外部連携）。
ここには本spec固有の行と逸脱だけを書く。

### 6.1 時間・asOf

| 対象 | current | history | snapshot | asOfで読む源 | 明示NULLの意味 |
|---|---|---|---|---|---|
| connection token | `encrypted_tokens`＋`expires_at` | rotation履歴 | — | 現在値 | 未接続。**送信を止める** |
| mapping | `m_external_mapping.verified_at`あり | 上書き（版なし） | `payload_snapshot`＝検証時の外部オブジェクト | 現在値 | **未検証**。送信前validationで止める（R1.3） |
| job payload | — | `t_integration_job_event` | `payload_hash`＝送信時点で固定 | job作成時点 | — |
| 外部連携状態 | jobから**導出** | job event | — | 現在値 | job無し＝**未送信**（成功でも失敗でもない） |
| 支払実績 | 外部sync値 | — | 照合時の金額/日付 | sync時点 | 未入金 |
| 月次照合 | 都度計算 | 保存しない | — | 対象月 | — |

- `verified_at IS NULL`のmappingを「マッピング不要」と扱わない。**未検証**であり、
  送信前validationで停止する。外部へ不完全な伝票を作らない（R1.3）。§1.1に該当。
- 外部連携状態はjobから導出し、invoice/BP payment側へ重複列を持たない（design §1）。
  重複列を置くとjobと業務側で状態が食い違う。

### 6.2 主体 × 操作 × 可見母集団

| 主体 | list/detail/count | export/download | notification | scheduler/async |
|---|---|---|---|---|
| 管理者 | 全件。connection/mapping編集可 | 全件 | 全障害 | job worker、支払sync |
| 財務担当（permission） | 全件のjob/mapping/照合。**tokenは不可視** | 同左 | validation error、照合差異 | — |
| マネージャー | 自組織に関わるjobの状態のみ。**mapping編集不可** | 同左 | — | — |
| 営業 | **不可視**（会計連携は業務担当の範囲外） | — | — | — |
| HR / 要員 | 不可視 | — | — | — |
| portal user | 不可視 | — | — | — |
| scheduler / job worker principal | 全件 | — | 宛先は財務/管理者 | claim/retry/sync |

- **tokenと秘密情報は誰にも表示しない。** 管理画面でも接続状態（有効/期限切れ）のみ。
  `encrypted_tokens`をAPIレスポンスへ含める経路を作らない。
- **秘密の非ログ出力をtestで固定する**（§7）。log capture testで
  token/refresh token/client secretが出ないことをassertする。design §6の`secret log capture`。
- job workerはHTTP requestの外で動く。**request scopeのserviceを呼ばない**（§3.3）。
  tenant/legal entity contextはjob行から明示的に設定し、`finally`で解除する。

### 6.3 状態機械と競合

| 状態 | 許可遷移 | 防重手段 | competing writer | rollback |
|---|---|---|---|---|
| job pending | →running | **DB lock/CAS で claim**（`WHERE status='pending'`） | 複数worker | pendingへ戻す |
| running | →succeeded / →retryable / →failed / →cancelled | 状態CAS＋`attempt_count` | worker二重起動 | retryableへ |
| retryable | →running（`next_retry_at`到来後） | 状態CAS | — | — |
| failed | →running（手動retry） | 状態CAS | — | — |
| succeeded | 終端 | `UNIQUE(idempotency_key)` | 再送 | **取消jobを新規作成**（物理削除しない、R2.3） |
| cancelled | 終端 | — | — | 新規job |

**エラー分類（§7の既定を本specの具体値へ）**:

| provider応答 | 分類 | 挙動 |
|---|---|---|
| 200 | succeeded | 金額/税合計をresponseと照合。**不一致ならsucceededにしない**（design §4） |
| 400 / 422 validation | failed（人手修正待ち） | **retryしない**。mapping/入力の修正を促す |
| 401 | retryable（1回だけ） | token refresh **1回**。再度401なら failed |
| 403 plan制限 | failed | CSV fallbackへ誘導。無限retryしない |
| 429 | retryable | backoff＋jitter。`Retry-After`を尊重 |
| 5xx / timeout | retryable | backoff＋jitter、max attemptsで failed |

- **冪等キー**: `idempotency_key`にUNIQUE。`payload_hash`が異なる同一keyの再送は**拒否**する
  （同keyで別payloadを送らせない）。10回再実行して外部1件（R6）。
- **token race**（design §6）: 複数jobが同時に401 → 同時refresh すると片方のtokenが無効化される。
  refreshはconnection行のロック下で1回だけ行い、他は新tokenを読み直す。
- **外部のみの取引を自動で内部作成しない**（R5.1、design §5）。link/ignoreを人が確定する。
- **DB transaction内でHTTPを呼ばない**（R4.1、§3.3）。business transactionはjob insertまで。

## 7. テスト

WireMock 200/400/401/403 plan/429/500/timeout、token race、idempotency、mapping不足、payload hash、
CSV fallback、月次照合、secret log capture、tenant/legal entity。

