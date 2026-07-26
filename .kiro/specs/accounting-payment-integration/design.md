# Design — 会計・支払連携

## 1. DDL（予約V73）

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

## 6. テスト

WireMock 200/400/401/403 plan/429/500/timeout、token race、idempotency、mapping不足、payload hash、
CSV fallback、月次照合、secret log capture、tenant/legal entity。

