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

- `OrganizationService`: tree、期間重複、循環防止、異動。属性更新は同一IDのversion CAS
  （行を分岐させると所属・cost center・予算・snapshotが旧IDへ取り残される）。統合は
  子組織・在籍所属・要員の所属組織・cost center・当月以降の予算を統合先へ付け替え、
  過去月の予算と月次snapshotは動かさない。退職・停止時は有効な所属を締め、上長参照を外す。
- `OrganizationScopeService`: current userのdescendant IDsと既存DataScopeの結合。
- `ManagementAccountingService`: 既存`MonthlyRevenueCalcService`の金額口径を再利用し、独自再計算禁止。
- `BudgetService`: upsert、CSV、楽観ロック。

### 帰属の解決順（管理会計・組織scope共通）

1. `t_engineer.organization_id`（要員自身の所属組織）
2. `t_engineer_account_link` → 対象日時点の主所属

アカウント連携は要員セルフサービスを使う要員にしか存在しないため、連携を必須にすると
大半の実績が「未配賦」になり R2.2/R2.3/R4 が成立しない。1 を正とし 2 はフォールバックに限る。

### 組織scopeとDataScopeの結合規則（R3.2の明文化）

| ロール | 業務データ（要員/契約/請求/顧客/案件/勤怠/通知/dashboard/export） | 組織マスタ・cost center・予算・snapshot |
|---|---|---|
| 管理者 | 全件 | 全件 |
| マネージャー（部門責任者） | 主所属組織＋子孫 ∩ DataScope。`manager_user_id` の直属ユーザーは個人単位で追加許可 | 主所属組織＋子孫 |
| 営業 / HR / 要員 | **既存のrole・DataScopeの範囲のまま**。組織で追加的に絞らない | 全件（menu権限で到達可否を制御） |

営業部の営業が技術部所属の要員の契約を担当するのは通常運用であり、そこへ組織を重ねて積集合を
取ると自分の担当データが0件になる。R3.1が求めるのは「一般ユーザーは既存role/data scope範囲」
であって追加制限ではない。メニュー権限は独立した認可ゲートとして常に併用する。

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

