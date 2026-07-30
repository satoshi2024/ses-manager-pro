# Design — 雇用勤怠・休暇・時間外労働

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V74）

- `m_work_calendar(id, legal_entity_id, name, valid_from/to)`、`m_work_calendar_day(calendar_id,date,type,scheduled_minutes)`。
- `t_employee_attendance(id, engineer_id, work_date, clock_in/out, break_minutes, work_type,
  workplace_type, source, source_external_id, status, version)`。
- `t_attendance_month(engineer_id, work_month, totals..., overtime_minutes, status, submitted/approved/closed metadata)`。
- `t_leave_request(engineer_id, leave_type, start/end/date/time, requested_minutes, reason, status,
  approval_request_id)`。
- 本システム残数を正にする場合だけ`t_leave_ledger`。外部正ならmapping/cacheのみ。
- `m_overtime_agreement(legal_entity_id, valid_from/to, special_clause, limits/config)`。
- `t_overtime_followup(engineer_id, period, warning_code, status, notified_at, health_action_status)`。

時間は分の整数で保存し、表示時に時間へ変換。浮動小数を使わない。

## 2. Calculator

- `AttendanceCalculator`: 日次→月次、深夜/休日区分。
- `OvertimeComplianceCalculator`: rolling 2〜6か月、年、45h超月数。休日労働を含む/含まない定義を
  ruleごとに明示する。
- official boundary fixtureをtest resource JSONへ保存。

## 3. Integration

- `AttendanceProvider` interface: Internal/Freee。OAuth/refreshはaccounting adapterと共通基盤。
- syncは外部updated_at cursor+external IDで冪等。timezoneはAsia/Tokyo固定ではなくtenant設定。
- work record comparisonはread-only DTO、金額serviceへ接続しない。

## 4. UI

- `/my/attendance`, `/attendance`, `/leave`。
- calendar、月次集計、warning、差異、提出/承認/締め。
- managerはorganization scope、HRは法人scope、本人は自己のみ。

## 5. テスト

DST影響なしのJST日界、跨夜、休憩、休日、45/360/720/80/100境界、source of truth、sync retry、
leave残、closing、work record差異非連動。

