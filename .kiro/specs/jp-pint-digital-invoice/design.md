# Design — JP PINTデジタルインボイス

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V76）

- `t_peppol_participant(owner_type/id, scheme_id, participant_id, provider, verified_at, status)`。
- `t_digital_invoice(id, invoice_id NULL, direction, profile, specification_version, message_id,
  provider_message_id, xml_document_id, validation_document_id, status, sent/received_at, version)`。
- `t_digital_invoice_event(digital_invoice_id, provider_event_id, event_type, event_at, payload_hash, signature_valid)`。
- customerへdelivery preference。

## 2. Canonical model

- `CanonicalInvoice`は既存invoice snapshotから生成するimmutable DTO。
- `JpPintRenderer`はversion別renderer/validator。XML libraryはXXE無効、安全なnamespace handling。
- 金額assert: line+tax+rounding=total。差があれば送信拒否。

## 3. Provider adapter

- `DigitalInvoiceProvider`: verifyParticipant, send, getStatus, parseWebhook, downloadReceived。
- HTTP/job/error/idempotencyはaccounting integration基盤を再利用。
- webhookはraw body hash/署名検証後、provider event IDで冪等。古いeventで終端statusを巻き戻さない。

## 4. 受信

- XML security parse→schema validate→archive→supplier/amount/date/PO match→review queue。
- BP purchase作成はreview確定後にaccounting canonical DTOへ渡す。

## 5. テスト

公式fixture/golden XML、XXE、rounding、participant、provider status、webhook signature/order/duplicate、
受信duplicate/照合、spec version切替、PDF fallback。

