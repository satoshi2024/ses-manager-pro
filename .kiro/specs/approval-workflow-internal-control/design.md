# Design — 承認ワークフロー・内部統制

> Test実行範囲は `test-execution-policy-s03-s17.md` のL0〜L5を正とし、通常Taskは定向・直接回帰、M taskで全量を行う。

## 1. DDL（予約V71）

- `m_approval_route(id, tenant_id, request_type, organization_id, min/max_amount, version_no,
  valid_from/to, active_flag)`。
- `m_approval_route_step(route_id, step_no, parallel_group, approver_type, approver_value, sla_hours)`。
- `t_approval_request(id, request_no, request_type, target_type/id, target_version, applicant_id,
  organization_id, amount_snapshot, payload_json, diff_json, route_snapshot_json, status, current_step,
  requested_at, finalized_at, idempotency_key, version)`。
- `t_approval_action(request_id, step_no, approver_user_id, action, comment, delegated_from, acted_at)`。
- `t_approval_delegation(from_user_id, to_user_id, valid_from/to, request_types_json, approved_by)`。

payload/diffはPII最小化し、対象全entityをserializeしない。adapterが許可fieldだけをsnapshotする。

## 2. Adapter

`ApprovalTargetAdapter`:

```java
RequestType type();
ApprovalSnapshot snapshot(targetId, command);
void validateBeforeRequest(...);
void applyApproved(ApprovalRequest request);
```

見積、契約、請求、BP支払、月次締めの5adapter。`applyApproved`は既存service methodへ委譲し、
`approval_request.id`を冪等sourceとして渡す。

## 3. Engine

- `request`, `approve`, `reject`, `returnForRevision`, `withdraw`, `resolveApprovers`, `escalate`。
- 条件付きUPDATEでcurrent step/status/versionをCAS。
- 並列groupは全員承認で次へ、1人却下で終端。代理は元承認者をactionへ残す。
- 最終承認transaction: request lock→target version再検証→adapter apply→request approved→outbox。

## 4. 対象API変更

対象操作buttonは「実行」から「申請」へ変更。直接endpointはpermission `*.approve.bypass`を作らず、
system migration以外はengine経由のみ。既存API互換が必要なら同URLが申請を返す形へ変更し、レスポンスに
`approvalRequestId/pending`を返す。

## 5. UI

- `/approval/inbox`, `/approval/requests`, `/approval/routes`。
- 差分はfield label、before/after、金額単位、機密mask。
- 対象画面に申請状態badgeと履歴link。

## 6. テスト

route resolution、金額境界、自己承認、代理期間、並列、競合、二重承認、apply rollback、outbox、
対象5adapterの単件service回帰。
