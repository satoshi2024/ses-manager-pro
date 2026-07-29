# Design — 注文・注文請・月次検収

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V69）

- `t_sales_order(id, tenant_id, legal_entity_id, order_no, customer_po_no, customer_id, contact_id,
  quotation_id, order_date, start/end_date, status, total_amount_snapshot, payment_terms_snapshot,
  source_document_id, acknowledgement_document_id, version)`。
- `t_sales_order_line(order_id, line_no, project_id, engineer_id, quantity, unit_price,
  settlement_min/max, amount, remarks)`。
- `t_contract.order_line_id`（UNIQUEで1明細→1契約、将来複数契約更新はrenewed chainで表現）。
- `t_acceptance(id, contract_id, work_record_id, work_month, status, submitted_at, customer_contact_id,
  accepted_at, reject_comment, document_id, version)`、`UNIQUE(contract_id,work_month)`。
- `t_contract.acceptance_required` default true。

## 2. Service

- `SalesOrderService`: number、状態、見積差分、contract draft。
- `AcceptanceService`: create/submit/accept/reject/cancel、work record version/amount snapshot。
- Contract draftは既存`buildAndSaveDraft`相当の共通経路へorder sourceを追加。
- Invoice generate queryへacceptance conditionをSQLで追加し、memory filter禁止。

## 3. Document

- 受領注文書は`ORDER_RECEIVED`、注文請書は`ORDER_ACKNOWLEDGEMENT`、検収書は`ACCEPTANCE`。
- 注文請書PDFはcompany/legal entity、注文条件、明細、顧客PO参照を印字。

## 4. UI/API

- `/sales-order`, `/acceptance`。注文一覧/詳細/差分、月次検収grid。
- quotationに「注文draft」、contract/work-recordに注文/検収status link。
- approval specのadapter: 条件差異、注文取消、検収取消。

## 5. テスト

PO/hash重複、状態、複数明細、契約冪等、差分、acceptance CAS、invoice guard、monthly closing、document ACL。

