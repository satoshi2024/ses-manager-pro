# Design — 要員配置・需給計画

## 1. DDL（予約V70）

- `t_project_position(id, project_id, position_no, role_name, required_count, skills_json,
  unit_price_min/max, start/end_date, location, allocation_percent, priority, status, version)`。
- `t_allocation_plan(id, engineer_id, position_id NULL, allocation_type, start/end_date,
  allocation_percent, status, source_contract_id, exception_reason, approval_request_id, version)`。
- `t_staffing_scenario(id, owner_user_id, name, base_date, shared_flag, assumptions_json)`。
- `t_staffing_scenario_allocation(scenario_id, engineer_id, position_id, dates, percent)`。
- proposal/contractへposition_id。

## 2. Capacity service

- 日単位区間を月別FTEへ変換。境界はinclusive start/end、open endは計画window末。
- actual contractを優先表示、planは重ねる。二重計上をsource_contract_idで排除。
- leaveは稼働可能日数を減らすが、短期休暇で契約FTE自体を自動変更しない。

## 3. UI

- project detail position board、engineer timeline、需給heatmap、scenario compare。
- 既存analytics availability calendarとcontract ganttを再利用/リンクし、別計算口径を作らない。

## 4. Query/performance

- planning horizon最大24か月、position/engineer filter必須、大量gridはserver aggregate。
- 全engineer×全dayの直積をJava memoryへ作らない。

## 5. テスト

区間境界、FTE、100%競合、actual/plan排他、更新契約、leave、scenario isolation、scope、24か月上限。

