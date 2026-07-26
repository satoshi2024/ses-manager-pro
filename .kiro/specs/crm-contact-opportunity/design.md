# Design — CRM複数担当者・商機管理

## 1. DDL（予約V65）

- `t_customer_contact(id, customer_id, name, name_kana, department, position, roles_json,
  email, phone, primary_flag, valid_from/to, status, version)`。
- `t_lead(id, company_name, contact_name/email/phone, source, owner_user_id, status,
  converted_customer_id/opportunity_id, version)`。
- `t_opportunity(id, customer_id, title, stage, expected_start_month, duration_months, required_count,
  unit_price, expected_amount, probability, owner_user_id, next_action_date, competitor, lost_reason,
  converted_project_id/quotation_id, version)`。
- `t_sales_activity.contact_id/opportunity_id/assignee_user_id`。

## 2. 状態/変換

- `OpportunityService`状態機械と`convertToProject/Quotation`。
- opportunity IDをproject/quotation source列へ保存しUNIQUEで冪等。
- 受注/失注後は活動追記可、主要金額/顧客/stageは編集不可。

## 3. Contact利用

- quotation/contract/invoice/documentの宛先はcontact IDを任意指定し、名称/email snapshotを保存。
- 既存単一contact fieldはmigration後read compatibility、write禁止。

## 4. UI

- customer detailへcontacts/opportunities/activities統合timeline。
- `/crm/opportunities` kanban/list、`/crm/leads`。
- next actionからtask specへtask作成。

## 5. KPI/forecast

- opportunity見込は未変換openだけ。proposalへ変換済みは既存提案forecastへ移り二重計上しない。
- probabilityはstage default + override理由。

## 6. テスト

primary contact一意、期間、PII mask、stage、冪等変換、forecast排他、activity scope、duplicate candidate。
