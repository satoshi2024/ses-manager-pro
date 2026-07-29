# Design — 要員セルフサービスポータルV2

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V74）

- `t_engineer_change_request(id, engineer_id, request_type, payload_json, diff_json, status,
  approval_request_id, applied_at, version)`。
- `t_expense_request(id, engineer_id, expense_no, expense_date, category, amount, customer_id,
  project_id, description, receipt_document_id, status, approval_request_id, accounting_job_id, paid_at, version)`。
- `t_one_on_one_request(id, engineer_id, counterpart_user_id, candidate_dates_json, scheduled_at,
  status, employee_visible_note, private_note_ref)`。
- `m_survey_template`, `t_survey_campaign`, `t_survey_response`（回答単位、visibility、consent/version）。
- skill sheet確認日/確認versionは既存document/engineer linkへ追加。

## 2. Apply adapter

- `EngineerChangeRequestService`はfield allowlistとtype別DTOを使い、任意JSON→entity反映を禁止。
- approval finalで既存Engineer/Skill/Career serviceをtransaction内に呼ぶ。target version競合時再申請。
- contact等の機密fieldは暗号化/field permissionに従う。

## 3. Payroll

- `FreeePayrollApiController`の管理APIを本人用に再利用せず、`/api/my/payroll`専用endpoint。
- engineer-account linkから本人を解決し、request engineerIdを受け取らない。
- sensitive responseは`Cache-Control: no-store`、再認証時刻/MFA contextを検証。

## 4. Expense

- approval adapter `EXPENSE_REQUEST`、archive receipt、accounting outbox。
- 金額は円、税区分/勘定科目は会計側mapping。本人が任意科目codeを送れない。

## 5. Survey/privacy

- 定型scaleと任意commentを分離。private noteは通常RetentionRisk DTOへ出さない。
- 集計は少人数組織の匿名性を守る最低回答数config。

## 6. UI/test

- `/my` dashboard、profile/skill/career/payroll/expense/1on1/survey。
- 本人scope parameterized test、apply競合、receipt ACL、payroll no-store/MFA、匿名閾値、mobile。

