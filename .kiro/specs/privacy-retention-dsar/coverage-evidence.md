# mechanical source coverage evidence

実行日: 2026-08-27（Asia/Tokyo）
実行方式: read-only。DB、filesystem、backup/replica、HTTP、外部providerへの接続なし。

## command

```powershell
pwsh -NoProfile -File .\tools\privacy-retention-dsar\inventory-coverage.ps1
```

## result

| field | value |
|---|---:|
| status | `BLOCKED_COVERAGE_INCOMPLETE` |
| exit code | `2` |
| migration file / table / CREATE column record / ALTER column record | `116 / 180 / 4,220 / 114` |
| entity table | `176` |
| provider/gateway/file/backup/restore/export/search/cache/audit/integration candidate file | `123` |
| explicit inventory record（DB / FILE / AI） | `79（62 / 10 / 7）` |
| explicit table match / unmapped table | `102 / 78` |
| providerCallCount / writeCount | `0 / 0` |
| inventory SHA-256 | `b0760b7337380f018f79bd7d07e6bf5c7e83e454c0a9bb123031b48a52ed6548` |
| source manifest SHA-256 | `6137cb504d8e17290727249de4d4808052d6c5d41c92c59ec25ac13f8a8d4cc3` |

inventory SHAは対象inventoryそのもの、source manifest SHAはmigration table/column・entity・provider候補の正規化列をハッシュした値である。固定値をinventory自身へ埋め込まず、同じcommandのstdoutと本ファイルを証跡にする。

## unmapped tables（全件 UNKNOWN/BLOCKED）

明示的なtable/column/provider rowがない対象、またはwildcard/group表記だけの対象はcompletion候補にしない。以下はscannerが列挙した未マップtableである。

```text
m_approval_route
m_approval_route_step
m_compliance_external_reviewer_type
m_compliance_mapping_review_requirement_group
m_compliance_mapping_review_requirement_type
m_compliance_mapping_source
m_compliance_mapping_version
m_compliance_verification_method
m_compliance_verification_source
m_contract_template
m_cost_center
m_document_type
m_external_mapping
m_lifecycle_template
m_lifecycle_template_task
m_lifecycle_template_task_dep
m_menu
m_organization_unit
m_overtime_agreement
m_permission_group
m_portal_organization
m_saved_view
m_survey_template
m_system_config
m_work_calendar
m_work_calendar_day
m_workplace
shedlock
t_ai_evaluation
t_ai_feedback
t_ai_outcome
t_ai_recommendation_item
t_approval_action
t_approval_delegation
t_approval_delegation_type
t_approval_participant
t_approval_request
t_approval_responsibility
t_attendance_month
t_bp_evaluation
t_bp_price_negotiation
t_bp_terms
t_compliance_finding
t_compliance_mapping_approval_event
t_compliance_mapping_status_event
t_compliance_operation_ledger
t_compliance_responsible_assignment
t_compliance_snapshot_operation
t_contract_acceptance_backfill
t_contract_compliance_worker_state
t_digital_invoice
t_digital_invoice_event
t_document_hash_claim
t_employee_attendance_break
t_engineer_accounting_history
t_engineer_bp_affiliation
t_engineer_sales
t_integration_job_event
t_invoice_payment
t_leave_ledger
t_leave_request
t_lifecycle_case
t_lifecycle_event
t_lifecycle_evidence_link
t_lifecycle_task
t_lifecycle_task_dep
t_management_budget
t_monthly_accounting_dimension
t_organization_relation_history
t_overtime_followup
t_peppol_participant
t_permission_group_action
t_portal_user_permission
t_project_skill
t_role_menu
t_survey_campaign
t_task_notification_log
t_user_organization
t_user_permission_group
```

この未マップ一覧が0になるまで、PR-R1は完全達成ではない。法的保持、owner、purpose、policy version、hold、disposition、DSAR provider、result evidenceを推測で補完せず、F1-Mを開始しない。
