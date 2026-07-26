# Design — 組織・管理会計

## 1. DDL（予約V60）

- `m_organization_unit(id, tenant_id, legal_entity_id, code, name, type, parent_id, valid_from/to, status, version)`。
- `t_user_organization(id, user_id, organization_id, position_name, manager_user_id, primary_flag, valid_from/to)`。
- `m_cost_center(id, legal_entity_id, code, name, organization_id, valid_from/to, status)`。
- `t_management_budget(id, organization_id, cost_center_id, budget_month, revenue, gross_profit,
  utilization_count, hire_count, version)`。
- `t_monthly_accounting_dimension(work_month, source_type, source_id, organization_id, cost_center_id,
  sales_user_id, revenue, cost, snapshot_at)` または既存月次確定時snapshot列。二重金額台帳を作らないため、
  金額は既存work_record/invoiceを正、dimensionは帰属と確定snapshotだけ持つ。

## 2. Service

- `OrganizationService`: tree、期間重複、循環防止、異動。
- `OrganizationScopeService`: current userのdescendant IDsと既存DataScopeの結合。
- `ManagementAccountingService`: 既存`MonthlyRevenueCalcService`の金額口径を再利用し、独自再計算禁止。
- `BudgetService`: upsert、CSV、楽観ロック。

## 3. UI/API

- `/organization`: tree + 詳細 + 所属/上長/原価部門。
- `/management-accounting`: 月/法人/組織/cost center filter、予実、drilldown。
- `/api/organizations/**`, `/api/management-accounting/**`。
- user管理へ主所属追加、engineer/contractへcost center表示。

## 4. snapshot

月次締め時に対象月の帰属をsnapshotし、reopen時は既存snapshotを維持するか再算定するかを確認画面で選ばせず、
原則維持。明示的な「帰属訂正」権限と監査理由でのみ変更する。

## 5. テスト

階層循環、期間重複、異動前後、上長scope、全社=子組織合計、既存金額口径一致、CSV formula injection。

